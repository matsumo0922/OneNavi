package me.matsumo.onenavi.core.navigation.newguidance.semantic

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * 案内ルート上の 1 イベント (位置非依存・不変)。
 *
 * sealed な二者択一 (Maneuver | Facility) をやめ、**主案内 ([primary]) + 補足
 * ([details])** に分ける。これにより JCT のように「曲がり + 施設 + 看板」を同時に
 * 表現できる。通過施設のように主案内が無い場合は [primary] が null。
 *
 * @property id イベントの一意 ID。
 * @property anchor ルート上の位置 (source / geometry 距離を分離保持)。
 * @property primary 主案内。アクションが無いイベント (通過施設等) では null。
 * @property details 補足情報 (facility / lane / toll / signpost / boundary / roadName / notices)。
 * @property sourceRefs 元データへの参照。
 */
@Immutable
data class GuidanceEvent(
    val id: GuidanceEventId,
    val anchor: RouteAnchor,
    val primary: GuidanceManeuver?,
    val details: GuidanceEventDetails,
    val sourceRefs: ImmutableList<SourceRef>,
)

/**
 * 案内イベントの一意 ID。
 *
 * @property value ID 文字列。
 */
@JvmInline
value class GuidanceEventId(val value: String)

/**
 * semantic イベントが由来する外部データへの参照。
 *
 * 後段でテキスト由来のレーン / 通知を拾い直すための紐付けに使う。
 *
 * @property guidancePointIndex 由来 GuidancePoint の index。無ければ null。
 * @property blockId 由来 GuideAnnouncementBlock の id。無ければ null。
 * @property pieceIndex block 内の案内片 index。無ければ null。
 */
@Immutable
data class SourceRef(
    val guidancePointIndex: Int?,
    val blockId: String?,
    val pieceIndex: Int?,
)
