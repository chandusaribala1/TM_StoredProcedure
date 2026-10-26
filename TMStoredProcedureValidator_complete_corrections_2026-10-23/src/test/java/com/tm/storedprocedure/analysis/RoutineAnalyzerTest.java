package com.tm.storedprocedure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.tm.storedprocedure.domain.OperationType;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutineAnalyzerTest {
    private final RoutineAnalyzer analyzer = new RoutineAnalyzer();

    @Test
    void classifiesReadAndScalarReturnFunctions() {
        RoutineDefinition query = routine(1, "find_item", "f", "sql", "SELECT item_id FROM public.items WHERE item_id = p_id", List.of(parameter(1, "p_id", "integer")));
        RoutineDefinition scalar = routine(2, "constant_value", "f", "plpgsql", "BEGIN RETURN 7; END", List.of());

        List<RoutineDefinition> analyzed = analyzer.analyzeAll(List.of(query, scalar));

        assertThat(analyzed).extracting(r -> r.analysis().classification()).containsExactly(OperationType.READ, OperationType.READ);
        assertThat(analyzed).allMatch(r -> r.analysis().safe());
    }

    @Test
    void identifiesWriteTargetsAndParameterBindings() {
        RoutineDefinition insert = routine(3, "add_item", "p", "plpgsql",
                "BEGIN INSERT INTO public.items (item_id, title) VALUES (p_id, p_title); END",
                List.of(parameter(1, "p_id", "integer"), parameter(2, "p_title", "text")));

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(insert)).getFirst();

        assertThat(analyzed.analysis().classification()).isEqualTo(OperationType.WRITE);
        assertThat(analyzed.analysis().targets()).containsEntry("public.items", Set.of("INSERT"));
        assertThat(analyzed.analysis().parameterBindings()).containsKeys("p_id", "p_title");
        assertThat(analyzed.analysis().safe()).isTrue();
    }

    @Test
    void preservesQuotedTargetAndColumnNamesForInsertUpdateAndDelete() {
        RoutineDefinition writer = routine(35, "quoted_writer", "p", "plpgsql",
                "BEGIN INSERT INTO public.\"TM_Application\" (\"App_ID\") VALUES (p_id); "
                        + "UPDATE public.\"TM_Application\" SET \"Last_Name\" = p_name WHERE \"App_ID\" = p_id; "
                        + "DELETE FROM public.\"TM_Application\" WHERE \"App_ID\" = p_id; END",
                List.of(parameter(1, "p_id", "integer"), parameter(2, "p_name", "text")));

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(writer)).getFirst();

        assertThat(analyzed.analysis().classification()).isEqualTo(OperationType.WRITE);
        assertThat(analyzed.analysis().operations()).containsExactlyInAnyOrder("INSERT", "UPDATE", "DELETE");
        assertThat(analyzed.analysis().targets()).containsEntry("public.TM_Application", Set.of("SELECT", "INSERT", "UPDATE", "DELETE"));
        assertThat(analyzed.analysis().parameterBindings().get("p_id"))
                .anySatisfy(binding -> {
                    assertThat(binding.table()).isEqualTo("public.TM_Application");
                    assertThat(binding.column()).isEqualTo("App_ID");
                });
        assertThat(analyzed.analysis().parameterBindings().get("p_name"))
                .anySatisfy(binding -> assertThat(binding.column()).isEqualTo("Last_Name"));
    }

    @Test
    void foldsUnquotedIdentifiersLikePostgresql() {
        RoutineDefinition insert = routine(36, "add_unquoted_item", "p", "plpgsql",
                "BEGIN INSERT INTO PUBLIC.TM_APPLICATION (APP_ID) VALUES (p_id); END",
                List.of(parameter(1, "p_id", "integer")));

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(insert)).getFirst();

        assertThat(analyzed.analysis().targets()).containsEntry("public.tm_application", Set.of("INSERT"));
        assertThat(analyzed.analysis().parameterBindings().get("p_id"))
                .anySatisfy(binding -> assertThat(binding.column()).isEqualTo("app_id"));
    }

    @Test
    void treatsMixedBehaviorAndUnboundedDynamicSqlConservatively() {
        RoutineDefinition mixed = routine(4, "copy_item", "p", "plpgsql",
                "BEGIN INSERT INTO public.items (item_id) VALUES (p_id); SELECT item_id FROM public.items; END",
                List.of(parameter(1, "p_id", "integer")));
        RoutineDefinition dynamic = routine(5, "dynamic_work", "p", "plpgsql", "BEGIN EXECUTE format('DELETE FROM %I', p_table); END", List.of());
        RoutineDefinition stringOnly = routine(6, "quoted_words", "f", "sql", "SELECT 'DELETE FROM public.items'; -- UPDATE public.items", List.of());

        List<RoutineDefinition> analyzed = analyzer.analyzeAll(List.of(mixed, dynamic, stringOnly));

        assertThat(analyzed.get(0).analysis().classification()).isEqualTo(OperationType.WRITE);
        assertThat(analyzed.get(1).analysis().safe()).isFalse();
        assertThat(analyzed.get(1).analysis().safetyReasons()).anyMatch(reason -> reason.contains("unsafe"));
        assertThat(analyzed.get(2).analysis().classification()).isEqualTo(OperationType.READ);
        assertThat(analyzed.get(2).analysis().operations()).containsExactly("SELECT");
        assertThat(analyzed.get(2).analysis().targets()).isEmpty();
    }

    @Test
    void ignoresKeywordSpellingInsideCommentsLiteralsAndQuotedIdentifiers() {
        RoutineDefinition routine = routine(37, "quoted_keyword_names", "f", "sql",
                "SELECT \"DROP\" FROM public.items WHERE note = 'CREATE DROP EXECUTE NEXTVAL'; "
                        + "-- DROP TABLE public.items\n/* CREATE TABLE x(id int) */",
                List.of());

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(routine)).getFirst();

        assertThat(analyzed.analysis().classification()).isEqualTo(OperationType.READ);
        assertThat(analyzed.analysis().safe()).isTrue();
        assertThat(analyzed.analysis().targets()).containsEntry("public.items", Set.of("SELECT"));
    }

    @Test
    void reportsTemporaryDdlSeparatelyFromPersistentUnsafeDdl() {
        RoutineDefinition routine = routine(38, "uses_temp", "p", "plpgsql",
                "BEGIN CREATE TEMP TABLE tmp_stage(id integer); DROP TABLE IF EXISTS tmp_stage; END", List.of());

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(routine)).getFirst();

        assertThat(analyzed.analysis().safetyReasons()).anyMatch(reason -> reason.contains("temporary-relation DDL"));
        assertThat(analyzed.analysis().safetyReasons()).noneMatch(reason -> reason.contains("unsafe SQL statement CREATE"));
    }

    @Test
    void recognizesBuiltInSequenceLookupsAndExplainsNonTransactionalSequenceMutation() {
        RoutineDefinition routine = routine(39, "uses_sequence", "p", "plpgsql",
                "BEGIN INSERT INTO public.items (item_id) VALUES (nextval(pg_get_serial_sequence('items', 'item_id'))); END", List.of());

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(routine)).getFirst();

        assertThat(analyzed.analysis().safetyReasons()).anyMatch(reason -> reason.contains("changes sequence state outside transactional rollback"));
        assertThat(analyzed.analysis().safetyReasons()).noneMatch(reason -> reason.contains("Unresolved routine call public.nextval"));
        assertThat(analyzed.analysis().safetyReasons()).noneMatch(reason -> reason.contains("Unresolved routine call public.pg_get_serial_sequence"));
    }

    @Test
    void doesNotTreatAColumnNamedCommitAsTransactionControl() {
        RoutineDefinition routine = routine(40, "commit_column", "f", "sql", "SELECT commit FROM public.items", List.of());

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(routine)).getFirst();

        assertThat(analyzed.analysis().internalTransactionControl()).isFalse();
        assertThat(analyzed.analysis().safe()).isTrue();
    }

    @Test
    void classifiesOnlyReadOrWriteAndBlocksUnrecognizedBehavior() {
        RoutineDefinition unrecognized = routine(7, "unrecognized", "p", "plpgsql", "BEGIN RAISE NOTICE 'hello'; END", List.of());

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(unrecognized)).getFirst();

        assertThat(OperationType.values()).containsExactly(OperationType.READ, OperationType.WRITE);
        assertThat(analyzed.analysis().classification()).isEqualTo(OperationType.WRITE);
        assertThat(analyzed.analysis().safe()).isFalse();
        assertThat(analyzed.analysis().safetyReasons()).anyMatch(reason -> reason.contains("No supported read or write operation"));
    }

    @Test
    void blocksAmbiguousOverloadedRoutineCalls() {
        RoutineDefinition caller = routine(10, "caller", "f", "plpgsql", "BEGIN RETURN public.calculate(1); END", List.of());
        RoutineDefinition overloadOne = routine(11, "calculate", "f", "sql", "SELECT 1", List.of(parameter(1, "value", "integer")));
        RoutineDefinition overloadTwo = routine(12, "calculate", "f", "sql", "SELECT 1", List.of(parameter(1, "value", "text")));

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(caller, overloadOne, overloadTwo)).getFirst();

        assertThat(analyzed.analysis().safe()).isFalse();
        assertThat(analyzed.analysis().safetyReasons()).anyMatch(reason -> reason.contains("overloaded"));
    }

    @Test
    void followsKnownRoutineDependenciesAndPropagatesWrites() {
        RoutineDefinition caller = routine(20, "caller", "f", "plpgsql", "BEGIN PERFORM public.add_item(1); RETURN 1; END", List.of());
        RoutineDefinition writer = routine(21, "add_item", "p", "plpgsql", "BEGIN INSERT INTO public.items (item_id) VALUES (p_id); END", List.of(parameter(1, "p_id", "integer")));

        List<RoutineDefinition> analyzed = analyzer.analyzeAll(List.of(caller, writer));

        assertThat(analyzed.getFirst().analysis().classification()).isEqualTo(OperationType.WRITE);
        assertThat(analyzed.getFirst().analysis().dependencySignatures()).contains("public.add_item()");
        assertThat(analyzed.getFirst().analysis().targets()).containsKey("public.items");
    }

    @Test
    void followsKnownTableValuedRoutineCallsAndPropagatesTemporaryDdlBlock() {
        RoutineDefinition caller = routine(41, "read_wrapper", "f", "sql",
                "SELECT * FROM public.read_with_temp()", List.of());
        RoutineDefinition dependency = routine(42, "read_with_temp", "f", "plpgsql",
                "BEGIN CREATE TEMP TABLE tmp_results(value integer); RETURN; END", List.of());

        List<RoutineDefinition> analyzed = analyzer.analyzeAll(List.of(caller, dependency));

        assertThat(analyzed.getFirst().analysis().dependencySignatures()).contains("public.read_with_temp()");
        assertThat(analyzed.getFirst().analysis().safe()).isFalse();
        assertThat(analyzed.getFirst().analysis().safetyReasons())
                .anyMatch(reason -> reason.contains("temporary-relation DDL"));
    }

    @Test
    void recordsExplicitIntegerCastForACharacterPredicateParameter() {
        RoutineDefinition routine = routine(44, "has_application", "f", "sql",
                "SELECT * FROM public.\"TM_Application\" AS a WHERE a.\"APP_ID\" = CAST(p_pro_id AS integer)",
                List.of(parameter(1, "p_pro_id", "character")));

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(routine)).getFirst();

        assertThat(analyzed.analysis().parameterBindings().get("p_pro_id"))
                .contains(new com.tm.storedprocedure.domain.ColumnBinding("public.TM_Application", "APP_ID", "PREDICATE_CAST:integer"));
    }

    @Test
    void recognizesTrimmedCharacterParameterCastToInteger() {
        RoutineDefinition routine = routine(45, "has_application_trimmed", "f", "plpgsql",
                "BEGIN RETURN QUERY SELECT * FROM public.\"TM_Application\" AS a "
                        + "WHERE a.\"APP_ID\" = btrim(p_pro_id)::integer; END",
                List.of(parameter(1, "p_pro_id", "character")));

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(routine)).getFirst();

        assertThat(analyzed.analysis().parameterBindings().get("p_pro_id"))
                .contains(new com.tm.storedprocedure.domain.ColumnBinding("public.TM_Application", "APP_ID", "PREDICATE_CAST:integer"));
    }

    @Test
    void blocksDynamicSqlAfterReturnQueryEvenWhenNotAtAStatementBoundary() {
        RoutineDefinition routine = routine(43, "dynamic_return", "f", "plpgsql",
                "BEGIN RETURN QUERY EXECUTE format('SELECT %s', 1); END", List.of());

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(routine)).getFirst();

        assertThat(analyzed.analysis().safe()).isFalse();
        assertThat(analyzed.analysis().safetyReasons())
                .anyMatch(reason -> reason.contains("dynamic SQL EXECUTE"));
    }

    @Test
    void analyzesLongIdentifierInputLinearlyWithoutRegexBacktracking() {
        // This is the input shape that made the old unanchored FUNCTION_CALL matcher
        // retry every suffix while looking for an opening parenthesis.
        String source = "SELECT " + "a".repeat(100_000);
        RoutineDefinition routine = routine(30, "long_source", "f", "sql", source, List.of());

        RoutineDefinition analyzed = analyzer.analyzeAll(List.of(routine)).getFirst();

        assertThat(analyzed.analysis().classification()).isEqualTo(OperationType.READ);
        assertThat(analyzed.analysis().operations()).containsExactly("SELECT");
        assertThat(analyzed.analysis().safe()).isTrue();
    }

    @Test
    void blocksOversizedAndMalformedRoutineSourceWithoutRecursing() {
        RoutineDefinition oversized = routine(31, "oversized", "f", "sql", "x".repeat(1_000_001), List.of());
        RoutineDefinition unterminatedComment = routine(32, "bad_comment", "f", "sql", "SELECT 1 /*" + "x".repeat(100_000), List.of());
        RoutineDefinition unterminatedString = routine(33, "bad_string", "f", "sql", "SELECT 'never closed", List.of());
        RoutineDefinition tokenFlood = routine(34, "token_flood", "f", "sql", ";".repeat(100_001), List.of());

        List<RoutineDefinition> analyzed = analyzer.analyzeAll(List.of(oversized, unterminatedComment, unterminatedString, tokenFlood));

        assertThat(analyzed.get(0).analysis().safe()).isFalse();
        assertThat(analyzed.get(0).analysis().safetyReasons()).anyMatch(reason -> reason.contains("analysis limit"));
        assertThat(analyzed.get(1).analysis().safe()).isFalse();
        assertThat(analyzed.get(1).analysis().safetyReasons()).anyMatch(reason -> reason.contains("unterminated block comment"));
        assertThat(analyzed.get(2).analysis().safe()).isFalse();
        assertThat(analyzed.get(2).analysis().safetyReasons()).anyMatch(reason -> reason.contains("unterminated string literal"));
        assertThat(analyzed.get(3).analysis().safe()).isFalse();
        assertThat(analyzed.get(3).analysis().safetyReasons()).anyMatch(reason -> reason.contains("token analysis limit"));
    }

    @Test
    void scansDefaultExpressionCallsWithoutMatchingTextInsideLiteralsOrComments() {
        assertThat(RoutineAnalyzer.findFunctionCalls("'ignored()' || nextval('seq') /* ignored_too() */ || pg_catalog.now()"))
                .containsExactly(new RoutineAnalyzer.FunctionCall(null, "nextval"),
                        new RoutineAnalyzer.FunctionCall("pg_catalog", "now"));
    }

    private static RoutineDefinition routine(long oid, String name, String kind, String language, String source, List<RoutineParameter> parameters) {
        return new RoutineDefinition(oid, "public", name, kind, language, "", "", source,
                kind.equals("p") ? "void" : "integer", false, "v", false, 0, parameters, List.of(), Set.of(), null);
    }
    private static RoutineParameter parameter(int position, String name, String type) {
        return new RoutineParameter(position, name, "i", type, false);
    }
}
