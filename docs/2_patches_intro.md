# 🧩 Introduction to Morphe Patches

Learn the basic concepts of Morphe Patcher and how to create patches.

## 📙 Fundamentals

A patch is a piece of code that modifies an Android application.  
There are multiple types of patches. Each type can modify a different part of the APK, such as the Dalvik VM bytecode, 
the APK resources, or arbitrary files in the APK:

- A `BytecodePatch` modifies the Dalvik VM bytecode
- A `ResourcePatch` modifies (decoded) resources
- A `RawResourcePatch` modifies arbitrary files

Each patch can declare a set of dependencies on other patches. Morphe Patcher will first execute dependencies
before executing the patch itself. This way, multiple patches can work together for abstract purposes in a modular way.

The `execute` function is the entry point for a patch. It is called by Morphe Patcher when the patch is executed.
The `execute` function receives an instance of a context object that provides access to the APK.
The patch can use this context to modify the APK.

Each type of context provides different APIs to modify the APK. For example, the `BytecodePatchContext` provides APIs
to modify the Dalvik VM bytecode, while the `ResourcePatchContext` provides APIs to modify resources.

The difference between `ResourcePatch` and `RawResourcePatch` is that Morphe Patcher will decode the resources
if it is supplied a `ResourcePatch` for execution or if any patch depends on a `ResourcePatch`
and will not decode the resources before executing `RawResourcePatch`.
Both, `ResourcePatch` and `RawResourcePatch` can modify arbitrary files in the APK,
whereas only `ResourcePatch` can modify decoded resources. The choice of which type to use depends on the use case.
Decoding and building resources is a time- and resource-consuming,
so if the patch does not need to modify decoded resources, it is better to use `RawResourcePatch` or `BytecodePatch`.

Example of patches:

```kt
@Surpress("unused")
val bytecodePatch = bytecodePatch {
    execute { 
        // More about this on the next page of the documentation.
    }
}

@Surpress("unused")
val rawResourcePatch = rawResourcePatch {
    execute {
        // More about this on the next page of the documentation.
    }
}

@Surpress("unused")
val resourcePatch = resourcePatch {
    execute {
        // More about this on the next page of the documentation.
    }
}
```

> [!TIP]
> To see real-world examples of patches,
> check out the repository for [Morphe Patches](https://github.com/MorpheApp/morphe-patches).

## ⏭️ Whats next

The next page will guide you through creating a development environment for creating patches.

Continue: [👨‍💻 Setting up a development environment](2_1_setup.md)
