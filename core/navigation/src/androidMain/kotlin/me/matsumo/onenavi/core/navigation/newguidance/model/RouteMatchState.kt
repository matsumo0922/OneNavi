package me.matsumo.onenavi.core.navigation.newguidance.model

/**
 * 現在位置と案内 route の一致状態。
 *
 * Route 上の進捗計算と、地図上に表示する実位置の切り替えに使う。`OFF_ROUTE_CANDIDATE`
 * 以上では古い route への自車アイコン吸着を避け、地図側は SDK road-snapped / raw GPS 位置を優先する。
 */
enum class RouteMatchState {

    /** 現在位置が案内 route 上にあると扱える状態。 */
    ON_ROUTE,

    /** route から外れた可能性があるが、リルート確定前の状態。 */
    OFF_ROUTE_CANDIDATE,

    /** route 逸脱が継続し、リルート要求対象として扱える状態。 */
    OFF_ROUTE_CONFIRMED,
}
