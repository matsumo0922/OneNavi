package me.matsumo.onenavi.xposed

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 【実験専用】Android Auto の新 SCVH relay 合成ホスト `ProjectionRootActivity`(CAR.WM.ProjRootAct)へ
 * 割り込み、native(parked-app)経路では原理的に不可能だった「Coolwalk split 同居」が projection 経路で
 * 成立するかを最小コストで判定する probe(docs/spec/32 経路B)。
 *
 * 仮説: gearhead の新 relay 合成は `ProjectionRootActivity` の rootLayout(`FrameLayout`、難読化 field `c`)
 * に各アプリの `SurfaceView` を載せて1枚の HU フレームを組む。ここへ自前 View を `addView` できれば、
 * `createOrUpdateViews`(Compose 難読化関数)を hook せずとも描画(HU に出る)と入力(本物 MotionEvent)が
 * 割り込みで取れる。取れれば次段で赤箱を OneNavi プロセスの `SurfaceControlViewHost` 由来 `SurfacePackage`
 * に差し替え、`OneNaviApp()` を split 同居させる。
 *
 * 新旧経路の分岐(2026-06-07 実機+smali 解析で確定): `igv`(projection マネージャ)の line 1888-1940 で
 * `acsl.a()`(=`acsm.a()`=Phenotype `StratoFeature__enabled`, default false)が true かつ `SDK_INT >= 36`
 * のとき `jcl`(新 relay host)を生成し `ProjectionRootActivity` を projected display へ起動する。false だと
 * 旧 `GhostActivity` 経路に落ちる。実機は Android 16(API36)で SDK 条件は満たすが、Phenotype 未配信で
 * `StratoFeature__enabled=false` のため旧経路だった。
 *
 * よって本 probe は2段で構成する:
 * - STEP P1(Strato 有効化): `acsm.a()` を true 固定 → `igv` が `jcl` 経由で `ProjectionRootActivity` を
 *   起動。`onCreate` 発火が成立の証左。(`acsk.c()` 経由ではなく `igv` は `acsl.a()`=`acsm.a()` を直接
 *   呼ぶため、hook 対象は実装本体 `acsm.a()`。)
 * - STEP P2(注入): `onCreate` 後に rootLayout へ赤箱を `addView` し、HU 表示とタッチ到達を確認。
 *
 * ⚠️ `acsm.a()` true 固定は gearhead 全体を新 relay 経路へ切り替えるため、Maps 等の既存 projected
 * アプリの描画に影響しうる。kill-switch は LSPosed モジュール ON/OFF。個人利用・実験用。確認後に削除する。
 */
internal object ProjectionSplitProbe {

    /** projection 合成ホストの完全修飾名。版に依らず非難読化で安定(D5 アンカー)。 */
    private const val PROJECTION_ROOT_ACTIVITY = "com.google.android.apps.auto.carservice.car.window.composition.ProjectionRootActivity"

    /** Strato feature flag の実装クラス(難読化名)。`a()` が Phenotype `StratoFeature__enabled`(default false)を返す。 */
    private const val STRATO_FLAG_IMPL = "acsm"

    /** 割り込ませる赤箱の一辺(px)。projected display 上で視認できる程度の固定値。 */
    private const val PROBE_BOX_SIZE_PX = 500

    /** logcat 抽出用タグ。 */
    private const val TAG = "OneNaviSplitProbe"

    /**
     * Strato 有効化(STEP P1)と rootLayout 割り込み(STEP P2)の hook をまとめて仕掛ける。
     * gearhead の全プロセスで呼ばれる(install 自体は package 単位で走る)。
     */
    fun install(classLoader: ClassLoader) {
        hookStratoFeature(classLoader)
        hookProjectionRootActivity(classLoader)
    }

    /**
     * `acsm.a()`(Strato feature flag `StratoFeature__enabled` の実装本体)を true 固定し、新 SCVH relay
     * 合成経路を有効化する。これにより `igv` が `jcl` を生成し `ProjectionRootActivity` を起動する(想定)。
     */
    private fun hookStratoFeature(classLoader: ClassLoader) {
        runCatching {
            val flagClass = classLoader.loadClass(STRATO_FLAG_IMPL)
            val flagMethod = flagClass.getDeclaredMethod("a")
            XposedBridge.hookMethod(flagMethod, ForceStratoEnabled)
            log("installed: hooked $STRATO_FLAG_IMPL.a() -> true (StratoFeature__enabled)")
        }.onFailure {
            log("strato hook FAILED: $it")
        }
    }

    /**
     * `ProjectionRootActivity.onCreate` を hook し、合成フレームの rootLayout へ赤箱を割り込ませる。
     * onCreate の発火自体が「Strato 有効化で新 SCVH 合成経路が起動した」ことの証左(STEP P1 達成)。
     */
    private fun hookProjectionRootActivity(classLoader: ClassLoader) {
        runCatching {
            val activityClass = classLoader.loadClass(PROJECTION_ROOT_ACTIVITY)
            val onCreateMethod = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)
            XposedBridge.hookMethod(onCreateMethod, OnCreateProbe)
            log("installed: hooked $PROJECTION_ROOT_ACTIVITY.onCreate")
        }.onFailure {
            log("ProjRootAct hook FAILED: $it")
        }
    }

    /** `acsm.a()` を true(Strato 有効)固定する hook。頻繁に呼ばれるためログは出さない。 */
    private object ForceStratoEnabled : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = true
        }
    }

    /** onCreate 完了後に rootLayout(FrameLayout)へ赤箱を載せる hook。 */
    private object OnCreateProbe : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            logActivityDisplay(activity)
            val rootLayout = findRootFrameLayout(activity)
            if (rootLayout == null) {
                log("WARN: rootLayout(FrameLayout) を ${activity.javaClass.name} から取得できず")
                return
            }
            attachProbeBox(rootLayout)
        }
    }

    /** どの projected display に合成 Activity が出たかを記録する(STEP P1 の確証)。 */
    private fun logActivityDisplay(activity: Activity) {
        val display = runCatching { activity.display }.getOrNull()
        log("ProjRootAct.onCreate fired: displayId=${display?.displayId} name=${display?.name}")
    }

    /** `ProjectionRootActivity` の唯一の FrameLayout instance field(難読化名 `c`)を型一致で取得する。 */
    private fun findRootFrameLayout(activity: Activity): FrameLayout? {
        val frameLayoutField = activity.javaClass.declaredFields.firstOrNull { field ->
            FrameLayout::class.java.isAssignableFrom(field.type)
        } ?: return null
        frameLayoutField.isAccessible = true
        return frameLayoutField.get(activity) as? FrameLayout
    }

    /** 合成フレームの最前面に赤箱を載せ、タッチ到達(本物 MotionEvent)を観測する。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachProbeBox(rootLayout: FrameLayout) {
        val probeBox = View(rootLayout.context).apply {
            setBackgroundColor(Color.RED)
            setOnTouchListener(ProbeTouchLogger)
        }
        val layoutParams = FrameLayout.LayoutParams(
            PROBE_BOX_SIZE_PX,
            PROBE_BOX_SIZE_PX,
            Gravity.CENTER,
        )
        rootLayout.addView(probeBox, layoutParams)
        probeBox.bringToFront()
        log("probe box attached: ${PROBE_BOX_SIZE_PX}px center, rootChildren=${rootLayout.childCount}")
    }

    /** 赤箱に届いた MotionEvent をログする(入力が gearhead Window 経由で子 View へ来るかの確証)。 */
    private object ProbeTouchLogger : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            log("PROBE TOUCH: action=${event.actionMasked} x=${event.x} y=${event.y}")
            return false
        }
    }

    /** XposedBridge と logcat の両方へ出す。 */
    private fun log(message: String) {
        XposedBridge.log("[$TAG] $message")
        Log.i(TAG, message)
    }
}
