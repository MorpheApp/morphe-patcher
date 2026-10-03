rootProject.name = "morphe-patcher"

// Include Morphe forks of libraries as composite builds if they exist locally
mapOf(
    "ARSCLib" to "com.github.MorpheApp:ARSCLib",
).forEach { (libraryPath, libraryName) ->
    val libDir = file("../$libraryPath")
    if (libDir.exists()) {
        includeBuild(libDir) {
            dependencySubstitution {
                substitute(module(libraryName)).using(project(":"))
                substitute(module("com.github.MorpheApp:arsclib")).using(project(":"))
            }
        }
    }
}
