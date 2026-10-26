package com.tm.storedprocedure.analysis;

import com.tm.storedprocedure.domain.OperationType;
import com.tm.storedprocedure.domain.RoutineDefinition;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Topological order with writes preferred whenever dependency constraints allow. */
@Component
public class ExecutionPlanner {
    private final com.tm.storedprocedure.metadata.TableMetadataService tables;
    public ExecutionPlanner(com.tm.storedprocedure.metadata.TableMetadataService tables) { this.tables = tables; }

    public Plan order(List<RoutineDefinition> routines) {
        Map<String, RoutineDefinition> bySignature = new HashMap<>();
        routines.forEach(r -> bySignature.put(r.signature(), r));
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, Set<String>> requiredDependencies = new HashMap<>();
        for (RoutineDefinition routine : routines) {
            Set<String> deps = new LinkedHashSet<>();
            if (routine.analysis() != null) for (String signature : routine.analysis().dependencySignatures()) {
                if (bySignature.containsKey(signature)) deps.add(signature);
            }
            dependencies.put(routine.signature(), deps);
            requiredDependencies.put(routine.signature(), new LinkedHashSet<>(deps));
        }
        // Add ordering-only edges for table-level FK prerequisites: routines that INSERT into a
        // referenced parent table are scheduled before routines that INSERT into its child table.
        // These are ordering constraints, not hard routine-call dependencies: if one parent writer
        // fails, other independent parent writers still run, and the child generator can use any
        // verified key registered by a successful parent writer.
        Map<String, List<RoutineDefinition>> insertWritersByTable = new HashMap<>();
        for (RoutineDefinition routine : routines) {
            if (routine.analysis() == null || !routine.analysis().operations().contains("INSERT")) continue;
            routine.analysis().targets().forEach((table, ops) -> {
                if (ops.contains("INSERT")) insertWritersByTable.computeIfAbsent(normalizeTable(table), ignored -> new ArrayList<>()).add(routine);
            });
        }
        for (RoutineDefinition childRoutine : routines) {
            if (childRoutine.analysis() == null || !childRoutine.analysis().operations().contains("INSERT")) continue;
            for (Map.Entry<String, Set<String>> target : childRoutine.analysis().targets().entrySet()) {
                if (!target.getValue().contains("INSERT")) continue;
                String[] parts = splitTable(target.getKey());
                if (parts == null) continue;
                try {
                    var childTable = tables.describe(parts[0], parts[1]);
                    for (var fk : childTable.foreignKeys()) {
                        String parentName = normalizeTable(fk.referencedSchema() + "." + fk.referencedTable());
                        for (RoutineDefinition parentRoutine : insertWritersByTable.getOrDefault(parentName, List.of())) {
                            if (!parentRoutine.signature().equals(childRoutine.signature()))
                                dependencies.get(childRoutine.signature()).add(parentRoutine.signature());
                        }
                    }
                } catch (RuntimeException metadataUnavailable) {
                    // Do not guess a relation or create a dependency when exact metadata is unavailable.
                }
            }
        }
        // The catalog/analyzer call graph remains the required dependency graph. FK edges only
        // determine order; a missing parent key is handled explicitly by TestCaseFactory.
        Set<String> emitted = new HashSet<>();
        List<RoutineDefinition> ordered = new ArrayList<>();
        List<RoutineDefinition> remaining = new ArrayList<>(routines);
        Comparator<RoutineDefinition> priority = Comparator
                .comparingInt((RoutineDefinition routine) -> routine.analysis() == null ? 4 : switch (routine.analysis().classification()) {
                    case WRITE -> 0; case READ -> 1;
                })
                .thenComparing(RoutineDefinition::signature);
        while (!remaining.isEmpty()) {
            List<RoutineDefinition> ready = remaining.stream()
                    .filter(r -> emitted.containsAll(dependencies.getOrDefault(r.signature(), Set.of())))
                    .sorted(priority).toList();
            if (ready.isEmpty()) break;
            for (RoutineDefinition routine : ready) {
                ordered.add(routine);
                emitted.add(routine.signature());
                remaining.remove(routine);
            }
        }
        Set<String> cycle = findCycleMembers(dependencies);
        remaining.sort(priority);
        ordered.addAll(remaining);
        Map<String, Set<String>> immutableDependencies = new HashMap<>();
        dependencies.forEach((key, value) -> immutableDependencies.put(key, Set.copyOf(value)));
        Map<String, Set<String>> immutableRequiredDependencies = new HashMap<>();
        requiredDependencies.forEach((key, value) -> immutableRequiredDependencies.put(key, Set.copyOf(value)));
        return new Plan(List.copyOf(ordered), cycle, Map.copyOf(immutableDependencies), Map.copyOf(immutableRequiredDependencies));
    }
    private static String normalizeTable(String qualified) {
        if (qualified == null) return "";
        String unquoted = qualified.replace("\"", "");
        int dot = unquoted.indexOf('.');
        if (dot < 0) return unquoted;
        return unquoted.substring(0, dot).toLowerCase(java.util.Locale.ROOT) + unquoted.substring(dot);
    }
    private static String[] splitTable(String qualified) {
        if (qualified == null) return null;
        int dot = qualified.indexOf('.');
        if (dot < 1 || dot == qualified.length() - 1) return null;
        return new String[]{qualified.substring(0, dot).replace("\"", ""), qualified.substring(dot + 1).replace("\"", "")};
    }

    private static Set<String> findCycleMembers(Map<String, Set<String>> dependencies) {
        // Kosaraju's algorithm identifies only routines that participate in a cycle. Kahn's
        // residual also contains downstream dependents, which are blocked for a different reason.
        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Set<String>> reverse = new HashMap<>();
        dependencies.keySet().forEach(node -> { graph.put(node, new LinkedHashSet<>()); reverse.put(node, new LinkedHashSet<>()); });
        dependencies.forEach((node, deps) -> deps.stream().filter(dependencies::containsKey).forEach(dependency -> {
            graph.get(node).add(dependency);
            reverse.get(dependency).add(node);
        }));

        Set<String> visited = new HashSet<>();
        List<String> finishOrder = new ArrayList<>(graph.size());
        for (String start : graph.keySet()) {
            if (!visited.add(start)) continue;
            Deque<Frame> stack = new ArrayDeque<>();
            stack.push(new Frame(start, graph.get(start).iterator()));
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (frame.neighbors().hasNext()) {
                    String next = frame.neighbors().next();
                    if (visited.add(next)) stack.push(new Frame(next, graph.get(next).iterator()));
                } else {
                    finishOrder.add(frame.node());
                    stack.pop();
                }
            }
        }

        visited.clear();
        Set<String> cycles = new LinkedHashSet<>();
        for (int i = finishOrder.size() - 1; i >= 0; i--) {
            String start = finishOrder.get(i);
            if (!visited.add(start)) continue;
            Set<String> component = new LinkedHashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                String node = stack.pop();
                component.add(node);
                for (String next : reverse.get(node)) if (visited.add(next)) stack.push(next);
            }
            if (component.size() > 1 || graph.get(start).contains(start)) cycles.addAll(component);
        }
        return Set.copyOf(cycles);
    }
    private record Frame(String node, Iterator<String> neighbors) { }
    public record Plan(List<RoutineDefinition> ordered, Set<String> dependencyCycles, Map<String, Set<String>> dependencies,
                       Map<String, Set<String>> requiredDependencies) {
        public Plan(List<RoutineDefinition> ordered, Set<String> dependencyCycles, Map<String, Set<String>> dependencies) {
            this(ordered, dependencyCycles, dependencies, dependencies);
        }
    }
}
