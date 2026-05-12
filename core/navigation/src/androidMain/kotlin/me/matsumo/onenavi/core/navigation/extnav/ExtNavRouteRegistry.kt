package me.matsumo.onenavi.core.navigation.extnav

import java.util.concurrent.ConcurrentHashMap

/**
 * `RouteDetail.id` → [ExtNavRoutePayload] のルックアップテーブル。
 *
 * [me.matsumo.onenavi.core.model.RouteDetail] は core/model 層の中立モデルで drive-supporter-api
 * 依存を持ち込めないため、ExtNav 固有情報はこのレジストリに一時的に束ねる。
 * セッション終了時に [clear] を呼ぶ。
 */
class ExtNavRouteRegistry {
    private val routes = ConcurrentHashMap<String, ExtNavRoutePayload>()

    fun put(payload: ExtNavRoutePayload) {
        routes[payload.id] = payload
    }

    fun get(routeId: String): ExtNavRoutePayload? = routes[routeId]

    fun clear() {
        routes.clear()
    }
}
