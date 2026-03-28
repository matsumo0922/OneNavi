package primitive

import me.matsumo.onenavi.configureDetekt
import me.matsumo.onenavi.library
import me.matsumo.onenavi.libs
import me.matsumo.onenavi.plugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class DetektPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.plugin("detekt").pluginId)

            configureDetekt()

            dependencies {
                "detektPlugins"(libs.library("detekt-formatting"))
                "detektPlugins"(libs.library("twitter-compose-rule"))
            }
        }
    }
}
