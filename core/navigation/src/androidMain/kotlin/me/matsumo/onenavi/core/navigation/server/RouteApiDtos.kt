package me.matsumo.onenavi.core.navigation.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 経路探索 API の request DTO。
 */
@Serializable
internal data class RoutePlanRequestDto(
    val origin: RouteCoordinateDto,
    val destination: RouteCoordinateDto,
    val via: List<RouteCoordinateDto> = emptyList(),
    val requestedPriorities: List<RoutePriorityDto> = emptyList(),
    val departureTime: String? = null,
    val alternatives: Int = 0,
)

/**
 * WGS84 座標の wire DTO。
 */
@Serializable
internal data class RouteCoordinateDto(
    val latitude: Double,
    val longitude: Double,
)

/**
 * 経路探索 API の response DTO。
 */
@Serializable
internal data class RoutePlanResponseDto(
    val candidates: List<RouteCandidateDto>,
    val diagnostics: List<RouteDiagnosticDto> = emptyList(),
)

/**
 * 候補経路 1 本分の wire DTO。
 */
@Serializable
internal data class RouteCandidateDto(
    val routePackageId: String,
    val priority: RoutePriorityDto = RoutePriorityDto.UNKNOWN,
    val mergedFromPriorities: List<RoutePriorityDto> = emptyList(),
    val geometry: List<RouteCoordinateDto>,
    val polyline: String,
    val summary: RouteSummaryDto,
    val tollFee: RouteTollFeeDto? = null,
    val tollDetails: List<RouteTollDetailDto>? = emptyList(),
    val speedLimitSegments: List<RouteSpeedLimitSegmentDto> = emptyList(),
    val congestionSegments: List<RouteCongestionSegmentDto> = emptyList(),
    val routeIncidents: List<RouteIncidentDto> = emptyList(),
    val guidancePoints: List<GuidancePointDto> = emptyList(),
    val maneuvers: List<RouteManeuverDto>,
    val diagnostics: List<RouteDiagnosticDto> = emptyList(),
)

/**
 * 候補経路の代表通行料金 DTO。
 */
@Serializable
internal data class RouteTollFeeDto(
    val currency: String,
    val amount: Double,
)

/**
 * 有料道路料金 1 件分の詳細 DTO。
 */
@Serializable
internal data class RouteTollDetailDto(
    val sectionIndex: Int,
    val tollIndex: Int,
    val fareIndex: Int,
    val countryCode: String? = null,
    val tollSystem: String? = null,
    val fareId: String? = null,
    val fareName: String? = null,
    val reason: String? = null,
    val paymentMethods: List<String> = emptyList(),
    val price: RouteTollPriceDto? = null,
    val convertedPrice: RouteTollPriceDto? = null,
)

/**
 * 有料道路料金の金額 DTO。
 */
@Serializable
internal data class RouteTollPriceDto(
    val type: String? = null,
    val currency: String? = null,
    val amount: Double? = null,
)

/**
 * route 上の制限速度区間 DTO。
 */
@Serializable
internal data class RouteSpeedLimitSegmentDto(
    val id: String,
    val startMeasureMetres: Double,
    val endMeasureMetres: Double,
    val speedLimitKmh: Int,
    val source: String,
)

/**
 * route 上の渋滞区間 DTO。
 */
@Serializable
internal data class RouteCongestionSegmentDto(
    val id: String,
    val startMeasureMetres: Double,
    val endMeasureMetres: Double,
    val level: RouteCongestionLevelDto = RouteCongestionLevelDto.UNKNOWN,
    val source: String,
    val trafficSpeedKmh: Int? = null,
    val baseSpeedKmh: Int? = null,
    val speedRatio: Double? = null,
    val displayText: String? = null,
)

/**
 * route 上の規制・事故 DTO。
 */
@Serializable
internal data class RouteIncidentDto(
    val id: String,
    val startMeasureMetres: Double,
    val endMeasureMetres: Double,
    val category: RouteIncidentCategoryDto = RouteIncidentCategoryDto.UNKNOWN,
    val kind: RouteIncidentKindDto = RouteIncidentKindDto.UNKNOWN,
    val displayText: String? = null,
    val roadNames: List<String> = emptyList(),
    val sectionName: String? = null,
    val source: String,
)

/**
 * 候補経路の集計情報 DTO。
 */
@Serializable
internal data class RouteSummaryDto(
    val durationSeconds: Int,
    val baseDurationSeconds: Int? = null,
    val typicalDurationSeconds: Int? = null,
    val lengthMetres: Int,
)

/**
 * route 上の maneuver DTO。
 */
@Serializable
internal data class RouteManeuverDto(
    val id: String,
    val type: RouteManeuverTypeDto = RouteManeuverTypeDto.UNKNOWN,
    val rawAction: String,
    val routeMeasureMetres: Double,
    val providerInstruction: String? = null,
    val debugInstruction: String? = null,
    val direction: String? = null,
    val severity: String? = null,
    val turnAngle: Double? = null,
)

/**
 * 経路探索 API の診断情報 DTO。
 */
@Serializable
internal data class RouteDiagnosticDto(
    val level: RouteDiagnosticLevelDto = RouteDiagnosticLevelDto.UNKNOWN,
    val message: String,
    val priority: RoutePriorityDto? = null,
)

/**
 * route 上の音声案内地点 DTO。
 */
@Serializable
internal data class GuidancePointDto(
    val index: Int,
    val distanceFromStartMetres: Int,
    val distanceFromPrevMetres: Int,
    val maneuverRefId: String? = null,
    val announcementBlocks: List<AnnouncementBlockDto>,
    val phrases: List<SsmlPhraseDto> = emptyList(),
)

/**
 * 1 発話単位の案内 block DTO。
 */
@Serializable
internal data class AnnouncementBlockDto(
    val id: String,
    val anchorMetres: Double,
    val triggerDistanceMetres: Int,
    val groupId: Int,
    val window: AnnouncementWindowDto?,
    val pieces: List<AnnouncementPieceDto>,
    val hasBlankAnnouncementSlot: Boolean,
    val categories: List<Int>,
)

/**
 * 発話候補が有効になる案内点までの残距離窓 DTO。
 */
@Serializable
internal data class AnnouncementWindowDto(
    val nearMetres: Int,
    val farMetres: Int,
)

/**
 * 案内 block を構成する意味片 DTO。
 */
@Serializable
internal data class AnnouncementPieceDto(
    val text: String,
    val ssml: String? = null,
    val templateRef: Int? = null,
    val category: Int? = null,
)

/**
 * 互換・debug 用の SSML phrase DTO。
 */
@Serializable
internal data class SsmlPhraseDto(
    val ssml: String,
    val distanceMetres: Int,
    val category: Int,
)

/**
 * wire 上の route priority。
 */
@Serializable(with = RoutePriorityDtoSerializer::class)
internal enum class RoutePriorityDto {
    /** 推奨ルート。 */
    RECOMMENDED,

    /** 渋滞回避近似ルート。 */
    AVOID_CONGESTION,

    /** 高速優先近似ルート。 */
    EXPRESS,

    /** 一般道優先近似ルート。 */
    FREE,

    /** 距離優先ルート。 */
    DISTANCE,

    /** アプリがまだ知らない route priority。 */
    UNKNOWN,
}

/**
 * wire 上の簡易 maneuver 種別。
 */
@Serializable(with = RouteManeuverTypeDtoSerializer::class)
internal enum class RouteManeuverTypeDto {
    /** 出発地点。 */
    DEPART,

    /** 到着地点。 */
    ARRIVE,

    /** 右左折。 */
    TURN,

    /** 分岐や車線維持。 */
    KEEP,

    /** 合流。 */
    MERGE,

    /** ラウンドアバウト。 */
    ROUNDABOUT,

    /** ランプまたは出口。 */
    EXIT,

    /** 未分類 maneuver。 */
    UNKNOWN,
}

/**
 * route 上の渋滞レベル。
 */
@Serializable(with = RouteCongestionLevelDtoSerializer::class)
internal enum class RouteCongestionLevelDto {
    /** 流れがスムーズな状態。 */
    SMOOTH,

    /** 混雑している状態。 */
    CROWDED,

    /** 渋滞している状態。 */
    JAM,

    /** 明示的な閉塞。 */
    BLOCKED,

    /** 不明または鮮度超過。 */
    UNKNOWN,
}

/**
 * route incident のカテゴリ。
 */
@Serializable(with = RouteIncidentCategoryDtoSerializer::class)
internal enum class RouteIncidentCategoryDto {
    /** 交通規制。 */
    REGULATION,

    /** 事故・故障車などの事象。 */
    INCIDENT,

    /** アプリがまだ知らない incident カテゴリ。 */
    UNKNOWN,
}

/**
 * route incident の種別。
 */
@Serializable(with = RouteIncidentKindDtoSerializer::class)
internal enum class RouteIncidentKindDto {
    /** 通行止・閉鎖・進入禁止。 */
    CLOSURE,

    /** 車線規制。 */
    LANE_RESTRICTION,

    /** 速度規制。 */
    SPEED_LIMIT,

    /** 片側交互通行。 */
    ALTERNATE_ONE_WAY,

    /** 片側通行。 */
    ONE_WAY,

    /** 右左折・直進禁止などの進行方向規制。 */
    TURN_RESTRICTION,

    /** 冬期閉鎖。 */
    WINTER_CLOSURE,

    /** チェーン規制。 */
    CHAIN_REQUIRED,

    /** 冬用タイヤ装着。 */
    WINTER_TIRE_REQUIRED,

    /** 事故・故障車など。 */
    INCIDENT,

    /** その他規制。 */
    OTHER_REGULATION,

    /** 不明な規制。 */
    UNKNOWN,
}

/**
 * wire 上の診断重要度。
 */
@Serializable(with = RouteDiagnosticLevelDtoSerializer::class)
internal enum class RouteDiagnosticLevelDto {
    /** 追跡用情報。 */
    INFO,

    /** 縮退や候補 drop を伴う警告。 */
    WARNING,

    /** アプリがまだ知らない診断重要度。 */
    UNKNOWN,
}

/**
 * 未知値を [unknownValue] に丸める enum serializer。
 */
internal abstract class UnknownSafeEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val values: Array<T>,
    private val unknownValue: T,
) : KSerializer<T> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = serialName,
        kind = PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: Decoder): T {
        val rawValue = decoder.decodeString()

        return values.firstOrNull { enumValue -> enumValue.name == rawValue } ?: unknownValue
    }

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(value.name)
    }
}

/**
 * [RoutePriorityDto] の未知値を [RoutePriorityDto.UNKNOWN] に丸める serializer。
 */
internal object RoutePriorityDtoSerializer : UnknownSafeEnumSerializer<RoutePriorityDto>(
    serialName = "RoutePriorityDto",
    values = RoutePriorityDto.entries.toTypedArray(),
    unknownValue = RoutePriorityDto.UNKNOWN,
)

/**
 * [RouteManeuverTypeDto] の未知値を [RouteManeuverTypeDto.UNKNOWN] に丸める serializer。
 */
internal object RouteManeuverTypeDtoSerializer : UnknownSafeEnumSerializer<RouteManeuverTypeDto>(
    serialName = "RouteManeuverTypeDto",
    values = RouteManeuverTypeDto.entries.toTypedArray(),
    unknownValue = RouteManeuverTypeDto.UNKNOWN,
)

/**
 * [RouteCongestionLevelDto] の未知値を [RouteCongestionLevelDto.UNKNOWN] に丸める serializer。
 */
internal object RouteCongestionLevelDtoSerializer : UnknownSafeEnumSerializer<RouteCongestionLevelDto>(
    serialName = "RouteCongestionLevelDto",
    values = RouteCongestionLevelDto.entries.toTypedArray(),
    unknownValue = RouteCongestionLevelDto.UNKNOWN,
)

/**
 * [RouteIncidentCategoryDto] の未知値を [RouteIncidentCategoryDto.UNKNOWN] に丸める serializer。
 */
internal object RouteIncidentCategoryDtoSerializer : UnknownSafeEnumSerializer<RouteIncidentCategoryDto>(
    serialName = "RouteIncidentCategoryDto",
    values = RouteIncidentCategoryDto.entries.toTypedArray(),
    unknownValue = RouteIncidentCategoryDto.UNKNOWN,
)

/**
 * [RouteIncidentKindDto] の未知値を [RouteIncidentKindDto.UNKNOWN] に丸める serializer。
 */
internal object RouteIncidentKindDtoSerializer : UnknownSafeEnumSerializer<RouteIncidentKindDto>(
    serialName = "RouteIncidentKindDto",
    values = RouteIncidentKindDto.entries.toTypedArray(),
    unknownValue = RouteIncidentKindDto.UNKNOWN,
)

/**
 * [RouteDiagnosticLevelDto] の未知値を [RouteDiagnosticLevelDto.UNKNOWN] に丸める serializer。
 */
internal object RouteDiagnosticLevelDtoSerializer : UnknownSafeEnumSerializer<RouteDiagnosticLevelDto>(
    serialName = "RouteDiagnosticLevelDto",
    values = RouteDiagnosticLevelDto.entries.toTypedArray(),
    unknownValue = RouteDiagnosticLevelDto.UNKNOWN,
)
