# Corrections based on run 85aa8c23-4e64-4e14-87ca-e030aac2edea

## Changes applied

1. **Anonymous `record` result detection**: PostgreSQL reports functions returning anonymous rows as `record` or `SETOF record`. The previous code compared the entire catalog result string to exactly `record`, allowing `SETOF record` functions to reach `SELECT * FROM function(...)` and fail with SQLSTATE `42601`. The validator now recognizes both forms and blocks invocation when no stable result-column contract can be established from catalog metadata. The block reason explains why and what must be reviewed.
2. **Result-contract validation**: Scalar result checks now use the same anonymous-record detection, preventing a generic scalar result check from treating `SETOF record` as a known one-column result.
3. **PostgreSQL `character` default width**: Catalog types `character`, `char`, and `bpchar` without a typmod represent the default one-character type. The generator now applies a width of one rather than using the general text fallback, reducing avoidable `22001` errors. Explicit `character(n)` / `bpchar(n)` widths still use the declared modifier.
4. **Date-shaped text inputs**: Parameters whose names clearly indicate DOB/date fields now receive ISO `yyyy-MM-dd` values; text ID parameters receive numeric-looking values where their name indicates an ID. These remain synthetic values and do not imply that the identifier is a valid business code.
5. **Regression tests**: Added tests for `SETOF record`, whitespace/case variants, and known scalar/composite return types.

## Important limitations / manual review

- The 136 `42601` failures in this report are consistent with anonymous `record` return contracts. This patch blocks calls whose result shape is not established; it does not invent a column list. To execute such routines, inspect their actual function body and establish a reliable output contract, preferably through declared OUT/TABLE parameters or a named composite result. Do not alter signatures speculatively.
- Duplicate-key errors (`23505`) need routine-specific analysis of generated/transformed keys and composite constraints. A collision retry alone cannot safely fix a routine that transforms IDs or ignores its key parameters. The existing code continues to avoid direct table inserts.
- FK failures (`23503`) require an existing or safely generated parent through a supported write routine. The current-run prerequisite registry is not a substitute for an unknown business-code mapping.
- Some fields use legacy business codes or cast text to numeric/date types. Without a known contract or lookup row, the validator should block rather than guess valid codes.
- Maven was not available as an executable in this environment, and the wrapper was not executable by file mode. Tests/build could not be run here; no test pass is claimed.

Database `tm`, schema `public1`, object names, data types, constraints, routine signatures, and business logic were not changed by this patch.
