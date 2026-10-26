package com.tm.storedprocedure.report;

import com.tm.storedprocedure.domain.OperationType;
import java.util.List;
import java.util.Map;

public record RoutineReport(
        String signature,
        String kind,
        OperationType classification,
        List<String> operations,
        List<String> parameters,
        String returnType,
        List<String> dependencies,
        String status,
        long durationMillis,
        List<TestCaseReport> testCases,
        List<String> reasons,
        String language,
        Map<String, List<String>> targetOperations,
        boolean internalTransactionControl,
        String outcome,
        List<String> tablesRead,
        List<String> tablesWritten,
        List<String> resultColumns,
        String remediationGuidance) {
    public RoutineReport {
        operations = List.copyOf(operations);
        parameters = List.copyOf(parameters);
        dependencies = List.copyOf(dependencies);
        testCases = List.copyOf(testCases);
        reasons = List.copyOf(reasons);
        tablesRead = List.copyOf(tablesRead);
        tablesWritten = List.copyOf(tablesWritten);
        resultColumns = List.copyOf(resultColumns);
        targetOperations = targetOperations.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
