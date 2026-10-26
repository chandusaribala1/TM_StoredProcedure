package com.tm.storedprocedure.report;

import java.time.Instant;
import java.util.List;

public record RunReport(
        String runId,
        Instant startedAt,
        Instant completedAt,
        String database,
        String schema,
        String status,
        long durationMillis,
        int discoveredRoutines,
        int attemptedCases,
        int successfulCases,
        int verifiedCases,
        int committedWrites,
        int failedCases,
        int blockedRoutines,
        int skippedCases,
        List<String> runReasons,
        List<RoutineReport> routines,
        int analyzedRoutines,
        int attemptedRoutines,
        int verifiedRoutines,
        int failedRoutines,
        int partiallyVerifiedRoutines,
        int skippedRoutines,
        int readRoutinesVerified,
        int rolledBackWrites,
        long rowsChanged) {
    public RunReport(String runId, Instant startedAt, Instant completedAt, String database, String schema,
                     String status, long durationMillis, int discoveredRoutines, int attemptedCases,
                     int successfulCases, int verifiedCases, int committedWrites, int failedCases,
                     int blockedRoutines, int skippedCases, List<String> runReasons, List<RoutineReport> routines) {
        this(runId, startedAt, completedAt, database, schema, status, durationMillis, discoveredRoutines,
                attemptedCases, successfulCases, verifiedCases, committedWrites, failedCases, blockedRoutines,
                skippedCases, runReasons, routines,
                routines.size(), attemptedRoutineCount(routines), routineCount(routines, "VERIFIED"),
                routineCount(routines, "FAILED") + routineCount(routines, "UNVERIFIED"),
                routineCount(routines, "PARTIAL"), routineCount(routines, "SKIPPED"),
                (int) routines.stream().filter(r -> r.classification() == com.tm.storedprocedure.domain.OperationType.READ
                        && "VERIFIED".equals(r.status())).count(), rolledBackWriteCount(routines), changedRowCount(routines));
    }
    public RunReport {
        runReasons = List.copyOf(runReasons);
        routines = List.copyOf(routines);
    }

    private static int routineCount(List<RoutineReport> routines, String status) {
        return (int) routines.stream().filter(routine -> status.equals(routine.status())).count();
    }

    private static int attemptedRoutineCount(List<RoutineReport> routines) {
        return (int) routines.stream().filter(routine -> routine.testCases().stream()
                .anyMatch(test -> !"SKIPPED".equals(test.status()))).count();
    }

    private static int rolledBackWriteCount(List<RoutineReport> routines) {
        return (int) routines.stream().filter(routine -> routine.classification() == com.tm.storedprocedure.domain.OperationType.WRITE)
                .flatMap(routine -> routine.testCases().stream())
                .filter(test -> "ROLLED_BACK_UNVERIFIED".equals(test.transactionOutcome())
                        || "ROLLED_BACK_AFTER_FAILURE".equals(test.transactionOutcome()))
                .count();
    }

    private static long changedRowCount(List<RoutineReport> routines) {
        return routines.stream().filter(routine -> routine.classification() == com.tm.storedprocedure.domain.OperationType.WRITE)
                .flatMap(routine -> routine.testCases().stream())
                .filter(test -> test.verificationSucceeded() && "COMMITTED".equals(test.transactionOutcome()))
                .mapToLong(test -> test.affectedRows() == null ? 0 : test.affectedRows()).sum();
    }
}
