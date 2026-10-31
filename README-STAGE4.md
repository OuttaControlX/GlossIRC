# GlossIRC v0.4 — experimental DCC

Adds an incoming classic DCC SEND offer parser and user-initiated receiving via Android CreateDocument, plus classic active DCC sending from Android OpenDocument with an explicitly configured reachable public IPv4 address. Transfers show byte counts, states and cancellation. Incoming offers are **never** auto-accepted. File data is saved only to a user-selected URI.

## Important limitations
- **DCC resume is not yet functional in the UI.** The receive core contains an initial RESUME request path, but ACCEPT negotiation, persisted partial-file handling and continued transfer after ACCEPT are not yet implemented. Do not rely on resuming interrupted downloads.
- No reverse/passive DCC, UPnP, NAT traversal, IPv6, integrity hash, or background foreground-service transfer persistence.
- Active send requires the advertised IPv4 to be reachable and the listening TCP port forwarded. On mobile carrier NAT it will often fail.
- This project has not been compiled or tested on an Android device in this environment.
- The project has no Gradle wrapper JAR. Open with Android Studio and use its Gradle installation / generate a wrapper as necessary.

## Safety
DCC is a direct peer-to-peer connection and exposes the participating IP addresses. Accept transfers only from trusted peers and inspect received files before opening them. The app does not execute received files.
