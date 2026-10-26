package com.tm.storedprocedure.metadata;

import com.tm.storedprocedure.analysis.RoutineAnalyzer;
import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import com.tm.storedprocedure.domain.RoutineResultColumn;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoutineRepository {
    static final String DISCOVERY_SQL = """
            SELECT p.oid, n.nspname, p.proname, p.prokind::text, l.lanname,
                   pg_get_function_identity_arguments(p.oid), pg_get_functiondef(p.oid),
                   p.prosrc, pg_get_function_result(p.oid), p.proretset,
                   p.provolatile::text, p.prosecdef, p.pronargdefaults
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            JOIN pg_catalog.pg_language l ON l.oid = p.prolang
            WHERE n.nspname = ? AND p.prokind IN ('f', 'p', 'w')
            ORDER BY p.proname, pg_get_function_identity_arguments(p.oid), p.oid
            """;

    private final JdbcTemplate jdbc;
    private final ValidatorProperties properties;
    private final RoutineAnalyzer analyzer;

    public RoutineRepository(JdbcTemplate jdbc, ValidatorProperties properties, RoutineAnalyzer analyzer) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.analyzer = analyzer;
    }

    public List<RoutineDefinition> discover() {
        List<RawRoutine> raw = jdbc.query(DISCOVERY_SQL, (rs, row) -> new RawRoutine(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getBoolean(10), rs.getString(11), rs.getBoolean(12), rs.getInt(13)),
                properties.getSchema());

        Set<Long> allOids = raw.stream().map(RawRoutine::oid).collect(java.util.stream.Collectors.toSet());
        List<RoutineDefinition> definitions = new ArrayList<>();
        for (RawRoutine item : raw) {
            List<RoutineParameter> parameters = parameters(item.oid(), item.defaultArgumentCount());
            Set<Long> dependencies = dependencies(item.oid(), allOids);
            definitions.add(new RoutineDefinition(item.oid(), item.schema(), item.name(), item.kind(), item.language(),
                    item.identityArguments(), item.definition(), item.source(), item.resultType(), item.returnsSet(),
                    item.volatility(), item.securityDefiner(), item.defaultArgumentCount(), parameters,
                    resultColumns(item.oid()), dependencies, null));
        }
        return analyzer.analyzeAll(definitions);
    }

    private List<RoutineParameter> parameters(long oid, int defaults) {
        List<ParameterRaw> raw = jdbc.query("""
                SELECT u.ordinality::integer,
                       COALESCE(p.proargnames[u.ordinality], ''),
                       COALESCE(p.proargmodes[u.ordinality]::text, 'i'),
                       pg_catalog.format_type(u.type_oid, NULL)
                FROM pg_catalog.pg_proc p
                CROSS JOIN LATERAL unnest(CASE WHEN p.proallargtypes IS NOT NULL
                    THEN p.proallargtypes ELSE p.proargtypes::oid[] END)
                    WITH ORDINALITY AS u(type_oid, ordinality)
                WHERE p.oid = ?
                ORDER BY u.ordinality
                """, (rs, row) -> new ParameterRaw(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)), oid);
        List<ParameterRaw> inputs = raw.stream().filter(p -> "i".equals(p.mode()) || "b".equals(p.mode()) || "v".equals(p.mode())).toList();
        int firstDefault = Math.max(0, inputs.size() - defaults);
        List<RoutineParameter> result = new ArrayList<>();
        for (ParameterRaw parameter : raw) {
            String name = parameter.name().isBlank() ? "arg" + parameter.position() : parameter.name();
            boolean isDefaulted = inputs.indexOf(parameter) >= firstDefault && defaults > 0 && inputs.contains(parameter);
            result.add(new RoutineParameter(parameter.position(), name, parameter.mode(), parameter.type(), isDefaulted));
        }
        return List.copyOf(result);
    }

    private Set<Long> dependencies(long oid, Set<Long> known) {
        List<Long> refs = jdbc.query("""
                SELECT DISTINCT d.refobjid
                FROM pg_catalog.pg_depend d
                WHERE d.classid = 'pg_catalog.pg_proc'::regclass
                  AND d.objid = ?
                  AND d.refclassid = 'pg_catalog.pg_proc'::regclass
                  AND d.refobjid IN (SELECT oid FROM pg_catalog.pg_proc WHERE pronamespace = (SELECT oid FROM pg_catalog.pg_namespace WHERE nspname = ?))
                  AND d.refobjid <> ?
                """, (rs, row) -> rs.getLong(1), oid, properties.getSchema(), oid);
        Set<Long> result = new LinkedHashSet<>();
        for (Long ref : refs) if (known.contains(ref)) result.add(ref);
        return Set.copyOf(result);
    }

    private List<RoutineResultColumn> resultColumns(long oid) {
        // OUT, INOUT and TABLE parameters are PostgreSQL's declared result-column
        // contract for record/SETOF record functions. Read this contract first so
        // these routines can be invoked and verified without guessing a result shape.
        List<RoutineResultColumn> declaredOutputs = jdbc.query("""
                SELECT COALESCE(NULLIF(p.proargnames[u.ordinality], ''),
                                'column' || u.ordinality::text),
                       pg_catalog.format_type(u.type_oid, NULL)
                FROM pg_catalog.pg_proc p
                CROSS JOIN LATERAL unnest(p.proallargtypes)
                    WITH ORDINALITY AS u(type_oid, ordinality)
                JOIN LATERAL unnest(p.proargmodes)
                    WITH ORDINALITY AS m(mode, ordinality)
                  ON m.ordinality = u.ordinality
                WHERE p.oid = ?
                  AND m.mode::text IN ('o', 'b', 't')
                ORDER BY u.ordinality
                """, (rs, row) -> new RoutineResultColumn(rs.getString(1), rs.getString(2)), oid);
        if (!declaredOutputs.isEmpty()) return List.copyOf(declaredOutputs);

        // Named composite return types have their result contract in pg_attribute.
        return jdbc.query("""
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod)
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_type t ON t.oid = p.prorettype
                JOIN pg_catalog.pg_attribute a ON a.attrelid = t.typrelid
                WHERE p.oid = ? AND t.typtype = 'c'
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """, (rs, row) -> new RoutineResultColumn(rs.getString(1), rs.getString(2)), oid);
    }

    private record RawRoutine(long oid, String schema, String name, String kind, String language,
                              String identityArguments, String definition, String source, String resultType,
                              boolean returnsSet, String volatility, boolean securityDefiner, int defaultArgumentCount) { }
    private record ParameterRaw(int position, String name, String mode, String type) { }
}
