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

extra["marknotesSyncOwner"] = settingsDir
apply(from = file("gradle/marknotes-sync.settings.gradle"))

rootProject.name = "MarkNotes"

include(":core")
// Everything the libre app shares with MarkNotes Pro (MaxDac/MarkNotes), which includes :core and :app-shared.
include(":app-shared")
include(":app")
