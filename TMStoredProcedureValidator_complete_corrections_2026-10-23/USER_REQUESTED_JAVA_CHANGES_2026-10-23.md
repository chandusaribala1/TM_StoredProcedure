# User-requested Java changes — 2026-10-23

## Implemented in this patch

- Routine execution remains phased: INSERT writers first, UPDATE writers second, DELETE writers third, GET-style reads fourth, and other SELECT/read routines last. Each verified write is committed independently.
- Generated values use the actual PostgreSQL parameter/column type and length metadata. Native `date`, `timestamp`, and `timestamp with time zone` values are generated as `LocalDate`, `LocalDateTime`, and `OffsetDateTime`. Text-typed date parameters receive ISO-shaped text when their contract is text.
- Numeric identity/sequence candidates are obtained from PostgreSQL's real sequence using `pg_get_serial_sequence` and `nextval`; the routine itself performs the write. No sequence reset or direct table insert is performed.
- Text identifier candidates for uniquely constrained non-identity columns use a bounded increasing sequence (`A1`, `A2`, …) where the column width permits; collision checks still apply.
- Email values are kept syntactically valid within the real column width; name/sex/email/date/phone/address/zip semantics remain based on the target column name.
- Values are bounded to the target column's declared size. A one-character sex/gender value is used only for a one-character target.
- Dynamic SQL routines are left uninvoked with no generated inputs, per request. Unsupported `record` return contracts remain blocked until real result columns/types can be established.

## Safety boundary

The request to remove all safety checks was not applied globally. UPDATE/DELETE ownership verification, primary-key verification, routine transaction-control checks, and broad-cleanup/dynamic-SQL blocks remain in place. Removing these globally could modify existing records outside the test run. The validator instead should execute UPDATE/DELETE routines only when it can bind them to verified records created during the current run.

## Remaining limitation

Foreign-key ordering is metadata-based and schedules supported parent INSERT writers before child INSERT writers. If no supported routine can create a required parent, or the routine's true business contract cannot be inferred from metadata/constraints/lookups, the dependent routine remains blocked rather than inventing a business code or bypassing the routine.

## Build/test status

The source changes were reviewed statically. Maven tests/build have not been verified in this environment; run `./mvnw test` and `./mvnw package` where Maven dependencies are available.
