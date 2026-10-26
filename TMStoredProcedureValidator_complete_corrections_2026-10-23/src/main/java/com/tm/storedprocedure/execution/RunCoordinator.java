package com.tm.storedprocedure.execution;

import com.tm.storedprocedure.analysis.ExecutionPlanner;
import com.tm.storedprocedure.analysis.RoutineAnalyzer;
import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.data.GeneratedRecordRegistry;
import com.tm.storedprocedure.data.TestCaseFactory;
import com.tm.storedprocedure.domain.ColumnBinding;
import com.tm.storedprocedure.domain.GeneratedInput;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineTestCase;
import com.tm.storedprocedure.domain.TableMetadata;
import com.tm.storedprocedure.metadata.RoutineRepository;
import com.tm.storedprocedure.metadata.TableMetadataService;
import com.tm.storedprocedure.report.GeneratedInputReport;
import com.tm.storedprocedure.report.JsonReportWriter;
import com.tm.storedprocedure.report.RoutineReport;
import com.tm.storedprocedure.report.RunReport;
import com.tm.storedprocedure.report.SafeErrors;
import com.tm.storedprocedure.report.SafeValueRenderer;
import com.tm.storedprocedure.report.TestCaseReport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RunCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);
    private static final Set<String> SAFE_DEFAULT_CALLS = Set.of("NEXTVAL", "CURRVAL", "LASTVAL", "NOW", "TRANSACTION_TIMESTAMP",
            "STATEMENT_TIMESTAMP", "CLOCK_TIMESTAMP", "GEN_RANDOM_UUID", "UUID_GENERATE_V4", "COALESCE", "NULLIF",
            "GREATEST", "LEAST", "LOWER", "UPPER", "TRIM", "BTRIM", "LENGTH", "CHAR_LENGTH", "ABS", "ROUND",
            "FLOOR", "CEIL", "CEILING", "MOD", "TO_CHAR", "TO_DATE", "TO_TIMESTAMP", "JSONB_BUILD_OBJECT",
            "JSON_BUILD_OBJECT", "ARRAY", "FORMAT", "MD5", "SHA256", "RANDOM", "TIMEZONE");
    private final JdbcTemplate jdbc;
    private final ValidatorProperties properties;
    private final RoutineRepository routines;
    private final TableMetadataService tableMetadata;
    private final ExecutionPlanner planner;
    private final TestCaseFactory testCases;
    private final GeneratedRecordRegistry generatedRecords;
    private final RoutineInvoker invoker;
    private final RowSnapshotService snapshots;
    private final PostgresRunLock runLock;
    private final JsonReportWriter reports;
    private final PlatformTransactionManager transactionManager;
    private final DataSourceProperties dataSourceProperties;
    private final AtomicBoolean inProcess = new AtomicBoolean();

    public RunCoordinator(JdbcTemplate jdbc, ValidatorProperties properties, RoutineRepository routines,
                          TableMetadataService tableMetadata, ExecutionPlanner planner, TestCaseFactory testCases,
                          GeneratedRecordRegistry generatedRecords, RoutineInvoker invoker,
                          RowSnapshotService snapshots, PostgresRunLock runLock, JsonReportWriter reports,
                          PlatformTransactionManager transactionManager, DataSourceProperties dataSourceProperties) {
        this.jdbc = jdbc; this.properties = properties; this.routines = routines; this.tableMetadata = tableMetadata;
        this.planner = planner; this.testCases = testCases; this.generatedRecords = generatedRecords;
        this.invoker = invoker; this.snapshots = snapshots; this.runLock = runLock; this.reports = reports;
        this.transactionManager = transactionManager;
        this.dataSourceProperties = dataSourceProperties;
    }

    public RunReport runOnce() {
        if (!inProcess.compareAndSet(false, true)) return blockedRun("Another validation run is already active in this process.");
        MutableRun run = new MutableRun(UUID.randomUUID().toString(), Instant.now(), properties.getSchema());
        boolean reportHealthy = true;
        try {
            reportHealthy = checkpoint(run, "RUNNING");
            if (!reportHealthy) throw new IllegalStateException("Initial execution report could not be persisted; no routines were invoked.");
            if (blank(dataSourceProperties.getUsername()) || blank(dataSourceProperties.getPassword())) {
                throw new IllegalStateException("EXTERNAL_DATABASE_CREDENTIALS_MISSING");
            }
            String database = jdbc.queryForObject("SELECT current_database()", String.class);
            run.database = database;
            if (!properties.getDatabase().equals(database)) throw new IllegalStateException("Connected database does not match validator.database.");
            Boolean schemaExists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)", Boolean.class, properties.getSchema());
            if (!Boolean.TRUE.equals(schemaExists)) throw new IllegalStateException("Configured schema does not exist.");

            PostgresRunLock.LockResult<Void> lock = runLock.withLock(database, properties.getSchema(), () -> {
                executeLocked(run);
                return null;
            });
            if (!lock.acquired()) run.runReasons.add("Another application instance holds the PostgreSQL advisory run lock; this startup was skipped.");
        } catch (Exception exception) {
            if ("EXTERNAL_DATABASE_CREDENTIALS_MISSING".equals(exception.getMessage())) {
                run.runReasons.add("TM_DB_USERNAME and TM_DB_PASSWORD must be supplied through external configuration; no database connection or routine call was attempted.");
                log.error("validation_run_blocked runId={} reason=external_database_credentials_missing", run.runId);
            } else {
                SafeErrors.Details failure = SafeErrors.from(exception);
                run.runReasons.add(failure.message() + (failure.sqlState() == null ? "" : " SQLSTATE=" + failure.sqlState()));
                log.error("validation_run_failed runId={} sqlState={} errorType={} message={} stackTrace={}",
                        run.runId, failure.sqlState(), exception.getClass().getSimpleName(),
                        SafeErrors.logMessage(exception), SafeErrors.stackTrace(exception));
            }
        } finally {
            run.completedAt = Instant.now();
            run.status = run.runReasons.isEmpty() ? (run.hasFailures() ? "COMPLETED_WITH_FAILURES" : "COMPLETED") : "COMPLETED_WITH_BLOCKS_OR_FAILURES";
            checkpoint(run, run.status);
            inProcess.set(false);
        }
        return run.toReport();
    }

    private void executeLocked(MutableRun run) {
        generatedRecords.clear();
        List<RoutineDefinition> discovered = routines.discover();
        run.discovered = discovered.size();
        if (discovered.size() > properties.getMaxRoutines()) {
            String reason = "Discovered " + discovered.size() + " routines, exceeding validator.max-routines="
                    + properties.getMaxRoutines() + "; all routines were classified and blocked before invocation.";
            run.runReasons.add(reason);
            discovered.forEach(routine -> {
                RoutineStatus status = new RoutineStatus(routine);
                status.block(reason);
                run.routines.put(routine.signature(), status);
            });
            checkpoint(run, "RUNNING");
            return;
        }
        ExecutionPlanner.Plan plan = planner.order(discovered);
        JsonReportWriter.WriteAttemptHistory writeHistory = reports.previousWriteAttempts(run.database, run.schema);
        Map<String, RoutineStatus> statuses = new HashMap<>();
        int executedCases = 0;
        int generatedRows = 0;
        boolean reportHealthy = checkpoint(run, "RUNNING");
        boolean writesAllowed = reportHealthy;
        for (RoutineDefinition routine : writePhaseThenReadPhase(plan.ordered())) {
            RoutineStatus status = new RoutineStatus(routine);
            run.routines.put(routine.signature(), status);
            if (writes(routine) && !properties.isAllowWriteReplay() && !writeHistory.diagnostics().isEmpty()) {
                status.skipRoutine("SKIPPED_BY_POLICY: prior write history could not be established; "
                        + String.join(" ", writeHistory.diagnostics()));
                statuses.put(routine.signature(), status);
                continue;
            }
            List<String> priorRuns = writeHistory.routines().get(routine.signature());
            if (writes(routine) && !properties.isAllowWriteReplay() && priorRuns != null && !priorRuns.isEmpty()) {
                status.skipRoutine("SKIPPED_BY_POLICY: this write routine was invoked in prior run(s) " + priorRuns
                        + "; automatic re-execution is disabled to prevent duplicate or repeated database effects.");
                statuses.put(routine.signature(), status);
                continue;
            }
            if (writes(routine) && !writesAllowed) {
                status.block("Write was blocked because an execution report checkpoint failed; commit evidence could not be preserved safely.");
                statuses.put(routine.signature(), status);
                continue;
            }
            if ("w".equals(routine.kind())) {
                status.block("Window functions require an OVER clause and cannot be invoked safely as standalone routines.");
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            if (plan.dependencyCycles().contains(routine.signature())) {
                status.block("Routine dependency cycle prevents a safe execution order. Cycle members: "
                        + plan.dependencyCycles().stream().sorted().toList() + ".");
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            List<String> failedDependencies = plan.requiredDependencies().getOrDefault(routine.signature(), Set.of()).stream()
                    .filter(signature -> !statuses.containsKey(signature) || !statuses.get(signature).verified())
                    .toList();
            if (!failedDependencies.isEmpty()) {
                status.block("A required routine dependency is unresolved, was not attempted, or did not complete verified execution: "
                        + String.join(", ", failedDependencies));
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            if (!routine.analysis().safe()) {
                status.blockAll(routine.analysis().safetyReasons().isEmpty() ? List.of("Routine behavior is unknown.") : routine.analysis().safetyReasons());
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            if (hasDynamicSql(routine)) {
                status.block("Dynamic SQL routine was not invoked; target/parameter effects are not statically bounded. No generated inputs were created for this routine.");
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            if (routine.anonymousRecordResult()
                    && routine.resultColumns().isEmpty()
                    && routine.parameters().stream().noneMatch(p -> "t".equals(p.mode()) || "o".equals(p.mode()) || "b".equals(p.mode()))) {
                status.block("The routine returns " + routine.resultType() + " but PostgreSQL catalog metadata provides no concrete output-column contract; invocation would raise SQLSTATE 42601. Inspect the routine definition for a stable OUT/TABLE/composite result contract, or leave blocked.");
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            if (routine.analysis().operations().contains("MERGE")) {
                status.block("MERGE action outcomes cannot be mapped to a bounded INSERT/UPDATE/DELETE verification contract.");
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            String preflight = preflight(routine);
            if (preflight != null) {
                status.block(preflight);
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            List<RoutineTestCase> cases;
            try { cases = testCases.create(routine); }
            catch (RuntimeException exception) {
                String reason = exception instanceof IllegalArgumentException ? exception.getMessage() : SafeErrors.from(exception).message();
                status.block("No safe test input could be derived: " + reason);
                statuses.put(routine.signature(), status);
                writesAllowed &= checkpoint(run, "RUNNING");
                continue;
            }
            if (cases.isEmpty()) status.block("No meaningful test variants could be generated.");
            for (RoutineTestCase testCase : cases) {
                if (executedCases >= properties.getMaxTestsPerRun()) {
                    status.skip(testCase, "Run stopped at validator.max-tests-per-run.");
                    continue;
                }
                if (Duration.between(run.startedAt, Instant.now()).toSeconds() >= properties.getRunTimeoutSeconds()) {
                    status.skip(testCase, "Run timeout limit reached.");
                    continue;
                }
                if (generatedRows >= properties.getMaxGeneratedRowsPerRun() && writes(routine)) {
                    status.skip(testCase, "Run stopped at validator.max-generated-rows-per-run.");
                    continue;
                }
                int inProgressCaseIndex = -1;
                if (writes(routine)) {
                    status.cases.add(inProgressCase(testCase));
                    inProgressCaseIndex = status.cases.size() - 1;
                    reportHealthy = checkpoint(run, "RUNNING");
                    writesAllowed &= reportHealthy;
                    if (!reportHealthy) {
                        status.cases.remove(inProgressCaseIndex);
                        status.skip(testCase, "Pre-invocation report checkpoint failed; the write routine was not invoked.");
                        break;
                    }
                }
                TestCaseReport result = executeCase(routine, testCase);
                if (inProgressCaseIndex >= 0) status.cases.set(inProgressCaseIndex, result);
                else status.cases.add(result);
                if (!"SKIPPED".equals(result.status())) executedCases++;
                if ("COMMITTED".equals(result.transactionOutcome()) || "INTERNAL_COMMIT_VERIFIED".equals(result.transactionOutcome())) {
                    generatedRows += countInserted(result.verificationEvidence());
                }
                reportHealthy = checkpoint(run, "RUNNING");
                writesAllowed &= reportHealthy;
                log.info("routine_case_finished runId={} signature={} caseId={} status={} executionSucceeded={} verificationSucceeded={} transactionOutcome={} durationMs={}",
                        run.runId, routine.signature(), testCase.caseId(), result.status(), result.executionSucceeded(),
                        result.verificationSucceeded(), result.transactionOutcome(), result.durationMillis());
                if (!reportHealthy && writes(routine)) {
                    status.reasons.add("Further writes were blocked because the report checkpoint failed.");
                    break;
                }
            }
            status.finish();
            statuses.put(routine.signature(), status);
            writesAllowed &= checkpoint(run, "RUNNING");
        }
    }

    /**
     * The validation contract is two-phase: attempt every eligible WRITE routine first,
     * allowing each verified write to commit independently; only then execute READ routines.
     * Dependencies from a READ routine into a WRITE routine cannot be satisfied without
     * violating this ordering and are therefore reported as blocked by the normal dependency check.
     */
    private List<RoutineDefinition> writePhaseThenReadPhase(List<RoutineDefinition> ordered) {
        // Preserve the planner's dependency order within each phase. Pure INSERT writers run
        // first so they can populate the current-run key registry; UPDATE writers run next,
        // DELETE writers only after inserts/updates, and read-only routines run last.
        // A routine that mixes operations is assigned to its most destructive phase so a
        // DELETE-bearing routine can never be accidentally pulled into the INSERT phase.
        List<RoutineDefinition> result = new ArrayList<>(ordered.size());
        for (int phase = 0; phase <= 4; phase++) {
            final int currentPhase = phase;
            ordered.stream().filter(routine -> operationPhase(routine) == currentPhase).forEach(result::add);
        }
        return List.copyOf(result);
    }

    private int operationPhase(RoutineDefinition routine) {
        if (routine.analysis() == null) return 3;
        Set<String> operations = routine.analysis().operations();
        if (operations.contains("DELETE")) return 2;
        if (operations.contains("UPDATE")) return 1;
        if (operations.contains("INSERT") || operations.contains("MERGE")) return 0;
        String routineName = routine.name() == null ? "" : routine.name().toLowerCase(java.util.Locale.ROOT);
        if (routineName.startsWith("get") || routineName.contains("_get_") || routineName.contains("get_")) return 3;
        return 4;
    }

    private boolean hasDynamicSql(RoutineDefinition routine) {
        String source = (routine.definition() == null ? "" : routine.definition()) + "\n"
                + (routine.source() == null ? "" : routine.source());
        // PL/pgSQL EXECUTE and dynamic format/EXECUTE patterns are deliberately left blank
        // per the test policy; no guessed target names or generated inputs are used.
        return source.matches("(?is).*\\bEXECUTE\\s+.*")
                || source.matches("(?is).*\\bformat\\s*\\(.*\\).*\\bEXECUTE\\b.*");
    }

    private String preflight(RoutineDefinition routine) {
        if (routine.hasInternalTransactionControl()) {
            return "BLOCKED_TRANSACTION_CONTROL: routine controls transactions internally; the validator cannot guarantee rollback of a failed or unverified write.";
        }
        if (!writes(routine)) return null;
        for (Map.Entry<String, Set<String>> entry : routine.analysis().targets().entrySet()) {
            if (entry.getValue().stream().noneMatch(Set.of("INSERT", "UPDATE", "DELETE")::contains)) continue;
            String[] parts = split(entry.getKey());
            TableMetadata table;
            try { table = tableMetadata.describe(parts[0], parts[1]); }
            catch (RuntimeException exception) {
                SafeErrors.Details details = SafeErrors.from(exception);
                String sqlState = details.sqlState() == null ? "unavailable" : details.sqlState();
                String exceptionType = exception.getClass().getSimpleName();
                log.warn("write_target_metadata_lookup_failed target={} sqlState={} exceptionType={} details={}",
                        entry.getKey(), sqlState, exceptionType, SafeErrors.logMessage(exception));
                return "Write target metadata lookup failed for " + entry.getKey()
                        + " (SQLSTATE=" + sqlState + ", exception=" + exceptionType
                        + "): " + details.message();
            }
            if (!Set.of("r", "p").contains(table.relationKind()))
                return "BLOCKED_UNSUPPORTED_RELATION_TYPE: write target " + entry.getKey() + " is a PostgreSQL "
                        + table.relationType() + "; verified base-table effects cannot be bounded safely.";
            if (table.primaryKey().isEmpty()) return "Write target " + entry.getKey() + " has no primary key for row-level verification.";
            if (table.rowSecurityEnabled()) return "Write target " + entry.getKey() + " uses row-level security; complete row-level verification cannot be proven with the configured validator role.";
            if (table.hasUserTriggers()) return "Write target " + entry.getKey() + " has user triggers with effects outside the verified routine boundary.";
            if (table.hasUserRules()) return "Write target " + entry.getKey() + " has rewrite rules whose additional effects cannot be bounded safely.";
            for (var column : table.columns()) {
                String defaultExpression = column.defaultExpression();
                if (defaultExpression == null || defaultExpression.isBlank()) continue;
                List<RoutineAnalyzer.FunctionCall> calls;
                try { calls = RoutineAnalyzer.findFunctionCalls(defaultExpression); }
                catch (IllegalArgumentException malformed) { return "Default expression on " + entry.getKey() + "." + column.name() + " exceeds or violates the safe analysis boundary."; }
                for (RoutineAnalyzer.FunctionCall call : calls) {
                    String schema = call.schema();
                    String function = call.name().toUpperCase(java.util.Locale.ROOT);
                    if ((schema != null && !"pg_catalog".equalsIgnoreCase(schema)) || !SAFE_DEFAULT_CALLS.contains(function))
                        return "Default expression on " + entry.getKey() + "." + column.name() + " calls an unreviewed function.";
                }
            }
            if (entry.getValue().contains("DELETE") && tableMetadata.hasReferencingForeignKey(table.schema(), table.name()))
                return "DELETE target is referenced by another table; cascade and dependent-row effects cannot be bounded safely.";
            if (entry.getValue().contains("UPDATE") || entry.getValue().contains("DELETE")) {
                Set<String> predicateColumns = new LinkedHashSet<>();
                routine.analysis().parameterBindings().values().stream().flatMap(List::stream)
                        .filter(binding -> binding.table().equals(entry.getKey()) && binding.role().startsWith("PREDICATE"))
                        .map(ColumnBinding::column).forEach(predicateColumns::add);
                if (!predicateColumns.containsAll(table.primaryKey())) return "UPDATE/DELETE does not bind every primary-key column to a routine input; the target row cannot be restricted to a current-run record.";
            }
        }
        return null;
    }

    private TestCaseReport executeCase(RoutineDefinition routine, RoutineTestCase testCase) {
        Instant started = Instant.now();
        List<GeneratedInputReport> inputs = testCase.inputs().entrySet().stream().map(e -> SafeValueRenderer.render(e.getKey(), e.getValue())).toList();
        List<String> prerequisites = testCase.inputs().entrySet().stream()
                .filter(e -> e.getValue().reusedReference())
                .map(e -> e.getValue().source() + ":" + e.getKey()) .toList();
        if (!routine.analysis().dependencySignatures().isEmpty()) prerequisites = new ArrayList<>(prerequisites);
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        AtomicReference<CaseOutcome> callbackOutcome = new AtomicReference<>();
        try {
            if (!writes(routine)) return executeRead(routine, testCase, inputs, prerequisites, started);
            if (routine.hasInternalTransactionControl()) return executeInternalTransactionRoutine(routine, testCase, inputs, prerequisites, started);
            TransactionTemplate transaction = transaction(false);
            CaseOutcome outcome = transaction.execute(status -> {
                CaseOutcome completed = executeWriteWithinTransaction(routine, testCase, status);
                callbackOutcome.set(completed);
                callbackCompleted.set(true);
                return completed;
            });
            if (outcome == null) throw new IllegalStateException("Transaction callback returned no outcome.");
            if (!outcome.verified()) return new TestCaseReport(testCase.caseId(), testCase.variant(), "FAILED", inputs, prerequisites,
                    elapsed(started), true, false, outcome.evidence(), outcome.affectedRows(), "ROLLED_BACK_UNVERIFIED", null,
                    "Write executed but row-level verification failed; the transaction was rolled back.", outcome.reason());
            if (!outcome.inserted().isEmpty()) outcome.inserted().forEach((table, rows) -> rows.forEach(row -> generatedRecords.add(table, row)));
            return new TestCaseReport(testCase.caseId(), testCase.variant(), "SUCCESS", inputs, prerequisites,
                    elapsed(started), true, true, outcome.evidence(), outcome.affectedRows(), "COMMITTED", null, null, null);
        } catch (Exception exception) {
            SafeErrors.Details failure = SafeErrors.from(exception);
            CaseOutcome completed = callbackOutcome.get();
            String transactionOutcome = !writes(routine) ? "READ_ONLY"
                    : routine.hasInternalTransactionControl() ? "INTERNAL_COMMIT_OUTCOME_UNKNOWN"
                    : callbackCompleted.get() && completed != null && !completed.verified() ? "ROLLBACK_OUTCOME_UNKNOWN"
                    : callbackCompleted.get() ? "COMMIT_OUTCOME_UNKNOWN" : "ROLLED_BACK_AFTER_FAILURE";
            String reason = !writes(routine) ? null : routine.hasInternalTransactionControl()
                    ? "The routine may have committed internally before failure; external rollback cannot be guaranteed."
                    : callbackCompleted.get() ? "The transaction callback completed, but the database transaction outcome could not be determined."
                    : null;
            return new TestCaseReport(testCase.caseId(), testCase.variant(), "FAILED", inputs, prerequisites,
                    elapsed(started), completed != null, completed != null && completed.verified(),
                    completed == null ? "No verified row or result evidence." : completed.evidence(),
                    completed == null ? null : completed.affectedRows(), transactionOutcome,
                    failure.sqlState(), failure.message(), reason);
        }
    }

    private TestCaseReport inProgressCase(RoutineTestCase testCase) {
        List<GeneratedInputReport> inputs = testCase.inputs().entrySet().stream()
                .map(entry -> SafeValueRenderer.render(entry.getKey(), entry.getValue())).toList();
        List<String> prerequisites = testCase.inputs().entrySet().stream().filter(entry -> entry.getValue().reusedReference())
                .map(entry -> entry.getValue().source() + ":" + entry.getKey()).toList();
        return new TestCaseReport(testCase.caseId(), testCase.variant(), "IN_PROGRESS", inputs, prerequisites, 0,
                false, false, "Pre-invocation checkpoint persisted; database outcome is unknown if the process stops here.",
                null, "NOT_STARTED", null, null,
                "The application will not automatically replay this write routine after an interrupted run.");
    }

    private TestCaseReport executeRead(RoutineDefinition routine, RoutineTestCase testCase, List<GeneratedInputReport> inputs,
                                       List<String> prerequisites, Instant started) {
        try {
            RoutineInvoker.RoutineOutput output = transaction(true).execute(status -> invoker.invoke(routine, testCase));
            if (output == null || output.truncated()) return new TestCaseReport(testCase.caseId(), testCase.variant(), "FAILED", inputs, prerequisites,
                    elapsed(started), true, false, "Result exceeded the configured output-row verification limit.", null,
                    "READ_ONLY", null, null, "Result contract was not fully observed.");
            boolean verified = resultContractMatches(routine, output);
            String evidence = "Returned " + output.rowCount() + " row(s) with " + output.columns().size() + " column(s); " + (verified ? "declared result shape is compatible." : "declared result shape could not be verified.");
            return new TestCaseReport(testCase.caseId(), testCase.variant(), verified ? "SUCCESS" : "UNVERIFIED", inputs, prerequisites,
                    elapsed(started), true, verified, evidence, null, "READ_ONLY", null, null,
                    verified ? null : "Routine executed, but its declared result contract could not be verified. " + resultContractMismatchReason(routine, output));
        } catch (Exception exception) {
            SafeErrors.Details failure = SafeErrors.from(exception);
            return new TestCaseReport(testCase.caseId(), testCase.variant(), "FAILED", inputs, prerequisites, elapsed(started),
                    false, false, "Read invocation failed.", null, "READ_ONLY", failure.sqlState(), failure.message(), null);
        }
    }

    private CaseOutcome executeWriteWithinTransaction(RoutineDefinition routine, RoutineTestCase testCase, TransactionStatus tx) {
        Set<String> targets = routine.analysis().targets().keySet();
        RowSnapshotService.SnapshotSet before = snapshots.capture(targets);
        RoutineInvoker.RoutineOutput output = invoker.invoke(routine, testCase);
        RowSnapshotService.SnapshotSet after = snapshots.capture(targets);
        Verification rowVerification = verify(routine, before, after);
        Verification verification = output == null || output.truncated()
                ? Verification.no("Routine result exceeded the configured output limit and could not be fully verified.", rowVerification.affectedRows())
                : !resultContractMatches(routine, output)
                ? Verification.no("Write ran, but its returned result shape did not match the declared routine contract. " + resultContractMismatchReason(routine, output), rowVerification.affectedRows())
                : rowVerification;
        if (!verification.verified()) tx.setRollbackOnly();
        return new CaseOutcome(verification.verified(), verification.evidence(), verification.affectedRows(), verification.reason(), verification.inserted());
    }

    private TestCaseReport executeInternalTransactionRoutine(RoutineDefinition routine, RoutineTestCase testCase,
                                                             List<GeneratedInputReport> inputs, List<String> prerequisites, Instant started) {
        try {
            Set<String> targets = routine.analysis().targets().keySet();
            RowSnapshotService.SnapshotSet before = snapshots.capture(targets);
            RoutineInvoker.RoutineOutput output = invoker.invoke(routine, testCase);
            RowSnapshotService.SnapshotSet after = snapshots.capture(targets);
            Verification verification = verify(routine, before, after);
            if (verification.verified()) verification.inserted().forEach((table, rows) -> rows.forEach(row -> generatedRecords.add(table, row)));
            return new TestCaseReport(testCase.caseId(), testCase.variant(), verification.verified() ? "SUCCESS" : "UNVERIFIED",
                    inputs, prerequisites, elapsed(started), true, verification.verified(), verification.evidence(), verification.affectedRows(),
                    verification.verified() ? "INTERNAL_COMMIT_VERIFIED" : "INTERNAL_COMMIT_UNVERIFIED", null, null,
                    verification.verified() ? null : "Routine may have committed internally; rollback was not possible and verification failed: " + verification.reason());
        } catch (Exception exception) {
            SafeErrors.Details failure = SafeErrors.from(exception);
            return new TestCaseReport(testCase.caseId(), testCase.variant(), "FAILED", inputs, prerequisites, elapsed(started),
                    false, false, "Internal transaction routine did not produce verifiable row evidence.", null,
                    "INTERNAL_COMMIT_OUTCOME_UNKNOWN", failure.sqlState(), failure.message(),
                    "The routine may have committed internally before failure; external rollback cannot be guaranteed.");
        }
    }

    private Verification verify(RoutineDefinition routine, RowSnapshotService.SnapshotSet before, RowSnapshotService.SnapshotSet after) {
        Map<String, List<Map<String, Object>>> inserted = new LinkedHashMap<>();
        long insertedCount = 0, updatedCount = 0, deletedCount = 0;
        List<String> evidence = new ArrayList<>();
        Set<String> operations = routine.analysis().operations();
        boolean expectsInsert = operations.contains("INSERT");
        boolean expectsUpdate = operations.contains("UPDATE");
        boolean expectsDelete = operations.contains("DELETE");
        for (String table : before.tables().keySet()) {
            RowSnapshotService.Diff diff = RowSnapshotService.diff(before.tables().get(table), after.tables().get(table));
            Set<String> expected = routine.analysis().targets().getOrDefault(table, Set.of());
            if (!expected.contains("INSERT") && !diff.inserted().isEmpty()) return Verification.no("Unexpected inserted rows were observed in " + table + ".", diff.total());
            if (!expected.contains("UPDATE") && !diff.updated().isEmpty()) return Verification.no("Unexpected updated rows were observed in " + table + ".", diff.total());
            if (!expected.contains("DELETE") && !diff.deleted().isEmpty()) return Verification.no("Unexpected deleted rows were observed in " + table + ".", diff.total());
            if (expected.contains("INSERT")) {
                if (diff.inserted().size() > 1) return Verification.no("INSERT changed more than one row in a single bounded test case in " + table + ".", diff.total());
                insertedCount += diff.inserted().size();
                if (!diff.inserted().isEmpty()) inserted.put(table, diff.inserted().values().stream().map(RowSnapshotService.RowRecord::values).toList());
            }
            if (expected.contains("UPDATE")) {
                if (diff.updated().size() > 1) return Verification.no("UPDATE changed more than one row in a single bounded test case in " + table + ".", diff.total());
                for (RowSnapshotService.RowRecord changed : diff.updated().values()) {
                    if (!generatedRecords.owns(table, changed.primaryKey())) return Verification.no("UPDATE changed a record that was not generated during this run in " + table + ".", diff.total());
                }
                updatedCount += diff.updated().size();
            }
            if (expected.contains("DELETE")) {
                if (diff.deleted().size() > 1) return Verification.no("DELETE changed more than one row in a single bounded test case in " + table + ".", diff.total());
                for (RowSnapshotService.RowRecord deleted : diff.deleted().values()) {
                    if (!generatedRecords.owns(table, deleted.primaryKey())) return Verification.no("DELETE affected a record not generated during this run in " + table + ".", diff.total());
                }
                deletedCount += diff.deleted().size();
            }
            if (!diff.inserted().isEmpty() || !diff.updated().isEmpty() || !diff.deleted().isEmpty()) {
                evidence.add(table + ": inserted=" + diff.inserted().size() + ", updated=" + diff.updated().size() + ", deleted=" + diff.deleted().size());
            }
        }
        if (expectsInsert && insertedCount < 1) return Verification.no("No inserted row was observed for an INSERT routine.");
        if (expectsUpdate && updatedCount < 1) return Verification.no("No generated row update was observed for an UPDATE routine.");
        if (expectsDelete && deletedCount < 1) return Verification.no("No current-run generated row deletion was observed for a DELETE routine.");
        if (!expectsInsert && !expectsUpdate && !expectsDelete) return Verification.no("No supported write operation was available for verification.");
        return new Verification(true, evidence.isEmpty() ? "Write invocation produced no row changes." : String.join("; ", evidence),
                insertedCount + updatedCount + deletedCount, null, inserted);
    }

    private boolean resultContractMatches(RoutineDefinition routine, RoutineInvoker.RoutineOutput output) {
        List<com.tm.storedprocedure.domain.RoutineResultColumn> expected = routine.resultColumns();
        if (expected.isEmpty()) {
            expected = routine.parameters().stream().filter(p -> "t".equals(p.mode()) || (routine.procedure() && p.output()))
                    .map(p -> new com.tm.storedprocedure.domain.RoutineResultColumn(p.name(), p.sqlType())).toList();
        }
        if (expected.isEmpty()) {
            if (routine.procedure()) return output.columns().isEmpty();
            if (routine.resultType() == null || routine.resultType().isBlank() || routine.anonymousRecordResult()) return false;
            String declared = routine.resultType().replaceFirst("(?i)^SETOF\\s+", "").trim();
            return output.columns().size() == 1 && compatibleType(declared, output.columns().getFirst().type());
        }
        if (expected.size() != output.columns().size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            var declared = expected.get(i);
            var actual = output.columns().get(i);
            if (declared.name() != null && !declared.name().isBlank() && !declared.name().equalsIgnoreCase(actual.name())) return false;
            if (!compatibleType(declared.sqlType(), actual.type())) return false;
        }
        return true;
    }

    private String resultContractMismatchReason(RoutineDefinition routine, RoutineInvoker.RoutineOutput output) {
        if (output == null) return "No routine output metadata was returned.";
        if (output.truncated()) return "Returned output was truncated at the configured row limit.";
        List<com.tm.storedprocedure.domain.RoutineResultColumn> expected = routine.resultColumns();
        if (expected.isEmpty()) {
            expected = routine.parameters().stream().filter(p -> "t".equals(p.mode()) || (routine.procedure() && p.output()))
                    .map(p -> new com.tm.storedprocedure.domain.RoutineResultColumn(p.name(), p.sqlType())).toList();
        }
        String actualShape = output.columns().stream().map(c -> c.name() + " " + c.type()).toList().toString();
        if (expected.isEmpty()) {
            if (routine.procedure()) return output.columns().isEmpty() ? "" : "Expected no result columns but received " + actualShape + ".";
            if (routine.resultType() == null || routine.resultType().isBlank() || routine.anonymousRecordResult())
                return "Function has no concrete declared result shape (" + routine.resultType() + "); actual columns=" + actualShape + ".";
            return "Expected one scalar column of type " + routine.resultType() + "; actual columns=" + actualShape + ".";
        }
        List<String> expectedShape = expected.stream().map(c -> c.name() + " " + c.sqlType()).toList();
        if (expected.size() != output.columns().size())
            return "Expected columns=" + expectedShape + "; actual columns=" + actualShape + ".";
        for (int i = 0; i < expected.size(); i++) {
            var declared = expected.get(i);
            var actual = output.columns().get(i);
            if (declared.name() != null && !declared.name().isBlank() && !declared.name().equalsIgnoreCase(actual.name()))
                return "Column " + (i + 1) + " name mismatch: expected " + declared.name() + ", actual " + actual.name() + ".";
            if (!compatibleType(declared.sqlType(), actual.type()))
                return "Column " + (i + 1) + " type mismatch: expected " + declared.sqlType() + ", actual " + actual.type() + ".";
        }
        return "The reported result shape is compatible; investigate the separate verification path.";
    }

    private static boolean compatibleType(String expected, String actual) {
        return normalizedType(expected).equals(normalizedType(actual));
    }
    private static String normalizedType(String type) {
        String value = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s*\\([^)]*\\)", "").trim();
        if (value.startsWith("setof ")) value = value.substring(6).trim();
        return switch (value) {
            case "int2" -> "smallint";
            case "int4", "serial" -> "integer";
            case "int8", "bigserial" -> "bigint";
            case "bool" -> "boolean";
            case "float4" -> "real";
            case "float8" -> "double precision";
            case "varchar", "bpchar" -> value.equals("varchar") ? "character varying" : "character";
            case "timestamptz" -> "timestamp with time zone";
            case "timestamp" -> "timestamp without time zone";
            case "timetz" -> "time with time zone";
            default -> value;
        };
    }

    private TransactionTemplate transaction(boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(properties.getQueryTimeoutSeconds());
        template.setReadOnly(readOnly);
        return template;
    }
    private boolean writes(RoutineDefinition routine) { return routine.analysis() != null && routine.analysis().writes(); }
    private int countInserted(String evidence) {
        if (evidence == null) return 0;
        int total = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("inserted=(\\d+)").matcher(evidence);
        while (matcher.find()) total += Integer.parseInt(matcher.group(1));
        return total;
    }
    private static String[] split(String table) { int dot = table.indexOf('.'); return new String[]{table.substring(0, dot), table.substring(dot + 1)}; }
    private static long elapsed(Instant started) { return Math.max(0, Duration.between(started, Instant.now()).toMillis()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private boolean checkpoint(MutableRun run, String status) {
        try { run.status = status; reports.write(run.toReport()); return true; }
        catch (RuntimeException exception) {
            String diagnostic = SafeErrors.logMessage(exception);
            String reason = "Execution report checkpoint failed; further writes were blocked. " + diagnostic;
            if (!run.runReasons.contains(reason)) run.runReasons.add(reason);
            log.error("execution_report_checkpoint_failed runId={} errorType={} details={}",
                    run.runId, exception.getClass().getSimpleName(), diagnostic);
            return false;
        }
    }

    private RunReport blockedRun(String reason) {
        Instant now = Instant.now();
        MutableRun run = new MutableRun(UUID.randomUUID().toString(), now, properties.getSchema());
        run.completedAt = now; run.status = "BLOCKED"; run.runReasons.add(reason); checkpoint(run, "BLOCKED"); return run.toReport();
    }

    private static final class MutableRun {
        final String runId; final Instant startedAt; final String schema; Instant completedAt;
        String database = "unknown", status = "RUNNING"; int discovered;
        final List<String> runReasons = new ArrayList<>();
        final Map<String, RoutineStatus> routines = new LinkedHashMap<>();
        MutableRun(String runId, Instant startedAt, String schema) { this.runId = runId; this.startedAt = startedAt; this.schema = schema; }
        boolean hasFailures() { return routines.values().stream().anyMatch(r -> r.failed() || r.blocked()); }
        RunReport toReport() {
            Instant end = completedAt == null ? Instant.now() : completedAt;
            List<RoutineReport> details = routines.values().stream().map(RoutineStatus::report).toList();
            int attempted = 0, success = 0, verified = 0, committed = 0, failed = 0, blocked = 0, skipped = 0;
            for (RoutineReport routine : details) {
                if ("BLOCKED".equals(routine.status())) blocked++;
                for (TestCaseReport test : routine.testCases()) {
                    if (!"SKIPPED".equals(test.status())) attempted++;
                    if (test.executionSucceeded()) success++;
                    if (test.verificationSucceeded()) verified++;
                    if (Set.of("COMMITTED", "INTERNAL_COMMIT_VERIFIED").contains(test.transactionOutcome())) committed++;
                    if ("FAILED".equals(test.status()) || "UNVERIFIED".equals(test.status())) failed++;
                    if ("SKIPPED".equals(test.status())) skipped++;
                }
            }
            int partiallyVerified = (int) details.stream().filter(routine -> "PARTIAL".equals(routine.status())).count();
            int failedRoutines = (int) details.stream().filter(routine -> "FAILED".equals(routine.status())
                    || "UNVERIFIED".equals(routine.status())).count();
            return new RunReport(runId, startedAt, completedAt, database, schema, status, Duration.between(startedAt, end).toMillis(),
                    discovered, attempted, success, verified, committed, failed, blocked, skipped, runReasons, details,
                    details.size(), attemptedRoutineCount(details), routineCount(details, "VERIFIED"),
                    failedRoutines, partiallyVerified, routineCount(details, "SKIPPED"),
                    readVerifiedRoutineCount(details), rolledBackWriteCount(details), changedRowCount(details));
        }

        private static int attemptedRoutineCount(List<RoutineReport> details) {
            return (int) details.stream().filter(routine -> routine.testCases().stream()
                    .anyMatch(test -> !"SKIPPED".equals(test.status()))).count();
        }
        private static int routineCount(List<RoutineReport> details, String state) {
            return (int) details.stream().filter(routine -> state.equals(routine.status())).count();
        }
        private static int readVerifiedRoutineCount(List<RoutineReport> details) {
            return (int) details.stream().filter(routine -> routine.classification() == com.tm.storedprocedure.domain.OperationType.READ
                    && "VERIFIED".equals(routine.status())).count();
        }
        private static int rolledBackWriteCount(List<RoutineReport> details) {
            return (int) details.stream().filter(routine -> routine.classification() == com.tm.storedprocedure.domain.OperationType.WRITE)
                    .flatMap(routine -> routine.testCases().stream())
                    .filter(test -> "ROLLED_BACK_UNVERIFIED".equals(test.transactionOutcome())
                            || "ROLLED_BACK_AFTER_FAILURE".equals(test.transactionOutcome())).count();
        }
        private static long changedRowCount(List<RoutineReport> details) {
            return details.stream().filter(routine -> routine.classification() == com.tm.storedprocedure.domain.OperationType.WRITE)
                    .flatMap(routine -> routine.testCases().stream())
                    .filter(test -> test.verificationSucceeded() && "COMMITTED".equals(test.transactionOutcome()))
                    .mapToLong(test -> test.affectedRows() == null ? 0 : test.affectedRows()).sum();
        }
    }

    private static final class RoutineStatus {
        final RoutineDefinition routine; final Instant started = Instant.now();
        final List<TestCaseReport> cases = new ArrayList<>(); final List<String> reasons = new ArrayList<>();
        String state = "PENDING";
        RoutineStatus(RoutineDefinition routine) { this.routine = routine; }
        void block(String reason) { blockAll(List.of(reason)); }
        void blockAll(List<String> values) { state = "BLOCKED"; reasons.addAll(values); }
        void skipRoutine(String reason) { state = "SKIPPED"; reasons.add(reason); }
        void skip(RoutineTestCase test, String reason) { cases.add(new TestCaseReport(test.caseId(), test.variant(), "SKIPPED",
                test.inputs().entrySet().stream().map(e -> SafeValueRenderer.render(e.getKey(), e.getValue())).toList(), List.of(), 0,
                false, false, "No invocation was made.", null, "NOT_STARTED", null, null, reason)); }
        void finish() {
            if ("BLOCKED".equals(state) || "SKIPPED".equals(state)) return;
            if (cases.isEmpty()) { state = "BLOCKED"; reasons.add("No test case was attempted."); return; }
            if (cases.stream().allMatch(c -> "SKIPPED".equals(c.status()))) { state = "SKIPPED"; return; }
            boolean hasFailure = cases.stream().anyMatch(c -> "FAILED".equals(c.status()) || "UNVERIFIED".equals(c.status()));
            boolean hasVerifiedCase = cases.stream().anyMatch(TestCaseReport::verificationSucceeded);
            if (hasFailure && hasVerifiedCase) state = "PARTIAL";
            else if (hasFailure) state = cases.stream().anyMatch(c -> "FAILED".equals(c.status())) ? "FAILED" : "UNVERIFIED";
            else if (cases.stream().allMatch(TestCaseReport::verificationSucceeded)) state = "VERIFIED";
            else state = "UNVERIFIED";
        }
        boolean verified() { return "VERIFIED".equals(state); }
        boolean failed() { return Set.of("FAILED", "UNVERIFIED", "PARTIAL").contains(state)
                || cases.stream().anyMatch(c -> "FAILED".equals(c.status()) || "UNVERIFIED".equals(c.status())); }
        boolean blocked() { return "BLOCKED".equals(state); }
        String outcome() {
            if ("VERIFIED".equals(state)) return routine.analysis().classification() == com.tm.storedprocedure.domain.OperationType.READ
                    ? "VERIFIED_READ" : "VERIFIED_AND_COMMITTED";
            if ("SKIPPED".equals(state)) return "SKIPPED_BY_POLICY";
            if ("BLOCKED".equals(state)) {
                String why = String.join(" ", reasons).toLowerCase(java.util.Locale.ROOT);
                if (why.contains("dependency cycle") || why.contains("required routine dependency")
                        || why.contains("unresolved routine call") || why.contains("foreign-key parent")
                        || why.contains("current-run generated record")) return "BLOCKED_UNRESOLVED_DEPENDENCY";
                if (why.contains("unsafe") || why.contains("transaction control") || why.contains("security definer")
                        || why.contains("dynamic sql") || why.contains("temporary-relation")) return "BLOCKED_UNSAFE";
                if (why.contains("metadata") || why.contains("relation") || why.contains("primary key")
                        || why.contains("schema") || why.contains("identifier_case")) return "BLOCKED_METADATA";
                return "BLOCKED_UNSUPPORTED";
            }
            if ("PARTIAL".equals(state)) return "PARTIALLY_VERIFIED";
            if ("UNVERIFIED".equals(state)) return "UNVERIFIED";
            List<TestCaseReport> attempted = cases.stream().filter(c -> !"SKIPPED".equals(c.status())).toList();
            List<TestCaseReport> failures = attempted.stream().filter(c -> "FAILED".equals(c.status()) || "UNVERIFIED".equals(c.status())).toList();
            if (routine.analysis().classification() == com.tm.storedprocedure.domain.OperationType.WRITE && !failures.isEmpty()
                    && failures.stream().allMatch(c -> "ROLLED_BACK_AFTER_FAILURE".equals(c.transactionOutcome())
                            || "ROLLED_BACK_UNVERIFIED".equals(c.transactionOutcome()))) return "FAILED_AND_ROLLED_BACK";
            if (routine.analysis().classification() == com.tm.storedprocedure.domain.OperationType.WRITE
                    && attempted.stream().anyMatch(c -> "COMMIT_OUTCOME_UNKNOWN".equals(c.transactionOutcome())))
                return "FAILED_COMMIT_OUTCOME_UNKNOWN";
            return "FAILED";
        }
        String remediationGuidance() {
            // Prefer concrete PostgreSQL diagnostics over a generic instruction when a
            // routine failed after invocation. These messages guide the SQL/routine fix;
            // Java cannot safely rewrite a stored routine's business SQL automatically.
            for (TestCaseReport testCase : cases) {
                if (!"FAILED".equals(testCase.status()) && !"UNVERIFIED".equals(testCase.status())) continue;
                String state = testCase.sqlState();
                if (state == null) continue;
                switch (state) {
                    case "42702":
                        return "SQLSTATE 42702 (ambiguous_column): qualify every conflicting column reference with its table alias in the PostgreSQL routine body; Java input generation cannot resolve SQL name ambiguity.";
                    case "42P01": {
                        String diagnostic = cases.stream().filter(c -> state.equals(c.sqlState()))
                                .map(TestCaseReport::errorDetails).filter(java.util.Objects::nonNull)
                                .map(value -> value.toLowerCase(java.util.Locale.ROOT)).findFirst().orElse("");
                        if (diagnostic.contains("tm_lead_source_permission") || routine.name().equalsIgnoreCase("ASP_GET_REF_CODES"))
                            return "ASP_GET_REF_CODES: relation public1.TM_Lead_Source_Permission was not found. Check pg_catalog for the exact quoted table name/schema and compare the routine reference with the supplied DDL. Deploy the table only if it is an actual required source object; do not invent a replacement table.";
                        return "SQLSTATE 42P01 (undefined_table): verify the exact schema/table spelling in pg_catalog and deploy or correct the missing dependency. Do not create a guessed business table from Java.";
                    }
                    case "42804": {
                        String diagnostic = cases.stream().filter(c -> state.equals(c.sqlState()))
                                .map(TestCaseReport::errorDetails).filter(java.util.Objects::nonNull)
                                .map(value -> value.toLowerCase(java.util.Locale.ROOT)).findFirst().orElse("");
                        if (diagnostic.contains("app_dob") && diagnostic.contains("timestamp") && diagnostic.contains("character"))
                            return "ASP_INSERT_APPLICATION datatype mismatch: p_app_dob is a character parameter but TM_Application.App_DOB is timestamp. Keep the existing routine signature; convert the non-empty ISO input inside the routine with NULLIF(p_app_dob, '')::timestamp (or use the correct timestamp-typed parameter only if changing the signature is permitted). Java now generates an ISO timestamp-shaped value but cannot change the deployed routine body.";
                        if (diagnostic.contains("boolean") && diagnostic.contains("integer"))
                            return "Boolean/integer mismatch: Java now restricts integer inputs mapped to boolean target columns to 0 or 1. PostgreSQL still requires an explicit, semantically correct conversion in the routine body, such as CASE WHEN parameter_value = 1 THEN TRUE ELSE FALSE END; do not change the callable signature without checking callers.";
                        return "PostgreSQL datatype mismatch: align the routine parameter-to-column mapping and add an explicit cast in the routine body only where the intended conversion is valid; Java must not change the deployed routine signature.";
                    }
                    case "42883", "42846":
                        return "PostgreSQL datatype/operator mismatch: align the routine parameter-to-column mapping and add an explicit cast in the routine body only where the intended conversion is valid; Java must not change the deployed routine signature.";
                    case "23502": {
                        String diagnostic = cases.stream().filter(c -> state.equals(c.sqlState()))
                                .map(TestCaseReport::errorDetails).filter(java.util.Objects::nonNull)
                                .map(value -> value.toLowerCase(java.util.Locale.ROOT)).findFirst().orElse("");
                        if (diagnostic.contains("ua_default"))
                            return "ASP_INSERT_ALIAS: TM_User_Alias.UA_Default is NOT NULL but the attempted insert supplied NULL. Check whether the routine accepts a value for this column; otherwise fix its INSERT/default logic or the schema default using the documented business rule. Java must not invent a default business value.";
                        return "SQLSTATE 23502 (not_null_violation): the routine omitted a required column or supplied NULL. Provide the documented required input or correct the routine's INSERT/default logic.";
                    }
                    case "23505": {
                        String diagnostic = cases.stream().filter(c -> state.equals(c.sqlState()))
                                .map(TestCaseReport::errorDetails).filter(java.util.Objects::nonNull)
                                .map(value -> value.toLowerCase(java.util.Locale.ROOT)).findFirst().orElse("");
                        if (diagnostic.contains("tm_products") || diagnostic.contains("asp_insert_product"))
                            return "ASP_INSERT_PRODUCT: duplicate key detected. Java now retries collision checks for mapped single-column unique keys and uses the actual PostgreSQL sequence when identity/default metadata supports it. If the routine hard-codes or transforms the key, correct that routine contract; do not reset the sequence or bypass the routine.";
                        if (diagnostic.contains("lead_source"))
                            return "Asp_Add_Lead_Source: duplicate key detected. Java now retries collision checks for mapped single-column unique keys and uses sequence metadata where available. Verify the actual primary-key column and whether the routine overrides the generated key.";
                        return "SQLSTATE 23505 (unique_violation): inspect the actual inserted key and identity/sequence behavior. Java generates collision-checked candidates for mapped single-column unique keys and uses sequence metadata when available; do not reset sequences or bypass the routine.";
                    }
                    case "22001":
                        return "Value exceeds the target character limit. Java now uses the target column's declared CHAR/VARCHAR length when a unique parameter-to-column binding is available. If the error remains, verify that the routine maps the parameter to the correct column or trims/corrects the source value without losing required semantics.";
                    case "22007", "22008", "22009": {
                        String diagnostic = cases.stream().filter(c -> state.equals(c.sqlState()))
                                .map(TestCaseReport::errorDetails).filter(java.util.Objects::nonNull)
                                .map(value -> value.toLowerCase(java.util.Locale.ROOT)).findFirst().orElse("");
                        if (diagnostic.contains("hip") || diagnostic.contains("timestamp"))
                            return "HIP application routine timestamp failure: Java generates ISO date/timestamp values from target-column metadata and respects declared text width. Verify each date parameter's mapping and convert text explicitly with NULLIF(parameter, '')::timestamp/date in the routine where required.";
                        return "Invalid date/time value: Java generates ISO date/timestamp-shaped values when the target metadata is temporal. Correct any explicit text-to-date/timestamp conversion in the routine body if the declared parameter is character.";
                    }
                    default:
                        // Continue in case a later failure has a more specific diagnostic.
                }
            }
            return switch (outcome()) {
                case "VERIFIED_READ", "VERIFIED_AND_COMMITTED" -> "No remediation required for the verified contract and observed effects.";
                case "BLOCKED_METADATA" -> "Verify the exact relation identifier and schema in pg_catalog; correct the routine reference or deploy the missing relation without case-insensitive remapping.";
                case "BLOCKED_UNRESOLVED_DEPENDENCY" -> "Resolve the named routine dependency or cycle, then review its behavior and test prerequisites before execution.";
                case "BLOCKED_UNSAFE" -> "Review the routine's DDL, dynamic SQL, transaction control, or external effects; keep it blocked until behavior can be bounded and rolled back safely.";
                case "BLOCKED_UNSUPPORTED" -> "Provide a supported language/return-contract analysis and metadata-backed input strategy, or retain the blocked outcome.";
                case "SKIPPED_BY_POLICY" -> "Automatic replay is disabled; review the prior report and database state before authorizing a separate validation attempt.";
                case "FAILED_AND_ROLLED_BACK" -> "Review the SQLSTATE and sanitized database diagnostic, correct the routine or generated input cause, and do not automatically replay this write.";
                case "PARTIALLY_VERIFIED" -> "Review failed variants and their SQLSTATEs; at least one case passed, but the routine contract is not fully verified.";
                case "FAILED_COMMIT_OUTCOME_UNKNOWN" -> "Inspect the transaction state and database rows manually before any retry; the commit outcome could not be established.";
                case "FAILED", "UNVERIFIED" -> "Review the SQLSTATE, sanitized diagnostic, and result contract before any retry.";
                default -> "Review each failed or unverified case, its SQLSTATE, result contract, and transaction outcome before any manual retry.";
            };
        }
        RoutineReport report() {
            return new RoutineReport(routine.signature(), routine.procedure() ? "PROCEDURE" : "w".equals(routine.kind()) ? "WINDOW_FUNCTION" : "FUNCTION",
                    routine.analysis().classification(), routine.analysis().operations().stream().sorted().toList(),
                    routine.parameters().stream().map(p -> p.name() + " " + p.mode() + " " + p.sqlType() + (p.hasDefault() ? " DEFAULT" : "")).toList(),
                    routine.resultType(), routine.analysis().dependencySignatures(), state, Duration.between(started, Instant.now()).toMillis(), cases, reasons,
                    routine.language(), routine.analysis().targets().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, entry -> entry.getValue().stream().sorted().toList())),
                    routine.hasInternalTransactionControl(), outcome(),
                    routine.analysis().targets().entrySet().stream().filter(entry -> entry.getValue().contains("SELECT"))
                            .map(Map.Entry::getKey).sorted().toList(),
                    routine.analysis().targets().entrySet().stream().filter(entry -> entry.getValue().stream()
                                    .anyMatch(Set.of("INSERT", "UPDATE", "DELETE", "MERGE")::contains))
                            .map(Map.Entry::getKey).sorted().toList(),
                    routine.resultColumns().stream().map(column -> column.name() + " " + column.sqlType()).toList(),
                    remediationGuidance());
        }
    }

    private record CaseOutcome(boolean verified, String evidence, long affectedRows, String reason,
                               Map<String, List<Map<String, Object>>> inserted) { }
    private record Verification(boolean verified, String evidence, long affectedRows, String reason,
                                Map<String, List<Map<String, Object>>> inserted) {
        static Verification no(String reason) { return new Verification(false, "Verification failed.", 0, reason, Map.of()); }
        static Verification no(String reason, long affectedRows) { return new Verification(false, "Verification failed.", affectedRows, reason, Map.of()); }
    }
}
