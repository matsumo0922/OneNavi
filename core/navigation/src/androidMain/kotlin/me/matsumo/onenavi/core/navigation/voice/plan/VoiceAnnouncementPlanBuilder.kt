package me.matsumo.onenavi.core.navigation.voice.plan

import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
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
     * 案内点に近い 1 つに畳む ([dedupBlocksByPieces])。残った block のうち最も手前 (最小
     * triggerDistanceMetres) の段を FINAL、それ以外を MIDDLE として扱う。
     */
    private fun buildTarget(
        routeId: String,
        guidancePoint: GuidancePoint,
        distanceContext: ExtNavRouteDistanceContext,
        config: VoiceAnnouncementConfig,
    ): AnnouncementTarget? {
        val validBlocks = guidancePoint.announcementBlocks.filter { block -> block.hasMappableAnchor() }

        if (validBlocks.isEmpty()) return null

        val uniqueBlocks = dedupBlocksBySpokenText(validBlocks)
        val finalBlockIndex = selectFinalBlockIndex(uniqueBlocks)
        val geometryMeters = distanceContext.geometryMetresFor(
            sourceMetres = guidancePoint.distanceFromStartMetres.toDouble(),
        )
        val groupIdsWithSpecificPredict = groupIdsWithSpecificPredict(uniqueBlocks, finalBlockIndex)
        val stages = mutableListOf<AnnouncementStage>()

        for (blockIndex in uniqueBlocks.indices) {
            val block = uniqueBlocks[blockIndex]
            val isFinalBlock = blockIndex == finalBlockIndex
            if (!isFinalBlock && !shouldAnnounceAsPredict(block, groupIdsWithSpecificPredict)) {
                logSkippedPredict(guidancePoint, block)
                continue
            }
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
        )
        val middleWindow = if (kind == AnnouncementStageKind.MIDDLE) {
            computeMiddleWindow(
                block = block,
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
        )

        return AnnouncementStage(
            id = id,
            groupKey = groupKey,
            kind = kind,
            triggerSourceMeters = triggerSourceMeters,
            triggerGeometryMeters = triggerGeometryMeters,
            middleWindow = middleWindow,
            pieces = block.pieces,
            categories = block.categories,
        )
    }

    /**
     * MIDDLE 段の発話有効範囲 (距離窓) を geometry 累積距離で求める。
     *
     * 外部データに発話有効範囲 ([GuideAnnouncementBlock.window]、案内点までの残距離) があれば、それを
     * source→geometry 変換して窓にする (案内点に近い端 → 窓の終端、遠い端 → 窓の開始)。範囲が取れない
     * block は暫定的に「自分のトリガ点から案内点まで」を窓とする (同一 group のグループ消費で 1 回に絞られる)。
     */
    private fun computeMiddleWindow(
        block: GuideAnnouncementBlock,
        triggerGeometryMeters: Double,
        gpGeometryMeters: Double,
        distanceContext: ExtNavRouteDistanceContext,
    ): AnnouncementDistanceWindow {
        val window = block.window
        val anchorSource = block.anchor.sourceDistanceFromStartMetres
        if (window == null || anchorSource == null) {
            return AnnouncementDistanceWindow(
                enterGeometryMeters = triggerGeometryMeters,
                exitGeometryMeters = gpGeometryMeters,
            )
        }

        val enterSourceMeters = anchorSource - window.farMetres
        val exitSourceMeters = anchorSource - window.nearMetres

        return AnnouncementDistanceWindow(
            enterGeometryMeters = distanceContext.geometryMetresFor(enterSourceMeters),
            exitGeometryMeters = distanceContext.geometryMetresFor(exitSourceMeters),
        )
    }

    /**
     * 同一案内点内で距離違い候補を束ねる groupKey を組み立てる。
     *
     * 外部データの group_id が有効ならそれで束ね (予告グループ / 直前グループ等が別 key になる)、
     * 0 (未設定) のときは案内点単位の既定グループに束ねる。
     */
    private fun groupKeyOf(
        routeId: String,
        guidancePointIndex: Int,
        groupId: Int,
    ): VoiceAnnouncementId {
        val groupToken = if (groupId != 0) "grp$groupId" else "grpDefault"

        return VoiceAnnouncementId("$routeId#gp$guidancePointIndex#$groupToken")
    }

    /**
     * 非 FINAL block を「予告 (MIDDLE)」として鳴らすべきかを返す。
     *
     * 外部データは同一案内点に、距離手がかり (「○○m先」「まもなく」等) を持つ予告 block と、距離手がかりの
     * 無い裸の方向句 block (「ポーン右方向です」等、汎用テンプレート) を併せ持つことがある。後者は本来
     * 案内点直前 (= FINAL) で鳴らすべき文言で、遠方で鳴ると「直前案内が手前で鳴る」誤発話になる。そこで:
     *
     * - **距離手がかりが無い裸句**は予告にしない (FINAL でのみ鳴らす)。これが大泉ルートの「ポーン右方向です」を
     *   500m 手前で鳴らしていた原因への対処。
     * - **汎用句 (全 piece が generic テンプレート) で、同一 group に距離付きの具体候補がある**場合も予告にしない
     *   (参照実装の generic-avoidance: 具体句があれば汎用句は鳴らさない)。
     *
     * メタ情報 (テンプレート ID) だけでは役割を判別できない (汎用テンプレートが予告にも直前にも現れる) ため、
     * 距離手がかりはテキストで判定する。手がかり語は [DISTANCE_CUE_MARKERS] で調整できる。
     */
    private fun shouldAnnounceAsPredict(
        block: GuideAnnouncementBlock,
        groupIdsWithSpecificPredict: Set<Int>,
    ): Boolean {
        if (!block.hasDistanceCue()) return false
        if (block.isGenericPhrase() && block.groupId in groupIdsWithSpecificPredict) return false

        return true
    }

    /**
     * 非 FINAL block のうち「距離付きの具体予告 (汎用テンプレートでない)」を持つ group_id の集合を返す。
     * generic-avoidance ([shouldAnnounceAsPredict]) で、具体候補がある group の汎用句を抑止するのに使う。
     */
    private fun groupIdsWithSpecificPredict(
        blocks: List<GuideAnnouncementBlock>,
        finalBlockIndex: Int,
    ): Set<Int> = blocks
        .filterIndexed { index, block -> index != finalBlockIndex && block.hasDistanceCue() && !block.isGenericPhrase() }
        .map { block -> block.groupId }
        .toSet()

    /** block の読み上げテキストに距離 / タイミングの手がかり語が含まれるか (「○○m先」「まもなく」等)。 */
    private fun GuideAnnouncementBlock.hasDistanceCue(): Boolean {
        val spokenText = pieces.joinToString(separator = "") { piece -> piece.text }

        return DISTANCE_CUE_MARKERS.any { marker -> spokenText.contains(marker) }
    }

    /** 全 piece が汎用テンプレート ([GENERIC_TEMPLATE_ID]) の block か。具体的な役割テンプレートを持たない。 */
    private fun GuideAnnouncementBlock.isGenericPhrase(): Boolean =
        pieces.isNotEmpty() && pieces.all { piece -> piece.templateRef == GENERIC_TEMPLATE_ID }

    // ---------------------------------------------------------------------
    // 診断ログ (issue #41 Phase 3 実機検証用、確認後に撤去予定)
    // ---------------------------------------------------------------------

    /** 予告 (MIDDLE) として鳴らさず畳んだ block を出力する。裸句の遠方発話抑止 / generic-avoidance の確認用。 */
    private fun logSkippedPredict(guidancePoint: GuidancePoint, block: GuideAnnouncementBlock) {
        Napier.d(tag = TAG) {
            "skip-predict gp=${guidancePoint.index} block=${block.id} group=${block.groupId} " +
                "generic=${block.isGenericPhrase()} cue=${block.hasDistanceCue()} text=\"${spokenTextOf(block)}\""
        }
    }

    /**
     * 段を組む過程の生値をダンプする。anchor 距離と triggerDistance を出して trigGeo を分解し、
     * 「自地点から遠すぎる位置でトリガする block」がどう生成されたかを切り分けるため。
     */
    private fun logBlockBreakdown(
        guidancePoint: GuidancePoint,
        block: GuideAnnouncementBlock,
        kind: AnnouncementStageKind,
        triggerSourceMeters: Double,
        triggerGeometryMeters: Double,
    ) {
        Napier.d(tag = TAG) {
            "gp=${guidancePoint.index} block=${block.id} kind=$kind " +
                "srcGp=${block.anchor.sourceGuidancePointIndex} srcBlockIdx=${block.anchor.sourceBlockIndex} " +
                "anchorSrc=${block.anchor.sourceDistanceFromStartMetres} triggerDist=${block.triggerDistanceMetres} " +
                "trigSrc=$triggerSourceMeters trigGeo=$triggerGeometryMeters " +
                "cats=${block.categories} pieceTmpl=${templateRefsOf(block)} pieceCats=${pieceCategoriesOf(block)} " +
                "text=\"${spokenTextOf(block)}\""
        }
    }

    /** 素片ごとの templateRef 一覧。ブロックの役割 (テンプレート種別) を切り分けるための診断値。 */
    private fun templateRefsOf(block: GuideAnnouncementBlock): List<Int?> =
        block.pieces.map { piece -> piece.templateRef }

    /** 素片ごとの category 一覧。ブロックの役割を切り分けるための診断値。 */
    private fun pieceCategoriesOf(block: GuideAnnouncementBlock): List<GuidanceCategory?> =
        block.pieces.map { piece -> piece.category }

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

    /** source 距離を対応付けられる (anchor 距離が非 null の) block かを返す。 */
    private fun GuideAnnouncementBlock.hasMappableAnchor(): Boolean =
        anchor.sourceDistanceFromStartMetres != null

    private companion object {

        /** ブロック分解の診断ログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncementBlock"

        /**
         * 汎用テンプレート (「指定なし」相当) の template_id。これだけで構成される block は役割が判別できない
         * 汎用句とみなす。外部データの GuideTemplate.template_id 100 に対応。
         */
        const val GENERIC_TEMPLATE_ID = 100

        /**
         * 「予告 (○○m手前で鳴らす案内)」とみなすための距離 / タイミング手がかり語。これらを 1 つも含まない
         * block は裸の方向句 (= 案内点直前で鳴らすべき文言) とみなし、遠方の予告にはしない。
         * テンプレート ID では役割を判別できないためテキストで判定する。実走行ログで調整する想定。
         */
        val DISTANCE_CUE_MARKERS = listOf("先", "まもなく")
    }
}
