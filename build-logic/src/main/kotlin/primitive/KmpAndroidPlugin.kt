package primitive

import me.matsumo.onenavi.android
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@Suppress("unused")
class KmpAndroidPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            kotlin {
                androidTarget {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_17)
                    }
                }
            }

            android {
                sourceSets {
                    getByName("main") {
                        manifest.srcFile("src/androidMain/AndroidManifest.xml")
                        res.srcDirs("src/androidMain/res")
                    }
                }
            }
        }
    }
}
