# GlossIRC v1.7 alpha — Channel links and join confirmation

- Channel names in chat are tappable and prompt for confirmation before sending JOIN.
- Channel browser selections also prompt for confirmation.
- JOIN commands no longer activate a channel immediately; only the server echo of the current user JOIN opens it.
- Common numeric join failures are reported in status and clear pending joins.
- Joined channel tracking is distinct from merely existing chat buffers.

This is an uncompiled source update. Use GitHub Actions to compile and test.
