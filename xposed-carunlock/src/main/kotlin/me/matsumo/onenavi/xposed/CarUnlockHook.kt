package me.matsumo.onenavi.xposed

import android.content.ComponentName
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Android Auto 本体(gearhead)に対し、OneNavi を parked-app として車 display に出すための hook。
 *
 * - ゲートA: `ltz`(GH.ParkedNativeAppCheck)の boolean 判定を true 固定し、parked-app 機能を有効化。
 * - ゲートC: `VirtualDeviceParams.Builder.setAllowedActivities(Set)` に OneNavi の CarActivity を注入し、
 *   VDM の activity policy(デフォルト block)で起動が弾かれないようにする。
 *
 * 走行維持(ゲートE)は別途 trace が必要なため本 hook には含めない(停車中 PoC = STEP1a 用)。
 * 走行ゲート解除を含むため個人利用・自己責任に限る。
 */
class CarUnlockHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != GEARHEAD_PACKAGE) {
            return
        }
        hookParkedNativeAppCheck(lpparam.classLoader)
        hookAllowedActivities(lpparam.classLoader)
    }

    /** ゲートA: `ltz` の boolean 判定メソッドを true 固定する。 */
    private fun hookParkedNativeAppCheck(classLoader: ClassLoader) {
        runCatching {
            val checkClass = classLoader.loadClass(PARKED_CHECK_CLASS)
            val booleanMethods = checkClass.declaredMethods.filter { method ->
                val isTargetName = method.name == "a" || method.name == "b"
                isTargetName && method.returnType == java.lang.Boolean.TYPE
            }
            for (method in booleanMethods) {
                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(true))
                log("hooked $PARKED_CHECK_CLASS.${method.name} -> true")
            }
            if (booleanMethods.isEmpty()) {
                log("WARN: no boolean a()/b() found on $PARKED_CHECK_CLASS")
            }
        }.onFailure {
            log("ltz hook failed: $it")
        }
    }

    /** ゲートC: VDM の許可 Activity 集合に OneNavi の CarActivity を注入する。 */
    private fun hookAllowedActivities(classLoader: ClassLoader) {
        runCatching {
            val builderClass = classLoader.loadClass(VDM_BUILDER_CLASS)
            val method = builderClass.getDeclaredMethod("setAllowedActivities", Set::class.java)
            XposedBridge.hookMethod(method, ActivityPolicyInjector)
            log("hooked setAllowedActivities")
        }.onFailure {
            log("setAllowedActivities hook failed: $it")
        }
    }

    /** `setAllowedActivities` 呼び出し直前に OneNavi の component を許可集合へ加える hook。 */
    private object ActivityPolicyInjector : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            @Suppress("UNCHECKED_CAST")
            val current = param.args.getOrNull(0) as? Set<ComponentName> ?: return
            val merged = HashSet(current)
            for (pkg in ONENAVI_PACKAGES) {
                merged.add(ComponentName(pkg, ONENAVI_CAR_ACTIVITY))
            }
            param.args[0] = merged
            log("injected OneNavi into allowedActivities (was ${current.size}, now ${merged.size})")
        }
    }

    private companion object {
        /** Android Auto 本体のパッケージ名。 */
        const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

        /** parked-app 可用性判定クラス(GH.ParkedNativeAppCheck の難読化名)。 */
        const val PARKED_CHECK_CLASS = "ltz"

        /** VDM の Activity policy を構築する framework クラス。 */
        const val VDM_BUILDER_CLASS = "android.companion.virtual.VirtualDeviceParams\$Builder"

        /** OneNavi の車用 Activity の完全修飾クラス名(applicationId 接尾辞に依存しない)。 */
        const val ONENAVI_CAR_ACTIVITY = "me.matsumo.onenavi.car.CarActivity"

        /** debug / release 両方の applicationId を許可対象にする。 */
        val ONENAVI_PACKAGES = listOf(
            "me.matsumo.onenavi",
            "me.matsumo.onenavi.debug",
        )

        fun log(message: String) {
            XposedBridge.log("[OneNaviCarUnlock] $message")
        }
    }
}
