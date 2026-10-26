package com.tm.storedprocedure.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.domain.TableMetadata;
import com.tm.storedprocedure.metadata.TableMetadataService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded, in-memory row fingerprints. Raw row values are never logged or reported. */
@Service
public class RowSnapshotService {
    private final JdbcTemplate jdbc;
    private final TableMetadataService metadata;
    private final ValidatorProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    public RowSnapshotService(JdbcTemplate jdbc, TableMetadataService metadata, ValidatorProperties properties) {
        this.jdbc = jdbc; this.metadata = metadata; this.properties = properties;
    }

    public SnapshotSet capture(Iterable<String> tables) {
        Map<String, TableSnapshot> result = new LinkedHashMap<>();
        for (String tableName : tables) {
            if (result.size() >= properties.getMaxSnapshotTables()) throw new SnapshotLimitException("Write touches more tables than validator.max-snapshot-tables.");
            String[] q = split(tableName);
            TableMetadata table = metadata.describe(q[0], q[1]);
            if (table.primaryKey().isEmpty()) throw new SnapshotLimitException("Cannot prove row impact for " + tableName + " because it has no primary key.");
            String sql = "SELECT to_jsonb(t)::text FROM " + quote(q[0]) + "." + quote(q[1]) + " t LIMIT ?";
            List<String> rows = jdbc.query(sql, (rs, row) -> rs.getString(1), properties.getMaxSnapshotRowsPerTable() + 1);
            if (rows.size() > properties.getMaxSnapshotRowsPerTable()) throw new SnapshotLimitException("Cannot prove row impact for " + tableName + " within the configured snapshot row limit.");
            Map<String, RowRecord> records = new LinkedHashMap<>();
            for (String json : rows) {
                try {
                    JsonNode node = mapper.readTree(json);
                    Map<String, Object> key = new LinkedHashMap<>();
                    Map<String, Object> allValues = new LinkedHashMap<>();
                    node.fields().forEachRemaining(entry -> {
                        // Null-valued columns cannot be used as foreign-key candidates. Omit them
                        // so immutable registry snapshots remain null-safe while preserving every
                        // non-null value that may be a referenced unique key.
                        if (!entry.getValue().isNull()) allValues.put(entry.getKey(), primitive(entry.getValue()));
                    });
                    ArrayNode keyNode = mapper.createArrayNode();
                    for (String pk : table.primaryKey()) {
                        JsonNode value = node.get(pk);
                        if (value == null || value.isNull()) throw new SnapshotLimitException("Primary key metadata could not identify a stable key in " + tableName + ".");
                        Object primitive = primitive(value);
                        key.put(pk, primitive);
                        keyNode.add(value);
                    }
                    String identity = mapper.writeValueAsString(keyNode);
                    String hash = sha256(json);
                    if (records.put(identity, new RowRecord(hash, Map.copyOf(key), Map.copyOf(allValues))) != null) throw new SnapshotLimitException("Duplicate primary key observed while snapshotting " + tableName + ".");
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Unable to parse an internal row snapshot.", exception);
                }
            }
            result.put(tableName, new TableSnapshot(tableName, table.primaryKey(), records));
        }
        return new SnapshotSet(result);
    }

    public static Diff diff(TableSnapshot before, TableSnapshot after) {
        Map<String, RowRecord> inserted = new LinkedHashMap<>(), updated = new LinkedHashMap<>(), deleted = new LinkedHashMap<>();
        before.rows().forEach((id, oldRow) -> {
            RowRecord current = after.rows().get(id);
            if (current == null) deleted.put(id, oldRow);
            else if (!oldRow.hash().equals(current.hash())) updated.put(id, current);
        });
        after.rows().forEach((id, row) -> { if (!before.rows().containsKey(id)) inserted.put(id, row); });
        return new Diff(inserted, updated, deleted);
    }

    private static Object primitive(JsonNode node) {
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.bigIntegerValue();
        if (node.isNumber()) return node.decimalValue();
        return node.toString();
    }
    private static String sha256(String value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String[] split(String value) {
        int dot = value.indexOf('.');
        if (dot < 1 || dot == value.length() - 1) throw new IllegalArgumentException("Invalid qualified table identifier from analysis.");
        return new String[]{value.substring(0, dot), value.substring(dot + 1)};
    }
    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    public record RowRecord(String hash, Map<String, Object> primaryKey, Map<String, Object> values) {
        public RowRecord(String hash, Map<String, Object> primaryKey) { this(hash, primaryKey, primaryKey); }
        public RowRecord { primaryKey = Map.copyOf(primaryKey); values = Map.copyOf(values); }
    }
    public record TableSnapshot(String table, List<String> primaryKeyColumns, Map<String, RowRecord> rows) { }
    public record SnapshotSet(Map<String, TableSnapshot> tables) { }
    public record Diff(Map<String, RowRecord> inserted, Map<String, RowRecord> updated, Map<String, RowRecord> deleted) {
        public long total() { return (long) inserted.size() + updated.size() + deleted.size(); }
    }
    public static class SnapshotLimitException extends RuntimeException { public SnapshotLimitException(String message) { super(message); } }
}
