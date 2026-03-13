rootProject.name = "morphe-patcher"

// Include Morphe forks of libraries as composite builds if they exist locally
mapOf(
    "ARSCLib" to "com.github.MorpheApp:arsclib",
    "multidexlib2" to "app.morphe:multidexlib",
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
