package me.matsumo.onenavi.core.navigation.voice.config

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory

/**
 * 発話カテゴリごとの ON / OFF ゲート。
 *
 * map に存在しない category は ON 扱いとする。実際のフィルタは発話 dispatch 直前に
 * piece 単位で適用するため、ここでは判定だけを提供し、plan の構築には関与しない。
 *
 * @property gates category と ON/OFF の対応。未登録 category は ON
 */
@Immutable
internal data class VoiceAnnouncementCategoryGate(
    private val gates: ImmutableMap<GuidanceCategory, Boolean>,
) {

    /**
     * 指定 category が発話対象かを返す。null (テンプレ未解決) はデフォルトで ON。
     *
     * @param category 判定対象の category。null 可
     * @return 発話してよいなら true
     */
    fun isEnabled(category: GuidanceCategory?): Boolean {
        if (category == null) return true
        return gates[category] ?: true
    }

    internal companion object {

        /** 全 category を ON にする素通しゲート。 */
        val AllOn = VoiceAnnouncementCategoryGate(persistentMapOf())

        /**
         * N 社のナビアプリのデフォルト発話 ON/OFF に揃えたゲート。
         *
         * issue #41 §Q4 で OFF 例として挙がっている [GuidanceCategory.Curve] に加え、
         * 走行に必須でない [GuidanceCategory.Scenic] / [GuidanceCategory.AccidentBlackSpot] /
         * [GuidanceCategory.Merge] を暫定で OFF にしている。完全なデフォルト表
         * (外部ナビ API 側の発話オプション) との突き合わせは Phase 4 で確定する。
         */
        val OneNaviDefault = of(
            GuidanceCategory.Curve to false,
            GuidanceCategory.Scenic to false,
            GuidanceCategory.AccidentBlackSpot to false,
            GuidanceCategory.Merge to false,
        )

        /**
         * category と ON/OFF の組から gate を作る。
         *
         * @param pairs category と ON/OFF の組
         * @return 指定値を保持する gate
         */
        fun of(vararg pairs: Pair<GuidanceCategory, Boolean>): VoiceAnnouncementCategoryGate =
            VoiceAnnouncementCategoryGate(pairs.toMap().toImmutableMap())

        /**
         * OFF にする category 名 ([GuidanceCategory.name]) 集合から gate を作る。設定画面の永続値から
         * 実行時の gate を組むための入口。名前に一致しない要素は無視し、一致した category のみ OFF にする。
         *
         * @param disabledNames OFF にする category の名前集合
         * @return 指定 category を OFF、それ以外を ON とする gate
         */
        fun ofDisabledNames(disabledNames: Set<String>): VoiceAnnouncementCategoryGate {
            val gates = GuidanceCategory.entries
                .filter { category -> category.name in disabledNames }
                .associateWith { false }
                .toImmutableMap()
            return VoiceAnnouncementCategoryGate(gates)
        }
    }
}
