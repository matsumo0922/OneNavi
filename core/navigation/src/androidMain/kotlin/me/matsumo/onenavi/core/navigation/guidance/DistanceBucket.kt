package me.matsumo.onenavi.core.navigation.guidance

/**
 * TTS で読み上げる距離の丸め単位。
 */
enum class DistanceBucket(
    val meters: Int,
) {
    M50(50),
    M100(100),
    M200(200),
    M300(300),
    M400(400),
    M500(500),
    M600(600),
    M700(700),
    M800(800),
    M900(900),
    KM1(1_000),
    KM2(2_000),
    KM3(3_000),
    KM4(4_000),
    KM5(5_000),
    KM10(10_000),
    ;

    val label: String
        get() = if (meters < 1_000) {
            "${meters}メートル"
        } else {
            "${meters / 1_000}キロ"
        }

    val aheadLabel: String
        get() = if (meters < 1_000) {
            "およそ${meters}m先"
        } else {
            "およそ${meters / 1_000}km先"
        }

    /**
     * 距離から最も近い読み上げ単位を選ぶ。
     */
    companion object {
        fun fromMeters(distanceMeters: Double): DistanceBucket {
            return entries.firstOrNull { distanceMeters <= it.meters } ?: KM10
        }
    }
}
