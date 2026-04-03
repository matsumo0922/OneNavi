package me.matsumo.onenavi.core.datasource

import com.mapbox.geojson.Point
import com.mapbox.search.ApiType
import com.mapbox.search.ForwardSearchOptions
import com.mapbox.search.ResponseInfo
import com.mapbox.search.ReverseGeoOptions
import com.mapbox.search.SearchCallback
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchOptions
import com.mapbox.search.SearchSelectionCallback
import com.mapbox.search.SearchSuggestionsCallback
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchSuggestion
import kotlinx.coroutines.suspendCancellableCoroutine
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import kotlin.coroutines.resume

class MapboxSearchDataSource : SearchDataSource {

    private val searchEngine: SearchEngine = SearchEngine.createSearchEngine(
        ApiType.SEARCH_BOX,
        SearchEngineSettings(),
    )
    private val latestSuggestionsById = linkedMapOf<String, SearchSuggestion>()

    override suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>> {
        if (query.isBlank()) return Result.success(emptyList())

        return runCatching {
            val suggestions = searchSuggestions(
                query = query,
                options = SearchOptions(
                    limit = MAX_SUGGESTIONS,
                ),
            )

            latestSuggestionsById.clear()
            suggestions.forEach { suggestion ->
                latestSuggestionsById[suggestion.id] = suggestion
            }

            suggestions.map { suggestion ->
                SearchSuggestionItem(
                    id = suggestion.id,
                    name = suggestion.name,
                    address = suggestion.fullAddress
                        ?.takeIf { it.isNotBlank() }
                        ?: suggestion.descriptionText?.takeIf { it.isNotBlank() },
                    distanceMeters = suggestion.distanceMeters,
                    categories = suggestion.categories.orEmpty(),
                )
            }
        }
    }

    override suspend fun select(suggestionId: String): Result<SearchResultItem> {
        return runCatching {
            val suggestion = latestSuggestionsById[suggestionId]
            val result = if (suggestion != null) {
                selectSuggestion(suggestion)
            } else {
                retrieveResult(suggestionId)
            }
            result.toResultItem()
        }
    }

    override suspend fun retrieve(id: String): Result<SearchResultItem> {
        return runCatching {
            retrieveResult(id).toResultItem()
        }
    }

    override suspend fun searchMultiple(
        query: String,
        latitude: Double?,
        longitude: Double?,
    ): Result<List<SearchResultItem>> {
        if (query.isBlank()) return Result.success(emptyList())

        return runCatching {
            val proximity = if (latitude != null && longitude != null) {
                Point.fromLngLat(longitude, latitude)
            } else {
                null
            }

            searchResults(
                query = query,
                options = SearchOptions(
                    proximity = proximity,
                    limit = MAX_SEARCH_RESULTS,
                ),
            ).map { it.toResultItem() }
        }
    }

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): Result<SearchResultItem?> {
        return runCatching {
            searchByReverseGeocode(
                options = ReverseGeoOptions(
                    center = Point.fromLngLat(longitude, latitude),
                    limit = 1,
                ),
            ).firstOrNull()?.toResultItem()
        }
    }

    private suspend fun searchSuggestions(
        query: String,
        options: SearchOptions,
    ): List<SearchSuggestion> = suspendCancellableCoroutine { continuation ->
        val task = searchEngine.search(
            query,
            options,
            object : SearchSuggestionsCallback {
                override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                    if (continuation.isActive) {
                        continuation.resume(suggestions)
                    }
                }

                override fun onError(e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            },
        )

        continuation.invokeOnCancellation { task.cancel() }
    }

    private suspend fun searchResults(
        query: String,
        options: SearchOptions,
    ): List<SearchResult> = suspendCancellableCoroutine { continuation ->
        val forwardOptions = ForwardSearchOptions.Builder()
            .apply {
                options.proximity?.let(::proximity)
                options.limit?.let(::limit)
                options.origin?.let(::origin)
            }
            .build()

        searchEngine.forward(
            query,
            forwardOptions,
            object : SearchCallback {
                override fun onResults(results: List<SearchResult>, responseInfo: ResponseInfo) {
                    if (continuation.isActive) {
                        continuation.resume(results)
                    }
                }

                override fun onError(e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            },
        )
    }

    private suspend fun searchByReverseGeocode(options: ReverseGeoOptions): List<SearchResult> =
        suspendCancellableCoroutine { continuation ->
            searchEngine.search(
                options,
                object : SearchCallback {
                    override fun onResults(results: List<SearchResult>, responseInfo: ResponseInfo) {
                        if (continuation.isActive) {
                            continuation.resume(results)
                        }
                    }

                    override fun onError(e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(e))
                        }
                    }
                },
            )
        }

    private suspend fun selectSuggestion(suggestion: SearchSuggestion): SearchResult =
        suspendCancellableCoroutine { continuation ->
            searchEngine.select(
                suggestion,
                object : SearchSelectionCallback {
                    override fun onSuggestions(
                        suggestions: List<SearchSuggestion>,
                        responseInfo: ResponseInfo,
                    ) = Unit

                    override fun onResult(
                        suggestion: SearchSuggestion,
                        result: SearchResult,
                        responseInfo: ResponseInfo,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onResults(
                        suggestion: SearchSuggestion,
                        results: List<SearchResult>,
                        responseInfo: ResponseInfo,
                    ) {
                        val selected = results.firstOrNull()
                        if (selected == null) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.failure(IllegalStateException("Search selection returned no results")))
                            }
                            return
                        }
                        if (continuation.isActive) {
                            continuation.resume(selected)
                        }
                    }

                    override fun onError(e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(e))
                        }
                    }
                },
            )
        }

    private suspend fun retrieveResult(id: String): SearchResult = suspendCancellableCoroutine { continuation ->
        searchEngine.retrieve(
            id,
            object : com.mapbox.search.SearchResultCallback {
                override fun onResult(result: SearchResult, responseInfo: ResponseInfo) {
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                override fun onError(e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            },
        )
    }

    private fun SearchResult.toResultItem(): SearchResultItem {
        val metadata = metadata

        return SearchResultItem(
            placeId = id,
            name = name,
            formattedAddress = fullAddress?.takeIf { it.isNotBlank() },
            shortFormattedAddress = address?.formattedAddress(),
            latitude = coordinate.latitude(),
            longitude = coordinate.longitude(),
            viewportSouth = boundingBox?.southwest()?.latitude(),
            viewportWest = boundingBox?.southwest()?.longitude(),
            viewportNorth = boundingBox?.northeast()?.latitude(),
            viewportEast = boundingBox?.northeast()?.longitude(),
            primaryType = newTypes.firstOrNull(),
            primaryTypeDisplayName = categories.orEmpty().firstOrNull(),
            types = newTypes,
            googleMapsUri = null,
            websiteUri = metadata?.website,
            internationalPhoneNumber = metadata?.phone,
            nationalPhoneNumber = metadata?.phone,
            rating = metadata?.averageRating,
            userRatingCount = metadata?.reviewCount,
            priceLevel = metadata?.priceLevel?.length,
            businessStatus = null,
            iconBackgroundColor = null,
            iconMaskUrl = makiIcon,
            editorialSummary = metadata?.description,
            currentOpeningHours = metadata?.openHours?.toString(),
            isOpenNow = null,
        )
    }

    companion object {
        private const val MAX_SUGGESTIONS = 10
        private const val MAX_SEARCH_RESULTS = 10
    }
}
