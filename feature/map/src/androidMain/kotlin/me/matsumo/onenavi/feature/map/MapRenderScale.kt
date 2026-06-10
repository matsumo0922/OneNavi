package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.staticCompositionLocalOf

/** 地図描画スケールの既定値。実 density と描画 density が一致する通常端末ではこの値を使う。 */
const val DEFAULT_MAP_RENDER_SCALE = 1f

/**
 * 地図サブツリーの dp→px 変換へ掛ける描画スケール係数。
 *
 * GoogleMap の描画 density はプロセス単位で端末本体の density に焼き付けられる。VirtualDisplay など
 * 実 density が端末本体と異なる表示先では、地図だけがその比率ぶん拡大されるため、地図の座標計算を
 * 焼き付け済みの描画 density 空間へ揃える係数として用いる。値は `描画 density / 表示先 density`。
 * 通常端末では provide されず [DEFAULT_MAP_RENDER_SCALE]（1.0）のまま何もしない。
 */
val LocalMapRenderScale = staticCompositionLocalOf { DEFAULT_MAP_RENDER_SCALE }
