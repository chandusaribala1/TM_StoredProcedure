package com.tm.storedprocedure.metadata;

import com.tm.storedprocedure.domain.ForeignKey;
import com.tm.storedprocedure.domain.TableColumn;
import com.tm.storedprocedure.domain.TableConstraint;
import com.tm.storedprocedure.domain.TableMetadata;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TableMetadataService {
    private final JdbcTemplate jdbc;
    public TableMetadataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public TableMetadata describe(String schema, String table) {
        ResolvedRelation resolved = resolveRelation(schema, table);
        // All subsequent metadata queries use the exact identifiers returned by PostgreSQL.
        final String actualSchema = resolved.schema();
        final String actualTable = resolved.name();
        List<String> relationKinds = jdbc.query("""
                SELECT r.relkind::text
                FROM pg_catalog.pg_class r
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                WHERE n.nspname = ? AND r.relname = ?
                """, (rs, row) -> rs.getString(1), actualSchema, actualTable);
        if (relationKinds.isEmpty()) throw new IllegalArgumentException(unresolvedRelation(schema, table));
        String relationKind = relationKinds.getFirst();
        if (!Set.of("r", "p", "v", "m", "f").contains(relationKind)) {
            throw new IllegalArgumentException("UNSUPPORTED_RELATION_TYPE: " + schema + "." + table
                    + " exists with PostgreSQL relkind='" + relationKind + "'; row metadata for this object type is not supported.");
        }

        List<TableColumn> columns = jdbc.query("""
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),
                       NOT a.attnotnull, pg_get_expr(ad.adbin, ad.adrelid),
                       a.attidentity::text, a.attgenerated::text
                FROM pg_catalog.pg_class r
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                JOIN pg_catalog.pg_attribute a ON a.attrelid = r.oid
                LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid = r.oid AND ad.adnum = a.attnum
                WHERE n.nspname = ? AND r.relname = ?
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """, (rs, row) -> {
            String identity = rs.getString(5);
            return new TableColumn(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                    identity != null && !identity.isBlank(), "a".equals(identity) ? "ALWAYS" : "d".equals(identity) ? "BY DEFAULT" : "",
                    rs.getString(6) != null && !rs.getString(6).isBlank());
        }, actualSchema, actualTable);
        if (columns.isEmpty()) throw new IllegalArgumentException("RELATION_METADATA_UNAVAILABLE: " + schema + "." + table
                + " exists, but PostgreSQL returned no usable column metadata.");

        List<String> primaryKey = jdbc.query("""
                SELECT a.attname
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class r ON r.oid = i.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, position) ON true
                JOIN pg_catalog.pg_attribute a ON a.attrelid = r.oid AND a.attnum = k.attnum
                WHERE i.indisprimary AND k.position <= i.indnkeyatts AND n.nspname = ? AND r.relname = ?
                ORDER BY k.position
                """, (rs, row) -> rs.getString(1), actualSchema, actualTable);

        List<TableConstraint> constraints = jdbc.query("""
                SELECT c.conname,
                       CASE c.contype WHEN 'p' THEN 'PRIMARY KEY' WHEN 'u' THEN 'UNIQUE'
                         WHEN 'f' THEN 'FOREIGN KEY' WHEN 'c' THEN 'CHECK' WHEN 'x' THEN 'EXCLUSION'
                         ELSE c.contype::text END,
                       pg_catalog.pg_get_constraintdef(c.oid, true)
                FROM pg_catalog.pg_constraint c
                JOIN pg_catalog.pg_class r ON r.oid = c.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                WHERE n.nspname = ? AND r.relname = ?
                UNION ALL
                SELECT index_class.relname, 'UNIQUE', pg_catalog.pg_get_indexdef(i.indexrelid)
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class r ON r.oid = i.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                JOIN pg_catalog.pg_class index_class ON index_class.oid = i.indexrelid
                WHERE n.nspname = ? AND r.relname = ? AND i.indisunique AND NOT i.indisprimary
                  AND i.indpred IS NULL AND i.indexprs IS NULL
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint c WHERE c.conindid = i.indexrelid)
                ORDER BY 1
                """, (rs, row) -> new TableConstraint(rs.getString(1), rs.getString(2), rs.getString(3)),
                actualSchema, actualTable, actualSchema, actualTable);

        List<ForeignKey> foreignKeys = jdbc.query("""
                SELECT c.conname, la.attname, rn.nspname, rr.relname, ra.attname, c.confdeltype::text
                FROM pg_catalog.pg_constraint c
                JOIN pg_catalog.pg_class r ON r.oid = c.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                JOIN pg_catalog.pg_class rr ON rr.oid = c.confrelid
                JOIN pg_catalog.pg_namespace rn ON rn.oid = rr.relnamespace
                JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS k(local_attnum, ref_attnum, pos) ON true
                JOIN pg_catalog.pg_attribute la ON la.attrelid = r.oid AND la.attnum = k.local_attnum
                JOIN pg_catalog.pg_attribute ra ON ra.attrelid = rr.oid AND ra.attnum = k.ref_attnum
                WHERE c.contype = 'f' AND n.nspname = ? AND r.relname = ?
                ORDER BY c.conname, k.pos
                """, (rs, row) -> new ForeignKey(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), deleteAction(rs.getString(6))), actualSchema, actualTable);

        TableSafety safety = jdbc.queryForObject("""
                WITH RECURSIVE target_relations(oid) AS (
                    SELECT r.oid FROM pg_catalog.pg_class r
                    JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                    WHERE n.nspname = ? AND r.relname = ?
                    UNION ALL
                    SELECT i.inhrelid FROM pg_catalog.pg_inherits i
                    JOIN target_relations parent ON parent.oid = i.inhparent
                )
                SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t
                    WHERE t.tgrelid IN (SELECT oid FROM target_relations)
                      AND NOT t.tgisinternal AND t.tgenabled <> 'D'),
                       EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite rw
                    WHERE rw.ev_class IN (SELECT oid FROM target_relations) AND rw.rulename <> '_RETURN'),
                       EXISTS (SELECT 1 FROM pg_catalog.pg_class r
                    WHERE r.oid IN (SELECT oid FROM target_relations) AND r.relrowsecurity)
                """, (rs, row) -> new TableSafety(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)), actualSchema, actualTable);
        if (safety == null) throw new IllegalArgumentException("Table safety metadata is unavailable for " + schema + "." + table + ".");
        return new TableMetadata(actualSchema, actualTable, columns, primaryKey, constraints, foreignKeys,
                safety.hasTriggers(), safety.hasRules(), safety.rowSecurityEnabled(), relationKind);
    }

    private ResolvedRelation resolveRelation(String schema, String requestedName) {
        List<RelationCandidate> exact = jdbc.query("""
                SELECT n.nspname, r.relname, r.relkind::text
                FROM pg_catalog.pg_class r
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                WHERE n.nspname = ? AND r.relname = ?
                  AND r.relkind IN ('r', 'p', 'v', 'm', 'f')
                  AND COALESCE(pg_catalog.obj_description(r.oid, 'pg_class'), '') NOT LIKE 'TM_VALIDATOR_CASE_ALIAS:%'
                """, (rs, row) -> new RelationCandidate(rs.getString(1), rs.getString(2), rs.getString(3)),
                schema, requestedName);
        if (exact.size() == 1) {
            RelationCandidate c = exact.getFirst();
            return new ResolvedRelation(c.schema(), c.name(), c.kind());
        }
        List<RelationCandidate> candidates = jdbc.query("""
                SELECT n.nspname, r.relname, r.relkind::text
                FROM pg_catalog.pg_class r
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                WHERE n.nspname = ? AND lower(r.relname) = lower(?)
                  AND r.relkind IN ('r', 'p', 'v', 'm', 'f')
                  AND COALESCE(pg_catalog.obj_description(r.oid, 'pg_class'), '') NOT LIKE 'TM_VALIDATOR_CASE_ALIAS:%'
                ORDER BY r.relname
                """, (rs, row) -> new RelationCandidate(rs.getString(1), rs.getString(2), rs.getString(3)),
                schema, requestedName);
        if (candidates.size() == 1) {
            RelationCandidate c = candidates.getFirst();
            return new ResolvedRelation(c.schema(), c.name(), c.kind());
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("AMBIGUOUS_IDENTIFIER_MATCH: " + schema + "." + requestedName
                    + " matches multiple relations in schema " + schema + ": "
                    + candidates.stream().map(c -> quote(c.name())).toList());
        }
        throw new IllegalArgumentException(unresolvedRelation(schema, requestedName));
    }

    private String unresolvedRelation(String schema, String table) {
        List<RelationCandidate> candidates = jdbc.query("""
                SELECT n.nspname, r.relname, r.relkind::text
                FROM pg_catalog.pg_class r
                JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                WHERE ((n.nspname = ? AND lower(r.relname) = lower(?))
                   OR (n.nspname <> ? AND r.relname = ?))
                  AND COALESCE(pg_catalog.obj_description(r.oid, 'pg_class'), '') NOT LIKE 'TM_VALIDATOR_CASE_ALIAS:%'
                ORDER BY CASE WHEN n.nspname = ? THEN 0 WHEN r.relname = ? THEN 1 ELSE 2 END,
                         n.nspname, r.relname
                LIMIT 20
                """, (rs, row) -> new RelationCandidate(rs.getString(1), rs.getString(2), rs.getString(3)),
                schema, table, schema, table, schema, table);
        RelationCandidate caseCandidate = candidates.stream()
                .filter(candidate -> candidate.schema().equals(schema) && !candidate.name().equals(table))
                .findFirst().orElse(null);
        if (caseCandidate != null) {
            return "IDENTIFIER_CASE_MISMATCH: exact relation " + schema + "." + table + " does not exist; the catalog contains "
                    + quote(schema) + "." + quote(caseCandidate.name()) + " (relkind='" + caseCandidate.kind()
                    + "'). PostgreSQL folds unquoted identifiers to lowercase; correct the routine reference or quote the exact catalog name. No case-insensitive remapping was performed.";
        }
        List<String> otherSchemas = candidates.stream().filter(candidate -> candidate.name().equals(table)
                        && !candidate.schema().equals(schema))
                .map(RelationCandidate::schema).distinct().toList();
        if (!otherSchemas.isEmpty()) {
            return "WRONG_SCHEMA: exact relation " + table + " is absent from schema " + schema
                    + " but exists in schema(s) " + otherSchemas + ". Qualify the routine reference with the intended schema.";
        }
        if (!candidates.isEmpty()) {
            RelationCandidate candidate = candidates.getFirst();
            return "SCHEMA_OR_IDENTIFIER_CASE_MISMATCH: exact relation " + schema + "." + table
                    + " does not exist; a case-insensitive name match is " + quote(candidate.schema()) + "."
                    + quote(candidate.name()) + " (relkind='" + candidate.kind()
                    + "'). Correct the schema or exact identifier spelling; no remapping was performed.";
        }
        return "MISSING_RELATION: exact relation " + schema + "." + table
                + " does not exist in pg_catalog.pg_class. Check the routine reference and deployment schema.";
    }

    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }

    public boolean hasReferencingForeignKey(String referencedSchema, String referencedTable) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_constraint c
                    JOIN pg_catalog.pg_class r ON r.oid = c.confrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                    WHERE c.contype = 'f' AND n.nspname = ? AND r.relname = ?
                )
                """, Boolean.class, referencedSchema, referencedTable);
        return Boolean.TRUE.equals(result);
    }

    public List<String> enumValues(String schema, String typeName) {
        return jdbc.query("""
                SELECT e.enumlabel
                FROM pg_catalog.pg_type t
                JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                JOIN pg_catalog.pg_enum e ON e.enumtypid = t.oid
                WHERE n.nspname = ? AND t.typname = ?
                ORDER BY e.enumsortorder
                """, (rs, row) -> rs.getString(1), schema, typeName);
    }

    private static String deleteAction(String action) {
        return switch (action) { case "c" -> "CASCADE"; case "n" -> "SET NULL"; case "d" -> "SET DEFAULT"; case "r" -> "RESTRICT"; default -> "NO ACTION"; };
    }
    private record RelationCandidate(String schema, String name, String kind) { }
    private record ResolvedRelation(String schema, String name, String kind) { }
    private record TableSafety(boolean hasTriggers, boolean hasRules, boolean rowSecurityEnabled) { }
}
