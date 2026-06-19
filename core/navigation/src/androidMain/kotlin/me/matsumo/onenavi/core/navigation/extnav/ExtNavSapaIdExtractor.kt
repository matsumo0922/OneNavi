package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.GuideImageRef as ExtNavGuideImageRef
import me.matsumo.drive.supporter.api.guidance.domain.GuideImageType as ExtNavGuideImageType
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance as ExtNavRouteGuidance
import me.matsumo.drive.supporter.api.sapa.domain.SapaId as ExtNavSapaId

/**
 * 外部ナビ API ライブラリ由来の SA/PA 看板画像参照から、詳細設備 API 用 ID を取り出す helper。
 */
internal object ExtNavSapaIdExtractor {

    /** SA/PA 詳細 API が受け取る ID の桁数。 */
    private const val SAPA_ID_WIDTH: Int = 11

    /**
     * ルート候補が参照する SA/PA 詳細 ID を重複なく集める。
     *
     * @param routeGuidance 対象のルート案内。
     * @return 取得対象の SA/PA 詳細 ID。
     */
    fun collect(routeGuidance: ExtNavRouteGuidance): ImmutableList<ExtNavSapaId> {
        val sapaIds = buildList {
            for (imageRef in routeGuidance.imageIds) {
                imageRef.toSapaIdOrNull()?.let { sapaId -> add(sapaId) }
            }

            for (guidancePoint in routeGuidance.guidancePoints) {
                for (imageRef in guidancePoint.imageRefs) {
                    imageRef.toSapaIdOrNull()?.let { sapaId -> add(sapaId) }
                }
            }

            for (intersection in routeGuidance.intersections) {
                for (imageRef in intersection.imageRefs) {
                    imageRef.toSapaIdOrNull()?.let { sapaId -> add(sapaId) }
                }
            }
        }

        return sapaIds
            .distinctBy { sapaId -> sapaId.value }
            .toImmutableList()
    }

    /**
     * 画像参照列に含まれる最初の SA/PA 詳細 ID を返す。
     *
     * @param imageRefs SA/PA 看板を含みうる画像参照列。
     * @return SA/PA 詳細 ID。無ければ null。
     */
    fun firstFrom(imageRefs: List<ExtNavGuideImageRef>): ExtNavSapaId? =
        imageRefs.firstNotNullOfOrNull { imageRef -> imageRef.toSapaIdOrNull() }

    /**
     * SA/PA 看板画像の minor を詳細 API 用 ID へ変換する。
     */
    private fun ExtNavGuideImageRef.toSapaIdOrNull(): ExtNavSapaId? {
        val isSapaSignboard = type == ExtNavGuideImageType.SaPaSignboard
        if (!isSapaSignboard) return null
        if (minor <= 0) return null

        val paddedValue = minor.toString().padStart(SAPA_ID_WIDTH, '0')
        return runCatching { ExtNavSapaId(paddedValue) }.getOrNull()
    }
}
