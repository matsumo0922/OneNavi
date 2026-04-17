package me.matsumo.onenavi.core.ui.callout

/**
 * 複数 Callout の配置戦略。
 */
enum class CalloutPlacementStrategy {
    /**
     * Callout 同士の重なり回避を優先する。[CalloutAnchor.Flexible] の候補点から重ならない位置を選ぶ。
     * 候補が全て既存と衝突する場合は [CalloutAnchor.primaryPoint] にフォールバックし重なりを許容する。
     */
    AvoidOverlap,

    /**
     * [CalloutAnchor.primaryPoint] に tail 先端を固定する。重なりは許容、方向のみ画面内収容優先で選択する。
     */
    AnchorFirst,
}
