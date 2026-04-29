@file:Suppress("UnstableApiUsage")

rootProject.name = "OneNavi"

pluginManagement {
    includeBuild("build-logic")
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
        maven("https://storage.googleapis.com/r8-releases/raw")
        maven("https://jitpack.io")
    }
}

include(":composeApp")
include(":core:common")
include(":core:ui")
include(":core:datasource")
include(":core:repository")
include(":core:resource")
include(":core:model")
include(":core:navigation")
include(":core:billing")
include(":feature:home")
include(":feature:map")
include(":feature:setting")
include(":feature:billing")

includeBuild("drive-supporter-api")
