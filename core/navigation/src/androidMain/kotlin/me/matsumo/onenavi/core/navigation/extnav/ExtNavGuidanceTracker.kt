package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.matsumo.drive.supporter.api.guidance.domain.Guidance
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.Intersection

/**
 * 現在地と [Guidance] の [GuidancePoint] 列をつき合わせ、進捗 / 次 GP / off-route 判定を提供する。
 *
 * Phase 1 の最小実装:
 * - 最近傍 Intersection のインデックスを決定し、[GuidancePoint] は `index` ではなく
 *   `distanceFromStartMetres` で紐付けたうえで走行順の次の GP を返す
 * - 残距離 = ルート総距離 - 開始からの累積距離
 * - 残時間 = 残距離 / 平均速度 (summary から算出)
 * - offRouteDistance = 最近傍 intersection 位置までの距離
 */
class ExtNavGuidanceTracker {

    private val _state = MutableStateFlow<ExtNavProgressSnapshot?>(null)
    val state: StateFlow<ExtNavProgressSnapshot?> = _state.asStateFlow()

    private var guidance: Guidance? = null
    private var averageMetresPerSecond: Double = DEFAULT_SPEED_MPS

    /**
     * セッション開始時にルートを紐付ける。以降の [onLocation] で使用される。
     */
    fun attach(guidance: Guidance) {
        this.guidance = guidance
        averageMetresPerSecond = run {
            val totalMetres = guidance.summary.distanceMetres.toDouble()
            val totalSeconds = guidance.summary.timeSeconds.toDouble().coerceAtLeast(1.0)
            (totalMetres / totalSeconds).coerceAtLeast(MIN_SPEED_MPS)
        }
        _state.value = null
    }

    fun detach() {
        guidance = null
        _state.value = null
    }

    /**
     * map-matched 現在地で進捗を更新する。呼び出し側 (SessionManager) が [RoadSnappedLocationProvider]
     * を購読して、このメソッドを定期的に呼ぶ。
     */
    fun onLocation(latitude: Double, longitude: Double) {
        val guide = guidance ?: return
        if (guide.intersections.isEmpty()) {
            _state.value = null
            return
        }

        val (nearestIndex, nearestDistanceMetres) = findNearestIntersection(
            intersections = guide.intersections,
            latitude = latitude,
            longitude = longitude,
        )

        val progressedMetres = progressMetresAt(guide, nearestIndex)
        val remainingMetres = (guide.summary.distanceMetres - progressedMetres).coerceAtLeast(0.0)
        val remainingSeconds = (remainingMetres / averageMetresPerSecond).coerceAtLeast(0.0)

        val upcomingIntersections = guide.intersections.drop(nearestIndex + 1)
        val nextIntersection = upcomingIntersections.firstOrNull()

        val upcomingGuidancePoints = guide.guidancePoints
            .filter { it.distanceFromStartMetres >= progressedMetres }
        val nextGuidancePoint = upcomingGuidancePoints.firstOrNull()
        val distanceToNextGpMetres = nextGuidancePoint?.let {
            (it.distanceFromStartMetres - progressedMetres).coerceAtLeast(0.0)
        }

        _state.update {
            ExtNavProgressSnapshot(
                nearestIntersectionIndex = nearestIndex,
                nearestIntersectionDistanceMetres = nearestDistanceMetres,
                progressedMetres = progressedMetres,
                remainingMetres = remainingMetres,
                remainingSeconds = remainingSeconds,
                nextIntersection = nextIntersection,
                nextGuidancePoint = nextGuidancePoint,
                distanceToNextGuidancePointMetres = distanceToNextGpMetres,
                upcomingGuidancePoints = upcomingGuidancePoints,
            )
        }
    }

    private fun findNearestIntersection(
        intersections: List<Intersection>,
        latitude: Double,
        longitude: Double,
    ): Pair<Int, Double> {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for ((index, intersection) in intersections.withIndex()) {
            val distance = haversineMetres(
                latitude,
                longitude,
                intersection.position.latDegrees,
                intersection.position.lonDegrees,
            )
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex to bestDistance
    }

    private fun progressMetresAt(guidance: Guidance, intersectionIndex: Int): Double {
        val intersections = guidance.intersections
        if (intersections.isEmpty()) return 0.0
        val clamped = intersectionIndex.coerceIn(0, intersections.lastIndex)
        if (clamped == 0) return 0.0
        var cumulative = 0.0
        for (nextIndex in 1..clamped) {
            val prev = intersections[nextIndex - 1].position
            val curr = intersections[nextIndex].position
            cumulative += haversineMetres(
                prev.latDegrees,
                prev.lonDegrees,
                curr.latDegrees,
                curr.lonDegrees,
            )
        }
        return cumulative
    }

    companion object {
        internal const val DEFAULT_SPEED_MPS: Double = 13.89 // 約 50 km/h
        internal const val MIN_SPEED_MPS: Double = 5.0 // 約 18 km/h (詰まり時の下限)

        internal fun haversineMetres(
            lat1: Double,
            lng1: Double,
            lat2: Double,
            lng2: Double,
        ): Double {
            val earthRadius = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            return earthRadius * c
        }
    }
}

/**
 * 現在の進捗スナップショット。UI 表示とアナウンス判定に用いる。
 */
@Immutable
data class ExtNavProgressSnapshot(
    /** 最も近い Intersection のインデックス (0-origin) */
    val nearestIntersectionIndex: Int,
    /** 最近傍 Intersection までの距離 (m)。これが off-route 判定のプロキシ */
    val nearestIntersectionDistanceMetres: Double,
    /** 出発地からの走行距離 (m) 近似 */
    val progressedMetres: Double,
    /** 残距離 (m) */
    val remainingMetres: Double,
    /** 残所要時間 (s) */
    val remainingSeconds: Double,
    /** 次の Intersection。終端に達していたら null */
    val nextIntersection: Intersection?,
    /** 次の GuidancePoint。終端に達していたら null */
    val nextGuidancePoint: GuidancePoint?,
    /** 次 GP までの距離 (m)。next が null なら null */
    val distanceToNextGuidancePointMetres: Double?,
    /** 進行方向に残っている GuidancePoint の並び。発話スケジューラが消費する */
    val upcomingGuidancePoints: List<GuidancePoint>,
)
