package com.tm.storedprocedure.data;

import com.tm.storedprocedure.config.ValidatorProperties;
import com.tm.storedprocedure.domain.ColumnBinding;
import com.tm.storedprocedure.domain.ForeignKey;
import com.tm.storedprocedure.domain.GeneratedInput;
import com.tm.storedprocedure.domain.RoutineDefinition;
import com.tm.storedprocedure.domain.RoutineParameter;
import com.tm.storedprocedure.domain.RoutineTestCase;
import com.tm.storedprocedure.domain.TableColumn;
import com.tm.storedprocedure.domain.TableConstraint;
import com.tm.storedprocedure.domain.TableMetadata;
import com.tm.storedprocedure.metadata.TableMetadataService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TestCaseFactory {
    private final TableMetadataService metadata;
    private final JdbcTemplate jdbc;
    private final GeneratedRecordRegistry registry;
    private final ValidatorProperties properties;
    private final Map<String, AtomicLong> generatedTextKeySequences = new ConcurrentHashMap<>();

    public TestCaseFactory(TableMetadataService metadata, JdbcTemplate jdbc,
                           GeneratedRecordRegistry registry, ValidatorProperties properties) {
        this.metadata = metadata; this.jdbc = jdbc; this.registry = registry; this.properties = properties;
    }

    public List<RoutineTestCase> create(RoutineDefinition routine) {
        List<RoutineParameter> inputs = routine.parameters().stream().filter(RoutineParameter::input).toList();
        boolean write = routine.analysis().writes();
        Set<String> defaulted = inputs.stream()
                .filter(RoutineParameter::hasDefault)
                .filter(parameter -> !requiresExplicitValue(routine, parameter))
                .map(RoutineParameter::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean canOmitDefaults = inputs.stream().allMatch(parameter -> !parameter.name().matches("arg[0-9]+"));
        Map<String, GeneratedInput> base = new LinkedHashMap<>();
        Map<String, Map<String, Object>> foreignKeyCache = new LinkedHashMap<>();
        for (RoutineParameter parameter : inputs) {
            if (write && canOmitDefaults && defaulted.contains(parameter.name())) continue;
            base.put(parameter.name(), generate(routine, parameter, foreignKeyCache));
        }
        List<RoutineTestCase> cases = new ArrayList<>();
        if (write && !defaulted.isEmpty() && canOmitDefaults) {
            cases.add(new RoutineTestCase(UUID.randomUUID().toString(), "DATABASE_DEFAULT", base, defaulted));
        } else {
            cases.add(new RoutineTestCase(UUID.randomUUID().toString(), "NON_NULL", base, Set.of()));
            if (!write && !defaulted.isEmpty() && canOmitDefaults && cases.size() < properties.getMaxTestsPerRoutine()) {
                Map<String, GeneratedInput> explicit = new LinkedHashMap<>(base);
                defaulted.forEach(explicit::remove);
                cases.add(new RoutineTestCase(UUID.randomUUID().toString(), "DATABASE_DEFAULT", explicit, defaulted));
            }
        }

        // Each write case can commit independently. Additional NULL/default variants may repeat
        // a non-idempotent business write, so a write routine receives one minimum invocation.
        if (write) return List.copyOf(cases);
        for (RoutineParameter parameter : inputs) {
            if (cases.size() >= properties.getMaxTestsPerRoutine()) break;
            if (!canBeNull(routine, parameter)) continue;
            Map<String, GeneratedInput> variant = new LinkedHashMap<>(base);
            variant.put(parameter.name(), new GeneratedInput(null, parameter.sqlType(), "NULL_VARIANT", false));
            cases.add(new RoutineTestCase(UUID.randomUUID().toString(), "NULL:" + parameter.name(), variant, Set.of()));
        }
        return List.copyOf(cases);
    }

    private GeneratedInput generate(RoutineDefinition routine, RoutineParameter parameter, Map<String, Map<String, Object>> foreignKeyCache) {
        List<ColumnBinding> bindings = routine.analysis().parameterBindings().getOrDefault(parameter.name().toLowerCase(Locale.ROOT), List.of());
        ColumnBinding binding = bindings.stream().filter(b -> isPredicateRole(b.role())).findFirst()
                .orElseGet(() -> bindings.stream().findFirst().orElse(null));

        // Fallback for routine bodies whose INSERT column list is not fully parsed by the
        // analyzer (e.g. multiline INSERTs, aliases, or simple quoted identifiers). Only infer
        // a binding when the parameter name matches exactly one target column after normalization.
        if (binding == null) binding = inferUnambiguousTargetBinding(routine, parameter);

        TableMetadata table = binding == null ? null : describe(binding.table());
        TableColumn column = table == null ? null : table.column(binding.column());

        // binding is conditionally reassigned above, so capture its table name in an
        // effectively-final local before using it in the stream predicate.
        final String boundTableName = binding == null ? null : binding.table();
        if (binding != null && isPredicateRole(binding.role())
                && routine.analysis().targets().getOrDefault(boundTableName, Set.of()).stream().anyMatch(op -> op.equals("UPDATE") || op.equals("DELETE"))
                && table != null && table.primaryKey().contains(binding.column())) {
            // Resolve all columns of a composite primary key from one verified record. Looking up
            // each key column independently can accidentally combine keys from different rows.
            Optional<Map<String, Object>> ownedRow = registry.findRow(binding.table(), table.primaryKey());
            Object ownedValue = ownedRow.isPresent() ? ownedRow.get().get(binding.column()) : null;
            if (ownedValue == null) throw new IllegalArgumentException("No current-run generated record is available for " + binding.table() + "." + binding.column() + "; a verified row containing the complete primary key is required.");
            return new GeneratedInput(ownedValue, parameter.sqlType(), "CURRENT_RUN_RECORD", true);
        }

        if (binding != null && binding.role().startsWith("PREDICATE_CAST:")) {
            String castType = binding.role().substring("PREDICATE_CAST:".length());
            String generationType = switch (castType) {
                case "int", "int4" -> "integer";
                case "int2" -> "smallint";
                case "int8" -> "bigint";
                default -> castType;
            };
            Object castValue = valueForType(generationType, null, null);
            Object value = isTextType(parameter.sqlType()) ? String.valueOf(castValue) : castValue;
            return new GeneratedInput(value, parameter.sqlType(), "EXPLICIT_CAST_COMPATIBLE", false);
        }

        if (column != null && table != null) {
            if ("INSERT".equals(binding.role()) && column.identity()) {
                // Generate an explicit identity argument from the real PostgreSQL sequence when
                // the routine maps this parameter to the identity column. The routine still owns
                // the write; Java never inserts directly or resets the sequence.
                boolean overridingAlways = "ALWAYS".equalsIgnoreCase(column.identityGeneration())
                        && routine.definition() != null
                        && routine.definition().matches("(?is).*\\bOVERRIDING\\s+SYSTEM\\s+VALUE\\b.*");
                if ("ALWAYS".equalsIgnoreCase(column.identityGeneration()) && !overridingAlways) {
                    throw new IllegalArgumentException("Routine input maps to identity column " + table.qualifiedName() + "." + column.name()
                            + " GENERATED ALWAYS, but the routine definition does not use OVERRIDING SYSTEM VALUE. Inspect the routine contract; Java will not change its signature or bypass it.");
                }
                Object sequenceValue = nextSequenceValue(table, column);
                if (sequenceValue == null) throw new IllegalArgumentException("Could not resolve the PostgreSQL identity sequence for "
                        + table.qualifiedName() + "." + column.name() + "; inspect identity metadata and routine behavior.");
                return new GeneratedInput(sequenceValue, parameter.sqlType(), "IDENTITY_SEQUENCE_CANDIDATE", false);
            }
            if ("INSERT".equals(binding.role()) && column.defaultExpression() != null
                    && column.defaultExpression().matches("(?is).*\\bnextval\\s*\\(.*")) {
                Object sequenceValue = nextSequenceValue(table, column);
                if (sequenceValue == null) throw new IllegalArgumentException("Could not resolve the PostgreSQL sequence for "
                        + table.qualifiedName() + "." + column.name() + "; explicit values are not guessed.");
                return new GeneratedInput(sequenceValue, parameter.sqlType(), "SEQUENCE_CANDIDATE", false);
            }
            ForeignKey fk = table.foreignKeys().stream().filter(key -> key.localColumn().equals(column.name())).findFirst().orElse(null);
            if (fk != null) {
                String cacheKey = fk.referencedSchema() + "." + fk.referencedTable() + "#" + fk.name();
                Map<String, Object> parentValues = foreignKeyCache.computeIfAbsent(cacheKey, ignored -> foreignKeyValues(table, fk));
                Object existing = parentValues.get(fk.referencedColumn());
                if (existing != null) return new GeneratedInput(existing, parameter.sqlType(), "EXISTING_REFERENCE", true);
                List<String> referencedColumns = table.foreignKeys().stream().filter(candidate -> candidate.name().equals(fk.name()))
                        .map(ForeignKey::referencedColumn).distinct().toList();
                Optional<Map<String, Object>> generatedParent = registry.findRow(fk.referencedSchema() + "." + fk.referencedTable(), referencedColumns);
                if (generatedParent.isPresent() && generatedParent.get().get(fk.referencedColumn()) != null)
                    return new GeneratedInput(generatedParent.get().get(fk.referencedColumn()), parameter.sqlType(), "GENERATED_PREREQUISITE", true);
                if (column.nullable()) return new GeneratedInput(null, parameter.sqlType(), "NULLABLE_FOREIGN_KEY", true);
                throw new IllegalArgumentException("Required foreign-key parent is unavailable for " + table.qualifiedName() + "." + column.name() + "; no prerequisite routine produced a verifiable parent record.");
            }
        }

        Object value;
        if (column != null && isTextType(parameter.sqlType()) && isNumericType(column.sqlType())) {
            Object numeric = valueForType(column.sqlType(), column, table);
            value = String.valueOf(numeric);
        } else {
            value = valueForType(parameter.sqlType(), column, table);
        }
        if (column != null && table != null && !hasColumnCheck(table, column.name())
                && !(isTextType(parameter.sqlType()) && isNumericType(column.sqlType()))) {
            value = semanticColumnValue(value, parameter.sqlType(), column, table);
        }
        // Prefer domain-shaped values over random character noise. Column metadata supplies
        // the actual storage length; unbound routine parameters use the parameter name.
        if (isTextType(parameter.sqlType()) && !(column != null && isNumericType(column.sqlType()))
                && !(column != null && table != null && hasColumnCheck(table, column.name()))) {
            int maxLength = column == null ? properties.getMaxGeneratedStringLength()
                    : Math.min(properties.getMaxGeneratedStringLength(), parseLength(column.sqlType(), properties.getMaxGeneratedStringLength()));
            value = semanticText(column == null ? parameter.name() : column.name(), maxLength, column, table);
        }
        if (column != null && column.generated()) {
            throw new IllegalArgumentException("Routine input maps to generated column " + table.qualifiedName() + "." + column.name() + "; generated values cannot be supplied safely.");
        }
        // Keep the callable routine signature unchanged, but shape the generated argument
        // to the actual target column's domain whenever that can be represented by the
        // declared parameter type. For incompatible declared types, report the database-side
        // cast/signature problem instead of silently passing a value of the wrong JDBC type.
        if (column != null && value != null) {
            value = targetAwareValue(value, parameter.sqlType(), column, table);
        }
        if (value != null && column != null && table != null && isSingleColumnUnique(table, column.name())) {
            int attempts = 0;
            while (exists(table, column.name(), value) && attempts++ < 100) {
                value = uniqueCandidate(parameter, column, table);
            }
            if (exists(table, column.name(), value)) throw new IllegalArgumentException("Unable to generate a collision-free value for unique column " + table.qualifiedName() + "." + column.name() + " after 100 candidates; inspect identity/sequence state and routine key behavior.");
        }
        return new GeneratedInput(value, parameter.sqlType(), column == null ? "TYPE_COMPATIBLE_SYNTHETIC" : "COLUMN_METADATA_TARGET_AWARE", false);
    }


    private Object targetAwareValue(Object value, String parameterType, TableColumn column, TableMetadata table) {
        String parameter = baseType(parameterType);
        String target = baseType(column.sqlType());
        // Date/time fields frequently arrive through legacy character parameters. Keep the
        // function/procedure signature intact and provide an ISO-8601 string; if PostgreSQL
        // still rejects it, the routine needs an explicit cast to timestamp/date.
        if (target.contains("timestamp") && isTextType(parameter)) {
            if (value instanceof LocalDateTime dateTime) return dateTime.toString();
            if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
            String text = String.valueOf(value).trim();
            try { return LocalDateTime.parse(text).toString(); }
            catch (RuntimeException ignored) {
                try { return LocalDate.parse(text).atStartOfDay().toString(); }
                catch (RuntimeException ignoredAgain) {
                    return LocalDateTime.now().withNano(0).toString();
                }
            }
        }
        if (target.equals("date") && isTextType(parameter)) {
            if (value instanceof LocalDate date) return date.toString();
            String text = String.valueOf(value).trim();
            try { return LocalDate.parse(text).toString(); }
            catch (RuntimeException ignored) {
                try { return LocalDateTime.parse(text).toLocalDate().toString(); }
                catch (RuntimeException ignoredAgain) { return LocalDate.now().toString(); }
            }
        }
        // SQL Server bit-style values are sometimes represented by integer procedure
        // parameters in converted routines. Use only 0/1 for a boolean target; the routine
        // still must cast integer to boolean if PostgreSQL does not allow assignment.
        if (target.equals("boolean") || target.equals("bool")) {
            if (value instanceof Boolean) return value;
            if (parameter.matches(".*(smallint|integer|bigint|int2|int4|int8).*"))
                return ThreadLocalRandom.current().nextBoolean() ? 1 : 0;
            if (isTextType(parameter)) return ThreadLocalRandom.current().nextBoolean() ? "true" : "false";
        }
        // Keep generated text within the real CHAR/VARCHAR limit, including when the
        // source parameter is wider than the target column.
        if (isTextType(target) && value instanceof String text) {
            int max = parseLength(column.sqlType(), properties.getMaxGeneratedStringLength());
            if (text.length() > max) return text.substring(0, max);
        }
        return value;
    }

    private Object uniqueCandidate(RoutineParameter parameter, TableColumn column, TableMetadata table) {
        String targetType = baseType(column.sqlType());
        if (column.identity() || (column.defaultExpression() != null
                && column.defaultExpression().matches("(?is).*\\bnextval\\s*\\(.*"))) {
            Object sequenceValue = nextSequenceValue(table, column);
            if (sequenceValue != null) return sequenceValue;
        }
        if (isTextType(targetType)) {
            int width = parseLength(column.sqlType(), properties.getMaxGeneratedStringLength());
            return semanticText(column.name(), Math.min(width, properties.getMaxGeneratedStringLength()), column, table);
        }
        Object candidate = valueForType(column.sqlType(), column, table);
        return targetAwareValue(candidate, parameter.sqlType(), column, table);
    }

    private static String baseType(String sqlType) {
        return sqlType == null ? "" : sqlType.toLowerCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)", "").trim();
    }

    private Object nextSequenceValue(TableMetadata table, TableColumn column) {
        String relation = quote(table.schema()) + "." + quote(table.name());
        String sequence = jdbc.queryForObject("SELECT pg_catalog.pg_get_serial_sequence(?, ?)", String.class,
                relation, column.name());
        if (sequence == null || sequence.isBlank()) return null;
        // Advance the actual sequence normally; never reset it. Legacy/manual values can be
        // ahead of the sequence, so verify candidate uniqueness before using it.
        for (int attempt = 0; attempt < 100; attempt++) {
            Object rawSequenceValue = jdbc.queryForObject("SELECT pg_catalog.nextval(?::regclass)", Object.class, sequence);
            if (rawSequenceValue == null) continue;
            Object candidate = rawSequenceValue;
            if (isTextType(column.sqlType())) {
                // Legacy sequence-backed character keys use a short prefix plus the actual
                // monotonically increasing sequence value (A1, A2, ...), bounded to the
                // declared CHAR/VARCHAR width. Numeric identity columns keep their numeric type.
                String digits = String.valueOf(rawSequenceValue);
                int width = parseLength(column.sqlType(), properties.getMaxGeneratedStringLength());
                candidate = "A" + digits;
                if (String.valueOf(candidate).length() > width) {
                    if (digits.length() > width) throw new IllegalArgumentException("Sequence value exceeds the available text identifier width for " + table.qualifiedName() + "." + column.name() + ".");
                    candidate = digits;
                }
            }
            if (!exists(table, column.name(), candidate)) return candidate;
        }
        throw new IllegalArgumentException("Identity/sequence candidate conflicts with existing values for "
                + table.qualifiedName() + "." + column.name() + " after 100 normal sequence advances; inspect sequence state manually.");
    }

    private boolean requiresExplicitValue(RoutineDefinition routine, RoutineParameter parameter) {
        List<ColumnBinding> bindings = routine.analysis().parameterBindings()
                .getOrDefault(parameter.name().toLowerCase(Locale.ROOT), List.of());
        ColumnBinding binding = bindings.stream().filter(b -> "INSERT".equals(b.role()) || "UPDATE".equals(b.role()))
                .findFirst().orElseGet(() -> bindings.stream().findFirst().orElse(null));
        if (binding == null) binding = inferUnambiguousTargetBinding(routine, parameter);
        if (binding == null) return false;
        try {
            TableMetadata table = describe(binding.table());
            TableColumn column = table.column(binding.column());
            return column != null && !column.nullable();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ColumnBinding inferUnambiguousTargetBinding(RoutineDefinition routine, RoutineParameter parameter) {
        String parameterName = normalizeIdentifier(parameter.name().replaceFirst("(?i)^p_", ""));
        List<ColumnBinding> matches = new ArrayList<>();
        for (String qualified : routine.analysis().targets().keySet()) {
            if (!qualified.contains(".")) continue;
            Set<String> operations = routine.analysis().targets().getOrDefault(qualified, Set.of());
            if (operations.stream().noneMatch(op -> op.equals("INSERT") || op.equals("UPDATE") || op.equals("DELETE"))) continue;
            TableMetadata target;
            try {
                target = describe(qualified);
            } catch (RuntimeException unavailable) {
                continue;
            }
            for (TableColumn candidate : target.columns()) {
                String columnName = normalizeIdentifier(candidate.name());
                if (columnName.equals(parameterName) || (parameterName.length() >= 5 && columnName.endsWith(parameterName))) {
                    String operation = operations.contains("INSERT") ? "INSERT"
                            : operations.contains("UPDATE") ? "UPDATE" : "PREDICATE";
                    matches.add(new ColumnBinding(qualified, candidate.name(), operation));
                }
            }
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String normalizeIdentifier(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Object semanticColumnValue(Object generated, String sqlType, TableColumn column, TableMetadata table) {
        String name = normalizeIdentifier(column.name());
        String type = sqlType.toLowerCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)", "").trim();
        if (type.matches(".*(smallint|integer|bigint|int2|int4|int8|numeric|decimal|serial).*")) {
            Matcher precision = Pattern.compile("(?i)(?:numeric|decimal)\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)").matcher(column.sqlType());
            if (precision.find() && !name.contains("year") && !name.contains("percent") && !name.endsWith("pct")) {
                int digits = Integer.parseInt(precision.group(1));
                int scale = precision.group(2) == null ? 0 : Integer.parseInt(precision.group(2));
                if (digits <= 4 && scale == 0) {
                    long max = (long) Math.pow(10, digits) - 1;
                    return coerceNumeric(BigDecimal.valueOf(ThreadLocalRandom.current().nextLong(1, Math.max(2, max + 1))), sqlType);
                }
            }
            if (name.contains("year")) return coerceNumeric(BigDecimal.valueOf(new int[]{1998, 2000, 2003, 2024}[ThreadLocalRandom.current().nextInt(4)]), sqlType);
            if (name.contains("quarter")) return coerceNumeric(BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(1, 5)), sqlType);
            if (name.contains("percent") || name.endsWith("pct")) return coerceNumeric(BigDecimal.valueOf(new int[]{10, 25, 50, 75, 100}[ThreadLocalRandom.current().nextInt(5)]), sqlType);
            if (name.contains("age")) return coerceNumeric(BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(18, 81)), sqlType);
            if (name.contains("enabled") || name.contains("default") || name.contains("selected")
                    || name.contains("active") || name.contains("delete") || name.contains("working")
                    || name.contains("smoker") || name.contains("dnc") || name.contains("retmail")
                    || name.contains("singleonly") || name.endsWith("flag")
                    || name.startsWith("has") || name.contains("hasplan")) {
                return coerceNumeric(BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(0, 2)), sqlType);
            }
            if (name.contains("count") || name.contains("occurr") || name.contains("days") || name.contains("months")) {
                return coerceNumeric(BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(1, 8)), sqlType);
            }
        }
        return generated;
    }

    private boolean canBeNull(RoutineDefinition routine, RoutineParameter parameter) {
        List<ColumnBinding> bindings = routine.analysis().parameterBindings().getOrDefault(parameter.name().toLowerCase(Locale.ROOT), List.of());
        // Without a relation binding there is no catalog evidence that a nullable variant is valid.
        if (bindings.isEmpty()) return false;
        for (ColumnBinding binding : bindings) {
            TableMetadata table = describe(binding.table());
            TableColumn column = table.column(binding.column());
            if (column == null || !column.nullable()) return false;
            if (isPredicateRole(binding.role()) && table.primaryKey().contains(binding.column())) return false;
            boolean explicitlyNotNull = table.constraints().stream().filter(c -> "CHECK".equals(c.type()))
                    .anyMatch(c -> Pattern.compile("(?is)\\b" + Pattern.quote(column.name()) + "\\b\\s+IS\\s+NOT\\s+NULL").matcher(c.definition()).find());
            if (explicitlyNotNull) return false;
        }
        return true;
    }

    private Object valueForType(String sqlType, TableColumn column, TableMetadata table) {
        String type = sqlType.toLowerCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)", "").trim();
        if (column != null && normalizeIdentifier(column.name()).contains("code")
                && (table == null || !hasColumnCheck(table, column.name()))
                && table.foreignKeys().stream().noneMatch(fk -> fk.localColumn().equals(column.name()))) {
            throw new IllegalArgumentException("Business-code value cannot be invented safely for "
                    + table.qualifiedName() + "." + column.name()
                    + "; use an existing lookup/code row or a documented routine contract.");
        }
        if (column != null && (normalizeIdentifier(column.name()).contains("sex")
                || normalizeIdentifier(column.name()).contains("gender")) && isNumericType(column.sqlType())
                && !hasColumnCheck(table, column.name())
                && table.foreignKeys().stream().noneMatch(fk -> fk.localColumn().equals(column.name()))) {
            throw new IllegalArgumentException("Numeric sex/gender code cannot be inferred safely for "
                    + table.qualifiedName() + "." + column.name() + "; use a CHECK constraint, lookup row, or documented business contract.");
        }
        if (column != null && normalizeIdentifier(column.name()).contains("option") && isNumericType(column.sqlType())
                && !hasColumnCheck(table, column.name())
                && table.foreignKeys().stream().noneMatch(fk -> fk.localColumn().equals(column.name()))) {
            throw new IllegalArgumentException("Numeric option code cannot be inferred safely for "
                    + table.qualifiedName() + "." + column.name() + "; use an existing lookup row or documented business contract.");
        }
        Object value;
        if (type.contains("uuid")) value = UUID.randomUUID();
        else if (type.matches(".*(smallint|int2).*")) value = (short) ThreadLocalRandom.current().nextInt(1, 1001);
        // Keep synthetic numeric inputs conservative. Large random IDs/amounts frequently trigger
        // unrelated business constraints and make failures harder to diagnose. CHECK constraints
        // are applied below, and foreign-key/primary-key values are handled by the metadata paths.
        else if (type.matches(".*(integer|serial|int4).*")) value = ThreadLocalRandom.current().nextInt(1, 100_001);
        else if (type.matches(".*(bigint|bigserial|int8).*")) value = ThreadLocalRandom.current().nextLong(1, 1_000_001);
        else if (type.matches(".*(numeric|decimal).*")) value = randomNumeric(column == null ? sqlType : column.sqlType());
        else if (type.matches(".*(real|double precision|float[48]).*")) value = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(1, 1000)).setScale(2, RoundingMode.HALF_UP);
        else if (type.equals("boolean") || type.equals("bool")) value = ThreadLocalRandom.current().nextBoolean();
        else if (type.equals("date")) value = LocalDate.now().minusDays(ThreadLocalRandom.current().nextInt(1, 1000));
        else if (type.contains("timestamp") && type.contains("with time zone"))
            value = OffsetDateTime.now().minusDays(ThreadLocalRandom.current().nextInt(1, 1000)).withNano(0);
        else if (type.contains("timestamp"))
            value = LocalDateTime.now().minusDays(ThreadLocalRandom.current().nextInt(1, 1000)).withNano(0);
        else if (type.equals("time") || type.startsWith("time ")) value = java.sql.Time.valueOf(java.time.LocalTime.NOON);
        else if (type.equals("json") || type.equals("jsonb")) value = "{\"validatorRun\":\"" + UUID.randomUUID() + "\"}";
        else if (type.endsWith("[]")) throw new IllegalArgumentException("Array input type needs an explicit safe value and is not guessed: " + sqlType + ".");
        else if (column != null && table != null && hasColumnCheck(table, column.name())) value = checkConstrainedValue(sqlType, column, table);
        else if (type.contains("char") || type.contains("text") || type.equals("name")) {
            int max = column == null ? properties.getMaxGeneratedStringLength()
                    : Math.min(properties.getMaxGeneratedStringLength(), parseLength(column.sqlType(), properties.getMaxGeneratedStringLength()));
            if (max < 1) throw new IllegalArgumentException("Column has no capacity for generated text: " + (column == null ? sqlType : column.name()) + ".");
            // Never recycle existing short values: this caused rows full of values such as "v".
            // Generate new synthetic values from the semantic column name instead.
            value = semanticText(column == null ? "value" : column.name(), max, column, table);
        } else if (type.matches(".*[a-z_][a-z0-9_]*")) {
            List<String> enumValues = enumValues(sqlType);
            if (!enumValues.isEmpty()) value = enumValues.get(ThreadLocalRandom.current().nextInt(enumValues.size()));
            else {
                if (column != null && table != null) {
                for (TableConstraint constraint : table.constraints()) {
                    if ("CHECK".equals(constraint.type()) && constraint.definition().toLowerCase(Locale.ROOT).contains(column.name().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("CHECK constraint cannot be safely satisfied from metadata: " + constraint.name() + ".");
                    }
                }
                }
                throw new IllegalArgumentException("No safe generator for PostgreSQL type " + sqlType + ".");
            }
        } else throw new IllegalArgumentException("No safe generator for PostgreSQL type " + sqlType + ".");

        if (column != null && table != null) value = applySimpleChecks(value, sqlType, column, table);
        return value;
    }

    private Object checkConstrainedValue(String type, TableColumn column, TableMetadata table) {
        for (TableConstraint constraint : table.constraints()) {
            if (!"CHECK".equals(constraint.type())) continue;
            String definition = constraint.definition();
            if (!definition.toLowerCase(Locale.ROOT).contains(column.name().toLowerCase(Locale.ROOT))) continue;
            Matcher in = Pattern.compile("(?is)(?:\\\"?" + Pattern.quote(column.name()) + "\\\"?)\\s+IN\\s*\\(([^)]*)\\)").matcher(definition);
            if (in.find()) {
                Matcher literals = Pattern.compile("'((?:''|[^'])*)'|(-?[0-9]+(?:\\.[0-9]+)?)|\\b(TRUE|FALSE)\\b", Pattern.CASE_INSENSITIVE).matcher(in.group(1));
                List<Object> allowed = new ArrayList<>();
                while (literals.find()) {
                    if (literals.group(1) != null) allowed.add(literals.group(1).replace("''", "'"));
                    else if (literals.group(2) != null) allowed.add(new BigDecimal(literals.group(2)));
                    else allowed.add(Boolean.valueOf(literals.group(3)));
                }
                if (!allowed.isEmpty()) return allowed.get(ThreadLocalRandom.current().nextInt(allowed.size()));
            }
            Matcher equals = Pattern.compile("(?is)\\b" + Pattern.quote(column.name()) + "\\s*=\\s*'((?:''|[^'])*)'").matcher(definition);
            if (equals.find()) return equals.group(1).replace("''", "'");
            Matcher numericEquals = Pattern.compile("(?is)\\b" + Pattern.quote(column.name()) + "\\s*=\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(definition);
            if (numericEquals.find()) return new BigDecimal(numericEquals.group(1));
            BigDecimal numeric = numericCheckValue(column.name(), definition);
            if (numeric != null) return numeric;
            if (definition.matches("(?is).*\\b" + Pattern.quote(column.name()) + "\\s+IS\\s+NOT\\s+NULL.*")) continue;
            if (definition.matches("(?is).*\\b(length|char_length)\\s*\\(\\s*" + Pattern.quote(column.name()) + "\\s*\\)\\s*[<]=?\\s*[0-9]+.*")) continue;
            throw new IllegalArgumentException("CHECK constraint semantics are unsupported for " + table.qualifiedName() + "." + column.name() + ": " + constraint.name() + ".");
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        if (normalized.contains("int") || normalized.contains("numeric") || normalized.contains("decimal")) return randomNumeric(column.sqlType());
        if (normalized.contains("char") || normalized.contains("text")) {
            int max = Math.min(properties.getMaxGeneratedStringLength(), parseLength(column.sqlType(), properties.getMaxGeneratedStringLength()));
            if (max < 8) throw new IllegalArgumentException("String CHECK requires a value shorter than the minimum collision-resistant test value.");
            return "v_" + UUID.randomUUID().toString().replace("-", "").substring(0, Math.min(32, max - 2));
        }
        if (normalized.equals("boolean") || normalized.equals("bool")) return ThreadLocalRandom.current().nextBoolean();
        throw new IllegalArgumentException("CHECK constraints do not define an unambiguous test value for " + column.name() + ".");
    }

    private static boolean hasColumnCheck(TableMetadata table, String column) {
        return table.constraints().stream().filter(constraint -> "CHECK".equals(constraint.type()))
                .anyMatch(constraint -> constraint.definition().toLowerCase(Locale.ROOT).contains(column.toLowerCase(Locale.ROOT)));
    }

    /** Generates plausible synthetic values from a column/parameter name without copying real PII. */
    private String semanticText(String rawName, int maxLength, TableColumn column, TableMetadata table) {
        if (maxLength < 1) throw new IllegalArgumentException("Text value has no permitted length.");
        String name = rawName == null ? "value" : rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String value;

        // Specific semantic rules must run before generic "name"/"type"/"code" rules.
        String targetType = column == null || column.sqlType() == null ? "" : column.sqlType().toLowerCase(Locale.ROOT);
        if (targetType.contains("timestamp") || targetType.contains("datetime")
                || name.contains("timestamp") || name.contains("datetime") || name.contains("updatedt") || name.contains("createdt")) {
            value = LocalDateTime.now().minusDays(ThreadLocalRandom.current().nextInt(1, 1000)).withNano(0).toString();
        } else if ((name.contains("dob") || name.endsWith("date") || name.endsWith("dt") || name.contains("birthdate"))
                && !name.contains("signature")) {
            value = java.time.LocalDate.now().minusDays(ThreadLocalRandom.current().nextInt(7000, 25000)).toString();
        } else if ((name.endsWith("id") || name.endsWith("appid") || name.endsWith("proid"))
                && !name.contains("uuid") && !name.contains("guid")) {
            value = Integer.toString(ThreadLocalRandom.current().nextInt(1000, 999999));
        } else if (name.contains("sex") || name.contains("gender")) {
            // A one-character value is used only when the actual target column has length one.
            boolean female = ThreadLocalRandom.current().nextBoolean();
            value = maxLength == 1 ? (female ? "F" : "M") : (female ? "Female" : "Male");
        } else if (name.contains("email") || name.contains("mail")) {
            // Keep the email syntactically valid even when the target is a short varchar.
            if (maxLength < 6) throw new IllegalArgumentException("Email target " + (column == null ? rawName : column.name()) + " is too short for a valid generated email address.");
            String local = "user" + token.toLowerCase(Locale.ROOT);
            String domain = "@gmail.com";
            if (local.length() + domain.length() > maxLength) {
                int localCapacity = maxLength - domain.length();
                if (localCapacity < 1) {
                    domain = "@x.co";
                    localCapacity = maxLength - domain.length();
                }
                local = local.substring(0, Math.min(local.length(), localCapacity));
            }
            value = local + domain;
        } else if (name.contains("phone") || name.contains("fax")) {
            value = "555" + String.format(Locale.ROOT, "%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        } else if (name.contains("zip") || name.contains("postal")) {
            value = String.format(Locale.ROOT, "%05d", ThreadLocalRandom.current().nextInt(100_000));
        } else if (name.contains("state") || name.endsWith("st")) {
            value = new String[] {"CA", "NY", "TX", "FL", "WA"}[ThreadLocalRandom.current().nextInt(5)];
        } else if (name.contains("firstname") || name.contains("firstnm") || name.endsWith("fnm")
                || name.endsWith("fname") || name.equals("appfnm")) {
            if (maxLength < 2) {
                throw new IllegalArgumentException("First-name column " + (column == null ? rawName : column.name())
                        + " has maximum length " + maxLength + "; cannot generate a full first name. Check the PostgreSQL column definition/metadata instead of silently truncating it to one character.");
            }
            value = new String[] {"John", "James", "Robert", "David", "Emma", "Maya", "Chandana", "Aarav", "Riya", "Olivia"}
                    [ThreadLocalRandom.current().nextInt(10)];
        } else if (name.contains("lastname") || name.contains("lastnm") || name.endsWith("lnm") || name.endsWith("lname")) {
            if (maxLength < 2) {
                throw new IllegalArgumentException("Last-name column " + (column == null ? rawName : column.name())
                        + " has maximum length " + maxLength + "; cannot generate a full last name. Check the PostgreSQL column definition/metadata instead of silently truncating it to one character.");
            }
            value = new String[] {"Wisely", "Miller", "Sharma", "Taylor", "Patel", "Reed", "Reddy", "Wilson", "Kumar", "Brown"}
                    [ThreadLocalRandom.current().nextInt(10)];
        } else if (name.contains("callname") || name.equals("callnm")) {
            value = new String[] {"Inbound", "Support", "Followup", "Welcome", "Outbound"}
                    [ThreadLocalRandom.current().nextInt(5)];
        } else if (name.contains("name") || name.endsWith("nm") || name.contains("user") || name.contains("login")) {
            value = new String[] {"John Byer", "James Miller", "Maya Patel", "Casey Reed", "Aarav Sharma", "Riya Kumar", "Emma Wilson", "David Brown"}
                    [ThreadLocalRandom.current().nextInt(6)];
        } else if (name.contains("city")) {
            value = new String[] {"Austin", "Denver", "Seattle", "Madison", "Phoenix"}[ThreadLocalRandom.current().nextInt(5)];
        } else if (name.contains("addr") || name.contains("address") || name.contains("street")) {
            value = (100 + ThreadLocalRandom.current().nextInt(9899)) + " Test Street";
        } else if (name.contains("ssn") || name.contains("taxid")) {
            value = "900" + String.format(Locale.ROOT, "%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        } else if (name.contains("url") || name.contains("website")) {
            value = "https://example.test/" + token.toLowerCase(Locale.ROOT);
        } else if (name.contains("date") || name.endsWith("dt") || name.contains("dob")) {
            // Text-typed date fields get an ISO date, rather than arbitrary random text.
            value = LocalDate.now().minusDays(ThreadLocalRandom.current().nextInt(1, 1000)).toString();
        } else if (name.contains("description") || name.contains("comment") || name.contains("detail")
                || name.contains("note") || name.contains("message") || name.contains("error")) {
            value = "Synthetic test data " + token;
        } else if (name.contains("year")) {
            value = new String[] {"1998", "2000", "2003", "2024"}[ThreadLocalRandom.current().nextInt(4)];
        } else if (name.contains("status")) {
            value = new String[] {"Active", "Pending", "Reviewed", "Complete"}[ThreadLocalRandom.current().nextInt(4)];
        } else if (name.contains("type") || name.contains("mode")) {
            value = new String[] {"Standard", "Primary", "Individual", "Scheduled"}[ThreadLocalRandom.current().nextInt(4)];
        } else if (name.contains("code")) {
            value = "TESTCODE" + token;
        } else if ((name.contains("id") || name.endsWith("key") || name.contains("number") || name.endsWith("num"))
                && column != null && isSingleColumnUnique(table, column.name())) {
            // Text-based identifier columns without a PostgreSQL identity/default use a
            // monotonically increasing, width-bounded candidate. Numeric identity columns
            // are handled earlier from their actual PostgreSQL sequence metadata.
            String sequenceKey = table.qualifiedName() + "." + column.name();
            long sequence = generatedTextKeySequences.computeIfAbsent(sequenceKey, ignored -> new AtomicLong(0)).incrementAndGet();
            String candidate = "A" + sequence;
            if (candidate.length() > maxLength) {
                String digits = Long.toString(sequence);
                if (digits.length() > maxLength) throw new IllegalArgumentException("Generated text identifier exhausted the available width for " + sequenceKey + ".");
                candidate = digits;
            }
            value = candidate;
        } else if (name.contains("id") || name.endsWith("key") || name.contains("number") || name.endsWith("num")) {
            value = "TEST" + token;
        } else {
            value = "Test value " + token;
        }

        // CHAR(n)/BPCHAR(n) has a hard storage limit. For n > 1, use the full semantic
        // value when it fits; only truncate when the actual database column is too short.
        if (value.length() > maxLength) value = value.substring(0, maxLength);
        if (value.isBlank()) throw new IllegalArgumentException("Generated text value is blank after applying the actual column width for " + (column == null ? rawName : column.name()) + ".");
        // The user explicitly disallows one-character filler for BPCHAR/CHAR columns
        // wider than one character. Preserve real one-character codes only for CHAR(1).
        if (maxLength > 1 && value.length() == 1) {
            value = ("T" + token).substring(0, Math.min(maxLength, 1 + token.length()));
        }
        return value;
    }

    private Object applySimpleChecks(Object value, String type, TableColumn column, TableMetadata table) {
        String result = String.valueOf(value);
        List<String> checks = table.constraints().stream().filter(c -> "CHECK".equals(c.type()))
                .map(TableConstraint::definition)
                .filter(definition -> definition.toLowerCase(Locale.ROOT).contains(column.name().toLowerCase(Locale.ROOT))).toList();
        if (checks.isEmpty()) return value;
        if (type.matches("(?is).*(smallint|integer|bigint|int[248]|numeric|decimal|serial|real|double precision|float[48]).*")) {
            String combined = String.join(" AND ", checks);
            if (Pattern.compile("(?is)\\b" + Pattern.quote(column.name()) + "\\b\\s*(>=|>|<=|<|=)").matcher(combined).find()) {
                BigDecimal constrained = numericCheckValue(column.name(), combined, numericScale(column.sqlType()));
                if (constrained == null) throw new IllegalArgumentException("CHECK constraint bounds cannot be derived safely for " + table.qualifiedName() + "." + column.name() + ".");
                return coerceNumeric(constrained, type);
            }
        }
        if (type.equalsIgnoreCase("boolean") || type.equalsIgnoreCase("bool")) {
            Matcher bool = Pattern.compile("(?is)\\b" + Pattern.quote(column.name()) + "\\s*=\\s*(TRUE|FALSE)\\b").matcher(String.join(" AND ", checks));
            if (bool.find()) return Boolean.valueOf(bool.group(1));
        }
        for (String definition : checks) {
            if (definition.matches("(?is).*\\b" + Pattern.quote(column.name()) + "\\s+IS\\s+NOT\\s+NULL.*")) continue;
            Matcher in = Pattern.compile("(?is)\\b" + Pattern.quote(column.name()) + "\\b\\s+IN\\s*\\(([^)]*)\\)").matcher(definition);
            if (in.find()) {
                Matcher literal = Pattern.compile("'((?:''|[^'])*)'").matcher(in.group(1));
                List<String> allowed = new ArrayList<>();
                while (literal.find()) allowed.add(literal.group(1).replace("''", "'"));
                if (!allowed.isEmpty()) return allowed.get(ThreadLocalRandom.current().nextInt(allowed.size()));
            }
            Matcher equals = Pattern.compile("(?is)\\b" + Pattern.quote(column.name()) + "\\s*=\\s*'((?:''|[^'])*)'").matcher(definition);
            if (equals.find()) return equals.group(1).replace("''", "'");
            Matcher max = Pattern.compile("(?is)(?:length|char_length)\\s*\\(\\s*" + Pattern.quote(column.name()) + "\\s*\\)\\s*<=\\s*([0-9]+)").matcher(definition);
            if (max.find()) return result.length() > Integer.parseInt(max.group(1))
                    ? result.substring(0, Integer.parseInt(max.group(1))) : value;
            throw new IllegalArgumentException("CHECK constraint semantics are unsupported for " + table.qualifiedName() + "." + column.name() + ".");
        }
        return value;
    }

    private Map<String, Object> foreignKeyValues(TableMetadata localTable, ForeignKey fk) {
        List<ForeignKey> group = localTable.foreignKeys().stream().filter(candidate -> candidate.name().equals(fk.name())).toList();
        List<String> referencedColumns = group.stream().map(ForeignKey::referencedColumn).distinct().toList();
        String projection = String.join(", ", referencedColumns.stream().map(TestCaseFactory::quote).toList());
        String nonNull = String.join(" AND ", referencedColumns.stream().map(c -> quote(c) + " IS NOT NULL").toList());
        String sql = "SELECT " + projection + " FROM " + quote(fk.referencedSchema()) + "." + quote(fk.referencedTable()) + " WHERE " + nonNull + " LIMIT 1";
        Map<String, Object> existing = jdbc.query(sql, rs -> {
            if (!rs.next()) return Map.of();
            Map<String, Object> values = new LinkedHashMap<>();
            for (String column : referencedColumns) values.put(column, rs.getObject(column));
            return values;
        });
        if (!existing.isEmpty() && existing.values().stream().noneMatch(java.util.Objects::isNull)) return existing;
        return Map.of();
    }
    private boolean exists(TableMetadata table, String column, Object value) {
        String sql = "SELECT EXISTS (SELECT 1 FROM " + quote(table.schema()) + "." + quote(table.name()) + " WHERE " + quote(column) + " = ?)";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, value));
    }
    private static boolean isSingleColumnUnique(TableMetadata table, String column) {
        return table.primaryKey().size() == 1 && table.primaryKey().getFirst().equals(column)
                || table.constraints().stream().filter(c -> "UNIQUE".equals(c.type()) || "PRIMARY KEY".equals(c.type()))
                .anyMatch(c -> c.definition().matches("(?is).*\\(\\s*\"?" + Pattern.quote(column) + "\"?\\s*\\).*") && !c.definition().contains(","));
    }
    private List<String> enumValues(String sqlType) {
        String typeName = sqlType;
        String schema = "public";
        if (typeName.contains(".")) {
            int dot = typeName.lastIndexOf('.');
            schema = typeName.substring(0, dot).replace("\"", "");
            typeName = typeName.substring(dot + 1);
        }
        typeName = typeName.replace("\"", "").replaceAll("\\[\\]$", "");
        return metadata.enumValues(schema, typeName);
    }
    private static BigDecimal randomNumeric(String sqlType) {
        Matcher precision = Pattern.compile("\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)").matcher(sqlType);
        int digits = 8, scale = 2;
        if (precision.find()) {
            digits = Integer.parseInt(precision.group(1));
            scale = precision.group(2) == null ? 0 : Integer.parseInt(precision.group(2));
        }
        scale = Math.min(scale, Math.max(0, digits - 1));
        int integerDigits = Math.max(1, Math.min(4, digits - scale));
        long maxWhole = (long) Math.pow(10, integerDigits) - 1;
        long whole = ThreadLocalRandom.current().nextLong(1, Math.max(2, maxWhole + 1));
        int safeScale = Math.min(scale, 8);
        long fractional = safeScale == 0 ? 0 : ThreadLocalRandom.current().nextLong(0, (long) Math.pow(10, safeScale));
        String fraction = safeScale == 0 ? "" : "." + String.format(Locale.ROOT, "%0" + safeScale + "d", fractional);
        return new BigDecimal(whole + fraction).setScale(scale, RoundingMode.DOWN);
    }
    private static BigDecimal numericCheckValue(String column, String definition) {
        return numericCheckValue(column, definition, 0);
    }
    private static BigDecimal numericCheckValue(String column, String definition, int scale) {
        if (Pattern.compile("(?i)\\bOR\\b").matcher(definition).find()) return null;
        Matcher matcher = Pattern.compile("(?is)\\b" + Pattern.quote(column) + "\\b\\s*(>=|>|<=|<|=)\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(definition);
        BigDecimal min = null, max = null;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            BigDecimal bound = new BigDecimal(matcher.group(2));
            BigDecimal epsilon = BigDecimal.ONE.movePointLeft(scale == 0 ? 0 : Math.max(scale, bound.scale()));
            switch (matcher.group(1)) {
                case ">=" -> min = min == null ? bound : min.max(bound);
                case ">" -> { BigDecimal candidate = bound.add(epsilon); min = min == null ? candidate : min.max(candidate); }
                case "<=" -> max = max == null ? bound : max.min(bound);
                case "<" -> { BigDecimal candidate = bound.subtract(epsilon); max = max == null ? candidate : max.min(candidate); }
                case "=" -> { min = min == null ? bound : min.max(bound); max = max == null ? bound : max.min(bound); }
                default -> { }
            }
        }
        if (!found || (min != null && max != null && min.compareTo(max) > 0)) return null;
        if (min == null) return max;
        if (max == null) return min;
        return min.add(max.subtract(min).divide(BigDecimal.valueOf(2), Math.max(min.scale(), max.scale()), RoundingMode.DOWN));
    }
    private static int numericScale(String sqlType) {
        Matcher matcher = Pattern.compile("\\(\\d+\\s*,\\s*(\\d+)\\)").matcher(sqlType);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
    private static Object coerceNumeric(BigDecimal value, String sqlType) {
        String type = sqlType.toLowerCase(Locale.ROOT);
        try {
            if (type.contains("smallint") || type.contains("int2")) return value.shortValueExact();
            if ((type.contains("integer") || type.contains("int4") || type.contains("serial")) && !type.contains("bigserial")) return value.intValueExact();
            if (type.contains("bigint") || type.contains("int8") || type.contains("bigserial")) return value.longValueExact();
            return value;
        } catch (ArithmeticException outsideType) {
            throw new IllegalArgumentException("CHECK constraint bounds cannot be represented by PostgreSQL type " + sqlType + ".");
        }
    }
    private TableMetadata describe(String qualified) {
        int dot = qualified.indexOf('.');
        if (dot < 1) throw new IllegalArgumentException("Unqualified target table cannot be used safely: " + qualified);
        return metadata.describe(qualified.substring(0, dot), qualified.substring(dot + 1));
    }
    private static int parseLength(String sqlType, int fallback) {
        Matcher matcher = Pattern.compile("\\((\\d+)\\)").matcher(sqlType == null ? "" : sqlType);
        if (matcher.find()) return Math.max(1, Integer.parseInt(matcher.group(1)));
        // PostgreSQL bpchar/CHAR without an explicit modifier is CHAR(1), not unlimited text.
        String normalized = sqlType == null ? "" : sqlType.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("character") || normalized.equals("char") || normalized.equals("bpchar")) return 1;
        return fallback;
    }
    private static boolean isPredicateRole(String role) {
        return "PREDICATE".equals(role) || role.startsWith("PREDICATE_CAST:");
    }
    private static boolean isNumericType(String sqlType) {
        String type = sqlType.toLowerCase(Locale.ROOT);
        return type.matches("(?is).*(smallint|integer|bigint|int2|int4|int8|numeric|decimal|serial|real|double precision|float[48]).*");
    }
    private static boolean isTextType(String sqlType) {
        String type = sqlType.toLowerCase(Locale.ROOT);
        return type.contains("char") || type.contains("text") || type.equals("name");
    }
    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
}
