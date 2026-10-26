# TMStoredProcedureValidator correction review — 2026-10-23

## Source reviewed

- Project: `TMStoredProcedureValidator_updated_corrections_2026-10-23.zip`
- Run report: `reports/run-85aa8c23-4e64-4e14-87ca-e030aac2edea.json`
- Report database/schema: `tm` / `public1`
- Baseline: 277 routines; 121 WRITE-classified routines; 23 verified committed writes; 11 failed WRITE cases; 87 blocked WRITE routines; 104 failed READ routines; 29 blocked READ routines; 22 verified READ routines.

## Changes made in this correction pass

1. **Routine result contract / SQLSTATE 42601**
   - The run coordinator now blocks anonymous `record`/`SETOF record` routines with no concrete result-column contract regardless of how the analyzer classified their operation. This prevents a function with an unknown record shape from reaching `SELECT * FROM routine(...)` and failing with `a column definition list is required`.
   - Concrete OUT/TABLE/composite/scalar result contracts continue through the existing contract verification path.

2. **Identity and sequence-backed input values**
   - When an input maps to an INSERT target identity column, the generator now tries to obtain the actual sequence using `pg_get_serial_sequence` and advances it with normal `nextval` calls. It checks candidate collisions against the target column and does not reset a sequence or insert directly into the table.
   - For `GENERATED ALWAYS`, it only permits the explicit-identity path when the actual routine definition contains `OVERRIDING SYSTEM VALUE`; otherwise it blocks with a specific contract message.
   - The same normal sequence lookup is used for mapped `nextval(...)`-default columns. If PostgreSQL cannot resolve the sequence, the case remains blocked with an actionable reason.
   - This is safer than arbitrary IDs, but it does not prove that every legacy routine's internal identifier transformation is correct. `ASP_INSERT_APPLICATION` and `ASP_INSERT_COI_App_Dependants_AU` must be rerun against the actual database to verify their behavior.

3. **Prerequisite ordering and generated-record tracking**
   - The planner now adds ordering-only edges from routines that INSERT into referenced parent tables to routines that INSERT into child tables, based on catalog foreign-key metadata.
   - These edges affect order without turning every possible parent writer into a mandatory routine-call dependency. A successful parent writer can register a key; failed parent writers do not automatically block unrelated parent writers.
   - Verified row snapshots now retain non-null column values as well as primary-key values. The generated-record registry can therefore reuse a verified non-null referenced unique-key value, not only a primary-key value, for a foreign-key prerequisite.
   - UPDATE/DELETE key selection now takes all columns of a composite primary key from the same verified row, avoiding accidental mixing of key components from different records.
   - The validator still does not synthesize business rows by direct table insert. If no safe creator routine succeeds, the dependent case remains blocked.

4. **Metadata coverage**
   - Catalog metadata now includes unconditional unique indexes that are not represented by a table constraint. Partial and expression indexes are intentionally excluded from generic uniqueness inference because their predicates/expressions require dedicated handling.
   - Existing primary-key, foreign-key, identity, default, CHECK, column length, nullability, and routine signature discovery remains in place.

5. **Generated values**
   - Text inputs mapped to timestamp/datetime columns or clearly timestamp-like names receive ISO local date-time text instead of a generic filler/date-only value. Native PostgreSQL temporal parameters continue to receive typed JDBC-compatible Java temporal values.
   - Unknown numeric business codes (including numeric option/sex/gender codes without a CHECK or FK/lookup contract) are blocked instead of guessed. Names that clearly indicate boolean flags such as `has...`, `rider`, `active`, or `...flag` can use 0/1; arbitrary integers are not globally treated as booleans.
   - Text values remain constrained by actual column length and retain meaningful multi-character values when the target allows them.

6. **Regression tests added/updated**
   - Parent-before-child ordering from FK metadata.
   - Timestamp text generation for a text parameter mapped to a timestamp column.
   - Reuse of verified non-null unique values from full row evidence.
   - Updated sequence-resolution expectations for identity/sequence-backed parameters.

## Build/test result

- Java runtime available: Java 21.
- `bash mvnw test -q` was attempted.
- **Tests did not run** because Maven Wrapper could not download Maven: `java.net.UnknownHostException: repo.maven.apache.org` (DNS/network unavailable).
- `mvn package` could not be run for the same environment limitation. No claim is made that compilation or tests passed.

## Remaining limitations / manual review

- The report's 32 blocked UPDATE/DELETE routines that do not bind every primary-key column remain blocked intentionally. They need a safe, current-run target contract; do not remove the guard.
- Tables without a primary key (e.g. `TM_Empi_Error_Log`, `TM_Login_History`, `TM_BPM_Error_Queue`, `TM_Prosp_Key`) still cannot be reliably verified by the existing snapshot strategy. Do not invent keys or change schema without approval.
- The report's missing prerequisite tables/keys can only be resolved when a suitable supported creator routine is identified, runs successfully, and its generated key is verified. The planner now orders routine writers by FK metadata, but it cannot invent a missing business-code contract or guarantee a valid creator exists for every table.
- Duplicate-key, FK, timestamp, string-length, and integer-conversion errors still need confirmation from the next live run. Collision-resistant generation and metadata checks reduce these errors but cannot infer undocumented business rules or dynamic SQL behavior.
- The report includes many failed READ routines beyond anonymous `record` functions (missing relations/schema references, unresolved calls, unsafe dynamic SQL). Those require the exact database-side routine contract/dependency to be corrected or explicitly left blocked.
- Dynamic SQL with unresolved targets, broad cleanup/delete behavior, routines with unbounded external effects, and routines without reliable row-level verification remain protected.

## Required next validation

1. Extract this ZIP over a copy of the current project.
2. Configure credentials externally and confirm the connection targets only the disposable `tm` test database and `public1` schema.
3. Run `mvn test` and `mvn package` where Maven dependencies are reachable.
4. Run the validator once, then inspect the new JSON report. Confirm the identity routines return/insert the expected generated IDs, FK parent rows are registered before child writes, and every committed write has row-level evidence.
5. Compare the new report with the baseline. Do not assume all 121 WRITE-classified routines should commit rows: safe INSERT/UPDATE routines should be verified; unsafe DELETE/cleanup routines or unsupported contracts should remain blocked with specific reasons.
