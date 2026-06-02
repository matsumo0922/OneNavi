package me.matsumo.onenavi.feature.map.state

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuideImage
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuideImageGateway
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey

/**
 * 案内画像 key の変化に応じて画像取得と cache を管理する controller。
 */
internal class NavigationGuideImageController(
    private val loader: NavigationGuideImageLoader,
    private val scope: CoroutineScope,
    private val imageChanged: (NavigationGuideImage?) -> Unit,
    private val maxCacheSize: Int = NAVIGATION_GUIDE_IMAGE_CACHE_MAX_SIZE,
) {
    private val cache = object : LinkedHashMap<GuideImageKey, NavigationGuideImage?>(maxCacheSize, NAVIGATION_GUIDE_IMAGE_CACHE_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldestEntry: MutableMap.MutableEntry<GuideImageKey, NavigationGuideImage?>): Boolean {
            return size > maxCacheSize
        }
    }

    private var currentKey: GuideImageKey? = null
    private var fetchJob: Job? = null

    init {
        require(maxCacheSize > 0) { "maxCacheSize must be greater than zero." }
    }

    fun onGuideImageKeyChanged(guideImageKey: GuideImageKey?) {
        if (guideImageKey == currentKey) return

        currentKey = guideImageKey
        fetchJob?.cancel()

        if (guideImageKey == null) {
            imageChanged(null)
            return
        }

        if (cache.containsKey(guideImageKey)) {
            imageChanged(cache[guideImageKey])
            return
        }

        imageChanged(null)
        fetchJob = scope.launch {
            fetchNavigationGuideImage(guideImageKey)
        }
    }

    fun clear() {
        fetchJob?.cancel()
        fetchJob = null
        currentKey = null
        cache.clear()
        imageChanged(null)
    }

    private suspend fun fetchNavigationGuideImage(guideImageKey: GuideImageKey) {
        val navigationGuideImage = loader.load(guideImageKey)
            .onFailure { error ->
                Napier.w(tag = NAVIGATION_GUIDE_IMAGE_CONTROLLER_TAG, throwable = error) {
                    "Failed to fetch guide image. key=$guideImageKey"
                }
            }
            .getOrNull()

        cache[guideImageKey] = navigationGuideImage

        if (currentKey == guideImageKey) {
            imageChanged(navigationGuideImage)
        }
    }
}

/**
 * 案内画像 key から表示用画像を取得する loader。
 */
internal fun interface NavigationGuideImageLoader {
    suspend fun load(guideImageKey: GuideImageKey): Result<NavigationGuideImage?>
}

/**
 * 外部ナビ API の案内画像取得 gateway を Compose 表示用画像へ変換する loader。
 */
internal class ExtNavNavigationGuideImageLoader(
    private val guideImageGateway: ExtNavGuideImageGateway,
) : NavigationGuideImageLoader {
    override suspend fun load(guideImageKey: GuideImageKey): Result<NavigationGuideImage?> {
        return guideImageGateway.fetch(guideImageKey)
            .mapCatching { guideImage -> guideImage.toNavigationGuideImageOrNull() }
    }

    private fun ExtNavGuideImage.toNavigationGuideImageOrNull(): NavigationGuideImage? {
        if (isMissing) return null

        val imageBitmap = decodeGuideImageBitmap(bytes) ?: return null
        return NavigationGuideImage(
            key = key,
            bitmap = imageBitmap,
        )
    }

    private fun decodeGuideImageBitmap(bytes: ByteArray) = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/** 案内画像 cache の最大保持件数。 */
private const val NAVIGATION_GUIDE_IMAGE_CACHE_MAX_SIZE = 24

/** 案内画像 cache の load factor。 */
private const val NAVIGATION_GUIDE_IMAGE_CACHE_LOAD_FACTOR = 0.75f

/** 案内画像 controller のログ tag。 */
private const val NAVIGATION_GUIDE_IMAGE_CONTROLLER_TAG = "NavigationGuideImageController"
