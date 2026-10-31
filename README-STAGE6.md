# GlossIRC v0.6 — outgoing DCC resume (experimental)

This incremental release adds handling of peer DCC RESUME requests for active outgoing classic DCC SEND offers. Matching is restricted to the server, peer nickname, filename and port; requested offsets must be inside the offered file. The sender replies with DCC ACCEPT and starts reading the selected Android document from the requested offset when the peer connects.

## Limitations

- This is an experimental source release, **not a verified APK**. Android SDK/Gradle build tools were unavailable here.
- Foreground services and background transfers are **not implemented** in this release. Connections/transfers are tied to the activity ViewModel lifetime and may be interrupted by Android background restrictions.
- Only classic active DCC is implemented; no reverse/passive DCC, TLS DCC, NAT traversal, checksum verification or reliable persistent resume after process death.
- Peer acknowledgement and full cross-client interoperability remain untested.
- The project currently lacks the Gradle wrapper JAR. Open the project with an Android Studio installation that can configure Gradle, or generate a wrapper with an installed Gradle distribution.

## Testing

Open the project in Android Studio, sync, compile, then test a DCC SEND with a reachable IPv4 address and a peer supporting DCC RESUME/ACCEPT. Do not rely on DCC for sensitive files until transfer integrity and peer identity are independently verified.
