plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.kmp.ios")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.core.navigation"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:model"))
        }

        androidMain.dependencies {
            implementation(libs.google.navigation)
            implementation(libs.play.services.location)
            implementation(libs.ktor.core)
            implementation(libs.ktor.okhttp)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation("me.matsumo.drive.supporter:drive-supporter-api")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
