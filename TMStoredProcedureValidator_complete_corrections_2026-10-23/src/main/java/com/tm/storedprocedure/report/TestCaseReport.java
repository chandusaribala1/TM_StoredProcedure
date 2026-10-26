package com.tm.storedprocedure.report;

import java.util.List;

public record TestCaseReport(
        String caseId,
        String variant,
        String status,
        List<GeneratedInputReport> generatedInputs,
        List<String> prerequisiteRecords,
        long durationMillis,
        boolean executionSucceeded,
        boolean verificationSucceeded,
        String verificationEvidence,
        Long affectedRows,
        String transactionOutcome,
        String sqlState,
        String errorDetails,
        String reason) {
    public TestCaseReport {
        generatedInputs = List.copyOf(generatedInputs);
        prerequisiteRecords = List.copyOf(prerequisiteRecords);
    }
}
