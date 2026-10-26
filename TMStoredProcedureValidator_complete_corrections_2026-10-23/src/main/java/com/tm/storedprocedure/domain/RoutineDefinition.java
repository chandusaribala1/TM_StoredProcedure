package com.tm.storedprocedure.domain;

import java.util.List;
import java.util.Set;

public record RoutineDefinition(
        long oid,
        String schema,
        String name,
        String kind,
        String language,
        String identityArguments,
        String definition,
        String source,
        String resultType,
        boolean returnsSet,
        String volatility,
        boolean securityDefiner,
        int defaultArgumentCount,
        List<RoutineParameter> parameters,
        List<RoutineResultColumn> resultColumns,
        Set<Long> dependencyOids,
        RoutineAnalysis analysis) {
    public RoutineDefinition {
        parameters = List.copyOf(parameters);
        resultColumns = List.copyOf(resultColumns);
        dependencyOids = Set.copyOf(dependencyOids);
    }
    public String signature() {
        return schema + "." + name + "(" + identityArguments + ")";
    }
    public RoutineDefinition withAnalysis(RoutineAnalysis value) {
        return new RoutineDefinition(oid, schema, name, kind, language, identityArguments,
                definition, source, resultType, returnsSet, volatility, securityDefiner,
                defaultArgumentCount, parameters, resultColumns, dependencyOids, value);
    }
    /** True when PostgreSQL reports an anonymous RECORD result (including SETOF record).
     * Such functions cannot be called with SELECT * unless a concrete column contract is known.
     */
    public boolean anonymousRecordResult() {
        if (resultType == null) return false;
        String normalized = resultType.trim().replaceAll("(?i)^SETOF\\s+", "").trim();
        return normalized.equalsIgnoreCase("record") || normalized.matches("(?is).*\\brecord\\s*\\[\\].*");
    }
    public boolean procedure() { return "p".equals(kind); }
    public boolean hasInternalTransactionControl() {
        return analysis != null && analysis.internalTransactionControl();
    }
}
