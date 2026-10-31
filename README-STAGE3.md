# GlossIRC v0.3 — scripting improvements

This is a source-only development snapshot, not a verified APK.

Added:
- Android Storage Access Framework `.mrc` import/export in the script editor.
- Simple one-line `if (left == right) command` conditions (also `!=`, `<`, `>`).
- Bounded `/timer COUNT SECONDS command` within script bodies.
- Basic `menu nicklist { Label:command }` popup parsing and nicklist long-press menu entries.
- Removed duplicated v0.2 editor and obsolete second script engine references.

Limitations: nested blocks, full mIRC expressions, `$read`/INI, robust timers, channel/status popups, DCC, and service-based background persistence are not implemented. Popup commands are currently submitted through the chat command handler. Scripts are not sandboxed against IRC-side effects; only import scripts you trust.

Import a `.mrc` file using Scripts > Import, then Save / Load. Export writes the current editor text.

Build using Android Studio with JDK 17, Android SDK 35 and Gradle plugin 8.7.3. No Gradle wrapper JAR is included; Android Studio can use a locally installed Gradle distribution.
