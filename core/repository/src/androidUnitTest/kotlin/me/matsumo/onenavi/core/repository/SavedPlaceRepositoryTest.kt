package me.matsumo.onenavi.core.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.common.formatter
import me.matsumo.onenavi.core.datasource.SavedPlaceDataSource
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.model.SavedPlaceInput
import me.matsumo.onenavi.core.model.SavedPlaceKind
import me.matsumo.onenavi.core.model.SavedPlaceLookupKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 保存地点 repository の登録ルールを検証するテスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedPlaceRepositoryTest {

    @Test
    fun `setHome keeps fixed id and preserves createdAt when replacing`() = runTest {
        val testClock = TestClock()
        val target = createTarget(nowProvider = testClock::now)
        val firstHome = target.repository.setHome(createInput(name = "First Home")).getOrThrow()
        val updatedHome = target.repository.setHome(
            createInput(
                name = "Updated Home",
                latitude = 35.1,
                longitude = 139.1,
            ),
        ).getOrThrow()

        val places = target.dataSource.currentPlaces()

        assertEquals(SavedPlaceRepository.HOME_ID, updatedHome.id)
        assertEquals(SavedPlaceKind.HOME, updatedHome.kind)
        assertEquals(firstHome.createdAt, updatedHome.createdAt)
        assertEquals(Instant.fromEpochMilliseconds(2_000), updatedHome.updatedAt)
        assertEquals(listOf(updatedHome), places)
    }

    @Test
    fun `setWork keeps one fixed work place`() = runTest {
        val target = createTarget()

        target.repository.setWork(createInput(name = "First Work")).getOrThrow()
        val updatedWork = target.repository.setWork(createInput(name = "Updated Work")).getOrThrow()

        assertEquals(SavedPlaceRepository.WORK_ID, updatedWork.id)
        assertEquals(listOf(updatedWork), target.dataSource.currentPlaces())
    }

    @Test
    fun `addBookmark always creates UUID based entries`() = runTest {
        val bookmarkIds = ArrayDeque(listOf("bookmark-1", "bookmark-2"))
        val target = createTarget(
            bookmarkIdProvider = { bookmarkIds.removeFirst() },
        )

        val firstBookmark = target.repository.addBookmark(
            createInput(sourcePlaceId = "source-a"),
        ).getOrThrow()
        val secondBookmark = target.repository.addBookmark(
            createInput(
                sourcePlaceId = "source-b",
                latitude = 35.1,
                longitude = 139.1,
            ),
        ).getOrThrow()

        assertEquals("bookmark-1", firstBookmark.id)
        assertEquals("bookmark-2", secondBookmark.id)
        assertEquals(listOf(firstBookmark, secondBookmark), target.dataSource.currentPlaces())
    }

    @Test
    fun `addBookmark returns existing bookmark for matching place`() = runTest {
        val bookmarkIds = ArrayDeque(listOf("bookmark-1", "bookmark-2"))
        val target = createTarget(
            bookmarkIdProvider = { bookmarkIds.removeFirst() },
        )
        val firstBookmark = target.repository.addBookmark(
            createInput(
                sourcePlaceId = "source-a",
                name = "Before",
            ),
        ).getOrThrow()

        val duplicatedBookmark = target.repository.addBookmark(
            createInput(
                sourcePlaceId = "source-a",
                name = "After",
                latitude = 0.0,
                longitude = 0.0,
            ),
        ).getOrThrow()

        assertEquals(firstBookmark, duplicatedBookmark)
        assertEquals(listOf(firstBookmark), target.dataSource.currentPlaces())
    }

    @Test
    fun `updateBookmarkName updates only bookmark and removeBookmark ignores fixed places`() = runTest {
        val target = createTarget(
            bookmarkIdProvider = { "bookmark-1" },
        )
        val home = target.repository.setHome(createInput()).getOrThrow()
        val bookmark = target.repository.addBookmark(createInput(name = "Before")).getOrThrow()

        val updatedBookmark = target.repository.updateBookmarkName(bookmark.id, "After").getOrThrow()
        target.repository.removeBookmark(home.id).getOrThrow()

        assertEquals("After", updatedBookmark.name)
        assertEquals(bookmark.createdAt, updatedBookmark.createdAt)
        assertEquals(listOf(home, updatedBookmark), target.dataSource.currentPlaces())

        target.repository.removeBookmark(bookmark.id).getOrThrow()

        assertEquals(listOf(home), target.dataSource.currentPlaces())
    }

    @Test
    fun `sourcePlaceId match has priority over coordinate fallback`() = runTest {
        val target = createTarget(
            bookmarkIdProvider = { "bookmark-1" },
        )
        val bookmark = target.repository.addBookmark(
            createInput(
                sourcePlaceId = "source-a",
                latitude = 35.0,
                longitude = 139.0,
            ),
        ).getOrThrow()

        val sourceMatchedBookmark = target.repository.findBookmark(
            SavedPlaceLookupKey(
                sourcePlaceId = "source-a",
                latitude = 0.0,
                longitude = 0.0,
            ),
        )
        val differentSourceSameCoordinate = target.repository.isBookmarked(
            SavedPlaceLookupKey(
                sourcePlaceId = "source-b",
                latitude = bookmark.latitude,
                longitude = bookmark.longitude,
            ),
        )
        val noSourceSameCoordinate = target.repository.isBookmarked(
            SavedPlaceLookupKey(
                sourcePlaceId = null,
                latitude = bookmark.latitude,
                longitude = bookmark.longitude,
            ),
        )

        assertEquals(bookmark, sourceMatchedBookmark)
        assertFalse(differentSourceSameCoordinate)
        assertTrue(noSourceSameCoordinate)
    }

    @Test
    fun `coordinate fallback matches meter level drift and rejects distant places`() = runTest {
        val target = createTarget()
        target.repository.setWork(
            createInput(
                sourcePlaceId = null,
                latitude = 35.0,
                longitude = 139.0,
            ),
        ).getOrThrow()

        val work = target.repository.currentWork()
        val state = target.repository.observeRegistrationState(
            SavedPlaceLookupKey(
                sourcePlaceId = "lookup-source",
                latitude = 35.000009,
                longitude = 139.000009,
            ),
        ).first { registrationState -> registrationState.isWork }
        val distantState = target.repository.observeRegistrationState(
            SavedPlaceLookupKey(
                sourcePlaceId = null,
                latitude = 35.00002,
                longitude = 139.00002,
            ),
        ).first()

        assertNotNull(work)
        assertEquals(work, state.work)
        assertFalse(distantState.isWork)
    }

    private fun createTarget(
        nowProvider: () -> Instant = TestClock()::now,
        bookmarkIdProvider: () -> String = { "bookmark-id" },
    ): TestTarget {
        val dataSource = SavedPlaceDataSource(
            preferenceHelper = InMemoryPreferenceHelper(),
            formatter = formatter,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        val repository = SavedPlaceRepository(
            dataSource = dataSource,
            nowProvider = nowProvider,
            bookmarkIdProvider = bookmarkIdProvider,
        )

        return TestTarget(dataSource, repository)
    }

    private fun createInput(
        sourcePlaceId: String? = "source-place",
        name: String? = "Tokyo Station",
        address: String? = "Tokyo",
        latitude: Double = 35.681236,
        longitude: Double = 139.767125,
    ): SavedPlaceInput {
        return SavedPlaceInput(
            sourcePlaceId = sourcePlaceId,
            name = name,
            address = address,
            latitude = latitude,
            longitude = longitude,
        )
    }
}

/** Repository と永続化先をまとめたテスト対象。 */
private data class TestTarget(
    val dataSource: SavedPlaceDataSource,
    val repository: SavedPlaceRepository,
)

/** テスト用の単調増加 clock。 */
private class TestClock {
    private var nextEpochMillis = 1_000L

    fun now(): Instant {
        val instant = Instant.fromEpochMilliseconds(nextEpochMillis)
        nextEpochMillis += 1_000

        return instant
    }
}

/** テスト用に単一の DataStore を返す PreferenceHelper。 */
private class InMemoryPreferenceHelper : PreferenceHelper {
    private val dataStore = InMemoryPreferencesDataStore()

    override fun create(name: String): DataStore<Preferences> = dataStore

    override fun delete(name: String) = Unit
}

/** テスト用のメモリ上 Preferences DataStore。 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val mutex = Mutex()
    private val mutablePreferences = kotlinx.coroutines.flow.MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = mutablePreferences

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        return mutex.withLock {
            val updatedPreferences = transform(mutablePreferences.value)
            mutablePreferences.value = updatedPreferences

            updatedPreferences
        }
    }
}
