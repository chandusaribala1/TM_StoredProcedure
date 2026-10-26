package com.tm.storedprocedure.runner;

import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.execution.RunCoordinator;
import com.tm.storedprocedure.report.RunReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Starts one synchronous validation run after the Spring context has initialized. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StartupValidationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupValidationRunner.class);

    private final RunCoordinator coordinator;
    private final ValidatorProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();

    public StartupValidationRunner(RunCoordinator coordinator, ValidatorProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isStartupEnabled()) {
            log.info("startup_validation_disabled database={} schema={}",
                    properties.getDatabase(), properties.getSchema());
            return;
        }
        if (!started.compareAndSet(false, true)) {
            log.warn("startup_validation_duplicate_invocation_suppressed database={} schema={}",
                    properties.getDatabase(), properties.getSchema());
            return;
        }

        RunReport report = coordinator.runOnce();
        Path reportPath = reportPath(report);
        boolean reportExists = reportPath != null && Files.isRegularFile(reportPath);
        log.info("startup_validation_finished runId={} database={} schema={} status={} discoveredRoutines={} analyzedRoutines={} attemptedRoutines={} verifiedRoutines={} failedRoutines={} partiallyVerifiedRoutines={} skippedRoutines={} attemptedCases={} verifiedCases={} committedWrites={} rolledBackWrites={} rowsChanged={} failedCases={} blockedRoutines={} skippedCases={} reportPath={} reportWritten={}",
                report.runId(), report.database(), report.schema(), report.status(), report.discoveredRoutines(),
                report.analyzedRoutines(), report.attemptedRoutines(), report.verifiedRoutines(), report.failedRoutines(),
                report.partiallyVerifiedRoutines(), report.skippedRoutines(), report.attemptedCases(), report.verifiedCases(), report.committedWrites(),
                report.rolledBackWrites(), report.rowsChanged(), report.failedCases(), report.blockedRoutines(), report.skippedCases(),
                reportPath == null ? "unavailable" : reportPath, reportExists);
    }

    private Path reportPath(RunReport report) {
        try {
            Path directory = Path.of(properties.getReportDirectory()).toAbsolutePath().normalize();
            return directory.resolve("run-" + report.runId() + ".json").normalize();
        } catch (InvalidPathException | NullPointerException exception) {
            log.warn("startup_validation_report_path_unavailable runId={} errorType={}",
                    report.runId(), exception.getClass().getSimpleName());
            return null;
        }
    }
}
