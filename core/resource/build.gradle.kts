plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.compose")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.kmp.ios")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.core.resource"
}

compose.resources {
    publicResClass = true
    packageOfResClass = "me.matsumo.onenavi.core.resource"
    generateResClass = always
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.resources)
        }
    }
}
