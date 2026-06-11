package me.matsumo.onenavi.guidance

import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout

/** 案内マニューバを通知や Android Auto へ渡す短い案内文に整形する formatter。 */
internal object GuidanceInstructionFormatter {

    /** マニューバを運転中に読める短い案内文へ変換する。 */
    fun format(maneuver: ManeuverCallout): String {
        val actionText = maneuver.actionText()
        val guidanceLabel = maneuver.guidanceLabel()

        return if (guidanceLabel != null) {
            "$guidanceLabel $actionText"
        } else {
            actionText
        }
    }

    private fun ManeuverCallout.guidanceLabel(): String? {
        val exitLabel = exitNumber?.takeIf { value -> value.isNotBlank() }
        if (exitLabel != null) {
            return "${exitLabel}出口"
        }

        return intersectionName?.takeIf { value -> value.isNotBlank() }
    }

    private fun ManeuverCallout.actionText(): String {
        return when (type) {
            ManeuverType.ARRIVE -> "目的地付近です"
            ManeuverType.CONTINUE -> "直進です"
            ManeuverType.DEPART -> "出発します"
            ManeuverType.END_OF_ROAD -> "${modifier.directionText()}です"
            ManeuverType.FORK -> "${modifier.directionText()}へ分岐します"
            ManeuverType.MERGE -> "${modifier.directionText()}へ合流します"
            ManeuverType.NAME_CHANGE -> "道なりです"
            ManeuverType.OFF_RAMP -> "${modifier.directionText()}の出口へ進みます"
            ManeuverType.ON_RAMP -> "${modifier.directionText()}の入口へ進みます"
            ManeuverType.ROTARY,
            ManeuverType.ROUNDABOUT,
            ManeuverType.TRAFFIC_CIRCLE,
            -> "ロータリーへ進みます"
            ManeuverType.TURN -> "${modifier.directionText()}です"
            ManeuverType.UTURN -> "Uターンです"
        }
    }

    private fun ManeuverModifier.directionText(): String {
        return when (this) {
            ManeuverModifier.LEFT -> "左折"
            ManeuverModifier.RIGHT -> "右折"
            ManeuverModifier.SHARP_LEFT -> "大きく左方向"
            ManeuverModifier.SHARP_RIGHT -> "大きく右方向"
            ManeuverModifier.SLIGHT_LEFT -> "斜め左方向"
            ManeuverModifier.SLIGHT_RIGHT -> "斜め右方向"
            ManeuverModifier.STRAIGHT -> "直進"
            ManeuverModifier.UTURN -> "Uターン"
        }
    }
}
