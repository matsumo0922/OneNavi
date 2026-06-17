package me.matsumo.onenavi.feature.map

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.common.formatter
import me.matsumo.onenavi.core.datasource.SavedPlaceDataSource
import me.matsumo.onenavi.core.datasource.SearchDataSource
import me.matsumo.onenavi.core.datasource.SearchHistoryDataSource
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.model.SavedPlace
import me.matsumo.onenavi.core.model.SavedPlaceKind
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.repository.SavedPlaceRepository
import me.matsumo.onenavi.core.repository.SearchRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** ブックマーク操作 controller の保存切り替えと marker tap 処理を検証するテスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapBookmarkActionControllerTest {

    @Test
    fun `togglePlaceBookmark adds and removes bookmark with synthetic place id normalized`() = runTest {
        val target = createTarget()
        val item = createSearchResultItem(
            placeId = "map-point:8Q7XMP",
            name = "Pinned coordinate",
            formattedAddress = "Tokyo",
        )

        target.controller.togglePlaceBookmark(item)

        val addedPlace = target.savedPlaceDataSource.currentPlaces().single()

        assertNull(addedPlace.sourcePlaceId)
        assertEquals("Pinned coordinate", addedPlace.name)
        assertEquals("Tokyo", addedPlace.address)

        target.controller.togglePlaceBookmark(item)

        assertEquals(emptyList(), target.savedPlaceDataSource.currentPlaces())
    }

    @Test
    fun `togglePlaceBookmark serializes concurrent clicks for the same place`() = runTest {
        val target = createTarget()
        val item = createSearchResultItem()
        val dispatcher = StandardTestDispatcher(testScheduler)

        val firstToggle = async(dispatcher) {
            target.controller.togglePlaceBookmark(item)
        }
        val secondToggle = async(dispatcher) {
            target.controller.togglePlaceBookmark(item)
        }

        awaitAll(firstToggle, secondToggle)

        assertEquals(emptyList(), target.savedPlaceDataSource.currentPlaces())
    }

    @Test
    fun `openBookmarkPlaceDetails uses retrieved place when sourcePlaceId exists`() = runTest {
        val retrievedItem = createSearchResultItem(
            placeId = "source-place",
            name = "Retrieved name",
            formattedAddress = "Retrieved address",
        )
        val target = createTarget(
            searchDataSource = FakeSearchDataSource(
                retrieveResult = Result.success(retrievedItem),
            ),
        )
        val place = createSavedPlace(sourcePlaceId = "source-place")

        target.controller.openBookmarkPlaceDetails(place)

        assertEquals(retrievedItem, target.openedPlaces.single())
    }

    @Test
    fun `openBookmarkPlaceDetails falls back to saved place when retrieve fails`() = runTest {
        val target = createTarget(
            searchDataSource = FakeSearchDataSource(
                retrieveResult = Result.failure(IllegalStateException("missing")),
            ),
        )
        val place = createSavedPlace(
            sourcePlaceId = "source-place",
            name = null,
            address = "Saved address",
        )

        target.controller.openBookmarkPlaceDetails(place)

        val openedPlace = target.openedPlaces.single()

        assertEquals("source-place", openedPlace.placeId)
        assertEquals("Saved address", openedPlace.name)
        assertEquals("Saved address", openedPlace.formattedAddress)
    }

    private fun createTarget(
        searchDataSource: SearchDataSource = FakeSearchDataSource(),
    ): TestTarget {
        val preferenceHelper = InMemoryPreferenceHelper()
        val savedPlaceDataSource = SavedPlaceDataSource(
            preferenceHelper = preferenceHelper,
            formatter = formatter,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        val savedPlaceRepository = SavedPlaceRepository(
            dataSource = savedPlaceDataSource,
            nowProvider = { Instant.fromEpochMilliseconds(1_000) },
            bookmarkIdProvider = { "bookmark-id" },
        )
        val searchRepository = SearchRepository(
            searchDataSource = searchDataSource,
            searchHistoryDataSource = SearchHistoryDataSource(
                preferenceHelper = preferenceHelper,
                formatter = formatter,
                ioDispatcher = UnconfinedTestDispatcher(),
            ),
        )
        val openedPlaces = mutableListOf<SearchResultItem>()
        val controller = MapBookmarkActionController(
            savedPlaceRepository = savedPlaceRepository,
            searchRepository = searchRepository,
            openPlaceDetails = { item -> openedPlaces += item },
        )

        return TestTarget(
            savedPlaceDataSource = savedPlaceDataSource,
            controller = controller,
            openedPlaces = openedPlaces,
        )
    }

    private fun createSavedPlace(
        sourcePlaceId: String?,
        name: String? = "Saved name",
        address: String? = "Saved address",
    ): SavedPlace {
        return SavedPlace(
            id = "bookmark-id",
            kind = SavedPlaceKind.BOOKMARK,
            sourcePlaceId = sourcePlaceId,
            name = name,
            address = address,
            latitude = 35.681236,
            longitude = 139.767125,
            createdAt = Instant.fromEpochMilliseconds(1_000),
            updatedAt = Instant.fromEpochMilliseconds(1_000),
        )
    }

    private fun createSearchResultItem(
        placeId: String = "source-place",
        name: String = "Tokyo Station",
        formattedAddress: String? = "Tokyo",
    ): SearchResultItem {
        return SearchResultItem(
            placeId = placeId,
            name = name,
            formattedAddress = formattedAddress,
            shortFormattedAddress = formattedAddress,
            latitude = 35.681236,
            longitude = 139.767125,
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
}

/** ブックマーク controller テストに必要な依存をまとめた対象。 */
private data class TestTarget(
    val savedPlaceDataSource: SavedPlaceDataSource,
    val controller: MapBookmarkActionController,
    val openedPlaces: List<SearchResultItem>,
)

/** テスト用の検索 data source。 */
private class FakeSearchDataSource(
    private val retrieveResult: Result<SearchResultItem> = Result.failure(IllegalStateException("not found")),
) : SearchDataSource {

    override suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>> {
        return Result.success(emptyList())
    }

    override suspend fun select(suggestionId: String): Result<SearchResultItem> {
        return retrieveResult
    }

    override suspend fun retrieve(id: String): Result<SearchResultItem> {
        return retrieveResult
    }

    override suspend fun searchMultiple(
        query: String,
        latitude: Double?,
        longitude: Double?,
    ): Result<List<SearchResultItem>> {
        return Result.success(emptyList())
    }
}

/** テスト用に DataStore を名前別に保持する PreferenceHelper。 */
private class InMemoryPreferenceHelper : PreferenceHelper {
    private val dataStores = mutableMapOf<String, InMemoryPreferencesDataStore>()

    override fun create(name: String): DataStore<Preferences> {
        return dataStores.getOrPut(name) { InMemoryPreferencesDataStore() }
    }

    override fun delete(name: String) = Unit
}

/** テスト用のメモリ上 Preferences DataStore。 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val mutex = Mutex()
    private val mutablePreferences = kotlinx.coroutines.flow.MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = mutablePreferences

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        return mutex.withLock {
            val updatedPreferences = transform(mutablePreferences.value)
            mutablePreferences.value = updatedPreferences

            updatedPreferences
        }
    }
}
