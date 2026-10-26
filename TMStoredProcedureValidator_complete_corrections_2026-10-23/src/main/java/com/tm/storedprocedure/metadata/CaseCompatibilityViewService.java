package com.tm.storedprocedure.metadata;

import com.tm.storedprocedure.config.ValidatorProperties;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adds non-destructive, automatically-updatable lowercase compatibility views for mixed-case
 * base tables. They allow legacy PL/pgSQL bodies using unquoted names such as tm_application
 * to reach the actual public."TM_Application" table without renaming or copying that table.
 * The views are marked so metadata resolution always prefers the real base table.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CaseCompatibilityViewService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CaseCompatibilityViewService.class);
    private static final String MARKER = "TM_VALIDATOR_CASE_ALIAS:";
    private final JdbcTemplate jdbc;
    private final ValidatorProperties properties;

    public CaseCompatibilityViewService(JdbcTemplate jdbc, ValidatorProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isCompatibilityViewsEnabled()) {
            log.info("case_compatibility_views_disabled schema={}", properties.getSchema());
            return;
        }
        Integer version = jdbc.queryForObject("SELECT current_setting('server_version_num')::integer", Integer.class);
        if (version == null || version < 150000) {
            log.warn("case_compatibility_views_skipped reason=postgresql_15_security_invoker_required version={}", version);
            return;
        }
        List<BaseRelation> relations = jdbc.query("""
                SELECT c.relname, c.relkind::text
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind IN ('r','p')
                  AND c.relname <> lower(c.relname)
                  AND (SELECT count(*) FROM pg_catalog.pg_class x
                       JOIN pg_catalog.pg_namespace xn ON xn.oid = x.relnamespace
                       WHERE xn.nspname = n.nspname AND lower(x.relname) = lower(c.relname)
                         AND x.relkind IN ('r','p','v','m','f')) = 1
                  AND EXISTS (SELECT 1 FROM pg_catalog.pg_proc p
                       JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
                       WHERE pn.nspname = n.nspname
                         AND position(lower(c.relname) in lower(COALESCE(p.prosrc, ''))) > 0)
                ORDER BY c.relname
                """, (rs, row) -> new BaseRelation(rs.getString(1), rs.getString(2)), properties.getSchema());
        int created = 0;
        int skipped = 0;
        for (BaseRelation relation : relations) {
            String alias = relation.name().toLowerCase(Locale.ROOT);
            if (alias.equals(relation.name())) continue;
            List<String> existing = jdbc.query("""
                    SELECT c.relkind::text, COALESCE(pg_catalog.obj_description(c.oid, 'pg_class'), '')
                    FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ?
                    """, (rs, row) -> rs.getString(1) + "|" + rs.getString(2), properties.getSchema(), alias);
            if (!existing.isEmpty()) {
                // Do not overwrite user objects. Our own alias is already in place.
                if (existing.stream().anyMatch(v -> v.startsWith("v|" + MARKER))) skipped++;
                else skipped++;
                continue;
            }
            List<String> columns = jdbc.query("""
                    SELECT a.attname
                    FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
                    WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY a.attnum
                    """, (rs, row) -> rs.getString(1), properties.getSchema(), relation.name());
            List<String> lowerColumns = columns.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
            if (lowerColumns.stream().distinct().count() != lowerColumns.size()) {
                log.warn("case_compatibility_view_skipped schema={} table={} reason=column_names_collide_after_lowercase", properties.getSchema(), relation.name());
                skipped++;
                continue;
            }
            String qSchema = quote(properties.getSchema());
            String qAlias = quote(alias);
            String qBase = quote(relation.name());
            String projection = columns.stream().map(c -> quote(c) + " AS " + quote(c.toLowerCase(Locale.ROOT)))
                    .reduce((left, right) -> left + ", " + right).orElseThrow();
            jdbc.execute("CREATE VIEW " + qSchema + "." + qAlias
                    + " WITH (security_invoker = true) AS SELECT " + projection + " FROM " + qSchema + "." + qBase);
            jdbc.execute("COMMENT ON VIEW " + qSchema + "." + qAlias + " IS " + quoteLiteral(MARKER + relation.name()));
            created++;
        }
        log.info("case_compatibility_views_finished schema={} candidates={} created={} skipped={}",
                properties.getSchema(), relations.size(), created, skipped);
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static String quoteLiteral(String value) { return "'" + value.replace("'", "''") + "'"; }
    private record BaseRelation(String name, String kind) { }
}
