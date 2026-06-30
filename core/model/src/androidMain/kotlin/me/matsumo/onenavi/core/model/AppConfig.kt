package me.matsumo.onenavi.core.model

/**
 * アプリのビルド設定値を保持するデータクラス。
 * AdMob の広告ユニット ID や課金 API キーなど、ビルド時に決定される値を管理する。
 */
data class AppConfig(
    val versionName: String,
    val versionCode: Int,
    val developerPin: String,
    val googleApiKey: String,
    val googleCloudTtsApiKey: String,
    val serverRouteBaseUrl: String,
    val serverRouteCfAccessClientIdHeader: String,
    val serverRouteCfAccessClientSecretHeader: String,
    val adMobAppId: String,
    val adMobInterstitialAdUnitId: String,
    val adMobBannerAdUnitId: String,
    val adMobRewardedAdUnitId: String,
    val purchaseAndroidApiKey: String?,
    val purchaseIosApiKey: String?,
    val extNavLoginId: String,
    val extNavPassword: String,
)
