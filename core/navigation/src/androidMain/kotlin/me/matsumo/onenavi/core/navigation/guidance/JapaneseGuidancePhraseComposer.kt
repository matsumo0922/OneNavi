package me.matsumo.onenavi.core.navigation.guidance

/**
 * 構造化案内イベントを日本語 TTS 文言へ変換するクラス。
 */
class JapaneseGuidancePhraseComposer {

    fun compose(event: GuidanceEvent): String {
        return when (event) {
            is TurnGuideEvent -> composeTurn(event)
            is LinkedTurnGuideEvent -> composeLinkedTurn(event)
            is HighwayGuideEvent -> composeHighway(event)
            is LaneGuideEvent -> composeLane(event)
            is RerouteGuideEvent -> if (event.offRoute) "ルートから外れました。" else "新しいルートで案内します。"
            is SafetyGuideEvent -> composeSafety(event)
            is AlongRoadGuideEvent -> if (event.bucket == DistanceBucket.KM5) "5km以上道なりです。" else "しばらく道なりです。"
            is WaypointGuideEvent -> event.kind.arrivalPhrase
            is SessionGuideEvent -> composeSession(event)
        }
    }

    private fun composeTurn(event: TurnGuideEvent): String {
        return buildString {
            append(timingPrefix(event.distanceMeters, event.timing))
            appendTargetPhrase(event.targetPhrase)
            append(directionSentence(event.direction))
            appendRoadName(event.roadName, event.targetPhrase)
        }
    }

    private fun composeLinkedTurn(event: LinkedTurnGuideEvent): String {
        return buildString {
            append(timingPrefix(event.distanceMeters, TurnTiming.MIDDLE))
            appendTargetPhrase(event.firstTargetPhrase)
            append(directionComma(event.firstDirection))
            append("その先、")
            appendTargetPhrase(event.nextTargetPhrase)
            append(nextSentence(event))
        }
    }

    private fun composeHighway(event: HighwayGuideEvent): String {
        val name = event.name?.takeIf { it.isNotBlank() }
        val prefix = event.distanceMeters
            ?.let { distanceMeters ->
                timingPrefix(
                    distanceMeters = distanceMeters,
                    timing = if (distanceMeters <= 50.0) TurnTiming.SOON else TurnTiming.MIDDLE,
                )
            }
            .orEmpty()
        val namePhrase = name?.let { "$it、" }.orEmpty()
        return when (event.kind) {
            HighwayGuideKind.ENTER -> "${prefix}${namePhrase}高速入口です。"
            HighwayGuideKind.EXIT -> "${prefix}${namePhrase}高速出口です。"
            HighwayGuideKind.FORK -> "${prefix}${namePhrase}分岐です。"
            HighwayGuideKind.MERGE -> "${prefix}${mergeSentence(event.direction)}"
            HighwayGuideKind.TOLL_GATE -> "${prefix}料金所です。"
            HighwayGuideKind.ROAD_KIND_CHANGED -> "高速道のルートに切り替わりました。"
        }
    }

    private fun composeLane(event: LaneGuideEvent): String {
        val laneText = when {
            event.validLaneIndices.first() == 0 -> "左側の車線"
            event.validLaneIndices.last() == event.laneCount - 1 -> "右側の車線"
            else -> "左から${event.validLaneIndices.first() + 1}番目の車線"
        }
        val prefix = if (event.distanceMeters <= 120.0) "まもなく、" else "この先、"
        return "${prefix}${laneText}を、お進みください。"
    }

    private fun composeSafety(event: SafetyGuideEvent): String {
        val prefix = if (event.distanceMeters <= 120.0) "まもなく、" else "${DistanceBucket.fromMeters(event.distanceMeters).aheadLabel}、"
        return when (event.kind) {
            SafetyGuideKind.RAILWAY_CROSSING -> "${prefix}踏切です。"
            SafetyGuideKind.STOP_SIGN -> if (event.distanceMeters <= 120.0) "一時停止です。" else "${prefix}一時停止です。"
            SafetyGuideKind.CROSSWALK -> "${prefix}横断歩道があります。"
        }
    }

    private fun composeSession(event: SessionGuideEvent): String {
        return when (event.kind) {
            SessionGuideKind.START -> "音声案内を開始します。実際の交通規制に従って走行してください。"
            SessionGuideKind.STOP -> "音声案内を終了します。"
            SessionGuideKind.PAUSE -> "音声案内を中断します。"
            SessionGuideKind.RESUME -> "音声案内を継続します。"
            SessionGuideKind.TIMEOUT_WARNING -> "あと5分で、音声案内を中断します。"
            SessionGuideKind.TIMEOUT -> "一定時間が経過したため、音声案内を中断します。"
        }
    }

    private fun timingPrefix(
        distanceMeters: Double,
        timing: TurnTiming,
    ): String {
        return if (timing == TurnTiming.SOON || distanceMeters <= 50.0) {
            "まもなく、"
        } else {
            "${DistanceBucket.fromMeters(distanceMeters).aheadLabel}、"
        }
    }

    private fun StringBuilder.appendTargetPhrase(targetPhrase: String?) {
        targetPhrase
            ?.takeIf { it.isNotBlank() }
            ?.let {
                append(it)
                append("、")
            }
    }

    private fun StringBuilder.appendRoadName(
        roadName: String?,
        targetPhrase: String?,
    ) {
        val name = roadName?.takeIf { it.isNotBlank() } ?: return
        if (targetPhrase?.contains(name) == true) return
        append(name)
        append("へ進みます。")
    }

    private fun nextSentence(event: LinkedTurnGuideEvent): String {
        return when (event.nextManeuverType) {
            "fork" -> "分岐です。"
            "on ramp" -> "高速入口です。"
            "off ramp" -> "高速出口です。"
            "merge" -> mergeSentence(event.nextDirection)
            else -> directionSentence(event.nextDirection)
        }
    }

    private fun directionSentence(direction: Direction): String {
        return when (direction) {
            Direction.LEFT -> "左方向です。"
            Direction.RIGHT -> "右方向です。"
            Direction.SLIGHT_LEFT -> "斜め左方向です。"
            Direction.SLIGHT_RIGHT -> "斜め右方向です。"
            Direction.SHARP_LEFT -> "左手前方向です。"
            Direction.SHARP_RIGHT -> "右手前方向です。"
            Direction.STRAIGHT -> "直進です。"
            Direction.UTURN -> "戻る方向です。"
            Direction.UNKNOWN -> "案内方向です。"
        }
    }

    private fun directionComma(direction: Direction): String {
        return when (direction) {
            Direction.LEFT -> "左方向、"
            Direction.RIGHT -> "右方向、"
            Direction.SLIGHT_LEFT -> "斜め左方向、"
            Direction.SLIGHT_RIGHT -> "斜め右方向、"
            Direction.SHARP_LEFT -> "左手前方向、"
            Direction.SHARP_RIGHT -> "右手前方向、"
            Direction.STRAIGHT -> "直進方向、"
            Direction.UTURN -> "戻る方向、"
            Direction.UNKNOWN -> "案内方向、"
        }
    }

    private fun mergeSentence(direction: Direction): String {
        return when (direction) {
            Direction.RIGHT,
            Direction.SLIGHT_RIGHT,
            Direction.SHARP_RIGHT,
            -> "右からの合流が有ります。"
            Direction.LEFT,
            Direction.SLIGHT_LEFT,
            Direction.SHARP_LEFT,
            -> "左からの合流が有ります。"
            else -> "合流が有ります。"
        }
    }
}
