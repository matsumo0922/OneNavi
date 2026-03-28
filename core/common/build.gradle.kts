plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.kmp.ios")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.core.common"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project.dependencies.platform(libs.koin.bom))

            api(libs.bundles.infra)
            api(libs.bundles.koin)
        }

        androidMain.dependencies {
            api(project.dependencies.platform(libs.firebase.bom))

            api(libs.bundles.firebase)
            api(libs.koin.android)
        }
    }
}
