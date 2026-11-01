# GlossIRC v0.5 — DCC resume prototype

Added a **Resume** option for incoming DCC SEND offers. Choose an existing partial document with Android's file picker. The app checks its length, sends a DCC RESUME request, waits for a matching DCC ACCEPT from the same IRC server and nickname (filename, port, offset and token must also match), then connects and appends data at the accepted offset. The receiver sends 32-bit DCC acknowledgements. Normal Receive still creates a new document.

## Important limitations
- **Not device-tested or compiled**. Resume is experimental and requires a peer supporting classic DCC RESUME/ACCEPT. No persistent resume state survives app termination, and there is no Android foreground service yet.
- The sender implementation does not yet handle incoming DCC RESUME requests. Thus outgoing GlossIRC transfers cannot yet be resumed by peers.
- The original project has no Gradle wrapper JAR. Android Studio must provide Gradle or generate the wrapper.
- The app's transfer jobs belong to the ViewModel, so process death stops them. Android background restrictions remain unaddressed.
- No hash verification, passive DCC, NAT traversal, or encrypted DCC. Only accept offers from trusted peers.
