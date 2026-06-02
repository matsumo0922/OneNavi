package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.core.result.ApiFailure
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.image.domain.GuideImage
import me.matsumo.drive.supporter.api.image.domain.GuideImageRequest
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey

/** 案内画像 API から取得する既定フォーマットの MIME type。 */
private const val WEBP_CONTENT_TYPE: String = "image/webp"

/**
 * 外部ナビ API ライブラリ由来の案内画像を取得する窓口。
 *
 * ルート検索で得た [ExtNavRoutePayload] または semantic 層の [GuideImageKey] から、
 * UI がデコードできる WEBP bytes を取得する。
 */
class ExtNavGuideImageGateway(
    private val clientProvider: ExtNavClientProvider,
    private val authGateway: ExtNavAuthGateway,
    private val routeRegistry: ExtNavRouteRegistry,
) {
    /**
     * [routeId] に紐付くルート上の案内画像を一括プリロードする。
     */
    suspend fun preload(routeId: String): Result<ImmutableList<ExtNavGuideImage>> {
        val payload = routeRegistry.get(routeId)
            ?: return Result.failure(ExtNavGuideImageRouteNotFoundException(routeId))
        return preload(payload)
    }

    /**
     * [payload] に含まれる全案内画像 ID を一括取得する。
     */
    suspend fun preload(payload: ExtNavRoutePayload): Result<ImmutableList<ExtNavGuideImage>> =
        fetchAll(payload.routeGuidance.toGuideImageKeys())

    /**
     * 1 枚の案内画像を取得する。
     */
    suspend fun fetch(key: GuideImageKey): Result<ExtNavGuideImage> =
        fetchAll(listOf(key)).mapCatching { images ->
            requireNotNull(images.firstOrNull()) {
                "guide image response did not contain id=${key.minor}"
            }
        }

    /**
     * 指定された案内画像キー群をまとめて取得する。
     */
    suspend fun fetchAll(keys: List<GuideImageKey>): Result<ImmutableList<ExtNavGuideImage>> {
        val distinctKeys = keys.distinct()
        val requestIds = distinctKeys.toGuideImageRequestIds()
        if (requestIds.isEmpty()) return Result.success(persistentListOf())

        authGateway.ensureSignedIn().getOrElse { cause ->
            return Result.failure(cause)
        }

        val client = clientProvider.get()
        val request = GuideImageRequest(
            ids = requestIds,
        )
        return when (val result = client.image.fetch(request)) {
            is ApiResult.Success -> {
                val keysByMinor = distinctKeys.groupBy { key -> key.minor }
                val images = result.value
                    .flatMap { image -> image.toExtNavGuideImages(keysByMinor) }
                    .toImmutableList()
                Result.success(images)
            }
            is ApiResult.Failure -> Result.failure(ExtNavGuideImageApiException(result.failure))
        }
    }

    private fun RouteGuidance.toGuideImageKeys(): ImmutableList<GuideImageKey> =
        imageIds
            .map { imageRef ->
                GuideImageKey(
                    major = imageRef.major,
                    minor = imageRef.minor,
                )
            }
            .distinct()
            .toImmutableList()
}

internal fun List<GuideImageKey>.toGuideImageRequestIds(): ImmutableList<Int> =
    map { key -> key.minor }
        .distinct()
        .toImmutableList()

internal fun GuideImage.toExtNavGuideImages(
    keysByMinor: Map<Int, List<GuideImageKey>>,
): List<ExtNavGuideImage> {
    val matchingKeys = keysByMinor[id] ?: return emptyList()
    return matchingKeys.map { key ->
        ExtNavGuideImage(
            key = key,
            bytes = bytes,
            contentType = WEBP_CONTENT_TYPE,
            isMissing = isMissing,
        )
    }
}

/**
 * OneNavi 側で扱う案内画像。
 *
 * [bytes] は [contentType] 形式の raw バイト列。UI 側で `ImageBitmap` や Coil へ渡してデコードする。
 */
@Immutable
class ExtNavGuideImage(
    val key: GuideImageKey,
    val bytes: ByteArray,
    val contentType: String,
    val isMissing: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtNavGuideImage) return false
        val hasSameKey = key == other.key
        val hasSameContentType = contentType == other.contentType
        val hasSameMissingState = isMissing == other.isMissing
        val hasSameMetadata = hasSameKey && hasSameContentType && hasSameMissingState
        return hasSameMetadata && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + isMissing.hashCode()
        return result
    }

    override fun toString(): String =
        "ExtNavGuideImage(key=$key, bytes=${bytes.size}B, contentType=$contentType, isMissing=$isMissing)"
}

/**
 * 案内画像 API の失敗を OneNavi 側の [Result] に載せる例外。
 */
class ExtNavGuideImageApiException(
    val failure: ApiFailure,
) : Exception("guide image api failed: $failure")

/**
 * [ExtNavRouteRegistry] に対象ルートの payload が無かったことを表す例外。
 */
class ExtNavGuideImageRouteNotFoundException(
    val routeId: String,
) : Exception("route payload not found: $routeId")
