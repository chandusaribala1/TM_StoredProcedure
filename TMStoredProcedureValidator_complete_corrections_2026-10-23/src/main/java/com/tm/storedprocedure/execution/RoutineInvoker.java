package com.tm.storedprocedure.execution;

import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import com.tm.storedprocedure.domain.RoutineTestCase;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RoutineInvoker {
    private final JdbcTemplate jdbc;
    private final ValidatorProperties properties;
    public RoutineInvoker(JdbcTemplate jdbc, ValidatorProperties properties) { this.jdbc = jdbc; this.properties = properties; }

    public RoutineOutput invoke(RoutineDefinition routine, RoutineTestCase testCase) {
        String sql = invocationSql(routine, testCase);
        return jdbc.execute((ConnectionCallback<RoutineOutput>) connection -> {
            if (routine.hasInternalTransactionControl() && !connection.getAutoCommit()) {
                throw new SQLException("Internal transaction-control routine was invoked inside a managed transaction.", "25001");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(properties.getQueryTimeoutSeconds());
                bind(statement, routine, testCase);
                boolean hasResult = statement.execute();
                Long updateCount = statement.getUpdateCount() >= 0 ? (long) statement.getUpdateCount() : null;
                int totalRows = 0;
                boolean truncated = false;
                List<OutputColumn> columns = List.of();
                while (hasResult || statement.getUpdateCount() != -1) {
                    if (hasResult) {
                        try (ResultSet result = statement.getResultSet()) {
                            if (result != null) {
                                ResultSetMetaData md = result.getMetaData();
                                List<OutputColumn> found = new ArrayList<>();
                                for (int i = 1; i <= md.getColumnCount(); i++) found.add(new OutputColumn(md.getColumnLabel(i), md.getColumnTypeName(i)));
                                if (columns.isEmpty()) columns = List.copyOf(found);
                                while (result.next()) {
                                    totalRows++;
                                    if (totalRows > properties.getMaxOutputRows()) { truncated = true; break; }
                                }
                            }
                        }
                        if (truncated) break;
                    }
                    hasResult = statement.getMoreResults(PreparedStatement.CLOSE_CURRENT_RESULT);
                    int count = statement.getUpdateCount();
                    if (count >= 0) updateCount = (updateCount == null ? 0 : updateCount) + count;
                    if (!hasResult && count == -1) break;
                }
                return new RoutineOutput(totalRows, columns, truncated, updateCount);
            }
        });
    }

    static String invocationSql(RoutineDefinition routine, RoutineTestCase testCase) {
        List<RoutineParameter> parameters = routine.parameters();
        boolean named = !testCase.omittedParameters().isEmpty()
                && parameters.stream().allMatch(p -> !p.name().matches("arg[0-9]+"));
        List<String> args = new ArrayList<>();
        for (RoutineParameter p : parameters) {
            if (!routine.procedure() && !p.input()) continue;
            if (testCase.omittedParameters().contains(p.name())) continue;
            String value = p.input() ? "?::" + safeType(p.sqlType()) : "NULL::" + safeType(p.sqlType());
            args.add(named ? quote(p.name()) + " => " + value : value);
        }
        String name = quote(routine.schema()) + "." + quote(routine.name());
        return routine.procedure() ? "CALL " + name + "(" + String.join(", ", args) + ")"
                : "SELECT * FROM " + name + "(" + String.join(", ", args) + ")";
    }

    private static void bind(PreparedStatement statement, RoutineDefinition routine, RoutineTestCase testCase) throws SQLException {
        int index = 1;
        for (RoutineParameter p : routine.parameters()) {
            if (!routine.procedure() && !p.input()) continue;
            if (testCase.omittedParameters().contains(p.name())) continue;
            if (p.input()) statement.setObject(index++, testCase.inputs().get(p.name()).value());
        }
    }

    private static String safeType(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(c ->
                !(Character.isLetterOrDigit(c) || c == '_' || c == '"' || c == '.' || c == ','
                        || c == ' ' || c == '(' || c == ')' || c == '[' || c == ']'))) {
            throw new IllegalArgumentException("Unsupported PostgreSQL type name from catalog metadata.");
        }
        return value;
    }
    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    public record OutputColumn(String name, String type) { }
    public record RoutineOutput(int rowCount, List<OutputColumn> columns, boolean truncated, Long jdbcUpdateCount) {
        public RoutineOutput { columns = List.copyOf(columns); }
    }
}
