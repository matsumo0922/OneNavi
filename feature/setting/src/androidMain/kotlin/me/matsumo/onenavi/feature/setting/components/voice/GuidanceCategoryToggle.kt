package me.matsumo.onenavi.feature.setting.components.voice

import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_voice_category_accident_black_spot
import me.matsumo.onenavi.core.resource.setting_voice_category_auto_expressway_entry
import me.matsumo.onenavi.core.resource.setting_voice_category_curve
import me.matsumo.onenavi.core.resource.setting_voice_category_flood
import me.matsumo.onenavi.core.resource.setting_voice_category_highway_construction_pr
import me.matsumo.onenavi.core.resource.setting_voice_category_highway_lane_reduction
import me.matsumo.onenavi.core.resource.setting_voice_category_highway_recommended_lane
import me.matsumo.onenavi.core.resource.setting_voice_category_highway_recommended_lane_jam_aware
import me.matsumo.onenavi.core.resource.setting_voice_category_intersection_guide
import me.matsumo.onenavi.core.resource.setting_voice_category_intersection_guide_soon
import me.matsumo.onenavi.core.resource.setting_voice_category_jam_scale_trend
import me.matsumo.onenavi.core.resource.setting_voice_category_landmark
import me.matsumo.onenavi.core.resource.setting_voice_category_local_lane_exclusive
import me.matsumo.onenavi.core.resource.setting_voice_category_local_road_direction
import me.matsumo.onenavi.core.resource.setting_voice_category_merge
import me.matsumo.onenavi.core.resource.setting_voice_category_merge_attention
import me.matsumo.onenavi.core.resource.setting_voice_category_narrow_street
import me.matsumo.onenavi.core.resource.setting_voice_category_oncoming_car
import me.matsumo.onenavi.core.resource.setting_voice_category_orbis
import me.matsumo.onenavi.core.resource.setting_voice_category_pedestrian_crossing
import me.matsumo.onenavi.core.resource.setting_voice_category_police_trap
import me.matsumo.onenavi.core.resource.setting_voice_category_railway_crossing
import me.matsumo.onenavi.core.resource.setting_voice_category_regulation
import me.matsumo.onenavi.core.resource.setting_voice_category_regulation_break
import me.matsumo.onenavi.core.resource.setting_voice_category_road_name
import me.matsumo.onenavi.core.resource.setting_voice_category_sag_part
import me.matsumo.onenavi.core.resource.setting_voice_category_scenic
import me.matsumo.onenavi.core.resource.setting_voice_category_speed_adjustment
import me.matsumo.onenavi.core.resource.setting_voice_category_stop_line
import me.matsumo.onenavi.core.resource.setting_voice_category_traffic_jam
import me.matsumo.onenavi.core.resource.setting_voice_category_traffic_light
import me.matsumo.onenavi.core.resource.setting_voice_category_tunnel_branch
import me.matsumo.onenavi.core.resource.setting_voice_category_turn_attention
import me.matsumo.onenavi.core.resource.setting_voice_category_uphill_lane
import me.matsumo.onenavi.core.resource.setting_voice_category_vehicle_height
import me.matsumo.onenavi.core.resource.setting_voice_category_wrong_entry
import me.matsumo.onenavi.core.resource.setting_voice_category_wrong_way_driving
import me.matsumo.onenavi.core.resource.setting_voice_category_zone30
import org.jetbrains.compose.resources.StringResource

/**
 * 設定画面で発話の ON / OFF を切り替えられる案内カテゴリ。
 *
 * [key] は外部ナビ API の category 名 (`GuidanceCategory.name`) と一致させ、永続化と発話判定の照合に用いる。
 * 表示名は [key] をそのまま使い、[description] に日本語の補足を表示する。
 *
 * @property key 永続化・発話判定で使う category 識別子 (外部ナビ API の category 名と一致)
 * @property description 行に表示する日本語の補足説明
 */
internal enum class GuidanceCategoryToggle(
    val key: String,
    val description: StringResource,
) {
    /** 交差点案内 (通常)。 */
    IntersectionGuide("IntersectionGuide", Res.string.setting_voice_category_intersection_guide),

    /** 交差点直前案内。 */
    IntersectionGuideSoon("IntersectionGuideSoon", Res.string.setting_voice_category_intersection_guide_soon),

    /** 速度調整案内。 */
    SpeedAdjustment("SpeedAdjustment", Res.string.setting_voice_category_speed_adjustment),

    /** 事故多発地点案内。 */
    AccidentBlackSpot("AccidentBlackSpot", Res.string.setting_voice_category_accident_black_spot),

    /** 逆走警告。 */
    WrongWayDriving("WrongWayDriving", Res.string.setting_voice_category_wrong_way_driving),

    /** 高速道路の推奨レーン案内。 */
    HighwayRecommendedLane("HighwayRecommendedLane", Res.string.setting_voice_category_highway_recommended_lane),

    /** 高速道路の推奨レーン案内 (渋滞考慮版)。 */
    HighwayRecommendedLaneJamAware(
        "HighwayRecommendedLaneJamAware",
        Res.string.setting_voice_category_highway_recommended_lane_jam_aware,
    ),

    /** ランドマーク案内。 */
    Landmark("Landmark", Res.string.setting_voice_category_landmark),

    /** 道路名案内。 */
    RoadName("RoadName", Res.string.setting_voice_category_road_name),

    /** 右左折注意案内。 */
    TurnAttention("TurnAttention", Res.string.setting_voice_category_turn_attention),

    /** 一般道方面案内。 */
    LocalRoadDirection("LocalRoadDirection", Res.string.setting_voice_category_local_road_direction),

    /** 信号機案内。 */
    TrafficLight("TrafficLight", Res.string.setting_voice_category_traffic_light),

    /** 誤進入警告。 */
    WrongEntry("WrongEntry", Res.string.setting_voice_category_wrong_entry),

    /** 高速道路自動進入案内。 */
    AutoExpresswayEntry("AutoExpresswayEntry", Res.string.setting_voice_category_auto_expressway_entry),

    /** オービス案内。 */
    Orbis("Orbis", Res.string.setting_voice_category_orbis),

    /** 景観案内。 */
    Scenic("Scenic", Res.string.setting_voice_category_scenic),

    /** 専用レーン案内。 */
    LocalLaneExclusive("LocalLaneExclusive", Res.string.setting_voice_category_local_lane_exclusive),

    /** 合流案内。 */
    Merge("Merge", Res.string.setting_voice_category_merge),

    /** 渋滞案内。 */
    TrafficJam("TrafficJam", Res.string.setting_voice_category_traffic_jam),

    /** 規制案内。 */
    Regulation("Regulation", Res.string.setting_voice_category_regulation),

    /** 冠水注意地点案内。 */
    Flood("Flood", Res.string.setting_voice_category_flood),

    /** ゾーン 30 案内。 */
    Zone30("Zone30", Res.string.setting_voice_category_zone30),

    /** 踏切案内。 */
    RailwayCrossing("RailwayCrossing", Res.string.setting_voice_category_railway_crossing),

    /** 速度違反取締案内。 */
    PoliceTrap("PoliceTrap", Res.string.setting_voice_category_police_trap),

    /** 合流注意案内。 */
    MergeAttention("MergeAttention", Res.string.setting_voice_category_merge_attention),

    /** 高速工事広報案内。 */
    HighwayConstructionPr("HighwayConstructionPr", Res.string.setting_voice_category_highway_construction_pr),

    /** 規制解除案内。 */
    RegulationBreak("RegulationBreak", Res.string.setting_voice_category_regulation_break),

    /** 高速車線減少案内。 */
    HighwayLaneReduction("HighwayLaneReduction", Res.string.setting_voice_category_highway_lane_reduction),

    /** 一時停止案内。 */
    StopLine("StopLine", Res.string.setting_voice_category_stop_line),

    /** 細街路案内。 */
    NarrowStreet("NarrowStreet", Res.string.setting_voice_category_narrow_street),

    /** 対向車注意。 */
    OncomingCar("OncomingCar", Res.string.setting_voice_category_oncoming_car),

    /** 車両高さ・道幅注意。 */
    VehicleHeight("VehicleHeight", Res.string.setting_voice_category_vehicle_height),

    /** 急カーブ注意案内。 */
    Curve("Curve", Res.string.setting_voice_category_curve),

    /** サグ部案内。 */
    SagPart("SagPart", Res.string.setting_voice_category_sag_part),

    /** トンネル内分岐案内。 */
    TunnelBranch("TunnelBranch", Res.string.setting_voice_category_tunnel_branch),

    /** 歩行者横断警告。 */
    PedestrianCrossing("PedestrianCrossing", Res.string.setting_voice_category_pedestrian_crossing),

    /** 渋滞規模傾向案内。 */
    JamScaleTrend("JamScaleTrend", Res.string.setting_voice_category_jam_scale_trend),

    /** 登坂車線案内。 */
    UphillLane("UphillLane", Res.string.setting_voice_category_uphill_lane),
}
