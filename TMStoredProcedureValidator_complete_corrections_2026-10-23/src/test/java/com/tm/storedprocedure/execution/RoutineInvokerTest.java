package com.tm.storedprocedure.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.tm.storedprocedure.domain.GeneratedInput;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import com.tm.storedprocedure.domain.RoutineTestCase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutineInvokerTest {
    @Test
    void buildsProcedureCallWithTypedInputAndOutputParameters() {
        RoutineDefinition routine = routine("save_item", "p", List.of(
                new RoutineParameter(1, "p_id", "i", "integer", false),
                new RoutineParameter(2, "p_result", "o", "text", false)));
        RoutineTestCase testCase = testCase(Map.of("p_id", new GeneratedInput(8, "integer", "test", false)), Set.of());

        assertThat(RoutineInvoker.invocationSql(routine, testCase))
                .isEqualTo("CALL \"public\".\"save_item\"(?::integer, NULL::text)");
    }

    @Test
    void omitsDefaultedFunctionArgumentsUsingNamedNotation() {
        RoutineDefinition routine = routine("lookup", "f", List.of(
                new RoutineParameter(1, "p_id", "i", "integer", false),
                new RoutineParameter(2, "p_locale", "i", "text", true)));
        RoutineTestCase testCase = testCase(Map.of("p_id", new GeneratedInput(8, "integer", "test", false)), Set.of("p_locale"));

        assertThat(RoutineInvoker.invocationSql(routine, testCase))
                .isEqualTo("SELECT * FROM \"public\".\"lookup\"(\"p_id\" => ?::integer)");
    }

    private static RoutineDefinition routine(String name, String kind, List<RoutineParameter> parameters) {
        return new RoutineDefinition(1, "public", name, kind, "plpgsql", "", "", "", "void", false,
                "v", false, 0, parameters, List.of(), Set.of(), null);
    }
    private static RoutineTestCase testCase(Map<String, GeneratedInput> inputs, Set<String> omitted) {
        return new RoutineTestCase("case", "NON_NULL", inputs, omitted);
    }
}
