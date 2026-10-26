package com.tm.storedprocedure.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Holds full row values for rows observed in verified routine writes during this run. */
@Component
public class GeneratedRecordRegistry {
    private final Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();

    public synchronized void add(String table, Map<String, Object> verifiedRowValues) {
        if (verifiedRowValues.isEmpty()) return;
        records.computeIfAbsent(normalizeTable(table), ignored -> new ArrayList<>()).add(Map.copyOf(verifiedRowValues));
    }
    public synchronized Optional<Object> find(String table, String column) {
        return records.getOrDefault(normalizeTable(table), List.of()).stream()
                .map(row -> row.get(column)).filter(java.util.Objects::nonNull).findFirst();
    }
    public synchronized Optional<Map<String, Object>> findRow(String table, List<String> columns) {
        return records.getOrDefault(normalizeTable(table), List.of()).stream()
                .filter(row -> row.keySet().containsAll(columns) && columns.stream().allMatch(column -> row.get(column) != null))
                .findFirst().map(Map::copyOf);
    }
    public synchronized List<Map<String, Object>> records(String table) {
        return records.getOrDefault(normalizeTable(table), List.of()).stream().map(Map::copyOf).toList();
    }
    public synchronized boolean owns(String table, Map<String, Object> key) {
        return records.getOrDefault(normalizeTable(table), List.of()).stream().anyMatch(row ->
                row.keySet().containsAll(key.keySet()) && key.entrySet().stream()
                        .allMatch(entry -> java.util.Objects.equals(row.get(entry.getKey()), entry.getValue())));
    }
    public synchronized int size() { return records.values().stream().mapToInt(List::size).sum(); }
    public synchronized void clear() { records.clear(); }
    public synchronized Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        records.forEach((table, rows) -> result.put(table, rows.stream().map(Map::copyOf).toList()));
        return Map.copyOf(result);
    }
    private static String normalizeTable(String table) {
        if (table == null) return "";
        String unquoted = table.replace("\"", "");
        int dot = unquoted.indexOf('.');
        if (dot < 0) return unquoted;
        // PostgreSQL schema names are commonly lowercase, but table identifiers are case
        // sensitive when quoted. Preserve table-name case to avoid merging distinct relations.
        return unquoted.substring(0, dot).toLowerCase(java.util.Locale.ROOT) + unquoted.substring(dot);
    }
}
