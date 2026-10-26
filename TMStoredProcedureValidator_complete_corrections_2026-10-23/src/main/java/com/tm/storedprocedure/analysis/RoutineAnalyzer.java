package com.tm.storedprocedure.analysis;

import com.tm.storedprocedure.domain.ColumnBinding;
import com.tm.storedprocedure.domain.OperationType;
import com.tm.storedprocedure.domain.RoutineAnalysis;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Conservatively analyzes catalog routine source with a bounded, single-pass lexer.
 * Routine source is database supplied input; it must not be fed to backtracking regexes.
 */
@Component
public class RoutineAnalyzer {
    static final int MAX_SOURCE_CHARS = 1_000_000;
    private static final int MAX_SOURCE_TOKENS = 100_000;

    /** Finds SQL call-shaped identifiers without regex backtracking, for catalog expressions. */
    public static List<FunctionCall> findFunctionCalls(String source) {
        if (source == null || source.length() > MAX_SOURCE_CHARS) throw new IllegalArgumentException("SQL expression exceeds the safe analysis limit.");
        LexResult lexed = Lexer.lex(source);
        if (lexed.malformed != null) throw new IllegalArgumentException(lexed.malformed);
        List<Token> tokens = lexed.tokens;
        List<FunctionCall> calls = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!token.identifier() || (i > 0 && tokens.get(i - 1).is("."))) continue;
            String schema = null;
            int nameAt = i;
            if (i + 2 < tokens.size() && tokens.get(i + 1).is(".") && tokens.get(i + 2).identifier()) {
                schema = token.value;
                nameAt = i + 2;
            }
            if (nextIs(tokens, nameAt + 1, "(")) calls.add(new FunctionCall(schema, tokens.get(nameAt).value));
        }
        return List.copyOf(calls);
    }

    public record FunctionCall(String schema, String name) { }

    private static final Set<String> PURE_CALLS = Set.of(
            "COALESCE", "NULLIF", "GREATEST", "LEAST", "LOWER", "UPPER", "TRIM", "BTRIM",
            "STRING_TO_ARRAY", "ARRAY_TO_STRING", "STRING_TO_TABLE",
            "LENGTH", "CHAR_LENGTH", "SUBSTRING", "ABS", "ROUND", "FLOOR", "CEIL", "CEILING",
            "MOD", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "NOW", "GEN_RANDOM_UUID",
            "UUID_GENERATE_V4", "TO_CHAR", "TO_DATE", "TO_TIMESTAMP", "CAST", "JSONB_BUILD_OBJECT",
            "JSON_BUILD_OBJECT", "JSONB_AGG", "JSON_AGG", "ARRAY_AGG", "COUNT", "SUM", "MIN", "MAX",
            "AVG", "STRING_AGG", "ARRAY_LENGTH", "CARDINALITY", "FORMAT", "POSITION", "REPLACE",
            "LEFT", "RIGHT", "CONCAT", "CONCAT_WS", "MD5", "SHA256", "ENCODE", "DECODE", "EXTRACT",
            "DATE_TRUNC", "DATE_PART", "ISNULL", "PG_TYPEOF", "ROW", "ARRAY", "OVER", "FILTER",
            "JSONB_BUILD_ARRAY", "JSON_BUILD_ARRAY", "UNNEST", "GENERATE_SERIES", "REGEXP_REPLACE",
            "REGEXP_MATCH", "REGEXP_MATCHES", "TO_JSON", "TO_JSONB", "XMLFOREST", "XMLATTRIBUTES",
            "RTRIM", "LTRIM", "STRPOS", "CHR", "ASCII", "INITCAP", "REPEAT", "REVERSE",
            "LPAD", "RPAD", "SPLIT_PART", "TRANSLATE", "OVERLAY", "QUOTE_IDENT", "QUOTE_LITERAL",
            "PG_GET_SERIAL_SEQUENCE", "CURRVAL", "LASTVAL", "NEXTVAL", "SETVAL",
            "TRUNC", "RANDOM", "CURRENT_DATABASE", "TO_REGCLASS", "PG_TABLE_IS_VISIBLE", "MAKE_INTERVAL",
            "JSONB_OBJECT_AGG", "JSON_OBJECT_AGG",
            "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMP_WITH_TIME_ZONE",
            "VARCHAR", "CHAR", "CHARACTER", "NUMERIC", "DECIMAL", "INTEGER", "INT", "BIGINT",
            "SMALLINT", "BOOLEAN", "TEXT", "BYTEA", "UUID", "REAL", "DOUBLE", "PRECISION",
            "VARBIT", "BIT", "INTERVAL", "XMLPARSE", "XMLSERIALIZE", "XMLPI", "XMLROOT",
            "XMLCOMMENT", "XMLCONCAT", "XMLEXISTS", "JSON", "JSONB", "REGCLASS", "REGTYPE");

    private static final Set<String> SQL_WORDS = Set.of(
            "IF", "WHILE", "FOR", "CASE", "VALUES", "IN", "EXISTS", "ARRAY", "ROW", "OVER",
            "FILTER", "WITHIN", "WHEN", "FROM", "SELECT", "RETURN", "RAISE", "PERFORM", "CALL",
            "ELSIF", "ASSERT", "EXTRACT", "AS", "ON", "USING", "INTO", "UPDATE", "DELETE",
            "INSERT", "MERGE", "JOIN", "ONLY", "LATERAL", "TABLE", "SET", "WHERE", "AND", "OR",
            "NOT", "NULL", "IS", "DISTINCT", "BY", "GROUP", "ORDER", "LIMIT", "OFFSET", "UNION",
            "ALL", "INTERSECT", "EXCEPT", "RETURNING", "WITH", "RECURSIVE", "MATERIALIZED", "TRUE",
            "FALSE", "UNKNOWN", "ASC", "DESC", "NULLS", "FIRST", "LAST", "CONFLICT", "DO",
            "NOTHING", "DEFAULT", "CURRENT OF", "EXCLUDED", "OLD", "NEW", "BEGIN", "END", "THEN", "ELSE",
            "BETWEEN", "LIKE", "ILIKE", "SIMILAR", "EXECUTE");

    private static final Set<String> UNSAFE_STATEMENTS = Set.of(
            "EXECUTE", "TRUNCATE", "CREATE", "ALTER", "DROP", "GRANT", "REVOKE", "VACUUM", "ANALYZE",
            "REFRESH", "NOTIFY", "LISTEN", "UNLISTEN", "COPY");
    private static final Set<String> UNSAFE_FUNCTIONS = Set.of(
            "DBLINK", "PG_NOTIFY", "SET_CONFIG", "LO_EXPORT", "LO_IMPORT", "HTTP_GET", "HTTP_POST",
            "HTTP_PUT", "HTTP_DELETE", "PG_ADVISORY_LOCK", "PG_ADVISORY_XACT_LOCK", "PG_TERMINATE_BACKEND",
            "PG_CANCEL_BACKEND", "PG_RELOAD_CONF", "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_WRITE_FILE",
            "PG_SLEEP", "PG_SLEEP_FOR", "PG_SLEEP_UNTIL");
    private static final Set<String> NONTRANSACTIONAL_SEQUENCE_FUNCTIONS = Set.of("NEXTVAL", "SETVAL");

    private static final Set<String> TX_CONTROL = Set.of("COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "START");
    private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");

    public List<RoutineDefinition> analyzeAll(List<RoutineDefinition> routines) {
        Map<Long, RoutineDefinition> byOid = new HashMap<>();
        Map<String, List<RoutineDefinition>> byName = new HashMap<>();
        for (RoutineDefinition routine : routines) {
            byOid.put(routine.oid(), routine);
            byName.computeIfAbsent(nameKey(routine.schema(), routine.name()), ignored -> new ArrayList<>()).add(routine);
        }

        Map<Long, DirectAnalysis> direct = new HashMap<>();
        for (RoutineDefinition routine : routines) direct.put(routine.oid(), analyzeDirect(routine, byOid, byName));

        Map<Long, RoutineDefinition> result = new HashMap<>();
        Set<Long> visiting = new HashSet<>();
        for (RoutineDefinition routine : routines) resolve(routine, byOid, direct, result, visiting);
        return routines.stream().map(routine -> result.get(routine.oid())).toList();
    }

    private RoutineDefinition resolve(RoutineDefinition routine, Map<Long, RoutineDefinition> byOid,
                                      Map<Long, DirectAnalysis> direct, Map<Long, RoutineDefinition> result,
                                      Set<Long> visiting) {
        RoutineDefinition known = result.get(routine.oid());
        if (known != null) return known;
        DirectAnalysis own = direct.get(routine.oid());
        if (!visiting.add(routine.oid())) {
            own.reasons.add("Routine dependency cycle prevents safe behavior analysis.");
            return routine.withAnalysis(own.toAnalysis());
        }

        LinkedHashSet<String> operations = new LinkedHashSet<>(own.operations);
        LinkedHashMap<String, Set<String>> targets = copyTargets(own.targets);
        LinkedHashMap<String, List<ColumnBinding>> bindings = copyBindings(own.bindings);
        LinkedHashSet<String> reasons = new LinkedHashSet<>(own.reasons);
        LinkedHashSet<String> dependencySignatures = new LinkedHashSet<>(own.dependencySignatures);
        boolean internalTransactions = own.internalTransactionControl;

        for (long dependencyOid : own.dependencyOids) {
            RoutineDefinition dependency = byOid.get(dependencyOid);
            if (dependency == null) continue;
            dependencySignatures.add(dependency.signature());
            if (visiting.contains(dependencyOid)) {
                reasons.add("Routine dependency cycle prevents safe behavior analysis.");
                continue;
            }
            RoutineDefinition resolved = resolve(dependency, byOid, direct, result, visiting);
            RoutineAnalysis analysis = resolved.analysis();
            if (analysis != null) {
                operations.addAll(analysis.operations());
                analysis.targets().forEach((table, verbs) -> targets.computeIfAbsent(table, ignored -> new LinkedHashSet<>()).addAll(verbs));
                analysis.parameterBindings().forEach((parameter, values) -> bindings.computeIfAbsent(parameter, ignored -> new ArrayList<>()).addAll(values));
                reasons.addAll(analysis.safetyReasons());
                dependencySignatures.addAll(analysis.dependencySignatures());
                internalTransactions |= analysis.internalTransactionControl();
            }
        }

        visiting.remove(routine.oid());
        boolean writes = operations.stream().anyMatch(WRITES::contains);
        OperationType classification = writes || operations.isEmpty() ? OperationType.WRITE : OperationType.READ;
        RoutineAnalysis analysis = new RoutineAnalysis(classification, operations, targets, bindings,
                List.copyOf(dependencySignatures), List.copyOf(reasons), internalTransactions);
        RoutineDefinition analyzed = routine.withAnalysis(analysis);
        result.put(routine.oid(), analyzed);
        return analyzed;
    }

    private DirectAnalysis analyzeDirect(RoutineDefinition routine, Map<Long, RoutineDefinition> byOid,
                                         Map<String, List<RoutineDefinition>> byName) {
        DirectAnalysis result = new DirectAnalysis();
        for (long dependency : routine.dependencyOids()) if (byOid.containsKey(dependency)) result.dependencyOids.add(dependency);

        if (!Set.of("sql", "plpgsql").contains(routine.language().toLowerCase(Locale.ROOT))) {
            result.reasons.add("Unsupported routine language cannot be analyzed safely: " + routine.language());
        }
        if (routine.securityDefiner()) result.reasons.add("SECURITY DEFINER routine is blocked because it runs with elevated privileges.");
        if (routine.source() == null || routine.source().isBlank()) {
            result.reasons.add("Routine source is empty; behavior cannot be determined safely.");
            return result;
        }
        if (routine.source().length() > MAX_SOURCE_CHARS) {
            result.reasons.add("Routine source exceeds the analysis limit of " + MAX_SOURCE_CHARS + " characters.");
            return result;
        }

        LexResult lexed = Lexer.lex(routine.source());
        if (lexed.malformed != null) result.reasons.add(lexed.malformed);
        List<Token> tokens = lexed.tokens;
        if (tokens.isEmpty()) {
            result.reasons.add("Routine source contains no analyzable SQL tokens.");
            return result;
        }

        Map<String, RoutineParameter> parameters = new HashMap<>();
        for (RoutineParameter parameter : routine.parameters()) {
            if (parameter.input()) parameters.put(parameter.name().toLowerCase(Locale.ROOT), parameter);
        }

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            // Quoted identifiers are always identifiers in PostgreSQL, even when their spelling
            // happens to be a keyword (for example, a column named "UPDATE").
            if (token.kind != Kind.WORD) continue;
            String word = token.upper();
            // EXECUTE is PL/pgSQL dynamic SQL. It may follow RETURN QUERY, so it is not
            // reliably located by the statement-boundary heuristic used for static DDL.
            // The lexer has already removed comments and string contents and excludes
            // quoted identifiers, so every remaining bare EXECUTE token is behaviorally
            // significant and must be reviewed as dynamic SQL.
            if (word.equals("EXECUTE")) {
                result.reasons.add("Routine uses unsafe dynamic SQL EXECUTE; the executed statements and their effects cannot be bounded safely.");
            } else if (UNSAFE_STATEMENTS.contains(word) && startsStatement(tokens, i)) {
                if ((word.equals("CREATE") || word.equals("DROP")) && isTemporaryRelationDdl(tokens, i)) {
                    result.reasons.add("Routine uses temporary-relation DDL; temporary-object lifecycle and pooled-session effects are not verified by this validator.");
                } else {
                    result.reasons.add("Routine contains unsafe SQL statement " + word + ".");
                }
            }
            if (UNSAFE_FUNCTIONS.contains(word) && nextIs(tokens, i + 1, "("))
                result.reasons.add("Routine invokes unsafe or externally observable function " + word + ".");
            if (NONTRANSACTIONAL_SEQUENCE_FUNCTIONS.contains(word) && nextIs(tokens, i + 1, "("))
                result.reasons.add("Routine invokes " + word.toLowerCase(Locale.ROOT)
                        + ", which changes sequence state outside transactional rollback.");
            if (TX_CONTROL.contains(word) && startsStatement(tokens, i)
                    && (!word.equals("START") || nextIs(tokens, i + 1, "TRANSACTION"))) {
                result.internalTransactionControl = true;
                if (!routine.procedure()) result.reasons.add("Function contains transaction control that PostgreSQL cannot execute safely in this context.");
            }

            switch (word) {
                case "SELECT" -> result.operations.add("SELECT");
                case "PERFORM" -> result.operations.add("PERFORM");
                case "RETURN" -> result.operations.add("RETURN");
                case "INSERT" -> {
                    result.operations.add("INSERT");
                    int into = findWord(tokens, i + 1, "INTO", 3);
                    Relation relation = into < 0 ? null : parseRelation(tokens, into + 1, routine.schema());
                    recordTarget(result, relation, "INSERT");
                    if (relation != null) parseInsertBindings(tokens, into + 1 + relation.tokenCount, relation.name, parameters, result.bindings);
                }
                case "UPDATE" -> {
                    result.operations.add("UPDATE");
                    Relation relation = parseRelation(tokens, skipOnly(tokens, i + 1), routine.schema());
                    recordTarget(result, relation, "UPDATE");
                    if (relation != null) parseUpdateBindings(tokens, i + 1 + relation.tokenCount, relation.name, parameters, result.bindings);
                }
                case "DELETE" -> {
                    result.operations.add("DELETE");
                    int from = i + 1 < tokens.size() && tokens.get(i + 1).is("FROM") ? i + 1 : findWord(tokens, i + 1, "FROM", 2);
                    Relation relation = from < 0 ? null : parseRelation(tokens, skipOnly(tokens, from + 1), routine.schema());
                    recordTarget(result, relation, "DELETE");
                }
                case "MERGE" -> {
                    result.operations.add("MERGE");
                    int into = findWord(tokens, i + 1, "INTO", 2);
                    Relation relation = into < 0 ? null : parseRelation(tokens, into + 1, routine.schema());
                    recordTarget(result, relation, "MERGE");
                }
                case "FROM", "JOIN" -> {
                    int next = skipOnly(tokens, i + 1);
                    Relation relation = parseRelation(tokens, next, routine.schema());
                    if (relation != null && !nextIs(tokens, next + relation.tokenCount, "(")) {
                        result.targets.computeIfAbsent(relation.name, ignored -> new LinkedHashSet<>()).add("SELECT");
                    }
                }
                default -> { }
            }
        }

        parsePredicateBindings(tokens, routine.schema(), parameters, result.bindings);
        discoverCalls(tokens, routine, byName, result);

        if (result.operations.isEmpty()) result.reasons.add("No supported read or write operation was identified.");
        for (String write : WRITES) {
            if (result.operations.contains(write) && result.targets.values().stream().noneMatch(ops -> ops.contains(write))) {
                result.reasons.add("The " + write + " target could not be resolved from routine source.");
            }
        }
        return result;
    }

    private static boolean startsStatement(List<Token> tokens, int index) {
        if (index == 0) return true;
        Token previous = tokens.get(index - 1);
        if (previous.is(";")) return true;
        if (previous.kind != Kind.WORD) return false;
        return Set.of("BEGIN", "THEN", "ELSE", "LOOP", "EXCEPTION", "DO").contains(previous.upper());
    }

    private static boolean isTemporaryRelationDdl(List<Token> tokens, int index) {
        if (tokens.get(index).is("CREATE")) {
            int end = Math.min(tokens.size(), index + 5);
            for (int cursor = index + 1; cursor < end; cursor++) {
                if (tokens.get(cursor).is("TEMP") || tokens.get(cursor).is("TEMPORARY")) return true;
                if (tokens.get(cursor).is("TABLE")) return false;
            }
        }
        if (tokens.get(index).is("DROP")) {
            int cursor = index + 1;
            if (cursor < tokens.size() && (tokens.get(cursor).is("TABLE") || tokens.get(cursor).is("VIEW"))) cursor++;
            if (cursor < tokens.size() && tokens.get(cursor).is("IF")) cursor += 2;
            if (cursor < tokens.size() && tokens.get(cursor).is("pg_temp")) return true;
            if (cursor + 2 < tokens.size() && tokens.get(cursor).is("pg_catalog") && tokens.get(cursor + 1).is(".")) return false;
            return cursor < tokens.size() && tokens.get(cursor).identifier()
                    && tokens.get(cursor).value.toLowerCase(Locale.ROOT).startsWith("tmp_");
        }
        return false;
    }

    private static void discoverCalls(List<Token> tokens, RoutineDefinition owner,
                                      Map<String, List<RoutineDefinition>> byName, DirectAnalysis result) {
        for (int i = 0; i < tokens.size(); i++) {
            Token first = tokens.get(i);
            if (!first.identifier()) continue;
            if (i > 0 && tokens.get(i - 1).is(".")) continue;
            int nameAt = i;
            String schema = null;
            if (i + 2 < tokens.size() && tokens.get(i + 1).is(".") && tokens.get(i + 2).identifier()) {
                schema = first.value;
                nameAt = i + 2;
            }
            if (!nextIs(tokens, nameAt + 1, "(")) continue;
            String function = databaseIdentifier(tokens.get(nameAt));
            String upper = function.toUpperCase(Locale.ROOT);
            if (PURE_CALLS.contains(upper) || SQL_WORDS.contains(upper)) continue;
            if (schema == null && isTypeConstructor(tokens, i)) continue;

            String targetSchema = schema == null ? owner.schema() : databaseIdentifier(tokens.get(i));
            List<RoutineDefinition> candidates = byName.getOrDefault(nameKey(targetSchema, function), List.of());
            if (candidates.size() == 1) {
                result.dependencyOids.add(candidates.getFirst().oid());
                result.dependencySignatures.add(candidates.getFirst().signature());
            } else if (candidates.size() > 1) {
                result.reasons.add("Call to overloaded routine " + targetSchema + "." + function + " cannot be resolved safely.");
            } else if (isRelationNamePosition(tokens, i)) {
                // Identifiers after INTO/TABLE/UPDATE are relation names even when the
                // following token is an INSERT column list. FROM/JOIN calls are handled
                // above when discovered and remain unresolved when no routine matches.
            } else if (schema != null || !SQL_WORDS.contains(upper)) {
                result.reasons.add("Unresolved routine call " + targetSchema + "." + function + " has unknown behavior.");
            }
        }
    }

    private static boolean isRelationNamePosition(List<Token> tokens, int index) {
        if (index == 0) return false;
        String previous = tokens.get(index - 1).upper();
        return Set.of("INTO", "UPDATE", "TABLE", "ONLY", "TRUNCATE").contains(previous);
    }

    private static boolean isTypeConstructor(List<Token> tokens, int index) {
        return index > 0 && (tokens.get(index - 1).is("::") || tokens.get(index - 1).is("AS"));
    }

    private static void parseInsertBindings(List<Token> tokens, int index, String table,
                                            Map<String, RoutineParameter> parameters,
                                            Map<String, List<ColumnBinding>> bindings) {
        int cursor = index;
        List<String> columns = List.of();
        if (cursor < tokens.size() && tokens.get(cursor).is("(")) {
            int close = matchingParen(tokens, cursor);
            if (close > cursor) columns = commaItems(tokens, cursor + 1, close).stream().map(RoutineAnalyzer::singleIdentifier).toList();
            cursor = close > cursor ? close + 1 : cursor;
        }
        int values = findWord(tokens, cursor, "VALUES", 4);
        if (values < 0 || values + 1 >= tokens.size() || !tokens.get(values + 1).is("(")) return;
        int close = matchingParen(tokens, values + 1);
        if (close < 0) return;
        List<List<Token>> expressions = commaItems(tokens, values + 2, close);
        int size = Math.min(columns.size(), expressions.size());
        for (int c = 0; c < size; c++) {
            String column = columns.get(c);
            String parameter = parameterInExpression(expressions.get(c), parameters);
            if (column != null && parameter != null) addBinding(bindings, parameter, table, column, "INSERT");
        }
    }

    private static void parseUpdateBindings(List<Token> tokens, int index, String table,
                                            Map<String, RoutineParameter> parameters,
                                            Map<String, List<ColumnBinding>> bindings) {
        int set = findWord(tokens, index, "SET", 5);
        if (set < 0) return;
        int end = firstWord(tokens, set + 1, Set.of("WHERE", "FROM", "RETURNING", "WHEN"));
        if (end < 0) end = tokens.size();
        for (List<Token> assignment : commaItems(tokens, set + 1, end)) {
            int equals = topLevelSymbol(assignment, "=");
            if (equals < 1) continue;
            String column = identifierBefore(assignment, equals);
            String parameter = parameterInExpression(assignment.subList(equals + 1, assignment.size()), parameters);
            if (column != null && parameter != null) addBinding(bindings, parameter, table, column, "UPDATE");
        }
    }

    private static void parsePredicateBindings(List<Token> tokens, String defaultSchema,
                                               Map<String, RoutineParameter> parameters,
                                               Map<String, List<ColumnBinding>> bindings) {
        for (int i = 1; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).is("=")) continue;
            String column = identifierBefore(tokens, i);
            if (column == null) continue;
            int start = i + 1;
            while (start < tokens.size() && tokens.get(start).is("(")) start++;
            if (start >= tokens.size()) continue;
            String parameter = parameterName(tokens.get(start), parameters);
            String role = "PREDICATE";
            if (parameter == null) {
                ExplicitCast cast = integerCastParameter(tokens, start, parameters);
                if (cast == null) continue;
                parameter = cast.parameter();
                role = "PREDICATE_CAST:" + cast.type();
            }
            String table = nearestTable(tokens, i, defaultSchema);
            if (table != null) addBinding(bindings, parameter, table, column, role);
        }
    }

    private static ExplicitCast integerCastParameter(List<Token> tokens, int start,
                                                      Map<String, RoutineParameter> parameters) {
        if (nextIs(tokens, start, "CAST") && nextIs(tokens, start + 1, "(") && start + 5 < tokens.size()
                && nextIs(tokens, start + 3, "AS") && nextIs(tokens, start + 5, ")")) {
            String parameter = parameterName(tokens.get(start + 2), parameters);
            ExplicitCast cast = integerCast(parameter, tokens.get(start + 4));
            if (cast != null) return cast;
        }

        int parameterAt = start;
        int castAt = start + 1;
        if (Set.of("BTRIM", "LTRIM", "TRIM").contains(tokens.get(start).upper())
                && nextIs(tokens, start + 1, "(") && nextIs(tokens, start + 3, ")")) {
            parameterAt = start + 2;
            castAt = start + 4;
        }
        if (!nextIs(tokens, castAt, "::") || castAt + 1 >= tokens.size()) return null;
        String parameter = parameterName(tokens.get(parameterAt), parameters);
        return integerCast(parameter, tokens.get(castAt + 1));
    }

    private static ExplicitCast integerCast(String parameter, Token type) {
        if (parameter == null || !type.identifier()) return null;
        String normalizedType = databaseIdentifier(type).toLowerCase(Locale.ROOT);
        if (!Set.of("smallint", "int2", "integer", "int", "int4", "bigint", "int8").contains(normalizedType)) return null;
        return new ExplicitCast(parameter, normalizedType);
    }

    private static String nearestTable(List<Token> tokens, int index, String defaultSchema) {
        for (int i = index - 1; i >= 0; i--) {
            if (tokens.get(i).is("FROM") || tokens.get(i).is("UPDATE") || tokens.get(i).is("INTO")) {
                Relation relation = parseRelation(tokens, skipOnly(tokens, i + 1), defaultSchema);
                if (relation != null) return relation.name;
            }
        }
        return null;
    }

    private static String parameterInExpression(List<Token> expression, Map<String, RoutineParameter> parameters) {
        for (int i = 0; i < expression.size(); i++) {
            Token token = expression.get(i);
            String value = parameterName(token, parameters);
            if (value != null) return value;
            if (token.is(":" ) && i + 1 < expression.size()) {
                value = parameterName(expression.get(i + 1), parameters);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static String parameterName(Token token, Map<String, RoutineParameter> parameters) {
        if (token.kind == Kind.PARAM) {
            try {
                int position = Integer.parseInt(token.value);
                for (RoutineParameter parameter : parameters.values()) if (parameter.position() == position) return parameter.name().toLowerCase(Locale.ROOT);
            } catch (NumberFormatException ignored) { return null; }
        }
        if (!token.identifier()) return null;
        return parameters.containsKey(token.value.toLowerCase(Locale.ROOT)) ? token.value.toLowerCase(Locale.ROOT) : null;
    }

    private static String identifierBefore(List<Token> tokens, int index) {
        if (index <= 0) return null;
        Token token = tokens.get(index - 1);
        if (!token.identifier()) return null;
        return databaseIdentifier(token);
    }

    private static String singleIdentifier(List<Token> tokens) {
        return tokens.size() == 1 && tokens.getFirst().identifier() ? databaseIdentifier(tokens.getFirst()) : null;
    }

    private static int topLevelSymbol(List<Token> tokens, String symbol) {
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).is("(")) depth++;
            else if (tokens.get(i).is(")")) depth--;
            else if (depth == 0 && tokens.get(i).is(symbol)) return i;
        }
        return -1;
    }

    private static List<List<Token>> commaItems(List<Token> tokens, int from, int to) {
        List<List<Token>> items = new ArrayList<>();
        int start = from, depth = 0;
        for (int i = from; i < to; i++) {
            if (tokens.get(i).is("(")) depth++;
            else if (tokens.get(i).is(")")) depth--;
            else if (depth == 0 && tokens.get(i).is(",")) {
                items.add(tokens.subList(start, i));
                start = i + 1;
            }
        }
        if (start <= to) items.add(tokens.subList(start, to));
        return items;
    }

    private static int matchingParen(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).is("(")) depth++;
            else if (tokens.get(i).is(")") && --depth == 0) return i;
        }
        return -1;
    }

    private static int findWord(List<Token> tokens, int from, String expected, int limit) {
        int end = Math.min(tokens.size(), from + limit);
        for (int i = from; i < end; i++) if (tokens.get(i).is(expected)) return i;
        return -1;
    }

    private static int firstWord(List<Token> tokens, int from, Set<String> words) {
        int depth = 0;
        for (int i = from; i < tokens.size(); i++) {
            if (tokens.get(i).is("(")) depth++;
            else if (tokens.get(i).is(")")) depth--;
            else if (depth == 0 && words.contains(tokens.get(i).upper())) return i;
        }
        return -1;
    }

    private static int skipOnly(List<Token> tokens, int index) {
        if (index < tokens.size() && tokens.get(index).is("ONLY")) index++;
        if (index < tokens.size() && tokens.get(index).is("(")) index++;
        return index;
    }

    private static Relation parseRelation(List<Token> tokens, int index, String defaultSchema) {
        if (index >= tokens.size() || !tokens.get(index).identifier()) return null;
        String first = databaseIdentifier(tokens.get(index));
        int count = 1;
        String schema = defaultSchema;
        String table = first;
        if (index + 2 < tokens.size() && tokens.get(index + 1).is(".") && tokens.get(index + 2).identifier()) {
            schema = first;
            table = databaseIdentifier(tokens.get(index + 2));
            count = 3;
        }
        return new Relation(schema + "." + table, count);
    }

    private static void recordTarget(DirectAnalysis result, Relation relation, String operation) {
        if (relation != null) result.targets.computeIfAbsent(relation.name, ignored -> new LinkedHashSet<>()).add(operation);
    }

    private static void addBinding(Map<String, List<ColumnBinding>> bindings, String parameter,
                                   String table, String column, String role) {
        String key = parameter.toLowerCase(Locale.ROOT);
        ColumnBinding value = new ColumnBinding(table, column, role);
        List<ColumnBinding> existing = bindings.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!existing.contains(value)) existing.add(value);
    }

    private static boolean nextIs(List<Token> tokens, int index, String value) {
        return index >= 0 && index < tokens.size() && tokens.get(index).is(value);
    }

    private static String nameKey(String schema, String name) {
        return schema + "." + name;
    }

    private static String normalizeIdentifier(String value) { return value.toLowerCase(Locale.ROOT); }
    private static String databaseIdentifier(Token token) {
        return token.kind == Kind.QUOTED_IDENT ? token.value : normalizeIdentifier(token.value);
    }

    private static LinkedHashMap<String, Set<String>> copyTargets(Map<String, Set<String>> source) {
        LinkedHashMap<String, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new LinkedHashSet<>(value)));
        return copy;
    }

    private static LinkedHashMap<String, List<ColumnBinding>> copyBindings(Map<String, List<ColumnBinding>> source) {
        LinkedHashMap<String, List<ColumnBinding>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new ArrayList<>(value)));
        return copy;
    }

    private record Relation(String name, int tokenCount) { }
    private record ExplicitCast(String parameter, String type) { }
    private enum Kind { WORD, QUOTED_IDENT, PARAM, SYMBOL }
    private record Token(Kind kind, String value) {
        boolean identifier() { return kind == Kind.WORD || kind == Kind.QUOTED_IDENT; }
        String upper() { return value.toUpperCase(Locale.ROOT); }
        boolean is(String other) { return value.equalsIgnoreCase(other); }
    }

    private static final class DirectAnalysis {
        final LinkedHashSet<String> operations = new LinkedHashSet<>();
        final LinkedHashMap<String, Set<String>> targets = new LinkedHashMap<>();
        final LinkedHashMap<String, List<ColumnBinding>> bindings = new LinkedHashMap<>();
        final LinkedHashSet<String> dependencySignatures = new LinkedHashSet<>();
        final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        final LinkedHashSet<Long> dependencyOids = new LinkedHashSet<>();
        boolean internalTransactionControl;
        RoutineAnalysis toAnalysis() {
            OperationType classification = operations.stream().anyMatch(WRITES::contains) || operations.isEmpty()
                    ? OperationType.WRITE : OperationType.READ;
            return new RoutineAnalysis(classification, operations, targets, bindings, List.copyOf(dependencySignatures),
                    List.copyOf(reasons), internalTransactionControl);
        }
    }

    private record LexResult(List<Token> tokens, String malformed) { }

    /** Linear lexer. Every source character is visited a bounded number of times. */
    private static final class Lexer {
        static LexResult lex(String source) {
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            while (i < source.length()) {
                if (tokens.size() >= MAX_SOURCE_TOKENS)
                    return new LexResult(List.copyOf(tokens), "Routine source exceeds the token analysis limit of " + MAX_SOURCE_TOKENS + ".");
                char c = source.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '-' && at(source, i + 1, '-')) {
                    i += 2;
                    while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
                    continue;
                }
                if (c == '/' && at(source, i + 1, '*')) {
                    int depth = 1;
                    i += 2;
                    while (i < source.length() && depth > 0) {
                        if (source.charAt(i) == '/' && at(source, i + 1, '*')) { depth++; i += 2; }
                        else if (source.charAt(i) == '*' && at(source, i + 1, '/')) { depth--; i += 2; }
                        else i++;
                    }
                    if (depth != 0) return new LexResult(List.copyOf(tokens), "Routine source has an unterminated block comment.");
                    continue;
                }
                if (c == '\'') {
                    i++;
                    boolean closed = false;
                    while (i < source.length()) {
                        char q = source.charAt(i++);
                        if (q == '\\' && i < source.length()) { i++; continue; }
                        if (q == '\'') {
                            if (i < source.length() && source.charAt(i) == '\'') { i++; continue; }
                            closed = true;
                            break;
                        }
                    }
                    if (!closed) return new LexResult(List.copyOf(tokens), "Routine source has an unterminated string literal.");
                    continue;
                }
                if (c == '"') {
                    i++;
                    StringBuilder value = new StringBuilder();
                    boolean closed = false;
                    while (i < source.length()) {
                        char q = source.charAt(i++);
                        if (q == '"') {
                            if (i < source.length() && source.charAt(i) == '"') { value.append('"'); i++; }
                            else { closed = true; break; }
                        } else value.append(q);
                    }
                    if (!closed) return new LexResult(List.copyOf(tokens), "Routine source has an unterminated quoted identifier.");
                    tokens.add(new Token(Kind.QUOTED_IDENT, value.toString()));
                    continue;
                }
                if (c == '$') {
                    int delimiterEnd = dollarDelimiterEnd(source, i);
                    if (delimiterEnd > i) {
                        String delimiter = source.substring(i, delimiterEnd);
                        int closing = source.indexOf(delimiter, delimiterEnd);
                        if (closing < 0) return new LexResult(List.copyOf(tokens), "Routine source has an unterminated dollar-quoted string.");
                        i = closing + delimiter.length();
                        continue;
                    }
                    if (i + 1 < source.length() && Character.isDigit(source.charAt(i + 1))) {
                        int start = ++i;
                        while (i < source.length() && Character.isDigit(source.charAt(i))) i++;
                        tokens.add(new Token(Kind.PARAM, source.substring(start, i)));
                        continue;
                    }
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = i++;
                    while (i < source.length()) {
                        char next = source.charAt(i);
                        if (!Character.isLetterOrDigit(next) && next != '_' && next != '$') break;
                        i++;
                    }
                    tokens.add(new Token(Kind.WORD, source.substring(start, i)));
                    continue;
                }
                if (Character.isDigit(c)) {
                    int start = i++;
                    while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '.' || source.charAt(i) == '_')) i++;
                    tokens.add(new Token(Kind.SYMBOL, source.substring(start, i)));
                    continue;
                }
                if (i + 1 < source.length()) {
                    String pair = source.substring(i, i + 2);
                    if (Set.of("::", ">=", "<=", "!=", "<>", "=>", "||", "->", "#>").contains(pair)) {
                        tokens.add(new Token(Kind.SYMBOL, pair)); i += 2; continue;
                    }
                }
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c)));
                i++;
            }
            return new LexResult(List.copyOf(tokens), null);
        }

        private static int dollarDelimiterEnd(String source, int start) {
            int i = start + 1;
            if (i < source.length() && source.charAt(i) != '$' && !isDollarTagStart(source.charAt(i))) return -1;
            while (i < source.length() && source.charAt(i) != '$') {
                if (!isDollarTagPart(source.charAt(i))) return -1;
                i++;
            }
            return i < source.length() ? i + 1 : -1;
        }

        private static boolean isDollarTagStart(char c) { return Character.isLetter(c) || c == '_'; }
        private static boolean isDollarTagPart(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
        private static boolean at(String source, int index, char expected) { return index < source.length() && source.charAt(index) == expected; }
    }
}
