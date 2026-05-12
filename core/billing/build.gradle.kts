plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.core.billing"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:model"))

            api(libs.bundles.purchase)
        }
    }
}
