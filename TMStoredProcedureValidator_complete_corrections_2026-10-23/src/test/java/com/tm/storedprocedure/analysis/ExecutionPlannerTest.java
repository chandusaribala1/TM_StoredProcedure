package com.tm.storedprocedure.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tm.storedprocedure.domain.OperationType;
import com.tm.storedprocedure.domain.ForeignKey;
import com.tm.storedprocedure.domain.TableColumn;
import com.tm.storedprocedure.domain.TableMetadata;
import com.tm.storedprocedure.domain.RoutineAnalysis;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.metadata.TableMetadataService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionPlannerTest {
    @Test
    void schedulesWritesBeforeIndependentReadsAndHonorsRoutineDependencies() {
        ExecutionPlanner planner = new ExecutionPlanner(mock(TableMetadataService.class));
        RoutineDefinition read = routine(1, "read", OperationType.READ, Set.of("SELECT"), Map.of(), List.of());
        RoutineDefinition writer = routine(2, "write", OperationType.WRITE, Set.of("INSERT"), Map.of(), List.of());
        RoutineDefinition dependent = routine(3, "dependent", OperationType.READ, Set.of("SELECT"), Map.of(), List.of("public.write()"));

        ExecutionPlanner.Plan plan = planner.order(List.of(read, dependent, writer));

        assertThat(plan.dependencyCycles()).isEmpty();
        List<String> order = plan.ordered().stream().map(RoutineDefinition::name).toList();
        assertThat(order.getFirst()).isEqualTo("write");
        assertThat(order.indexOf("dependent")).isGreaterThan(order.indexOf("write"));
    }

    @Test
    void doesNotInventDependenciesBetweenIndependentWritersToTheSameTable() {
        ExecutionPlanner planner = new ExecutionPlanner(mock(TableMetadataService.class));
        RoutineDefinition update = routineWithTarget(4, "a_update", OperationType.WRITE, Set.of("UPDATE"), "public.items");
        RoutineDefinition insert = routineWithTarget(5, "z_insert", OperationType.WRITE, Set.of("INSERT"), "public.items");

        ExecutionPlanner.Plan plan = planner.order(List.of(update, insert));

        assertThat(plan.dependencyCycles()).isEmpty();
        assertThat(plan.dependencies().get("public.a_update()")).isEmpty();
        assertThat(plan.requiredDependencies().get("public.a_update()")).isEmpty();
    }

    @Test
    void ordersParentInsertRoutinesBeforeChildInsertRoutinesUsingForeignKeyMetadata() {
        TableMetadataService metadata = mock(TableMetadataService.class);
        when(metadata.describe("public", "children")).thenReturn(new TableMetadata("public", "children",
                List.of(new TableColumn("id", "integer", false, null, false, "", false),
                        new TableColumn("parent_id", "integer", false, null, false, "", false)),
                List.of("id"), List.of(), List.of(new ForeignKey("fk_child_parent", "parent_id", "public", "parents", "id", "NO ACTION")),
                false, false, false));
        ExecutionPlanner planner = new ExecutionPlanner(metadata);
        RoutineDefinition child = routineWithTarget(11, "a_child_insert", OperationType.WRITE, Set.of("INSERT"), "public.children");
        RoutineDefinition parent = routineWithTarget(12, "z_parent_insert", OperationType.WRITE, Set.of("INSERT"), "public.parents");

        ExecutionPlanner.Plan plan = planner.order(List.of(child, parent));

        assertThat(plan.ordered().stream().map(RoutineDefinition::name).toList())
                .containsSubsequence("z_parent_insert", "a_child_insert");
        assertThat(plan.requiredDependencies().get("public.a_child_insert()")).isEmpty();
    }

    @Test
    void reportsOnlyActualCycleMembersAndNotTheirDownstreamDependents() {
        ExecutionPlanner planner = new ExecutionPlanner(mock(TableMetadataService.class));
        RoutineDefinition first = routine(6, "first", OperationType.READ, Set.of("SELECT"), Map.of(), List.of("public.second()"));
        RoutineDefinition second = routine(7, "second", OperationType.READ, Set.of("SELECT"), Map.of(), List.of("public.first()"));
        RoutineDefinition downstream = routine(8, "downstream", OperationType.READ, Set.of("SELECT"), Map.of(), List.of("public.second()"));

        ExecutionPlanner.Plan plan = planner.order(List.of(first, second, downstream));

        assertThat(plan.dependencyCycles()).containsExactlyInAnyOrder("public.first()", "public.second()");
        assertThat(plan.dependencyCycles()).doesNotContain("public.downstream()");
    }

    private static RoutineDefinition routine(long oid, String name, OperationType type, Set<String> operations,
                                             Map<String, Set<String>> targets, List<String> dependencies) {
        RoutineAnalysis analysis = new RoutineAnalysis(type, operations, targets, Map.of(), dependencies, List.of(), false);
        return new RoutineDefinition(oid, "public", name, "f", "sql", "", "", "SELECT 1", "integer",
                false, "i", false, 0, List.of(), List.of(), Set.of(), analysis);
    }
    private static RoutineDefinition routineWithTarget(long oid, String name, OperationType type, Set<String> operations, String target) {
        RoutineAnalysis analysis = new RoutineAnalysis(type, operations, Map.of(target, operations), Map.of(), List.of(), List.of(), false);
        return new RoutineDefinition(oid, "public", name, "p", "plpgsql", "", "", "", "void",
                false, "v", false, 0, List.of(), List.of(), Set.of(), analysis);
    }
}
