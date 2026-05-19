# 28. `MapScreenState.Navigating` / `NewGuidanceManager` 実装設計 — Phase 1（進捗追跡 + UI 描画用データまで）

> **作成日:** 2026-05-18
> **ステータス:** ドラフト（実装着手前）
> **対象:** `MapScreenState.Navigating` と `NewGuidanceManager` を Phase 1 スコープで具体化するための設計仕様。spec/18 §Phase 1 のうち、走行進捗追跡と UI 描画用モデルの整備までを対象とする。音声案内（`ExtNavAnnouncementScheduler` / `ExtNavSsmlSpeaker`）・リルート検知（`ExtNavRerouteDetector`）・到達判定・カメラ自動制御は本ドキュメントの対象外（別 PR）

---

## 0. このドキュメントの位置付け

- spec/18 (`18_external_nav_api_migration_plan.md`) が外部ナビ API 移行の全体計画
- spec/24 (`24_new_guidance_manager_design.md`) が `NewGuidanceManager` の全体設計（v2）
- 本ドキュメント (spec/28) は **spec/24 §5「Guidance フェーズ (今後実装)」を Phase 1 範囲に絞って具体化** する位置付け。実装着手前の最終設計レビュー用

実装範囲を絞った理由:

- 一度に音声・リルート・到着まで入れると PR が肥大化しレビュー困難
- Phase 1 を「進捗追跡 + UI 骨格」までで切ると、実機で走行 → TBT が表示されるところまでが手動 QA できる
- 音声 / リルート / 到着は Phase 1 のデータ構造が固まれば後から積み増しできる設計にする

### 0.1 前提とする決定事項

| 項目 | 決定 |
|---|---|
| `GuidanceState` に進捗を載せる方式 | `Guiding` を `data class Guiding(progress: GuidanceProgress)` 化（State 1 本に集約） |
| 位置情報ソース | `FusedLocationProviderClient`（spec/18 Q-102 の検証結果待ちではなく、spec/24 で既に決定済み） |
| TBT UI の重ね方 | Compose の `Box` overlay（`AndroidView(NavigationView)` の上に `Box` を重ねる）。`NavigationView.setCustomControl` は使わない |
| 案内終了ボタンの遷移先 | `RoutePreview` に戻す（`Navigating` だけ pop し、`NewRouteManager` の `Ready` は保持） |
| 本 PR で新規作成する範囲 | 設計のみ。実装は本ドキュメントを基に別途行う |
| `New` prefix | 旧 `feature/home` 系と並走するための一時 prefix。整理 PR で外す（spec/24 §9.2） |

### 0.2 用語

- **GP (案内ポイント)**: 外部ナビ API ライブラリの案内テキスト発火点（`RouteGuidance.guidancePoints[]`）
- **進捗投影**: 生 GPS 位置を `RouteDetail.geometry` の最近接セグメントに射影し、単調増加な走行距離・残距離・次 GP を算出する処理
- **TBT (Turn-By-Turn)**: 交差点ごとの曲がる方向案内 UI

---

## 1. 外部ナビ API のデータソース利用方針

### 1.0 結論: Phase 1 はナビ中の追加通信なし

`ExtNavRouteDataSource.kt` を読むと、ルート探索（Preview）時点で外部ナビ API ライブラリの
`GuidanceClient.resolveGuidance()` と `RouteClient.search()` を並列に叩いて、各候補の
`RouteGuidance` を `ExtNavRouteRegistry` に既にキャッシュ済み。ナビ進捗計算に必要な
polyline / 交差点 / GP / phrase / summary / congestion はすべてここから取れる。

つまり Phase 1（進捗追跡 + UI データ整備）の範囲では、

- **ROUTE / GUIDE のどちらも追加で叩かない**
- Tracker は Registry から `ExtNavRoutePayload.routeGuidance` を読み、位置 tick のたびに
  オンメモリで進捗を再計算するだけ
- ネットワーク I/O は Preview 時点で完結している

リルート時（Phase 1 範囲外）に限り、`ExtNavRouteDataSource.searchRoutes(currentLocation,
destination, remainingViaPoints)` を再呼び出しして新しい `RouteGuidance` を取得する。

### 1.1 既存 Preview フロー（`ExtNavRouteDataSource.searchRoutes`）の確認

```
HomeMapViewModel / MapViewModel.onSearchRoutes
   │
   ▼
RouteRepository.searchRoutes
   │
   ▼
ExtNavRouteDataSource.searchRoutes (origin, destination, intermediates)
   ├─ ExtNavAuthGateway.ensureSignedIn()
   ├─ RouteSearchCriteria(
   │      start, goal, waypoints,
   │      priorities = { Recommended, AvoidCongestion, Express, Free },
   │  )
   ├─ coroutineScope { 並列:
   │      ① guidanceDeferred = client.guidance.resolveGuidance(criteria)
   │              → Guidance.routes: ImmutableList<RouteGuidance>
   │                  (1 priority に対し 1 ルート、全 priority 一度に返る)
   │      ② priorities ごとに:
   │              client.route.search(criteria.copy(priorities = { p }))
   │                  → Route 候補（fareSegments に料金区間あり）
   │                  → InterchangeNameHint(entryName, exitName) に射影
   │     }
   │     hints = (priority → InterchangeNameHint) のマップ
   │
   ├─ guidance.routes.map { routeGuidance ->
   │       routeId = routeGuidance.priority?.name ?: "route-${index}"
   │       geometry = buildGeometry(routeGuidance)   // polyline 優先、intersection フォールバック
   │       roadClassSegments = buildRoadClassSegments(routeGuidance, geometry, hint)
   │       congestionSegments = buildCongestionSegments(...)
   │       tollDetails = summary.tollDetails.map { ... }
   │
   │       registry.put(ExtNavRoutePayload(id = routeId, routeGuidance = routeGuidance))
   │
   │       RouteResult(item = RouteItem(...), detail = RouteDetail(id = routeId, ...))
   │  }
   │
   ▼
NewRouteManager.state = Ready(routes = [RouteDetail × N])
```

`Registry` に詰まる payload と `RouteDetail.id` は **同じ `routeId` 文字列でキー一致** している。
`NewGuidanceManager.startGuidance(route: RouteDetail)` から `registry.get(route.id)` で確実に
引ける。

### 1.2 ROUTE と GUIDE の役割分担（実装の事実）

| エンドポイント | 何のために叩いているか | Phase 1 ナビ中で使う？ |
|---|---|---|
| `GuidanceClient.resolveGuidance(criteria)` | 全候補の polyline / GP / intersection / phrase / summary / congestion をまとめて 1 リクエストで取得。**Phase 1 の主データ源** | 既に Registry にキャッシュ済み。**追加で叩かない** |
| `RouteClient.search(criteria)` × priorities | 料金区間の入口 / 出口 IC 名 (`InterchangeNameHint`) を取るためだけに、priority ごとに個別に叩いている。GP / polyline は使っていない | **追加で叩かない**（Preview 用） |

> spec/18 §4.4 は「案内 API は 1 候補しか返さない」前提で priority 切替 3 並列を Phase 2 に
> 回していたが、実装では既に `resolveGuidance` に priorities 集合を渡して `Guidance.routes` で
> 複数候補が返る形に解決済み。本ドキュメントはその事実を正とする。

### 1.3 `RouteGuidance` から取り出す Phase 1 必須フィールド

`ExtNavRoutePayload.routeGuidance: RouteGuidance` から `ExtNavGuidanceMapper` 経由で取り出す:

| フィールド | 用途 | OneNavi 側モデル |
|---|---|---|
| `routeGuidance.polyline: List<Coord>` | 自車投影の基盤（既に `RouteDetail.geometry` として詰め直し済み） | `RouteDetail.geometry` |
| `routeGuidance.intersections[]` | 交差点名 / 高速サイン / 道路名（次マニューバの `intersectionName` 抽出） | `GuidanceManeuverInfo.intersectionName` |
| `routeGuidance.guidancePoints[]` | **TBT バナーの主データ源**。`distanceFromStartMetres` でルート始点からの距離を持つ | `GuidanceManeuverInfo`, `LaneGuidance`, `DirectionSign` |
| `routeGuidance.guidancePoints[].phrases[]` | 音声フレーズ（Phase 2 で使用） | `GuidancePhrase`（Phase 2） |
| `routeGuidance.guidancePoints[].category` | `GuidanceCategory`（マニューバ種別の射影元） | `ManeuverType` |
| `routeGuidance.summary.distanceMetres` | ルート全長（残距離の母数） | `RouteDetail.distanceMeters` |
| `routeGuidance.summary.timeSeconds` | ルート全所要時間（残時間の母数） | `RouteDetail.durationSeconds` |
| `routeGuidance.summary.streets[]` | 現在走行中の道路名 / 高速判定（StreetSegment 単位） | `GuidanceProgress.currentRoadName`, `currentRoadClass` |
| `routeGuidance.congestionSegments[]` | Phase 1 では `RouteDetail.congestionSegments` 経由で polyline 色分け済み。Tracker は触らない | （既存）`CongestionSegment` |

### 1.4 ナビ中の渋滞情報・規制情報の扱い（Phase 1 はスナップショット固定）

#### 1.4.1 結論

- **渋滞情報**: `RouteGuidance.congestionSegments` として **ルート探索時に 1 度だけ取得**済み。
  Phase 1 ではこれを `RouteDetail.congestionSegments` 経由で polyline 色分けに使うだけ。
  **ナビ中に再取得・更新はしない**
- **規制情報（事故・通行止め等）**: Phase 1 では **取得も表示もしない**。spec/18 §1.1 表中の
  `TrafficClient` 群（`listByPathCodes` 等）は Phase 2 で `ExtNavTrafficOverlay` として実装予定
- **動的更新**: Phase 1 では一切なし。経路が古い渋滞情報を引きずる可能性は許容範囲

#### 1.4.2 `RouteGuidance.congestionSegments` の中身（取得済みデータ）

`ExtNavRouteDataSource.buildCongestionSegments(...)` で `CongestionSegment` 中立モデルに射影済み:

| フィールド | 内容 |
|---|---|
| `startPolylinePointIndex / endPolylinePointIndex` | polyline 上の区間 |
| `severity` | `NORMAL / SLOW / TRAFFIC_JAM / UNKNOWN` |
| `startDistanceMeters / endDistanceMeters` | ルート始点からの距離 |
| `congestionDistanceMeters` | 渋滞区間長 |
| `transitMinutes` | 通過予想時間 |
| `trend` | `STABLE / INCREASING / DECREASING / INTERMITTENT / UNKNOWN` |
| `headPointName / tailPointName / 各 kana / 各 roadNumbering` | 渋滞先頭・末尾の地点名 |
| `source` | `GUIDANCE_POINT / ROUTE_LINK`（外部ナビ API 側の算出元） |

Phase 1 ではこれを `GuidanceProgress` に **逐次再計算では入れない**。理由:

- `RouteDetail.congestionSegments` として既に画面全体に行き渡っており、polyline 色分けは Preview /
  Navigating 共通で機能する
- 「現在通過中の渋滞」「次の渋滞までの距離」を TBT バナーに出すかどうかは UI デザイン判断の話で、
  Phase 1 のデータモデル整備としては余剰

将来 TBT バナーに「この先 3km 渋滞」のような表示を出す場合は、`GuidanceProgress` に
`upcomingCongestion: CongestionSegment?` を追加し、Tracker 内で現在位置 (`currentCumulativeMeters`)
より先の最初の congestion を抽出する形で拡張する（Phase 2 想定）。

#### 1.4.3 ナビ中の動的更新（Phase 2 計画）

spec/18 §3.2 の `ExtNavTrafficOverlay` を実装する場合の想定:

```
ExtNavTrafficOverlay
  ├─ ルート確定時に対象 pathCode 群を抽出
  ├─ 定期ポーリング（例: 5 分間隔）で TrafficClient.listByPathCodes(pathCodes)
  ├─ 差分を CongestionSegment に射影し、RouteDetail.congestionSegments を replace
  └─ 規制情報（事故・通行止め）は RegulationOverlay として別 StateFlow で公開
```

Phase 1 ではこれは **作らない**。Tracker / `NewGuidanceManager` / UI モデルが Phase 2 で
`ExtNavTrafficOverlay` を `collect` できる形にだけ準備しておけば良い（つまり `GuidanceProgress` に
無理に渋滞フィールドを足さない方向で粒度を切る）。

#### 1.4.4 関連する OneNavi 側既存設計

- spec/26 (`26_route_congestion_design.md`) — 渋滞モデルの設計
- spec/27 (`27_route_congestion_visualization_design.md`) — polyline 色分けの可視化設計

これらは Preview 時点で機能していて Navigating でも流用できる。本ドキュメントはここに依存しない。

---

### 1.5 GP の `distanceFromStartMetres` の取り扱い

外部ライブラリ側の `guidancePoints[i].distanceFromStartMetres` は **`routeGuidance.summary.distanceMetres`
基準**（つまり外部 API が算出したルート全長基準）。一方、OneNavi 側で polyline 描画と
進捗計算に使う `RouteDetail.geometry` は **haversine で逐次計算した累積距離**を別に持つ。

両者は完全一致しないため、Tracker は attach 時に以下のキャリブレーションを行う:

```
scaleFactor = (geometry の haversine 全長) / summary.distanceMetres
gpCumulativeOnGeometry[i] = guidancePoints[i].distanceFromStartMetres × scaleFactor
```

その上で `gpCumulativeOnGeometry[]` と geometry の累積距離テーブルで二分探索すれば、tick 時に
O(log n) で次 GP を引ける。GP の `distanceFromStartMetres` が既に存在するため、`GuidancePoint.position`
を polyline に snap する処理は **不要**（snap が必要になるのは intersection 連結フォールバックの
ケースのみ。dense polyline が存在する通常パスでは GP の距離指標をそのまま使う）。

---

## 2. データ型の設計

### 2.1 `GuidanceProgress`（新規） — UI が読む案内中のスナップショット

`feature/map` から購読する単一のスナップショット。Compose の安定性のため `@Immutable` + `ImmutableList` を徹底。

```kotlin
/**
 * 案内中の進捗スナップショット。
 *
 * `NewGuidanceManager` が `GuidanceState.Guiding(progress)` の形で公開する。
 * `ExtNavGuidanceTracker` の `state` を `ExtNavGuidanceMapper` で変換した結果。
 */
@Immutable
data class GuidanceProgress(
    val distanceRemainingMeters: Int,
    val durationRemainingSeconds: Int,
    val etaEpochMillis: Long,
    val traveledMeters: Int,

    val snappedLocation: RoutePoint,
    val bearingDegrees: Float,

    // 最後の GP を通過後 / GP 0 件のルート / 初回 tick 前など、案内対象が存在しない時に null。
    // UI 側は null の場合「直進」または到着間際のテキストを暫定表示する（spec/28 §OQ1）。
    val nextManeuver: GuidanceManeuverInfo?,
    val followupManeuver: GuidanceManeuverInfo?,

    val lanes: ImmutableList<LaneGuidance>,
    val directionSign: DirectionSign?,
    val highwayPanel: HighwayPanel?,

    val currentRoadName: String?,
    val currentRoadClass: RoadClass,
    val currentSpeedLimitKmh: Int?,
)
```

| フィールド | 用途 |
|---|---|
| `distanceRemainingMeters` | ETA カードの「残距離」表示 |
| `durationRemainingSeconds` | ETA カードの「残時間」表示 |
| `etaEpochMillis` | ETA カードの到着予想時刻表示。`durationRemainingSeconds` + tick 時刻から算出 |
| `traveledMeters` | デバッグ・統計用。単調増加（リルートで 0 リセット） |
| `snappedLocation` | 自車マーカーの座標。生 GPS ではなく polyline 投影後の点 |
| `bearingDegrees` | カメラ追従の bearing。生 GPS の bearing が信頼できないケースで投影セグメントの方位を使う |
| `nextManeuver` | TBT バナーの主役 |
| `followupManeuver` | 「その後 右折」用。Phase 1 では nullable で空でも OK |
| `lanes` | レーンガイダンス UI（実装は Phase 1 範囲外だがデータは詰める） |
| `directionSign` | 方面看板表示 |
| `highwayPanel` | IC/JCT/SA/PA パネル |
| `currentRoadName` | 走行中の道路名表示 |
| `currentRoadClass` | 表示色・パネル切り替えの判定用 |
| `currentSpeedLimitKmh` | 速度計サブ表示（Phase 3 範囲だがデータは詰める） |

### 2.2 `GuidanceManeuverInfo`（新規）

```kotlin
@Immutable
data class GuidanceManeuverInfo(
    val type: ManeuverType,
    val modifier: ManeuverModifier,
    val distanceToManeuverMeters: Int,
    val intersectionName: String?,
    val exitNumber: String?,
    val guidancePointIndex: Int,
)
```

- `ManeuverType` / `ManeuverModifier` は `core/model` に既存（Mapbox 時代の遺物）。不足分のみ追記し再利用する
- `guidancePointIndex` は Phase 2 の発話既出マーク（`spoken.add((gpIndex, category, distance))`）のキーに使う。Phase 1 では未使用だがフィールドだけ用意

### 2.3 `LaneGuidance` / `DirectionSign` / `HighwayPanel`（新規・最小）

```kotlin
@Immutable
data class LaneGuidance(
    val lanes: ImmutableList<Lane>,
)

@Immutable
data class Lane(
    val allowedDirections: ImmutableList<ManeuverModifier>,
    val recommendedDirection: ManeuverModifier?,
    val isActive: Boolean,
)

@Immutable
data class DirectionSign(
    val primary: String,
    val secondary: String?,
    val imageKey: String?,
)

@Immutable
data class HighwayPanel(
    val kind: HighwayFacility,
    val name: String,
    val distanceMeters: Int,
    val services: ImmutableList<String>,
)

/** 高速道路施設種別 */
enum class HighwayFacility { IC, JCT, SA, PA }
```

- `DirectionSign.imageKey` は Phase 2 で `GuideImageClient.preload` の Coil キャッシュキーを保持する（Phase 1 では常に null）
- `HighwayPanel.services` は Phase 2 で SA/PA のサービス情報を入れる

### 2.4 `GuidanceState.Guiding` を data class 化

```kotlin
@Immutable
sealed interface GuidanceState {
    data object Idle : GuidanceState
    data class Guiding(val progress: GuidanceProgress) : GuidanceState
    data object Rerouting : GuidanceState
    data object Arrived : GuidanceState
    data class Failed(val message: String) : GuidanceState
}
```

- Phase 1 開始直後（初回位置取得前）は `Guiding(progress=null)` ではなく、`Idle` のまま保持し、最初の位置 tick で `Guiding(progress)` に遷移させる
- `Rerouting` / `Arrived` への遷移は Phase 1 範囲外。型としては残す

### 2.5 `ExtNavRoutePayload`（既存）

`core/navigation/src/androidMain/.../extnav/ExtNavRouteDataSource.kt` の **末尾 (L1028-1032)
に既に定義済み**。再掲のみ:

```kotlin
@Immutable
data class ExtNavRoutePayload(
    val id: String,
    val routeGuidance: RouteGuidance,
)
```

`RouteGuidance` は `me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance`。
**OneNavi 側コードからは `ExtNavGuidanceMapper` を経由する場合のみ参照**し、他クラスは
直接ライブラリ型を持ち出さない（命名ポリシー + 抽象化）。

---

## 3. 新規クラスの責務

### 3.1 ファイル配置

```
core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/
└── location/
    └── CurrentLocationDataSource.kt        # 新規

core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/
├── extnav/
│   ├── ExtNavGuidanceTracker.kt            # 新規
│   ├── ExtNavGuidanceMapper.kt             # 新規（internal）
│   ├── ExtNavRoutePayload.kt               # 確認 or 新規
│   └── (既存) ExtNavRouteRegistry.kt, ExtNavRouteDataSource.kt 等
└── newguidance/
    ├── NewGuidanceManager.kt               # 改修
    └── model/
        ├── GuidanceState.kt                # Guiding を data class 化
        ├── GuidanceProgress.kt             # 新規
        ├── GuidanceManeuverInfo.kt         # 新規
        ├── LaneGuidance.kt                 # 新規
        ├── DirectionSign.kt                # 新規
        └── HighwayPanel.kt                 # 新規

core/model/src/androidMain/kotlin/me/matsumo/onenavi/core/model/
└── (既存) ManeuverType / ManeuverModifier / RoadClass / RoutePoint  ← 再利用
```

`core/model` の旧型（Mapbox 時代の `GuidanceEvent` / `DistanceBucket` 等）は本 PR では削除しない（spec/18 §3.1 で「ほぼ全削除」対象だが、Phase 1 のスコープ外）。

### 3.2 `CurrentLocationDataSource`（新規）

```kotlin
class CurrentLocationDataSource(
    private val context: Context,
) {
    /**
     * 位置情報の連続購読。callbackFlow で FusedLocationProviderClient をラップ。
     *
     * 呼び出し側は ACCESS_FINE_LOCATION 権限取得後に collect すること。
     */
    fun locationUpdates(
        intervalMillis: Long = 1_000L,
        minDistanceMeters: Float = 0f,
    ): Flow<UserLocation>

    /**
     * 最後に取得済みの位置（即時表示用）。
     *
     * `NewGuidanceManager.startGuidance` の **冒頭で呼ぶ** 想定（§3.5.4 / §OQ2）。
     * 取得できた場合は即座に 1 tick 分の進捗算出に使い、駄目なら `ExtNavGuidanceBootstrap`
     * 経由で origin ベースの初期 progress を出す。
     */
    suspend fun lastKnown(): UserLocation?
}

@Immutable
data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Float?,
    val speedMps: Float?,
    val accuracyMeters: Float,
    val timestampMillis: Long,
)
```

- 権限チェックは呼び出し側責務（Compose Permission API or `calf` を使う）
- 位置取得失敗・権限拒否は `Flow` 自体を complete させず、`flow { }` の外側で握って `GuidanceState.Failed` に変換する
- ForegroundService の起動は **Phase 1 範囲外**。アプリがフォアグラウンドの間だけ動く前提

### 3.3 `ExtNavGuidanceTracker`（新規・コア）

```kotlin
class ExtNavGuidanceTracker {
    /** 現在の進捗。null = 未 attach */
    val state: StateFlow<GuidanceProgress?>

    /**
     * 案内対象のルート + ガイダンスデータを attach する。
     *
     * attach 時に GP 累積距離テーブル（geometry index → cumulative meters）を
     * 事前計算しておき、tick 時の次 GP 検索を O(log n) に抑える。
     */
    fun attach(payload: ExtNavRoutePayload, route: RouteDetail)

    /**
     * 位置 1 点を投入して進捗を再計算する。state が更新される。
     */
    fun onLocation(location: UserLocation)

    /** 累積状態をクリアし state を null に戻す */
    fun detach()
}
```

#### 3.3.1 内部処理の要点（実装者向けメモ）

1. **polyline 投影**
   - `route.geometry: ImmutableList<RoutePoint>` に対する最近接セグメント検索
   - 単調増加を維持するため、前 tick の投影 index より手前は探索しない（最大 N セグメント先読み）
   - 投影精度: WGS84 を平面近似（短距離なので緯度経度の sin 補正だけで OK）

2. **GP 累積距離のプリ計算**（§1.5 に詳述）
   - `guidancePoints[i].distanceFromStartMetres × (geometryTotalMeters / summary.distanceMetres)`
     で `gpCumulativeOnGeometry[i]` を事前計算
   - tick 時は `gpCumulativeOnGeometry` を二分探索して次 GP を O(log n) で引く

3. **`nextManeuver` 抽出**
   - 現在位置の累積距離 < GP の累積距離、を満たす最初の GP を `nextManeuver`
   - `distanceToManeuverMeters` = `gpCumulativeOnGeometry[i] - currentCumulativeMeters`
   - **該当 GP が無い場合（最終 GP を通過後 / `guidancePoints` が空のルート）は `nextManeuver = null`**
     を `GuidanceProgress` に詰める（§OQ1）。Tracker はこの状態でも `state` を更新し続ける
     （`distanceRemainingMeters` / `snappedLocation` 等は通常通り）

4. **残時間の補正**
   - Phase 1 は `route.durationSeconds × (1 - traveled / total)` の単純比例
   - Phase 2 で `TrafficClient` 由来の混雑情報で補正

5. **ヒステリシス**
   - GPS jitter で投影点が前後しないよう、後退方向への投影更新は閾値（5m）以下なら無視

### 3.4 `ExtNavGuidanceMapper`（新規・`internal`）

外部ナビ API ライブラリの型 → OneNavi モデル の変換は **必ずここに集約**。他クラスはライブラリ型を持ち出さない。

#### 3.4.1 入力構造の前提（実物の `RouteGuidance` を読んでわかったこと）

`ExtNavRouteDataSource.kt` で参照されている既存ライブラリ型の事実を整理:

| ライブラリ型 / フィールド | 確認済みの内容 |
|---|---|
| `RouteGuidance.guidancePoints: List<GuidancePoint>` | 案内発火点の配列 |
| `GuidancePoint.distanceFromStartMetres` | ルート始点からの距離（`summary.distanceMetres` 基準）。事前計算で投影可能 |
| `GuidancePoint.phrases: List<Phrase>` | この GP で発話するフレーズの配列 |
| `GuidancePoint.phrases[].category: GuidanceCategory` | 例: `AutoExpresswayEntry` `IntersectionGuide` 等。**maneuver 種別の決定は phrase レベル**。GP 自体に category は無いか、複数 phrase で複数 category を持つ |
| `RouteGuidance.intersections: List<Intersection>` | 交差点配列。`position`, `name`, `roadNameOfficial`, `roadName`, `roadNumberSign` 等を持つ |
| `Intersection.position: Coord` | 緯度経度 |
| `Intersection.roadNumberSign` | 路線記号（高速判定に使われる） |
| `RouteGuidance.polyline: List<Coord>` | dense polyline |

**重要**: `GuidancePoint.position` を直接持つかは未確認。前ドラフトで「GP の position を polyline に snap」「`category` を GP 1 個から決める」と書いたが、これは事実とズレている可能性が高い。Mapper は以下のとおり phrase ベースで設計し直す。

#### 3.4.2 GP と Intersection の対応付け

GP 自体は「テキスト発火点」であり、「どの交差点での案内なのか」「方面看板の文言は何か」は
`intersections[]` 側に持っている情報が大半。Mapper では GP と intersection を **インデックス
ベース ではなく 累積距離ベース** で対応付ける:

```
gpCumulativeOnGeometry[i] と各 intersection.position の geometry 累積距離を比較し、
GP に最も近い intersection を採用する（許容半径: 300m。spec/18 §1.4 の Junction snap と同等）
```

採用された intersection から `intersectionName` / `roadNumberSign` / `roadNameOfficial` を取り、
GP からは `phrases[]` の category / 発話タイミングを取る。

> 結果として Mapper の入力は「GP 単体」ではなく「GP + 対応 Intersection + GP の残距離 + 区分け済み phrase」となる。

#### 3.4.3 ManeuverType / ManeuverModifier の決定ロジック

phrase の category と intersection の情報を組み合わせて決める:

| 出力 `ManeuverType` | 判定条件 |
|---|---|
| `Merge` | phrase.category が `Merge` / `MergeAttention` / `HighwayLaneReduction` を含む |
| `Fork` | phrase.category が `AutoExpresswayEntry` / 分岐系を含む、または intersection が JCT 系 |
| `RoundaboutExit` | phrase.category がラウンドアバウト系 |
| `Arrive` | 最終 GP（`isLastGP == true`） |
| `Turn` | 上記以外で intersection.position と次 polyline セグメントの方位差が一定以上 |
| `Continue` | 方位差が小さい・かつ category が `IntersectionGuide` 系でない |

`ManeuverModifier`（左右の細分化）は、`Intersection.position` 前後の polyline 進行方向の角度差から
算出（±5° = Straight, ±5〜±60° = Slight, ±60〜±150° = Left/Right, ±150° 超 = UTurn）。
GP 単体の `approachAngle` というフィールド前提は撤回。

#### 3.4.4 DirectionSign の組み立て

方面看板は **GP だけでは作れない**。`Intersection.roadNameOfficial` / `Intersection.roadName` /
`Intersection.roadNumberSign` を組み合わせ、Phase 2 で `GuideImageClient.preload` を導入したら
`imageKey` に対応する画像参照を渡す。Phase 1 は文字列の primary / secondary のみ（image は null）。

```
primary = intersection.roadNameOfficial ?: intersection.roadName
secondary = intersection.roadNumberSign.takeIf { it.isNotBlank() }
imageKey = null   // Phase 1
```

primary / secondary の両方が空なら DirectionSign は出さない（null を返す）。

#### 3.4.5 HighwayPanel の組み立て

`Intersection.roadNumberSign.isNotBlank()` を「高速サイン保有交差点」とみなし、`Intersection.name` から
IC/JCT/SA/PA の種別を文字列パターンで判定（`"JCT"` `"SA"` `"PA"` を含むか、それ以外は IC）。
これは `ExtNavRouteDataSource.kt` の `hasHighwaySign` 判定と同じロジック。

#### 3.4.6 API シグネチャ（修正後）

```kotlin
// import me.matsumo.drive.supporter.api.guidance.domain.{ RouteGuidance,
//     GuidancePoint, Intersection, Phrase, GuidanceCategory, ... }

internal object ExtNavGuidanceMapper {
    /**
     * GP 1 件分のスナップショットを ManeuverInfo に変換する。
     * 呼び出し側で GP と最近接 intersection の対応付けを済ませてから渡す。
     */
    fun toManeuverInfo(
        guidancePoint: GuidancePoint,
        guidancePointIndex: Int,
        nearestIntersection: Intersection?,
        bearingDiffDegrees: Float,
        distanceToManeuverMeters: Int,
        isLastGuidancePoint: Boolean,
    ): GuidanceManeuverInfo

    fun toLaneGuidance(
        guidancePoint: GuidancePoint,
    ): LaneGuidance?

    fun toDirectionSign(
        nearestIntersection: Intersection?,
    ): DirectionSign?

    fun toHighwayPanel(
        nearestIntersection: Intersection,
        distanceMeters: Int,
    ): HighwayPanel?

    fun toManeuverType(
        phrases: List<Phrase>,
        bearingDiffDegrees: Float,
        isLastGuidancePoint: Boolean,
    ): ManeuverType

    fun toManeuverModifier(bearingDiffDegrees: Float): ManeuverModifier
}
```

ライブラリの実型名（`RouteGuidance` / `GuidancePoint` / `Intersection` / `GuidanceCategory` / `Phrase`）は
既に `ExtNavRouteDataSource.kt` で参照されているため、本ドキュメントでも同じ表記を使う。
事業者名・製品名・パッケージ root のドメイン名は引き続き露出させない。

> **未確認の前提**: `GuidancePoint.phrases[].category` の具体的な列挙値・`Phrase` の正確な型・
> `lane` / `directionSigns` 等のフィールドの存在は drive-supporter-api 側の `GuidancePoint` 定義を
> 読まないと確定できない。Mapper 実装に着手する前に該当ファイルを精読し、本節を確定版に更新する
> こと（§9 確認事項 C / G）。

### 3.5 `NewGuidanceManager`（改修）

#### 3.5.1 Koin 登録の方針（singleton 維持）

`NewGuidanceManager` は **`single { }` のまま維持** する（spec/24 §2.2 の既存方針）。
`MapViewModel` から `inject` され、`onCleared` で `release()` を呼ぶ前提だが、後述の通り
`release()` は internal scope を破壊せず session 停止だけにする。再生成は OneNavi プロセス
ライフサイクル単位で行わない（`MapViewModel` が作り直されても singleton インスタンスは継続）。

#### 3.5.2 並行制御の方針

- 「位置購読 collect」と「tracker.state collect」を同じ親 Job 配下に置くと、片方の例外で
  もう片方が落ちる。`SupervisorJob` を内包しただけでは **子 launch 同士の supervisor 効果は
  得られない**（`SupervisorJob` は scope 直下の子に対してのみ supervisor として働き、`launch { launch {} }`
  のネスト時は親 launch のキャンセル方針が支配する）
- 正しくは以下のいずれか:
  - **(a) `supervisorScope` で囲む**
  - **(b) 2 本の `launch` をそれぞれ `scope.launch { try { ... } catch { Failed } }` で個別に保護**
  - **(c) 親に `Job()` ではなく `SupervisorJob()` を持ち、子 launch を `scope.launch { }` のフラットな並列にし、入れ子にしない**

本設計では **(c) + 個別 catch** を採用する。session のジョブを集約する `Job` を session ごとに
生成し、停止時にその Job をキャンセルすれば 2 本まとめて停止できる。

#### 3.5.3 ライフサイクル方針

- `release()` は **scope を `cancel()` しない**。`sessionJob` を canecl し、`tracker.detach()`
  を呼び、`_state = Idle` に戻すだけ（= `stopGuidance` と同じ）
- これにより singleton として複数回 `start → release → start` を繰り返せる
- scope 自体の破棄は OneNavi プロセス終了時のみ（Koin が application scope で握る）

#### 3.5.4 初回 tick 前の挙動

開始ボタンを押した直後、初回 GPS tick が来るまでは `_state = Idle` のままだと UI が
「案内中なのに何も出てない」状態になる。対策として `startGuidance` 内で:

1. `locationDataSource.lastKnown()` を即座に呼び、取れた位置で 1 回 `tracker.onLocation` を回す
2. それでも null（権限取得直後で fix なし）の場合は `GuidanceState.Guiding(progress = bootstrapProgress)` を
   公開する — bootstrap progress は `summary.distanceMetres` 全長を残距離として詰めた初期スナップ
   ショット（`snappedLocation = route.origin`, `nextManeuver = 最初の GP からの ManeuverInfo`）

これで停止ボタンを表示しつつ「位置取得中」表示も別途出せる（後者は UI 側で `progress.bearingDegrees == 0f &&
snappedLocation == route.origin` を Loading 扱いするなど別途判断）。

#### 3.5.5 修正後コード例

```kotlin
class NewGuidanceManager(
    private val registry: ExtNavRouteRegistry,
    private val tracker: ExtNavGuidanceTracker,
    private val locationDataSource: CurrentLocationDataSource,
    private val guidanceBootstrap: ExtNavGuidanceBootstrap,
) {
    // singleton 全体で 1 つ。再利用するのでここで cancel しない。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    suspend fun startGuidance(route: RouteDetail) {
        val payload = registry.get(route.id)
        if (payload == null) {
            _state.value = GuidanceState.Failed("route payload not found: ${route.id}")
            return
        }
        tracker.attach(payload, route)

        // 初回 tick 前: lastKnown から bootstrap、駄目なら origin ベース bootstrap
        val initialLocation = locationDataSource.lastKnown()
        if (initialLocation != null) {
            tracker.onLocation(initialLocation)
        } else {
            _state.value = GuidanceState.Guiding(
                progress = guidanceBootstrap.fromOrigin(payload, route),
            )
        }

        // session 用 Job を新規に作って scope の直下子としてフラットに 2 本走らせる。
        // SupervisorJob 直下なら片方の失敗で他方が巻き込まれない。
        val session = SupervisorJob(parent = scope.coroutineContext.job)
        sessionJob = session

        scope.launch(session) {
            try {
                locationDataSource.locationUpdates().collect { location ->
                    tracker.onLocation(location)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = GuidanceState.Failed("location: ${error.message ?: "unknown"}")
            }
        }

        scope.launch(session) {
            try {
                tracker.state.collect { progress ->
                    if (progress != null) {
                        _state.value = GuidanceState.Guiding(progress)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = GuidanceState.Failed("tracker: ${error.message ?: "unknown"}")
            }
        }
    }

    fun stopGuidance() {
        sessionJob?.cancel()
        sessionJob = null
        tracker.detach()
        _state.value = GuidanceState.Idle
    }

    /**
     * MapViewModel.onCleared から呼ぶエントリ。session を止めるだけで scope は破棄しない。
     * （NewGuidanceManager は Koin single 登録のため、再利用される前提）
     */
    fun release() {
        stopGuidance()
    }
}
```

- 到着判定 / リルート判定 / 音声 は Phase 1 範囲外
- `Arrived` / `Rerouting` 遷移は別 PR で `tracker.state` の派生として実装
- `ExtNavGuidanceBootstrap` は §3.6 で定義する小さなヘルパ

### 3.6 `ExtNavGuidanceBootstrap`（新規・`internal`）

初回 GPS tick 前にも UI を出すための「ルート始点ベース progress」を作る小さなヘルパ。

```kotlin
internal class ExtNavGuidanceBootstrap {
    fun fromOrigin(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
    ): GuidanceProgress = GuidanceProgress(
        distanceRemainingMeters = route.distanceMeters.toInt(),
        durationRemainingSeconds = route.durationSeconds.toInt(),
        etaEpochMillis = System.currentTimeMillis() + route.durationSeconds.toLong() * 1000L,
        traveledMeters = 0,
        snappedLocation = route.origin,
        bearingDegrees = initialBearingFromGeometry(route.geometry),
        nextManeuver = firstManeuverOrNull(payload, route),
        followupManeuver = null,
        lanes = persistentListOf(),
        directionSign = null,
        highwayPanel = null,
        currentRoadName = null,
        currentRoadClass = route.roadClassSegments.firstOrNull()?.roadClass ?: RoadClass.ORDINARY,
        currentSpeedLimitKmh = null,
    )
}
```

`nextManeuver` は `GuidancePoint` が 1 件以上あれば最初の GP から組み立てる。**ゼロ件のときは
`null`**（§Open Questions の OQ1 に対応するため `GuidanceProgress.nextManeuver` を nullable 化する）。

### 4.1 `MapViewModel.handleNavigationStart` / `handleNavigationStop`

```kotlin
private fun handleNavigationStart() {
    val previewState = newRouteManager.state.value as? RoutePreviewState.Ready ?: return
    pushScreenState(MapScreenState.Navigating)
    viewModelScope.launch {
        // suspend fun: lastKnown() を awaiting するため
        newGuidanceManager.startGuidance(route = previewState.selectedRoute)
    }
}

private fun handleNavigationStop() {
    newGuidanceManager.stopGuidance()
    popScreenState()                  // ← Phase 1 で追加。Navigating → RoutePreview に戻す
    // NewRouteManager は触らない（Ready を保持して RoutePreview に戻る）
}
```

`MapScreen` への `newGuidanceState` 受け渡しは既に `MapViewModel.newGuidanceState: StateFlow<GuidanceState>` が公開されているので、Compose 側で `collectAsStateWithLifecycle()` する。

### 4.2 `MapScreen` の Navigating 分岐

`MapScreenContent` / `MapScreenBottomSheetContent` の `is Navigating -> Unit` を以下に置き換え:

```kotlin
// 上部オーバーレイ（次ターンバナー）
is MapScreenState.Navigating -> {
    val guiding = guidanceState as? GuidanceState.Guiding ?: return@MapScreenContent
    MapNavigatingTopContent(
        modifier = Modifier.fillMaxWidth(),
        progress = guiding.progress,
    )
}

// 下部オーバーレイ（ETA カード + 案内終了ボタン）
is MapScreenState.Navigating -> {
    val guiding = guidanceState as? GuidanceState.Guiding ?: return@MapScreenBottomSheetContent
    MapNavigatingBottomContent(
        modifier = Modifier.fillMaxWidth(),
        progress = guiding.progress,
        onStopClicked = { onUiEvent(MapUiEvent.OnNavigationStop) },
    )
}
```

`SHEET_VISIBLE_STATES` には `Navigating` を含めない（`BottomSheet` は非表示のまま、TBT は `AndroidView(NavigationView)` の上に `Box` overlay で重ねる）。

実 Composable の中身（次ターンバナー・ETA カード・レーン UI 等）の実装は本ドキュメント範囲外（UI 実装は本人）。本ドキュメントは `progress` 経由で UI に必要なデータを届けるところまでを規定。

### 4.3 `MapEffect` の Navigating 分岐を新設

現状 `feature/map/.../MapEffect.kt:54` で `is MapScreenState.Navigating -> Unit` になっているため、
案内開始の瞬間にルートライン・目的地マーカー・自車マーカーが**全部消える**。本 PR で必ず
以下を追加すること:

```kotlin
is MapScreenState.Navigating -> {
    NavigatingEffect(
        screenState = screenState,
        routePreviewState = routePreviewState,  // selectedRoute から geometry を引く
        guidanceState = guidanceState,           // Guiding.progress.snappedLocation を自車マーカーに
        googleMap = googleMap,
    )
}
```

`NavigatingEffect` の中身:

- ルートライン: `RoutePreviewState.Ready.selectedRoute.geometry` を `MapPolyline` で描画
  （Preview と同じスタイル。リルート時は新 geometry に置き換わる）
- 目的地マーカー: `selectedRoute.destination` に `MapMarker`
- 自車マーカー: `guidanceState.progress.snappedLocation` + `progress.bearingDegrees` で
  chevron 風マーカー（新規 `MapSelfMarker` Composable を別途用意）
- 経由地マーカー: `selectedRoute.intermediateWaypoints` に `MapMarker`

`MapEffect` のシグネチャに `guidanceState: GuidanceState` 引数を追加し、`MapScreen.android.kt`
側で `viewModel.newGuidanceState.collectAsStateWithLifecycle()` で渡す。

### 4.4 描画レイヤの想定

```
Box (MapScreen 全体)
├── AndroidView(NavigationView)          # 地図描画
├── MapPolyline                          # ルートライン（既存）
├── MapMarker(destination)               # 目的地マーカー（既存）
├── MapMarker(selfChevron)               # 自車マーカー（progress.snappedLocation, progress.bearingDegrees）
├── Box(Modifier.align(TopStart))        # 次ターンバナー
│   └── MapNavigatingTopContent(progress)
└── Box(Modifier.align(BottomCenter))    # ETA カード + 案内終了ボタン
    └── MapNavigatingBottomContent(progress, onStopClicked)
```

---

## 5. DI 変更（`NavigationModule.kt`）

```kotlin
val navigationModule: Module = module {
    // 既存
    single { NewRouteManager(routeRepository = get()) }
    single { ExtNavRouteRegistry() }
    single<RouteDataSource> { ExtNavRouteDataSource(...) }
    // ... 略

    // 新規
    single { ExtNavGuidanceTracker() }
    single { ExtNavGuidanceBootstrap() }
    single {
        NewGuidanceManager(
            registry = get(),
            tracker = get(),
            locationDataSource = get(),
            guidanceBootstrap = get(),
        )
    }
}
```

> **scope の注意**: `NewGuidanceManager` は `single`（application scope）で握る。
> `release()` を呼んでも内部 `CoroutineScope` は cancel しないため、`MapViewModel` が
> 作り直されて再度 `startGuidance` してもインスタンスは健全に動作する。`ExtNavGuidanceTracker` /
> `ExtNavGuidanceBootstrap` も同様に `single` で OK（状態は session 単位で `attach` / `detach` する設計）。

`CurrentLocationDataSource` は `core/datasource` 配置にする想定なので、`core/datasource` の DI モジュール側で登録:

```kotlin
val datasourceModule: Module = module {
    single { CurrentLocationDataSource(context = androidContext()) }
    // ... 既存
}
```

---

## 6. シーケンス図

### 6.1 案内開始 → 初期表示 → 1 tick

```
[User taps 案内開始]
   │
   ▼
MapViewModel.handleNavigationStart
   │
   ├─ pushScreenState(MapScreenState.Navigating)
   └─ scope.launch { newGuidanceManager.startGuidance(RouteDetail) }
        │
        ├─ ExtNavRouteRegistry.get(route.id) → ExtNavRoutePayload (Preview でキャッシュ済み)
        ├─ ExtNavGuidanceTracker.attach(payload, route)
        │    └─ GP 累積距離テーブル事前計算 (§1.5 の scaleFactor で射影)
        │
        ├─ ★初期化分岐:
        │    locationDataSource.lastKnown()
        │      ├─ 取得成功 → tracker.onLocation(it)  → tracker.state 更新 → Guiding(progress)
        │      └─ null     → guidanceBootstrap.fromOrigin(payload, route)
        │                    → _state.value = Guiding(bootstrapProgress) (origin 起点)
        │
        ├─ session = SupervisorJob(parent = scope.coroutineContext.job)
        ├─ scope.launch(session) { try { locationUpdates.collect { tracker.onLocation(it) } }
        │                          catch (e) { _state = Failed("location: ...") } }
        └─ scope.launch(session) { try { tracker.state.collect { _state = Guiding(it) } }
                                   catch (e) { _state = Failed("tracker: ...") } }
   │
   ▼
[初期表示] MapScreen は Guiding(progress) を即時受け取り、TBT + 停止ボタン表示
   │
   ▼
[1st 位置 tick (FusedLocation から)]
   │
ExtNavGuidanceTracker.onLocation(userLocation)
   ├─ polyline 投影 → snappedLocation, currentCumulativeMeters
   ├─ 次 GP 検索 (gpCumulativeOnGeometry 二分探索)
   ├─ 次 GP に対応する最近接 intersection を距離許容範囲で同定
   ├─ ExtNavGuidanceMapper:
   │    ├─ toManeuverInfo(gp, gpIndex, nearestIntersection, bearingDiff, distance, isLast)
   │    ├─ toLaneGuidance(gp)
   │    ├─ toDirectionSign(nearestIntersection)
   │    └─ toHighwayPanel(nearestIntersection, distance)   // intersection.roadNumberSign 非空時のみ
   ├─ 残距離 = geometryTotalMeters - currentCumulativeMeters
   ├─ 残時間 = totalSeconds × (1 - traveled / total)
   └─ state.value = GuidanceProgress(... nextManeuver = ManeuverInfo or null ...)
   │
   ▼
NewGuidanceManager._state = GuidanceState.Guiding(progress)
   │
   ▼
MapScreen が collectAsStateWithLifecycle で再構成
   ├─ MapEffect.NavigatingEffect → polyline / destination / self marker 更新
   ├─ MapNavigatingTopContent(progress) → 次ターンバナー描画 (nextManeuver != null のとき)
   └─ MapNavigatingBottomContent(progress) → ETA カード描画
```

### 6.2 案内終了

```
[User taps 案内終了]
   │
   ▼
MapViewModel.handleNavigationStop
   ├─ newGuidanceManager.stopGuidance()
   │    ├─ sessionJob.cancel()
   │    ├─ tracker.detach()
   │    └─ state = Idle
   └─ popScreenState()
        └─ MapScreenState.Navigating → RoutePreview に戻る
```

`NewRouteManager` の `RoutePreviewState.Ready` は **保持したまま**。ユーザは別ルートを選び直して再度「案内開始」できる。

---

## 7. テスト戦略

### 7.1 単体テスト（CI）

| テスト対象 | 主なケース |
|---|---|
| `ExtNavGuidanceTrackerTest` | attach → onLocation で `state` が更新される / 単調増加が維持される / 投影誤差が 5m 以下 / 次 GP 切り替わり |
| `ExtNavGuidanceMapperTest` | 外部ライブラリのモックデータから `GuidanceManeuverInfo` / `LaneGuidance` / `DirectionSign` / `HighwayPanel` が正しく生成される |
| `NewGuidanceManagerTest` | `startGuidance` → `state = Guiding` / `stopGuidance` → `state = Idle` / `registry.get(...)` が null → `state = Failed` / `release` 冪等 |

### 7.2 統合テスト（実機・手動 QA）

- `docs/spec/15` の Fake GPS Dev Tool でドライブシミュレーション
- 短距離（市街地）・中距離（高速混在）の 2 ルートで:
  - 自車マーカーが polyline 上に乗っているか（投影精度）
  - 次ターンバナーの距離が GPS tick に応じて減っていくか
  - GP 切り替わり時にバナーが更新されるか
  - 案内終了ボタンで RoutePreview に戻るか

---

## 8. Phase 1 範囲外（後続 PR）

| 機能 | 対応する PR / spec |
|---|---|
| 音声案内（`ExtNavAnnouncementScheduler` + `ExtNavSsmlSpeaker` + `tts/*`） | spec/18 T12, T13 / spec/21 |
| リルート検知（`ExtNavRerouteDetector`） | spec/18 T14 |
| 到着判定（`GuidanceState.Arrived` 遷移） | spec/18 T20 |
| カメラ自動制御（`ExtNavCameraController`） | spec/18 §1.5 / T15 |
| ForegroundService（バックグラウンド継続） | spec/18 T21 |
| `GuideImage` プリロード（青看板・3D JCT） | spec/18 §4.5 / Phase 2 |
| ナビ中の渋滞情報動的更新（`ExtNavTrafficOverlay`） | spec/18 §3.2 / Phase 2 |
| 規制情報（事故・通行止め）取得・表示 | spec/18 §1.1 / Phase 2 |
| 複数ルート候補（priority 切替） | **既に解決済み**（`resolveGuidance` が複数返す） |
| 速度計 / 速度制限 UI | spec/18 Phase 3 |

これらは Phase 1 の `GuidanceProgress` データ構造で吸収可能な形にしてある（`lanes` / `directionSign` / `highwayPanel` / `currentSpeedLimitKmh` は Phase 1 では値が空でも OK だが、フィールドだけ確保）。

---

## 9. 着手前に確認すべきこと

| # | 項目 | 確認方法 / 現状 |
|---|---|---|
| A | ~~`ExtNavRoutePayload.kt` の所在~~ | **確認済み**: `ExtNavRouteDataSource.kt` 末尾 (L1028-1032) に定義あり |
| B | ~~外部 API の `RouteGuidance` 構造~~ | **確認済み**: `polyline / intersections[] / guidancePoints[]` (`distanceFromStartMetres` 持ち) / `summary` / `congestionSegments` が揃っている (`ExtNavRouteDataSource` で全て使用済み) |
| C | GP の `phrases[]` 型と発話距離 | Phase 2 範囲。`drive-supporter-api/` 側の `me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint.phrases` を確認 |
| D | ~~`RouteDetail.id` と Registry キーの一致~~ | **確認済み**: 両方 `routeGuidance.priority?.name ?: "route-${index}"` で同じ値 |
| E | `AppSetting.extNavDeviceUuid` の永続化 | spec/18 §2.3 で必要と決定済み。実装されているか OneNavi 側で確認 |
| F | `ACCESS_FINE_LOCATION` 権限フロー | `feature/map` 側で起動時に権限要求が走っているか確認。無ければ `calf` の Permission API or `accompanist-permissions` を追加 |
| G | `GuidanceCategory` の Phase 1 で使う列挙値 | `me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory` の列挙のうち、Phase 1 の `ManeuverType` 射影で必要なもの（Turn / Fork / Merge / Arrive 等）を確定 |

A・B・D は本ドキュメント執筆時点で `ExtNavRouteDataSource.kt` 読解により確認済み。
C・G は `drive-supporter-api/` のプライベートリポジトリ側で詳細確認。E・F は本 PR で実装範囲に
含めるかどうかの判断が必要。

---

## 10. 命名ポリシー

`CLAUDE.md` §厳命に従い、本ドキュメント中で:

- 事業者名: 「N 社」
- ライブラリ: 「外部ナビ API ライブラリ」または `drive-supporter-api`（git submodule のディレクトリ名としてのみ）
- クラス prefix: `ExtNav`
- BuildKonfig / 環境変数: `EXT_NAV_*`

外部ライブラリの実型名（`RouteGuidance` / `GuidancePoint` / `Intersection` / `GuidanceCategory` 等）は
ドメインを表す一般名なので本ドキュメント上で参照しても OK（既存 `ExtNavRouteDataSource.kt` でも
同様に参照されている）。一方で、ルート import 元のパッケージ root のドメイン名・事業者名・
製品名は本ドキュメントに書かない。

---

## 11. Open Questions / 設計の合意点

### OQ1: `nextManeuver` が null になるケースの UI 挙動

- **状況**: 最後の GP を通過後・`guidancePoints` が 0 件・初回 tick 前で bootstrap 経由など
- **データ層の合意**: `GuidanceProgress.nextManeuver: GuidanceManeuverInfo?` を nullable にする
- **UI 層の合意点（要決定）**: null のときの TBT バナー表示
  - 候補 A: バナー自体を消して ETA カードのみ表示
  - 候補 B: 「目的地まで直進」のような暫定テキストを出す
  - 候補 C: `distanceRemainingMeters` が閾値以下なら「まもなく到着」を出す
- **Phase 1 推奨**: 候補 A（最小実装）。候補 B/C は Phase 2 の到着判定実装と合わせて検討

### OQ2: 初回 GPS tick 前の UI 状態

- **状況**: `startGuidance` 直後、まだ位置 fix が来ていない瞬間
- **データ層の合意**: `CurrentLocationDataSource.lastKnown()` を `startGuidance` 冒頭で呼ぶ
  - 取れた場合: tracker.onLocation で通常 flow へ
  - 取れなかった場合: `ExtNavGuidanceBootstrap.fromOrigin` で `progress = bootstrap` を即時公開
- これにより `MapScreen` は `Guiding(progress)` を必ず受け取れるため、停止ボタン・ルートライン・
  目的地マーカーは欠落しない
- bootstrap progress と通常 progress の見分けを UI で出したい場合は `GuidanceProgress` に
  `isBootstrap: Boolean` のようなフラグ追加を検討（Phase 1 では不要、必要になったら別 PR）

---

## 12. 参考リンク

- `docs/spec/18_external_nav_api_migration_plan.md` — 外部ナビ API 移行計画（正本）
- `docs/spec/19_drive_supporter_api_integration_plan.md` — 外部ライブラリ統合計画
- `docs/spec/21_ext_nav_guide_proto_and_announcement.md` — GP / 音声案内の検討
- `docs/spec/24_new_guidance_manager_design.md` — `NewGuidanceManager` 全体設計（v2）
- `docs/spec/15_fake_gps_dev_tool.md` — ドライブシミュレーション用 Fake GPS
