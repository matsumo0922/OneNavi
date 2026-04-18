package me.matsumo.onenavi.core.navigation.guidance

import me.matsumo.onenavi.core.model.ManeuverModifier

/**
 * 構造化案内イベントを日本語 TTS 文言へ変換するクラス。
 */
class JapaneseGuidancePhraseComposer {

    fun compose(event: GuidanceEvent): String {
        return when (event) {
            is TurnGuideEvent -> composeTurn(event)
            is LinkedTurnGuideEvent -> "${directionText(event.firstDirection)}方向、その先${turnActionText(event.nextDirection)}です"
            is HighwayGuideEvent -> composeHighway(event)
            is LaneGuideEvent -> composeLane(event)
            is RerouteGuideEvent -> if (event.offRoute) "ルートから外れました。再計算します" else "新しいルートで案内します"
            is SafetyGuideEvent -> composeSafety(event)
            is AlongRoadGuideEvent -> "${event.bucket.label}以上、道なりです"
            is WaypointGuideEvent -> event.kind.arrivalPhrase
            is SessionGuideEvent -> composeSession(event)
        }
    }

    private fun composeTurn(event: TurnGuideEvent): String {
        val action = turnActionText(event.direction)
        return when (event.timing) {
            TurnTiming.SOON -> "まもなく${action}です"
            TurnTiming.MIDDLE,
            TurnTiming.FAR,
            -> "${DistanceBucket.fromMeters(event.distanceMeters).label}先、${action}です"
        }
    }

    private fun composeHighway(event: HighwayGuideEvent): String {
        val name = event.name?.takeIf { it.isNotBlank() }
        return when (event.kind) {
            HighwayGuideKind.ENTER -> name?.let { "${it}方面、高速道路に入ります" } ?: "高速道路に入ります"
            HighwayGuideKind.EXIT -> name?.let { "${it}方面、出口です" } ?: "出口です"
            HighwayGuideKind.FORK -> "${directionText(event.direction)}方向です"
            HighwayGuideKind.MERGE -> "合流です、ご注意ください"
            HighwayGuideKind.TOLL_GATE -> "まもなく料金所です"
            HighwayGuideKind.ROAD_KIND_CHANGED -> "道路種別が変わります"
        }
    }

    private fun composeLane(event: LaneGuideEvent): String {
        val laneText = when {
            event.validLaneIndices.first() == 0 -> "左側の車線"
            event.validLaneIndices.last() == event.laneCount - 1 -> "右側の車線"
            else -> "左から${event.validLaneIndices.first() + 1}番目の車線"
        }
        return "${laneText}をお進みください"
    }

    private fun composeSafety(event: SafetyGuideEvent): String {
        val prefix = if (event.distanceMeters <= 120.0) "まもなく" else "${DistanceBucket.fromMeters(event.distanceMeters).label}先、"
        return when (event.kind) {
            SafetyGuideKind.RAILWAY_CROSSING -> "${prefix}踏切があります"
            SafetyGuideKind.STOP_SIGN -> "${prefix}一時停止があります"
            SafetyGuideKind.CROSSWALK -> "${prefix}横断歩道があります"
        }
    }

    private fun composeSession(event: SessionGuideEvent): String {
        return when (event.kind) {
            SessionGuideKind.START -> "音声案内を開始します。実際の交通規制に従って、走行してください。"
            SessionGuideKind.STOP -> "音声案内を終了します"
            SessionGuideKind.PAUSE -> "音声案内を中断します"
            SessionGuideKind.RESUME -> "音声案内を継続します"
            SessionGuideKind.TIMEOUT_WARNING -> "あと5分で音声案内を中断します"
            SessionGuideKind.TIMEOUT -> "一定時間が経過したため、音声案内を中断します"
        }
    }

    private fun turnActionText(direction: ManeuverModifier): String {
        return when (direction) {
            ManeuverModifier.LEFT -> "左折"
            ManeuverModifier.RIGHT -> "右折"
            ManeuverModifier.SLIGHT_LEFT -> "斜め左"
            ManeuverModifier.SLIGHT_RIGHT -> "斜め右"
            ManeuverModifier.SHARP_LEFT -> "鋭角左折"
            ManeuverModifier.SHARP_RIGHT -> "鋭角右折"
            ManeuverModifier.STRAIGHT -> "直進"
            ManeuverModifier.UTURN -> "Uターン"
            ManeuverModifier.UNKNOWN -> "案内方向へ進行"
        }
    }

    private fun directionText(direction: ManeuverModifier): String {
        return when (direction) {
            ManeuverModifier.LEFT,
            ManeuverModifier.SLIGHT_LEFT,
            ManeuverModifier.SHARP_LEFT,
            -> "左"
            ManeuverModifier.RIGHT,
            ManeuverModifier.SLIGHT_RIGHT,
            ManeuverModifier.SHARP_RIGHT,
            -> "右"
            ManeuverModifier.STRAIGHT -> "直進"
            ManeuverModifier.UTURN -> "Uターン"
            ManeuverModifier.UNKNOWN -> "案内"
        }
    }
}
