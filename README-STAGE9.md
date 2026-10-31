# GlossIRC v0.9 — scripting persistence and commands

This stage extends the existing limited mIRC-like interpreter.

## Changes
- Script `%variables` now persist in SharedPreferences across app restarts.
- `/inc %counter [step]` and `/dec %counter [step]` work inside aliases and event handlers.
- `$time` and `$date` identifiers are expanded in script commands.
- Event parser no longer accidentally requires a trailing space after the closing brace.
- Non-text JOIN/PART/CONNECT events no longer require their pattern to match an empty text payload.
- Project version metadata updated to 0.9.0.

## Example
```mirc
alias counter {
  inc %counter
  echo Count: %counter
}
on *:TEXT:!hello:#:{
  msg $chan Hello $nick, the time is $time
}
```

## Known limits
- Not full mIRC scripting: nested blocks, while loops, complex expressions, custom identifiers and broad popup support remain incomplete.
- Timer execution is tied to the in-process main looper; it is not durable across process death.
- No APK build or device test was performed here. Gradle wrapper is still missing.
- Do not enable scripts from untrusted sources.
