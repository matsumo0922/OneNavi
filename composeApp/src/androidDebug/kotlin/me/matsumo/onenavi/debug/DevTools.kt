package me.matsumo.onenavi.debug

import android.content.Context

/**
 * デバッグ用ツールの初期化エントリポイント（debug ビルド版）。
 * FakeGpsServer を起動する。
 */
object DevTools {
    fun initialize(context: Context) {
        FakeGpsServer(context.applicationContext).start()
    }
}
