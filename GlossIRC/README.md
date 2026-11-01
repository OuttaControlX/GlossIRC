# GlossIRC v0.1.0 — Android IRC Client

Native Kotlin + Jetpack Compose IRC client starter project.

## Open and build
1. Open this folder in Android Studio (Ladybug 2024.2.1 or later).
2. Install Android SDK Platform 35 and JDK 17.
3. Allow Gradle sync to download dependencies.
4. Select the `app` configuration and Run on an Android 8.0+ device.

The ZIP does **not** contain a Gradle wrapper binary or precompiled APK. Android Studio can use its configured Gradle installation; alternatively run `gradle wrapper --gradle-version 8.9` with Gradle installed, then `./gradlew assembleDebug`.

## Working scope (v0.1)
- Separate TCP/TLS socket for each server; multiple servers concurrently
- Server/channel/private message buffers and switching
- IRC PING/PONG, NICK/USER registration, JOIN, PART, QUIT, PRIVMSG, NOTICE, NICK, NAMES replies
- Commands: /join, /j, /part, /nick, /msg, /query (send message), /me, /notice, /raw, /quote, /quit
- mIRC foreground color codes 0–15, bold, italic, underline and reset
- Glossy dark Compose UI with drawer and nick list

## Limitations and next milestones
- Connections live in an Activity-scoped ViewModel: **not** a foreground service; Android may disconnect in background.
- No SASL, NickServ workflow, IRCv3 negotiation, automatic reconnect, persistent settings, logging or script engine yet.
- DCC send/receive/resume is **not implemented**.
- No built-in script editor, mIRC aliases/variables/events, custom popups yet.
- mIRC color backgrounds and extended hex colors not yet rendered.
- Incoming IRC line length and flood controls need production hardening; raw commands should be used only on trusted networks.
- This source is untested in an Android SDK build environment. Resolve any SDK/Gradle version mismatch during sync.

## Suggested next modules
`script/`: tokenizer, AST, variable scope, event dispatch, alias registry, script repository and editor.
`dcc/`: CTCP DCC negotiation, transfer sockets, resume offset negotiation, Android document picker and persistent transfer state.
`service/`: foreground connection service, notifications, reconnect policies.

## v0.2 scripting stage
- In-app script editor (code icon), save/edit/delete/enable/disable scripts; persisted in private SharedPreferences.
- Supported mIRC-like subset: `alias name { ... }`, `on *:TEXT:pattern:target:{ ... }`, `on *:JOIN:*:#channel:{ ... }`, PART and NOTICE events, `$nick`, `$chan`, `$me`, `$1`, `$2`, `%variables`, `/set`, `/load`, `/unload`, `msg`, `notice`, `echo`, `inc`.
- Syntax is intentionally limited. Not supported: arbitrary mIRC expressions, nested blocks/conditionals, regex matches, sockets, DLLs, timers, `on START`, all other mIRC scripting APIs. Script event handlers run on the main dispatcher; avoid large scripts. Do not load untrusted scripts.
- The editor currently saves local scripts only; Android document-picker `.mrc` import/export is a future milestone.
- DCC send/receive/resume remains unimplemented.
