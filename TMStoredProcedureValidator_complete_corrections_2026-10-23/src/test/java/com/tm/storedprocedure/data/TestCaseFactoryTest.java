package com.tm.storedprocedure.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.domain.ColumnBinding;
import com.tm.storedprocedure.domain.ForeignKey;
import com.tm.storedprocedure.domain.OperationType;
import com.tm.storedprocedure.domain.RoutineAnalysis;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import com.tm.storedprocedure.domain.TableColumn;
import com.tm.storedprocedure.domain.TableConstraint;
import com.tm.storedprocedure.domain.TableMetadata;
import com.tm.storedprocedure.metadata.TableMetadataService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class TestCaseFactoryTest {
    private final TableMetadataService metadata = mock(TableMetadataService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GeneratedRecordRegistry registry = new GeneratedRecordRegistry();
    private final TestCaseFactory factory = new TestCaseFactory(metadata, jdbc, registry, new ValidatorProperties());

    @Test
    void createsNonNullReadVariantForUnboundParameterWithoutAssumingNullIsValid() {
        RoutineParameter input = new RoutineParameter(1, "p_text", "i", "text", false);
        RoutineDefinition routine = routine("search", List.of(input), new RoutineAnalysis(OperationType.READ,
                Set.of("SELECT"), Map.of(), Map.of(), List.of(), List.of(), false));

        var cases = factory.create(routine);

        assertThat(cases).extracting(c -> c.variant()).containsExactly("NON_NULL");
        assertThat(cases.getFirst().inputs().get("p_text").value()).asString().startsWith("Test value ");
    }

    @Test
    void generatesRealisticFirstAndLastNamesForTextParameters() {
        RoutineDefinition firstNameRoutine = routine("add_person", List.of(
                new RoutineParameter(1, "p_first_name", "i", "character varying", false),
                new RoutineParameter(2, "p_last_name", "i", "character varying", false)),
                new RoutineAnalysis(OperationType.READ, Set.of("SELECT"), Map.of(), Map.of(), List.of(), List.of(), false));

        var inputs = factory.create(firstNameRoutine).getFirst().inputs();

        assertThat(inputs.get("p_first_name").value()).isIn("John", "Jhon", "James", "Robert", "David", "Emma", "Maya", "Chandana", "Aarav", "Riya");
        assertThat(inputs.get("p_last_name").value()).isIn("Byer", "Miller", "Sharma", "Taylor", "Patel", "Reed", "Reddy", "Wilson", "Kumar", "Brown");
    }

    @Test
    void generatesDomainShapedEmailInsteadOfRandomCharacterNoise() {
        RoutineParameter input = new RoutineParameter(1, "p_email", "i", "character varying", false);
        RoutineDefinition routine = routine("search_by_email", List.of(input), new RoutineAnalysis(OperationType.READ,
                Set.of("SELECT"), Map.of(), Map.of(), List.of(), List.of(), false));

        var generated = factory.create(routine).getFirst().inputs().get("p_email").value().toString();

        assertThat(generated).contains("@gmail.com").startsWith("user");
    }

    @Test
    void generatesSemanticValuesInsteadOfReusingObservedShortValues() {
        RoutineParameter input = new RoutineParameter(1, "p_state", "i", "character", false);
        TableMetadata table = new TableMetadata("public", "items",
                List.of(new TableColumn("state", "character(2)", false, null, false, "", false)),
                List.of("id"), List.of(), List.of(), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(table);
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.WRITE, Set.of("INSERT"),
                Map.of("public.items", Set.of("INSERT")),
                Map.of("p_state", List.of(new ColumnBinding("public.items", "state", "INSERT"))), List.of(), List.of(), false);
        RoutineDefinition routine = routine("add_state", List.of(input), analysis);

        var testCase = factory.create(routine).getFirst();

        assertThat(testCase.inputs().get("p_state").value()).isIn("CA", "NY", "TX", "FL", "WA");
        assertThat(testCase.inputs().get("p_state").value()).isNotEqualTo("WI");
    }

    @Test
    void generatesNumericTextForAnExplicitIntegerCastParameter() {
        RoutineParameter input = new RoutineParameter(1, "p_pro_id", "i", "character", false);
        TableMetadata table = new TableMetadata("public", "TM_Application",
                List.of(new TableColumn("APP_ID", "integer", false, null, false, "", false)),
                List.of("APP_ID"), List.of(), List.of(), false, false, false);
        when(metadata.describe("public", "TM_Application")).thenReturn(table);
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.READ, Set.of("SELECT"),
                Map.of("public.TM_Application", Set.of("SELECT")),
                Map.of("p_pro_id", List.of(new ColumnBinding("public.TM_Application", "APP_ID", "PREDICATE_CAST:integer"))),
                List.of(), List.of(), false);
        RoutineDefinition routine = routine("has_application", List.of(input), analysis);

        var testCase = factory.create(routine).getFirst();

        assertThat(testCase.inputs().get("p_pro_id").value()).isInstanceOf(String.class);
        assertThat(Integer.parseInt((String) testCase.inputs().get("p_pro_id").value())).isPositive();
        assertThat(testCase.inputs().get("p_pro_id").source()).isEqualTo("EXPLICIT_CAST_COMPATIBLE");
    }

    @Test
    void generatesOnlyOneWriteCaseAndUsesDatabaseDefaultsWhenAvailable() {
        RoutineParameter required = new RoutineParameter(1, "p_id", "i", "integer", false);
        RoutineParameter optional = new RoutineParameter(2, "p_note", "i", "text", true);
        RoutineDefinition routine = routine("add_with_default", List.of(required, optional),
                new RoutineAnalysis(OperationType.WRITE, Set.of("INSERT"), Map.of("public.items", Set.of("INSERT")),
                        Map.of(), List.of(), List.of(), false));

        var cases = factory.create(routine);

        assertThat(cases).hasSize(1);
        assertThat(cases.getFirst().variant()).isEqualTo("DATABASE_DEFAULT");
        assertThat(cases.getFirst().inputs()).containsKey("p_id").doesNotContainKey("p_note");
        assertThat(cases.getFirst().omittedParameters()).containsExactly("p_note");
    }

    @Test
    void generatesCheckConstrainedValuesAndRespectsNonNullability() {
        RoutineParameter input = new RoutineParameter(1, "p_amount", "i", "integer", false);
        TableMetadata table = new TableMetadata("public", "items",
                List.of(new TableColumn("amount", "integer", false, null, false, "", false)), List.of("id"),
                List.of(new TableConstraint("items_amount_check", "CHECK", "CHECK ((amount >= 1) AND (amount <= 9))")), List.of(), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(table);
        Map<String, List<ColumnBinding>> bindings = Map.of("p_amount", List.of(new ColumnBinding("public.items", "amount", "INSERT")));
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.WRITE, Set.of("INSERT"), Map.of("public.items", Set.of("INSERT")),
                bindings, List.of(), List.of(), false);
        RoutineDefinition routine = routine("add_item", List.of(input), analysis);

        var cases = factory.create(routine);

        assertThat(cases).hasSize(1);
        assertThat(cases.getFirst().inputs().get("p_amount").value()).isEqualTo(5);
    }

    @Test
    void doesNotOmitDefaultedParameterWhenItMapsToNotNullTargetColumn() {
        RoutineParameter input = new RoutineParameter(1, "p_required_name", "i", "character varying", true);
        TableMetadata table = new TableMetadata("public", "items",
                List.of(new TableColumn("required_name", "varchar(40)", false, null, false, "", false)),
                List.of("required_name"), List.of(), List.of(), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(table);
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.WRITE, Set.of("INSERT"),
                Map.of("public.items", Set.of("INSERT")), Map.of(), List.of(), List.of(), false);
        RoutineDefinition routine = routine("add_item", List.of(input), analysis);

        var testCase = factory.create(routine).getFirst();

        assertThat(testCase.inputs()).containsKey("p_required_name");
        assertThat(testCase.omittedParameters()).doesNotContain("p_required_name");
        assertThat(testCase.inputs().get("p_required_name").value()).asString().hasSizeGreaterThan(1);
    }

    @Test
    void deleteInputsRequireCurrentRunOwnedPrimaryKeys() {
        RoutineParameter input = new RoutineParameter(1, "p_id", "i", "integer", false);
        TableMetadata table = new TableMetadata("public", "items",
                List.of(new TableColumn("id", "integer", false, null, false, "", false)), List.of("id"), List.of(), List.of(), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(table);
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.WRITE, Set.of("DELETE"), Map.of("public.items", Set.of("DELETE")),
                Map.of("p_id", List.of(new ColumnBinding("public.items", "id", "PREDICATE"))), List.of(), List.of(), false);
        RoutineDefinition routine = routine("delete_item", List.of(input), analysis);

        assertThatThrownBy(() -> factory.create(routine)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current-run generated record");
    }

    @Test
    void requiresResolvableSequenceForExplicitIdentityAndSequenceBackedInsertValues() {
        RoutineParameter input = new RoutineParameter(1, "p_id", "i", "integer", false);
        TableMetadata identity = new TableMetadata("public", "items",
                List.of(new TableColumn("id", "integer", false, null, true, "BY DEFAULT", false)), List.of("id"), List.of(), List.of(), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(identity);
        RoutineDefinition identityRoutine = routine("add_identity", List.of(input), insertAnalysis("id"));
        assertThatThrownBy(() -> factory.create(identityRoutine)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not resolve the PostgreSQL identity sequence");

        TableMetadata sequence = new TableMetadata("public", "items",
                List.of(new TableColumn("id", "integer", false, "nextval('items_id_seq'::regclass)", false, "", false)),
                List.of("id"), List.of(), List.of(), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(sequence);
        RoutineDefinition sequenceRoutine = routine("add_sequence", List.of(input), insertAnalysis("id"));
        assertThatThrownBy(() -> factory.create(sequenceRoutine)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not resolve the PostgreSQL sequence");
    }

    @Test
    void obtainsExplicitIdentityInputFromTheActualPostgresSequence() {
        RoutineParameter input = new RoutineParameter(1, "p_id", "i", "integer", false);
        TableMetadata identity = new TableMetadata("public", "items",
                List.of(new TableColumn("id", "integer", false, null, true, "BY DEFAULT", false)),
                List.of("id"), List.of(), List.of(), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(identity);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.eq("SELECT pg_catalog.pg_get_serial_sequence(?, ?)"),
                org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq("\"public\".\"items\""),
                org.mockito.ArgumentMatchers.eq("id"))).thenReturn("\"public\".\"items_id_seq\"");
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.eq("SELECT pg_catalog.nextval(?::regclass)"),
                org.mockito.ArgumentMatchers.eq(Object.class), org.mockito.ArgumentMatchers.eq("\"public\".\"items_id_seq\"")))
                .thenReturn(10L);
        RoutineDefinition identityRoutine = routine("add_identity", List.of(input), insertAnalysis("id"));

        var generated = factory.create(identityRoutine).getFirst().inputs().get("p_id");

        assertThat(generated.value()).isEqualTo(10L);
        assertThat(generated.source()).isEqualTo("IDENTITY_SEQUENCE_CANDIDATE");
    }

    @Test
    void registryCanReuseNonNullUniqueValuesFromACompleteVerifiedRow() {
        registry.add("public.parents", Map.of("id", 42, "parent_code", "PARENT-42", "display_name", "Test Parent"));

        assertThat(registry.findRow("public.parents", List.of("parent_code")))
                .contains(Map.of("id", 42, "parent_code", "PARENT-42", "display_name", "Test Parent"));
        assertThat(registry.owns("public.parents", Map.of("id", 42))).isTrue();
    }

    @Test
    void reusesVerifiedCurrentRunParentRecordsForRequiredForeignKeys() {
        registry.add("public.parents", Map.of("id", 42));
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(ResultSetExtractor.class)))
                .thenReturn(Map.of());
        TableMetadata child = new TableMetadata("public", "items",
                List.of(new TableColumn("id", "integer", false, null, false, "", false),
                        new TableColumn("parent_id", "integer", false, null, false, "", false)),
                List.of("id"), List.of(), List.of(new ForeignKey("fk_items_parent", "parent_id", "public", "parents", "id", "NO ACTION")), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(child);
        RoutineParameter input = new RoutineParameter(1, "p_parent_id", "i", "integer", false);
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.WRITE, Set.of("INSERT"), Map.of("public.items", Set.of("INSERT")),
                Map.of("p_parent_id", List.of(new ColumnBinding("public.items", "parent_id", "INSERT"))), List.of(), List.of(), false);
        RoutineDefinition routine = routine("add_child", List.of(input), analysis);

        var testCase = factory.create(routine).getFirst();

        assertThat(testCase.inputs().get("p_parent_id").value()).isEqualTo(42);
        assertThat(testCase.inputs().get("p_parent_id").source()).isEqualTo("GENERATED_PREREQUISITE");
        assertThat(testCase.inputs().get("p_parent_id").reusedReference()).isTrue();
    }

    @Test
    void generatesTimestampTextForTextParameterMappedToTimestampColumn() {
        RoutineParameter input = new RoutineParameter(1, "p_update_dt", "i", "character varying", false);
        TableMetadata table = new TableMetadata("public", "items",
                List.of(new TableColumn("update_dt", "timestamp without time zone", false, null, false, "", false)),
                List.of("id"), List.of(), List.of(), false, false, false);
        when(metadata.describe("public", "items")).thenReturn(table);
        RoutineAnalysis analysis = new RoutineAnalysis(OperationType.WRITE, Set.of("INSERT"),
                Map.of("public.items", Set.of("INSERT")),
                Map.of("p_update_dt", List.of(new ColumnBinding("public.items", "update_dt", "INSERT"))), List.of(), List.of(), false);
        RoutineDefinition routine = routine("add_item", List.of(input), analysis);

        Object generated = factory.create(routine).getFirst().inputs().get("p_update_dt").value();

        assertThat(generated).asString().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void generatesTypedTemporalValuesForPostgresDateTimestampAndTimestamptz() {
        RoutineDefinition dateRoutine = routine("read_date", List.of(new RoutineParameter(1, "p_date", "i", "date", false)),
                new RoutineAnalysis(OperationType.READ, Set.of("SELECT"), Map.of(), Map.of(), List.of(), List.of(), false));
        RoutineDefinition timestampRoutine = routine("read_timestamp", List.of(new RoutineParameter(1, "p_ts", "i", "timestamp without time zone", false)),
                new RoutineAnalysis(OperationType.READ, Set.of("SELECT"), Map.of(), Map.of(), List.of(), List.of(), false));
        RoutineDefinition timestamptzRoutine = routine("read_timestamptz", List.of(new RoutineParameter(1, "p_ts", "i", "timestamp with time zone", false)),
                new RoutineAnalysis(OperationType.READ, Set.of("SELECT"), Map.of(), Map.of(), List.of(), List.of(), false));

        assertThat(factory.create(dateRoutine).getFirst().inputs().get("p_date").value()).isInstanceOf(java.time.LocalDate.class);
        assertThat(factory.create(timestampRoutine).getFirst().inputs().get("p_ts").value()).isInstanceOf(java.time.LocalDateTime.class);
        assertThat(factory.create(timestamptzRoutine).getFirst().inputs().get("p_ts").value()).isInstanceOf(java.time.OffsetDateTime.class);
    }

    private static RoutineAnalysis insertAnalysis(String column) {
        return new RoutineAnalysis(OperationType.WRITE, Set.of("INSERT"), Map.of("public.items", Set.of("INSERT")),
                Map.of("p_id", List.of(new ColumnBinding("public.items", column, "INSERT"))), List.of(), List.of(), false);
    }

    private static RoutineDefinition routine(String name, List<RoutineParameter> parameters, RoutineAnalysis analysis) {
        return new RoutineDefinition(1, "public", name, "f", "plpgsql", "", "", "", "integer",
                false, "v", false, 0, parameters, List.of(), Set.of(), analysis);
    }
}
