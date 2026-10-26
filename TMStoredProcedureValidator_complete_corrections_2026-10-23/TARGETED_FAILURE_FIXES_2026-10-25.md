# Targeted Java updates for the 25 October execution errors

This package updates the validator's generated-input behavior and per-error remediation messages. It does not rewrite or deploy PostgreSQL routine bodies or create missing business tables.

## Implemented in Java

- **Parameter-to-column generation:** values are adjusted using the matched target column metadata. Character parameters bound to `date`/`timestamp` columns receive ISO-formatted values; integer parameters bound to Boolean columns are limited to `0`/`1`; generated text is bounded by the actual target column length where a unique mapping exists.
- **Unique keys:** collision checks for mapped single-column primary/unique keys now retry up to 100 candidates. If catalog metadata provides an identity/sequence, the generator advances the real sequence normally and checks the candidate before using it. It never resets sequences or bypasses the routine.
- **`ASP_INSERT_APPLICATION`:** report guidance now identifies the `p_app_dob` character-to-`App_DOB` timestamp mismatch and describes the routine-side cast needed if the signature remains character.
- **`ASP_INSERT_ALIAS`:** report guidance identifies `TM_User_Alias.UA_Default` NOT NULL failure and explicitly avoids inventing a business default.
- **`ASP_PUT_IN_ROTATION`:** integer inputs mapped to Boolean target columns are restricted to `0`/`1`; if PostgreSQL still rejects the assignment, the routine body needs an explicit conversion.
- **`ASP_INSERT_PRODUCT` / `Asp_Add_Lead_Source`:** duplicate-key guidance points to mapped key generation, sequence metadata, and the possibility that the routine overrides the supplied key.
- **HIP application routines:** invalid timestamp guidance points to ISO generation and explicit conversion of text parameters.
- **`Asp_Add_User`:** SQLSTATE `22001` now points to actual target column length and binding accuracy.
- **Read-function errors:** SQLSTATE `42702` continues to report the need to qualify ambiguous columns in the routine body.
- **`ASP_GET_REF_CODES`:** SQLSTATE `42P01` now names `public1.TM_Lead_Source_Permission` and tells the operator to verify the actual schema/table in catalog metadata.
- **`SETOF record`:** the validator continues to read declared `OUT`/`INOUT`/`TABLE` columns and named composite return columns. Anonymous record routines without a catalog output contract remain blocked rather than guessing a result shape.

## Database-side work that Java cannot safely perform

1. A `SETOF record` routine with no declared output contract must be given a correct `OUT`/`RETURNS TABLE` or named composite contract in SQL. The contract must be derived from the original routine/query, not guessed.
2. Ambiguous SQL identifiers require qualifying the column references in the stored routine body.
3. `TM_User_Alias.UA_Default` needs a documented business value, a correct routine assignment, or an approved schema default.
4. A character-to-timestamp or integer-to-Boolean mismatch may require an explicit conversion in the routine body. The callable signature is not silently changed.
5. A missing `public1.TM_Lead_Source_Permission` table must be checked against the original DDL/schema. This project intentionally does not invent or auto-create it.

## Verification status

Maven is not installed in this execution environment, and the Maven Wrapper previously could not download Maven due to DNS/network resolution. Therefore, this ZIP has **not** been compiled or integration-tested against the user's PostgreSQL database in this session. Run `mvnw.cmd test` on Windows with Maven dependencies reachable, then run the validator and inspect the new JSON report.
