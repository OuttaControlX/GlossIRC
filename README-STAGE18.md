# GlossIRC v1.8 alpha

This complete project is based on the v1.7 consolidated source, not a patch archive.

Added: sound preferences (connect, private message, channel join, DCC send/receive/complete), Android document picker for custom sounds, sound testing; mIRC-like appearance palette for chat background, panels and accent; channel topic handling for 332 and TOPIC; editing/deleting global scripting variables; nickname tap actions in chat; scroll-to-latest control and reverse-layout scroll position preservation; crown/diamond role indicators based on known channel prefix and local IRC operator state; context-sensitive close action.

Limitations: DCC sound events are configurable in Settings but are not yet wired to all transfer state changes. Operator role detection is partial; it relies on NAMES prefixes and a local 381 response, not full IRCv3 account/WHO role tracking. Appearance options are an initial palette, not a complete mIRC colors dialog. Audio playback and behavior are untested on devices. The Android project has not been compiled in this environment; use GitHub Actions and share any Kotlin compiler errors.

IMPORTANT: Cloud debug APK signing keys may change between runs. Back up your saved profiles and scripts before uninstalling any installed version.
