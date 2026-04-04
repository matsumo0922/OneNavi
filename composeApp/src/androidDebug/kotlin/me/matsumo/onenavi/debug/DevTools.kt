package me.matsumo.onenavi.debug

import android.content.Context
import android.util.Log

/**
 * デバッグ用ツールの初期化エントリポイント（debug ビルド版）。
 * FakeGpsServer を起動する。
 */
object DevTools {
    private const val TAG = "DevTools"

    fun initialize(context: Context) {
        Log.d(TAG, "DevTools.initialize() called")
        FakeGpsServer(context.applicationContext).start()
    }
}
