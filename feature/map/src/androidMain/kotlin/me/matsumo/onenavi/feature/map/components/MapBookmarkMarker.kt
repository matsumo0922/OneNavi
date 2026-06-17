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
 * @param showBookmarkIcon marker 中央にブックマークアイコンを表示する場合 true
 * @param onClicked marker がタップされた時の callback
 */
@Composable
internal fun MapBookmarkMarker(
    googleMap: GoogleMap,
    place: SavedPlace,
    zIndex: Float,
    showBookmarkIcon: Boolean,
    onClicked: (SavedPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = rememberBookmarkMarkerIcon(
        showBookmarkIcon = showBookmarkIcon,
        modifier = modifier,
    )
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
    showBookmarkIcon: Boolean,
    modifier: Modifier = Modifier,
): BitmapDescriptor {
    return rememberMapComposeBitmapDescriptor("bookmark-marker", showBookmarkIcon) {
        BookmarkMarkerIcon(
            modifier = modifier,
            showBookmarkIcon = showBookmarkIcon,
        )
    }
}

@Composable
private fun BookmarkMarkerIcon(
    showBookmarkIcon: Boolean,
    modifier: Modifier = Modifier,
) {
    val markerSize = if (showBookmarkIcon) BookmarkMarkerSize else BookmarkMarkerDotSize
    val markerModifier = modifier
        .size(markerSize)
        .clip(CircleShape)
        .background(BookmarkMarkerBackgroundColor)
        .border(
            width = BookmarkMarkerBorderWidth,
            color = BookmarkMarkerBorderColor,
            shape = CircleShape,
        )

    Box(
        modifier = markerModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (showBookmarkIcon) {
            Icon(
                modifier = Modifier.size(BookmarkMarkerIconSize),
                imageVector = Icons.Outlined.Bookmark,
                contentDescription = null,
                tint = BookmarkMarkerIconColor,
            )
        }
    }
}

private fun bookmarkMarkerTag(id: String): String {
    return "bookmark:$id"
}

/** ブックマーク marker の描画領域サイズ。 */
private val BookmarkMarkerSize = 28.dp

/** 広域表示時のブックマーク marker 点サイズ。 */
private val BookmarkMarkerDotSize = 10.dp

/** ブックマーク marker の中心アイコンサイズ。 */
private val BookmarkMarkerIconSize = 16.dp

/** ブックマーク marker の枠線幅。 */
private val BookmarkMarkerBorderWidth = 1.5.dp

/** ブックマーク marker の背景色。 */
private val BookmarkMarkerBackgroundColor = Color(0xFF2E7D32)

/** ブックマーク marker の枠線色。 */
private val BookmarkMarkerBorderColor = Color(0xFF8E8E8E)

/** ブックマーク marker のアイコン色。 */
private val BookmarkMarkerIconColor = Color.White
