package com.tm.storedprocedure.domain;

public record ForeignKey(
        String name,
        String localColumn,
        String referencedSchema,
        String referencedTable,
        String referencedColumn,
        String deleteAction) { }
