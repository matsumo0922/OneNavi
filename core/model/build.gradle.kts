plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.core.model"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(project(":core:common"))
            api(project(":core:resource"))

            implementation(libs.ktor.core)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
