package me.matsumo.onenavi.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import me.matsumo.onenavi.core.common.car.OneNaviDisplaySurface

/** 現在の Compose ツリーを表示している OneNavi の表示面。 */
val LocalOneNaviDisplaySurface = staticCompositionLocalOf { OneNaviDisplaySurface.Phone }
