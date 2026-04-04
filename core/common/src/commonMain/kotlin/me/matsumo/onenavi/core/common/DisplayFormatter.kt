package me.matsumo.onenavi.core.common

/**
 * 秒数を「1日3時間49分」「1時間23分」「10分」のような表記にフォーマットする。
 * 各単位のラベルは引数で受け取り、多言語対応を可能にする。
 * 1分未満は「1分」として扱う。
 */
fun formatDuration(
    totalSeconds: Double,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
): String {
    val totalMinutes = (totalSeconds / 60).toInt().coerceAtLeast(1)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60

    return buildString {
        if (days > 0) append("$days$dayLabel")
        if (hours > 0) append("$hours$hourLabel")
        if (minutes > 0 || isEmpty()) append("$minutes$minuteLabel")
    }
}

/**
 * メートル単位の距離を「670 m」「10 km」「2,300 km」のようにフォーマットする。
 * 単位ラベルは引数で受け取り、多言語対応を可能にする。
 * 1,000 m 未満は m 表示、1,000 m 以上は km 表示（小数第1位まで、ただし .0 は省略）。
 */
fun formatDistance(
    meters: Double,
    meterLabel: String,
    kilometerLabel: String,
): String {
    if (meters < 1_000) {
        return "${meters.toInt()} $meterLabel"
    }

    val km = meters / 1_000
    val formatted = if (km % 1.0 < 0.05) {
        formatWithComma(km.toInt().toLong())
    } else {
        val integerPart = km.toLong()
        val fractionalPart = ((km - integerPart) * 10).toInt()
        "${formatWithComma(integerPart)}.$fractionalPart"
    }

    return "$formatted $kilometerLabel"
}

/**
 * 金額を「¥1,200」のようにフォーマットする。
 */
fun formatYen(amount: Int): String {
    return "¥${formatWithComma(amount.toLong())}"
}

private fun formatWithComma(value: Long): String {
    val text = value.toString()
    val result = StringBuilder()

    for (index in text.indices) {
        if (index > 0 && (text.length - index) % 3 == 0) {
            result.append(',')
        }
        result.append(text[index])
    }

    return result.toString()
}
