package me.matsumo.onenavi.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** 現在の Compose ツリーから Android platform Dialog を安全に表示できるかどうか。 */
val LocalSupportsPlatformDialogWindow = staticCompositionLocalOf { true }
