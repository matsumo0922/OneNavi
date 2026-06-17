package me.matsumo.onenavi.core.datasource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.model.SavedPlace
import me.matsumo.onenavi.core.model.SavedPlaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** 保存地点 data source の JSON 保存を検証するテスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedPlaceDataSourceTest {

    @Test
    fun `currentPlaces decodes saved place store`() = runTest {
        val preferenceHelper = SavedPlaceInMemoryPreferenceHelper()
        val dataSource = createDataSource(preferenceHelper)
        val savedPlace = createSavedPlace(id = "bookmark-1")

        preferenceHelper.writeSavedPlaces(listOf(savedPlace))

        assertEquals(listOf(savedPlace), dataSource.currentPlaces())
    }

    @Test
    fun `upsert decodes current JSON inside edit`() = runTest {
        val preferenceHelper = SavedPlaceInMemoryPreferenceHelper()
        val dataSource = createDataSource(preferenceHelper)
        val existingPlace = createSavedPlace(id = "bookmark-1")
        val addedPlace = createSavedPlace(
            id = "bookmark-2",
            latitude = 35.2,
            longitude = 139.2,
        )

        preferenceHelper.writeSavedPlaces(listOf(existingPlace))
        dataSource.upsert(addedPlace)

        assertEquals(listOf(existingPlace, addedPlace), dataSource.currentPlaces())
    }

    @Test
    fun `upsert replaces same id without duplicating`() = runTest {
        val preferenceHelper = SavedPlaceInMemoryPreferenceHelper()
        val dataSource = createDataSource(preferenceHelper)
        val originalPlace = createSavedPlace(id = "bookmark-1", name = "Before")
        val updatedPlace = originalPlace.copy(name = "After")

        dataSource.upsert(originalPlace)
        dataSource.upsert(updatedPlace)

        assertEquals(listOf(updatedPlace), dataSource.currentPlaces())
    }

    @Test
    fun `remove deletes only matching id`() = runTest {
        val preferenceHelper = SavedPlaceInMemoryPreferenceHelper()
        val dataSource = createDataSource(preferenceHelper)
        val firstPlace = createSavedPlace(id = "bookmark-1")
        val secondPlace = createSavedPlace(id = "bookmark-2")

        dataSource.upsert(firstPlace)
        dataSource.upsert(secondPlace)
        dataSource.remove(firstPlace.id)

        assertEquals(listOf(secondPlace), dataSource.currentPlaces())
    }

    @Test
    fun `currentPlaces falls back to empty list when JSON is invalid`() = runTest {
        val preferenceHelper = SavedPlaceInMemoryPreferenceHelper()
        val dataSource = createDataSource(preferenceHelper)

        preferenceHelper.writeRawJson("{")

        assertEquals(emptyList(), dataSource.currentPlaces())
    }

    private fun createDataSource(preferenceHelper: PreferenceHelper): SavedPlaceDataSource {
        return SavedPlaceDataSource(
            preferenceHelper = preferenceHelper,
            formatter = Json,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun createSavedPlace(
        id: String,
        name: String = "Tokyo Station",
        latitude: Double = 35.681236,
        longitude: Double = 139.767125,
    ): SavedPlace {
        return SavedPlace(
            id = id,
            kind = SavedPlaceKind.BOOKMARK,
            sourcePlaceId = "source-$id",
            name = name,
            address = "Tokyo",
            latitude = latitude,
            longitude = longitude,
            createdAt = Instant.fromEpochMilliseconds(1_000),
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        )
    }
}

/** テスト用に単一の DataStore を返す PreferenceHelper。 */
private class SavedPlaceInMemoryPreferenceHelper : PreferenceHelper {
    private val dataStore = SavedPlaceInMemoryPreferencesDataStore()

    override fun create(name: String): DataStore<Preferences> = dataStore

    override fun delete(name: String) = Unit

    suspend fun writeSavedPlaces(places: List<SavedPlace>) {
        val store = TestSavedPlaceStore(places = places)
        writeRawJson(Json.encodeToString(store))
    }

    suspend fun writeRawJson(json: String) {
        dataStore.updateData {
            mutablePreferencesOf(SAVED_PLACES_KEY to json)
        }
    }
}

/** テスト用のメモリ上 Preferences DataStore。 */
private class SavedPlaceInMemoryPreferencesDataStore : DataStore<Preferences> {
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

/** DataSource の保存形式に合わせたテスト用 wrapper。 */
@Serializable
private data class TestSavedPlaceStore(
    val version: Int = 1,
    val places: List<SavedPlace> = emptyList(),
)

private val SAVED_PLACES_KEY = stringPreferencesKey(PreferencesName.SAVED_PLACES)
