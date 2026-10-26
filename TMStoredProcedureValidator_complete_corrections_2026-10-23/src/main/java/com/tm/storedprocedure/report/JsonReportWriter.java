package com.tm.storedprocedure.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tm.storedprocedure.config.ValidatorProperties;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class JsonReportWriter {
    private final ObjectMapper mapper;
    private final Path directory;

    public JsonReportWriter(ValidatorProperties properties) {
        this.directory = Path.of(properties.getReportDirectory()).toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Path write(RunReport report) {
        Path destination = directory.resolve("run-" + safeId(report.runId()) + ".json").normalize();
        if (!destination.startsWith(directory)) throw new IllegalArgumentException("Report path escaped configured report directory.");
        Path temp = null;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, ".run-" + report.runId() + "-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), report);
            replaceWithRetry(temp, destination);
            temp = null;
            return destination;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist execution report.", exception);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    /**
     * Reads existing reports as an at-most-once guard for write routines. Routines with any
     * prior non-skipped invocation are not automatically replayed on later application starts.
     * If a prior report cannot be read, callers must block writes because commit history is unknown.
     */
    public WriteAttemptHistory previousWriteAttempts(String database, String schema) {
        if (!Files.isDirectory(directory)) return new WriteAttemptHistory(Map.of(), List.of());
        Map<String, Set<String>> attempts = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString().matches("run-[A-Za-z0-9-]{16,64}\\.json"))
                    .sorted().toList()) {
                try {
                    var root = mapper.readTree(path.toFile());
                    if (!database.equals(root.path("database").asText()) || !schema.equals(root.path("schema").asText())) continue;
                    String runId = root.path("runId").asText(path.getFileName().toString());
                    var routines = root.path("routines");
                    if (!routines.isArray()) continue;
                    for (var routine : routines) {
                        if (!"WRITE".equals(routine.path("classification").asText())) continue;
                        var cases = routine.path("testCases");
                        if (!cases.isArray()) continue;
                        boolean invokedOrOutcomeUnknown = false;
                        for (var testCase : cases) {
                            if (!"SKIPPED".equals(testCase.path("status").asText())) {
                                invokedOrOutcomeUnknown = true;
                                break;
                            }
                        }
                        if (invokedOrOutcomeUnknown) attempts.computeIfAbsent(routine.path("signature").asText(), ignored -> new LinkedHashSet<>()).add(runId);
                    }
                } catch (IOException | RuntimeException failure) {
                    diagnostics.add("Could not read prior execution report " + path.getFileName()
                            + "; previous write outcomes are unknown and new writes must be blocked.");
                }
            }
        } catch (IOException | RuntimeException failure) {
            diagnostics.add("Could not enumerate the configured report directory; previous write outcomes are unknown and new writes must be blocked.");
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        attempts.forEach((signature, runs) -> immutable.put(signature, List.copyOf(runs)));
        return new WriteAttemptHistory(Map.copyOf(immutable), List.copyOf(diagnostics));
    }

    public record WriteAttemptHistory(Map<String, List<String>> routines, List<String> diagnostics) {
        public WriteAttemptHistory {
            routines = Map.copyOf(routines);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static void replaceWithRetry(Path source, Path destination) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                try {
                    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                if (attempt == 2) break;
                try {
                    Thread.sleep(25L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
        throw lastFailure;
    }

    private static String safeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9-]{16,64}")) throw new IllegalArgumentException("Invalid run id.");
        return value;
    }
}
