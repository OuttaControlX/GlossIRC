# GlossIRC v0.7 — Foreground IRC service

- Starts an Android `remoteMessaging` foreground service when connecting to a server.
- The IRC view model is application-scoped rather than activity-scoped. Its coroutine scope and DCC transfers can continue while the activity is destroyed, provided the process remains alive.
- Persistent notification opens the client or offers **Disconnect all**.
- Service is not sticky and does **not** restore connections or DCC transfers after process death/reboot.
- Android may impose restrictions; users should open the app to initiate connections. Notification permission should be requested in the UI on Android 13+ for notification visibility; foreground service launch still follows OS rules.
- Do not mistake this for fully verified background reliability. No emulator/device compilation or integration testing has been performed.

## Next priorities
- Implement runtime connection status in notification, automatic reconnect, persisted server profiles, notification permission prompt, and transfer resume after restart.
- Expand mIRC scripting compatibility.
