plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.compose")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.feature.billing"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:model"))
            implementation(project(":core:repository"))
            implementation(project(":core:billing"))
            implementation(project(":core:ui"))
            implementation(project(":core:resource"))
        }
    }
}
