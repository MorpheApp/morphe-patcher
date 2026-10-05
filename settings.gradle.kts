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
