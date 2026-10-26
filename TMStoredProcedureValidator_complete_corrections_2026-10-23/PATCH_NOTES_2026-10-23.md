# Patch notes — synthetic test data generation

## Fix
- Removed the strategy that copied existing short strings from target tables into generated test inputs. This could reuse values such as `v` and produce meaningless rows.
- Text generation now consistently uses semantic column/parameter names and generates synthetic values rather than reusing existing table data.
- Updated the short state-code regression test to assert semantic synthetic values are generated instead of copying an existing value.

## Scope and limitations
- This patch preserves the existing schema, table names, column names, database constraints, generated/identity-column protections, foreign-key behavior, and write verification safeguards.
- It does not guarantee every stored procedure can commit. Missing tables/columns, incompatible routine signatures, unsupported record-return functions, business rules, foreign-key prerequisites, and unsafe update/delete routines still require separate fixes.
- A one-character column can only hold one character; the generator cannot turn a schema-defined one-character field into a full name without a schema change.

## Verification
Run with Java 21 and working Maven dependency access:

```bat
mvnw.cmd test
```

Then test against a disposable PostgreSQL database and review the JSON report. Do not disable constraints or write verification to increase the commit count.

## Follow-up review patch
- Added PostgreSQL built-in string/array conversion functions (`string_to_array`, `array_to_string`, `string_to_table`) to the analyzer's known pure-call allowlist. These built-ins are no longer incorrectly treated as unresolved user-defined routine dependencies when referenced in routine bodies.
- Deliberately did not weaken primary-key verification for writes or allow broad UPDATE/DELETE routines to execute without current-run row targeting.
- Deliberately did not change `ASP_INSERT_ALIAS` or `ASP_SET_PRODELETE_STATE` automatically: the report shows a missing required `UA_Default` value in the former and an invalid `Pro_MkID` column reference in the latter. Those require inspecting/correcting the actual routine definition or schema, not bypassing routine logic from Java.
- Automated Maven tests could not run in this environment because the Maven wrapper could not download Maven from `repo.maven.apache.org` (network/DNS unavailable). Run `mvnw.cmd test` locally before deploying.

## Follow-up correction pass (2026-10-23)

See `CORRECTION_REVIEW_2026-10-23.md` for a source-by-source summary and verified test status. This pass adds FK-based parent-before-child write ordering, full non-null verified row-value tracking for FK/unique-key reuse, composite-PK consistency for current-run UPDATE/DELETE targeting, real sequence-based candidates for explicitly supplied identity/serial values where the routine contract permits them, broader unique-index metadata, timestamp-shaped text for timestamp-backed fields, stricter unknown business-code handling, and unconditional blocking for anonymous `record` functions without a concrete output contract. Maven tests could not run because the wrapper download failed with DNS resolution error for `repo.maven.apache.org`.


## 2026-10-23: realistic name generation follow-up

- Updated synthetic first-name values (for example, John/Jhon) and last-name values (for example, Byer/Miller); generic person-name parameters now also have realistic full-name options.
- Important PostgreSQL limitation: the uploaded report shows routine parameters such as `"AD_First_Name" character` and `"AD_Last_Name" character`. In PostgreSQL, bare `character` means `character(1)`, so the database truncates `John`/`Byer` to one character before the routine body receives them. Java-side generation alone cannot fix this. If the intended contract is full names, the routine parameter types must be corrected to `character varying`/`varchar` (and the target columns must support that width) in a separately reviewed SQL migration.
- Destructive-write safety checks were retained. Matching a table name to a routine does not prove the routine is bounded or safe to execute; blindly removing UPDATE/DELETE ownership checks could modify existing data. Eligible writes continue to commit only after verification.
