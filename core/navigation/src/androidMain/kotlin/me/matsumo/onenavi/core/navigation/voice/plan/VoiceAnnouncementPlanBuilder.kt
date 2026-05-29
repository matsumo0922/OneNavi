package me.matsumo.onenavi.core.navigation.voice.plan

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
     * source 距離を対応付けられない block (anchor 距離が null) は除外する。残った block のうち
     * 最も手前 (最小 triggerDistanceMetres) の段を FINAL、それ以外を MIDDLE として扱う。
     */
    private fun buildTarget(
        routeId: String,
        guidancePoint: GuidancePoint,
        distanceContext: ExtNavRouteDistanceContext,
        config: VoiceAnnouncementConfig,
    ): AnnouncementTarget? {
        val validBlocks = guidancePoint.announcementBlocks.filter { block -> block.hasMappableAnchor() }

        if (validBlocks.isEmpty()) return null

        val finalBlockIndex = selectFinalBlockIndex(validBlocks)
        val stages = mutableListOf<AnnouncementStage>()

        for (blockIndex in validBlocks.indices) {
            val block = validBlocks[blockIndex]
            val blockStages = buildStagesForBlock(
                routeId = routeId,
                guidancePoint = guidancePoint,
                block = block,
                isFinalBlock = blockIndex == finalBlockIndex,
                distanceContext = distanceContext,
                config = config,
            )
            stages += blockStages
        }

        val geometryMeters = distanceContext.geometryMetresFor(
            sourceMetres = guidancePoint.distanceFromStartMetres.toDouble(),
        )

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
     * 数だけ複製し、無ければ block 自身の triggerDistanceMetres で 1 段にする。
     */
    private fun buildStagesForBlock(
        routeId: String,
        guidancePoint: GuidancePoint,
        block: GuideAnnouncementBlock,
        isFinalBlock: Boolean,
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
                distanceContext = distanceContext,
            )
            stages += stage
        }

        return stages
    }

    /**
     * 単一の距離段を組み立てる。source トリガ距離を geometry 距離へ変換して保持する。
     */
    private fun buildStage(
        routeId: String,
        guidancePoint: GuidancePoint,
        block: GuideAnnouncementBlock,
        triggerSourceMeters: Double,
        kind: AnnouncementStageKind,
        idSuffix: String?,
        distanceContext: ExtNavRouteDistanceContext,
    ): AnnouncementStage {
        val triggerGeometryMeters = distanceContext.geometryMetresFor(triggerSourceMeters)
        val id = buildStageId(
            routeId = routeId,
            guidancePointIndex = guidancePoint.index,
            blockId = block.id,
            idSuffix = idSuffix,
        )

        return AnnouncementStage(
            id = id,
            kind = kind,
            triggerSourceMeters = triggerSourceMeters,
            triggerGeometryMeters = triggerGeometryMeters,
            pieces = block.pieces,
            categories = block.categories,
        )
    }

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
}
