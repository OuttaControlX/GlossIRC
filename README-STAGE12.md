# GlossIRC v1.2 Alpha — scripting compatibility increment

Changes over v1.1:
- `elseif` sibling branches are parsed as a chain and only the first matching branch executes.
- Positional alias arguments `$0` (count) and `$1` through `$9`.
- Simple `$upper(text)`, `$lower(text)`, `$len(text)` identifiers.
- Previous features and limitations remain unchanged.

Example:
```mirc
alias demo {
  var %x 2
  if (%x == 1) { echo one }
  elseif (%x == 2) { echo two }
  else { echo other }
  echo $upper(hello) $len(world)
}
```

## Verification
Source was inspected and archive tested. This environment lacks the Android SDK and Gradle, so the APK was not compiled and runtime behavior remains unverified. This is not full mIRC compatibility.
