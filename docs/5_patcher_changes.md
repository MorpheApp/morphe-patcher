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

### ✨ New fingerprint features

- `isStatic` matches only static or only non-static methods, ignoring all other access flags.
- `superclass` and `interfaces` match the class the method is declared in.
- Instruction locations can match before the previous filter, or the last match after it:
  `MatchAfterLast()`, `MatchBeforeAnywhere()`, `MatchBeforeImmediately()`, `MatchBeforeWithin(int)`
  and `MatchBeforeFirst()`.

See [Fingerprinting](2_2_1_fingerprinting.md) for details.
