package com.tm.storedprocedure.domain;

import java.util.List;

public record TableMetadata(
        String schema,
        String name,
        List<TableColumn> columns,
        List<String> primaryKey,
        List<TableConstraint> constraints,
        List<ForeignKey> foreignKeys,
        boolean hasUserTriggers,
        boolean hasUserRules,
        boolean rowSecurityEnabled,
        String relationKind) {
    public TableMetadata(String schema, String name, List<TableColumn> columns, List<String> primaryKey,
                        List<TableConstraint> constraints, List<ForeignKey> foreignKeys,
                        boolean hasUserTriggers, boolean hasUserRules, boolean rowSecurityEnabled) {
        this(schema, name, columns, primaryKey, constraints, foreignKeys,
                hasUserTriggers, hasUserRules, rowSecurityEnabled, "r");
    }
    public TableMetadata {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        constraints = List.copyOf(constraints);
        foreignKeys = List.copyOf(foreignKeys);
        relationKind = relationKind == null ? "?" : relationKind;
    }
    public String qualifiedName() { return schema + "." + name; }
    public String relationType() {
        return switch (relationKind) {
            case "r" -> "table";
            case "p" -> "partitioned table";
            case "v" -> "view";
            case "m" -> "materialized view";
            case "f" -> "foreign table";
            default -> "unsupported relation kind '" + relationKind + "'";
        };
    }
    /** Exact match wins; a case-insensitive fallback is accepted only when unique. */
    public TableColumn column(String columnName) {
        TableColumn exact = columns.stream().filter(column -> column.name().equals(columnName)).findFirst().orElse(null);
        if (exact != null) return exact;
        List<TableColumn> matches = columns.stream()
                .filter(column -> column.name().equalsIgnoreCase(columnName)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }
}
