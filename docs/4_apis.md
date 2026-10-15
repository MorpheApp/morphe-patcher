# 💪 Advanced APIs

A handful of APIs are available to make patch development easier and more efficient.

## 📙 Overview

1. 🔍 Find immutable classes with `classDefBy(String)`
2. 🔄️️ Change immutable classes to mutable with `mutableClassDefBy(String)` and `mutableClassDefBy(ClassDef)`
3. 💾 Read and write resource files with `get(String, Boolean)` and `delete(String)`
4. 📃 Read and write DOM files using `document(String)` and  `document(InputStream)`
5. 📜 List the entries of the APK being patched with `listApkEntries(String)`
6. 🗂️ Decode resources when they are needed with `decode(String...)` and `listDecodableFiles(String)`
7. 🧮 Find free registers to use in injected code with `findFreeRegister(Int, Int...)` and `getFreeRegisterProvider(Int, Int, Int...)`
8. 🔬 Get the registers of an instruction with `registersUsed`
9. ↩️ Override the return value of a method with `returnEarly()` and `returnLate()`
10. 🔁 Change every matching instruction in all methods with `matchAllMethodIndicesForEach()`

### 🧰 APIs

#### 🔍 `classDefBy(String)`

The `classDefBy(String)` function finds an immutable class by its type, such as a type from a constant
string or from a fingerprint match. `classDefByOrNull(String)` returns null if the class does not exist.
Both also accept a predicate to find a class by other characteristics.

```kt
execute {
    // Find the superclass of the return type of a fingerprint match.
    val superclassOfReturnType = classDefBy(ShowAdsFingerprint.originalMethod.returnType).superclass
}
```

#### 🔄️ `mutableClassDefBy(String)` and `mutableClassDefBy(ClassDef)`

By default, the classes are immutable and they cannot be modified.
To make a class mutable use the `mutableClassDefBy` function, with the type of the class or the class itself.
The first call replaces the original class definition with a mutable copy,
thus allowing you to make changes to the class. Subsequent calls return the same mutable copy.

```kt
execute {
    // Find the superclass of the return type of a fingerprint match.
    val superclassOfReturnType = classDefBy(ShowAdsFingerprint.originalMethod.returnType).superclass!!

    // Make the superclass mutable, and make all of its methods public.
    mutableClassDefBy(superclassOfReturnType).methods.forEach { method ->
        method.setAccessFlags(
            (method.accessFlags and (AccessFlags.PRIVATE.value or AccessFlags.PROTECTED.value).inv())
                    or AccessFlags.PUBLIC.value
        )
    }
}
```

To modify a method called by an instruction of a fingerprint match, use `getMethodCalled()` of the instruction
match. It returns the mutable method, making its class mutable if needed:

```kt
execute {
    // The first filter of ShowAdsFingerprint is a methodCall filter.
    ShowAdsFingerprint.instructionMatches.first().getMethodCalled().returnEarly()
}
```

#### 💾 `get(String, Boolean)` and `delete(String)`

The `get(String, Boolean)` function returns a `File` object that can be used to read and write resource files.

```kt
execute {
    val file = get("res/values/strings.xml")
    val content = file.readText()
    file.writeText(content)
}
```

When resources are decoded on request, as `decode(String...)` below describes, `get` decodes the file
first if it is not decoded yet. Pass `false` as the second argument to get the file without decoding it,
to replace or add a file. A values file written this way, such as
`res/values-de/strings.xml`, adds its resources to those of the APK and replaces the ones of the same name,
but removes none.

The `delete` function can mark files for deletion when the APK is rebuilt. It accepts decoded
resource paths as well as any other entry of the APK as returned by `listApkEntries(String)`,
including native libraries, which are never staged to the working directory. A directory name
such as `lib/x86/` deletes everything below it. The manifest and `resources.arsc` cannot be deleted.

```kt
execute {
    delete("res/values/strings.xml")

    // Keep only one architecture.
    listApkEntries("lib/")
        .filterNot { it.startsWith("lib/arm64-v8a/") }
        .forEach(::delete)
}
```

#### 📃 `document(String)`  and  `document(InputStream)`

The `document` function is used to read and write DOM files.

```kt
execute {
    document("res/values/strings.xml").use { document ->
        val element = document.createElement("string").apply {
            textContent = "Hello, World!"
        }
        document.documentElement.appendChild(element)
    }
}
```

You can also read documents from an `InputStream`:

```kt
execute {
    val inputStream = classLoader.getResourceAsStream("some.xml")
    document(inputStream).use { document ->
        // ...
    }
}
```

#### 📜 `listApkEntries(String)`

The `listApkEntries` function lists the entries of the APK being patched, without staging them
to the working directory. Use it to discover what an APK contains, rather than walking the
working directory: not every entry is staged there, and native libraries in particular are left
in the archive. Names are archive names, so they can be passed straight to `get(String, Boolean)`.
An optional prefix restricts the listing.

```kt
execute {
    val nativeLibraries = listApkEntries("lib/")
}
```

The listing describes the input APK, so it does not reflect files a patch has since added,
changed or deleted.

#### 🗂️ `decode(String...)` and `listDecodableFiles(String)`

Resources are decoded when patches need them, instead of all of them before any patch executes.
Decoding a large app takes much of the time patching does, while patches read only a few of its files.

- The manifest is always decoded.
- A file a patch reads by its path, with `get(String, Boolean)` or `document(String)`, is decoded then.
- A directory only holds the files decoded or added so far. A patch that lists or walks a directory must
  first decode what it looks for with `decode`.

`decode` takes glob patterns: `*` matches within one segment of a path, `**` matches across segments and
`?` matches one character. A pattern that matches a directory matches everything below it, and `**`
decodes everything. It returns the matching files in the working directory, which include files patches
added. Files that are already decoded, or that a patch changed, added or deleted, are left as they are.

```kt
execute {
    // Every values file holding colors, such as res/values-night/colors.xml.
    decode("res/values*/colors.xml").forEach { path ->
        document(path).use { document ->
            // ...
        }
    }

    // Everything below a directory.
    decode("res/xml")
}
```

`listDecodableFiles` lists what can be decoded, by the path it is decoded to, without decoding anything.
It lists the manifest, the resource files, the values files `resources.arsc` is decoded to, and the other
entries of the APK, which are extracted as they are. Native libraries and DEX files are not listed.

```kt
execute {
    val locales = listDecodableFiles("res/values-*/strings.xml")
}
```

#### 🧮 `findFreeRegister(Int, Int...)` and `getFreeRegisterProvider(Int, Int, Int...)`

Injected code often needs a register to hold a temporary value. Using a hard coded register such as
`v0` is fragile, because the original code may still need the value in that register after the
injected code runs. `findFreeRegister` returns a register that is safe to overwrite at an index,
without changing the register count of the method.

Starting from the given index, the instructions are scanned for a register that is written to
before it is read from. Branches, switches and gotos are followed, and a register is only returned
if it is free on every path. If a return or throw is reached, any register not yet used on
that path is also free. Long and double values are handled as register pairs, and
4-bit registers (`v0` to `v15`) are preferred because many instructions can only use those.
If no 4-bit register is free, a higher register is returned.

Pass every register the injected code reads from as an excluded register,
so the free register does not overwrite a value the injected code needs.

```kt
import app.morphe.patcher.util.findFreeRegister

execute {
    ShowTitleFingerprint.let {
        // The match is the move-result-object that holds the TextView.
        val moveResult = it.instructionMatches.last()
        val insertIndex = moveResult.index + 1
        val viewRegister = moveResult.getInstruction<OneRegisterInstruction>().registerA

        it.method.apply {
            val freeRegister = findFreeRegister(insertIndex, viewRegister)

            addInstructions(
                insertIndex,
                """
                    invoke-static { v$viewRegister }, Lapp/morphe/extension/TitlePatch;->getTitle(Landroid/widget/TextView;)Ljava/lang/String;
                    move-result-object v$freeRegister
                    invoke-virtual { v$viewRegister, v$freeRegister }, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
                """
            )
        }
    }
}
```

If injected code needs more than one free register, use `getFreeRegisterProvider`.
The second parameter is the number of free registers to find, and the remaining parameters are
the registers to exclude, the same as with `findFreeRegister`.
Each call to `getFreeRegister()` returns a different register.

```kt
import app.morphe.patcher.util.getFreeRegisterProvider

execute {
    SettingsFingerprint.let {
        it.method.apply {
            // Insert before the last instruction, which is return-void.
            val insertIndex = implementation!!.instructions.size - 1
            val fragmentRegister = it.instructionMatches.first()
                .getInstruction<OneRegisterInstruction>().registerA

            val registerProvider = getFreeRegisterProvider(insertIndex, 2, fragmentRegister)
            // Non-range invoke instructions can only use 4-bit registers.
            val screenRegister = registerProvider.getFreeRegister4Bit()
            val preferenceRegister = registerProvider.getFreeRegister4Bit()

            addInstructions(
                insertIndex,
                """
                    invoke-virtual { v$fragmentRegister }, Landroidx/preference/PreferenceFragmentCompat;->getPreferenceScreen()Landroidx/preference/PreferenceScreen;
                    move-result-object v$screenRegister
                    invoke-static { v$screenRegister }, Lapp/morphe/extension/SettingsPatch;->createPreference(Landroidx/preference/PreferenceScreen;)Landroidx/preference/Preference;
                    move-result-object v$preferenceRegister
                    invoke-virtual { v$screenRegister, v$preferenceRegister }, Landroidx/preference/PreferenceScreen;->addPreference(Landroidx/preference/Preference;)Z
                """
            )
        }
    }
}
```

The provider has these functions:

- `getFreeRegister()`: Returns the next free register. Throws an `IllegalStateException` if none are left.
- `getFreeRegister4Bit()`: Same as `getFreeRegister()`, but throws an `IllegalStateException`
  if the next free register is not a 4-bit register. Use this if the register is used by an
  instruction that only accepts `v0` to `v15`, such as a non-range `invoke` or `const/4`.
- `availableCount()` and `hasFreeRegisters()`: How many free registers are left.
- `getAllocatedFreeRegisters()`: The registers already returned by `getFreeRegister()`.
- `getUsedAndUnAvailableRegisters()`: Every register that is not safe to use, including the
  excluded registers and the registers already returned. This can be passed as the excluded
  registers to a later search at a different index.

`findFreeRegister` and `getFreeRegisterProvider` throw an `IllegalArgumentException` if no
free register exists at the index.
This can happen if every register is read before it is written, or if the paths of a branch or
switch have no free register in common. In that case, add registers to the method instead with
`cloneParameters()` or `addFreeRegisters(Int)`.

Both replace the method in its class with a clone that has more registers, so the original method
must not be modified afterward and the returned method must be used instead. Instructions are added
to the start of the clone, so instruction indexes found before cloning, such as fingerprint match
indexes, must be increased by the number of added instructions.

- `cloneParameters()`: Copies the parameters to the registers they used in the original method,
  so the parameter registers `p0`, `p1`, etc. are free to use. The number of added instructions
  is `numberOfParameterRegistersLogical`. This cannot be used on a static method without parameters.
- `addFreeRegisters(Int)`: Use this if the method does not have enough parameter registers. Returns
  the cloned method and the number of added instructions. The free registers start at the
  `registerCount` of the original method, so they may be too large for instructions that only
  accept 4-bit registers.

```kt
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegistersLogical

execute {
    val titleIndex = ShowTitleFingerprint.instructionMatches.last().index

    ShowTitleFingerprint.method.cloneParameters().apply {
        // Account for the instructions added to the start of the cloned method.
        val insertIndex = titleIndex + numberOfParameterRegistersLogical

        // The original p0 value was copied to another register, so p0 is free to use.
        addInstructions(
            insertIndex,
            """
                invoke-static { }, Lapp/morphe/extension/TitlePatch;->getTitle()Ljava/lang/String;
                move-result-object p0
                invoke-static { p0 }, Lapp/morphe/extension/TitlePatch;->logTitle(Ljava/lang/String;)V
            """
        )
    }

    val settingsIndex = SettingsFingerprint.instructionMatches.last().index
    val firstFreeRegister = SettingsFingerprint.method.implementation!!.registerCount
    val (clonedMethod, addedInstructions) = SettingsFingerprint.method.addFreeRegisters(3)

    // Registers firstFreeRegister to firstFreeRegister + 2 are free to use.
    clonedMethod.addInstructions(
        settingsIndex + addedInstructions,
        "const v$firstFreeRegister, 0x1"
    )
}
```

#### 🔬 `registersUsed`

The `registersUsed` extension property returns the registers of an `Instruction`, in the order they
are listed. An instruction that does not use any registers returns an empty list.

This is most useful for method calls. The same call can be compiled differently between app
versions, depending on which registers the compiler picked:

```smali
# All registers are 4-bit (v0 to v15), so the registers are listed individually.
invoke-static { v1, v2 }, Lcom/example/TitleView;->setTitle(Ljava/lang/String;I)V

# A register is v16 or higher, so a range of consecutive registers is used.
invoke-static/range { v16 .. v17 }, Lcom/example/TitleView;->setTitle(Ljava/lang/String;I)V
```

The registers of the first form are read with `FiveRegisterInstruction.registerC`, `registerD`,
etc., and the registers of the second form with `RegisterRangeInstruction.startRegister` and
`registerCount`. A patch that only handles one form breaks when the app is compiled the other way.
`registersUsed` handles both, and returns `[1, 2]` for the first call and `[16, 17]` for the second.

```kt
import app.morphe.patcher.util.registersUsed

execute {
    ShowTitleFingerprint.let {
        // Matches the call to setTitle(Ljava/lang/String;I)V, in either form.
        val setTitleMatch = it.instructionMatches.first()
        // A static call has no instance register, so the first register is the first parameter.
        val titleRegister = setTitleMatch.instruction.registersUsed.first()

        // Replace the title argument before the call.
        // A range of one register works with any register number.
        it.method.addInstructions(
            setTitleMatch.index,
            """
                invoke-static/range { v$titleRegister .. v$titleRegister }, Lapp/morphe/extension/TitlePatch;->getTitle(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$titleRegister
            """
        )
    }
}
```

For a call that is not static, the first register is the instance the method is called on, and the
parameters follow. A `long` or `double` parameter uses two registers, and both are listed.

#### ↩️ `returnEarly()` and `returnLate()`

These functions change a method to always return a constant value.

- `returnEarly(value)`: Inserts a return at the start of the method,
  so none of the original code runs.
- `returnLate(value)`: The original code runs as before, but every return instruction
  returns the value instead. Use this if the original code does something that must still happen,
  such as setting a field or registering a listener.

```kt
import app.morphe.patcher.util.returnEarly
import app.morphe.patcher.util.returnLate

execute {
    // Skip the method entirely and always return true.
    IsPremiumFingerprint.method.returnEarly(true)

    // Run the original code, but always return false.
    ShouldShowAdsFingerprint.method.returnLate(false)

    // Skip a method that returns void.
    LogAnalyticsFingerprint.method.returnEarly()

    // Return null from a method that returns an object or an array.
    GetAdConfigFingerprint.method.returnEarly(null)

    // Return a constant string.
    GetUserAgentFingerprint.method.returnLate("Morphe")
}
```

- Prefer `returnLate()` if the method has side effects that other code depends on, and `returnEarly()`
  if the original code should not run at all, such as code that loads ads or sends analytics.

#### 🔁 `matchAllMethodIndicesForEach()`

`matchAllMethodIndicesForEach()` finds every method a fingerprint matches, and calls the block once
for each instruction in those methods that matches the fingerprint's filter. The block is called
with the method as the receiver and the index of the instruction as the parameter.

This differs from `matchAll()`, which only gives the first matching index of each method.
If a method has the same string three times, `matchAll()` matches it once
and `matchAllMethodIndicesForEach()` calls the block three times.

For example, to change the text `original text` everywhere it is used in the app:

```kt
import app.morphe.patcher.util.matchAllMethodIndicesForEach

object OriginalTextFingerprint : Fingerprint(
    filters = listOf(
        string("original text")
    )
)

execute {
    OriginalTextFingerprint.matchAllMethodIndicesForEach { index ->
        // const-string and const-string/jumbo both store the string in registerA.
        val register = getInstruction<OneRegisterInstruction>(index).registerA

        replaceInstruction(index, "const-string v$register, \"new text\"")
    }
}
```

Before the patch:

```java
void showTitle() {
    titleView.setText("original text");
    toolbar.setTitle("original text");
    Log.d(TAG, "original text");
}
```

After the patch, the block was called three times for this method:

```java
void showTitle() {
    titleView.setText("new text");
    toolbar.setTitle("new text");
    Log.d(TAG, "new text");
}
```

- The fingerprint must have exactly one filter, otherwise an `IllegalArgumentException` is thrown.
  Other fingerprint properties, such as `returnType`, `parameters` or `custom`, can be used to
  limit which methods are searched.
- The indices of each method are visited from last to first, so adding or removing instructions
  at the current index does not change the indices that have not been visited yet.
- If nothing matches, a `PatchException` is thrown. Use `requireMatches = false` if the
  instruction may not exist in some app versions:
  `OriginalTextFingerprint.matchAllMethodIndicesForEach(requireMatches = false) { index -> ... }`

A single filter can also be used directly, without declaring a fingerprint:

```kt
string("original text").matchAllMethodIndicesForEach { index ->
    val register = getInstruction<OneRegisterInstruction>(index).registerA

    replaceInstruction(index, "const-string v$register, \"new text\"")
}
```

## 🎉 Afterword

Morphe Patcher is a powerful library to patch Android applications, offering a rich set of APIs to
develop patches that outlive app updates. Patches make up Morphe; without you, the community of
patch developers, Morphe would not be what it is today. We hope that this documentation has been
helpful to you and are excited to see what you will create with Morphe Patcher.
