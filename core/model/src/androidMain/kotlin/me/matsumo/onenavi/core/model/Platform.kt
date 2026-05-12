package me.matsumo.onenavi.core.model

/** 実行中のプラットフォームを表す列挙型。 */
enum class Platform {
    Android,
    IOS,
}

val currentPlatform: Platform = Platform.Android
