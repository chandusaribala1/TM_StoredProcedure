# Compilation fix — 2026-10-23

Fixed the Java compile error caused by capturing a reassigned `binding` local in a lambda in `TestCaseFactory.generate(...)`.

The code now stores the resolved table name in the effectively-final local `boundTableName` before the stream predicate uses it. This is a compile-only correction and does not weaken row ownership or primary-key safety checks.

Build status: not verified in this packaging step. Run `mvnw.cmd test` and `mvnw.cmd package` on Windows with Maven dependencies available.
