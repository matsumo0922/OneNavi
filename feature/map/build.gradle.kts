plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.compose")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
}

android {
    namespace = "me.matsumo.onenavi.feature.map"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:model"))
            implementation(project(":core:navigation"))
            implementation(project(":core:repository"))
            implementation(project(":core:datasource"))
            implementation(project(":core:ui"))
            implementation(project(":core:resource"))

            implementation(libs.kotlinx.datetime)
            implementation("com.google.maps.android:maps-compose:${libs.versions.googleMapsCompose.get()}") {
                exclude(group = "com.google.android.gms", module = "play-services-maps")
            }
            implementation(libs.google.navigation)
            implementation(libs.play.services.location)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
