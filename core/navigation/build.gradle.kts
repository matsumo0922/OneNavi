plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.matsumo.onenavi.core.navigation"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            val extNavApiVersion = providers
                .gradleProperty("extNavApiVersion")
                .orElse(providers.environmentVariable("EXT_NAV_API_VERSION"))
                .orElse("latest.release")

            implementation(project(":core:common"))
            implementation(project(":core:model"))
            implementation(project(":core:datasource"))
            implementation(project(":core:repository"))

            implementation(libs.ktor.core)
            implementation(libs.ktor.okhttp)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation("me.matsumo.drive.supporter:drive-supporter-api:${extNavApiVersion.get()}")
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
