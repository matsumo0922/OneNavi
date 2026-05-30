package me.matsumo.onenavi.core.navigation.voice.plan

import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementBlock
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDistanceContext
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRoutePayload
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import kotlin.math.roundToInt

/**
 * payload と attach 時の距離変換 context から、位置非依存の [VoiceAnnouncementPlan] を構築する。
 *
 * 各 GP の発話 block を距離段 (MIDDLE / FINAL) に整理し、source→geometry 変換を必ず通して
 * 発話トリガ距離を確定する。tick ごとの発話済み状態や barge-in 判定は持たない。
 */
internal class VoiceAnnouncementPlanBuilder {

    /**
     * 発話プランを構築する。
     *
     * @param payload guidancePoints / announcementBlocks を含む payload
     * @param distanceContext source→geometry 距離変換 context
     * @param config category gate / 距離 override などの設定
     * @return GP を [AnnouncementTarget] にまとめた発話プラン
     */
    fun build(
        payload: ExtNavRoutePayload,
        distanceContext: ExtNavRouteDistanceContext,
        config: VoiceAnnouncementConfig,
    ): VoiceAnnouncementPlan {
        val routeId = payload.id
        val targets = mutableListOf<AnnouncementTarget>()

        for (guidancePoint in payload.routeGuidance.guidancePoints) {
            val target = buildTarget(
                routeId = routeId,
                guidancePoint = guidancePoint,
                distanceContext = distanceContext,
                config = config,
            )
            if (target != null) targets += target
        }

        return VoiceAnnouncementPlan(
            routeId = routeId,
            targets = targets
                .sortedBy { target -> target.geometryMeters }
                .toImmutableList(),
        )
    }

    /**
     * 1 GP を [AnnouncementTarget] に変換する。発話可能な block が無ければ null を返す。
     *
     * source 距離を対応付けられない block (anchor 距離が null) は除外する。外部データは同一 GP に
     * まったく同じ発話内容の block を複数距離で重複させて持つため、発話内容が同じ block は
     * 案内点に近い 1 つに畳む ([dedupBlocksBySpokenText])。残った block のうち最も手前 (最小
     * triggerDistanceMetres) の段を FINAL、それ以外を MIDDLE として扱う。
     *
     * 距離違い候補のうち「どれを鳴らすか」はプラン構築では絞り込まず、発話時に group_id・距離窓・
     * テンプレート種別から選抜する (外部ナビ API 参照実装と同じデータ駆動方式)。ここでは全 block を
     * 段に変換し、選抜に必要な groupKey / 距離窓 / 汎用フラグを付与するだけに留める。
     */
    private fun buildTarget(
        routeId: String,
        guidancePoint: GuidancePoint,
        distanceContext: ExtNavRouteDistanceContext,
        config: VoiceAnnouncementConfig,
    ): AnnouncementTarget? {
        val validBlocks = guidancePoint.announcementBlocks.filter { block -> block.isAnnounceable(guidancePoint) }

        if (validBlocks.isEmpty()) return null

        val uniqueBlocks = dedupBlocksBySpokenText(validBlocks)
        val finalBlockIndex = selectFinalBlockIndex(uniqueBlocks)
        val geometryMeters = distanceContext.geometryMetresFor(
            sourceMetres = guidancePoint.distanceFromStartMetres.toDouble(),
        )
        val stages = mutableListOf<AnnouncementStage>()

        for (blockIndex in uniqueBlocks.indices) {
            val block = uniqueBlocks[blockIndex]
            val isFinalBlock = blockIndex == finalBlockIndex
            val blockStages = buildStagesForBlock(
                routeId = routeId,
                guidancePoint = guidancePoint,
                block = block,
                isFinalBlock = isFinalBlock,
                gpGeometryMeters = geometryMeters,
                distanceContext = distanceContext,
                config = config,
            )
            stages += blockStages
        }

        return AnnouncementTarget(
            guidancePointIndex = guidancePoint.index,
            geometryMeters = geometryMeters,
            stages = stages
                .sortedBy { stage -> stage.triggerSourceMeters }
                .toImmutableList(),
        )
    }

    /**
     * 1 block を距離段に変換する。
     *
     * FINAL block は 1 つの FINAL 段にする。MIDDLE block は距離 override があれば手前距離の
     * 数だけ複製し、無ければ block 自身の triggerDistanceMetres で 1 段にする。MIDDLE 段の発話有効範囲
     * (距離窓) は外部データの [GuideAnnouncementBlock.window] 由来で、同一 group_id の候補が代替として束ねられる。
     */
    private fun buildStagesForBlock(
        routeId: String,
        guidancePoint: GuidancePoint,
        block: GuideAnnouncementBlock,
        isFinalBlock: Boolean,
        gpGeometryMeters: Double,
        distanceContext: ExtNavRouteDistanceContext,
        config: VoiceAnnouncementConfig,
    ): List<AnnouncementStage> {
        val anchorSource = requireNotNull(block.anchor.sourceDistanceFromStartMetres) {
            "mappable block must have a non-null source anchor distance"
        }

        if (isFinalBlock) {
            val triggerSource = anchorSource - block.triggerDistanceMetres.toDouble()
            val finalStage = buildStage(
                routeId = routeId,
                guidancePoint = guidancePoint,
                block = block,
                triggerSourceMeters = triggerSource,
                kind = AnnouncementStageKind.FINAL,
                idSuffix = null,
                windowRemainingMeters = null,
                gpGeometryMeters = gpGeometryMeters,
                distanceContext = distanceContext,
            )
            return listOf(finalStage)
        }

        val overrideDistances = config.distanceOverrides.overrideFor(block.categories)

        if (overrideDistances == null) {
            val triggerSource = anchorSource - block.triggerDistanceMetres.toDouble()
            val middleStage = buildStage(
                routeId = routeId,
                guidancePoint = guidancePoint,
                block = block,
                triggerSourceMeters = triggerSource,
                kind = AnnouncementStageKind.MIDDLE,
                idSuffix = null,
                windowRemainingMeters = null,
                gpGeometryMeters = gpGeometryMeters,
                distanceContext = distanceContext,
            )
            return listOf(middleStage)
        }

        val stages = mutableListOf<AnnouncementStage>()

        for (leadDistanceMeters in overrideDistances) {
            val triggerSource = anchorSource - leadDistanceMeters
            val stage = buildStage(
                routeId = routeId,
                guidancePoint = guidancePoint,
                block = block,
                triggerSourceMeters = triggerSource,
                kind = AnnouncementStageKind.MIDDLE,
                idSuffix = leadDistanceMeters.roundToInt().toString(),
                // override は発話距離を再定義するため、窓もこの override 距離周りに作る (元 block の窓は使わない)。
                windowRemainingMeters = leadDistanceMeters,
                gpGeometryMeters = gpGeometryMeters,
                distanceContext = distanceContext,
            )
            stages += stage
        }

        return stages
    }

    /**
     * 単一の距離段を組み立てる。source トリガ距離を geometry 距離へ変換して保持する。
     *
     * MIDDLE 段には [computeMiddleWindow] で発話有効範囲 (距離窓) を、全段に group_id 由来の
     * [groupKeyOf] を割り当てる。
     */
    private fun buildStage(
        routeId: String,
        guidancePoint: GuidancePoint,
        block: GuideAnnouncementBlock,
        triggerSourceMeters: Double,
        kind: AnnouncementStageKind,
        idSuffix: String?,
        windowRemainingMeters: Double?,
        gpGeometryMeters: Double,
        distanceContext: ExtNavRouteDistanceContext,
    ): AnnouncementStage {
        val triggerGeometryMeters = distanceContext.geometryMetresFor(triggerSourceMeters)
        val id = buildStageId(
            routeId = routeId,
            guidancePointIndex = guidancePoint.index,
            blockId = block.id,
            idSuffix = idSuffix,
        )
        val groupKey = groupKeyOf(
            routeId = routeId,
            guidancePointIndex = guidancePoint.index,
            groupId = block.groupId,
            blockId = block.id,
            idSuffix = idSuffix,
        )
        val middleWindow = if (kind == AnnouncementStageKind.MIDDLE) {
            computeMiddleWindow(
                block = block,
                windowRemainingMeters = windowRemainingMeters,
                triggerGeometryMeters = triggerGeometryMeters,
                gpGeometryMeters = gpGeometryMeters,
                distanceContext = distanceContext,
            )
        } else {
            null
        }

        logBlockBreakdown(
            guidancePoint = guidancePoint,
            block = block,
            kind = kind,
            triggerSourceMeters = triggerSourceMeters,
            triggerGeometryMeters = triggerGeometryMeters,
            middleWindow = middleWindow,
        )

        return AnnouncementStage(
            id = id,
            groupKey = groupKey,
            kind = kind,
            triggerSourceMeters = triggerSourceMeters,
            triggerGeometryMeters = triggerGeometryMeters,
            middleWindow = middleWindow,
            isGeneric = block.isGenericBlock(),
            pieces = block.pieces,
            categories = block.categories,
        )
    }

    /**
     * MIDDLE 段の発話有効範囲 (距離窓) を geometry 累積距離で求める。
     *
     * 窓の遠い端 (far) / 近い端 (near) の残距離は次の優先順で決める。いずれも案内点までの残距離
     * [near, far] を source 距離へ直し、source→geometry 変換して窓にする (遠い端 → 窓の開始、近い端 → 窓の終端)。
     *
     * 1. [windowRemainingMeters] が非 null (距離 override で複製した段) ならその距離を far とし、手前へ
     *    [FALLBACK_WINDOW_METERS] 広げた帯にする。override は発話距離を再定義するので元 block の窓は使わない。
     * 2. 外部データに発話有効範囲 ([GuideAnnouncementBlock.window]) があればそれを使う。
     * 3. どちらも無ければ名目トリガ距離 (delta) 周りの狭い帯にする。
     *
     * **発話有効範囲が無い block を「自分のトリガ点から案内点まで」の広い窓にしてはならない**。広い窓は
     * 遠方トリガ block を案内点までずっと発話可能にし、手前の案内地点の直後に遠方の予告が鳴る誤発話
     * (例: 大泉ルートで裸の「右方向です」が 300m 手前で鳴る) を生むため。狭い帯なら、その帯が手前の
     * 案内地点より遠いときは route 順ゲートで解禁前に通り過ぎ、自然に鳴らない。
     */
    private fun computeMiddleWindow(
        block: GuideAnnouncementBlock,
        windowRemainingMeters: Double?,
        triggerGeometryMeters: Double,
        gpGeometryMeters: Double,
        distanceContext: ExtNavRouteDistanceContext,
    ): AnnouncementDistanceWindow {
        val anchorSource = block.anchor.sourceDistanceFromStartMetres
            ?: return AnnouncementDistanceWindow(triggerGeometryMeters, gpGeometryMeters)

        val window = block.window
        val nearRemainingMeters: Double
        val farRemainingMeters: Double
        when {
            windowRemainingMeters != null -> {
                farRemainingMeters = windowRemainingMeters
                nearRemainingMeters = (windowRemainingMeters - FALLBACK_WINDOW_METERS).coerceAtLeast(0.0)
            }
            window != null -> {
                nearRemainingMeters = window.nearMetres.toDouble()
                farRemainingMeters = window.farMetres.toDouble()
            }
            else -> {
                farRemainingMeters = block.triggerDistanceMetres.toDouble()
                nearRemainingMeters = (block.triggerDistanceMetres - FALLBACK_WINDOW_METERS).coerceAtLeast(0).toDouble()
            }
        }

        val enterSourceMeters = anchorSource - farRemainingMeters
        val exitSourceMeters = anchorSource - nearRemainingMeters

        return AnnouncementDistanceWindow(
            enterGeometryMeters = distanceContext.geometryMetresFor(enterSourceMeters),
            exitGeometryMeters = distanceContext.geometryMetresFor(exitSourceMeters),
        )
    }

    /**
     * 同一案内点内で距離違い候補を束ねる groupKey を組み立てる。
     *
     * 外部データの group_id が有効ならそれで束ね (予告グループ / 直前グループ等が別 key になり、選抜では
     * グループごとに 1 つだけ鳴らして消費する)。0 は「グループ無し (単独候補)」を表し、参照実装でも束ねない
     * ため block 単位で一意な key にして個別に発話・消費させる (例: 遠方予告「○km先 ○○ 方向」は互いに束ねない)。
     *
     * 距離 override で 1 block を複数段に複製したときは [idSuffix] (= 手前距離) を key に含めて段ごとに別 group
     * にする。同一 group のままだとグループ消費で最初の 1 段しか鳴らず、「指定した距離それぞれで鳴らす」という
     * override の意図 (= 各距離で 1 回ずつ) を満たせないため。
     */
    private fun groupKeyOf(
        routeId: String,
        guidancePointIndex: Int,
        groupId: Int,
        blockId: String,
        idSuffix: String?,
    ): VoiceAnnouncementId {
        val groupToken = if (groupId != 0) "grp$groupId" else "grpDefault#$blockId"
        val full = if (idSuffix == null) groupToken else "$groupToken#$idSuffix"

        return VoiceAnnouncementId("$routeId#gp$guidancePointIndex#$full")
    }

    /**
     * 先頭 piece が汎用テンプレート ([GENERIC_TEMPLATE_ID]) の block か。選抜時の汎用回避フラグに使う。
     *
     * 外部ナビ API 参照実装の汎用回避は**先頭フレーズの template_id == 100** を見て具体候補へ差し替えるため、
     * それに合わせて先頭 piece で判定する (後続 piece に別テンプレートや null が混ざっても先頭が汎用なら汎用扱い)。
     */
    private fun GuideAnnouncementBlock.isGenericBlock(): Boolean =
        pieces.firstOrNull()?.templateRef == GENERIC_TEMPLATE_ID

    // ---------------------------------------------------------------------
    // 診断ログ (issue #41 Phase 3 実機検証用、確認後に撤去予定)
    // ---------------------------------------------------------------------

    /**
     * 段を組む過程の生値をダンプする。anchor 距離・triggerDistance に加え group_id・距離窓を出して、
     * 「どの距離帯でどの候補が発話可能になるか」「遠すぎる位置でトリガする block」を切り分けるため。
     */
    private fun logBlockBreakdown(
        guidancePoint: GuidancePoint,
        block: GuideAnnouncementBlock,
        kind: AnnouncementStageKind,
        triggerSourceMeters: Double,
        triggerGeometryMeters: Double,
        middleWindow: AnnouncementDistanceWindow?,
    ) {
        Napier.d(tag = TAG) {
            "gp=${guidancePoint.index} block=${block.id} kind=$kind group=${block.groupId} " +
                "dataWin=${block.window} stageWin=$middleWindow generic=${block.isGenericBlock()} " +
                "srcGp=${block.anchor.sourceGuidancePointIndex} srcBlockIdx=${block.anchor.sourceBlockIndex} " +
                "anchorSrc=${block.anchor.sourceDistanceFromStartMetres} triggerDist=${block.triggerDistanceMetres} " +
                "trigSrc=$triggerSourceMeters trigGeo=$triggerGeometryMeters " +
                "cats=${block.categories} pieceTmpl=${templateRefsOf(block)} " +
                "text=\"${spokenTextOf(block)}\""
        }
    }

    /** 空の地点名スロットを持つ (無名 advance-notice) ため発話対象から外した block を出力する。 */
    private fun logSkippedNamelessAdvance(guidancePoint: GuidancePoint, block: GuideAnnouncementBlock) {
        Napier.d(tag = TAG) {
            "skip-nameless-advance gp=${guidancePoint.index} block=${block.id} group=${block.groupId} " +
                "triggerDist=${block.triggerDistanceMetres} text=\"${spokenTextOf(block)}\""
        }
    }

    /** 素片ごとの templateRef 一覧。ブロックの役割 (テンプレート種別) を切り分けるための診断値。 */
    private fun templateRefsOf(block: GuideAnnouncementBlock): List<Int?> =
        block.pieces.map { piece -> piece.templateRef }

    /**
     * 距離段の安定キーを組み立てる。距離 override で複製した段は手前距離を suffix に付ける。
     */
    private fun buildStageId(
        routeId: String,
        guidancePointIndex: Int,
        blockId: String,
        idSuffix: String?,
    ): VoiceAnnouncementId {
        val base = "$routeId#gp$guidancePointIndex#$blockId"
        val full = if (idSuffix == null) base else "$base#$idSuffix"

        return VoiceAnnouncementId(full)
    }

    /**
     * 読み上げテキストが一致する重複 block を 1 つに畳む。
     *
     * 外部データは同一 GP に同じ文言の block を複数の手前距離で重複させて持つ
     * (例: 「この信号を左方向です」を 110 / 100 / 80 / 59m 手前の 4 block で重複)。さらに、文言が完全に
     * 同じでも piece の templateRef / category が異なる (トリガ理由違いの) 重複もあるため、piece タプルでなく
     * **結合後の読み上げテキスト**でキーにする。同テキストの block 群は **案内点に最も近い
     * (triggerDistanceMetres が最小の) 1 つ**に畳む。距離 override 由来の複製は単一 block を後段で複製する
     * もので、本 dedup は block 単位で行うため衝突しない (異なる文言の block は畳まれない)。
     *
     * @param blocks 同一 GP の発話可能な block 群 (出現順)
     * @return 読み上げテキストごとに 1 つだけ残した block 群
     */
    private fun dedupBlocksBySpokenText(blocks: List<GuideAnnouncementBlock>): List<GuideAnnouncementBlock> {
        val nearestByText = LinkedHashMap<String, GuideAnnouncementBlock>()

        for (block in blocks) {
            val spokenText = spokenTextOf(block)
            val existing = nearestByText[spokenText]
            if (existing == null) {
                nearestByText[spokenText] = block
                continue
            }
            nearestByText[spokenText] = nearerBlock(existing, block)
        }

        return nearestByText.values.toList()
    }

    /** block の全 piece の text を結合した読み上げテキスト。重複判定のキーに使う。 */
    private fun spokenTextOf(block: GuideAnnouncementBlock): String =
        block.pieces.joinToString(separator = "") { piece -> piece.text }

    /** 重複 block のうち案内点に近い (triggerDistanceMetres が小さい) 方を返す。同値なら先着を残す。 */
    private fun nearerBlock(
        current: GuideAnnouncementBlock,
        candidate: GuideAnnouncementBlock,
    ): GuideAnnouncementBlock =
        if (candidate.triggerDistanceMetres < current.triggerDistanceMetres) candidate else current

    /**
     * GP 内で FINAL とみなす block の index を返す。最も手前 (最小 triggerDistanceMetres) の
     * block を採用し、同値なら出現順で先頭を採る。
     */
    private fun selectFinalBlockIndex(blocks: List<GuideAnnouncementBlock>): Int {
        var bestIndex = 0
        var bestLeadDistance = blocks[0].triggerDistanceMetres

        for (index in 1 until blocks.size) {
            val leadDistance = blocks[index].triggerDistanceMetres
            if (leadDistance < bestLeadDistance) {
                bestIndex = index
                bestLeadDistance = leadDistance
            }
        }

        return bestIndex
    }

    /**
     * 発話プランに載せる対象の block かを返す。
     *
     * source 距離を対応付けられる (anchor 距離が非 null) ことに加え、**空の announcement スロットを
     * 持つ block (= 無名 advance-notice) は除外**する。手前で地点名を読み上げる advance-notice
     * (「(ビープ) <地点名> ○方向です」) は案内点が無名のとき地点名スロットが空になり、外部データ上は
     * 「(ビープ) ○方向です」という裸の方向句として残る。地点名の無い advance は参照アプリでは発話されず、
     * 鳴らすと「直前案内が遠方で鳴る」誤発話になるため、データの空スロット ([hasBlankAnnouncementSlot]) で
     * 落とす (テキスト一致ではなく構造シグナルで判定)。
     */
    private fun GuideAnnouncementBlock.isAnnounceable(guidancePoint: GuidancePoint): Boolean {
        if (anchor.sourceDistanceFromStartMetres == null) return false
        if (hasBlankAnnouncementSlot) {
            logSkippedNamelessAdvance(guidancePoint, this)
            return false
        }

        return true
    }

    private companion object {

        /** ブロック分解の診断ログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncementBlock"

        /**
         * 汎用テンプレート (「指定なし」相当) の template_id。先頭 piece がこれの block を汎用句とみなし、
         * 同一グループに具体テンプレートの候補があれば選抜時に避ける。外部データの GuideTemplate.template_id
         * 100 に対応。
         */
        const val GENERIC_TEMPLATE_ID = 100

        /**
         * 発話有効範囲がデータに無い block の代用窓の幅 (m)。名目トリガ距離 (delta) から手前側へこの幅だけ
         * 広げた `[delta - 幅, delta]` を残距離窓とする。広くしすぎると遠方トリガ block が手前の案内地点まで
         * 発話可能になり誤発話を生むため、距離段の標準的な間隔程度に抑える。
         */
        const val FALLBACK_WINDOW_METERS = 60
    }
}
