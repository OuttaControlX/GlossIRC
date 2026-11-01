# GlossIRC v1.3 alpha — compatibility improvements

Changes:
- Event context now carries server and network fields for future wiring; `$server` and `$network` expand when those fields are supplied. Existing event callers still use empty defaults.
- Nested `$upper()`, `$lower()`, `$len()` identifiers are evaluated from the innermost call outward (maximum depth 16).
- `$left(text,N)` and `$right(text,N)` added.
- Script `halt` and `return` stop the current execution block.
- Version bumped to 1.3.0-alpha.

Limitations: Android compilation and runtime tests have not been completed. Full mIRC syntax, popup handling, DCC and background reliability remain incomplete. This source is not a verified APK.
