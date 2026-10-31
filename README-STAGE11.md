# GlossIRC v1.1 alpha — scripting iteration

Based on v1.0 alpha. This revision adds `/var %local value` scoped to an alias/event invocation, local variable expansion, local `inc`/`dec`, and `elseif` branch handling for the supported multiline syntax. Timers receive a snapshot of local variables. Nested alias calls get their own local scope.

Example:
```
alias classify {
  var %score 5
  if (%score > 10) {
    echo high
  }
  elseif (%score == 5) {
    echo medium
  }
  else {
    echo low
  }
}
```

Important: this is a limited mIRC-inspired interpreter, not full mIRC compatibility. Nested conditions, popup syntax and event semantics still need broader conformance tests. Gradle wrapper and Android SDK compilation are not available in this environment; project build and device operation are **unverified**.
