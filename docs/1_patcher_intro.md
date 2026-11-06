# 💉 Introduction to Morphe Patcher

To create patches for Android apps, it is recommended to know the basic concept of Morphe Patcher.

## 📙 How it works

Morphe Patcher is a library that allows modifying Android apps by applying patches.
It is built on top of [Smali](https://github.com/google/smali) for bytecode manipulation and [ARSCLib](https://github.com/MorpheApp/ARSCLib)
for resource decoding and encoding.

Morphe Patcher receives a list of patches and applies them to a given APK file.
It then returns the modified components of the APK file, such as modified dex files and resources,
that can be repackaged into a new APK file.

Morphe Patcher has a simple API that allows you to load patches from MPP (JAR or DEX container) files
and apply them to an APK file. Later on, you will learn how to create patches.

```kt
val patches = loadPatchesFromJar(setOf(File("morphe-patches.mpp")))

val patcherResult = Patcher(PatcherConfig(apkFile = File("some.apk"))).use { patcher ->
    // Here you can access metadata about the APK file through patcher.context.packageMetadata
    // such as package name, version code, version name, etc.

    // Add patches.
    patcher += patches

    // Execute the patches.
    runBlocking {
        patcher().collect { patchResult ->
            if (patchResult.exception != null)
                logger.severe { "\"${patchResult.patch}\" failed:\n${patchResult.exception}" }
            else
                logger.info { "\"${patchResult.patch}\" succeeded" }
        }
    }

    // Compile and save the patched APK file components.
    patcher.get()
}

// The result of the patcher contains the modified components of the APK file that can be repackaged into a new APK file.
val dexFiles = patcherResult.dexFiles
val resources = patcherResult.resources
```

### 📊 Progress

Patches are reported one by one as they execute. The rest of the work, such as decoding the APK,
indexing its classes and writing the patched APK, is reported through `PatcherConfig.progress`, so a
host can show how far patching got:

```kt
val config = PatcherConfig(
    apkFile = File("some.apk"),
    progress = { task, done, total -> println("$task: $done of $total") },
)
```

Each `PatcherTask` reports 0 of its total when it starts and its total when it is done. Reports are
spaced out and come from the threads that do the work.

## ⏭️ What's next

The next page teaches the fundamentals of Morphe Patches.

Continue: [🧩 Introduction to Morphe Patches](2_patches_intro.md)
