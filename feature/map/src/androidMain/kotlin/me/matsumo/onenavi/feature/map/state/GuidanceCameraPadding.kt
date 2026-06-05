package me.matsumo.onenavi.feature.map.state

/**
 * 案内中追従カメラの描画 padding を解決する純粋ロジック。
 *
 * GoogleMap は camera target を「padding を除いた可視領域の中心」に置く。案内中追従では、その中心が
 * 下部カード上端から [VEHICLE_ANCHOR_MARGIN_FROM_BOTTOM_DP] 上へ来るよう上 padding を算出することで、
 * 自車（camera target）を画面下部の固定位置へアンカーする。
 */
internal object GuidanceCameraPadding {

    /**
     * 適用する上 padding（px）を返す。
     *
     * 案内中追従では `padded中心 = カード上端 - margin` となる上 padding を返す。導出は
     * `padded中心 = (H + top - bottom) / 2 = H - bottom - margin` を top について解いて
     * `top = H - bottom - 2*margin`。アンカーは下端（[mapViewHeightPx] と下 padding）基準なので、
     * 上部マニューバパネルの展開で実 top padding が変わっても自車位置は動かない。案内中追従でない場合や
     * ビューの高さが未確定の場合は、実 top padding をそのまま返す。
     *
     * @param isGuidanceFollowActive 案内中追従として下端アンカーを使う場合 true
     * @param mapViewHeightPx 地図ビューの高さ（px）
     * @param rawTopPaddingPx 実際の上 obstruction padding（px）
     * @param rawBottomPaddingPx 実際の下 obstruction padding（px）
     * @param density 画面密度
     * @param anchorFractionFromBottom 画面下端からのアンカー割合。null の場合は Compact 用のカード基準を使う
     * @return GoogleMap へ渡す上 padding（px）
     */
    fun resolveTopPaddingPx(
        isGuidanceFollowActive: Boolean,
        mapViewHeightPx: Int,
        rawTopPaddingPx: Int,
        rawBottomPaddingPx: Int,
        density: Float,
        anchorFractionFromBottom: Float? = null,
    ): Int {
        if (!isGuidanceFollowActive || mapViewHeightPx <= 0) {
            return rawTopPaddingPx
        }

        if (anchorFractionFromBottom != null) {
            return resolveFractionAnchorTopPaddingPx(
                mapViewHeightPx = mapViewHeightPx,
                rawBottomPaddingPx = rawBottomPaddingPx,
                density = density,
                anchorFractionFromBottom = anchorFractionFromBottom,
            )
        }

        val marginPx = (VEHICLE_ANCHOR_MARGIN_FROM_BOTTOM_DP * density).toInt()
        return (mapViewHeightPx - rawBottomPaddingPx - 2 * marginPx).coerceAtLeast(0)
    }

    private fun resolveFractionAnchorTopPaddingPx(
        mapViewHeightPx: Int,
        rawBottomPaddingPx: Int,
        density: Float,
        anchorFractionFromBottom: Float,
    ): Int {
        val anchorFraction = anchorFractionFromBottom.coerceIn(0f, 1f)
        val puckRadiusPx = GUIDANCE_PUCK_RADIUS_DP * density
        val gapPx = GUIDANCE_PUCK_NAVIGATION_BAR_GAP_DP * density
        val minimumDistanceFromBottomPx = rawBottomPaddingPx + puckRadiusPx + gapPx
        val anchorDistanceFromBottomPx = maxOf(
            mapViewHeightPx * anchorFraction,
            minimumDistanceFromBottomPx,
        )

        return (mapViewHeightPx - 2 * anchorDistanceFromBottomPx + rawBottomPaddingPx)
            .toInt()
            .coerceAtLeast(0)
    }
}

/** 分割案内で navigation bar から離すために見込む自車 puck の半径。 */
private const val GUIDANCE_PUCK_RADIUS_DP = 32.0f

/** 分割案内で自車 puck と navigation bar の間に確保する隙間。 */
private const val GUIDANCE_PUCK_NAVIGATION_BAR_GAP_DP = 8.0f
