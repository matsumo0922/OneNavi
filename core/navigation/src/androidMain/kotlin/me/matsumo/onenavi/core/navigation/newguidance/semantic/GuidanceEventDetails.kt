package me.matsumo.onenavi.core.navigation.newguidance.semantic

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * 案内イベントの補足情報 (主案内 [GuidanceManeuver] に付随する属性)。
 *
 * 構造が決まっている中核 (facility / lane / toll / signpost / boundary / roadName) は
 * typed nullable フィールドで持ち、種類が開いている裾 ([notices]) だけをリストで持つ
 * ハイブリッド構成。**facility を maneuver と排他にしない** ため、ここに置いて主案内と
 * 共存させる (例: JCT = 曲がり + 施設 + 看板)。
 *
 * @property facility IC / JCT / SA / PA / 料金所などの施設。無ければ null。
 * @property lane レーン情報。無ければ null。
 * @property toll この地点で発生する料金。無ければ null。
 * @property signpost 方面看板。無ければ null。
 * @property boundary 高速の入口 / 出口境界。無ければ null。
 * @property roadName 道路名。無ければ null。
 * @property notices 警告 / オービス / カーブ / 一時停止などの裾情報。
 */
@Immutable
data class GuidanceEventDetails(
    val facility: StepFacility?,
    val lane: GuidanceLane?,
    val toll: StepToll?,
    val signpost: StepSignpost?,
    val boundary: HighwayBoundary?,
    val roadName: StepRoadName?,
    val notices: ImmutableList<GuidanceNotice>,
)

/**
 * 施設 (IC / JCT / SA / PA / 料金所)。
 *
 * @property kind 施設種別。
 * @property name 施設名。
 * @property services SA / PA の提供サービス。無ければ空。
 */
@Immutable
data class StepFacility(
    val kind: FacilityKind,
    val name: String,
    val services: ImmutableList<StepFacilityService>,
)

/** 施設種別。 */
enum class FacilityKind { IC, JCT, SA, PA, TOLL_GATE }

/**
 * 施設で利用できる設備や混雑情報。
 *
 * @property kind 設備種別。
 * @property label ナビ画面に表示する短いラベル。
 */
@Immutable
data class StepFacilityService(
    val kind: FacilityServiceKind,
    val label: String,
)

/** 施設設備の表示種別。 */
enum class FacilityServiceKind {
    /** 通常車の駐車場状況。 */
    PARKING_STATUS,

    /** 大型車の駐車場状況。 */
    LARGE_CAR_PARKING_STATUS,

    /** ガソリンスタンド。 */
    GAS_STATION,

    /** スマート IC。 */
    SMART_IC,

    /** トイレ。 */
    TOILET,

    /** 多目的トイレ。 */
    ACCESSIBLE_TOILET,

    /** 道路情報端末。 */
    HIGHWAY_INFO_TERMINAL,

    /** 軽食。 */
    SNACK,

    /** ショッピング。 */
    SHOPPING,

    /** レストラン。 */
    RESTAURANT,

    /** コンビニ。 */
    CONVENIENCE_STORE,

    /** 授乳室。 */
    NURSING_ROOM,

    /** ベビーベッド。 */
    BABY_BED,

    /** 宿泊・休憩施設。 */
    LODGING,

    /** 入浴施設。 */
    BATH,

    /** インフォメーション。 */
    INFORMATION,

    /** ATM。 */
    ATM,

    /** シャワー。 */
    SHOWER,

    /** 洗車場。 */
    CAR_WASH,

    /** コインランドリー。 */
    LAUNDROMAT,

    /** ドラッグストア。 */
    DRUGSTORE,

    /** 郵便ポスト。 */
    POSTBOX,

    /** EV 充電設備。 */
    EV_CHARGER,

    /** その他。 */
    OTHER,
}

/**
 * 料金。整形済み文字列ではなく金額そのものを持つ。
 *
 * @property amountYen 料金 (円)。
 */
@Immutable
data class StepToll(val amountYen: Int)

/** 高速道路の境界。整形済み文字列ではなく enum で持つ。 */
enum class HighwayBoundary { ENTRANCE, EXIT }

/**
 * 方面看板。単一文字列に潰さず、副方面・看板画像も保持する。
 *
 * テキストなしで画像のみの看板 (交差点案内画像など) も表現できるよう、[primary] は nullable とする。
 * [primary] と [imageRef] の両方が null になることはない。
 *
 * @property primary 主方面テキスト (`direction_a`)。テキストが空の場合は null。
 * @property secondary 副方面テキスト (`direction_b`)。無ければ null。
 * @property imageRef 看板画像キー (`categories.major/minor` 由来)。無ければ null。
 */
@Immutable
data class StepSignpost(
    val primary: String?,
    val secondary: String?,
    val imageRef: GuideImageKey?,
)

/**
 * 案内画像キー。
 *
 * @property major 画像カテゴリの major 値。
 * @property minor 画像カテゴリの minor 値。
 */
@Immutable
data class GuideImageKey(
    val major: Int,
    val minor: Int,
)

/**
 * 道路名。
 *
 * @property text 道路名 (路線番号または名称)。
 */
@Immutable
data class StepRoadName(val text: String)

/**
 * イベントに付随する裾の通知 (警告系)。
 *
 * @property kind 通知種別。
 * @property text 表示用テキスト。無ければ null。
 */
@Immutable
data class GuidanceNotice(
    val kind: GuidanceNoticeKind,
    val text: String?,
)

/** 通知種別。発話 category から導出する。 */
enum class GuidanceNoticeKind {
    /** 事故多発地点。 */
    ACCIDENT_BLACK_SPOT,

    /** オービス。 */
    SPEED_CAMERA,

    /** カーブ注意。 */
    CURVE,

    /** 一時停止。 */
    STOP_LINE,

    /** 速度調整 / 制限。 */
    SPEED_ADJUSTMENT,

    /** 合流注意。 */
    MERGE_ATTENTION,

    /** その他 (未分類)。 */
    OTHER,
}
