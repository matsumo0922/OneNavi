package me.matsumo.onenavi.core.ui.screen

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

@Immutable
@Serializable
sealed interface Destination : NavKey {
    @Serializable
    data object Home : Destination

    @Serializable
    data class Download(val url: String) : Destination

    @Serializable
    data class Paywall(val source: String) : Destination

    @Serializable
    sealed interface Setting : Destination {
        @Serializable
        data object Root : Setting

        @Serializable
        data object License : Setting
    }

    companion object {
        val config = SavedStateConfiguration {
            serializersModule = SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(Home::class, Home.serializer())
                    subclass(Download::class, Download.serializer())
                    subclass(Paywall::class, Paywall.serializer())
                    subclass(Setting.Root::class, Setting.Root.serializer())
                    subclass(Setting.License::class, Setting.License.serializer())
                }
            }
        }
    }
}
