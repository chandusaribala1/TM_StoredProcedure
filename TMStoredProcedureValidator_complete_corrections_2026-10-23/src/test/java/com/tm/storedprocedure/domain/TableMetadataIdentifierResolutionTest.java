package com.tm.storedprocedure.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TableMetadataIdentifierResolutionTest {
    private TableMetadata metadata(List<TableColumn> columns) {
        return new TableMetadata("public", "TM_Prospect", columns, List.of(), List.of(), List.of(),
                false, false, false, "r");
    }

    @Test
    void exactColumnMatchTakesPrecedence() {
        TableColumn exact = new TableColumn("APP_ID", "integer", false, null, false, "", false);
        TableColumn alternateCase = new TableColumn("app_id", "text", true, null, false, "", false);
        assertThat(metadata(List.of(exact, alternateCase)).column("APP_ID")).isSameAs(exact);
    }

    @Test
    void uniqueCaseInsensitiveColumnMatchResolvesToActualColumn() {
        TableColumn actual = new TableColumn("APP_DT", "timestamp without time zone", true, null, false, "", false);
        assertThat(metadata(List.of(actual)).column("app_dt")).isSameAs(actual);
    }

    @Test
    void ambiguousCaseInsensitiveColumnMatchDoesNotGuess() {
        TableColumn upper = new TableColumn("APP_ID", "integer", false, null, false, "", false);
        TableColumn lower = new TableColumn("app_id", "text", true, null, false, "", false);
        assertThat(metadata(List.of(upper, lower)).column("App_Id")).isNull();
    }
}
