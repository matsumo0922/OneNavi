package me.matsumo.onenavi.core.navigation.newguidance.presentation

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey

/**
 * コンパクト表示 (TBT バナー) 向けの presentation 値 (L3)。
 *
 * 上段の主案内 ([primary]) + 案内ラベル ([secondaryLabel]) と、下段の補助 ([support]) が
 * 別フィールドに分かれているため、**案内文とレーンを同時表示**できる。優先順位 (案内ラベルの
 * waterfall、下段の lane と followup の排他) は projection 内で 1 度だけ決まり、UI はその結果を
 * そのまま描く。
 *
 * @property primary 上段に出す次の主案内
 * @property secondaryLabel 上段の案内ラベル (交差点 > 方面看板 > 道路名 > 出口 の waterfall)。無ければ null
 * @property signpostImageKey 下段に出す方面看板画像の key。無ければ null
 * @property roadClass バナーの配色に使う現在走行中の道路種別
 * @property support 下段の補助表示 (レーン または フォローアップ)。無ければ null
 * @property hasMoreEvents フルリストへ展開できる後続イベントがあるか
 */
@Immutable
data class ManeuverBanner(
    val primary: ManeuverCallout,
    val secondaryLabel: String?,
    val signpostImageKey: GuideImageKey?,
    val roadClass: RoadClass,
    val support: BannerSupport?,
    val hasMoreEvents: Boolean,
)

/**
 * コンパクトバナー下段の補助表示。レーンとフォローアップは排他で、projection が片方を選ぶ。
 */
@Immutable
sealed interface BannerSupport {

    /**
     * 次の進路選択点のレーン。
     *
     * @property lane 表示するレーン
     */
    @Immutable
    data class Lanes(
        val lane: LanePresentation,
    ) : BannerSupport

    /**
     * 次の次の主案内 (フォローアップ案内)。
     *
     * @property maneuver フォローアップの主案内
     */
    @Immutable
    data class Followup(
        val maneuver: ManeuverCallout,
    ) : BannerSupport
}
