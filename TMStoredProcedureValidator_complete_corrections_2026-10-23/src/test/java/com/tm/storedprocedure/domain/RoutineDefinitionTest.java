package com.tm.storedprocedure.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutineDefinitionTest {
    @Test
    void detectsSetofRecordAsAnonymousResult() {
        assertThat(routine("SETOF record").anonymousRecordResult()).isTrue();
    }

    @Test
    void detectsRecordWithWhitespaceAndCaseDifferences() {
        assertThat(routine(" setof   RECORD ").anonymousRecordResult()).isTrue();
    }

    @Test
    void doesNotTreatConcreteScalarOrCompositeResultsAsAnonymousRecord() {
        assertThat(routine("integer").anonymousRecordResult()).isFalse();
        assertThat(routine("SETOF public1.my_result_type").anonymousRecordResult()).isFalse();
    }

    private static RoutineDefinition routine(String resultType) {
        return new RoutineDefinition(1, "public1", "sample", "f", "sql", "", "", "", resultType,
                true, "v", false, 0, List.of(), List.of(), Set.of(), null);
    }
}
