package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.FollowupDistanceBucket.Companion.fromMeters
import kotlin.math.abs

/**
 * 連続案内（followup）で使う距離バケット。
 *
 * 主ターンの発話に続けて「その先、およそ XXm で」と次ターンの予告を載せるときの、
 * 距離フレーズの段階。実距離は [fromMeters] で最も近い値にスナップされる。
 *
 * @property approximateMeters 発話する概算距離（メートル）。
 * @property phraseId 「およそ XXm で」の [TtsPhraseId]。
 */
@Immutable
enum class FollowupDistanceBucket(
    val approximateMeters: Int,
    val phraseId: TtsPhraseId,
) {
    /** およそ50mで */
    AT_50M(50, TtsPhraseId.DISTANCE_APPROX_50M_AT),

    /** およそ100mで */
    AT_100M(100, TtsPhraseId.DISTANCE_APPROX_100M_AT),

    /** およそ200mで */
    AT_200M(200, TtsPhraseId.DISTANCE_APPROX_200M_AT),

    /** およそ300mで */
    AT_300M(300, TtsPhraseId.DISTANCE_APPROX_300M_AT),

    /** およそ400mで */
    AT_400M(400, TtsPhraseId.DISTANCE_APPROX_400M_AT),

    /** およそ500mで */
    AT_500M(500, TtsPhraseId.DISTANCE_APPROX_500M_AT),
    ;

    companion object {
        /**
         * 実距離 [meters] を最も近いバケットにスナップする。
         *
         * 範囲外（0m 以下や 500m 超え）でも最近傍を返すので、呼び出し側で
         * followup 生成の閾値判定（例: 500m 以下）を行ったあとに使うこと。
         */
        fun fromMeters(meters: Int): FollowupDistanceBucket {
            return entries.minBy { bucket -> abs(bucket.approximateMeters - meters) }
        }
    }
}
