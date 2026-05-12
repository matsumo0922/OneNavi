plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

kotlin {
    sourceSets.all {
        languageSettings {
            languageVersion = "2.0"
        }
    }
}

dependencies {
    compileOnly(gradleKotlinDsl())
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation("org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.get()}")
    implementation(libs.secret.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.build.konfig.gradlePlugin)
    implementation(libs.gms.services)
}

gradlePlugin {
    plugins {
        register("AndroidApplicationPlugin") {
            id = "matsumo.primitive.android.application"
            implementationClass = "primitive.AndroidApplicationPlugin"
        }
        register("AndroidLibraryPlugin") {
            id = "matsumo.primitive.android.library"
            implementationClass = "primitive.AndroidLibraryPlugin"
        }
        register("KmpPlugin") {
            id = "matsumo.primitive.kmp.common"
            implementationClass = "primitive.KmpCommonPlugin"
        }
        register("KmpAndroidPlugin") {
            id = "matsumo.primitive.kmp.android"
            implementationClass = "primitive.KmpAndroidPlugin"
        }
        register("KmpAndroidCompose") {
            id = "matsumo.primitive.kmp.compose"
            implementationClass = "primitive.KmpComposePlugin"
        }
        register("DetektPlugin") {
            id = "matsumo.primitive.detekt"
            implementationClass = "primitive.DetektPlugin"
        }
    }
}
