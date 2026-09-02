# 💪 Advanced APIs

A handful of APIs are available to make patch development easier and more efficient.

## 📙 Overview

1. 🔍 Find immutable classes with `classDefBy(String)`
2. 🔄️️ Change immutable classes to mutable with `mutableClassDefBy(ClassDef)
3. 💾 Read and write resource files with `get(String, Boolean)` and `delete(String)`
4. 📃 Read and write DOM files using `document(String)` and  `document(InputStream)`
5. 📜 List the entries of the APK being patched with `listApkEntries(String)`

### 🧰 APIs

#### 🔍 `classDefBy(String)`

The `classDefBy(String)` function is an alternative to finding immutable classes
from a constant string or from a String field of a fingerprint match. 

```kt
execute {
    // Find the superclass of a fingerprint return type
    val superClassOfReturnType = classDefBy(match().originalMethod.returnType).superclass
}
```

#### 🔄️ `mutableDefClassBy(ClassDef)`

By default, the classes are immutable and they cannot be modified.
To make a class mutable use the `mutableClassDefBy(ClassDef)` function.
Accessing the property will replace the original class definition with the mutable copy,
thus allowing you to make changes to the class. Subsequent accesses will return the same mutable copy.

```kt
execute {
    // Find a class by the return type of a fingerprint
    val superClassOfReturnType = classDefBy(match().originalMethod.returnType).superclass

    val mutableClass = mutableClassDefBy(superClassOfReturnType)
    mutableClass.methods.add(Method())
}
```

<!-- TODO show getting a method/field using `getMethodCalled()` -->

#### 💾 `get(String, Boolean)` and `delete(String)`

The `get(String, Boolean)` function returns a `File` object that can be used to read and write resource files.

```kt
execute {
    val file = get("res/values/strings.xml")
    val content = file.readText()
    file.writeText(content)
}
```

The `delete` function can mark files for deletion when the APK is rebuilt.

```kt
execute {
    delete("res/values/strings.xml")
}
```

#### 📃 `document(String)`  and  `document(InputStream)`

The `document` function is used to read and write DOM files.

```kt
execute {
    document("res/values/strings.xml").use { document ->
        val element = doc.createElement("string").apply {
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

## 🎉 Afterword

Morphe Patcher is a powerful library to patch Android applications, offering a rich set of APIs to
develop patches that outlive app updates. Patches make up Morphe; without you, the community of
patch developers, Morphe would not be what it is today. We hope that this documentation has been
helpful to you and are excited to see what you will create with Morphe Patcher.
