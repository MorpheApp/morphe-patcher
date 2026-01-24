# 👨‍💻 Setting up a development environment

To start developing patches with Morphe Patcher, you must prepare a development environment.

## 📝 Prerequisites

- A Java IDE with Kotlin support, such as [Android Studio](https://developer.android.com/studio) or [IntelliJ IDEA](https://www.jetbrains.com/idea/)
- Knowledge of Java, [Kotlin](https://kotlinlang.org), and basic understanding of [Dalvik bytecode](https://source.android.com/docs/core/runtime/dalvik-bytecode)
- Android reverse engineering skills and tools such as [jadx](https://github.com/skylot/jadx)

## 🏃 Prepare the environment

Throughout the documentation, [Morphe Patches](https://github.com/MorpheApp/morphe-patches) will be used as an example project.

> [!NOTE]
> To start a fresh project, 
> you can use the [Morphe Patches template](https://github.com/MorpheApp/morphe-patches-template).

1. Clone the repository

   ```bash
   git clone https://github.com/MorpheApp/morphe-patches
   cd morphe-patches
   ```

2. Build the project

   ```bash
   ./gradlew buildAndroid
   ```

> [!NOTE]
> If the build fails due to authentication, you need to add github authentication authenticate to your command line environment.
> Create a PAT with the scope `read:packages` [here](https://github.com/settings/tokens/new?scopes=read:packages&description=Morphe) and add your token to ~/.gradle/gradle.properties
>
> Example `gradle.properties` file in your user folder (usually found in `~/.gradle/gradle.properties`)
> ```properties
> gpr.user = github_account_name
> gpr.key = gph_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
> ```
>
> Alternatively, instead of modifying your gradle file, your you can install [GitHub CLI](https://cli.github.com) and login using:
> ```bash
> gh auth
> ```

3. Open the project in your IDE

> [!TIP]
> It is a good idea to set up a complete development environment for Morphe, so that you can also test your patches
> by following the [Morphe documentation](https://github.com/MorpheApp/Morphe-documentation).

## ⏭️ What's next

The next page will go into details about a Morphe patch.

Continue: [🧩 Anatomy of a patch](2_2_patch_anatomy.md)
