@file:Suppress("UnstableApiUsage")

rootProject.name = "OneNavi"

fun optionalGradleOrEnvironmentProperty(name: String, environmentName: String): String? =
    providers
        .gradleProperty(name)
        .orElse(providers.environmentVariable(environmentName))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val extApiPath = optionalGradleOrEnvironmentProperty(
    name = "extApiPath",
    environmentName = "EXT_API_PATH",
)
val extApiRepositoryPath = optionalGradleOrEnvironmentProperty(
    name = "extApiRepositoryPath",
    environmentName = "EXT_API_REPOSITORY_PATH",
) ?: "${System.getProperty("user.home")}/.gradle/local-repos/ext-api"

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
            name = "externalApi"
            url = uri(extApiRepositoryPath)
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
include(":feature:map")
include(":feature:setting")
include(":feature:billing")

extApiPath?.let { path ->
    includeBuild(path)
}
