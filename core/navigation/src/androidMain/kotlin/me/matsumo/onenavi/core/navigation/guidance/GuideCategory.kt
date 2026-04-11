package me.matsumo.onenavi.core.navigation.guidance

/**
 * 案内イベントの分類。
 */
enum class GuideCategory {
    TURN,
    HIGHWAY,
    LANE,
    REROUTE,
    SAFETY,
    ALONG_ROAD,
    WAYPOINT,
    SESSION,
}
