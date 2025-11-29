# 💉 Morphe Patcher

Morphe Patcher used to patch Android applications.

## ❓ About

Morphe Patcher is a library to patch Android applications.  
It powers [Morphe CLI](https://github.com/MorpheApp/morphe-cli),
[Morphe (Manager)](https://github.com/MorpheApp/morphe-manager),
and various third party patch managers.

## 💪 Features

Some of the features the Morphe Patcher provides are:

- 🔧 **Patch Dalvik VM bytecode**: Disassemble and assemble Dalvik bytecode
- 📦 **Patch APK resources**: Decode and build Android APK resources
- 📂 **Patch arbitrary APK files**: Read and write arbitrary files directly from and to APK files
- 🧩 **Write modular patches**: Extensive API to write modular patches that can patch Dalvik VM bytecode,
  APK resources and arbitrary APK files

## 🚀 How to get started

To use Morphe Patcher in your project, follow these steps:

1. [Add the repository](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry#using-a-published-package)
   to your project
2. Add the dependency to your project:

   ```kt
    dependencies {
        implementation("app.morphe:morphe-patcher:{$version}")
    }
   ```

## 📚 Everything else

### 📙 Contributing

Thank you for considering contributing to Morphe Patcher.
You can find the contribution guidelines [here](CONTRIBUTING.md).

### 🛠️ Building

To build Morphe Patcher,
you can follow the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation).

### 📃 Documentation

You can find the fundamentals of Morphe Patcher and how to create patches [here](https://github.com/MorpheApp/morphe-patcher/tree/main/docs).

## 📜 Licence

Morphe Patches are licensed under the [GNU GPL v3.0](https://www.gnu.org/licenses/gpl-3.0.html), with additional conditions under Section 7:

- **Name Restriction (7c):** The name **"Morphe"** may not be used for derivative works.  
  Derivatives must adopt a distinct identity unrelated to "Morphe."

See the [LICENSE](./LICENSE) file for full terms.
