package me.matsumo.onenavi.core.navigation.server

import me.matsumo.onenavi.core.datasource.RouteDataSource

/**
 * route source の移行段階。
 */
internal enum class GuidanceMigrationStage {
    /** server route API を shadow / rollback 可能な経路として接続する段階。 */
    S1,
}

/**
 * route source 選択の設定。
 *
 * @param stage route source の移行段階
 * @param forceExistingSource true の間は runtime 設定に関わらず既存 source へ固定する build-time kill-switch
 */
internal data class GuidanceProviderConfig(
    val stage: GuidanceMigrationStage = GuidanceMigrationStage.S1,
    val forceExistingSource: Boolean = false,
)

/**
 * 移行設定と runtime トグルに従って既存 source と server source を切り替える [RouteDataSource]。
 *
 * @param serverRouteEnabledProvider 開発者設定の server route トグルが ON かを毎回の探索時に返す
 */
internal class GuidanceRouteDataSourceSelector(
    private val existingSource: RouteDataSource,
    private val serverSource: RouteDataSource,
    private val providerConfig: GuidanceProviderConfig,
    private val apiConfig: GuidanceApiConfig,
    private val serverRouteEnabledProvider: () -> Boolean,
) : RouteDataSource {

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
        originDirectionDegrees: Int?,
    ) = selectedSource().searchRoutes(
        originLatitude = originLatitude,
        originLongitude = originLongitude,
        destinationLatitude = destinationLatitude,
        destinationLongitude = destinationLongitude,
        intermediateWaypoints = intermediateWaypoints,
        originDirectionDegrees = originDirectionDegrees,
    )

    /**
     * 現在の設定で利用する source を返す。
     */
    private fun selectedSource(): RouteDataSource {
        val canUseServerSource = providerConfig.stage == GuidanceMigrationStage.S1
        val isRollbackForced = providerConfig.forceExistingSource
        val hasServerEndpoint = apiConfig.baseUrl.isNotBlank()
        val isEnabledByUser = serverRouteEnabledProvider()
        val shouldUseServerSource = canUseServerSource && !isRollbackForced && hasServerEndpoint && isEnabledByUser

        return if (shouldUseServerSource) {
            serverSource
        } else {
            existingSource
        }
    }
}
