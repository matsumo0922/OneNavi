package me.matsumo.onenavi.core.navigation

import android.os.Handler
import android.os.Looper
import io.github.aakira.napier.Napier
import me.matsumo.onenavi.core.navigation.NavigationViewBridgeDiagnostics.inspectRenderState
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * `NavigationView` 内部 SDK の async render pipeline (`bo.ac.f` / `bo.ac.g` /
 * `bo.ac.h`) に logging proxy を被せて、background-thread での silent な例外を
 * 可視化する。lower seam (`bb.e.a(bv.h)`) は同期 return するが、実際の polyline
 * 構築は executor 上で進むため、そこでの例外を拾わない限り「何が落ちたか」が
 * 見えない。
 *
 * 加えて [inspectRenderState] で `bo.ac.U` (`bo.aa`) を読み取り、`bq.ag` 配列の
 * 実体サイズを後追いで記録する。0 なら build pipeline で死亡、>0 なら render
 * pipeline で死亡、と切り分けられる。
 */
internal object NavigationViewBridgeDiagnostics {

    private const val TAG = "NavViewDiag"

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * `bo.m.e` (= `bo.ac`) を取り出し、その `f` (Executor) と `g` (zd.bj) と
     * `h.a` (in.ae 内部 Executor) を logging proxy に差し替える。
     * 既に proxy 化済みなら no-op。
     */
    fun installExecutorLogging(overlayController: Any) {
        runCatching {
            val ac = readField(overlayController, "e")
                ?: error("bo.m.e is null — cannot reach bo.ac")

            wrapPlainExecutorField(ac, fieldName = "f", label = "ac.f")
            wrapBjField(ac, fieldName = "g", label = "ac.g")

            // bo.ac.h = in.ae(bjVar) — in.ae は final class。内部 `a` (Executor) を差し替える。
            val h = readField(ac, "h")
            if (h != null) {
                wrapPlainExecutorField(h, fieldName = "a", label = "ac.h.a")
            }

            Napier.i(tag = TAG) { "[NAVDBG] diag.executors wrapped" }
        }.onFailure { error ->
            Napier.w(error, tag = TAG) { "[NAVDBG] diag.installExecutorLogging failed" }
        }
    }

    /**
     * route polyline の `ov.ad` (= `bq.e.k[*].psAu.b.a`) に対して `l()` を強制呼出し、
     * visibility flag (`ov.a.d`) を立てる。
     *
     * `bq.e.b()` の polyline path (line 466) は `adVarO2.l()` を呼ばないが、
     * waypoint path (line 540) は `adVarO3.l()` を呼んでいる。`ov.ad` ctor は
     * `super(..., false)` で呼ばれるので `ov.a.a` も常に false で、`v()` も飛ばされる。
     * 結果として polyline は GL に登録されているが visible flag が立たない。
     *
     * 1700ms 後 (= inspectRenderState の 200ms 後) に bo.aa の bq.e.k を全部走査して
     * 各 ov.ad に対して `l()` を invoke する。これで描画が出るかを実機で確認する。
     */
    fun forcePolylineVisibility(overlayController: Any) {
        mainHandler.postDelayed(
            {
                runCatching {
                    val ac = readField(overlayController, "e") ?: return@runCatching
                    val tLock = readField(ac, "T") ?: ac
                    synchronized(tLock) {
                        val aa = readField(ac, "U") ?: return@synchronized
                        val counters = mutableMapOf<String, Int>()

                        // bq.e の k (ETA callout / start marker / turn arrow を含む)
                        val bqE = readField(aa, "a")
                        if (bqE != null) {
                            invokeLOnMapAwValues(bqE, "k", "bqE.k", counters)
                            invokeLOnIterableAw(bqE, "o", "bqE.o", counters)
                            invokeLOnIterableAw(bqE, "n", "bqE.n", counters)
                            invokeLOnIterableAw(bqE, "s", "bqE.s", counters)
                            // bq.e.r (single ps.aw start point callout)
                            val bqER = readField(bqE, "r")
                            if (bqER != null) {
                                invokeLOnAw(bqER, "bqE.r", counters)
                            }
                        }

                        // bo.aa.b = er<bq.ag>。各 bq.ag の K / m を走査して polyline decoration を活性化。
                        val agList = readField(aa, "b") as? List<*>
                        agList?.forEachIndexed { index, ag ->
                            if (ag == null) return@forEachIndexed
                            invokeLOnMapAwValues(ag, "K", "ag[$index].K", counters)
                            invokeLOnIterableAw(ag, "m", "ag[$index].m", counters)
                            // bq.ag.h = bq.i の中身も覗く
                            val bqI = readField(ag, "h")
                            if (bqI != null) {
                                Napier.i(tag = TAG) {
                                    "[NAVDBG] diag.force.bqI fields=${describeShallow(bqI)}"
                                }
                                // bq.i 内の er<aw> 系フィールドにも l() invoke
                                bqI.javaClass.declaredFields.forEach { field ->
                                    runCatching {
                                        field.isAccessible = true
                                        val value = field.get(bqI)
                                        if (value is List<*>) {
                                            value.forEach { entry ->
                                                if (entry != null && tryInvokeLOnAw(entry)) {
                                                    counters["bqI.${field.name}"] =
                                                        (counters["bqI.${field.name}"] ?: 0) + 1
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Napier.i(tag = TAG) {
                            "[NAVDBG] diag.force.polylineVisibility done: $counters"
                        }
                    }
                }.onFailure { error ->
                    Napier.w(error, tag = TAG) { "[NAVDBG] diag.force.polylineVisibility failed" }
                }
            },
            FORCE_VISIBILITY_DELAY_MS,
        )
    }

    /**
     * `target.fieldName` (Map<*, ps.aw>) の各 value から `ps.aw.b.a` (= `ov.ad`) を辿り
     * `l()` を呼んで visible flag を立てる。
     */
    private fun invokeLOnMapAwValues(
        target: Any,
        fieldName: String,
        label: String,
        counters: MutableMap<String, Int>,
    ) {
        val map = readField(target, fieldName) as? Map<*, *> ?: return
        var n = 0
        for (entry in map.entries) {
            val aw = entry.key  // bq.e.k = ez<ps.aw, Boolean> なので key 側
            if (aw != null && tryInvokeLOnAw(aw)) n++
        }
        // value 側にも awVar が入ってる可能性 (ag.K = Map<m, aw>)
        for (entry in map.entries) {
            val aw = entry.value
            if (aw != null && tryInvokeLOnAw(aw)) n++
        }
        if (n > 0) counters[label] = n
    }

    private fun invokeLOnIterableAw(
        target: Any,
        fieldName: String,
        label: String,
        counters: MutableMap<String, Int>,
    ) {
        val list = readField(target, fieldName) as? List<*> ?: return
        var n = 0
        for (entry in list) {
            if (entry != null && tryInvokeLOnAw(entry)) n++
        }
        if (n > 0) counters[label] = n
    }

    private fun invokeLOnAw(aw: Any, label: String, counters: MutableMap<String, Int>) {
        if (tryInvokeLOnAw(aw)) {
            counters[label] = 1
        }
    }

    /**
     * `aw` から内部 `ov.ad` を辿って `l()` を invoke する。
     * 成功したら true を返す。
     * `ps.au` (b=ps.b, b.a=ov.ad) と `ps.av` (内部構造異なる) の両方をハンドル。
     */
    private fun tryInvokeLOnAw(aw: Any): Boolean {
        return runCatching {
            val psF = readField(aw, "b") ?: return@runCatching false
            val ovAd = readField(psF, "a") ?: return@runCatching false
            invokeNoArg(ovAd, "l")
            true
        }.getOrDefault(false)
    }

    /**
     * `bb.e.a(bv.h)` 呼び出し直後に schedule する。
     * 1500ms 後に `bo.ac.U` を読み、`aa.b` (`er<bq.ag>`) の size を吐く。
     */
    fun inspectRenderState(overlayController: Any) {
        mainHandler.postDelayed(
            {
                runCatching {
                    val ac = readField(overlayController, "e")
                        ?: return@runCatching
                    val tLock = readField(ac, "T") ?: ac
                    synchronized(tLock) {
                        val aa = readField(ac, "U")
                        if (aa == null) {
                            Napier.i(tag = TAG) {
                                "[NAVDBG] diag.inspect: bo.ac.U=null (no aa created — build pipeline failed before render-state assembly)"
                            }
                            return@synchronized
                        }
                        val agList = readField(aa, "b")
                        val agSize = agList?.let { sizeOfYbEr(it) } ?: -1
                        val bqE = readField(aa, "a")
                        val zOk = readField(aa, "c") != null
                        Napier.i(tag = TAG) {
                            "[NAVDBG] diag.inspect: bo.aa.b.size=$agSize " +
                                "(bq.ag count) bq.e=${bqE != null} bq.z=$zOk"
                        }
                        if (bqE != null) {
                            inspectRouteRenderer(bqE)
                        }
                        if (agSize > 0 && agList is List<*>) {
                            inspectFirstAg(agList[0])
                        }
                    }
                }.onFailure { error ->
                    Napier.w(error, tag = TAG) { "[NAVDBG] diag.inspect failed" }
                }
            },
            INSPECT_DELAY_MS,
        )
    }

    /**
     * `bq.e` (= 本物の route polyline renderer) の内部状態をログる。
     *
     * - `i` (`er<bq.d>`): route descriptor list — size 0 なら polyline 描画対象が無い。
     * - `k` (`Map<ps.aw,?>`): rendered ETA callout entries — 0 なら b() の本体ループに入れてない。
     * - `s` (`er<nt.g>`): rendered fade tile entries (callout 用) — 同上。
     * - `r` (`ps.aw`): start point callout — null なら描画スキップ。
     * - `g` (boolean): true になってないと b() 内の if (this.g) ブロックに入らない。
     */
    private fun inspectRouteRenderer(bqE: Any) {
        runCatching {
            val gFlag = (readField(bqE, "g") as? Boolean) == true
            val iSize = readField(bqE, "i")?.let { sizeOfYbEr(it) } ?: -1
            val kSize = (readField(bqE, "k") as? Map<*, *>)?.size ?: -1
            val sSize = readField(bqE, "s")?.let { sizeOfYbEr(it) } ?: -1
            val rOk = readField(bqE, "r") != null
            val pOk = readField(bqE, "p") != null
            val qOk = readField(bqE, "q") != null
            // l = ETA map (cr.ba → Integer seconds), m = distance map (cr.ba → Integer meters)
            // → 両方 0 だと polyline build loop の `if (this.l.containsKey(baVar))` で全 skip。
            // t = cr.bb (SHOW_ALL / SHOW_NONE / ...) → SHOW_NONE だと build しても GL push しない。
            // a = cr.bc (TIME / DISTANCE) → callout label の表示種別。
            val lSize = (readField(bqE, "l") as? Map<*, *>)?.size ?: -1
            val mSize = (readField(bqE, "m") as? Map<*, *>)?.size ?: -1
            val visibility = readField(bqE, "t")?.let { (it as? Enum<*>)?.name ?: it.toString() }
            val detailType = readField(bqE, "a")?.let { (it as? Enum<*>)?.name ?: it.toString() }
            Napier.i(tag = TAG) {
                "[NAVDBG] diag.inspect.bqE: g=$gFlag i.size=$iSize " +
                    "k.size=$kSize s.size=$sSize r=$rOk p=$pOk q=$qOk " +
                    "l.size=$lSize m.size=$mSize t=$visibility a=$detailType"
            }
            // k の中身 (ps.aw) を 1 個サンプル: クラス名と toString だけ吐く。
            val kMap = readField(bqE, "k") as? Map<*, *>
            if (kMap != null && kMap.isNotEmpty()) {
                val (key, value) = kMap.entries.first()
                Napier.i(tag = TAG) {
                    "[NAVDBG] diag.inspect.bqE.k[0]: " +
                        "key=${key?.javaClass?.name} val=$value"
                }
                inspectPsAuEntry(key)
            }

            inspectGAndPolyline(bqE)
            inspectStyleAssets(bqE)
        }.onFailure { error ->
            Napier.w(error, tag = TAG) { "[NAVDBG] diag.inspect.bqE failed" }
        }
    }

    /**
     * `bq.e.G` (`List<br.av>` route descriptor list) と `bq.e.i[0]` (`bq.d`) を覗き、
     * 実際に注入された polyline の vertex 数・先頭/末尾座標を log に吐く。
     *
     * polyline build loop (bq.e.b 内の `for (br.av : this.G)`) が 0 回回ってると `k.size` が
     * 0 になるが、現状は 1 なので少なくとも 1 route 分は通っている。座標が drive-supporter
     * 系の Mercator 値を期待値で復元できれば、build pipeline の入力は正しい。
     */
    private fun inspectGAndPolyline(bqE: Any) {
        runCatching {
            val gList = readField(bqE, "G") as? List<*>
            val gSize = gList?.size ?: -1
            Napier.i(tag = TAG) {
                "[NAVDBG] diag.inspect.bqE.G.size=$gSize (List<br.av> route list)"
            }
            if (gList != null && gList.isNotEmpty()) {
                val firstAv = gList[0]
                val az = firstAv?.let { runCatching { invokeNoArg(it, "d") }.getOrNull() }
                if (az != null) {
                    val routeId = readField(az, "W")
                    val mode = readField(az, "g")?.let { (it as? Enum<*>)?.name ?: it.toString() }
                    val pList = runCatching { invokeNoArg(az, "P") as? List<*> }.getOrNull()
                    val qList = runCatching { invokeNoArg(az, "q") as? List<*> }.getOrNull()
                    val au = runCatching { invokeNoArg(az, "o") }.getOrNull()
                    val aiVerts = au?.let { auObj ->
                        runCatching {
                            val ai = invokeNoArg(auObj, "d")
                            ai?.let { invokeNoArg(it, "g") as? Int }
                        }.getOrNull()
                    } ?: -1
                    Napier.i(tag = TAG) {
                        "[NAVDBG] diag.inspect.bqE.G[0].az: id=$routeId mode=$mode " +
                            "P.size=${pList?.size ?: -1} q.size=${qList?.size ?: -1} " +
                            "o.d.g=$aiVerts"
                    }
                }
            }

            val iList = readField(bqE, "i") as? List<*>
            if (iList != null && iList.isNotEmpty()) {
                val bqD = iList[0]
                val ba = bqD?.let { readField(it, "a") }
                if (ba != null) {
                    val ai = readField(ba, "a")
                    val vertCount = ai?.let { runCatching { invokeNoArg(it, "g") as? Int }.getOrNull() } ?: -1
                    val firstVertex = ai?.let { aiObj ->
                        runCatching {
                            val z = invokeNoArg1Int(aiObj, "h", 0)
                            z?.let {
                                val lat = invokeNoArg(it, "h") as? Double
                                val lng = invokeNoArg(it, "l") as? Double
                                "lat=$lat lng=$lng"
                            }
                        }.getOrNull()
                    } ?: "<n/a>"
                    val lastVertex = if (vertCount > 0 && ai != null) {
                        runCatching {
                            val z = invokeNoArg1Int(ai, "h", vertCount - 1)
                            z?.let {
                                val lat = invokeNoArg(it, "h") as? Double
                                val lng = invokeNoArg(it, "l") as? Double
                                "lat=$lat lng=$lng"
                            }
                        }.getOrNull() ?: "<n/a>"
                    } else {
                        "<n/a>"
                    }
                    Napier.i(tag = TAG) {
                        "[NAVDBG] diag.inspect.bqE.i[0].ba.a: vertices=$vertCount " +
                            "first=$firstVertex last=$lastVertex"
                    }
                }
            }
        }.onFailure { error ->
            Napier.w(error, tag = TAG) { "[NAVDBG] diag.inspect.bqE.G failed" }
        }
    }

    /**
     * `bq.e.j` (`cr.ay` impl, 大抵 `cr.e`) を辿って style asset の ready 状態を確認する。
     *
     * `cr.e.f(...)` は line 386 で
     *   `if (cr.k.l == null || cr.k.n == null) return acd.ed.v;`
     * という早期 return を持つ。`cr.k.l/n` は `cr.bm.b/c(bm.c, z2, z4)` (= `ns.l.f(acd.ev)`)
     * で取れる polyline callout style asset。これが null だと polyline 全部空 proto で
     * build され、GL に push しても 1 byte も描画されない。
     *
     * → ここで直接 `ns.l.f(LEGEND_STYLE_ROUTE_CALLOUT_*)` を呼んで戻り値を確認する。
     *   null なら style asset 未供給 = bp.e/cr.bm 経由の bootstrap 不足が確定する。
     */
    private fun inspectStyleAssets(bqE: Any) {
        runCatching {
            val ay = readField(bqE, "j") ?: return@runCatching
            // cr.e は cr.c を `a` field に持つ。
            val crC = readField(ay, "a") ?: run {
                Napier.i(tag = TAG) { "[NAVDBG] diag.inspect.style: cr.e.a (cr.c) missing" }
                return@runCatching
            }
            val ready = (readField(crC, "a") as? Boolean) == true
            // cr.c.a() を呼んで cr.bm を強制 lazy 解決。
            val bm = runCatching { invokeNoArg(crC, "a") }.getOrNull()
            if (bm == null) {
                Napier.i(tag = TAG) { "[NAVDBG] diag.inspect.style: cr.bm null (ready=$ready)" }
                return@runCatching
            }
            val nsL = readField(bm, "c")
            val sCacheSize = (readField(bm, "s") as? Map<*, *>)?.size ?: -1
            Napier.i(tag = TAG) {
                "[NAVDBG] diag.inspect.style: ready=$ready bm=${bm.javaClass.simpleName} " +
                    "ns.l=${nsL?.javaClass?.simpleName} bm.s.size=$sCacheSize"
            }

            if (nsL != null) {
                val evClass = runCatching {
                    Class.forName("com.google.android.libraries.navigation.internal.acd.ev")
                }.getOrNull()
                if (evClass != null) {
                    val evNames = listOf(
                        "LEGEND_STYLE_ROUTE_CALLOUT_PRIMARY_GROUP",
                        "LEGEND_STYLE_ROUTE_CALLOUT_TITLE",
                        "LEGEND_STYLE_ROUTE_CALLOUT_SELECTED_PRIMARY_GROUP",
                        "LEGEND_STYLE_ROUTE_CALLOUT_SELECTED_TITLE",
                        "LEGEND_STYLE_CAR_ROUTE_CALLOUT_PRIMARY_GROUP",
                        "LEGEND_STYLE_CAR_ROUTE_CALLOUT_SELECTED_PRIMARY_GROUP",
                    )
                    val fMethod = runCatching {
                        nsL.javaClass.methods.firstOrNull {
                            it.name == "f" && it.parameterTypes.size == 1 &&
                                it.parameterTypes[0].isAssignableFrom(evClass)
                        }
                    }.getOrNull()
                    if (fMethod != null) {
                        for (evName in evNames) {
                            val result = runCatching {
                                @Suppress("UNCHECKED_CAST")
                                val ev = java.lang.Enum.valueOf(evClass as Class<out Enum<*>>, evName)
                                fMethod.invoke(nsL, ev)
                            }
                            val display = result.fold(
                                onSuccess = { value ->
                                    if (value == null) "null" else value.javaClass.simpleName
                                },
                                onFailure = { err -> "ERR ${err.javaClass.simpleName}" },
                            )
                            Napier.i(tag = TAG) {
                                "[NAVDBG] diag.inspect.style.ev[$evName]=$display"
                            }
                        }
                    } else {
                        Napier.i(tag = TAG) { "[NAVDBG] diag.inspect.style: ns.l.f(ev) method missing" }
                    }
                }
            }
        }.onFailure { error ->
            Napier.w(error, tag = TAG) { "[NAVDBG] diag.inspect.style failed" }
        }
    }

    /**
     * `bq.e.k[0]` (= `ps.au`) の内部 GL renderable を覗く。
     *
     * `ps.au` は `(this.A.E(), eVarG2.f())` で構築されており、第 2 引数 `ps.f` が
     * 実際の GL primitive。`ps.au.b` (= `ps.f`) を取り、その中の `ov.ad` (renderable)
     * の有無で「polyline は GL queue に積まれている」かを判定する。
     * `ad == null` なら build pipeline は通ったが描画素材が空、というケースを示す。
     */
    private fun inspectPsAuEntry(psAu: Any?) {
        if (psAu == null) return
        runCatching {
            val psF = readField(psAu, "b")
            // ps.au.c (boolean) — destroy 済みなら b() は早期 return する。
            val auDestroyed = (readField(psAu, "c") as? Boolean) == true
            Napier.i(tag = TAG) {
                "[NAVDBG] diag.inspect.bqE.k[0].psAu: " +
                    "b=${psF?.javaClass?.name} destroyed=$auDestroyed " +
                    "fields=${describeShallow(psAu)}"
            }
            if (psF != null) {
                Napier.i(tag = TAG) {
                    "[NAVDBG] diag.inspect.bqE.k[0].psAu.b(psF): " +
                        "fields=${describeShallow(psF)}"
                }
                inspectOvAdRenderable(psF)
            }
        }.onFailure { error ->
            Napier.w(error, tag = TAG) { "[NAVDBG] diag.inspect.bqE.k[0].psAu failed" }
        }
    }

    /**
     * `ps.b.a` (= `ov.ad` / `nt.n` GL renderable) の anchor 状態を読む。
     *
     * `ps.k.b(ps.f)` (= polyline activate path) は
     *   `acd.b bVarG = nVar.g(); if (bVarG == null) return;`
     * という早期 return を持つ。ここで return されると `this.a.e()` (= GL flush) も
     * 呼ばれないため、build pipeline は通っているのに polyline が visible にならない。
     *
     * ついでに `ov.ad` の他のフラグ (visible / fade / drop list 等) を
     * shallow にダンプし、`bq.e.b()` line 465 path で `adVarO.l()` が
     * 呼ばれていないことに起因する non-visible 状態を疑う。
     */
    private fun inspectOvAdRenderable(psF: Any) {
        runCatching {
            val ovAd = readField(psF, "a")
            if (ovAd == null) {
                Napier.i(tag = TAG) { "[NAVDBG] diag.inspect.ovAd: ps.b.a=null" }
                return@runCatching
            }
            val fResult = runCatching { invokeNoArg(ovAd, "f") }.getOrNull()
            val gResult = runCatching { invokeNoArg(ovAd, "g") }.getOrNull()
            val fSummary = fResult?.let { z ->
                runCatching {
                    val lat = invokeNoArg(z, "h") as? Double
                    val lng = invokeNoArg(z, "l") as? Double
                    "z(lat=$lat lng=$lng)"
                }.getOrDefault(z.javaClass.simpleName)
            } ?: "null"
            val gSummary = gResult?.let { (it as? Enum<*>)?.name ?: it.javaClass.simpleName } ?: "null"
            Napier.i(tag = TAG) {
                "[NAVDBG] diag.inspect.ovAd: f()=$fSummary g()=$gSummary " +
                    "(g=null → ps.k.b() early-returns and GL flush is skipped)"
            }
            Napier.i(tag = TAG) {
                "[NAVDBG] diag.inspect.ovAd.fields=${describeShallow(ovAd)}"
            }
        }.onFailure { error ->
            Napier.w(error, tag = TAG) { "[NAVDBG] diag.inspect.ovAd failed" }
        }
    }

    private fun describeShallow(target: Any): String {
        return target.javaClass.declaredFields.joinToString(prefix = "[", postfix = "]") { field ->
            runCatching {
                field.isAccessible = true
                val v = field.get(target)
                "${field.name}:${field.type.simpleName}=${v?.javaClass?.simpleName ?: "null"}"
            }.getOrElse { "${field.name}=<inaccessible>" }
        }
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.isEmpty()
            }?.let { method ->
                method.isAccessible = true
                return method.invoke(target)
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeNoArg1Int(target: Any, methodName: String, arg: Int): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == java.lang.Integer.TYPE
            }?.let { method ->
                method.isAccessible = true
                return method.invoke(target, arg)
            }
            current = current.superclass
        }
        return null
    }

    /**
     * `bq.ag` (1 ルート分の polyline + decoration container) の内部状態をログる。
     *
     * - `g` (`er<nt.j>`): waypoint marker polyline 群 — bo.v.a で構築。
     * - `K` (`Map<*,*>`): road-condition decorations — empty が普通。
     * - `m` (`er<aw>`): incident decorations — empty が普通。
     * - `y` (int): 1 = SELECTED (描画 ON), 3 = skip。
     * - `a.c.size` (`bu.c`): renderer に push する ob.k 群 — 0 だと render trigger が空回り。
     */
    private fun inspectFirstAg(ag: Any?) {
        if (ag == null) return
        runCatching {
            val y = readField(ag, "y") as? Int ?: -1
            val gSize = readField(ag, "g")?.let { sizeOfYbEr(it) } ?: -1
            val kSize = (readField(ag, "K") as? Map<*, *>)?.size ?: -1
            val mSize = readField(ag, "m")?.let { sizeOfYbEr(it) } ?: -1
            val bu = readField(ag, "a")
            val buCSize = bu?.let { (readField(it, "c") as? List<*>)?.size } ?: -1
            val buBSize = bu?.let { (readField(it, "b") as? List<*>)?.size } ?: -1
            val buDSize = bu?.let { (readField(it, "d") as? List<*>)?.size } ?: -1
            Napier.i(tag = TAG) {
                "[NAVDBG] diag.inspect.ag[0]: y=$y g.size=$gSize K.size=$kSize " +
                    "m.size=$mSize bu.b=$buBSize bu.c=$buCSize bu.d=$buDSize"
            }
        }.onFailure { error ->
            Napier.w(error, tag = TAG) { "[NAVDBG] diag.inspect.ag failed" }
        }
    }

    private fun wrapPlainExecutorField(
        target: Any,
        fieldName: String,
        label: String,
    ) {
        val field = findField(target.javaClass, fieldName) ?: return
        val original = field.get(target) as? Executor ?: return
        if (original is LoggingExecutor) return
        field.set(target, LoggingExecutor(original, label))
    }

    private fun wrapBjField(
        target: Any,
        fieldName: String,
        label: String,
    ) {
        val field = findField(target.javaClass, fieldName) ?: return
        val original = field.get(target) ?: return
        if (Proxy.isProxyClass(original.javaClass)) return

        val bjClass = runCatching {
            Class.forName("com.google.android.libraries.navigation.internal.zd.bj")
        }.getOrNull() ?: return

        val proxy = Proxy.newProxyInstance(
            bjClass.classLoader,
            arrayOf(bjClass),
            BjLoggingInvocationHandler(original, label),
        )
        field.set(target, proxy)
    }

    private fun sizeOfYbEr(value: Any): Int {
        // yb.er は AbstractList を継承する Guava ImmutableList 相当。
        if (value is List<*>) return value.size
        return runCatching {
            val sizeMethod = value.javaClass.methods.firstOrNull { it.name == "size" && it.parameterTypes.isEmpty() }
                ?: return@runCatching -1
            (sizeMethod.invoke(value) as? Int) ?: -1
        }.getOrDefault(-1)
    }

    private fun readField(target: Any, fieldName: String): Any? {
        val field = findField(target.javaClass, fieldName) ?: return null
        return field.get(target)
    }

    private fun findField(targetClass: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = targetClass
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    /**
     * polyline body 描画の上流ゲートを実機で検証する。
     *
     * 仮説:
     *   bo.ac.q() (line 1492) は `if (alVarC != null)` 内でしか `this.ao`
     *   (xz.an Optional) を初期化しない。`alVarC = ((bv.b)hVar).a.c()`
     *   = `be.c()` = `((br.az)list.get(0)).g` (travelMode)。
     *   ao が空のままだと line 2059 の polyline schedule block
     *   (`if (this.ao.a())` … `acVar.m(hVar, ezVar, aVar4)`) が skip され、
     *   polyline body は描画されない。
     *
     * インスペクション項目:
     *   - bo.ac.R.a = bv.h (我々が注入した state)
     *   - bv.h.a = br.be (RouteList)
     *   - be.c() = travelMode (DRIVE / WALK / null...)
     *   - be.j(0).g = az.g (各 route の travelMode)
     *   - bo.ac.ao xz.an の `a()` (= isPresent)
     *   - bo.ac.U bo.aa の状態
     *   - bo.ac.G rh.a (route gen counter)
     */
    fun inspectInjectionPipelineGating(overlayController: Any) {
        mainHandler.postDelayed(
            {
                runCatching {
                    val ac = readField(overlayController, "e")
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] gating: bo.m.e=null" }

                    val rField = readField(ac, "R")
                    val hVar = rField?.let { readField(it, "a") }
                    val be = hVar?.let { readField(it, "a") }
                    val travelMode = be?.let { invokeNoArg(it, "c") }
                    val routeCount = be?.let { invokeNoArg(it, "k") as? Int } ?: -1
                    val firstAz = be?.let {
                        runCatching { invokeNoArg1Int(it, "j", 0) }.getOrNull()
                    }
                    val firstAzG = firstAz?.let { readField(it, "g") }

                    val ao = readField(ac, "ao")
                    val aoPresent = ao?.let { invokeNoArg(it, "a") }
                    val u = readField(ac, "U")
                    val gField = readField(ac, "G")

                    val sFlag = readField(ac, "s")
                    val adState = readField(ac, "ad")

                    Napier.i(tag = TAG) {
                        "[NAVDBG] gating.q: " +
                            "hVar=${hVar?.javaClass?.simpleName} " +
                            "be=${be?.javaClass?.simpleName} routes=$routeCount " +
                            "be.c()=${travelMode?.toString() ?: "null"} " +
                            "az[0].g=${firstAzG?.toString() ?: "null"} " +
                            "ao=${ao?.javaClass?.simpleName} ao.a()=$aoPresent " +
                            "U(bo.aa)=${u != null} " +
                            "G(rh.a)=${gField?.javaClass?.simpleName} " +
                            "s=$sFlag ad=$adState"
                    }

                    if (firstAz != null) {
                        Napier.i(tag = TAG) {
                            "[NAVDBG] gating.az[0]: ${describeShallow(firstAz)}"
                        }
                    }
                }.onFailure { error ->
                    Napier.w(error, tag = TAG) {
                        "[NAVDBG] gating inspect failed: ${error.javaClass.name}: ${error.message}"
                    }
                }
            },
            INSPECT_GATING_DELAY_MS,
        )
    }

    /**
     * `bo.ac.m(bv.h, Map, rh.a)` を reflection で直接呼んで return List を logcat に出す。
     *
     * 用途:
     *   通常 flow では `bo.ac.q()` 内で `m()` が `bjVar` 上で schedule され、
     *   返ってくる `List<zd.bf>` (= polyline rendering Futures) は SDK 内部で
     *   消費されてしまうため外から観測できない。直接呼んで size を見れば
     *   - 0 件 → m() 内部 ((bv.b)hVar).c, beVar.k() etc.) で skip 条件成立
     *   - >0 件 → m() は cq.aw 構築 + v() 呼び出しを成功させている
     *           (= polyline body が表示されない原因は bo.ac.v() の中、
     *             もしくは別の build pipeline)
     *   が切り分けできる。
     */
    fun directInvokeM(overlayController: Any) {
        mainHandler.postDelayed(
            {
                runCatching {
                    val ac = readField(overlayController, "e")
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] m: bo.m.e=null" }

                    val rField = readField(ac, "R")
                    val hVar = rField?.let { readField(it, "a") }
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] m: hVar=null" }
                    val gField = readField(ac, "G")
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] m: G=null" }
                    val emptyMap = readEmptyImmutableMap()
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] m: empty map=null" }

                    val mMethod = ac.javaClass.declaredMethods.firstOrNull { method ->
                        method.name == "m" && method.parameterTypes.size == 3
                    } ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] m: method not found" }
                    mMethod.isAccessible = true

                    val result = runCatching { mMethod.invoke(ac, hVar, emptyMap, gField) }
                        .onFailure { error ->
                            Napier.w(error, tag = TAG) {
                                "[NAVDBG] m: invocation threw " +
                                    "${error.javaClass.name}: ${error.message}"
                            }
                        }
                        .getOrNull()

                    val list = result as? List<*>
                    if (list == null) {
                        Napier.i(tag = TAG) {
                            "[NAVDBG] m: return type=${result?.javaClass?.name ?: "null"}"
                        }
                        return@runCatching
                    }
                    Napier.i(tag = TAG) {
                        "[NAVDBG] m: return List size=${list.size} " +
                            "first=${list.firstOrNull()?.javaClass?.name ?: "n/a"}"
                    }
                }.onFailure { error ->
                    Napier.w(error, tag = TAG) {
                        "[NAVDBG] m: outer failed ${error.javaClass.name}: ${error.message}"
                    }
                }
            },
            DIRECT_M_DELAY_MS,
        )
    }

    private fun readEmptyImmutableMap(): Any? {
        return runCatching {
            Class.forName("com.google.android.libraries.navigation.internal.yb.lw")
                .getDeclaredField("b")
                .apply { isAccessible = true }
                .get(null)
        }.getOrNull()
    }

    /**
     * polyline body 描画関数 `bo.ac.v(cq.aw, nt.ap, boolean, bv.h, br.az, Map, int, rh.a)`
     * を直接 reflection 呼び出し。
     *
     * `m()` が空 List を返している (= 内部 cq.aw 構築で skip) 状況に対し、
     * 自前で `cq.aw.b(polyline, DRIVE).a()` を組み立てて v() を invoke することで、
     * polyline body を強制的に GL 上に乗せられるか検証する。
     *
     * 成功条件 (実機画面に polyline が出る):
     *   この経路で polyline body の最終 GL 描画は完成。あとは m() が cq.aw を作るための
     *   azVar 内部データ (q()/legs/walking) を埋める方向で詰める。
     *
     * 失敗条件:
     *   v() 内部で更に別の null 参照や guard が走っており、cq.aw 単独では descend し切れない。
     *   v() の引数 nt.ap (pick handler), int category などを別パターンで再試行する必要あり。
     */
    fun directInvokeV(overlayController: Any) {
        mainHandler.postDelayed(
            {
                runCatching {
                    val ac = readField(overlayController, "e")
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: bo.m.e=null" }

                    val rField = readField(ac, "R")
                    val hVar = rField?.let { readField(it, "a") }
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: hVar=null" }
                    val gField = readField(ac, "G")
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: G=null" }
                    val emptyMap = readEmptyImmutableMap()
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: empty map=null" }

                    val be = readField(hVar, "a")
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: be=null" }
                    val az = invokeNoArg1Int(be, "j", 0)
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: az=null" }
                    val polyline = readField(az, "j")
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: polyline=null" }
                    val travelMode = readField(az, "g")
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: travelMode=null" }

                    // cq.av.a(polyline, travelMode) → cq.a builder with bits 0,3 set
                    val avClass = Class.forName("com.google.android.libraries.navigation.internal.cq.av")
                    val avMethod = avClass.declaredMethods.first { it.name == "a" && it.parameterTypes.size == 2 }
                    avMethod.isAccessible = true
                    val cqA = avMethod.invoke(null, polyline, travelMode)
                        ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: cq.a builder=null" }

                    // cq.a.a() → cq.aw
                    val awBuildMethod = cqA.javaClass.declaredMethods.first { it.name == "a" && it.parameterTypes.isEmpty() }
                    awBuildMethod.isAccessible = true
                    val cqAw = runCatching { awBuildMethod.invoke(cqA) }
                        .onFailure { Napier.w(it, tag = TAG) { "[NAVDBG] v: aw.build threw ${it.javaClass.simpleName}: ${it.message}" } }
                        .getOrNull() ?: return@runCatching

                    Napier.i(tag = TAG) { "[NAVDBG] v: built cq.aw=${cqAw.javaClass.simpleName}" }

                    // bo.ac.v(cq.aw, nt.ap, boolean, bv.h, br.az, Map, int, rh.a) → zd.bf
                    val vMethod = ac.javaClass.declaredMethods.firstOrNull { method ->
                        method.name == "v" && method.parameterTypes.size == 8
                    } ?: return@runCatching Napier.w(tag = TAG) { "[NAVDBG] v: method not found" }
                    vMethod.isAccessible = true

                    val args: Array<Any?> = arrayOf(
                        cqAw,
                        null,
                        true,
                        hVar,
                        az,
                        emptyMap,
                        1,
                        gField,
                    )
                    val result = runCatching { vMethod.invoke(ac, *args) }
                        .onFailure { error ->
                            Napier.w(error, tag = TAG) {
                                "[NAVDBG] v: invocation threw ${error.javaClass.name}: ${error.message}"
                            }
                        }
                        .getOrNull()

                    Napier.i(tag = TAG) {
                        "[NAVDBG] v: returned ${result?.javaClass?.simpleName ?: "null"}"
                    }
                }.onFailure { error ->
                    Napier.w(error, tag = TAG) {
                        "[NAVDBG] v: outer failed ${error.javaClass.name}: ${error.message}"
                    }
                }
            },
            DIRECT_V_DELAY_MS,
        )
    }

    private const val INSPECT_DELAY_MS = 1500L
    private const val FORCE_VISIBILITY_DELAY_MS = 1700L
    private const val INSPECT_GATING_DELAY_MS = 1900L
    private const val DIRECT_M_DELAY_MS = 2200L
    private const val DIRECT_V_DELAY_MS = 2500L

    /**
     * Runnable を try/catch で包んで例外を Napier に吐く Executor。
     * SDK 側は executor が例外を投げてもキャッチしないので silent fail する。
     * これを wrap すると bo.v.a / bo.aa.a 内の NPE 等が直接ログに出る。
     */
    private class LoggingExecutor(
        private val delegate: Executor,
        private val label: String,
    ) : Executor {
        override fun execute(command: Runnable) {
            delegate.execute(WrappedRunnable(command, label))
        }
    }

    private class WrappedRunnable(
        private val delegate: Runnable,
        private val label: String,
    ) : Runnable {
        override fun run() {
            try {
                delegate.run()
            } catch (error: Throwable) {
                Napier.e(error, tag = TAG) {
                    "[NAVDBG] async exception on $label: " +
                        "${error.javaClass.name}: ${error.message}"
                }
                throw error
            }
        }
    }

    /**
     * `zd.bj` (ScheduledExecutorService) の Proxy。
     * `execute(Runnable)` だけ wrap し、それ以外は pass-through。
     * schedule 系は本件 polyline pipeline では使われないので素通しで OK。
     */
    private class BjLoggingInvocationHandler(
        private val delegate: Any,
        private val label: String,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: emptyArray()
            if (method.name == "execute" && arguments.size == 1) {
                val runnable = arguments[0] as? Runnable
                if (runnable != null) {
                    return method.invoke(delegate, WrappedRunnable(runnable, label))
                }
            }
            return try {
                method.invoke(delegate, *arguments)
            } catch (error: java.lang.reflect.InvocationTargetException) {
                throw error.targetException ?: error
            }
        }
    }
}
