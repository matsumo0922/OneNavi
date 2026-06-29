@file:Suppress("UnusedPrivateProperty")

import com.android.build.api.variant.ResValue
import com.codingfeline.buildkonfig.compiler.FieldSpec
import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.application")
    id("matsumo.primitive.kmp.compose")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
}

val localProperties = Properties().apply {
    project.rootDir.resolve("local.properties").also {
        if (it.exists()) load(it.inputStream())
    }
}

val admobTestAppId = "ca-app-pub-0000000000000000~0000000000"
val bannerAdTestId = "ca-app-pub-3940256099942544/6300978111"
val nativeAdTestId = "ca-app-pub-3940256099942544/2247696110"
val rewardAdTestId = "ca-app-pub-3940256099942544/5224354917"

android {
    namespace = "me.matsumo.onenavi"

    signingConfigs {
        getByName("debug") {
            storeFile = file("${project.rootDir}/gradle/keystore/debug.keystore")
        }
        create("release") {
            storeFile = file("${project.rootDir}/gradle/keystore/release.keystore")
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
        }
        create("billing") {
            storeFile = file("${project.rootDir}/gradle/keystore/release.keystore")
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            versionNameSuffix = ".D"
            applicationIdSuffix = ".debug"
        }
        create("billing") {
            signingConfig = signingConfigs.getByName("billing")
            isDebuggable = true
            matchingFallbacks.add("debug")
        }
    }

    androidComponents {
        onVariants {
            val appName = when (it.buildType) {
                "debug" -> "OneNavi Debug"
                "billing" -> "OneNavi Billing"
                else -> null
            }

            it.manifestPlaceholders.apply {
                put("ADMOB_ANDROID_APP_ID", localProperties.getProperty("ADMOB_ANDROID_APP_ID") ?: admobTestAppId)
                put("GOOGLE_API_KEY", localProperties.getProperty("GOOGLE_API_KEY") ?: System.getenv("GOOGLE_API_KEY").orEmpty())
            }

            if (appName != null) {
                it.resValues.apply {
                    put(it.makeResValueKey("string", "app_name"), ResValue(appName, null))
                }
            }

            if (it.buildType == "release") {
                it.packaging.resources.excludes.add("META-INF/**")
            }
        }
    }
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:model"))
            implementation(project(":core:datasource"))
            implementation(project(":core:repository"))
            implementation(project(":core:billing"))
            implementation(project(":core:navigation"))
            implementation(project(":core:ui"))
            implementation(project(":core:resource"))

            implementation(project(":feature:home"))
            implementation(project(":feature:map"))
            implementation(project(":feature:setting"))
            implementation(project(":feature:billing"))

            implementation(libs.androidx.core)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.play.review)
            implementation(libs.play.update)
            implementation(libs.koin.androidx.startup)
            implementation(libs.accompanist.permissions)
            implementation(libs.androidx.car.app)
            implementation(libs.androidx.car.app.projected)
            implementation(libs.play.services.maps)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

buildkonfig {
    packageName = "me.matsumo.onenavi"

    defaultConfigs {
        fun setField(name: String, defaultValue: String = "") {
            val envValue = System.getenv(name)
            val propertyValue = localProperties.getProperty(name)

            buildConfigField(FieldSpec.Type.STRING, name, propertyValue ?: envValue ?: defaultValue)
        }

        setField("VERSION_NAME", libs.versions.versionName.get())
        setField("VERSION_CODE", libs.versions.versionCode.get())

        setField("DEVELOPER_PIN", "1234")
        setField("GOOGLE_API_KEY")
        setField("GOOGLE_CLOUD_TTS_API_KEY")
        setField("SERVER_ROUTE_BASE_URL")
        setField("SERVER_ROUTE_FORCE_EXISTING_SOURCE", "true")
        setField("SERVER_ROUTE_CF_ACCESS_CLIENT_ID_HEADER")
        setField("SERVER_ROUTE_CF_ACCESS_CLIENT_SECRET_HEADER")
        setField("PURCHASE_ANDROID_API_KEY")

        setField("ADMOB_ANDROID_APP_ID", admobTestAppId)
        setField("ADMOB_ANDROID_BANNER_AD_UNIT_ID", admobTestAppId)
        setField("ADMOB_ANDROID_INTERSTITIAL_AD_UNIT_ID", bannerAdTestId)
        setField("ADMOB_ANDROID_NATIVE_AD_UNIT_ID", nativeAdTestId)
        setField("ADMOB_ANDROID_REWARDED_AD_UNIT_ID", rewardAdTestId)

        setField("APPLOVIN_SDK_KEY")

        setField("EXT_NAV_LOGIN_ID")
        setField("EXT_NAV_PASSWORD")
    }
}
