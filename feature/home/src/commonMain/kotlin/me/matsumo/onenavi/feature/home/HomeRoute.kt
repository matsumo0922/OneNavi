package me.matsumo.onenavi.feature.home

import kotlinx.serialization.Serializable

@Serializable
sealed interface HomeRoute {
    @Serializable
    data object Photos : HomeRoute

    @Serializable
    data object Downloads : HomeRoute
}
