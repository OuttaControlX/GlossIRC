# GlossIRC v1.0 alpha — scripting control flow

Built on the v0.9 source project.

## Changes
- Balanced-brace parsing for alias and event definitions with nested blocks.
- Multi-line if / else and while blocks, plus single-line conditions.
- Comparisons ==, !=, <, >, <=, >=, and iswm.
- Raw IRC command support in scripts.
- Execution limits: maximum 12 nested calls, 100 while iterations, 500 statements per call.
- Existing saved scripts, server profiles, DCC implementation and editor retained.

## Sample
```mirc
alias countup {
  set %count 0
  while (%count < 3) {
    inc %count
    echo Counter: %count
  }
  if (%count == 3) {
    echo Finished
  }
  else {
    echo Unexpected result
  }
}
```

## Limitations
This is a partial mIRC scripting interpreter, not complete mIRC compatibility.
Android SDK/Gradle build and device testing have not been performed.
Some mIRC constructs (elseif, switch, local variables, advanced identifiers, sockets) remain unsupported.
The scripting engine runs on the caller thread; avoid heavy scripts.
