# GlossIRC v0.8 — connection recovery and saved profiles

- Saved server profiles (host, port, TLS, nickname, auto-connect) with connect, toggle, and remove controls in the drawer.
- Auto-connect profiles are restored when the shared model is recreated.
- Reconnect with exponential backoff (2–60 seconds), stopping after explicit disconnect.
- Android 13+ notification permission request.
- Foreground service START_STICKY restart hint.

Limitations: not compiled or device-tested. Android background execution may still prevent restart. No SASL credentials saved. Scripting and DCC remain experimental.
