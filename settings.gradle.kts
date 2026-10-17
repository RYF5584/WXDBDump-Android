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
        // Xposed API, compile-only and needed at build time only
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "WXDB-Dump-Android"

include(":payload")
include(":xposed")
include(":app")
