# GlossIRC v0.2 — scripting foundation

Adds a locally persisted, enable/disable-able `.mrc` script library and an in-app editor (Code icon in top bar).

Implemented limited mIRC-like syntax:
- `alias name { ... }` and `/name arguments`, `$1`, `$2`, `$3`, `$*`
- `/set %variable value`, `/unset %variable`, variable expansion
- `on *:TEXT:pattern:#:{ ... }` and ACTION/NOTICE/JOIN/PART/CONNECT event forms
- `$nick`, `$chan`, `$me`, simple wildcard matching, `msg`, `notice`, `echo`, `set`, `unset`, `join`, `part`, `nick`, `me`
- Scripts saved to Android SharedPreferences and reloaded on app startup

Limitations: this is NOT a full mIRC interpreter. No nested blocks, `if`/`while`, arbitrary identifiers, timers, popups, external `.mrc` file import/export, or DCC yet. Event handler syntax is deliberately simplified. Editor is plain text (not syntax-highlighted). Scripts run in the UI event handler and should be kept small. Network connections still live in the Activity ViewModel.

## Build
Open GlossIRC in Android Studio with Android SDK 35 and JDK 17. Gradle 8.9 is required; the wrapper JAR is not bundled. This source was not compiled in this environment.
