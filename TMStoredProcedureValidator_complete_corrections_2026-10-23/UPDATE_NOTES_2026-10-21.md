# Validator code update — 2026-10-21

This package is based on `TMStoredProcedureValidator_case_resolution_updated(1).zip` and overlays the Java source files shared in the 2026-10-21 conversation where matching project classes were present.

## Changes in this update

1. `TestCaseFactory.java`
   - Reduced generic synthetic integer, bigint, smallint, floating-point, and unconstrained numeric magnitudes to conservative ranges. The previous generator frequently produced near-maximum integer values and very large numeric amounts, which can cause irrelevant overflow/business-rule failures.
   - Existing metadata-based foreign-key handling, identity/sequence protections, CHECK-constraint handling, and target column length limits remain in place.

2. `RunCoordinator.java`
   - Failed result-contract verification now records expected and actual result column names/types in the case diagnostic instead of only saying the shapes did not match. This should make OUT/INOUT and function-return mismatches easier to diagnose.

3. Source synchronization
   - Matching Java classes were updated from the latest source attachments shared in the conversation, including `TableMetadataService.java`, `RoutineRepository.java`, `RoutineInvoker.java`, `RowSnapshotService.java`, report classes, and run coordinator/runner classes.

## Important limitations

- This update does not create missing tables, rewrite stored-procedure business logic, rename identifiers, or weaken safety blocks.
- Missing relations such as `TM_ProspectDupes`, `TM_POSSIBLE_DUPES`, and `TM_RateRiskFactors` must be confirmed against the intended database DDL/source inventory. Do not create placeholder tables merely to make tests pass.
- Dynamic SQL, temporary-table lifecycle, `DROP`, sequence-state changes, and writes that cannot be bounded to current-run rows remain blocked by policy.
- Maven tests could not be run in this environment because Maven Wrapper attempted to download Maven from `repo.maven.apache.org`, but network/DNS access was unavailable. Treat this package as a source patch requiring `mvnw.cmd clean test` locally before running it against the database.
