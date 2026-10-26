package com.tm.storedprocedure.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.tm.storedprocedure.analysis.ExecutionPlanner;
import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.data.GeneratedRecordRegistry;
import com.tm.storedprocedure.data.TestCaseFactory;
import com.tm.storedprocedure.domain.ColumnBinding;
import com.tm.storedprocedure.domain.GeneratedInput;
import com.tm.storedprocedure.domain.OperationType;
import com.tm.storedprocedure.domain.RoutineAnalysis;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import com.tm.storedprocedure.domain.RoutineTestCase;
import com.tm.storedprocedure.domain.TableColumn;
import com.tm.storedprocedure.domain.TableMetadata;
import com.tm.storedprocedure.metadata.RoutineRepository;
import com.tm.storedprocedure.metadata.TableMetadataService;
import com.tm.storedprocedure.report.JsonReportWriter;
import com.tm.storedprocedure.report.RunReport;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RunCoordinatorTest {
    @TempDir Path temp;

    @Test
    void preservesOneVerifiedCommitWhenAnIndependentRoutineFails() throws Exception {
        Fixture fixture = new Fixture();
        RoutineDefinition first = insertRoutine(1, "first", "public.first_table");
        RoutineDefinition second = insertRoutine(2, "second", "public.second_table");
        fixture.prepare(List.of(first, second));
        fixture.snapshots(first, empty("public.first_table"), inserted("public.first_table", 10));
        fixture.snapshots(second, empty("public.second_table"));
        when(fixture.invoker.invoke(eq(first), any())).thenReturn(output());
        when(fixture.invoker.invoke(eq(second), any())).thenThrow(new IllegalStateException("synthetic failure"));

        RunReport report = fixture.coordinator.runOnce();

        assertThat(report.status()).isEqualTo("COMPLETED_WITH_FAILURES");
        assertThat(report.committedWrites()).isEqualTo(1);
        assertThat(report.failedCases()).isEqualTo(1);
        assertThat(fixture.transactions.commits).isEqualTo(1);
        assertThat(fixture.transactions.rollbacks).isEqualTo(1);
        assertThat(fixture.registry.records("public.first_table")).contains(Map.of("id", BigInteger.TEN));
        assertThat(report.routines()).extracting(r -> r.status()).containsExactly("VERIFIED", "FAILED");
        assertThat(report.routines().get(1).outcome()).isEqualTo("FAILED_AND_ROLLED_BACK");
        assertThat(report.failedRoutines()).isEqualTo(1);
        assertThat(report.partiallyVerifiedRoutines()).isZero();
    }

    @Test
    void distinguishesPartiallyVerifiedRoutineFromOneWhoseEveryCaseFailed() throws Exception {
        Fixture fixture = new Fixture();
        RoutineDefinition routine = new RoutineDefinition(9, "public", "read_pair", "f", "sql", "", "", "SELECT 1", "integer",
                false, "i", false, 0, List.of(), List.of(), Set.of(), new RoutineAnalysis(OperationType.READ,
                Set.of("SELECT"), Map.of(), Map.of(), List.of(), List.of(), false));
        fixture.prepare(List.of(routine));
        when(fixture.testCases.create(routine)).thenReturn(List.of(
                new RoutineTestCase("case-good", "NON_NULL", Map.of(), Set.of()),
                new RoutineTestCase("case-bad", "DATABASE_DEFAULT", Map.of(), Set.of())));
        when(fixture.invoker.invoke(eq(routine), any())).thenReturn(new RoutineInvoker.RoutineOutput(1,
                List.of(new RoutineInvoker.OutputColumn("read_pair", "int4")), false, null))
                .thenThrow(new IllegalStateException("synthetic read failure"));

        RunReport report = fixture.coordinator.runOnce();

        assertThat(report.routines().getFirst().status()).isEqualTo("PARTIAL");
        assertThat(report.routines().getFirst().outcome()).isEqualTo("PARTIALLY_VERIFIED");
        assertThat(report.verifiedRoutines()).isZero();
        assertThat(report.failedRoutines()).isZero();
        assertThat(report.partiallyVerifiedRoutines()).isEqualTo(1);
        assertThat(report.attemptedRoutines()).isEqualTo(1);
    }

    @Test
    void rollsBackAnInsertThatCannotBeVerified() throws Exception {
        Fixture fixture = new Fixture();
        RoutineDefinition routine = insertRoutine(3, "unverified", "public.items");
        fixture.prepare(List.of(routine));
        fixture.snapshots(routine, empty("public.items"), empty("public.items"));
        when(fixture.invoker.invoke(eq(routine), any())).thenReturn(output());

        RunReport report = fixture.coordinator.runOnce();

        assertThat(report.successfulCases()).isEqualTo(1);
        assertThat(report.verifiedCases()).isZero();
        assertThat(report.committedWrites()).isZero();
        assertThat(fixture.transactions.commits).isZero();
        assertThat(fixture.transactions.rollbacks).isEqualTo(1);
        assertThat(report.routines().getFirst().testCases().getFirst().transactionOutcome()).isEqualTo("ROLLED_BACK_UNVERIFIED");
    }

    @Test
    void reportsUnknownCommitOutcomeWithoutClaimingRollbackOrCommit() throws Exception {
        Fixture fixture = new Fixture();
        RoutineDefinition routine = insertRoutine(7, "commit_uncertain", "public.items");
        fixture.prepare(List.of(routine));
        fixture.snapshots(routine, empty("public.items"), inserted("public.items", 12));
        when(fixture.invoker.invoke(eq(routine), any())).thenReturn(output());
        fixture.transactions.failCommit = true;

        RunReport report = fixture.coordinator.runOnce();

        var result = report.routines().getFirst().testCases().getFirst();
        assertThat(result.executionSucceeded()).isTrue();
        assertThat(result.verificationSucceeded()).isTrue();
        assertThat(result.transactionOutcome()).isEqualTo("COMMIT_OUTCOME_UNKNOWN");
        assertThat(report.committedWrites()).isZero();
    }

    @Test
    void blocksDeleteBeforeInvocationWhenItCouldAffectReferencingRows() throws Exception {
        Fixture fixture = new Fixture();
        RoutineDefinition routine = deleteRoutine();
        fixture.prepare(List.of(routine));
        when(fixture.tableMetadata.hasReferencingForeignKey("public", "items")).thenReturn(true);

        RunReport report = fixture.coordinator.runOnce();

        assertThat(report.blockedRoutines()).isEqualTo(1);
        assertThat(report.routines().getFirst().reasons()).anyMatch(reason -> reason.contains("referenced by another table"));
        verify(fixture.invoker, never()).invoke(any(), any());
        assertThat(fixture.transactions.commits).isZero();
    }

    @Test
    void blocksWritesWhenRowLevelSecurityPreventsCompleteVerification() throws Exception {
        Fixture fixture = new Fixture();
        RoutineDefinition routine = insertRoutine(6, "rls_write", "public.items");
        fixture.prepare(List.of(routine));
        when(fixture.tableMetadata.describe("public", "items")).thenReturn(new TableMetadata("public", "items",
                List.of(new TableColumn("id", "integer", false, null, false, "", false)), List.of("id"), List.of(), List.of(), false, false, true));

        RunReport report = fixture.coordinator.runOnce();

        assertThat(report.blockedRoutines()).isEqualTo(1);
        assertThat(report.routines().getFirst().reasons()).anyMatch(reason -> reason.contains("row-level security"));
        verify(fixture.invoker, never()).invoke(any(), any());
    }

    @Test
    void blocksWritesWhenTableRewriteRulesCouldAddUntrackedEffects() throws Exception {
        Fixture fixture = new Fixture();
        RoutineDefinition routine = insertRoutine(8, "rule_write", "public.items");
        fixture.prepare(List.of(routine));
        when(fixture.tableMetadata.describe("public", "items")).thenReturn(new TableMetadata("public", "items",
                List.of(new TableColumn("id", "integer", false, null, false, "", false)), List.of("id"), List.of(), List.of(), false, true, false));

        RunReport report = fixture.coordinator.runOnce();

        assertThat(report.blockedRoutines()).isEqualTo(1);
        assertThat(report.routines().getFirst().reasons()).anyMatch(reason -> reason.contains("rewrite rules"));
        verify(fixture.invoker, never()).invoke(any(), any());
    }

    @Test
    void recordsAllDiscoveredRoutinesButBlocksExecutionWhenRoutineLimitIsExceeded() throws Exception {
        Fixture fixture = new Fixture();
        fixture.properties.setMaxRoutines(1);
        RoutineDefinition first = insertRoutine(30, "first_over_limit", "public.first_table");
        RoutineDefinition second = insertRoutine(31, "second_over_limit", "public.second_table");
        fixture.prepare(List.of(first, second));

        RunReport report = fixture.coordinator.runOnce();

        assertThat(report.discoveredRoutines()).isEqualTo(2);
        assertThat(report.blockedRoutines()).isEqualTo(2);
        assertThat(report.attemptedCases()).isZero();
        assertThat(report.committedWrites()).isZero();
        assertThat(report.runReasons()).anyMatch(reason -> reason.contains("exceeding validator.max-routines=1"));
        verify(fixture.invoker, never()).invoke(any(), any());
        verify(fixture.planner, never()).order(any());
    }

    @Test
    void verifiesReadResultShapeSeparatelyFromExecutionSuccess() throws Exception {
        Fixture fixture = new Fixture();
        RoutineDefinition routine = new RoutineDefinition(4, "public", "read_value", "f", "sql", "", "", "SELECT 1", "integer",
                false, "i", false, 0, List.of(), List.of(), Set.of(), new RoutineAnalysis(OperationType.READ, Set.of("SELECT"),
                Map.of(), Map.of(), List.of(), List.of(), false));
        fixture.prepare(List.of(routine));
        when(fixture.testCases.create(routine)).thenReturn(List.of(new RoutineTestCase("case-read", "NON_NULL", Map.of(), Set.of())));
        when(fixture.invoker.invoke(eq(routine), any())).thenReturn(new RoutineInvoker.RoutineOutput(1,
                List.of(new RoutineInvoker.OutputColumn("read_value", "int4")), false, null));

        RunReport report = fixture.coordinator.runOnce();

        assertThat(report.successfulCases()).isEqualTo(1);
        assertThat(report.verifiedCases()).isEqualTo(1);
        assertThat(report.committedWrites()).isZero();
        assertThat(report.routines().getFirst().testCases().getFirst().executionSucceeded()).isTrue();
        assertThat(report.routines().getFirst().testCases().getFirst().verificationSucceeded()).isTrue();
    }

    private final class Fixture {
        final ValidatorProperties properties = new ValidatorProperties();
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final RoutineRepository routineRepository = mock(RoutineRepository.class);
        final TableMetadataService tableMetadata = mock(TableMetadataService.class);
        final ExecutionPlanner planner = mock(ExecutionPlanner.class);
        final TestCaseFactory testCases = mock(TestCaseFactory.class);
        final GeneratedRecordRegistry registry = new GeneratedRecordRegistry();
        final RoutineInvoker invoker = mock(RoutineInvoker.class);
        final RowSnapshotService snapshots = mock(RowSnapshotService.class);
        final PostgresRunLock runLock = mock(PostgresRunLock.class);
        final RecordingTransactions transactions = new RecordingTransactions();
        final RunCoordinator coordinator;

        Fixture() throws Exception {
            properties.setReportDirectory(temp.resolve("reports").toString());
            DataSourceProperties dataSource = new DataSourceProperties();
            dataSource.setUsername("external-test-user");
            dataSource.setPassword("external-test-password");
            when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("tm");
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(true);
            when(runLock.withLock(anyString(), anyString(), any())).thenAnswer(invocation -> {
                PostgresRunLock.Work<?> work = invocation.getArgument(2);
                return new PostgresRunLock.LockResult<>(true, work.run());
            });
            coordinator = new RunCoordinator(jdbc, properties, routineRepository, tableMetadata, planner, testCases,
                    registry, invoker, snapshots, runLock, new JsonReportWriter(properties), transactions, dataSource);
        }

        void prepare(List<RoutineDefinition> routines) {
            when(routineRepository.discover()).thenReturn(routines);
            when(planner.order(routines)).thenReturn(new ExecutionPlanner.Plan(routines, Set.of(), Map.of()));
            for (RoutineDefinition routine : routines) {
                routine.analysis().targets().keySet().forEach(target -> {
                    when(tableMetadata.describe("public", target.substring("public.".length()))).thenReturn(table(target.substring("public.".length())));
                });
            }
            when(testCases.create(any())).thenAnswer(invocation -> List.of(testCase()));
        }

        void snapshots(RoutineDefinition routine, RowSnapshotService.SnapshotSet... values) {
            when(snapshots.capture(eq(routine.analysis().targets().keySet()))).thenReturn(values[0], values.length > 1 ? values[1] : values[0]);
        }
    }

    private static RoutineDefinition insertRoutine(long oid, String name, String target) {
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.WRITE, Set.of("INSERT"), Map.of(target, Set.of("INSERT")),
                Map.of("p_value", List.of(new ColumnBinding(target, "value", "INSERT"))), List.of(), List.of(), false);
        return new RoutineDefinition(oid, "public", name, "p", "plpgsql", "", "", "", "void", false, "v", false,
                0, List.of(new RoutineParameter(1, "p_value", "i", "text", false)), List.of(), Set.of(), analysis);
    }
    private static RoutineDefinition deleteRoutine() {
        String target = "public.items";
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.WRITE, Set.of("DELETE"), Map.of(target, Set.of("DELETE")),
                Map.of("p_id", List.of(new ColumnBinding(target, "id", "PREDICATE"))), List.of(), List.of(), false);
        return new RoutineDefinition(5, "public", "delete_item", "p", "plpgsql", "", "", "", "void", false, "v", false,
                0, List.of(new RoutineParameter(1, "p_id", "i", "integer", false)), List.of(), Set.of(), analysis);
    }
    private static RoutineTestCase testCase() {
        return new RoutineTestCase("case-write", "NON_NULL", Map.of("p_value", new GeneratedInput("synthetic", "text", "COLUMN_METADATA", false)), Set.of());
    }
    private static TableMetadata table(String name) {
        return new TableMetadata("public", name, List.of(new TableColumn("id", "integer", false, null, false, "", false),
                new TableColumn("value", "text", true, null, false, "", false)), List.of("id"), List.of(), List.of(), false, false, false);
    }
    private static RoutineInvoker.RoutineOutput output() { return new RoutineInvoker.RoutineOutput(0, List.of(), false, null); }
    private static RowSnapshotService.SnapshotSet empty(String table) {
        return snapshot(table, Map.of());
    }
    private static RowSnapshotService.SnapshotSet inserted(String table, long id) {
        return snapshot(table, Map.of("row-" + id, new RowSnapshotService.RowRecord("hash-" + id, Map.of("id", BigInteger.valueOf(id)))));
    }
    private static RowSnapshotService.SnapshotSet snapshot(String table, Map<String, RowSnapshotService.RowRecord> rows) {
        return new RowSnapshotService.SnapshotSet(Map.of(table, new RowSnapshotService.TableSnapshot(table, List.of("id"), rows)));
    }

    private static final class RecordingTransactions implements PlatformTransactionManager {
        int commits;
        int rollbacks;
        boolean failCommit;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) {
            if (failCommit) throw new IllegalStateException("synthetic commit failure");
            if (status.isRollbackOnly()) rollbacks++; else commits++;
        }
        @Override public void rollback(TransactionStatus status) { rollbacks++; }
    }
}
