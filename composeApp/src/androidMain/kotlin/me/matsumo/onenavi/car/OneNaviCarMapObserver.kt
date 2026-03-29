package me.matsumo.onenavi.car

import com.mapbox.maps.extension.androidauto.MapboxCarMapObserver
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location

/**
 * Android Auto の地図サーフェスが利用可能になったとき、
 * Mapbox 地図のスタイル・ロケーションパックを設定するオブザーバー。
 */
class OneNaviCarMapObserver : MapboxCarMapObserver {

    override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
        val mapboxMap = mapboxCarMapSurface.mapSurface.getMapboxMap()
        mapboxMap.loadStyle("mapbox://styles/mapbox/standard")

        val locationPlugin = mapboxCarMapSurface.mapSurface.location
        locationPlugin.updateSettings {
            enabled = true
            locationPuck = createDefault2DPuck(withBearing = true)
        }
    }

    override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
        // no-op
    }
}
