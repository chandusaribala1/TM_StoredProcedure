# Corrections based on requested write-first execution and run report

Baseline report: `run-eb98af3c-7617-42b1-8a8a-23eda700b8fa.json` (database `tm`, schema `public1`). It reports 277 routines discovered, 95 attempted cases, 87 successful cases, 73 verified cases, 38 committed writes, 22 failed cases, and 202 blocked routines.

## Applied in this source pass

1. **Operation phase ordering:** routine execution is now ordered as INSERT-only/insert-bearing writers first, UPDATE next, DELETE-bearing routines after updates, GET-named read routines next, and remaining read/SELECT routines last. The existing planner's dependency order is preserved inside each phase. Routines combining DELETE with another operation are placed in the DELETE phase rather than being pulled forward.
2. **Temporal generation:** PostgreSQL `date` parameters now receive `LocalDate`; `timestamp without time zone` receives `LocalDateTime`; `timestamp with time zone` receives `OffsetDateTime`. Text parameters bound to date/time columns continue to use ISO-compatible semantic strings, subject to the routine's actual contract.
3. **Semantic flags:** removed the heuristic that treated every column containing `rider` as a Boolean. Numeric flags are only inferred from more explicit flag-like names; option/code columns without an established contract remain blocked rather than inventing business codes.
4. **Sex/gender text values:** one-character columns get `F`/`M`; wider text columns get `Female`/`Male`, subject to the real column length.
5. **Dynamic SQL:** routines whose source contains PL/pgSQL `EXECUTE` are blocked before test-input generation, with a clear reason and no generated inputs, as requested for unresolved dynamic SQL.
6. **Regression test:** added a test asserting the Java value classes used for PostgreSQL `date`, `timestamp`, and `timestamptz` parameters.

## Safety behavior intentionally retained

The global destructive-operation safety checks were **not removed**. Removing them would allow broad deletes/updates or operations against rows not created by this run. DELETE routines still require a target demonstrably owned by the current run, all required key columns, and bounded verification. Routines without a primary key/reliable row-level verification, unresolved `record` return contracts, transaction-control behavior, or unknown business codes can remain blocked. This is intentional; blocked routines must not be forced to execute just to raise the committed-write count.

## Identity behavior

For numeric identity/sequence-backed columns that a routine explicitly accepts as input, the generator continues to use the actual PostgreSQL sequence via `pg_get_serial_sequence`/`nextval`, checks the candidate against existing values, and does not reset the sequence. PostgreSQL identity columns are numeric; a `character(10)` column is not itself a PostgreSQL identity column. Character business keys should only be generated when the routine and table metadata establish that the key format is acceptable.

## Validation status

`bash mvnw test -q` was attempted in this environment but could not start Maven because DNS resolution for `repo.maven.apache.org` failed (`UnknownHostException`). The tests/build are therefore **not verified**. Run `.\mvnw.cmd test` and `.\mvnw.cmd package` on the user's Windows machine with Maven dependencies available.
