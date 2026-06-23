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
 *
 * 取得済みの画像は minor ID をキーとして内部キャッシュに保持し、同一画像の再取得を避ける。
 * 空 ID (minor <= 0) および重複 ID は API に送らない。
 */
class ExtNavGuideImageGateway internal constructor(
    private val backend: ExtNavGuideImageGatewayBackend,
    private val routeRegistry: ExtNavRouteRegistry,
) {
    /**
     * minor ID をキーとする画像キャッシュ。
     *
     * 同一 minor は画像バイナリが同一なので minor 単位で保持すれば十分。
     * major が異なっても minor が同じなら同一バイナリを返す。
     */
    private val imageCache: MutableMap<Int, GuideImage> = mutableMapOf()

    constructor(
        clientProvider: ExtNavClientProvider,
        authGateway: ExtNavAuthGateway,
        routeRegistry: ExtNavRouteRegistry,
    ) : this(
        backend = DefaultExtNavGuideImageGatewayBackend(
            clientProvider = clientProvider,
            authGateway = authGateway,
        ),
        routeRegistry = routeRegistry,
    )

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
     *
     * 空 ID (minor <= 0) と重複 ID は除外した上で、既にキャッシュに存在する minor は
     * API に送らない。キャッシュ未命中分のみ API を呼び出す。
     */
    suspend fun fetchAll(keys: List<GuideImageKey>): Result<ImmutableList<ExtNavGuideImage>> {
        val validKeys = keys.filterValidKeys()
        if (validKeys.isEmpty()) return Result.success(persistentListOf())

        val (cachedImages, uncachedKeys) = splitByCache(validKeys)
        val uncachedRequestIds = uncachedKeys.toGuideImageRequestIds()

        val fetchedImages = if (uncachedRequestIds.isEmpty()) {
            emptyList()
        } else {
            val newImages = fetchFromBackend(uncachedKeys, uncachedRequestIds).getOrElse { cause ->
                return Result.failure(cause)
            }
            newImages
        }

        val allImages = (cachedImages + fetchedImages).toImmutableList()
        return Result.success(allImages)
    }

    /**
     * キャッシュ済み画像と未キャッシュキーに分割する。
     *
     * @return Pair(キャッシュから復元した [ExtNavGuideImage] 一覧, キャッシュ未命中の [GuideImageKey] 一覧)
     */
    private fun splitByCache(
        validKeys: List<GuideImageKey>,
    ): Pair<List<ExtNavGuideImage>, List<GuideImageKey>> {
        val cached = mutableListOf<ExtNavGuideImage>()
        val uncached = mutableListOf<GuideImageKey>()

        for (key in validKeys) {
            val hit = imageCache[key.minor]
            if (hit != null) {
                cached.add(hit.toExtNavGuideImage(key))
            } else {
                uncached.add(key)
            }
        }

        return cached to uncached
    }

    /**
     * 未キャッシュキーを backend から取得し、結果をキャッシュに書き込む。
     */
    private suspend fun fetchFromBackend(
        uncachedKeys: List<GuideImageKey>,
        uncachedRequestIds: ImmutableList<Int>,
    ): Result<List<ExtNavGuideImage>> {
        backend.ensureSignedIn().getOrElse { cause ->
            return Result.failure(cause)
        }

        val request = GuideImageRequest(ids = uncachedRequestIds)
        return when (val result = backend.fetch(request)) {
            is ApiResult.Success -> {
                val keysByMinor = uncachedKeys.groupBy { key -> key.minor }
                val images = result.value.flatMap { image ->
                    imageCache[image.id] = image
                    image.toExtNavGuideImages(keysByMinor)
                }
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

/**
 * [ExtNavGuideImageGateway] が利用する認証済み案内画像取得 backend。
 */
internal interface ExtNavGuideImageGatewayBackend {
    suspend fun ensureSignedIn(): Result<Unit>

    suspend fun fetch(request: GuideImageRequest): ApiResult<ImmutableList<GuideImage>>
}

/**
 * 外部ナビ API ライブラリの client provider / auth gateway を使う既定 backend。
 */
private class DefaultExtNavGuideImageGatewayBackend(
    private val clientProvider: ExtNavClientProvider,
    private val authGateway: ExtNavAuthGateway,
) : ExtNavGuideImageGatewayBackend {

    override suspend fun ensureSignedIn(): Result<Unit> =
        authGateway.ensureSignedIn()

    override suspend fun fetch(request: GuideImageRequest): ApiResult<ImmutableList<GuideImage>> {
        val client = clientProvider.get()
        return client.image.fetch(request)
    }
}

/**
 * [GuideImageKey] リストから有効なキーのみを返す純粋関数。
 *
 * minor が 0 以下のキーは API が受け付けない空 ID として除外する。
 * 重複キーも除外する。
 */
internal fun List<GuideImageKey>.filterValidKeys(): List<GuideImageKey> {
    val isValidMinor = { key: GuideImageKey -> key.minor > 0 }
    return filter(isValidMinor).distinct()
}

/**
 * [GuideImageKey] リストから minor ID のみを抽出して重複排除した一覧を返す純粋関数。
 */
internal fun List<GuideImageKey>.toGuideImageRequestIds(): ImmutableList<Int> =
    map { key -> key.minor }
        .distinct()
        .toImmutableList()

/**
 * [GuideImage] を [GuideImageKey] で修飾して [ExtNavGuideImage] に変換する。
 */
internal fun GuideImage.toExtNavGuideImage(key: GuideImageKey): ExtNavGuideImage =
    ExtNavGuideImage(
        key = key,
        bytes = bytes,
        contentType = WEBP_CONTENT_TYPE,
        isMissing = isMissing,
    )

internal fun GuideImage.toExtNavGuideImages(
    keysByMinor: Map<Int, List<GuideImageKey>>,
): List<ExtNavGuideImage> {
    val matchingKeys = keysByMinor[id] ?: return emptyList()
    return matchingKeys.map { key -> toExtNavGuideImage(key) }
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
