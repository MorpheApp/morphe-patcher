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
    // Exact access flags
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
            // Restrict to field get operation.
            opcode = Opcode.IGET,
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
    .registers 4

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
  each filter, zero or more other instructions can exist.  Instruction filters must be declared in
  the same order as the instructions appear in the target method.

  If the distance between each instruction declaration can be approximated, then the `instructionLocation`
  parameter can be used with `MatchAfterWithin(int)` to restrict the instruction match to a maximum
  distance from the last instruction. To restrict matching to the first instruction of a method use
  `MatchFirst()`. See `InstructionLocation` for more ways to restrict the matching index.

  If a single instruction varies slightly between different app targets but otherwise the fingerprint
  is still the same, the `anyInstruction()` filter can be used to specify the different expected
  instruction. Such as:
  ```kt
  anyInstruction(
      string("string in early app target, but not found in later target"),
      string("updated string in latest app target, but not found in earlier target")
  )
  ```

  To simplify some filter declarations, `methodCall` and `fieldAccess` can be declared using
  copy-pasted un-obfuscated smali statements. Such as: 
  ```kt
  methodCall(smali = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;")
  fieldAccess(smali = "Landroid/os/Build;->MODEL:Ljava/lang/String;")
  ```

  #### String declarations
  
  There are two ways to declare strings in fingerprints. The first and preferred way is using a
  string filter declaration such as `filters = listOf(string("foo"), string("bar"), /* other filters if desired */ )`
  where the order of the strings declared in the fingerprint must be the same as the order the strings appearing in
  the target method (the example above "foo" must appear _before_ "bar").
  
  The second is using the `strings = listof("foo", "bar")` declaration, where the strings are unordered and will
  match in any order of the target method. Generally, the `strings = listOf()` declaration is only useful for 
  matching methods with a large number of strings and they may appear randomly such as enum name types in an Enum
  initialization method. If you are unsure which to use, then use `filter = listOf(string("foo"))`.
  If you need the indices of this second matching type they are not found in `instructionMatches` but instead in
  their own legacy matching result. Please see the Fingerprint javadoc for more details on this second string declaration.

  #### Pure opcode matching

  If a method cannot be uniquely identified using the built in filters, but a fixed pattern of
  opcodes can identify the method, then the opcode pattern can be defined using the fingerprint
  `filters = OpcodesFilter.opcodesToFilters(Opcode...)` declaration.  Opcode patterns do not allow 
  variable spacing between each opcode, and all opcodes all must appear exactly as declared. Opcode 
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
        val moveResultIndex = filter3.index
        val moveResultRegister = filter3.getInstruction<OneRegisterInstruction>().registerA

        it.method.addInstructions(moveResultIndex + 1, "const/4 v$moveResultRegister, 0x0")
    }
}
```

Be careful if making more than 1 modification to the same method.  Adding/removing instructions to
a method can cause fingerprint match indexes to no longer be correct. The simplest solution is
to modify the target method from the last match index to the first.  Another solution is after modifying
the target method to then call `clearMatch()` followed by `match()`, and then the instruction match indexes
are up to date and correct.

Modifying the example above to also change the code `return parameter2 != 1337;` into: `return false;`: 

```kt
execute {
    AdLoaderFingerprint.let {
        // Modify method from last indexes to first to preserve the correct fingerprint indexes.

        // Remove conditional branch and always return false.
        val filter6 = it.instructionMatches[5]
        it.method.removeInstruction(filter6.index)

        // Changes the target code to:
        // if (false) {
        //    showBannerAds();
        // }
        val filter4 = it.instructionMatches[3]
        val moveResultIndex = filter3.index
        val moveResultRegister = filter3.getInstruction<OneRegisterInstruction>().registerA

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
> If the fingerprint can not be matched to any method,
> accessing certain properties of the will raise an exception.
> If no match existing is a normal use case, instead use the `orNull` properties such as `matchOrNull`.

The following properties can be accessed in a fingerprint:

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
The mutable copies can be modified. They are lazy properties, so they are only computed
and only then will effectively replace the `original` method or class definition when accessed.

> [!TIP]
> If only read-only access to the class or method is needed, the `originalClassDef` and
> `originalMethod` properties should be used, to avoid making a mutable copy of the class or method.

## 🏹 Manually matching fingerprints

By default, a fingerprint is matched automatically against all classes when one of the
fingerprint's properties is accessed.

Instead, the fingerprint can be matched manually using various overloads of a fingerprint's `match` function:

- In a **list of classes**, if the fingerprint can match in a known subset of classes

  If you have a known list of classes you know the fingerprint can match in, you can match the
  fingerprint on the list of classes:

  ```kt
  execute {
      val match = ShowAdsFingerprint.match(classes)
  }
  ```

- In a **single class**, if the fingerprint can match in a single known class

  If you know the fingerprint can match a method in a specific class, you can match the fingerprint
  in the class:

  ```kt
  execute { 
      val adsLoaderClass = classBy("Lcom/some/app/ads/Loader;")

      val match = showAdsFingerprint.match(adsLoaderClass)
  }
  ```

  Another common use case is to find the class of the target code by finger printing an easy
  to identify method in that class (especially a method with string constants), then use the class
  found to match a second fingerprint that finds the target method. 

  ```kt
  execute {
      // Match showAdsFingerprint to the class of the ads loader found by adsLoaderClassFingerprint.
      val match = showAdsFingerprint.match(adsLoaderClassFingerprint.originalClassDef)
  }
  ```

> [!TIP]
> To see real-world examples of fingerprints,
> check out the repository for [Morphe Patches](https://github.com/MorpheApp/morphe-patches).

## ⏭️ What's next

The next page discusses the structure and conventions of patches.

Continue: [📜 Project structure and conventions](3_structure_and_conventions.md)
