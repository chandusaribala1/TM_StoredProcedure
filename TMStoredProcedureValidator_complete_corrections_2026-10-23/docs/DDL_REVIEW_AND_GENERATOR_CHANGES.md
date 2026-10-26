# Public1 DDL review and synthetic-data generator patch

Target from supplied run report: database `tm`, schema `public1`.
DDL reviewed: `public1_ddl(1).sql`.

## Code changes

- `TestCaseFactory` now infers a parameter-to-column binding only when the normalized parameter name matches exactly one write-target column (or the column ends in a sufficiently specific parameter suffix). Ambiguous mappings are not guessed.
- A routine parameter with a database default is no longer omitted when it maps to a `NOT NULL` target column. This addresses cases like `ASP_INSERT_PRODUCT` where `Prd_Sls_Rep_Id` is `NOT NULL` but the defaulted parameter was omitted.
- Numeric generation respects small `numeric(p)` precision, and semantic values are used for year, quarter, percent, age, flags, and counts when no CHECK constraint overrides the value.
- Text parameters mapped to numeric columns receive numeric text rather than arbitrary values such as `TEST45...`, addressing cast/input failures like `Asp_Insert_Batch_Print` when its application ID is cast to an integer.
- Text values are generated from column semantics and actual column length. `sex`/`gender` remain `F` or `M`; `bpchar(1)` flags may be one-character codes. For `bpchar(n)` where `n > 1`, generated values are not a one-character filler. Existing database widths are preserved.

## DDL-confirmed target constraints

- `TM_Product`: `Prd_ID` + `Prd_Typ` primary key; `Prd_Sls_Rep_Id`, `Prd_Lead_Src`, `Prd_Update_Dt`, `Prd_Update_Id` are NOT NULL. `Prd_Lead_ID` is identity-generated.
- `TM_Prospect`: `Pro_ID` primary key; `Pro_ID`, `Pro_HH_Num`, first/last name, address, city, state, ZIP, update ID/date are NOT NULL. `Pro_Num_ID` is identity-generated. `Pro_Sex` is `bpchar(1)`.
- `TM_Codes`: `Code_ID` is identity-generated; `Code_Typ` is `numeric(3)` and must not receive an arbitrary large integer.
- `TM_Letter_Print_Queue`: `LQ_Letter_ID` is identity-generated; `LQ_ID` and `LQ_Name` are NOT NULL; `LQ_App_ID` is integer.
- `TM_User_Alias`: composite primary key (`UA_Log_Id`, `UA_Alias`); `UA_Default` is `int2 NOT NULL`. The routine signature shown in the report accepts only ID and alias, so this Java patch cannot itself supply `UA_Default`; the SQL routine needs its own valid intended default/assignment.
- `TM_Campaign`: `Cam_Year` is `bpchar(4)`, `Cam_LOB` and `Cam_Type` are NOT NULL. Year generation uses four-digit values.
- `TM_Scheduled_Report_Users`: `SRU_E_Mail` is `varchar(100) NOT NULL`; email generation is semantic and email-shaped.

## Safety boundaries

This patch only generates input values and maps parameters. It does not insert directly into application tables, bypass procedures, disable constraints, change schema/table/column names, or alter data types. It cannot guarantee a row in a table whose routines are missing, blocked, destructive, have unresolved dependencies, or do not accept enough inputs to create a valid row. The validator must still verify each routine's actual effect and commit only verified writes.

The run report showed 22 committed writes but also 158 failed cases and 114 blocked routines. Re-run in the intended test database after installing this patch, then inspect the new JSON report. In particular, SQL routine fixes are still required for `ASP_INSERT_ALIAS` (`UA_Default` is absent from the routine inputs) and any PostgreSQL `RETURNS record` functions called without a column-definition list. This Java patch does not replace those SQL fixes.
