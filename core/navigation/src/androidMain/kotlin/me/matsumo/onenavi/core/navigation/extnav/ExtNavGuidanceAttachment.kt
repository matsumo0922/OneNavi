package me.matsumo.onenavi.core.navigation.extnav

import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute

/**
 * [ExtNavGuidanceTracker.attach] が返す、tick 非依存の attach 時成果物。
 *
 * tick hot path を太らせずに、UI 向けの semantic 射影と音声プランの双方が attach 時の同じ
 * 成果物 (案内ルート / 距離変換 context) を共有できるようにするための束ね。
 *
 * @property guidanceRoute payload を射影した位置非依存の案内ルート
 * @property distanceContext source→geometry 距離変換 context
 */
internal class ExtNavGuidanceAttachment(
    val guidanceRoute: GuidanceRoute,
    val distanceContext: ExtNavRouteDistanceContext,
)
