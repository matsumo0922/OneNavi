package me.matsumo.onenavi.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey

val LocalNavBackStack = staticCompositionLocalOf<MutableList<NavKey>> {
    error("No NavBackStack provided")
}
