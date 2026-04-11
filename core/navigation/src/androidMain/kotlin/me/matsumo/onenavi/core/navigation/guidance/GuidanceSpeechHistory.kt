package me.matsumo.onenavi.core.navigation.guidance

/**
 * ルート単位で発話済みイベントを記録し、重複発話を防ぐクラス。
 */
class GuidanceSpeechHistory {

    private var currentRouteId: String? = null
    private val spokenAtMillis = mutableMapOf<GuidanceEventId, Long>()

    fun resetForNewRoute(routeId: String) {
        if (currentRouteId == routeId) return
        currentRouteId = routeId
        spokenAtMillis.clear()
    }

    fun hasSpoken(id: GuidanceEventId): Boolean {
        return spokenAtMillis.containsKey(id)
    }

    fun markSpoken(id: GuidanceEventId, nowMillis: Long = System.currentTimeMillis()) {
        spokenAtMillis[id] = nowMillis
    }

    fun lastSpokenAt(id: GuidanceEventId): Long? {
        return spokenAtMillis[id]
    }

    fun clear() {
        currentRouteId = null
        spokenAtMillis.clear()
    }
}
