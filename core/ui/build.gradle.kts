plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.compose")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.core.ui"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:repository"))
            implementation(project(":core:datasource"))
            implementation(project(":core:resource"))

            api(libs.bundles.ui.common)
            api(libs.bundles.compose)
            api(libs.bundles.calf)
            api(libs.bundles.ui.android)

            api(libs.adaptive)
            api(libs.adaptive.layout)
            api(libs.lexilabs.basic.ads)
            api(libs.play.service.ads)

            api(libs.compose.ui.tooling)
        }
    }
}
