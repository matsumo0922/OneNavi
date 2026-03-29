plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.kmp.ios")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.core.repository"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:datasource"))
            implementation(project(":core:billing"))
            implementation(project(":core:resource"))

            implementation(libs.bundles.ktor)
            implementation(libs.kotlinx.datetime)
        }

        androidMain.dependencies {
            api(libs.ktor.okhttp)
        }

        iosMain.dependencies {
            api(libs.ktor.darwin)
        }
    }
}
