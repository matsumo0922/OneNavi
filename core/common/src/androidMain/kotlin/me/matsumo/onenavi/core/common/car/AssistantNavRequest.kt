package me.matsumo.onenavi.core.common.car

import androidx.compose.runtime.Immutable

/**
 * アシスタント intent から得た geo 座標。
 *
 * @property latitude 緯度
 * @property longitude 経度
 */
@Immutable
data class AssistantNavCoordinate(
    val latitude: Double,
    val longitude: Double,
)

/**
 * アシスタント / Gemini の geo intent から抽出した案内要求。
 */
sealed interface AssistantNavRequest {

    /**
     * 目的地を設定し、案内を自動開始する要求。
     *
     * @property query 目的地名。座標のみの要求では null
     * @property coordinate 目的地座標。検索語のみの要求では null
     */
    @Immutable
    data class Navigate(
        val query: String?,
        val coordinate: AssistantNavCoordinate?,
    ) : AssistantNavRequest

    /**
     * ルートプレビューだけを表示する要求。
     *
     * @property query 目的地名。座標のみの要求では null
     * @property coordinate 目的地座標。検索語のみの要求では null
     */
    @Immutable
    data class Preview(
        val query: String?,
        val coordinate: AssistantNavCoordinate?,
    ) : AssistantNavRequest

    /**
     * 経由地を追加する要求。
     *
     * @property query 経由地名。座標のみの要求では null
     * @property coordinate 経由地座標。検索語のみの要求では null
     */
    @Immutable
    data class AddStop(
        val query: String?,
        val coordinate: AssistantNavCoordinate?,
    ) : AssistantNavRequest

    /**
     * 場所検索結果を表示する要求。
     *
     * @property query 検索語
     */
    @Immutable
    data class Search(
        val query: String,
    ) : AssistantNavRequest
}
