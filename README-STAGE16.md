# GlossIRC v1.6 Alpha — IRC name matching and server tree

- Channel and private-message buffers are looked up using default RFC1459 IRC casemapping (including special bracket and caret equivalences), avoiding duplicate buffers with differently cased names.
- Channel nick lists also use casemapped lookup.
- Active servers now display in a collapsible navigation tree, with status, channels and private messages as children. Tap triangle to expand/collapse.
- Saved server list persists host, port, TLS, preferred nickname, ident/username and auto-connect. Existing saved profiles are migrated with ident `glossirc`.
- Connecting from the server form saves the profile; disconnecting removes the live session without deleting the saved profile.
- Ident is sent as the IRC USER command's username. This is not an identd server and does not change a server-assigned vhost.

Note: IRC networks can negotiate different CASEMAPPING settings (ascii, strict-rfc1459). This version uses RFC1459 default for buffer matching; dynamic CASEMAPPING negotiation remains a future improvement.

Build via GitHub Actions. This source package was not compiled or tested on a device in this environment.
