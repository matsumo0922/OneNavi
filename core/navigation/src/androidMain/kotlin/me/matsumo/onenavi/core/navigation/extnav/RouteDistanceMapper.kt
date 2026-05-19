package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable

/**
 * source 距離を geometry 距離へ区分線形で変換する mapper。
 *
 * source 距離は外部 API の summary 距離基準、geometry 距離は OneNavi の
 * polyline 累積距離基準を想定する。
 */
internal class RouteDistanceMapper(
    anchors: List<DistanceAnchor>,
) {
    private val anchors: List<DistanceAnchor> = anchors.normalized()

    fun mapSourceToGeometry(sourceMetres: Double): Double {
        if (anchors.isEmpty() || !sourceMetres.isFinite()) return 0.0
        if (sourceMetres <= anchors.first().sourceMetres) return anchors.first().geometryMetres
        if (sourceMetres >= anchors.last().sourceMetres) return anchors.last().geometryMetres

        val upperIndex = findUpperAnchorIndex(sourceMetres)
        val lowerAnchor = anchors[upperIndex - 1]
        val upperAnchor = anchors[upperIndex]
        val sourceSpanMetres = upperAnchor.sourceMetres - lowerAnchor.sourceMetres
        if (sourceSpanMetres <= 0.0) return lowerAnchor.geometryMetres

        val ratio = (sourceMetres - lowerAnchor.sourceMetres) / sourceSpanMetres
        return lowerAnchor.geometryMetres + (upperAnchor.geometryMetres - lowerAnchor.geometryMetres) * ratio
    }

    private fun findUpperAnchorIndex(sourceMetres: Double): Int {
        var lowIndex = 1
        var highIndex = anchors.lastIndex

        while (lowIndex < highIndex) {
            val middleIndex = (lowIndex + highIndex) / 2
            if (anchors[middleIndex].sourceMetres < sourceMetres) {
                lowIndex = middleIndex + 1
            } else {
                highIndex = middleIndex
            }
        }
        return lowIndex
    }

    private fun List<DistanceAnchor>.normalized(): List<DistanceAnchor> {
        val sortedAnchors = asSequence()
            .filter { anchor -> anchor.sourceMetres.isFinite() && anchor.geometryMetres.isFinite() }
            .sortedWith(
                compareBy<DistanceAnchor> { anchor -> anchor.sourceMetres }
                    .thenBy { anchor -> anchor.geometryMetres },
            )
            .toList()

        val distinctAnchors = mutableListOf<DistanceAnchor>()
        for (anchor in sortedAnchors) {
            if (distinctAnchors.lastOrNull()?.sourceMetres != anchor.sourceMetres) {
                distinctAnchors += anchor
            }
        }

        val monotonicAnchors = mutableListOf<DistanceAnchor>()
        for (anchor in distinctAnchors) {
            val previousAnchor = monotonicAnchors.lastOrNull()
            if (previousAnchor == null ||
                anchor.sourceMetres > previousAnchor.sourceMetres + ANCHOR_SOURCE_TOLERANCE_METRES &&
                anchor.geometryMetres > previousAnchor.geometryMetres + ANCHOR_GEOMETRY_TOLERANCE_METRES
            ) {
                monotonicAnchors += anchor
            }
        }
        return monotonicAnchors
    }

    private companion object {
        private const val ANCHOR_SOURCE_TOLERANCE_METRES: Double = 1.0
        private const val ANCHOR_GEOMETRY_TOLERANCE_METRES: Double = 1.0
    }
}

/**
 * source 距離と geometry 距離の対応点。
 *
 * @param sourceMetres 外部 API の summary 距離基準
 * @param geometryMetres OneNavi の polyline 累積距離基準
 */
@Immutable
internal data class DistanceAnchor(
    val sourceMetres: Double,
    val geometryMetres: Double,
)
