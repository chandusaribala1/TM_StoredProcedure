package com.tm.storedprocedure.domain;

public record RoutineParameter(
        int position,
        String name,
        String mode,
        String sqlType,
        boolean hasDefault) {
    public boolean input() {
        return "i".equals(mode) || "b".equals(mode) || "v".equals(mode);
    }
    public boolean output() {
        return "o".equals(mode) || "b".equals(mode) || "t".equals(mode);
    }
}
