plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.kmp.ios")
    id("matsumo.primitive.detekt")
    alias(libs.plugins.ksp)
}

android {
    namespace = "me.matsumo.onenavi.core.datasource"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.proto)
            implementation(libs.ktor.okhttp)
            implementation(libs.google.places)
            implementation(libs.mapbox.android)
        }

        iosMain.dependencies {
            implementation(libs.ktor.darwin)
        }

        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:model"))
            implementation(project(":core:resource"))

            api(libs.bundles.filekit)
            api(libs.androidx.datastore.preferences)

            implementation(libs.kotlinx.datetime)
            implementation(libs.gifkt)
            implementation(libs.kmp.zip)
            implementation(libs.ktor.core)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)

            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    listOf("kspAndroid", "kspIosX64", "kspIosArm64", "kspIosSimulatorArm64").forEach { target ->
        add(target, libs.androidx.room.compiler)
    }
}
