package com.tm.storedprocedure.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.domain.GeneratedInput;
import com.tm.storedprocedure.domain.OperationType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonReportWriterTest {
    @TempDir Path temp;

    @Test
    void redactsSensitiveNamesAndWritesSeparateAtomicRunReports() throws Exception {
        assertThat(SafeValueRenderer.render("customer_email", new GeneratedInput("generated@example.test", "text", "COLUMN_METADATA", false)).value())
                .isEqualTo("[REDACTED]");
        assertThat(SafeValueRenderer.render("p_reference", new GeneratedInput("existing-value", "text", "EXISTING_REFERENCE", true)).value())
                .isEqualTo("[REDACTED]");

        ValidatorProperties properties = new ValidatorProperties();
        properties.setReportDirectory(temp.resolve("reports").toString());
        JsonReportWriter writer = new JsonReportWriter(properties);
        Instant now = Instant.now();
        Path first = writer.write(report("11111111-1111-4111-8111-111111111111", now));
        Path second = writer.write(report("22222222-2222-4222-8222-222222222222", now));

        assertThat(first).exists();
        assertThat(second).exists().isNotEqualTo(first);
        assertThat(Files.readString(first)).contains("startedAt", "database", "routines").doesNotContain("password", "generated@example.test");
        assertThat(Files.list(first.getParent()).filter(path -> path.getFileName().toString().endsWith(".tmp")).count()).isZero();
    }

    @Test
    void replacesThePriorCheckpointForTheSameRun() throws Exception {
        ValidatorProperties properties = new ValidatorProperties();
        properties.setReportDirectory(temp.resolve("reports").toString());
        JsonReportWriter writer = new JsonReportWriter(properties);
        String runId = "33333333-3333-4333-8333-333333333333";
        Path first = writer.write(report(runId, Instant.parse("2026-01-01T00:00:00Z")));
        Path second = writer.write(report(runId, Instant.parse("2026-01-02T00:00:00Z")));

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(second)).contains("2026-01-02T00:00:00Z");
        try (var files = Files.list(second.getParent())) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp")).count()).isZero();
        }
    }

    @Test
    void treatsPreviouslyInvokedWriteRoutinesAsAtMostOnce() {
        ValidatorProperties properties = new ValidatorProperties();
        properties.setReportDirectory(temp.resolve("reports").toString());
        JsonReportWriter writer = new JsonReportWriter(properties);
        Instant now = Instant.now();
        TestCaseReport committed = new TestCaseReport("case-1", "NON_NULL", "SUCCESS", List.of(), List.of(), 1,
                true, true, "public.items: inserted=1", 1L, "COMMITTED", null, null, null);
        RoutineReport routine = new RoutineReport("public.add_item(integer)", "PROCEDURE", OperationType.WRITE,
                List.of("INSERT"), List.of("p_id i integer"), null, List.of(), "VERIFIED", 1,
                List.of(committed), List.of(), "plpgsql", java.util.Map.of("public.items", List.of("INSERT")), false,
                "VERIFIED_AND_COMMITTED", List.of(), List.of("public.items"), List.of(), "No remediation required.");
        writer.write(new RunReport("44444444-4444-4444-8444-444444444444", now, now, "tm", "public",
                "COMPLETED", 1, 1, 1, 1, 1, 1, 0, 0, 0, List.of(), List.of(routine)));

        var history = writer.previousWriteAttempts("tm", "public");

        assertThat(history.diagnostics()).isEmpty();
        assertThat(history.routines()).containsEntry("public.add_item(integer)", List.of("44444444-4444-4444-8444-444444444444"));
        assertThat(writer.previousWriteAttempts("other", "public").routines()).isEmpty();
    }

    private static RunReport report(String id, Instant now) {
        return new RunReport(id, now, now, "tm", "public", "COMPLETED", 1, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }
}
