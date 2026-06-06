plugins {
    alias(libs.plugins.android.application)
    // kotlin-gradle-plugin は kmp 経由でクラスパス上にあるため version 無しで適用する。
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "me.matsumo.onenavi.xposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.matsumo.onenavi.xposed"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.xposed.api)
}
