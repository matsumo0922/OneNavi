package me.matsumo.onenavi.debug

import android.content.Context

/**
 * デバッグ用ツールの初期化エントリポイント（release ビルド版）。
 * release ビルドでは何もしない。
 */
object DevTools {
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context) = Unit
}
