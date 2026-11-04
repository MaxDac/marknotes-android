pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MarkNotes"

include(":core")
// Everything the libre app shares with MarkNotes Plus (MaxDac/MarkNotes), which includes :core and :app-shared.
include(":app-shared")
include(":app")
