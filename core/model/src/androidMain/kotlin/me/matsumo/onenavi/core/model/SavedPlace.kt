package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import me.matsumo.onenavi.core.common.serializer.InstantSerializer
import kotlin.time.Instant

/**
 * ユーザーが保存した地点。
 *
 * @property id 保存地点の識別子
 * @property kind 保存地点の種別
 * @property sourcePlaceId 検索結果など参照元が持つ地点 ID
 * @property name ブックマーク表示などに使う地点名
 * @property address 一覧や詳細表示に使う住所キャッシュ
 * @property latitude 緯度
 * @property longitude 経度
 * @property createdAt 作成日時
 * @property updatedAt 更新日時
 */
@Immutable
@Serializable
data class SavedPlace(
    val id: String,
    val kind: SavedPlaceKind,
    val sourcePlaceId: String?,
    val name: String?,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
)

/** 保存地点の登録種別。 */
@Serializable
enum class SavedPlaceKind {
    /** 自宅として保存された地点。 */
    HOME,

    /** 職場として保存された地点。 */
    WORK,

    /** ブックマークとして保存された地点。 */
    BOOKMARK,
}

/**
 * 保存地点を作成・更新するための入力値。
 *
 * @property sourcePlaceId 検索結果など参照元が持つ地点 ID
 * @property name ブックマーク表示などに使う地点名
 * @property address 一覧や詳細表示に使う住所キャッシュ
 * @property latitude 緯度
 * @property longitude 経度
 */
@Immutable
data class SavedPlaceInput(
    val sourcePlaceId: String?,
    val name: String?,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
)

/**
 * 表示中の地点が登録済みか判定するためのキー。
 *
 * @property sourcePlaceId 検索結果など参照元が持つ地点 ID
 * @property latitude 緯度
 * @property longitude 経度
 */
@Immutable
data class SavedPlaceLookupKey(
    val sourcePlaceId: String?,
    val latitude: Double,
    val longitude: Double,
)

/**
 * 表示中の地点に対する登録状態。
 *
 * @property home 自宅として一致した保存地点
 * @property work 職場として一致した保存地点
 * @property bookmark ブックマークとして一致した保存地点
 */
@Immutable
data class SavedPlaceRegistrationState(
    val home: SavedPlace?,
    val work: SavedPlace?,
    val bookmark: SavedPlace?,
) {
    /** 自宅として登録済みか。 */
    val isHome: Boolean get() = home != null

    /** 職場として登録済みか。 */
    val isWork: Boolean get() = work != null

    /** ブックマークとして登録済みか。 */
    val isBookmarked: Boolean get() = bookmark != null
}
