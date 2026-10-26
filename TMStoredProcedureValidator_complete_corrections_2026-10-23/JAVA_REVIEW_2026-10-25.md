# Java review - 25 October 2026 (targeted error handling update)

See `TARGETED_FAILURE_FIXES_2026-10-25.md` for the complete mapping of the requested failures to code changes and database-side follow-up.

The generator now applies target-column-aware ISO date/time formatting, Boolean 0/1 values for integer-typed inputs mapped to Boolean columns, target-length bounds for text, and up to 100 collision checks for mapped single-column unique keys. The run report now gives routine-specific remediation for `ASP_INSERT_APPLICATION`, `ASP_INSERT_ALIAS`, `ASP_INSERT_PRODUCT`, `Asp_Add_Lead_Source`, HIP timestamp failures, `Asp_Add_User` length errors, and `ASP_GET_REF_CODES` missing relation errors. Existing `42702` ambiguous-column and anonymous `SETOF record` contract guards remain in place.

Important: this is not a SQL routine migration or schema auto-repair tool. It cannot safely invent a `SETOF record` output schema, business default for `UA_Default`, or missing `TM_Lead_Source_Permission` table. Those require the source routine contract or original DDL. SQL routine bodies may still need explicit casts or qualified column references.

Build status: not verified in this environment. `mvn` is not installed and `sh mvnw -q test` failed because the environment could not resolve `repo.maven.apache.org`. Run `mvnw.cmd test` on Windows with Maven Central reachable before using the ZIP against the test database.
