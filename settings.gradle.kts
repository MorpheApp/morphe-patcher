rootProject.name = "morphe-patcher"

// Include Morphe forks of libraries as composite builds if they exist locally
mapOf(
    "ARSCLib" to "com.github.MorpheApp:arsclib",
).forEach { (libraryPath, libraryName) ->
    val libDir = file("../$libraryPath")
    if (libDir.exists()) {
        includeBuild(libDir) {
            dependencySubstitution {
                substitute(module(libraryName)).using(project(":"))
            }
        }
    }
}

// Include the Morphe fork of smali as a composite build if it exists locally
file("../smali").takeIf { it.exists() }?.let { smaliDir ->
    includeBuild(smaliDir) {
        dependencySubstitution {
            substitute(module("com.github.MorpheApp.smali:smali")).using(project(":smali"))
            substitute(module("com.github.MorpheApp.smali:smali-dexlib2")).using(project(":dexlib2"))
            substitute(module("com.github.MorpheApp.smali:smali-util")).using(project(":util"))
        }
    }
}
