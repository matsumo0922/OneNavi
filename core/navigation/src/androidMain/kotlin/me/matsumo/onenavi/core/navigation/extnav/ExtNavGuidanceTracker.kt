package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.Guidance
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.Intersection
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker.Companion.BACKWARD_TOLERANCE_METRES
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker.Companion.SEGMENT_SEARCH_WINDOW

/**
 * 現在地を [Guidance.polyline] に射影して進捗 (累積 m) を求め、
 * そこから残距離 / 次 GP / 次 Intersection / off-route 判定を提供する。
 *
 * 旧実装 (intersections 最近傍) は intersections が sparse だと progressedMetres が
 * 前に進まず、次 GP へ切り替わらなかった。dense polyline (~40-80m 間隔) を
 * ベースにすることで、実走 m 単位の細かい進捗と安定した GP 切替を実現する。
 *
 * 要点:
 * - attach 時に polyline の累積距離配列と各 Intersection の polyline 上の投影 m を precompute
 * - onLocation ごとに「前回セグメント窓 ± [SEGMENT_SEARCH_WINDOW]」内の最近傍セグメントを探索
 * - progressedMetres は monotonic。後退は [BACKWARD_TOLERANCE_METRES] まで許容
 * - [ExtNavProgressSnapshot.nearestIntersectionDistanceMetres] はポリラインまでの垂線距離
 *   (off-route 判定用)
 */
class ExtNavGuidanceTracker {

    private val _state = MutableStateFlow<ExtNavProgressSnapshot?>(null)
    val state: StateFlow<ExtNavProgressSnapshot?> = _state.asStateFlow()

    private var guidance: Guidance? = null
    private var averageMetresPerSecond: Double = DEFAULT_SPEED_MPS

    private var polylineCumMetres: DoubleArray = DoubleArray(0)
    private var intersectionProgressMetres: DoubleArray = DoubleArray(0)
    private var totalPolylineMetres: Double = 0.0
    private var lastProgressMetres: Double = 0.0
    private var lastSegmentIndex: Int = 0

    fun attach(guidance: Guidance) {
        this.guidance = guidance
        averageMetresPerSecond = run {
            val totalMetres = guidance.summary.distanceMetres.toDouble()
            val totalSeconds = guidance.summary.timeSeconds.toDouble().coerceAtLeast(1.0)
            (totalMetres / totalSeconds).coerceAtLeast(MIN_SPEED_MPS)
        }
        precomputePolyline(guidance)
        lastProgressMetres = 0.0
        lastSegmentIndex = 0
        _state.value = null
    }

    fun detach() {
        guidance = null
        polylineCumMetres = DoubleArray(0)
        intersectionProgressMetres = DoubleArray(0)
        totalPolylineMetres = 0.0
        _state.value = null
    }

    fun onLocation(latitude: Double, longitude: Double) {
        val guide = guidance ?: return
        val polyline = guide.polyline
        if (polyline.size < 2 || polylineCumMetres.size != polyline.size) {
            _state.value = null
            return
        }

        val projection = projectToPolyline(polyline, latitude, longitude)

        val rawProgress = projection.progressMetres
        val monotonicProgress = rawProgress.coerceAtLeast(
            lastProgressMetres - BACKWARD_TOLERANCE_METRES,
        ).let { candidate ->
            if (rawProgress < lastProgressMetres - MAX_BACKWARD_METRES) {
                lastProgressMetres
            } else {
                candidate
            }
        }
        lastProgressMetres = monotonicProgress
        lastSegmentIndex = projection.segmentIndex

        val totalMetres = guide.summary.distanceMetres
            .toDouble()
            .coerceAtLeast(totalPolylineMetres)
        val remainingMetres = (totalMetres - monotonicProgress).coerceAtLeast(0.0)
        val remainingSeconds = (remainingMetres / averageMetresPerSecond).coerceAtLeast(0.0)

        val nextIntersectionIndex = findNextIntersection(monotonicProgress)
        val nextIntersection = nextIntersectionIndex?.let { guide.intersections[it] }
        val nearestIntersectionIndexForSnapshot = nextIntersectionIndex
            ?: guide.intersections.lastIndex.coerceAtLeast(0)

        val upcomingGuidancePoints = guide.guidancePoints.filter { guidancePoint ->
            guidancePoint.distanceFromStartMetres >= monotonicProgress - GP_EPSILON_METRES
        }
        val nextGuidancePoint = upcomingGuidancePoints.firstOrNull()
        val distanceToNextGpMetres = nextGuidancePoint?.let {
            (it.distanceFromStartMetres - monotonicProgress).coerceAtLeast(0.0)
        }

        _state.update {
            ExtNavProgressSnapshot(
                nearestIntersectionIndex = nearestIntersectionIndexForSnapshot,
                nearestIntersectionDistanceMetres = projection.perpendicularMetres,
                progressedMetres = monotonicProgress,
                remainingMetres = remainingMetres,
                remainingSeconds = remainingSeconds,
                nextIntersection = nextIntersection,
                nextGuidancePoint = nextGuidancePoint,
                distanceToNextGuidancePointMetres = distanceToNextGpMetres,
                upcomingGuidancePoints = upcomingGuidancePoints,
            )
        }
    }

    private fun precomputePolyline(guidance: Guidance) {
        val polyline = guidance.polyline
        if (polyline.size < 2) {
            polylineCumMetres = DoubleArray(0)
            intersectionProgressMetres = DoubleArray(0)
            totalPolylineMetres = 0.0
            return
        }
        val cumulative = DoubleArray(polyline.size)
        for (index in 1 until polyline.size) {
            val prev = polyline[index - 1]
            val curr = polyline[index]
            cumulative[index] = cumulative[index - 1] + haversineMetres(
                prev.latDegrees,
                prev.lonDegrees,
                curr.latDegrees,
                curr.lonDegrees,
            )
        }
        polylineCumMetres = cumulative
        totalPolylineMetres = cumulative.last()

        intersectionProgressMetres = DoubleArray(guidance.intersections.size) { index ->
            val position = guidance.intersections[index].position
            projectToPolyline(polyline, position.latDegrees, position.lonDegrees).progressMetres
        }
    }

    private fun projectToPolyline(
        polyline: List<Coord>,
        latitude: Double,
        longitude: Double,
    ): PolylineProjection {
        val windowStart = (lastSegmentIndex - SEGMENT_SEARCH_WINDOW).coerceAtLeast(0)
        val windowEnd = (lastSegmentIndex + SEGMENT_SEARCH_WINDOW).coerceAtMost(polyline.size - 2)

        val windowed = searchBestSegment(polyline, latitude, longitude, windowStart, windowEnd)
        if (windowed.perpendicularMetres <= ON_ROUTE_THRESHOLD_METRES) {
            return windowed
        }
        // 窓外に出た可能性があるので全体を再スキャンして救済
        val fullScan = searchBestSegment(polyline, latitude, longitude, 0, polyline.size - 2)
        return if (fullScan.perpendicularMetres < windowed.perpendicularMetres) fullScan else windowed
    }

    private fun searchBestSegment(
        polyline: List<Coord>,
        latitude: Double,
        longitude: Double,
        fromIndex: Int,
        toIndex: Int,
    ): PolylineProjection {
        var bestSegment = fromIndex
        var bestDistance = Double.MAX_VALUE
        var bestProgress = polylineCumMetres.getOrElse(fromIndex) { 0.0 }
        for (segmentIndex in fromIndex..toIndex) {
            val start = polyline[segmentIndex]
            val end = polyline[segmentIndex + 1]
            val projection = pointToSegmentMetres(
                pointLat = latitude,
                pointLng = longitude,
                startLat = start.latDegrees,
                startLng = start.lonDegrees,
                endLat = end.latDegrees,
                endLng = end.lonDegrees,
            )
            if (projection.distanceMetres < bestDistance) {
                bestDistance = projection.distanceMetres
                bestSegment = segmentIndex
                val segmentLength = polylineCumMetres[segmentIndex + 1] -
                    polylineCumMetres[segmentIndex]
                bestProgress = polylineCumMetres[segmentIndex] + segmentLength * projection.t
            }
        }
        return PolylineProjection(
            segmentIndex = bestSegment,
            progressMetres = bestProgress,
            perpendicularMetres = bestDistance,
        )
    }

    private fun findNextIntersection(progressMetres: Double): Int? {
        for (index in intersectionProgressMetres.indices) {
            // 「まだ通過していない」intersection。PASSED_TOLERANCE 分の通過直後は
            // 引き続き「次」として提示し続け、UI がちらつかないようにする。
            if (intersectionProgressMetres[index] >=
                progressMetres - INTERSECTION_PASSED_TOLERANCE_METRES) {
                return index
            }
        }
        return null
    }

    private data class PolylineProjection(
        val segmentIndex: Int,
        val progressMetres: Double,
        val perpendicularMetres: Double,
    )

    private data class SegmentProjection(
        val distanceMetres: Double,
        val t: Double,
    )

    companion object {
        internal const val DEFAULT_SPEED_MPS: Double = 13.89 // 約 50 km/h
        internal const val MIN_SPEED_MPS: Double = 5.0 // 約 18 km/h (詰まり時の下限)

        /** 前回セグメントから前後このセグメント数だけ先にウィンドウ探索する */
        private const val SEGMENT_SEARCH_WINDOW: Int = 8

        /** ウィンドウ探索結果がこれより離れていたら全体を再スキャン */
        private const val ON_ROUTE_THRESHOLD_METRES: Double = 40.0

        /** 直前の progressedMetres から「多少」戻ることを許す幅 (GPS 揺れ吸収) */
        internal const val BACKWARD_TOLERANCE_METRES: Double = 15.0

        /** 直前 progressedMetres からこれ以上戻る値は棄却 (U-turn / 近接区間誤判定防止) */
        internal const val MAX_BACKWARD_METRES: Double = 80.0

        /** intersection を通過後もこの距離までは「次」として提示し続ける */
        private const val INTERSECTION_PASSED_TOLERANCE_METRES: Double = 10.0

        /** upcoming GP を通過後もこの距離までは候補に残す */
        private const val GP_EPSILON_METRES: Double = 5.0

        internal fun haversineMetres(
            lat1: Double,
            lng1: Double,
            lat2: Double,
            lng2: Double,
        ): Double {
            val earthRadius = 6_371_000.0
            val deltaLat = Math.toRadians(lat2 - lat1)
            val deltaLng = Math.toRadians(lng2 - lng1)
            val a = kotlin.math.sin(deltaLat / 2) * kotlin.math.sin(deltaLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(deltaLng / 2) * kotlin.math.sin(deltaLng / 2)
            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            return earthRadius * c
        }

        /**
         * 点を線分に射影。平面近似 (equirectangular, 平均緯度基準) で数 km 範囲なら十分。
         * 戻り値は (点→線分の最短距離 m, 線分上の正規化位置 t∈[0,1])。
         */
        private fun pointToSegmentMetres(
            pointLat: Double,
            pointLng: Double,
            startLat: Double,
            startLng: Double,
            endLat: Double,
            endLng: Double,
        ): SegmentProjection {
            val refLat = Math.toRadians((startLat + endLat) / 2.0)
            val metresPerDegLat = 111_320.0
            val metresPerDegLng = 111_320.0 * kotlin.math.cos(refLat)

            val bx = (endLng - startLng) * metresPerDegLng
            val by = (endLat - startLat) * metresPerDegLat
            val px = (pointLng - startLng) * metresPerDegLng
            val py = (pointLat - startLat) * metresPerDegLat

            val lengthSquared = bx * bx + by * by
            val t = if (lengthSquared < EPSILON) {
                0.0
            } else {
                ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
            }
            val projX = bx * t
            val projY = by * t
            val dx = px - projX
            val dy = py - projY
            return SegmentProjection(
                distanceMetres = kotlin.math.sqrt(dx * dx + dy * dy),
                t = t,
            )
        }

        private const val EPSILON: Double = 1e-9
    }
}

/**
 * 現在の進捗スナップショット。UI 表示とアナウンス判定に用いる。
 */
@Immutable
data class ExtNavProgressSnapshot(
    /** 進行方向で次の Intersection のインデックス。終端に達したら末尾 index が返る */
    val nearestIntersectionIndex: Int,
    /** 現在地から polyline への垂線距離 (m)。off-route 判定の proxy */
    val nearestIntersectionDistanceMetres: Double,
    /** 出発地からの走行距離 (m)。polyline 射影 + monotonic clamp */
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
