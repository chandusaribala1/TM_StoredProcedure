# Case-resolution and write-replay patch

## Changes

1. Adds `CaseCompatibilityViewService` before startup validation. For mixed-case base tables in the configured schema, it creates a lowercase compatibility view only when:
   - the lowercase name is not already occupied;
   - exactly one relation has that case-insensitive name; and
   - a routine source in the configured schema mentions the relation name.
2. Compatibility views use `security_invoker = true` and project actual columns under lowercase names. They point to the existing base table; they do not create/copy a business table or change its columns. DML through an automatically-updatable view affects the underlying table, as the stored procedure intended.
3. Compatibility views are marked `TM_VALIDATOR_CASE_ALIAS:`. `TableMetadataService` excludes marked aliases from metadata resolution so primary keys, constraints, foreign keys, triggers, and row snapshots are taken from the actual base relation, not from the view.
4. Adds `validator.allow-write-replay`. The included `application.yml` sets it to `true` for the user's disposable test database so prior report history does not automatically skip every previously invoked write. Set it to `false` before connecting to any database whose data must be preserved. Existing transaction verification and safety checks remain in place.
5. The existing exact-first, unique case-insensitive metadata lookup remains enabled.

## Important limits

- This does not create missing business tables such as `TM_POSSIBLE_DUPES`, `TM_PROSPECTDUPES`, `TM_PROSPECTHOPPER`, or rate-calculation/temp objects when no uniquely matching base table exists. Those need to be restored from the migration source or the corresponding routine must be reviewed.
- Compatibility views require PostgreSQL 15+ for `security_invoker`. The service logs and skips alias creation on older versions.
- A routine that uses quoted mixed-case column names may need a routine-specific correction if its source does not match the view's lowercase column aliases.
- The patch preserves the primary-key verification and safe-write checks. It does not force commits for unverified writes.
- Maven tests could not be run in this environment because Maven Wrapper could not download Maven from `repo.maven.apache.org` (network/DNS unavailable). Run `mvnw.cmd test` locally before treating this as production-ready.
