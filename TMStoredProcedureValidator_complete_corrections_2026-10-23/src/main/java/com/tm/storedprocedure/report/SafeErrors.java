package com.tm.storedprocedure.report;

import java.sql.SQLException;
import java.util.regex.Pattern;

public final class SafeErrors {
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(://)[^:/@\\s]+(:)[^@/\\s]+(@)");
    private static final Pattern URL_QUERY_CREDENTIALS = Pattern.compile("(?i)([?&](?:password|passwd|pwd|user|username|token|secret)=)[^&\\s]+");
    private static final Pattern ASSIGNED_CREDENTIAL = Pattern.compile(
            "(?i)(\\b(?:password|passwd|pwd|username|user|credential|secret|token)\\b\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;&)]+)");
    private static final Pattern AUTH_USER = Pattern.compile("(?i)(\\bfor user\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern INVALID_INPUT_VALUE = Pattern.compile(
            "(?i)(invalid input syntax for type [^:]+:\\s*)(?:\"[^\"]*\"|'(?:''|[^'])*')");

    private SafeErrors() { }
    public static Details from(Throwable throwable) {
        SQLException sql = findSql(throwable);
        if (sql != null) return new Details(sql.getSQLState(), safeSqlMessage(sql));
        if (throwable instanceof IllegalArgumentException argument && argument.getMessage() != null) {
            return new Details(null, redact(argument.getMessage()));
        }
        return new Details(null, throwable == null ? "Unknown failure." : throwable.getClass().getSimpleName() + " occurred; message omitted.");
    }

    /** Returns exception messages suitable for logs after removing common credential forms. */
    public static String logMessage(Throwable throwable) {
        if (throwable == null) return "Unknown failure.";
        SQLException sql = findSql(throwable);
        if (sql != null) return sql.getClass().getSimpleName() + " SQLSTATE=" + sql.getSQLState() + ": " + safeSqlMessage(sql);
        StringBuilder message = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (message.length() > 0) message.append("; caused by ");
            message.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message.append(": ").append(redact(current.getMessage()));
            }
            current = current.getCause();
        }
        return message.toString();
    }

    /** Builds a useful stack trace without forwarding raw exception messages to the logger. */
    public static String stackTrace(Throwable throwable) {
        if (throwable == null) return "";
        StringBuilder trace = new StringBuilder();
        Throwable current = throwable;
        int causes = 0;
        while (current != null && causes++ < 8) {
            if (trace.length() > 0) trace.append(System.lineSeparator()).append("Caused by: ");
            trace.append(current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 80); i++) {
                trace.append(System.lineSeparator()).append("    at ").append(frames[i]);
            }
            current = current.getCause();
        }
        return redact(trace.toString());
    }

    private static String safeSqlMessage(SQLException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "Database operation failed.";
        String primary = message.split("\\R", 2)[0].replaceFirst("(?i)^(ERROR|FATAL|PANIC):\\s*", "").trim();
        primary = INVALID_INPUT_VALUE.matcher(primary).replaceAll("$1[VALUE REDACTED]");
        primary = redact(primary);
        return primary.length() > 500 ? primary.substring(0, 500) : primary;
    }

    private static String redact(String value) {
        String redacted = URL_CREDENTIALS.matcher(value).replaceAll("$1[REDACTED]$2[REDACTED]$3");
        redacted = redacted.replaceAll("(?i)(://)[^/@\\s]+@", "$1[REDACTED]@");
        redacted = URL_QUERY_CREDENTIALS.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = ASSIGNED_CREDENTIAL.matcher(redacted).replaceAll("$1[REDACTED]");
        return AUTH_USER.matcher(redacted).replaceAll("$1[REDACTED]");
    }

    private static SQLException findSql(Throwable throwable) {
        for (Throwable cursor = throwable; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof SQLException sql) return sql;
        }
        return null;
    }
    public record Details(String sqlState, String message) { }
}
