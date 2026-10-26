package com.tm.storedprocedure.domain;

import java.util.Map;
import java.util.Set;

public record RoutineTestCase(String caseId, String variant, Map<String, GeneratedInput> inputs, Set<String> omittedParameters) {
    public RoutineTestCase {
        inputs = Map.copyOf(inputs);
        omittedParameters = Set.copyOf(omittedParameters);
    }
    public Map<String, Object> values() {
        return inputs.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().value()));
    }
}
