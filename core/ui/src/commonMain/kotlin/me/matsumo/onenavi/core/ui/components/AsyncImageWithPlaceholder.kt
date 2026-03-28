package me.matsumo.onenavi.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.revenuecat.placeholder.PlaceholderDefaults
import com.revenuecat.placeholder.placeholder
import io.github.aakira.napier.Napier

@Composable
fun AsyncImageWithPlaceholder(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var isError by rememberSaveable { mutableStateOf(false) }

    if (isError) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AsyncImage(
            modifier = modifier.placeholder(
                enabled = isLoading,
                highlight = PlaceholderDefaults.pulse,
            ),
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            onSuccess = {
                isLoading = false
            },
            onError = {
                Napier.w(it.result.throwable) { "Failed to load image. $model" }

                isLoading = false
                isError = true
            },
        )
    }
}
