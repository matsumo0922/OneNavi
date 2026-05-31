package me.matsumo.onenavi.core.navigation.voice.suppression

import androidx.compose.runtime.Immutable

/**
 * 発話を確定した案内 1 件の「案内地点 index × 読み上げ SSML」キー。同一内容の二重発話抑止に使う。
 *
 * 距離段の pieces は位置非依存で、どの素片を鳴らすかは category gate 適用後 (発話直前) に確定する。
 * そのため raw text が異なる別段でも、gate 適用後に同じ読み上げ内容へ畳まれることがある。発話済みの
 * (案内地点, SSML) を記録しておき、同一案内地点で同じ内容を繰り返さないために使う (案内地点が違えば
 * 同じ「右方向です」でも別案内なので抑止しない = 外部ナビ API 参照実装の同一案内 + 同一文言判定に対応)。
 *
 * @property targetIndex 案内地点の plan 内 index
 * @property ssml category gate / 結合を適用後の読み上げ SSML
 */
@Immutable
internal data class SpokenAnnouncementContent(
    val targetIndex: Int,
    val ssml: String,
)
