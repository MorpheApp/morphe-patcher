# 🧩 Introduction to Morphe Patches

Learn the basic concepts of Morphe Patcher and how to create patches.

## 📙 Fundamentals

A patch is a piece of code that modifies an Android application (APK).  
Patches are declared with the `patch` function, and a patch can modify any part of the APK:
the Dalvik VM bytecode, the decoded resources and any other file.

Each patch can declare dependencies on other patches. Morphe Patcher ensures these dependencies are 
executed first, allowing patches to work together in a modular way.

The `execute` function is the entry point for a patch. It is called by Morphe Patcher when the patch is executed.

Patches modify the APK with global functions, such as `mutableClassDefBy` for classes and `document` for
resources, and with fingerprints. These can be used while an APK is being patched, such as in the `execute`
block of a patch, in a function that a patch calls, or in an instruction filter of a fingerprint.

Morphe Patcher decodes what patches use when they first need it:

- The DEX files are decoded when a patch first uses a class or a fingerprint.
- The manifest and the resource table are decoded when a patch first uses a resource, and every other
  resource when a patch requests it, see [Advanced APIs](4_apis.md).
- Other files of the APK, such as `assets/data.bin` and native libraries, are extracted when a patch requests
  them.

Anything that no patch uses is copied to the patched APK as it is.

Example of a patch:

```kt
@Suppress("unused")
val examplePatch = patch {
    execute { 
        // More about this on the next page of the documentation.
    }
}
```

> [!TIP]
> To see real-world examples of patches,
> check out the repository for [Morphe Patches](https://github.com/MorpheApp/morphe-patches).

## ⏭️ What's next

The next page will guide you through creating a development environment for creating patches.

Continue: [👨‍💻 Setting up a development environment](2_1_setup.md)
