# 📜 Project structure and conventions

Over time, a specific project structure and conventions have been established.

## 📁 File structure

Patches are organized in a specific way. The file structure looks as follows:

```text
📦your.patches.app.category
 ├ 🔍Fingerprints.kt
 └ 🧩SomePatch.kt
```

> [!NOTE]
> Moving fingerprints to a separate file isn't strictly necessary, but it helps the organization when a patch uses multiple fingerprints.

## 📙 Conventions

- 🔥 Name a patch after what it does. For example, if a patch removes ads, name it `Remove ads`.
  If a patch changes the color of a button, name it `Change button color`
- 🔥 Write the patch description in the third person, present tense, and end it with a period.
  If a patch removes ads, the description can be omitted because of redundancy,
  but if a patch changes the color of a button, the description can be _Changes the color of the resume button to red._
- 🔥 Name fingerprints with a best guess of what the target method does.
- 🔥 Write patches with modularity and reusability in mind. Patches can depend on each other,
  so it is important to write patches in a way that can be used in different contexts.
- 🔥🔥 Keep patches as minimal as possible. This reduces the risk of failing patches.
  Instead of involving many abstract changes in one patch or writing entire methods or classes in a patch,
  you can write code in extensions. An extension is a precompiled DEX file that is merged into the patched app 
  before this patch is executed.
  Patches can then reference methods and classes from extensions.
  A real-world example of extensions can be found in the [Morphe Patches](https://github.com/MorpheApp/morphe-patches) repository
- 🔥🔥🔥 Do not overload a fingerprint with information about a method that's likely to change.
  In the example of an obfuscated method, it's better to fingerprint the method by its return type
  and parameters rather than its name because the name is likely to change. An intelligent selection
  of an instructions filters in a method can result in a strong fingerprint dynamic to app updates.
- 🔥🔥🔥 Document your patches. Patches are abstract, so it is important to document parts of the code
  that are not self-explanatory. For example, explain why and how a certain method is patched or 
  large blocks of instructions that are modified or added to a method.

## ⏭️ What's next

The next page discusses useful APIs for patch development.

Continue: [💪 Advanced APIs](4_apis.md)
