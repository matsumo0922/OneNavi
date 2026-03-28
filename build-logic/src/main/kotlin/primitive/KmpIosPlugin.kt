package primitive

import org.gradle.api.Plugin
import org.gradle.api.Project

class KmpIosPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            kotlin {
                listOf(
                    iosX64(),
                    iosArm64(),
                    iosSimulatorArm64(),
                ).forEach { iosTarget ->
                    iosTarget.binaries.framework {
                        baseName = "composeApp"
                        isStatic = true
                    }
                }

                sourceSets.named { it.lowercase().startsWith("ios") }.configureEach {
                    languageSettings {
                        optIn("kotlinx.cinterop.ExperimentalForeignApi")
                    }
                }
            }
        }
    }
}
