package com.tm.storedprocedure.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tm.storedprocedure.analysis.RoutineAnalyzer;
import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.domain.OperationType;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RoutineRepositoryTest {
    @Test
    void discoversProceduresAndFunctionsIncludingOutputModesBeyondTheExecutionLimit() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ValidatorProperties properties = new ValidatorProperties();
        properties.setMaxRoutines(1);
        RoutineRepository repository = new RoutineRepository(jdbc, properties, new RoutineAnalyzer());

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> mapper = invocation.getArgument(1);
            Object[] arguments = Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length);
            if (sql.equals(RoutineRepository.DISCOVERY_SQL)) {
                assertThat(sql)
                        .contains("FROM pg_catalog.pg_proc p")
                        .contains("JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace")
                        .contains("WHERE n.nspname = ? AND p.prokind IN ('f', 'p', 'w')")
                        .contains("pg_get_function_identity_arguments(p.oid)");
                assertThat(arguments).containsExactly("public1");
                return List.of(
                        mapRow(mapper, routineRow(101, "function_with_outputs", "f", "sql",
                                "IN p_input integer, INOUT p_value bigint", "SELECT 1", "record"), 0),
                        mapRow(mapper, routineRow(102, "function_with_outputs", "f", "sql",
                                "IN p_input text", "SELECT 2", "integer"), 1),
                        mapRow(mapper, routineRow(103, "procedure_with_input", "p", "plpgsql",
                                "IN p_id integer", "BEGIN PERFORM 1; END", "void"), 2));
            }
            if (sql.contains("SELECT u.ordinality::integer")) {
                long oid = (Long) arguments[0];
                if (oid == 101L) {
                    assertThat(sql).contains("p.proallargtypes IS NOT NULL").contains("p.proargmodes[u.ordinality]");
                    return List.of(
                            mapRow(mapper, parameterRow(1, "p_input", "i", "integer"), 0),
                            mapRow(mapper, parameterRow(2, "p_output", "o", "text"), 1),
                            mapRow(mapper, parameterRow(3, "p_value", "b", "bigint"), 2));
                }
                if (oid == 102L) return List.of(mapRow(mapper, parameterRow(1, "p_input", "i", "text"), 0));
                return List.of(mapRow(mapper, parameterRow(1, "p_id", "i", "integer"), 0));
            }
            if (sql.contains("SELECT COALESCE(NULLIF(p.proargnames[u.ordinality]")) {
                long oid = (Long) arguments[0];
                if (oid == 101L) {
                    assertThat(sql).contains("m.mode::text IN ('o', 'b', 't')");
                    return List.of(
                            mapRow(mapper, resultColumnRow("p_output", "text"), 0),
                            mapRow(mapper, resultColumnRow("p_value", "bigint"), 1));
                }
                return List.of();
            }
            if (sql.contains("SELECT DISTINCT d.refobjid") || sql.contains("SELECT a.attname")) return List.of();
            throw new AssertionError("Unexpected metadata SQL: " + sql);
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        List<RoutineDefinition> routines = repository.discover();

        assertThat(routines).hasSize(3);
        assertThat(routines).extracting(RoutineDefinition::kind).containsExactly("f", "f", "p");
        assertThat(routines).extracting(routine -> routine.analysis().classification())
                .containsExactly(OperationType.READ, OperationType.READ, OperationType.READ);
        assertThat(routines.getFirst().parameters())
                .extracting(RoutineParameter::mode)
                .containsExactly("i", "o", "b");
        assertThat(routines.getFirst().parameters())
                .extracting(RoutineParameter::input)
                .containsExactly(true, false, true);
        assertThat(routines.getFirst().parameters())
                .extracting(RoutineParameter::output)
                .containsExactly(false, true, true);
        assertThat(routines.getFirst().resultColumns())
                .extracting(column -> column.name() + ":" + column.sqlType())
                .containsExactly("p_output:text", "p_value:bigint");
        assertThat(routines.stream().map(RoutineDefinition::signature).distinct()).hasSize(3);
        assertThat(routines.stream().filter(routine -> routine.name().equals("function_with_outputs"))
                .map(RoutineDefinition::signature).distinct()).hasSize(2);
    }

    private static Object mapRow(RowMapper<?> mapper, ResultSet resultSet, int row) throws SQLException {
        return mapper.mapRow(resultSet, row);
    }

    private static ResultSet routineRow(long oid, String name, String kind, String language,
                                        String identityArguments, String source, String resultType) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(oid);
        when(rs.getString(2)).thenReturn("public1");
        when(rs.getString(3)).thenReturn(name);
        when(rs.getString(4)).thenReturn(kind);
        when(rs.getString(5)).thenReturn(language);
        when(rs.getString(6)).thenReturn(identityArguments);
        when(rs.getString(7)).thenReturn("CREATE " + kind + " " + name);
        when(rs.getString(8)).thenReturn(source);
        when(rs.getString(9)).thenReturn(resultType);
        when(rs.getBoolean(10)).thenReturn(false);
        when(rs.getString(11)).thenReturn("v");
        when(rs.getBoolean(12)).thenReturn(false);
        when(rs.getInt(13)).thenReturn(0);
        return rs;
    }

    private static ResultSet resultColumnRow(String name, String sqlType) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(name);
        when(rs.getString(2)).thenReturn(sqlType);
        return rs;
    }

    private static ResultSet parameterRow(int position, String name, String mode, String sqlType) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt(1)).thenReturn(position);
        when(rs.getString(2)).thenReturn(name);
        when(rs.getString(3)).thenReturn(mode);
        when(rs.getString(4)).thenReturn(sqlType);
        return rs;
    }
}
