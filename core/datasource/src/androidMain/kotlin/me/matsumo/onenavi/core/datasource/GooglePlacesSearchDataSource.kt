package me.matsumo.onenavi.core.datasource

import android.content.Context
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import kotlinx.coroutines.tasks.await
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

class GooglePlacesSearchDataSource(
    context: Context,
    googleApiKey: String,
) : SearchDataSource {

    private val placesClient: PlacesClient

    private var sessionToken: AutocompleteSessionToken = AutocompleteSessionToken.newInstance()

    init {
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(context, googleApiKey)
        }
        placesClient = Places.createClient(context)
    }

    override suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>> {
        if (query.isBlank()) return Result.success(emptyList())

        return runCatching {
            val request = FindAutocompletePredictionsRequest.builder()
                .setQuery(query)
                .setSessionToken(sessionToken)
                .build()

            val response = placesClient.findAutocompletePredictions(request).await()

            response.autocompletePredictions.map { prediction ->
                SearchSuggestionItem(
                    id = prediction.placeId,
                    name = prediction.getPrimaryText(null).toString(),
                    address = prediction.getSecondaryText(null).toString().takeIf { it.isNotBlank() },
                    distanceMeters = prediction.distanceMeters?.toDouble(),
                    categories = emptyList(),
                )
            }
        }
    }

    override suspend fun select(suggestionId: String): Result<SearchResultItem> {
        return runCatching {
            val request = FetchPlaceRequest.builder(suggestionId, DETAIL_FIELDS)
                .setSessionToken(sessionToken)
                .build()

            val response = placesClient.fetchPlace(request).await()

            // セッション終了 → 新しいトークンを生成
            sessionToken = AutocompleteSessionToken.newInstance()

            response.place.toResultItem()
        }
    }

    override suspend fun retrieve(id: String): Result<SearchResultItem> {
        return runCatching {
            val request = FetchPlaceRequest.builder(id, DETAIL_FIELDS).build()
            val response = placesClient.fetchPlace(request).await()
            response.place.toResultItem()
        }
    }

    override suspend fun searchMultiple(
        query: String,
        latitude: Double?,
        longitude: Double?,
    ): Result<List<SearchResultItem>> {
        if (query.isBlank()) return Result.success(emptyList())

        return runCatching {
            val requestBuilder = SearchByTextRequest.builder(query, DETAIL_FIELDS)
                .setMaxResultCount(MAX_SEARCH_RESULTS)

            if (latitude != null && longitude != null) {
                val bounds = CircularBounds.newInstance(
                    com.google.android.gms.maps.model.LatLng(latitude, longitude),
                    PROXIMITY_RADIUS_METERS,
                )
                requestBuilder.setLocationBias(bounds)
            }

            val response = placesClient.searchByText(requestBuilder.build()).await()
            response.places.map { it.toResultItem() }
        }
    }

    companion object {
        private const val MAX_SEARCH_RESULTS = 10
        private const val PROXIMITY_RADIUS_METERS = 50_000.0

        private val DETAIL_FIELDS = listOf(
            Place.Field.ID,
            Place.Field.DISPLAY_NAME,
            Place.Field.FORMATTED_ADDRESS,
            Place.Field.SHORT_FORMATTED_ADDRESS,
            Place.Field.LOCATION,
            Place.Field.VIEWPORT,
            Place.Field.TYPES,
            Place.Field.PRIMARY_TYPE,
            Place.Field.PRIMARY_TYPE_DISPLAY_NAME,
            Place.Field.GOOGLE_MAPS_URI,
            Place.Field.WEBSITE_URI,
            Place.Field.INTERNATIONAL_PHONE_NUMBER,
            Place.Field.NATIONAL_PHONE_NUMBER,
            Place.Field.RATING,
            Place.Field.USER_RATING_COUNT,
            Place.Field.PRICE_LEVEL,
            Place.Field.BUSINESS_STATUS,
            Place.Field.ICON_BACKGROUND_COLOR,
            Place.Field.ICON_MASK_URL,
            Place.Field.EDITORIAL_SUMMARY,
            Place.Field.CURRENT_OPENING_HOURS,
        )
    }
}

private fun Place.toResultItem(): SearchResultItem {
    val viewport = viewport

    return SearchResultItem(
        placeId = id ?: "",
        name = displayName ?: "",
        formattedAddress = formattedAddress,
        shortFormattedAddress = shortFormattedAddress,
        latitude = location?.latitude ?: 0.0,
        longitude = location?.longitude ?: 0.0,
        viewportSouth = viewport?.southwest?.latitude,
        viewportWest = viewport?.southwest?.longitude,
        viewportNorth = viewport?.northeast?.latitude,
        viewportEast = viewport?.northeast?.longitude,
        primaryType = primaryType,
        primaryTypeDisplayName = primaryTypeDisplayName,
        types = placeTypes?.map { it.toString() }.orEmpty(),
        googleMapsUri = googleMapsUri?.toString(),
        websiteUri = websiteUri?.toString(),
        internationalPhoneNumber = internationalPhoneNumber,
        nationalPhoneNumber = nationalPhoneNumber,
        rating = rating,
        userRatingCount = userRatingCount,
        priceLevel = priceLevel,
        businessStatus = businessStatus?.name,
        iconBackgroundColor = iconBackgroundColor?.let {
            String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and it)
        },
        iconMaskUrl = iconMaskUrl,
        editorialSummary = editorialSummary,
        currentOpeningHours = currentOpeningHours?.weekdayText?.joinToString("\n"),
        isOpenNow = null,
    )
}
