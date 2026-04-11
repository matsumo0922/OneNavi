package me.matsumo.onenavi.core.navigation.guidance

/**
 * 経由地または目的地の種類と到着時の読み上げ文言。
 */
enum class DestinationKind(
    val arrivalPhrase: String,
) {
    DESTINATION("目的地付近です"),
    STATION("駅入口付近です"),
    BUS_STOP("バス停付近です"),
    AIRPORT("空港付近です"),
    FERRY("フェリー乗り場付近です"),
    WAYPOINT("経由地付近です"),
}
