package me.matsumo.onenavi.core.navigation.newguidance.presentation

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * 案内中 UI が読む presentation 射影一式 (L3)。
 *
 * 同じ semantic イベント列を、コンパクト ([banner])・フルリスト ([listItems]) の 2 通りに
 * 射影した結果と、地図オーバーレイ向けの主案内 ([nextManeuver] / [followupManeuver]) を束ねる。
 * tick ごとに [GuidancePresentationProjector] が組み立てる。位置スカラは
 * [me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress] 側に持つ。
 *
 * [banner] の主案内は [nextManeuver] と同一だが、コンパクトバナーの下段がレーンを選んだ場合でも
 * 地図上のフォローアップ CallOut を出せるよう、callout 用の主案内はここに別途公開する。
 *
 * @property nextManeuver 次の主案内 (バナー主案内 / カメラフォーカス / CallOut マーカー)。無ければ null
 * @property followupManeuver 次の次の主案内 (CallOut マーカー)。無ければ null
 * @property banner コンパクト表示用バナー。主案内が無ければ null
 * @property listItems フルリスト表示用の行 (現在地より先・距離の降順)
 */
@Immutable
data class GuidancePresentation(
    val nextManeuver: ManeuverCallout?,
    val followupManeuver: ManeuverCallout?,
    val banner: ManeuverBanner?,
    val listItems: ImmutableList<GuidanceListItem>,
) {
    companion object {
        /** 案内対象が確定する前に使う空の presentation。 */
        val Empty: GuidancePresentation = GuidancePresentation(
            nextManeuver = null,
            followupManeuver = null,
            banner = null,
            listItems = persistentListOf(),
        )
    }
}
