plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
    alias(libs.plugins.ksp)
}

android {
    namespace = "me.matsumo.onenavi.core.datasource"
}

kotlin {
    sourceSets {
        androidMain.dependencies {
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
            implementation(libs.kotlinx.coroutines.play.services)

            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.proto)
            implementation(libs.ktor.okhttp)
            implementation(libs.play.services.maps)
            implementation(libs.play.services.location)
            implementation("com.google.android.libraries.places:places:${libs.versions.googlePlaces.get()}") {
                exclude(group = "com.google.android.gms", module = "play-services-maps")
            }
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
}
