# 🔀 Patcher changes by version

Patches built for an older Morphe Patcher keep the old behavior, decided by the `Patcher-Version`
attribute of the patch bundle manifest. A warning is logged when an older bundle gets a newer behavior.

## 2.0.0

### 🗂️ Resources are decoded on request

Only the manifest is decoded up front. Other files are decoded when read with `get()` or `document()`.
Applies only if every bundle is built for 2.0.0 or newer.

**Migrate:** before listing or walking a resource directory, decode what you need:

```kt
// Before: get("res").listFiles { ... }
decode("res/values*/strings.xml").forEach { path -> document(path).use { /* ... */ } }
```

Use `listDecodableFiles()` to see which files exist without decoding them. See [Advanced APIs](4_apis.md).

### 🔎 Fingerprint strings must match exactly

Raw strings in `strings = listOf("foo")` must now equal a string of the method, instead of only being
contained in one. Applies if any bundle is built for 2.0.0 or newer, or has no declared version.

**Migrate:** declare the entire string, or use a string filter with the comparison you need:

```kt
strings = listOf(
    string("player_", StringComparisonType.STARTS_WITH),
    string("state", StringComparisonType.CONTAINS),
)
```

String filters such as `string("foo")` are not affected.

### 🔁 Earlier filters are retried when a later filter cannot match

If a fingerprint filter cannot match, the filter before it is now retried with its next matching instruction,
then the filter before that one, and so on back to the first filter. Before, only the first filter was retried,
and not at all if it used `MatchAfterLast()`. `MatchAfterLast()` and `MatchBeforeFirst()` are now retried too,
instead of only using the last or first match. Applies if any bundle is built for 2.0.0 or newer, or has no declared version.

Retrying only changes what a fingerprint matches if it uses `MatchAfterImmediately()`, `MatchAfterWithin(int)`,
`MatchAfterLast()`, a `MatchBefore` location, a custom location, or `OpcodesFilter.opcodesToFilters()`.
Fingerprints that only use `MatchAfterAnywhere()` and `MatchFirst()` match the same instructions as before.

For example, an `invoke-static` without a `move-result-object` after it no longer stops this fingerprint
from matching a later `invoke-static` that has one, because the `invoke-static` filter is retried:

```kt
filters = listOf(
    literal(2.0),
    opcode(Opcode.INVOKE_STATIC),
    opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
)
```

**Migrate:** fingerprints using the locations above can now match methods they did not match before, and the
first matching method is used, so check they still match the intended method and instruction indexes.
An older bundle is only named in the warning if its bytecode uses one of them.

### ✨ New fingerprint features

- `isStatic` matches only static or only non-static methods, ignoring all other access flags.
- `superclass` and `interfaces` match the class the method is declared in.
- Instruction locations can match before the previous filter, or the last match after it:
  `MatchAfterLast()`, `MatchBeforeAnywhere()`, `MatchBeforeImmediately()`, `MatchBeforeWithin(int)`
  and `MatchBeforeFirst()`.

See [Fingerprinting](2_2_1_fingerprinting.md) for details.

## 1.16.0

### ↩️ `returnEarly()` and `returnLate()`

`MutableMethod.returnEarly(value)` makes a method return a constant before any of its code runs.
`returnLate(value)` runs the original code, but makes every return instruction return the value instead.
Both accept primitives, strings and `null`, and `returnEarly()` without a value skips a method that returns void.

```kt
IsPremiumFingerprint.method.returnEarly(true)
ShouldShowAdsFingerprint.method.returnLate(false)
```

### 🧮 Free registers

`Method.findFreeRegister(index, excludedRegisters...)` finds a register that is safe to overwrite at an index,
following every branch from it, and `getFreeRegisterProvider(index, count, excludedRegisters...)` finds several.
If no register is free, `cloneParameters()` and `addFreeRegisters(Int)` add registers to the method instead.

```kt
val freeRegister = method.findFreeRegister(insertIndex, viewRegister)
```

See [Advanced APIs](4_apis.md) for details.
