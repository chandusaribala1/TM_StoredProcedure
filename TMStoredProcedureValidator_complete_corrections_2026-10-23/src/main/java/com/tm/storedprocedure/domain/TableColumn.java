package com.tm.storedprocedure.domain;

public record TableColumn(
        String name,
        String sqlType,
        boolean nullable,
        String defaultExpression,
        boolean identity,
        String identityGeneration,
        boolean generated) { }
