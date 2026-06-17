package me.matsumo.onenavi.core.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import me.matsumo.onenavi.core.datasource.SavedPlaceDataSource
import me.matsumo.onenavi.core.model.SavedPlace
import me.matsumo.onenavi.core.model.SavedPlaceInput
import me.matsumo.onenavi.core.model.SavedPlaceKind
import me.matsumo.onenavi.core.model.SavedPlaceLookupKey
import me.matsumo.onenavi.core.model.SavedPlaceRegistrationState
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 保存地点の登録ルールと登録済み判定を扱う repository。
 *
 * @param dataSource 保存地点の永続化 data source
 * @param nowProvider 保存地点の更新時刻を返す provider
 * @param bookmarkIdProvider ブックマーク ID を発行する provider
 */
class SavedPlaceRepository(
    private val dataSource: SavedPlaceDataSource,
    private val nowProvider: () -> Instant = { Clock.System.now() },
    private val bookmarkIdProvider: () -> String = { UUID.randomUUID().toString() },
) {
    val places: StateFlow<List<SavedPlace>> = dataSource.places

    val home: Flow<SavedPlace?> = places.map { savedPlaces ->
        savedPlaces.firstOrNull { savedPlace -> savedPlace.kind == SavedPlaceKind.HOME }
    }

    val work: Flow<SavedPlace?> = places.map { savedPlaces ->
        savedPlaces.firstOrNull { savedPlace -> savedPlace.kind == SavedPlaceKind.WORK }
    }

    val bookmarks: Flow<List<SavedPlace>> = places.map { savedPlaces ->
        savedPlaces.filter { savedPlace -> savedPlace.kind == SavedPlaceKind.BOOKMARK }
    }

    /** 表示中地点に対する自宅・職場・ブックマークの登録状態を購読する。 */
    fun observeRegistrationState(lookupKey: SavedPlaceLookupKey): Flow<SavedPlaceRegistrationState> {
        return places.map { savedPlaces ->
            savedPlaces.registrationStateOf(lookupKey)
        }
    }

    /** 表示中地点がブックマーク済みかを購読する。 */
    fun observeIsBookmarked(lookupKey: SavedPlaceLookupKey): Flow<Boolean> {
        return observeRegistrationState(lookupKey).map { registrationState ->
            registrationState.isBookmarked
        }
    }

    /** 現在の自宅登録を返す。 */
    suspend fun currentHome(): SavedPlace? {
        return dataSource.currentPlaces()
            .firstOrNull { savedPlace -> savedPlace.kind == SavedPlaceKind.HOME }
    }

    /** 現在の職場登録を返す。 */
    suspend fun currentWork(): SavedPlace? {
        return dataSource.currentPlaces()
            .firstOrNull { savedPlace -> savedPlace.kind == SavedPlaceKind.WORK }
    }

    /** 表示中地点に一致するブックマークを返す。 */
    suspend fun findBookmark(lookupKey: SavedPlaceLookupKey): SavedPlace? {
        return dataSource.currentPlaces()
            .firstOrNull { savedPlace ->
                savedPlace.kind == SavedPlaceKind.BOOKMARK && savedPlace.matches(lookupKey)
            }
    }

    /** 表示中地点がブックマーク済みかを返す。 */
    suspend fun isBookmarked(lookupKey: SavedPlaceLookupKey): Boolean {
        return findBookmark(lookupKey) != null
    }

    /** 自宅を 1 件だけ登録する。 */
    suspend fun setHome(input: SavedPlaceInput): Result<SavedPlace> = runCatching {
        upsertFixedPlace(
            id = HOME_ID,
            kind = SavedPlaceKind.HOME,
            input = input,
        )
    }

    /** 自宅登録を削除する。 */
    suspend fun clearHome(): Result<Unit> = runCatching {
        dataSource.remove(HOME_ID)
    }

    /** 職場を 1 件だけ登録する。 */
    suspend fun setWork(input: SavedPlaceInput): Result<SavedPlace> = runCatching {
        upsertFixedPlace(
            id = WORK_ID,
            kind = SavedPlaceKind.WORK,
            input = input,
        )
    }

    /** 職場登録を削除する。 */
    suspend fun clearWork(): Result<Unit> = runCatching {
        dataSource.remove(WORK_ID)
    }

    /** ブックマークを新規追加する。 */
    suspend fun addBookmark(input: SavedPlaceInput): Result<SavedPlace> = runCatching {
        val now = nowProvider()
        val bookmark = input.toSavedPlace(
            id = bookmarkIdProvider(),
            kind = SavedPlaceKind.BOOKMARK,
            createdAt = now,
            updatedAt = now,
        )

        dataSource.upsert(bookmark)

        bookmark
    }

    /** ブックマーク名を更新する。 */
    suspend fun updateBookmarkName(id: String, name: String): Result<SavedPlace> = runCatching {
        val bookmark = dataSource.currentPlaces()
            .firstOrNull { savedPlace ->
                savedPlace.id == id && savedPlace.kind == SavedPlaceKind.BOOKMARK
            }
        requireNotNull(bookmark) { "Bookmark is not found: $id" }

        val updatedBookmark = bookmark.copy(
            name = name,
            updatedAt = nowProvider(),
        )
        dataSource.upsert(updatedBookmark)

        updatedBookmark
    }

    /** ブックマークを削除する。 */
    suspend fun removeBookmark(id: String): Result<Unit> = runCatching {
        val bookmark = dataSource.currentPlaces()
            .firstOrNull { savedPlace ->
                savedPlace.id == id && savedPlace.kind == SavedPlaceKind.BOOKMARK
            }
        if (bookmark == null) return@runCatching

        dataSource.remove(id)
    }

    private suspend fun upsertFixedPlace(
        id: String,
        kind: SavedPlaceKind,
        input: SavedPlaceInput,
    ): SavedPlace {
        val current = dataSource.currentPlaces()
            .firstOrNull { savedPlace -> savedPlace.id == id }
        val now = nowProvider()
        val createdAt = current?.createdAt ?: now
        val savedPlace = input.toSavedPlace(
            id = id,
            kind = kind,
            createdAt = createdAt,
            updatedAt = now,
        )

        dataSource.upsert(savedPlace)

        return savedPlace
    }

    private fun SavedPlaceInput.toSavedPlace(
        id: String,
        kind: SavedPlaceKind,
        createdAt: Instant,
        updatedAt: Instant,
    ): SavedPlace {
        return SavedPlace(
            id = id,
            kind = kind,
            sourcePlaceId = sourcePlaceId,
            name = name,
            address = address,
            latitude = latitude,
            longitude = longitude,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun List<SavedPlace>.registrationStateOf(lookupKey: SavedPlaceLookupKey): SavedPlaceRegistrationState {
        return SavedPlaceRegistrationState(
            home = findMatchingPlace(SavedPlaceKind.HOME, lookupKey),
            work = findMatchingPlace(SavedPlaceKind.WORK, lookupKey),
            bookmark = findMatchingPlace(SavedPlaceKind.BOOKMARK, lookupKey),
        )
    }

    private fun List<SavedPlace>.findMatchingPlace(
        kind: SavedPlaceKind,
        lookupKey: SavedPlaceLookupKey,
    ): SavedPlace? {
        return firstOrNull { savedPlace ->
            savedPlace.kind == kind && savedPlace.matches(lookupKey)
        }
    }

    private fun SavedPlace.matches(lookupKey: SavedPlaceLookupKey): Boolean {
        val lookupSourcePlaceId = lookupKey.sourcePlaceId
        val savedSourcePlaceId = sourcePlaceId
        val bothHaveSourcePlaceId = lookupSourcePlaceId != null && savedSourcePlaceId != null
        if (bothHaveSourcePlaceId) return lookupSourcePlaceId == savedSourcePlaceId

        return latitude.isCloseTo(lookupKey.latitude) && longitude.isCloseTo(lookupKey.longitude)
    }

    private fun Double.isCloseTo(other: Double): Boolean {
        return abs(this - other) <= COORDINATE_TOLERANCE
    }

    companion object {
        /** 自宅登録に使う固定 ID。 */
        const val HOME_ID = "home"

        /** 職場登録に使う固定 ID。 */
        const val WORK_ID = "work"

        /** 座標一致判定に使う緯度経度それぞれの許容誤差。 */
        private const val COORDINATE_TOLERANCE = 1e-6
    }
}
