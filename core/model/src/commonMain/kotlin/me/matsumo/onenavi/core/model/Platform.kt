package me.matsumo.onenavi.core.model

expect val currentPlatform: Platform

enum class Platform {
    Android,
    IOS,
}
