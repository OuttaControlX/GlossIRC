# GlossIRC v1.4 alpha — stability patch

Based on the v1.3 alpha source tree.

## Changes
- Move saved-profile auto-connect until after script engine, DCC manager, and server collections initialize. Prevents initialization-order failure.
- Accept three-field JOIN/PART/CONNECT event declarations such as `on *:JOIN:#:{ ... }`, while retaining four-field TEXT/ACTION/NOTICE handlers.
- Catch and log foreground-service startup failures so an Android service restriction does not crash the UI immediately. This is not a substitute for robust background lifecycle management.
- Bump app version to 1.4.0-alpha.

## Verification
- Python static assertions and ZIP CRC validation only. Android SDK/Gradle were unavailable in the execution environment; no APK build or device test has been performed.
- DCC remains experimental; full mIRC compatibility is not implemented.

## Suggested manual smoke test
1. Import project in Android Studio and sync Gradle.
2. Build debug APK and review compiler diagnostics.
3. Save an auto-connect profile, restart, verify the app does not crash.
4. Enable `on *:JOIN:#:{ echo -a joined }` and join a channel.
5. Test multi-server, nicklists, message formatting and DCC against a controlled IRC server.
