package me.matsumo.onenavi.core.navigation

import com.mapbox.api.directions.v5.models.VoiceInstructions

/**
 * Mapbox VoiceInstructions を日本語音声案内テキストに変換するジェネレータ。
 * 設計書 §8.3 の 17 テンプレートを実装する。
 */
object JapaneseAnnouncementGenerator {

    private const val NEARBY_THRESHOLD_METERS = 100.0

    /** リルート完了時の案内テキスト。 */
    const val REROUTE_ANNOUNCEMENT = "ルートを再計算しました"

    /**
     * VoiceInstructions から日本語の案内テキストを生成する。
     * 対応するテンプレートがない場合は null を返し、呼び出し側で Mapbox デフォルトの announcement を使用する。
     */
    fun generate(voiceInstructions: VoiceInstructions): String? {
        val announcement = voiceInstructions.announcement() ?: return null
        val distanceAlongGeometry = voiceInstructions.distanceAlongGeometry()

        val type = extractManeuverType(announcement)
        val modifier = extractManeuverModifier(announcement)

        val isNearby = distanceAlongGeometry != null && distanceAlongGeometry < NEARBY_THRESHOLD_METERS

        return generateFromTemplate(type, modifier, distanceAlongGeometry, isNearby, announcement)
    }

    private fun generateFromTemplate(
        type: String?,
        modifier: String?,
        distanceAlongGeometry: Double?,
        isNearby: Boolean,
        originalAnnouncement: String,
    ): String? {
        val distanceText = distanceAlongGeometry?.let { formatDistanceForSpeech(it) }

        return when {
            // テンプレート #12: 最終目的地到着
            type == "arrive" && !isWaypointArrival(originalAnnouncement) ->
                "目的地に到着しました"

            // テンプレート #13: 経由地到着
            type == "arrive" && isWaypointArrival(originalAnnouncement) ->
                "経由地に到着しました"

            // テンプレート #15: Uターン
            modifier == "uturn" ->
                if (isNearby) "まもなくUターンです" else "${distanceText}先、Uターンです"

            // テンプレート #5: まもなく左折
            isNearby && modifier == "left" ->
                "まもなく左折です"

            // テンプレート #6: まもなく右折
            isNearby && modifier == "right" ->
                "まもなく右折です"

            // テンプレート #16: 鋭角左折
            modifier == "sharp left" ->
                if (isNearby) "まもなく鋭角左折です" else "${distanceText}先、鋭角左折です"

            // テンプレート #17: 鋭角右折
            modifier == "sharp right" ->
                if (isNearby) "まもなく鋭角右折です" else "${distanceText}先、鋭角右折です"

            // テンプレート #1: 左折
            modifier == "left" && distanceText != null ->
                "${distanceText}先、左折です"

            // テンプレート #2: 右折
            modifier == "right" && distanceText != null ->
                "${distanceText}先、右折です"

            // テンプレート #3: 斜め左
            modifier == "slight left" ->
                if (isNearby) "まもなく斜め左です" else "${distanceText}先、斜め左です"

            // テンプレート #4: 斜め右
            modifier == "slight right" ->
                if (isNearby) "まもなく斜め右です" else "${distanceText}先、斜め右です"

            // テンプレート #7: 直進
            modifier == "straight" ->
                "直進です"

            // テンプレート #8: 合流
            type == "merge" ->
                "合流です、ご注意ください"

            // テンプレート #9: 分岐左
            type == "fork" && modifier == "left" ->
                "左方向です"

            // テンプレート #10: 分岐右
            type == "fork" && modifier == "right" ->
                "右方向です"

            // テンプレート #11: 出口
            type == "off ramp" -> {
                val name = extractDestinationName(originalAnnouncement)
                if (name != null) "${name}方面、出口です" else "出口です"
            }

            else -> null
        }
    }

    private fun formatDistanceForSpeech(meters: Double): String {
        return if (meters < 1000) {
            "${meters.toInt()}メートル"
        } else {
            val km = meters / 1000
            if (km % 1.0 < 0.05) {
                "${km.toInt()}キロ"
            } else {
                "%.1fキロ".format(km)
            }
        }
    }

    private fun extractManeuverType(announcement: String): String? {
        val lowerAnnouncement = announcement.lowercase()
        return when {
            lowerAnnouncement.contains("arrive") || lowerAnnouncement.contains("到着") -> "arrive"
            lowerAnnouncement.contains("merge") || lowerAnnouncement.contains("合流") -> "merge"
            lowerAnnouncement.contains("fork") || lowerAnnouncement.contains("分岐") -> "fork"
            lowerAnnouncement.contains("off ramp") || lowerAnnouncement.contains("出口") -> "off ramp"
            lowerAnnouncement.contains("on ramp") -> "on ramp"
            lowerAnnouncement.contains("turn") || lowerAnnouncement.contains("折") -> "turn"
            else -> null
        }
    }

    private fun extractManeuverModifier(announcement: String): String? {
        val lowerAnnouncement = announcement.lowercase()
        return when {
            lowerAnnouncement.contains("u-turn") || lowerAnnouncement.contains("uターン") -> "uturn"
            lowerAnnouncement.contains("sharp left") || lowerAnnouncement.contains("鋭角左") -> "sharp left"
            lowerAnnouncement.contains("sharp right") || lowerAnnouncement.contains("鋭角右") -> "sharp right"
            lowerAnnouncement.contains("slight left") || lowerAnnouncement.contains("斜め左") -> "slight left"
            lowerAnnouncement.contains("slight right") || lowerAnnouncement.contains("斜め右") -> "slight right"
            lowerAnnouncement.contains("left") || lowerAnnouncement.contains("左") -> "left"
            lowerAnnouncement.contains("right") || lowerAnnouncement.contains("右") -> "right"
            lowerAnnouncement.contains("straight") || lowerAnnouncement.contains("直進") -> "straight"
            else -> null
        }
    }

    private fun isWaypointArrival(announcement: String): Boolean {
        val lowerAnnouncement = announcement.lowercase()
        return lowerAnnouncement.contains("waypoint") || lowerAnnouncement.contains("経由地")
    }

    private fun extractDestinationName(announcement: String): String? {
        val pattern = Regex("toward[s]?\\s+(.+)", RegexOption.IGNORE_CASE)
        return pattern.find(announcement)?.groupValues?.getOrNull(1)?.trim()
    }
}
