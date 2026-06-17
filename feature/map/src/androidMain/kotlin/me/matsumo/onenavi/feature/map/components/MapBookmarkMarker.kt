package me.matsumo.onenavi.feature.map.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import me.matsumo.onenavi.core.model.SavedPlace
import me.matsumo.onenavi.feature.map.components.callout.rememberMapComposeBitmapDescriptor

/**
 * 保存済みブックマーク地点の marker を描画する。
 *
 * @param googleMap marker 描画先の GoogleMap
 * @param place 描画対象の保存地点
 * @param zIndex marker の zIndex
 * @param onClicked marker がタップされた時の callback
 */
@Composable
internal fun MapBookmarkMarker(
    googleMap: GoogleMap,
    place: SavedPlace,
    zIndex: Float,
    onClicked: (SavedPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = rememberBookmarkMarkerIcon(modifier)
    val clickDispatcher = LocalMapMarkerClickDispatcher.current
    val markerTag = bookmarkMarkerTag(place.id)
    val currentOnClicked = rememberUpdatedState(onClicked)

    DisposableEffect(googleMap, place, zIndex, icon, clickDispatcher, markerTag) {
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(place.latitude, place.longitude))
                .title(place.name)
                .anchor(0.5f, 0.5f)
                .icon(icon)
                .zIndex(zIndex),
        )

        marker?.tag = markerTag
        clickDispatcher?.register(markerTag) {
            currentOnClicked.value(place)
        }

        onDispose {
            marker?.remove()
            clickDispatcher?.unregister(markerTag)
        }
    }
}

@Composable
private fun rememberBookmarkMarkerIcon(
    modifier: Modifier = Modifier,
): BitmapDescriptor {
    return rememberMapComposeBitmapDescriptor("bookmark-marker") {
        BookmarkMarkerIcon(
            modifier = modifier,
        )
    }
}

@Composable
private fun BookmarkMarkerIcon(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(BookmarkMarkerSize)
            .clip(CircleShape)
            .background(BookmarkMarkerBackgroundColor)
            .border(
                width = BookmarkMarkerBorderWidth,
                color = BookmarkMarkerBorderColor,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = Modifier.size(BookmarkMarkerIconSize),
            imageVector = Icons.Outlined.Bookmark,
            contentDescription = null,
            tint = BookmarkMarkerIconColor,
        )
    }
}

private fun bookmarkMarkerTag(id: String): String {
    return "bookmark:$id"
}

/** ブックマーク marker の描画領域サイズ。 */
private val BookmarkMarkerSize = 36.dp

/** ブックマーク marker の中心アイコンサイズ。 */
private val BookmarkMarkerIconSize = 20.dp

/** ブックマーク marker の枠線幅。 */
private val BookmarkMarkerBorderWidth = 2.dp

/** ブックマーク marker の背景色。 */
private val BookmarkMarkerBackgroundColor = Color(0xFF2E7D32)

/** ブックマーク marker の枠線色。 */
private val BookmarkMarkerBorderColor = Color(0xFF8E8E8E)

/** ブックマーク marker のアイコン色。 */
private val BookmarkMarkerIconColor = Color.White
