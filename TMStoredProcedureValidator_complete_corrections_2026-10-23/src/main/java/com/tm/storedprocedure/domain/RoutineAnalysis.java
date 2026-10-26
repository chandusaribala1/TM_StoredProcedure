package com.tm.storedprocedure.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record RoutineAnalysis(
        OperationType classification,
        Set<String> operations,
        Map<String, Set<String>> targets,
        Map<String, List<ColumnBinding>> parameterBindings,
        List<String> dependencySignatures,
        List<String> safetyReasons,
        boolean internalTransactionControl) {
    public RoutineAnalysis {
        operations = Set.copyOf(operations);
        targets = targets.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        parameterBindings = parameterBindings.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        dependencySignatures = List.copyOf(dependencySignatures);
        safetyReasons = List.copyOf(safetyReasons);
    }
    public boolean safe() { return safetyReasons.isEmpty(); }
    public boolean writes() { return operations.stream().anyMatch(Set.of("INSERT", "UPDATE", "DELETE", "MERGE")::contains); }
}
