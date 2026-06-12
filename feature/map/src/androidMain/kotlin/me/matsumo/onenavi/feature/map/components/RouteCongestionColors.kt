package me.matsumo.onenavi.feature.map.components

import androidx.compose.ui.graphics.Color
import me.matsumo.onenavi.core.model.CongestionSeverity

/**
 * 渋滞区間をルート本体へ重ねるときの表示色。
 *
 * NORMAL / UNKNOWN は道路種別色を維持するため null を返す。
 */
internal fun routeCongestionBodyColorOf(severity: CongestionSeverity): Color? = when (severity) {
    CongestionSeverity.TRAFFIC_JAM -> Color(0xFFE53935)
    CongestionSeverity.SLOW -> Color(0xFFFB8C00)
    CongestionSeverity.NORMAL, CongestionSeverity.UNKNOWN -> null
}
