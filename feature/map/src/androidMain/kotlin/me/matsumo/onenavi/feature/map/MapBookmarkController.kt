package me.matsumo.onenavi.feature.map

import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.matsumo.onenavi.core.model.SavedPlace
import me.matsumo.onenavi.core.model.SavedPlaceInput
import me.matsumo.onenavi.core.model.SavedPlaceLookupKey
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.repository.SavedPlaceRepository
import me.matsumo.onenavi.core.repository.SearchRepository
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapUiState
import java.util.Locale

/**
 * 保存地点 repository の更新を地図 UI state へ反映する coordinator。
 *
 * @param savedPlaceRepository 保存地点 repository。
 * @param uiState 更新先の地図 UI state。
 * @param scope 購読を生存させる coroutine scope。
 */
internal class MapBookmarkStateCoordinator(
    private val savedPlaceRepository: SavedPlaceRepository,
    private val uiState: MutableStateFlow<MapUiState>,
    private val scope: CoroutineScope,
) {

    /** ブックマーク一覧と地点詳細の保存状態の購読を開始する。 */
    fun start() {
        observeBookmarkedPlaces()
        observePlaceDetailsBookmark()
    }

    private fun observeBookmarkedPlaces() {
        savedPlaceRepository.bookmarks
            .onEach(::updateBookmarkedPlaces)
            .launchIn(scope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePlaceDetailsBookmark() {
        uiState
            .map { state -> state.currentPlaceDetailsLookupKey() }
            .distinctUntilChanged()
            .flatMapLatest(::observeBookmark)
            .onEach(::updatePlaceDetailsBookmark)
            .launchIn(scope)
    }

    private fun observeBookmark(lookupKey: SavedPlaceLookupKey?): Flow<SavedPlace?> {
        if (lookupKey == null) return flowOf(null)

        return savedPlaceRepository
            .observeRegistrationState(lookupKey)
            .map { registrationState -> registrationState.bookmark }
    }

    private fun updateBookmarkedPlaces(bookmarkedPlaces: List<SavedPlace>) {
        uiState.update { state ->
            state.copy(bookmarkedPlaces = bookmarkedPlaces.toImmutableList())
        }
    }

    private fun updatePlaceDetailsBookmark(bookmark: SavedPlace?) {
        uiState.update { state ->
            state.copy(placeDetailsBookmark = bookmark)
        }
    }
}

/**
 * PlaceDetails のブックマーク操作と bookmark marker tap を扱う controller。
 *
 * @param savedPlaceRepository 保存地点 repository。
 * @param searchRepository 地点詳細の再取得に使う repository。
 * @param openPlaceDetails 地点詳細を開く suspend callback。
 */
internal class MapBookmarkActionController(
    private val savedPlaceRepository: SavedPlaceRepository,
    private val searchRepository: SearchRepository,
    private val openPlaceDetails: suspend (SearchResultItem) -> Unit,
) {
    private val bookmarkToggleMutex = Mutex()

    /** 指定地点のブックマーク保存状態を切り替える。 */
    suspend fun togglePlaceBookmark(item: SearchResultItem) {
        val result = bookmarkToggleMutex.withLock {
            togglePlaceBookmarkLocked(item)
        }

        result.onFailure { error ->
            Napier.e(error, TAG) { "Failed to toggle place bookmark. placeId=${item.placeId}" }
        }
    }

    private suspend fun togglePlaceBookmarkLocked(item: SearchResultItem): Result<Unit> {
        val lookupKey = item.toSavedPlaceLookupKey()
        val bookmark = savedPlaceRepository.findBookmark(lookupKey)

        return if (bookmark == null) {
            savedPlaceRepository.addBookmark(item.toSavedPlaceInput()).map { }
        } else {
            savedPlaceRepository.removeBookmark(bookmark.id)
        }
    }

    /** ブックマーク marker に対応する地点詳細を開く。 */
    suspend fun openBookmarkPlaceDetails(place: SavedPlace) {
        val result = retrieveBookmarkedPlace(place) ?: place.toSearchResultItem()

        openPlaceDetails(result)
    }

    private suspend fun retrieveBookmarkedPlace(place: SavedPlace): SearchResultItem? {
        val sourcePlaceId = place.sourcePlaceId?.takeIf { id -> id.isNotBlank() } ?: return null

        return searchRepository.retrieve(sourcePlaceId)
            .onFailure { error ->
                Napier.e(error, TAG) { "Failed to retrieve bookmarked place. sourcePlaceId=$sourcePlaceId" }
            }
            .getOrNull()
    }

    private companion object {

        /** Logcat でブックマーク操作のログを絞り込むためのタグ。 */
        const val TAG = "MapBookmarkActionController"
    }
}

internal fun SearchResultItem.toSavedPlaceInput(): SavedPlaceInput {
    return SavedPlaceInput(
        sourcePlaceId = normalizedSourcePlaceId(),
        name = name,
        address = formattedAddress ?: shortFormattedAddress,
        latitude = latitude,
        longitude = longitude,
    )
}

internal fun SearchResultItem.toSavedPlaceLookupKey(): SavedPlaceLookupKey {
    return SavedPlaceLookupKey(
        sourcePlaceId = normalizedSourcePlaceId(),
        latitude = latitude,
        longitude = longitude,
    )
}

private fun MapUiState.currentPlaceDetailsLookupKey(): SavedPlaceLookupKey? {
    val place = currentPlaceDetailsPlace() ?: return null

    return place.toSavedPlaceLookupKey()
}

private fun MapUiState.currentPlaceDetailsPlace(): SearchResultItem? {
    val overlayPlaceDetails = overlayState as? MapOverlayState.PlaceDetails

    return overlayPlaceDetails?.place ?: selectedResult
}

private fun SearchResultItem.normalizedSourcePlaceId(): String? {
    val normalizedPlaceId = placeId.trim()
    val isBlankPlaceId = normalizedPlaceId.isBlank()
    val isMapPointPlaceId = normalizedPlaceId.startsWith(MAP_POINT_ID_PREFIX)
    val isSavedPlaceId = normalizedPlaceId.startsWith(SAVED_PLACE_ID_PREFIX)
    val isSyntheticPlaceId = isBlankPlaceId || isMapPointPlaceId || isSavedPlaceId

    if (isSyntheticPlaceId) return null

    return normalizedPlaceId
}

private fun SavedPlace.toSearchResultItem(): SearchResultItem {
    val displayName = name
        ?.takeIf { placeName -> placeName.isNotBlank() }
        ?: address
            ?.takeIf { placeAddress -> placeAddress.isNotBlank() }
        ?: formatCoordinateName(latitude, longitude)

    return SearchResultItem(
        placeId = sourcePlaceId ?: "$SAVED_PLACE_ID_PREFIX$id",
        name = displayName,
        formattedAddress = address,
        shortFormattedAddress = address,
        latitude = latitude,
        longitude = longitude,
        viewportSouth = null,
        viewportWest = null,
        viewportNorth = null,
        viewportEast = null,
        primaryType = null,
        primaryTypeDisplayName = null,
        types = emptyList(),
        googleMapsUri = null,
        websiteUri = null,
        internationalPhoneNumber = null,
        nationalPhoneNumber = null,
        rating = null,
        userRatingCount = null,
        priceLevel = null,
        businessStatus = null,
        iconBackgroundColor = null,
        iconMaskUrl = null,
        editorialSummary = null,
        currentOpeningHours = null,
        isOpenNow = null,
    )
}

private fun formatCoordinateName(latitude: Double, longitude: Double): String {
    return String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
}

/** 地図長押しなどから作る synthetic placeId の prefix。 */
private const val MAP_POINT_ID_PREFIX = "map-point:"

/** 保存地点 fallback から作る synthetic placeId の prefix。 */
private const val SAVED_PLACE_ID_PREFIX = "saved-place:"
