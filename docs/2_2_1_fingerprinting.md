# 🔎 Fingerprinting

In the context of Morphe, a fingerprint is a partial description of a method.
It is used to uniquely match a method by its characteristics.
Fingerprinting is used to match methods with a limited amount of known information.
Methods with obfuscated names that change with each update are primary candidates for fingerprinting.
The goal of fingerprinting is to uniquely identify a method by capturing various attributes, such as the return type,
access flags, instructions, strings, and more. Fingerprints are declared with varying amounts of information,
and the fingerprint matches a method only if _all_ the declared information matches.

## ⛳️ Example fingerprint

```kt
// Declaring fingerprints as classes is not required, but if a fingerprint fails
// to match then the exception stack trace will include the fingerprint name.
object AdLoaderFingerprint : Fingerprint(
    // Defining class is matched using StringComparisonType semantics (see class for details).
    definingClass = "Lcom/some/app/ads/AdsLoader;",
    // Exact access flags. Visibility, final and synthetic flags can change
    // between app versions, so if only static matters then instead use
    // `isStatic = true` or `isStatic = false`. Only one of the two can be declared.
    // With `isStatic`, constructors are matched using `name = "<init>"` or `name = "<clinit>"`.
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    // Return type is matched using StringComparisonType semantics.
    returnType = "Z",
    // Declared parameters are matched using StringComparisonType semantics.
    // Non obfuscated classes are best declared using the full class name.
    // While obfuscated class names must be declared only using the object type
    // Since obfuscated names change between releases.
    // Last parameter is simply `L` since it's an obfuscated class object.
    parameters = listOf("Ljava/lang/String;", "I", "L"),

    // Instruction filters.
    filters = listOf(
        // Filter 1.
        fieldAccess(
            // Restrict to a static object field get operation.
            opcode = Opcode.SGET_OBJECT,
            // "this" refers to the class the method was declared in.
            // It does not include superclasses or subclasses.
            definingClass = "this",
            type = "Ljava/util/Map;"
        ),

        // Filter 2.
        string("showBannerAds"),

        // Filter 3.
        methodCall(
            definingClass = "Ljava/lang/String;",
            name = "equals",
        ),

        // Filter 4.
        // MatchAfterImmediately() means this must match immediately after the last filter.
        opcode(Opcode.MOVE_RESULT, InstructionLocation.MatchAfterImmediately()),

        // Filter 5.
        literal(1337),

        // Filter 6.
        opcode(Opcode.IF_EQ)
    )
)
```

### 🔤 Type declarations

Types such as `definingClass`, `returnType`, `parameters`, `superclass` and `interfaces`, and the types
declared in filters such as `methodCall`, `fieldAccess`, `newInstance` and `checkCast`, are written in
the [type descriptor](https://source.android.com/docs/core/runtime/dex-format#typedescriptor) format.
How a declared type is compared depends on how it is written:

| Declaration                                       | Comparison    | Example                 | Matches                                        |
|---------------------------------------------------|---------------|-------------------------|------------------------------------------------|
| A primitive type (`B`, `C`, `D`, `F`, `I`, `J`, `S`, `V`, `Z`) | Equals | `"I"`               | Only `int`                                     |
| Starts with `L` and ends with `;`                 | Equals        | `"Ljava/lang/String;"`  | Only `String`                                  |
| Starts with `L`                                   | Starts with   | `"L"`                   | Any object type, such as an obfuscated class   |
|                                                   |               | `"Lcom/some/app/"`      | Any class in the `com.some.app` package        |
| Starts with `[` and ends with `;`                 | Equals        | `"[Ljava/lang/String;"` | Only `String[]`                                |
| Starts with `[`                                   | Starts with   | `"["`                   | Any array                                      |
| Ends with `;`                                     | Ends with     | `"/String;"`            | Any class named `String`, in any package       |
| Anything else                                     | Contains      | `"Ads"`                 | Any type containing `Ads`                      |

Obfuscated classes are best declared as `"L"`, because their names change between app versions.
Declared `parameters` must have the same number of parameters as the method,
and each parameter is compared using the rules above.

The `definingClass` of `methodCall` and `fieldAccess` filters also accepts `"this"`, which matches the
class the fingerprinted method is declared in. Superclasses and subclasses of that class do not match.

The rules are implemented by `StringComparisonType`, which can also be passed explicitly to `string()` filters.

### 🧪 Custom conditions

If a method cannot be described with the other parameters, `custom` declares a condition that must also
be true. It is called with the method and its class, after all other declared information matched:

```kt
object LargeOnClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    custom = { method, classDef ->
        // Only methods of a class with exactly 7 fields, and with at least 40 instructions.
        classDef.fields.count() == 7 && (method.implementation?.instructions?.count() ?: 0) >= 40
    }
)
```

Prefer instruction filters when possible, because a custom condition is checked for every method that
otherwise matches, and it cannot be used to speed up matching.

### 🧱 Static or instance methods

`accessFlags` must match exactly, but most access flags are not stable between app versions.
Visibility can be widened or narrowed by R8/ProGuard, `final` can change when a subclass is added or removed,
and synthetic flags depend on how the code is compiled.
If the only access flag that matters is whether the method is static, then declare `isStatic` instead:

```kt
object ChannelTabBuilderFingerprint : Fingerprint(
    // Matches only non-static methods. All other access flags are ignored.
    isStatic = false,
    returnType = "Landroid/view/View;",
    parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;", "Z", "L", "Z")
)
```

A fingerprint can declare either `accessFlags` or `isStatic`, but not both.

Because `AccessFlags.CONSTRUCTOR` cannot be declared together with `isStatic`, match constructors by name instead.
Use `name = "<init>"` for a constructor or `name = "<clinit>"` for a static initializer.
Constructors are never static and static initializers are always static, so these names do not need `isStatic`:

```kt
object AdLoaderConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/some/app/ads/AdsLoader;",
    name = "<init>",
    parameters = listOf("Ljava/lang/String;")
)
```

### 🧬 Superclass and interfaces

`superclass` and `interfaces` match the class the method is declared in.
`superclass` is the direct superclass, and `interfaces` are interfaces the class directly implements.
Both are matched using `StringComparisonType` semantics, the same as `definingClass`.
All declared interfaces must be implemented, in any order, and the class can implement other interfaces too.
Superclasses and interfaces of superclasses are not checked.

```kt
object VideoPlayerImplFingerprint : Fingerprint(
    // Exact comparison. The class can also implement other interfaces.
    interfaces = listOf("Lcom/some/app/player/VideoPlayer;"),
    strings = listOf("VideoPlayerImpl")
)

object NetworkRequestFingerprint : Fingerprint(
    // Starts with comparison. Matches any superclass in this package.
    superclass = "Lcom/some/app/network/",
    returnType = "Lcom/some/app/network/RequestTask;",
    parameters = listOf(
        "Lcom/some/app/network/HttpRequest;",
        "Lcom/some/app/network/RequestCallbacks;"
    )
)
```

### 📦 Resource ids as literals

Apps refer to their resources by id, and those ids differ per build. `resourceLiteral(type, name)`
matches an instruction loading the id of a named resource of the APK being patched, looked up
from its resource table when the fingerprint is first matched:

```kt
filters = listOf(
    resourceLiteral(ResourceType.LAYOUT, "account_compact_link"),
    opcode(Opcode.MOVE_RESULT_OBJECT, InstructionLocation.MatchAfterWithin(5)),
)
```

Pass `exceptionIfResourceNotFound = false` for a resource that only some app versions have,
typically inside `anyInstruction(...)`; the filter then never matches instead of failing. The
same lookup is available directly with `resourceId(type, name)` and `hasResourceId(type, name)`.

## 🔎 Example target app in Java and Smali

```java
package com.some.app.ads;

class AdsLoader {
  private final static Map<String, String> m = new HashMap<>();

  // Method to fingerprint.
  public final boolean obfuscatedMethod(String parameter1, int parameter2, ObfuscatedClass parameter3) {
    // Filter 1 target instruction.
    String string = m.get(parameter1);

    unrelatedMethod(string);

    // Filter 2, 3, 4 target instructions, and the instructions to modify.
    if ("showBannerAds".equals(string)) {
      showBannerAds();
    }

    // Filter 5 and 6 target instructions.
    return parameter2 != 1337;
  }

  private void showBannerAds() {
    // ...
  }

  private void unrelatedMethod(String parameter) {
    // ...
  }
}
```

```asm
# Method to fingerprint.
.method public final obfuscatedMethod(Ljava/lang/String;ILObfuscatedClass;)Z
    .registers 5

    # Filter 1 target instruction.
    sget-object v0, Lcom/some/app/ads/AdsLoader;->a:Ljava/util/Map;

    invoke-interface {v0, p1}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object p1

    check-cast p1, Ljava/lang/String;

    invoke-direct {p0, p1}, Lcom/some/app/ads/AdsLoader;->unrelatedMethod(Ljava/lang/String;)V

    # Filter 2 target instruction.
    const-string v0, "showBannerAds"

    # Filter 3 target instruction.
    invoke-virtual {v0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    # Filter 4 target instruction.
    move-result p1

    if-eqz p1, :cond_16

    invoke-direct {p0}, Lcom/some/app/ads/AdsLoader;->showBannerAds()V

    # Filter 5 target instruction.
    :cond_16
    const/16 p1, 0x539

    # Filter 6 target instruction.
    if-eq p2, p1, :cond_1c

    const/4 p1, 0x1

    goto :goto_1d

    :cond_1c
    const/4 p1, 0x0

    :goto_1d
    return p1
.end method
```

Notice the fingerprint filters do not declare every instruction in the target method, and between
each filter, zero or more other instructions can exist. Unless a filter matches before the previous filter (see below),
instruction filters must be declared in the same order as the instructions appear in the target method.

## 🧰 Instruction filters

These filters are available. Every filter also takes a `location` parameter, described in the next section.

| Filter                                  | Matches                                                                                        |
|-----------------------------------------|------------------------------------------------------------------------------------------------|
| `opcode(Opcode)`                        | A single opcode.                                                                               |
| `string(value)`                         | A `const-string` instruction. Compared using `EQUALS` unless a `StringComparisonType` is passed. |
| `methodCall(...)`                       | A method call, by any of `definingClass`, `name`, `parameters`, `returnType` and `opcode(s)`.  |
| `fieldAccess(...)`                      | A field get or put, by any of `definingClass`, `name`, `type` and `opcode(s)`.                 |
| `literal(value)`                        | A `const` instruction loading the number. Accepts `Int`, `Long`, `Float`, `Double` and `() -> Long?`. |
| `resourceLiteral(type, name)`           | A `const` instruction loading the id of a resource of the APK being patched.                    |
| `newInstance(type)`                     | A `new-instance` instruction of the type.                                                      |
| `checkCast(type)`                       | A `check-cast` instruction to the type.                                                        |
| `instanceOf(type)`                      | An `instance-of` instruction for the type.                                                     |
| `anyInstruction(filter, ...)`           | Any one of the declared filters.                                                               |

`methodCall` and `fieldAccess` can also be declared with a `MethodReference` or `FieldReference`,
or with a copy-pasted un-obfuscated smali statement:

```kt
methodCall(smali = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;")
fieldAccess(smali = "Landroid/os/Build;->MODEL:Ljava/lang/String;")
```

If a single instruction varies slightly between different app targets but otherwise the fingerprint
is still the same, the `anyInstruction()` filter can be used to specify the different expected
instruction. Only the location of `anyInstruction` is used, and the locations of the filters inside it are ignored:

```kt
anyInstruction(
    string("string in early app target, but not found in later target"),
    string("updated string in latest app target, but not found in earlier target"),
    location = InstructionLocation.MatchAfterWithin(5)
)
```

For unusual cases, a custom filter can be declared by implementing `InstructionFilter`, or by extending
`OpcodeFilter` or `OpcodesFilter` to reduce opcode checking boilerplate. Custom filters cannot be used to speed
up matching, so prefer the built-in filters when possible.

If the distance between each instruction declaration can be approximated, then the `location`
parameter can be used with `MatchAfterWithin(int)` to restrict the instruction match to a maximum
distance from the last instruction. To restrict matching to the first instruction of a method use
`MatchFirst()`.

### 📍 Instruction locations and matching before

Each filter matches relative to the filter declared before it, and the location of each filter
sets where to match, either after or before the previous filter:

| Location                   | Matches                                                                          | As the first filter                     |
|----------------------------|----------------------------------------------------------------------------------|-----------------------------------------|
| `MatchAfterAnywhere()`     | The closest match after the previous filter. This is the default.                | Anywhere in the method                  |
| `MatchAfterImmediately()`  | Immediately after the previous filter.                                           | Not allowed                             |
| `MatchAfterWithin(int)`    | Within a number of instructions after the previous filter.                       | Within the first `int + 1` instructions |
| `MatchFirst()`             | The first instruction of the method.                                             | Required                                |
| `MatchAfterLast()`         | The farthest match after the previous filter, which is the last in the method.   | Anywhere, trying the last match first   |
| `MatchBeforeAnywhere()`    | The closest match before the previous filter.                                    | Not allowed                             |
| `MatchBeforeImmediately()` | Immediately before the previous filter.                                          | Not allowed                             |
| `MatchBeforeWithin(int)`   | The closest match within a number of instructions before the previous filter.    | Not allowed                             |
| `MatchBeforeFirst()`       | The farthest match before the previous filter, which is the first in the method. | Not allowed                             |

A location that is not allowed throws an exception when the fingerprint is matched.

`Anywhere` locations try the closest instruction to the previous filter first, while `MatchAfterLast()` and
`MatchBeforeFirst()` try the farthest first. They only differ when more than one instruction matches.
For example, if the previous filter matched index 6, and the instructions at indexes 1 and 4 both match:

- `MatchBeforeAnywhere()` matches index 4, the closest.
- `MatchBeforeFirst()` matches index 1, the farthest.

The same applies after the previous filter: if indexes 8 and 12 both match,
`MatchAfterAnywhere()` matches index 8 and `MatchAfterLast()` matches index 12.

Matching before is useful when the most unique instruction appears after the instructions to find.
For example, to find the last method call whose result is returned:
```kt
filters = listOf(
    // Tries the last RETURN_OBJECT of the method first.
    opcode(Opcode.RETURN_OBJECT, InstructionLocation.MatchAfterLast()),
    // Within 10 instructions before RETURN_OBJECT.
    methodCall(
      definingClass = "Landroid/net/Uri;",
      name = "parse",
      location = InstructionLocation.MatchBeforeWithin(10)
    ),
    // Immediately after parse method call.
    opcode(Opcode.MOVE_RESULT_OBJECT, InstructionLocation.MatchAfterImmediately())
)
```

When matching before keep in mind:
- Because each filter can change the matching direction, a filter can match the same instruction as an earlier filter.
  For example, `MatchAfterImmediately()` declared after `MatchBeforeImmediately()` matches the same
  instruction as the filter declared before both of them.
- `instructionMatches` are in the same order the filters are declared, and the match indexes are not in
  ascending order if any filter matches before the previous filter.

### 🔁 Retrying filters

Each filter matches the first instruction its location allows, in the order its location searches.
If a filter cannot match, then the filter before it is retried with its next matching instruction.
If that filter has no more matching instructions, then the filter before that one is retried, and so on
back to the first filter. A fingerprint does not match a method only if no combination of instructions
matches every filter, and the first combination found is used.

Retrying only changes what a fingerprint matches if it uses `MatchAfterImmediately()`, `MatchAfterWithin(int)`,
`MatchAfterLast()` or a `MatchBefore` location. If every filter uses `MatchAfterAnywhere()` or `MatchFirst()`,
then the closest instructions always match if any instructions do, so retrying never changes the match.

Retrying is what allows filters that must match close to each other, such as `MatchAfterImmediately()`
and `MatchAfterWithin(int)`, to find a group of instructions anywhere after the previous filter:

```kt
internal object StartVideoInformerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        // Filter 1.
        literal(2.0),
        // Filter 2.
        opcode(Opcode.INVOKE_STATIC),
        // Filter 3.
        opcode(Opcode.MOVE_RESULT_OBJECT, InstructionLocation.MatchAfterImmediately()),
    )
)
```

```asm
# Filter 1 target instruction.
const-wide/high16 v0, 0x4000000000000000L    # 2.0

# Filter 2 matches this first, but filter 3 cannot match the next instruction.
invoke-static {}, Lcom/some/app/Logger;->log()V

iget-object v2, p0, Lcom/some/app/VideoInformer;->a:Ljava/lang/String;

# Filter 2 is retried and matches this call instead.
invoke-static {v2}, Lcom/some/app/VideoInformer;->start(Ljava/lang/String;)Ljava/lang/Object;

# Filter 3 target instruction.
move-result-object v2
```

Filters 2 and 3 together match the first `invoke-static` after the literal that is immediately
followed by `move-result-object`, so `instructionMatches[1]` is the second call.
If no `invoke-static` after the literal is followed by `move-result-object`, then filter 1 is
retried with the next `2.0` literal of the method, if there is one.

Locations that try the farthest instruction first are retried the same way. To find the last
`return-object` of a method that returns the result of a method call:

```kt
filters = listOf(
    // Tries the last RETURN_OBJECT first, then the RETURN_OBJECT before it, and so on.
    opcode(Opcode.RETURN_OBJECT, InstructionLocation.MatchAfterLast()),
    opcode(Opcode.MOVE_RESULT_OBJECT, InstructionLocation.MatchBeforeImmediately()),
)
```

If the last `return-object` returns a field value or a parameter, then the `return-object` before it is tried.

`MatchFirst()`, `MatchAfterImmediately()` and `MatchBeforeImmediately()` can only match one instruction,
so they have no other instruction to retry with, and the filter before them is retried instead.

### 💬 String declarations

There are two ways to declare strings in fingerprints. The first and preferred way is using a
string filter declaration such as `filters = listOf(string("foo"), string("bar"), /* other filters if desired */ )`
where the order of the strings declared in the fingerprint must be the same as the order the strings appearing in
the target method (the example above "foo" must appear _before_ "bar").

The second is using the `strings = listOf("foo", "bar")` declaration, where the strings are unordered and will
match in any order of the target method. Generally, the `strings = listOf()` declaration is only useful for
matching methods with a large number of strings and they may appear randomly such as enum name types in an Enum
initialization method. If you are unsure which to use, then use `filters = listOf(string("foo"))`.
Raw strings such as `strings = listOf("foo")` are compared using `StringComparisonType.EQUALS`. To use a different
comparison, declare string filters instead such as `strings = listOf(string("foo"), string("bar", StringComparisonType.STARTS_WITH))`.
String filters declared in `strings` match in any order and cannot declare an `InstructionLocation`.
The indices of strings declared this way are not in `instructionMatches`, but instead in `stringMatches`,
in the same order the strings are declared.

### 🧩 Pure opcode matching

If a method cannot be uniquely identified using the built-in filters, but a fixed pattern of
opcodes can identify the method, then the opcode pattern can be defined using the fingerprint
`filters = OpcodesFilter.opcodesToFilters(Opcode...)` declaration.  Opcode patterns do not allow
variable spacing between each opcode, and all opcodes must appear exactly as declared. Opcode
patterns should be avoided whenever possible due to their fragility and possibility of matching
completely unrelated code.

> [!TIP]
> A fingerprint should contain information about a method that is unlikely to change between updates.
> Obfuscated class and method names should never be used.

## 🔨 How to use fingerprints

After declaring a fingerprint it can be used in a patch to find the method it matches to:

```kt
execute {
    AdLoaderFingerprint.let {
        // Changes the target code to:
        // if (false) {
        //    showBannerAds();
        // }
        val filter4 = it.instructionMatches[3]
        val moveResultIndex = filter4.index
        val moveResultRegister = filter4.getInstruction<OneRegisterInstruction>().registerA

        it.method.addInstructions(moveResultIndex + 1, "const/4 v$moveResultRegister, 0x0")
    }
}
```

Each item of `instructionMatches` corresponds to the filter declared at the same position, and has:

- `index`: The index of the matched instruction in the method.
- `instruction`: The matched instruction.
- `getInstruction<T>()`: The matched instruction, cast to the expected type such as `OneRegisterInstruction`.
- `getMethodCalled()`: For a method call, the mutable method that is called.
- `getFieldAccessed()`: For a field access, the mutable field that is accessed.

Be careful if making more than 1 modification to the same method. Adding or removing instructions in
a method can cause fingerprint match indexes to no longer be correct. The simplest solution is
to modify the target method from the last match index to the first. Another solution is after modifying
the target method to then call `clearMatch()` followed by `match()`, and then the instruction match indexes
are up to date and correct.

Modifying the example above to also change the code `return parameter2 != 1337;` into `return true;`:

```kt
execute {
    AdLoaderFingerprint.let {
        // Modify method from last indexes to first to preserve the correct fingerprint indexes.

        // Remove the conditional branch, so the method always continues to
        // the const/4 p1, 0x1 instruction and returns true.
        val filter6 = it.instructionMatches[5]
        it.method.removeInstruction(filter6.index)

        // Changes the target code to:
        // if (false) {
        //    showBannerAds();
        // }
        val filter4 = it.instructionMatches[3]
        val moveResultIndex = filter4.index
        val moveResultRegister = filter4.getInstruction<OneRegisterInstruction>().registerA

        it.method.addInstructions(moveResultIndex + 1, "const/4 v$moveResultRegister, 0x0")
    }
}
```

For performance reasons, a fingerprint will always match only once (unless `clearMatch()` is called).
This makes it useful to share fingerprints between multiple patches, and the fingerprint matches on
the first usage of it.

```kt
val mainActivityPatch1 = bytecodePatch {
    execute {
        mainActivityOnCreateFingerprint.method.apply {
            // Modifications made here.
        }
    }
}

val mainActivityPatch2 = bytecodePatch {
    execute {
        mainActivityOnCreateFingerprint.method.apply {
            // More modifications made here.
            // Fingerprint does not match again, and the match result indexes are still the same as
            // found in mainActivityPatch1.
        }
    }
}
```

> [!WARNING]
> If one patch adds or removes instructions in a method, the match indexes of a shared fingerprint are no
> longer correct for other patches, and the order patches are executed in is not always known.
> If a patch uses match indexes of a fingerprint that other patches also modify, call `clearMatch()` first,
> so the fingerprint matches again against the modified method:
>
> ```kt
> mainActivityOnCreateFingerprint.clearMatch()
> mainActivityOnCreateFingerprint.let {
>     val index = it.instructionMatches.first().index
>     // ...
> }
> ```

> [!TIP]
> Avoid using custom block unless necessary. Patching performance is dramatically faster
> using instruction filters and other non-custom declarations.


If the patch only needs the method to always return a constant value, no match indexes are needed.
Use `returnEarly()` to return the value before any of the original code runs, or `returnLate()` to run
the original code and then return the value from every return instruction:

```kt
execute {
    // The ad loader never runs, and the method always returns false.
    AdLoaderFingerprint.method.returnEarly(false)
}
```

See the `returnEarly()` and `returnLate()` section of [Advanced APIs](4_apis.md) for all supported return types.

Using methods found with one fingerprint in a different fingerprint:

Fingerprints can be declared as local variables (and not classes as above),
which is useful if a fingerprint requires information found with prior fingerprints.

```kotlin
val complexPatch = bytecodePatch(name = "Complex patch") {
    execute {
        val showAdFingerprint = Fingerprint(
            returnType = "Z",
            parameters = listOf("Ljava/lang/String;"),
            filters = listOf(
                methodCall(
                    name = "shouldShowAds",
                    returnType = "Z",
                    // Use class found with fingerprint declared earlier.
                    definingClass = AdLoaderFingerprint.originalClassDef.type
                ),
                opcode(Opcode.MOVE_RESULT, MatchAfterImmediately())
            )
        )

        showAdFingerprint.let {
            val shouldShowAdsFilterMatch = it.instructionMatches[1]
            val register = shouldShowAdsFilterMatch.getInstruction<OneRegisterInstruction>().registerA

            // Override method call return value of "shouldShowAds" with false.
            it.method.addInstructions(
                shouldShowAdsFilterMatch.index,
                """
                    const/4 v0, 0x0
                    return v0
                """
            )
        }
    }
}
```

> [!WARNING]
> If the fingerprint cannot be matched to any method,
> accessing properties of the fingerprint raises a `PatchException`.
> If no match is a normal use case, instead use the `OrNull` properties and functions such as `matchOrNull()`.

The following functions and properties can be accessed in a fingerprint:

- `match()`: The `Match` of the fingerprint. If no match is found, an exception is raised.
- `matchOrNull()`: The `Match` of the fingerprint, or null.
- `instructionMatches`: The matches of the declared `filters`, in the order the filters are declared.
  If no match is found, an exception is raised.
- `instructionMatchesOrNull`: The matches of the declared `filters`, or null.
- `stringMatches` and `stringMatchesOrNull`: The matches of the unordered `strings` declaration.
- `clearMatch()`: Clears the match, so the fingerprint matches again the next time it is used.
- `patchException()`: A `PatchException` for this fingerprint failing to match, to throw from custom code.
- `originalClassDef`: The immutable class definition the fingerprint matches to. If no match is found, an exception is raised.
- `originalClassDefOrNull`: The immutable class definition the fingerprint matches to, or null.
- `originalMethod`: The immutable method the fingerprint matches to. If no match is found, an exception is raised.
- `originalMethodOrNull`: The immutable method the fingerprint matches to, or null.
- `classDef`: The mutable class the fingerprint matches to. If no match is found, an exception is raised.
- `classDefOrNull`: The mutable class the fingerprint matches to, or null.
- `method`: The mutable method the fingerprint matches to. If no match is found, an exception is raised.
- `methodOrNull`: The mutable method the fingerprint matches to, or null.

The difference between the `original` and non-`original` properties is that the `original` properties return the
original class or method definition, while the non-`original` properties return a mutable copy of the class or method.
The mutable copies can be modified. The first time a mutable copy is accessed, it is created and replaces the
original class in the app, so the changes are included in the patched app.

> [!TIP]
> If only read-only access to the class or method is needed, the `originalClassDef` and
> `originalMethod` properties should be used, to avoid making a mutable copy of the class or method.


## 🔁 Finding all methods a fingerprint matches

A fingerprint matches only the first method it finds. To find every method that matches, use `matchAll()`,
which returns a `Match` for each method. The instruction matches of each method are the first that match
the filters.

```kt
execute {
    ShowAdsFingerprint.matchAll().forEach { match ->
        match.method.addInstruction(0, "return-void")
    }
}
```

- `matchAll()` raises an exception if no method matches, and `matchAllOrNull()` returns null instead.
- `matchAll(range)` raises an exception if the number of matched methods is not in the range,
  such as `matchAll(1..2)`. A range that includes zero returns an empty list if no method matches.
- `matchAll(classDef)` and `matchAll(classDef, range)` match only the methods of a single class.

To change every matching instruction instead of only the first of each method, such as changing all
const-string instructions to a different string literal, use `matchAllMethodIndicesForEach()`:

```kt
import app.morphe.patcher.util.matchAllMethodIndicesForEach

// Replace a specific bytecode string in all const-string instructions.
string("original text").matchAllMethodIndicesForEach { index ->
    val register = getInstruction<OneRegisterInstruction>(index).registerA
    replaceInstruction(index, "const-string v$register, \"new text\"")
}
```

See [`matchAllMethodIndicesForEach()`](4_apis.md#-matchallmethodindicesforeach) for more details.

## 🏹 Manually matching fingerprints

By default, a fingerprint is matched automatically against all classes when one of the
fingerprint's properties is accessed.

Instead, the fingerprint can be matched manually using the overloads of a fingerprint's `match` function.
Each has an `OrNull` variant that returns null instead of raising an exception.

- In a **single class**, if the fingerprint can match in a single known class:

  ```kt
  execute {
      val adsLoaderClass = classDefBy("Lcom/some/app/ads/Loader;")

      val match = showAdsFingerprint.match(adsLoaderClass)
  }
  ```

- In a **single method**, to check if a known method matches, with `match(method)` or
  `match(method, classDef)`.

A common use case is to find the class of the target code by fingerprinting an easy
to identify method in that class (especially a method with string constants), then use the class
found to match a second fingerprint that finds the target method:

```kt
execute {
    // Match showAdsFingerprint to the class of the ads loader found by adsLoaderClassFingerprint.
    val match = showAdsFingerprint.match(adsLoaderClassFingerprint.originalClassDef)
}
```

This can be declared as part of the fingerprint itself, using the `classFingerprint` field:

```kt
val showAdFingerprint = Fingerprint(
    // Find class using another fingerprint, such as a method that contains unique strings.
    classFingerprint = Fingerprint(name = "toString", strings = listOf("classField=")),
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            name = "getValue",
            returnType = "Z",
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately())
    )
)
```

> [!TIP]
> To see real-world examples of fingerprints,
> check out the repository for [Morphe Patches](https://github.com/MorpheApp/morphe-patches).

## ⏭️ What's next

The next page discusses the structure and conventions of patches.

Continue: [📜 Project structure and conventions](3_structure_and_conventions.md)
