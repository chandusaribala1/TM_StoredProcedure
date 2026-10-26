package com.tm.storedprocedure.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SafeErrorsTest {
    @Test
    void logsExceptionMessageAndStackFramesWithoutCredentialValues() {
        IllegalStateException failure = new IllegalStateException(
                "Could not connect to jdbc:postgresql://dbuser:dbpass@localhost/tm?user=dbuser&password=dbpass");

        String message = SafeErrors.logMessage(failure);
        String trace = SafeErrors.stackTrace(failure);

        assertThat(message).contains("IllegalStateException", "Could not connect")
                .doesNotContain("dbuser", "dbpass");
        assertThat(trace).contains("IllegalStateException", "at ")
                .doesNotContain("dbuser", "dbpass");
    }

    @Test
    void preservesSafeSqlStateAndPrimaryDiagnosticWhileRedactingInputValuesAndDetails() {
        SQLException failure = new SQLException(
                "ERROR: invalid input syntax for type integer: \"sensitive-value\"\nDetail: rejected row had secret payload",
                "22P02");

        SafeErrors.Details details = SafeErrors.from(failure);
        String logMessage = SafeErrors.logMessage(failure);

        assertThat(details.sqlState()).isEqualTo("22P02");
        assertThat(details.message()).contains("invalid input syntax for type integer", "[VALUE REDACTED]")
                .doesNotContain("sensitive-value", "secret payload");
        assertThat(logMessage).contains("SQLSTATE=22P02", "[VALUE REDACTED]")
                .doesNotContain("sensitive-value", "secret payload");
    }
}
