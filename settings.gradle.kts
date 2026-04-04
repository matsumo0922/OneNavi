@file:Suppress("UnstableApiUsage")

import java.util.*


rootProject.name = "OneNavi"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                val localProperties = Properties().apply {
                    file("local.properties").also {
                        if (it.exists()) load(it.inputStream())
                    }
                }

                username = "mapbox"
                password = localProperties.getProperty("MAPBOX_DOWNLOADS_TOKEN").orEmpty()
            }
        }
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
include(":feature:setting")
include(":feature:billing")
