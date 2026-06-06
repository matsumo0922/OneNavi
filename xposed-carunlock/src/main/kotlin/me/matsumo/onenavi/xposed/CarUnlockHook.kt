package me.matsumo.onenavi.xposed

import android.content.ComponentName
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Android Auto 本体(gearhead)に対し、OneNavi を parked-app として車 display に出すための hook。
 *
 * - ゲートA: `ltz`(GH.ParkedNativeAppCheck)の boolean 判定(`a()`/`b()`)を true 固定。
 * - ゲートC: framework API `VirtualDeviceParams.Builder.setAllowedActivities(Set)` に OneNavi の
 *   CarActivity component を注入し、VDM の activity policy(デフォルト block)を回避。
 *
 * 走行維持(ゲートE)は別途 trace が必要なため含めない(停車中 PoC = STEP1a 用)。
 * 個人利用・自己責任に限る。診断のため XposedBridge と logcat の両方へログする。
 */
class CarUnlockHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != GEARHEAD_PACKAGE) {
            return
        }
        log("loaded into ${lpparam.packageName} (processName=${lpparam.processName})")
        hookParkedNativeAppCheck(lpparam.classLoader)
        hookPackageListOptIn(lpparam.classLoader)
        hookAllowedActivities(lpparam.classLoader)
    }

    /**
     * ゲートB(opt-in): `kgj.e(list, pkg)`(package が CradleFeature の各 package list に在るか)を
     * OneNavi に対して true 固定し、`app_package_list` / `app_launcher_package_list` 等(実機は Phenotype
     * 未配信で空)を一括で opt-in 扱いにする。
     */
    private fun hookPackageListOptIn(classLoader: ClassLoader) {
        runCatching {
            val helperClass = classLoader.loadClass(PACKAGE_LIST_HELPER_CLASS)
            val candidates = helperClass.declaredMethods.filter { method ->
                val isE = method.name == "e" && method.returnType == java.lang.Boolean.TYPE
                val secondIsString = method.parameterTypes.size == 2 && method.parameterTypes[1] == String::class.java
                isE && secondIsString
            }
            for (method in candidates) {
                XposedBridge.hookMethod(method, PackageListOptIn)
                log("hooked $PACKAGE_LIST_HELPER_CLASS.e(list, String)")
            }
            if (candidates.isEmpty()) {
                log("WARN: no e(list, String) on $PACKAGE_LIST_HELPER_CLASS")
            }
        }.onFailure {
            log("kgj.e hook FAILED: $it")
        }
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
                XposedBridge.hookMethod(method, ForceTrue)
                log("hooked $PARKED_CHECK_CLASS.${method.name}(${method.parameterTypes.size} args) -> true")
            }
            if (booleanMethods.isEmpty()) {
                log("WARN: no boolean a()/b() on $PARKED_CHECK_CLASS")
            }
        }.onFailure {
            log("ltz hook FAILED: $it")
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
            log("setAllowedActivities hook FAILED: $it")
        }
    }

    /** `ltz` の判定を true 固定しつつ、呼ばれたことをログする hook。 */
    private object ForceTrue : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = true
            log("ForceTrue: ${param.method.name} -> true")
        }
    }

    /** `kgj.e(list, pkg)` を OneNavi の package に対してのみ true 固定する hook(他 package は素通し)。 */
    private object PackageListOptIn : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val pkg = param.args.getOrNull(1) as? String ?: return
            if (pkg in ONENAVI_PACKAGES) {
                param.result = true
                log("PackageListOptIn: $pkg -> true")
            }
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
            log("injected OneNavi into allowedActivities (was ${current.size}, now ${merged.size}): $current")
        }
    }

    private companion object {
        /** Android Auto 本体のパッケージ名。 */
        const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

        /** parked-app 可用性判定クラス(GH.ParkedNativeAppCheck の難読化名)。 */
        const val PARKED_CHECK_CLASS = "ltz"

        /** package list 照合ヘルパ(`kgj.e(list, pkg)` の難読化名)。 */
        const val PACKAGE_LIST_HELPER_CLASS = "kgj"

        /** VDM の Activity policy を構築する framework クラス。 */
        const val VDM_BUILDER_CLASS = "android.companion.virtual.VirtualDeviceParams\$Builder"

        /** OneNavi の車用 Activity の完全修飾クラス名(applicationId 接尾辞に依存しない)。 */
        const val ONENAVI_CAR_ACTIVITY = "me.matsumo.onenavi.car.CarActivity"

        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarUnlock"

        /** debug / release 両方の applicationId を許可対象にする。 */
        val ONENAVI_PACKAGES = listOf(
            "me.matsumo.onenavi",
            "me.matsumo.onenavi.debug",
        )

        /** XposedBridge と logcat の両方へ出す(logcat 可視化のため)。 */
        fun log(message: String) {
            XposedBridge.log("[$TAG] $message")
            Log.i(TAG, message)
        }
    }
}
