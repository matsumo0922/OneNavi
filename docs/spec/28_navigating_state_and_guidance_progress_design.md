# 28. `MapScreenState.Navigating` / `NewGuidanceManager` 仕様 — 進捗追跡 + UI 描画用データ

> **作成日:** 2026-05-18
> **ステータス:** 仕様
> **対象:** `MapScreenState.Navigating` と `NewGuidanceManager` の案内中 state、進捗追跡、UI 描画用モデル、周辺機能が消費する projection snapshot のデータ契約。

---

## 0. このドキュメントの位置付け

- spec/18 (`18_external_nav_api_migration_plan.md`) が外部ナビ API 移行の全体計画
- spec/24 (`24_new_guidance_manager_design.md`) が `NewGuidanceManager` の全体設計（v2）
- 本ドキュメント (spec/28) は **`MapScreenState.Navigating` で必要な進捗追跡とデータ契約の最終形** を定義する。

本ドキュメントが定義する最終状態:

- UI は `GuidanceProgress` だけを購読して TBT / ETA / 自車マーカーを描画できる
- ルートライン・目的地・経由地マーカーは `GuidanceState.Guiding.route` を正とし、リルート後も
  Navigating 内で新しい geometry に置き換わる
- 音声案内・リルート検知・到着判定・カメラ制御は `ExtNavProgressSnapshot` を消費できる
- GPS の生位置は `NewGuidanceManager` が `ExtNavGuidanceTracker` に投入し、ルート追従・発話・リルート判定は
  Tracker が生成する projection snapshot を共通入力にする
- ルート探索時に取得した `RouteGuidance` を案内中の主データ源とし、通常の位置 tick ではネットワーク I/O を発生させない

### 0.1 前提とする決定事項

| 項目 | 決定 |
|---|---|
| `GuidanceState` に進捗を載せる方式 | `Guiding` を `data class Guiding(route: RouteDetail, progress: GuidanceProgress)` 化。UI には現在 route と `GuidanceProgress`、Tracker 内部と周辺機能には `ExtNavProgressSnapshot` を使う |
| 位置情報ソース | `FusedLocationProviderClient`（spec/18 Q-102 の検証結果待ちではなく、spec/24 で既に決定済み） |
| TBT UI の重ね方 | Compose の `Box` overlay（`AndroidView(NavigationView)` の上に `Box` を重ねる）。`NavigationView.setCustomControl` は使わない |
| 案内終了ボタンの遷移先 | `RoutePreview` に戻す（`Navigating` だけ pop し、`NewRouteManager` の `Ready` は保持） |
| `New` prefix | 旧 `feature/home` 系と並走する間の一時 prefix。prefix の有無は本仕様の状態遷移・データ契約に影響しない |

### 0.2 用語

- **GP (案内ポイント)**: 外部ナビ API ライブラリの案内テキスト発火点（`RouteGuidance.guidancePoints[]`）
- **進捗投影**: 生 GPS 位置を `RouteDetail.geometry` の最近接セグメントに射影し、単調増加な走行距離・残距離・次 GP を算出する処理
- **オフルート候補**: 生 GPS と projection のズレ、進行方向、精度から「ルート外の可能性が高い」と判定された tick。
  これ自体はリルート確定ではなく、`ExtNavRerouteDetector` の debounce 入力
- **発話スロット**: GP と phrase をキーにした音声案内の発話単位。`ExtNavAnnouncementScheduler` が発話済み状態を持つ
- **TBT (Turn-By-Turn)**: 交差点ごとの曲がる方向案内 UI

---

## 1. 外部ナビ API のデータソース利用方針

### 1.0 結論: ROUTE / GUIDE は per-tick で叩かず、交通 overlay は案内中に定期更新する

`ExtNavRouteDataSource.kt` を読むと、ルート探索（Preview）時点で外部ナビ API ライブラリの
`GuidanceClient.resolveGuidance()` と `RouteClient.search()` を並列に叩いて、各候補の
`RouteGuidance` を `ExtNavRouteRegistry` に既にキャッシュ済み。ナビ進捗計算に必要な
polyline / 交差点 / GP / phrase / summary / congestion はすべてここから取れる。

通常案内中の位置 tick では、

- **ROUTE / GUIDE のどちらも追加で叩かない**
- Tracker は Registry から `ExtNavRoutePayload.routeGuidance` を読み、位置 tick のたびに
  オンメモリで進捗を再計算するだけ
- Tracker 自体はネットワーク I/O を持たない

リルート時は、`ExtNavRouteDataSource.searchRoutes(currentLocation, destination, remainingViaPoints)`
を再呼び出しして新しい `RouteGuidance` を取得する。

一方、渋滞・規制情報は時間経過で古くなるため、案内中に `ExtNavTrafficOverlay` が別経路で更新する。
更新トリガは以下:

- 案内開始直後に 1 回
- 最終成功取得から **5 分以上** 経過
- 最終成功取得地点から **5 km 以上** 進んだ
- リルートで route id / path code 群が変わった

いずれかを満たしたら、in-flight の取得が無い場合に traffic / regulation 系 API を再取得する。
失敗時は最後に成功した overlay を保持し、`GuidanceProgress` は更新失敗の影響を受けない。

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
   ├─ searchSessionId = registry.beginSession()
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
   │       routeKey = routeGuidance.priority?.name ?: "route-${index}"
   │       routeId = "$searchSessionId:$routeKey"
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

`Registry` に詰まる payload と `RouteDetail.id` は **検索セッション ID を含む同じ `routeId` 文字列でキー一致** させる。
`NewGuidanceManager.startGuidance(route: RouteDetail)` から `registry.get(route.id)` で引く。

重要: priority 名だけを route id にすると、新しい検索でも `Recommended` / `Free` など同じキーが
再利用され、古い `RouteDetail` と新しい payload の取り違えが起きうる。`ExtNavRouteDataSource.searchRoutes`
は検索開始時に `registry.beginSession()` を呼び、旧 payload を clear したうえで、その session id を
全候補の `RouteDetail.id` / `ExtNavRoutePayload.id` に含める。

### 1.2 ROUTE と GUIDE の役割分担（実装の事実）

| エンドポイント | 何のために叩いているか | 案内中の通信タイミング |
|---|---|---|
| `GuidanceClient.resolveGuidance(criteria)` | 全候補の polyline / GP / intersection / phrase / summary / congestion をまとめて 1 リクエストで取得。案内中の主データ源 | Preview / reroute 時のみ。位置 tick では叩かない |
| `RouteClient.search(criteria)` × priorities | 料金区間の入口 / 出口 IC 名 (`InterchangeNameHint`) を取るためだけに、priority ごとに個別に叩いている。GP / polyline は使っていない | Preview / reroute 時のみ。位置 tick では叩かない |
| `TrafficClient.listByPathCodes(pathCodes)` 等 | ルート上の最新渋滞情報を更新する | 案内開始直後、5 分経過、5 km 進行、reroute 後 |
| 規制情報 API / overlay | 事故・通行止めなどを更新する | 渋滞情報と同じ trigger で更新 |

> spec/18 §4.4 は「案内 API は 1 候補しか返さない」前提だったが、実装では既に
> `resolveGuidance` に priorities 集合を渡して `Guidance.routes` で複数候補が返る形に解決済み。
> 本ドキュメントはその事実を正とする。

### 1.3 `RouteGuidance` から取り出すフィールド

`ExtNavRoutePayload.routeGuidance: RouteGuidance` から `ExtNavGuidanceMapper` 経由で取り出す:

| フィールド | 用途 | OneNavi 側モデル |
|---|---|---|
| `routeGuidance.polyline: List<Coord>` | 自車投影の基盤（既に `RouteDetail.geometry` として詰め直し済み） | `RouteDetail.geometry` |
| `routeGuidance.intersections[]` | 交差点名 / 高速サイン / 道路名（次マニューバの `intersectionName` 抽出） | `GuidanceManeuverInfo.intersectionName` |
| `routeGuidance.guidancePoints[]` | **TBT バナーの主データ源**。`distanceFromStartMetres` でルート始点からの距離を持つ | `GuidanceManeuverInfo`, `LaneGuidance`, `DirectionSign` |
| `routeGuidance.guidancePoints[].phrases[]` | 音声フレーズ / マニューバ種別判定 | `ExtNavAnnouncementScheduler`, `GuidanceManeuverInfo` |
| `routeGuidance.guidancePoints[].phrases[].category` | `GuidanceCategory`（マニューバ種別の射影元） | `ManeuverType`, `ExtNavAnnouncementScheduler` |
| `routeGuidance.summary.distanceMetres` | ルート全長（残距離の母数） | `RouteDetail.distanceMeters` |
| `routeGuidance.summary.timeSeconds` | ルート全所要時間（残時間の母数） | `RouteDetail.durationSeconds` |
| `routeGuidance.summary.streets[]` | 現在走行中の道路名 / 高速判定（StreetSegment 単位） | `GuidanceProgress.currentRoadName`, `currentRoadClass` |
| `routeGuidance.congestionSegments[]` | ルート探索時点の初期渋滞 snapshot。`ExtNavTrafficOverlay` の最新値が無い場合の fallback | `CongestionSegment` |

### 1.4 ナビ中の渋滞情報・規制情報の扱い

#### 1.4.1 結論

- **初期渋滞情報**: `RouteGuidance.congestionSegments` としてルート探索時に取得済み。
  `RouteDetail.congestionSegments` 経由で overlay 初期値 / fallback に使う
- **Tracker の責務**: 渋滞セグメントを所有しない。必要な UI / 音声は
  `ExtNavProgressSnapshot.currentCumulativeMeters` と traffic overlay の最新セグメントから前方渋滞を派生する
- **動的更新**: 交通情報の再取得は `ExtNavTrafficOverlay` の責務。案内開始直後 / 5 分経過 /
  5 km 進行 / reroute 後に更新する。Tracker / `GuidanceProgress` は
  動的交通情報の保存先にならない
- **規制情報（事故・通行止め等）**: `RegulationOverlay` のような別 StateFlow で扱い、
  `GuidanceProgress` には混ぜない

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

初期 snapshot / 動的 overlay のどちらも `GuidanceProgress` には逐次再計算では入れない。理由:

- polyline 色分けは `RouteDetail.congestionSegments` または `ExtNavTrafficOverlay` のセグメントを
  map overlay が直接読む方が責務分離しやすい
- 「現在通過中の渋滞」「次の渋滞までの距離」を TBT バナーに出すかどうかは UI デザイン判断の話で、
  `GuidanceProgress` の基本契約に含める必要がない

TBT バナーに「この先 3km 渋滞」のような表示を出す場合は、UI 側または traffic overlay 側で
現在位置 (`ExtNavProgressSnapshot.currentCumulativeMeters`) より先の最初の congestion を抽出する。
`GuidanceProgress` に `upcomingCongestion` を足すのは、UI が常時必要とすることが確定した場合に限る。

#### 1.4.3 ナビ中の動的更新

spec/18 §3.2 の `ExtNavTrafficOverlay` は、Tracker とは別の状態として扱う:

```
ExtNavTrafficOverlay
  ├─ ルート確定時に対象 pathCode 群を抽出
  ├─ 案内開始直後に TrafficClient.listByPathCodes(pathCodes) を取得
  ├─ 最終成功取得から 5 分以上、または 5 km 以上進行したら再取得
  ├─ reroute で route id / path code 群が変わったら即時再取得
  ├─ 差分を CongestionSegment に射影し、TrafficOverlayState として公開
  └─ 規制情報（事故・通行止め）は RegulationOverlay として別 StateFlow で公開
```

`GuidanceProgress` はこの overlay を直接保持しない。UI は必要に応じて
`GuidanceState.Guiding.progress` と `ExtNavTrafficOverlay` を組み合わせて描画する。

#### 1.4.4 関連する OneNavi 側既存設計

- spec/26 (`26_route_congestion_design.md`) — 渋滞モデルの設計
- spec/27 (`27_route_congestion_visualization_design.md`) — polyline 色分けの可視化設計

これらは Preview 時点で機能していて Navigating でも流用できる。本ドキュメントはここに依存しない。

---

### 1.5 GP の `distanceFromStartMetres` の取り扱い

外部ライブラリ側の `guidancePoints[i].distanceFromStartMetres` は **`routeGuidance.summary.distanceMetres`
基準**（つまり外部 API が算出したルート全長基準）。一方、OneNavi 側で polyline 描画と
進捗計算に使う `RouteDetail.geometry` は **haversine で逐次計算した累積距離**を別に持つ。

両者は完全一致しない。さらにズレは全線で均一とは限らないため、Tracker は attach 時に
**一律 scaleFactor は使わず**、既存 `ExtNavRouteDataSource.buildRoadClassSegments(...)` と同じ考え方の
アンカー付き距離 mapper を使う:

```
source metres = routeGuidance.summary.distanceMetres 基準
geometry metres = RouteDetail.geometry の haversine 累積距離

anchors:
  0m -> 0m
  summary.distanceMetres -> geometryTotalMeters
  street/intersection matching で作れる中間アンカー

gpCumulativeOnGeometry[i] = RouteDistanceMapper.mapSourceToGeometry(guidancePoints[i].distanceFromStartMetres)
```

その上で `gpCumulativeOnGeometry[]` と geometry の累積距離テーブルで二分探索すれば、tick 時に
O(log n) で次 GP を引ける。`RouteDistanceMapper` は `core/navigation/extnav` 内の小さな純 Kotlin
部品として切り出し、Preview 側の道路種別境界推定と Guidance 側の GP 射影で共用する。

中間アンカーが作れないルートでは 0m / 終端だけの線形 mapper にフォールバックする。ただしこれも
`RouteDistanceMapper` 経由に統一し、テストで「単純 scaleFactor ではない」ことを明示する。

---

## 2. データ型の設計

### 2.1 `GuidanceProgress` — UI が読む案内中のスナップショット

`feature/map` の TBT / ETA / 自車マーカーが読む案内進捗スナップショット。Compose の安定性のため
`@Immutable` + `ImmutableList` を徹底。

```kotlin
/**
 * 案内中の進捗スナップショット。
 *
 * `NewGuidanceManager` が `GuidanceState.Guiding(route, progress)` の形で公開する。
 * `ExtNavGuidanceTracker` の `snapshot.progress` として生成される UI 用モデル。
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
    // UI 側は null の場合 TBT バナーを非表示にする（§10）。
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

`GuidanceProgress` は UI 描画に必要な値だけを持つ。音声案内・リルート・到着判定のような周辺機能が
必要とする projection 生データは、下記 `ExtNavProgressSnapshot` に分けて保持する。

| フィールド | 用途 |
|---|---|
| `distanceRemainingMeters` | ETA カードの「残距離」表示 |
| `durationRemainingSeconds` | ETA カードの「残時間」表示 |
| `etaEpochMillis` | ETA カードの到着予想時刻表示。`durationRemainingSeconds` + tick 時刻から算出 |
| `traveledMeters` | デバッグ・統計用。単調増加（リルートで 0 リセット） |
| `snappedLocation` | 自車マーカーの座標。生 GPS ではなく polyline 投影後の点 |
| `bearingDegrees` | カメラ追従の bearing。生 GPS の bearing が信頼できないケースで投影セグメントの方位を使う |
| `nextManeuver` | TBT バナーの主役 |
| `followupManeuver` | 「その後 右折」用。該当データがない場合は null |
| `lanes` | レーンガイダンス UI。取得口または意味付けが未確定な場合は空 |
| `directionSign` | 方面看板表示。取得口が未確定な場合は null |
| `highwayPanel` | IC/JCT/SA/PA パネル |
| `currentRoadName` | 走行中の道路名表示。道路名区間を確定できない場合は null |
| `currentRoadClass` | 表示色・パネル切り替えの判定用 |
| `currentSpeedLimitKmh` | 速度計サブ表示。速度制限の取得元と有効範囲が未確定な場合は null |

### 2.1.1 `ExtNavProgressSnapshot`（Tracker 内部 / 周辺機能用）

`GuidanceProgress` は UI 向けに丸めたモデルなので、Tracker は音声・リルート・到着判定が
再利用できる projection snapshot も公開する。

```kotlin
@Immutable
data class ExtNavProgressSnapshot(
    val progress: GuidanceProgress,
    val rawLocation: UserLocation?,
    val currentCumulativeMeters: Double,
    val distanceRemainingMeters: Double,
    val matchedSegmentIndex: Int,
    val projectionErrorMeters: Double,
    val locationTimestampMillis: Long,
    val vehicleSpeedMps: Float?,
    val isOffRouteCandidate: Boolean,
    val nextGuidancePointIndex: Int?,
)
```

| フィールド | 用途 |
|---|---|
| `progress` | UI に公開する `GuidanceProgress` |
| `rawLocation` | FusedLocation 由来の生位置。音声発話の速度調整・デバッグ用 |
| `currentCumulativeMeters` | geometry 上の現在累積距離。発話・到着・渋滞先頭距離の共通基準 |
| `distanceRemainingMeters` | 内部計算用の Double 残距離。UI では丸めて `GuidanceProgress` に入れる |
| `matchedSegmentIndex` | snap 先 polyline segment。camera / marker / debug overlay の基礎 |
| `projectionErrorMeters` | 生 GPS と snap 点の距離。リルート候補判定に使う |
| `locationTimestampMillis` | tick の鮮度判定、停止時の古い lastKnown 除外 |
| `vehicleSpeedMps` | 発話タイミング調整、速度計 |
| `isOffRouteCandidate` | `ExtNavRerouteDetector` 入力。UI には直接出さない |
| `nextGuidancePointIndex` | 音声案内の GP slot state / followup 算出のキー |

### 2.2 `GuidanceManeuverInfo`

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
- `guidancePointIndex` は発話既出マーク（`spoken.add((gpIndex, category, distance))`）のキーに使う

### 2.3 `LaneGuidance` / `DirectionSign` / `HighwayPanel`

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

- `DirectionSign.imageKey` は `GuideImageClient.preload` の Coil キャッシュキーを保持する。画像が無い場合は null
- `HighwayPanel.services` は SA/PA のサービス情報が取れる場合に入れる。無い場合は空

#### 2.3.1 取得口が足りない / 未確定のデータ

以下は取得口または意味付けが未確定。実装では
「空で返す」か「限定的な fallback」として扱い、確定していない値を本物の案内情報として表示しない。

| 対象 | 足りない / 未確定のデータ | 候補 / 既存の取得口 | 未確定時の扱い | 必要な確認 |
|---|---|---|---|---|
| `LaneGuidance` | 推奨車線の左右・通行可否・料金所レーン種別の意味付け。`laneMarkers` は疎で、発話上の「右側 N 車線」と物理 GP が離れるケースがある | `GuidancePoint` の lane 系フィールド候補、`phrases[]` の発話テキスト | 原則 `emptyList()`。確実に意味が取れる fixture だけ mapper test 対象 | lane marker の意味をサンプル追加で確定し、phrase text との対応ルールを定義 |
| `DirectionSign` | 方面看板そのものの文言・画像キー。intersection の道路名だけでは看板表示とは限らない | `Intersection.roadNameOfficial` / `roadName` / `roadNumberSign`、`GuideImageClient.preload` | `directionSign = null` を基本。道路名 fallback を使う場合は「道路名表示」として扱い、看板 UI にはしない | `GuidancePoint.directionSigns[]` 相当の有無と `GuideImage` key の対応を確認 |
| `ExtNavAnnouncementScheduler` | phrase ごとの明示的な発話距離 / 発話タイミング。GP は距離を持つが、1 GP 内の複数 phrase が同じタイミングか段階発話かは型定義に依存する | `GuidancePoint.distanceFromStartMetres`, `GuidancePoint.phrases[]` | `gpIndex + phraseIndex + category` を発話済みキーにし、明示的な trigger が無い phrase は GP までの残距離と速度から標準スロットに割り当てる | `Phrase` に trigger distance / audio id / ssml text 相当があるか確認 |
| `currentRoadName` | 走行順を保った道路名区間。既存 `RouteDetail.roadSegments` は距離順集計で、現在道路名には使えない | `routeGuidance.summary.streets[]`、intersection の road name | `null` でも可。出す場合は attach 時に `RoadNameSegment(start/endGeometryMeters, roadName)` を内部生成 | `RouteDistanceMapper` と同じアンカーで `StreetSegment` を geometry 距離に射影 |
| `currentSpeedLimitKmh` | 速度制限の取得元と有効範囲 | 未確定。過去 spec では GuidePoint speedLimit 想定 | 常に `null` | 外部ライブラリ型に speed limit があるか確認 |
| `HighwayPanel.services` | SA/PA のサービス情報（給油・飲食など） | 未確定。intersection 名から施設種別だけ推定可能 | `services = emptyList()` | 施設メタデータの取得口を確認。なければ名称・距離のみ表示 |

### 2.4 `GuidanceState.Guiding` を data class 化

```kotlin
@Immutable
sealed interface GuidanceState {
    data object Idle : GuidanceState
    data class Guiding(
        val route: RouteDetail,
        val progress: GuidanceProgress,
    ) : GuidanceState
    data object Rerouting : GuidanceState
    data object Arrived : GuidanceState
    data class Failed(val message: String) : GuidanceState
}
```

- 案内開始直後（初回位置取得前）は `progress = null` を許容せず、`lastKnown()` または
  `ExtNavGuidanceBootstrap.fromOrigin(...)` で非 null の `Guiding(route, progress)` を即時公開する（§3.5.4）
- `route` は現在案内中の `RouteDetail`。リルート成立時は新しい `RouteDetail` に置き換え、
  ルートライン・目的地・経由地マーカーもこの route を正とする
- `Rerouting` は逸脱検知後の再探索中、`Arrived` は到着判定成立後の状態として使う

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

`RouteGuidance` は外部ナビ API ライブラリの guidance domain 型。
**OneNavi 側コードからは `ExtNavGuidanceMapper` を経由する場合のみ参照**し、他クラスは
直接ライブラリ型を持ち出さない（命名ポリシー + 抽象化）。

`ExtNavRouteRegistry` は route id の衝突を避けるため、検索ごとに session を切る:

```kotlin
class ExtNavRouteRegistry {
    fun beginSession(): String {
        clear()
        return newSessionId()
    }

    fun put(payload: ExtNavRoutePayload)
    fun get(routeId: String): ExtNavRoutePayload?
    fun clear()
}
```

`ExtNavRouteDataSource.searchRoutes(...)` は `beginSession()` の戻り値を route id に含める。
`RouteDetail.id` と `ExtNavRoutePayload.id` は常に同じ値にする。

---

## 3. クラスの責務

### 3.1 ファイル配置

```
core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/
└── location/
    └── CurrentLocationDataSource.kt

core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/
├── extnav/
│   ├── ExtNavGuidanceTracker.kt
│   ├── ExtNavRerouteDetector.kt
│   ├── ExtNavAnnouncementScheduler.kt
│   ├── ExtNavVoicePlayer.kt
│   ├── ExtNavGuidanceMapper.kt             # internal
│   ├── RouteDistanceMapper.kt              # summary 距離 → geometry 距離のアンカー付き変換
│   ├── ExtNavRoutePayload.kt
│   └── (既存) ExtNavRouteRegistry.kt, ExtNavRouteDataSource.kt 等
└── newguidance/
    ├── NewGuidanceManager.kt
    └── model/
        ├── GuidanceState.kt                # Guiding を data class 化
        ├── GuidanceProgress.kt
        ├── GuidanceManeuverInfo.kt
        ├── LaneGuidance.kt
        ├── DirectionSign.kt
        └── HighwayPanel.kt

core/model/src/androidMain/kotlin/me/matsumo/onenavi/core/model/
└── (既存) ManeuverType / ManeuverModifier / RoadClass / RoutePoint  ← 再利用
```

`core/model` の旧型（Mapbox 時代の `GuidanceEvent` / `DistanceBucket` 等）はこの仕様では参照しない。
互換維持のため残っていても、`Navigating` の状態計算には使わない。

### 3.2 `CurrentLocationDataSource`

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
     * `NewGuidanceManager.startGuidance` の **冒頭で呼ぶ** 想定（§3.5.4 / §10）。
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
- 案内中の GPS tick は `NewGuidanceManager` だけが購読し、各 tick を `ExtNavGuidanceTracker.onLocation(...)`
  に渡す。音声・リルート・到着判定は raw location Flow を直接 collect せず、Tracker の snapshot を消費する
- バックグラウンド継続が必要な場合は ForegroundService 側がこの data source を collect する。
  `CurrentLocationDataSource` 自体は位置更新 Flow の提供に責務を限定する

### 3.3 `ExtNavGuidanceTracker`

```kotlin
class ExtNavGuidanceTracker {
    /** 現在の projection snapshot。null = 未 attach */
    val snapshot: StateFlow<ExtNavProgressSnapshot?>

    /**
     * 案内対象のルート + ガイダンスデータを attach する。
     *
     * attach 時に GP 累積距離テーブル（geometry index → cumulative meters）を
     * 事前計算しておき、tick 時の次 GP 検索を O(log n) に抑える。
     */
    fun attach(payload: ExtNavRoutePayload, route: RouteDetail)

    /**
     * 位置 1 点を投入して進捗を再計算する。snapshot が更新される。
     */
    fun onLocation(location: UserLocation)

    /** 累積状態をクリアし snapshot を null に戻す */
    fun detach()
}
```

#### 3.3.1 内部処理仕様

1. **polyline 投影**
   - `route.geometry: ImmutableList<RoutePoint>` に対する最近接セグメント検索
   - 単調増加を維持するため、前 tick の投影 index より手前は探索しない（最大 N セグメント先読み）
   - 投影精度: WGS84 を平面近似（短距離なので緯度経度の sin 補正だけで OK）

2. **GP 累積距離のプリ計算**（§1.5 に詳述）
   - `RouteDistanceMapper.mapSourceToGeometry(guidancePoints[i].distanceFromStartMetres)` で
     `gpCumulativeOnGeometry[i]` を事前計算
   - tick 時は `gpCumulativeOnGeometry` を二分探索して次 GP を O(log n) で引く

3. **`nextManeuver` 抽出**
   - 現在位置の累積距離 < GP の累積距離、を満たす最初の GP を `nextManeuver`
   - `distanceToManeuverMeters` = `gpCumulativeOnGeometry[i] - currentCumulativeMeters`
   - **該当 GP が無い場合（最終 GP を通過後 / `guidancePoints` が空のルート）は `nextManeuver = null`**
     を `GuidanceProgress` に詰める（§10）。Tracker はこの状態でも `snapshot` を更新し続ける
     （`distanceRemainingMeters` / `snappedLocation` 等は通常通り）

4. **残時間の補正**
   - 基本値は `route.durationSeconds × (1 - traveled / total)` の単純比例
   - 動的交通情報がある場合は `ExtNavTrafficOverlay` が残時間補正値を提供し、UI 側で組み合わせる

5. **ヒステリシス**
   - GPS jitter で投影点が前後しないよう、後退方向への投影更新は閾値（5m）以下なら無視

6. **内部 snapshot の保持**
   - UI に出す値は `GuidanceProgress` に丸める
   - 音声・リルート・到着判定が必要にする `projectionErrorMeters` / `vehicleSpeedMps` /
     `currentCumulativeMeters` などは `ExtNavProgressSnapshot` に残す

#### 3.3.2 GPS 入力とルート追従判定

`ExtNavGuidanceTracker` は GPS tick の正規化点。`UserLocation` を受け取り、ルート geometry 上の
projection として `ExtNavProgressSnapshot` を更新する。Tracker は **リルート開始や音声再生を直接行わない**。
その代わり、周辺コンポーネントが同じ基準で判断できるように、以下を snapshot に残す:

- `rawLocation`: 生 GPS。発話速度補正・デバッグ・再探索 origin に使う
- `currentCumulativeMeters`: ルート上の現在位置。次 GP / 発話 / 到着 / 交通 overlay の共通距離基準
- `projectionErrorMeters`: 生 GPS と snap 点の横ズレ
- `vehicleSpeedMps`: 発話距離の先読み補正、停止中の誤発話抑制
- `matchedSegmentIndex`: カメラ・自車マーカー・進行方向補正
- `isOffRouteCandidate`: debounce 前の逸脱候補

`isOffRouteCandidate` は以下を満たす tick で true:

- `rawLocation.accuracyMeters <= 50m`
- `projectionErrorMeters >= max(30m, rawLocation.accuracyMeters * 1.5)`
- `vehicleSpeedMps >= 1.5m/s`、または直近 5 秒で `currentCumulativeMeters` が 10m 以上進んでいる
- 進行方向が取れる場合、snap 先 segment の方位と GPS bearing の差が 70° 以上、または最近接 projection が
  前回の `matchedSegmentIndex` から探索窓内で見つからない

低精度 GPS、停止中、目的地直前 100m 以内では `isOffRouteCandidate = false` に倒す。Tracker の
責務は「候補 tick を出す」までで、連続 tick の debounce、cooldown、再探索要求は
`ExtNavRerouteDetector` が持つ。

#### 3.3.3 `RouteDistanceMapper`（純関数）

`RouteGuidance.summary.distanceMetres` 基準の距離を、`RouteDetail.geometry` の haversine 累積距離へ
変換する小さな部品。`ExtNavRouteDataSource` 内に既にある道路種別境界推定ロジックから、以下を
再利用可能な形で切り出す。

```kotlin
internal class RouteDistanceMapper(
    anchors: List<DistanceAnchor>,
) {
    fun mapSourceToGeometry(sourceMeters: Double): Double
}

@Immutable
internal data class DistanceAnchor(
    val sourceMeters: Double,
    val geometryMeters: Double,
)
```

作るアンカー:

| アンカー | source | geometry |
|---|---|---|
| 始点 | `0.0` | `0.0` |
| 終点 | `summary.distanceMetres` | `geometryCumulative.last()` |
| 中間 | `summary.streets[]` の道路名境界または交差点一致で推定した距離 | intersection を geometry に snap した累積距離 |
| 高速入口補正 | `AutoExpresswayEntry` 系 GP の `distanceFromStartMetres` | 近傍の高速 sign 付き intersection の累積距離 |

アンカーは `sourceMeters` / `geometryMeters` とも単調増加になるものだけを採用する。中間アンカーが
作れない場合は始点・終点だけの線形変換にフォールバックする。

### 3.4 `ExtNavGuidanceMapper`（`internal`）

外部ナビ API ライブラリの型 → OneNavi モデル の変換は **必ずここに集約**。他クラスはライブラリ型を持ち出さない。

#### 3.4.1 入力構造

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

**重要**: `GuidancePoint.position` を直接持つ前提にはしない。Mapper は GP 単体ではなく
`guidancePoints[].distanceFromStartMetres` と `phrases[]` を正とし、phrase ベースで `GuidanceManeuverInfo`
へ射影する。

#### 3.4.2 GP と Intersection の対応付け

GP 自体は「テキスト発火点」であり、「どの交差点での案内なのか」「道路名 / 高速サインの補助情報」は
`intersections[]` 側に持っている情報が多い。Mapper では GP と intersection を **インデックス
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

方面看板は **GP と intersection だけでは確定しない**。`Intersection.roadNameOfficial` /
`Intersection.roadName` / `Intersection.roadNumberSign` は道路名表示や高速パネルの補助には使えるが、
それだけを `DirectionSign` として出すと「看板文言」と「道路名」が混ざる。

`DirectionSign` は原則 null。外部ライブラリ型に `directionSigns[]` 相当の取得口が
確認できた場合だけ、文字列の primary / secondary を詰める。`GuideImageClient.preload` の結果が
ある場合は `imageKey` に対応する画像参照を渡す。

#### 3.4.5 HighwayPanel の組み立て

`Intersection.roadNumberSign.isNotBlank()` を「高速サイン保有交差点」とみなし、`Intersection.name` から
IC/JCT/SA/PA の種別を文字列パターンで判定（`"JCT"` `"SA"` `"PA"` を含むか、それ以外は IC）。
これは `ExtNavRouteDataSource.kt` の `hasHighwaySign` 判定と同じロジック。

#### 3.4.6 API シグネチャ

```kotlin
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

`GuidancePoint.phrases[].category` の具体的な列挙値・`Phrase` の正確な型・`lane` /
`directionSigns` 等のフィールドの存在は外部ナビ API ライブラリ側の定義を正とする。未確定の項目は
§2.3.1 のとおり null / empty で扱う。

### 3.5 `NewGuidanceManager`

#### 3.5.1 Koin 登録の方針（singleton 維持）

`NewGuidanceManager` は **`single { }` のまま維持** する（spec/24 §2.2 の既存方針）。
`MapViewModel` から `inject` され、`onCleared` で `release()` を呼ぶ前提だが、後述の通り
`release()` は internal scope を破壊せず session 停止だけにする。再生成は OneNavi プロセス
ライフサイクル単位で行わない（`MapViewModel` が作り直されても singleton インスタンスは継続）。

#### 3.5.2 並行制御の方針

- 「位置購読 collect」と「tracker.snapshot collect」を同じ親 Job 配下に置くと、片方の例外で
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

- `release()` は **scope を `cancel()` しない**。`sessionJob` を cancel し、`tracker.detach()`
  を呼び、`_state = Idle` に戻すだけ（= `stopGuidance` と同じ）
- これにより singleton として複数回 `start → release → start` を繰り返せる
- scope 自体の破棄は OneNavi プロセス終了時のみ（Koin が application scope で握る）

#### 3.5.4 初回 tick 前の挙動

開始ボタンを押した直後、初回 GPS tick が来るまでは `_state = Idle` のままだと UI が
「案内中なのに何も出てない」状態になる。対策として `startGuidance` 内で:

1. `locationDataSource.lastKnown()` を即座に呼び、取れた位置で 1 回 `tracker.onLocation` を回す
2. それでも null（権限取得直後で fix なし）の場合は `GuidanceState.Guiding(route, bootstrapProgress)` を
   公開する — bootstrap progress は `summary.distanceMetres` 全長を残距離として詰めた初期スナップ
   ショット（`snappedLocation = route.origin`, `nextManeuver = 最初の GP からの ManeuverInfo`）

これで停止ボタンを表示しつつ「位置取得中」表示も別途出せる（後者は UI 側で `progress.bearingDegrees == 0f &&
snappedLocation == route.origin` を Loading 扱いするなど別途判断）。

#### 3.5.5 API 形状

```kotlin
class NewGuidanceManager(
    private val registry: ExtNavRouteRegistry,
    private val tracker: ExtNavGuidanceTracker,
    private val rerouteDetector: ExtNavRerouteDetector,
    private val announcementScheduler: ExtNavAnnouncementScheduler,
    private val locationDataSource: CurrentLocationDataSource,
    private val guidanceBootstrap: ExtNavGuidanceBootstrap,
    private val routeRepository: RouteRepository,
) {
    // singleton 全体で 1 つ。再利用するのでここで cancel しない。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    private var currentRoute: RouteDetail? = null

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    suspend fun startGuidance(route: RouteDetail) {
        val payload = registry.get(route.id)
        if (payload == null) {
            _state.value = GuidanceState.Failed("route payload not found: ${route.id}")
            return
        }
        tracker.attach(payload, route)
        rerouteDetector.attach(route)
        announcementScheduler.attach(payload, route)
        currentRoute = route

        // 初回 tick 前: lastKnown から bootstrap、駄目なら origin ベース bootstrap
        val initialLocation = locationDataSource.lastKnown()
        if (initialLocation != null) {
            tracker.onLocation(initialLocation)
        } else {
            _state.value = GuidanceState.Guiding(
                route = route,
                progress = guidanceBootstrap.fromOrigin(payload, route),
            )
        }

        // session 用 Job を作って scope の直下子としてフラットに 2 本走らせる。
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
                tracker.snapshot.collect { snapshot ->
                    if (snapshot != null) {
                        val activeRoute = currentRoute ?: return@collect
                        val decision = rerouteDetector.onSnapshot(snapshot)
                        if (decision is ExtNavRerouteDecision.Request) {
                            announcementScheduler.detach()
                            _state.value = GuidanceState.Rerouting
                            handleReroute(decision)
                        } else {
                            announcementScheduler.onSnapshot(snapshot)
                            _state.value = GuidanceState.Guiding(
                                route = activeRoute,
                                progress = snapshot.progress,
                            )
                        }
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
        rerouteDetector.detach()
        announcementScheduler.detach()
        currentRoute = null
        _state.value = GuidanceState.Idle
    }

    /** session を止めるだけで scope は破棄しない。 */
    fun release() {
        stopGuidance()
    }

    private suspend fun handleReroute(request: ExtNavRerouteDecision.Request) {
        val previousRoute = currentRoute ?: return
        val results = routeRepository.searchRoutes(
            originLatitude = request.origin.latitude,
            originLongitude = request.origin.longitude,
            destinationLatitude = request.destination.latitude,
            destinationLongitude = request.destination.longitude,
            intermediateWaypoints = request.remainingViaPoints.map { it.latitude to it.longitude },
        ).getOrThrow()
        val route = selectRerouteCandidate(results, previousRoute)
        val payload = registry.get(route.id) ?: error("reroute payload not found: ${route.id}")

        tracker.attach(payload, route)
        rerouteDetector.attach(route)
        announcementScheduler.attach(payload, route)
        currentRoute = route
        _state.value = GuidanceState.Guiding(
            route = route,
            progress = guidanceBootstrap.fromOrigin(payload, route),
        )
    }
}
```

- `locationDataSource.locationUpdates()` の GPS tick は `tracker.onLocation(...)` にだけ投入する
- Tracker が生成した `ExtNavProgressSnapshot` を `GuidanceProgress` 更新、リルート判定、音声発話、到着判定へ fan-out する
- `currentRoute` は `GuidanceState.Guiding.route` の出力元。リルート成立時は `selectRerouteCandidate(...)` で
  現在 route と同じ priority を優先し、無ければ検索結果の先頭を採用する
- 到着判定成立時は `GuidanceState.Arrived`、逸脱検知後の再探索中は `GuidanceState.Rerouting` に遷移する
- `ExtNavRerouteDetector` は §3.6、`ExtNavAnnouncementScheduler` は §3.7、
  `ExtNavGuidanceBootstrap` は §3.8 で定義する

### 3.6 `ExtNavRerouteDetector`（`internal`）

`ExtNavRerouteDetector` は `ExtNavProgressSnapshot` の連続 tick からリルート開始条件を確定する。
Tracker は 1 tick ごとの `isOffRouteCandidate` を出すだけで、連続性・cooldown・目的地直前の抑制は
Detector が持つ。

```kotlin
internal class ExtNavRerouteDetector {
    fun attach(route: RouteDetail)
    fun onSnapshot(snapshot: ExtNavProgressSnapshot): ExtNavRerouteDecision
    fun detach()
}

internal sealed interface ExtNavRerouteDecision {
    data object None : ExtNavRerouteDecision
    data class Request(
        val origin: RoutePoint,
        val destination: RoutePoint,
        val remainingViaPoints: ImmutableList<RoutePoint>,
        val currentCumulativeMeters: Double,
        val reason: ExtNavRerouteReason,
    ) : ExtNavRerouteDecision
}

internal enum class ExtNavRerouteReason {
    OffRoute,
}
```

リルート要求は以下の条件をすべて満たしたときだけ出す:

- `snapshot.isOffRouteCandidate == true` が 3 tick 連続、または 5 秒以上継続
- 最終リルート要求から 30 秒以上経過
- 案内開始 / reroute 完了から 10 秒以上経過
- `snapshot.distanceRemainingMeters > 100m`
- `snapshot.rawLocation != null`

`Request.origin` は `snapshot.rawLocation` を `RoutePoint` に変換した現在地。`destination` は現在案内中の
`RouteDetail.destination`。`remainingViaPoints` は現在案内中の `RouteDetail.intermediateWaypoints` から、
`currentCumulativeMeters` より前にある経由地を除外したもの。

### 3.7 `ExtNavAnnouncementScheduler`（`internal`）

`ExtNavAnnouncementScheduler` は GPS 位置に応じた音声発話タイミングを制御する。入力は
`ExtNavProgressSnapshot` と `RouteGuidance.guidancePoints[].phrases[]`。GPS Flow を直接 collect せず、
Tracker の projection snapshot を唯一の時刻・距離基準にする。

```kotlin
internal class ExtNavAnnouncementScheduler(
    private val voicePlayer: ExtNavVoicePlayer,
) {
    fun attach(payload: ExtNavRoutePayload, route: RouteDetail)
    fun onSnapshot(snapshot: ExtNavProgressSnapshot)
    fun detach()
}

internal interface ExtNavVoicePlayer {
    fun enqueue(announcement: ExtNavAnnouncement)
    fun clear()
}

@Immutable
internal data class ExtNavAnnouncement(
    val guidancePointIndex: Int,
    val phraseIndex: Int,
    val category: GuidanceCategory,
    val text: String,
    val ssml: String?,
)
```

`ExtNavTtsVoicePlayer` は `ExtNavVoicePlayer` の具象実装。音声合成・AudioFocus・キュー排他を持ち、
`detach()` / `stopGuidance()` / `Rerouting` / `Arrived` では `clear()` で未再生キューを破棄する。

発話済み判定は `(guidancePointIndex, phraseIndex, category)` をキーにする。`onSnapshot` は
`snapshot.currentCumulativeMeters` と attach 時に `RouteDistanceMapper` で作った `gpCumulativeOnGeometry` から、次 GP までの
距離を計算し、以下のスロットを満たした phrase を `voicePlayer.enqueue(...)` する:

| スロット | 発火条件 |
|---|---|
| 予告 | 次 GP まで 700m 以下（高速）または 300m 以下（一般道）。速度が 20m/s 以上なら +200m 先読み |
| 直前 | 次 GP まで 120m 以下。速度が 3m/s 未満なら 60m 以下まで遅延 |
| 通過 | `snapshot.currentCumulativeMeters >= gpCumulativeOnGeometry[gpIndex]` |

`Phrase` が明示的な trigger distance / audio id / ssml text を持つ場合はそれを優先する。無い場合は
`Phrase` の text / category を使って標準スロットに割り当てる。`snapshot.isOffRouteCandidate == true`、
`GuidanceState.Rerouting`、`GuidanceState.Arrived` の間は発話キューを進めない。

### 3.8 `ExtNavGuidanceBootstrap`（`internal`）

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
`null`**。UI は `nextManeuver == null` のとき TBT バナーを非表示にし、ETA / 停止ボタンは表示を継続する。

## 4. feature/map 連携仕様

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
    popScreenState()                  // Navigating → RoutePreview に戻す
    // NewRouteManager は触らない（Ready を保持して RoutePreview に戻る）
}
```

`MapScreen` への `newGuidanceState` 受け渡しは既に `MapViewModel.newGuidanceState: StateFlow<GuidanceState>` が公開されているので、Compose 側で `collectAsStateWithLifecycle()` する。

### 4.2 `MapScreen` の Navigating 分岐

`MapScreenContent` / `MapScreenBottomSheetContent` の `Navigating` 分岐は以下の状態を描画する:

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

次ターンバナー・ETA カード・レーン UI 等は `GuidanceProgress` を唯一の案内進捗入力として描画する。

### 4.3 `MapEffect` の Navigating 分岐

`MapEffect` の `Navigating` 分岐は、案内中もルートライン・目的地マーカー・自車マーカーを
表示し続ける:

```kotlin
is MapScreenState.Navigating -> {
    NavigatingEffect(
        screenState = screenState,
        guidanceState = guidanceState,  // Guiding.route をルートライン、Guiding.progress を自車マーカーに使う
        googleMap = googleMap,
    )
}
```

`NavigatingEffect` の中身:

- ルートライン: `GuidanceState.Guiding.route.geometry` を `MapPolyline` で描画
  （Preview と同じスタイル。リルート時は `Guiding.route` が置き換わる）
- 目的地マーカー: `GuidanceState.Guiding.route.destination` に `MapMarker`
- 自車マーカー: `guidanceState.progress.snappedLocation` + `progress.bearingDegrees` で
  chevron 風マーカー
- 経由地マーカー: `GuidanceState.Guiding.route.intermediateWaypoints` に `MapMarker`

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

    // Guidance
    single { ExtNavGuidanceTracker() }
    single { ExtNavRerouteDetector() }
    single<ExtNavVoicePlayer> { ExtNavTtsVoicePlayer(...) }
    single { ExtNavAnnouncementScheduler(voicePlayer = get()) }
    single { ExtNavGuidanceBootstrap() }
    single {
        NewGuidanceManager(
            registry = get(),
            tracker = get(),
            rerouteDetector = get(),
            announcementScheduler = get(),
            locationDataSource = get(),
            guidanceBootstrap = get(),
            routeRepository = get(),
        )
    }
}
```

> **scope の注意**: `NewGuidanceManager` は `single`（application scope）で握る。
> `release()` を呼んでも内部 `CoroutineScope` は cancel しないため、`MapViewModel` が
> 作り直されて再度 `startGuidance` してもインスタンスは健全に動作する。`ExtNavGuidanceTracker` /
> `ExtNavRerouteDetector` / `ExtNavAnnouncementScheduler` / `ExtNavGuidanceBootstrap` も同様に `single`
> で OK（状態は session 単位で `attach` / `detach` する設計）。

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
        │    └─ GP 累積距離テーブル事前計算 (§1.5 の RouteDistanceMapper で射影)
        ├─ ExtNavRerouteDetector.attach(route)
        ├─ ExtNavAnnouncementScheduler.attach(payload, route)
        │
        ├─ ★初期化分岐:
        │    locationDataSource.lastKnown()
        │      ├─ 取得成功 → tracker.onLocation(it)  → tracker.snapshot 更新 → Guiding(route, progress)
        │      └─ null     → guidanceBootstrap.fromOrigin(payload, route)
        │                    → _state.value = Guiding(route, bootstrapProgress) (origin 起点)
        │
        ├─ session = SupervisorJob(parent = scope.coroutineContext.job)
        ├─ scope.launch(session) { try { locationUpdates.collect { tracker.onLocation(it) } }
        │                          catch (e) { _state = Failed("location: ...") } }
        └─ scope.launch(session) { try { tracker.snapshot.collect { fanOutSnapshot(it) } }
                                   catch (e) { _state = Failed("tracker: ...") } }
   │
   ▼
[初期表示] MapScreen は Guiding(route, progress) を即時受け取り、ルートライン + TBT + 停止ボタン表示
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
   └─ snapshot.value = ExtNavProgressSnapshot(progress = GuidanceProgress(...), ...)
   │
   ▼
NewGuidanceManager snapshot fan-out
   ├─ ExtNavRerouteDetector.onSnapshot(snapshot)
   │    └─ Request なら GuidanceState.Rerouting → routeRepository.searchRoutes(...) → tracker.attach(newPayload, newRoute)
   ├─ ExtNavAnnouncementScheduler.onSnapshot(snapshot)
   │    └─ 発話スロット到達なら ExtNavVoicePlayer.enqueue(...)
   ├─ 到着判定
   │    └─ 成立なら GuidanceState.Arrived
   └─ _state = GuidanceState.Guiding(activeRoute, snapshot.progress)
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
   │    ├─ rerouteDetector.detach()
   │    ├─ announcementScheduler.detach()
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
| `RouteDistanceMapperTest` | 0m / 終端 / 中間アンカーで単調増加変換される / 中間アンカーありの場合に単純 scaleFactor と異なる GP 距離を返す |
| `ExtNavGuidanceTrackerTest` | attach → onLocation で `snapshot` が更新される / `GuidanceProgress` と内部 projection 値が整合する / 単調増加が維持される / 投影誤差が 5m 以下 / 次 GP 切り替わり |
| `ExtNavRerouteDetectorTest` | off-route candidate 3 tick 連続で `Request` / 1 tick だけなら `None` / 低精度 GPS・停止中・目的地直前は抑制 / cooldown 中は再要求しない |
| `ExtNavAnnouncementSchedulerTest` | GP までの距離スロット到達で発話 enqueue / 同じ `(gpIndex, phraseIndex, category)` は二重発話しない / off-route・rerouting・arrived 中は発話しない / 速度に応じた先読み・遅延 |
| `ExtNavGuidanceMapperTest` | 外部ライブラリのモックデータから `GuidanceManeuverInfo` / `LaneGuidance` / `DirectionSign` / `HighwayPanel` が正しく生成される |
| `ExtNavRouteRegistryTest` | `beginSession()` で旧 payload が clear される / session id を含む route id だけ取得できる |
| `NewGuidanceManagerTest` | `startGuidance` → `state = Guiding` / `stopGuidance` → `state = Idle` / `registry.get(...)` が null → `state = Failed` / snapshot fan-out で発話・リルート判定が呼ばれる / `release` 冪等 |

### 7.2 統合テスト（実機・手動 QA）

- `docs/spec/15` の Fake GPS Dev Tool でドライブシミュレーション
- 短距離（市街地）・中距離（高速混在）の 2 ルートで:
  - 自車マーカーが polyline 上に乗っているか（投影精度）
  - 次ターンバナーの距離が GPS tick に応じて減っていくか
  - GP 切り替わり時にバナーが更新されるか
  - ルートから意図的に外した GPS で `Rerouting` に入り、新 route に置き換わるか
  - GP の予告 / 直前 / 通過スロットで音声発話が 1 回ずつ鳴るか
  - 案内終了ボタンで RoutePreview に戻るか

---

## 8. 周辺コンポーネントとの接続仕様

| コンポーネント | 入力 | 出力 / 反映先 |
|---|---|---|
| `ExtNavAnnouncementScheduler` | `ExtNavProgressSnapshot`, `RouteGuidance.guidancePoints[].phrases[]`, `RouteDistanceMapper` で射影した GP 累積距離 | 発話キュー。既発話判定には `guidancePointIndex + phraseIndex + category` を使う |
| `ExtNavVoicePlayer` | `ExtNavAnnouncement` | 音声合成 / 再生キュー / AudioFocus |
| `ExtNavRerouteDetector` | `projectionErrorMeters`, `isOffRouteCandidate`, `rawLocation`, `currentCumulativeMeters` | `ExtNavRerouteDecision.Request`、`GuidanceState.Rerouting`、再探索要求 |
| `RouteRepository` | `ExtNavRerouteDecision.Request` の origin / destination / remainingViaPoints | reroute 後の `RouteDetail` と `ExtNavRoutePayload` |
| 到着判定 | `distanceRemainingMeters`, `currentCumulativeMeters`, 目的地近傍判定 | `GuidanceState.Arrived` |
| `ExtNavCameraController` | `snappedLocation`, `bearingDegrees`, `matchedSegmentIndex`, `nextManeuver` | 地図カメラの bearing / zoom / tilt |
| ForegroundService | `CurrentLocationDataSource.locationUpdates()` | バックグラウンド継続時の位置購読 |
| `ExtNavTrafficOverlay` | route path code 群、`RouteDetail.congestionSegments` | 動的渋滞セグメント、規制情報 overlay |
| `GuideImageClient.preload` | `RouteGuidance` | `DirectionSign.imageKey` / 高速パネル画像参照 |
| 速度計 UI | `rawLocation.speedMps`, `currentSpeedLimitKmh` | 現在速度・制限速度表示 |

`GuidanceProgress` は UI 表示に必要な安定モデル、`ExtNavProgressSnapshot` は周辺コンポーネント向けの
projection モデルとして分離する。周辺コンポーネントが必要な情報を `GuidanceProgress` に混ぜない。

---

## 9. 命名ポリシー

`CLAUDE.md` §厳命に従い、本ドキュメント中で:

- 事業者名: 「N 社」
- ライブラリ: 「外部ナビ API ライブラリ」
- クラス prefix: `ExtNav`
- BuildKonfig / 環境変数: `EXT_NAV_*`

外部ライブラリの実型名（`RouteGuidance` / `GuidancePoint` / `Intersection` / `GuidanceCategory` 等）は
ドメインを表す一般名なので本ドキュメント上で参照しても OK（既存 `ExtNavRouteDataSource.kt` でも
同様に参照されている）。一方で、ルート import 元のパッケージ root のドメイン名・事業者名・
製品名は本ドキュメントに書かない。

---

## 10. UI フォールバック仕様

| 状況 | 表示 / 状態 |
|---|---|
| `nextManeuver == null` | TBT バナーは非表示。ETA カード、停止ボタン、ルートライン、自車マーカーは表示継続 |
| `guidancePoints` が 0 件 | `nextManeuver = null`。残距離 / ETA / 自車位置だけを更新 |
| 初回 GPS tick 前で `lastKnown()` あり | `tracker.onLocation(lastKnown)` の結果を `Guiding(route, progress)` として公開 |
| 初回 GPS tick 前で `lastKnown()` なし | `ExtNavGuidanceBootstrap.fromOrigin(...)` の progress を `Guiding(route, progress)` として即時公開 |
| bootstrap progress 表示中 | `snappedLocation = route.origin`、`bearingDegrees = initialBearingFromGeometry(route.geometry)`。通常 tick 到着後に tracker snapshot へ置き換わる |

---

## 11. 参考リンク

- `docs/spec/18_external_nav_api_migration_plan.md` — 外部ナビ API 移行計画（正本）
- `docs/spec/19_drive_supporter_api_integration_plan.md` — 外部ライブラリ統合計画
- `docs/spec/21_ext_nav_guide_proto_and_announcement.md` — GP / 音声案内の設計
- `docs/spec/24_new_guidance_manager_design.md` — `NewGuidanceManager` 全体設計（v2）
- `docs/spec/15_fake_gps_dev_tool.md` — ドライブシミュレーション用 Fake GPS
