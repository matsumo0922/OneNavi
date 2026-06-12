plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.core.common"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            api(project.dependencies.platform(libs.koin.bom))
            api(project.dependencies.platform(libs.firebase.bom))

            api(libs.compose.runtime)
            api(libs.bundles.infra)
            api(libs.bundles.koin)
            api(libs.bundles.firebase)
            api(libs.koin.android)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
