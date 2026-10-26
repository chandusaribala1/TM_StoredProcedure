package com.tm.storedprocedure.runner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.execution.RunCoordinator;
import com.tm.storedprocedure.report.RunReport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class StartupValidationRunnerTest {
    private static final DefaultApplicationArguments ARGUMENTS =
            new DefaultApplicationArguments(new String[0]);

    @Test
    void runsValidationOnceWhenStartupIsEnabled() {
        RunCoordinator coordinator = mock(RunCoordinator.class);
        ValidatorProperties properties = new ValidatorProperties();
        StartupValidationRunner runner = new StartupValidationRunner(coordinator, properties);
        when(coordinator.runOnce()).thenReturn(report());

        runner.run(ARGUMENTS);
        runner.run(ARGUMENTS);

        verify(coordinator, times(1)).runOnce();
    }

    @Test
    void doesNotRunValidationWhenStartupIsDisabled() {
        RunCoordinator coordinator = mock(RunCoordinator.class);
        ValidatorProperties properties = new ValidatorProperties();
        properties.setStartupEnabled(false);

        new StartupValidationRunner(coordinator, properties).run(ARGUMENTS);

        verify(coordinator, never()).runOnce();
    }

    private static RunReport report() {
        Instant now = Instant.now();
        return new RunReport("11111111-1111-4111-8111-111111111111", now, now, "tm", "public",
                "COMPLETED", 1, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }
}
