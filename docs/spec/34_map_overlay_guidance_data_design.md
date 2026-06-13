# 34. 発話連動地図オーバーレイのデータ設計

> **作成日:** 2026-06-13
> **ステータス:** 設計。描画 UI の詳細仕様と実装は別タスク。
> **対象:** TTS / 発話で既に使っている案内データを、地図上の信号・一時停止・踏切アイコン、
> 規制・事故 CallOut、渋滞 CallOut に接続するためのデータ取得・モデル整理・状態配布。
> **関連:** `17_callout_redesign.md`, `21_ext_nav_guide_proto_and_announcement.md`,
> `26_route_congestion_design.md`, `27_route_congestion_visualization_design.md`,
> `28_navigating_state_and_guidance_progress_design.md`,
> `33_external_nav_api_local_dependency.md`

---

## 0. この仕様の位置付け

本書は issue #126 の A/B/C を、描画実装の前段にあるデータ契約として整理する。

| ID | 表示したいもの | 本書で決めること | 本書でやらないこと |
|---|---|---|---|
| A | ルート polyline 上の信号機 / 一時停止 / 踏切アイコン | 発話 category から静的な route point event を作り、`RouteDetail` で配る | アイコンの意匠、ズーム別間引き、Marker 描画 |
| B | 規制・事故地点の CallOut | 交通情報 API 由来の incident を動的 state として配る | CallOut の見た目、タップ時詳細、API ライブラリ側の pathCode 実装 |
| C | 渋滞区間に出す時間 CallOut | 既存 `CongestionSegment` を CallOut request に射影する | `+N 分` を遅延として出すかどうかの UI 最終判断 |

既存設計と実装を再利用することを前提にする。特に CallOut 配置、渋滞区間、案内中 state、
外部ナビ API ライブラリ依存方式は、本書で作り直さない。

公開 OSS 側の文書なので、外部ナビ API 提供事業者・製品の実名、private repository の URL、
認証情報、アセット由来を特定できる情報は記載しない。必要な呼称は
「外部ナビ API ライブラリ」「N 社」「N 社のナビアプリ」に統一する。

---

## 1. 再利用する既存資産

| 資産 | 既存の場所 | 本書での扱い |
|---|---|---|
| CallOut 配置 engine | spec 17、`MapCallOutRequest` / `MapCallOutTarget` / `MapCallOutPlacementEngine` | B/C は `PointFixed` request として流す。新しい配置 engine は作らない |
| ルート候補 CallOut | `MapRoutePreviewCallOutMarkerEffect` | route 上の可動 CallOut の前例として参照する |
| 案内地点 CallOut | `MapGuidanceManeuverCallOutMarkerEffect` | A/B/C の avoidance polyline や priority 設計の前例として参照する |
| 渋滞 polyline 色分け | spec 27、`MapPolyline` / `RouteCongestionColors` | C は既存 overlay の上に CallOut を足すだけ |
| 渋滞モデル | spec 26、`RouteDetail.congestionSegments` / `CongestionSegment` | C は追加取得なしで読む |
| 渋滞音声 | spec 27 UC4 | C の文言・発話は同じ `CongestionSegment` を使う |
| 発話 category の semantic 化 | spec 21、`GuidanceRouteMapper.NOTICE_KIND_BY_CATEGORY`、`GuidanceEventDetails.notices` | A の種別判定はこの方針に合わせる |
| 案内中 state 配布 | spec 28、`ExtNavGuidanceTracker.snapshot` → `NewGuidanceManager.state` → `MapViewModel.newGuidanceState` | A/C は route の静的データとして流す。B は別 StateFlow に分ける |
| 外部ナビ API ライブラリ依存 | spec 33 | OneNavi 側は通常 AAR を読む。private checkout の変更は別 PR |

---

## 2. 全体方針

発話レーンと地図表示レーンの合流点を、次の 2 系統に分ける。

### 2.1 静的オーバーレイ

ルート探索またはリルートで `RouteDetail` が確定した時点で作り、通常の位置 tick では再計算しない。

- A: 信号機 / 一時停止 / 踏切の `RoutePointEvent`
- C: 既存 `CongestionSegment` から作る渋滞 CallOut

配布経路は既存の route state を使う。

```text
ExtNavRouteDataSource.searchRoutes()
  └ RouteDetail(pointEvents, congestionSegments)
       └ NewRouteManager / NewGuidanceManager
            └ MapViewModel.newRoutePreviewState / newGuidanceState
                 └ MapEffect
```

`GuidanceProgress` は位置スカラに限定する。A/C のような route 固定データを
`GuidanceProgress` に毎 tick コピーしない。

### 2.2 動的オーバーレイ

案内中に時間で古くなる情報は、route / progress とは別 state で配る。

- B: 規制・事故 CallOut
- 将来の最新渋滞 snapshot

配布経路は `GuidanceState.Guiding` と独立させる。

```text
ExtNavTrafficOverlay
  └ TrafficOverlayState        // 最新渋滞 snapshot

RegulationOverlay
  └ RegulationOverlayState     // 規制・事故 incident

MapViewModel
  └ MapEffect
```

これらの overlay state の失敗は案内進捗を止めない。取得に失敗した場合は最後に成功した overlay を
保持し、UI は stale であることを必要に応じて表現する。更新責務と trigger は spec 28 の
`ExtNavTrafficOverlay` / `RegulationOverlay` 契約を正とし、本書では置き換えない。

---

## 3. データ源泉

| 要素 | 主データ | 座標の作り方 | OneNavi 側の新規取得 |
|---|---|---|---|
| A: 信号機 / 一時停止 / 踏切 | 発話 category と `GuidancePoint.distanceFromStartMetres` | 外部ナビ API ライブラリ側で polyline 補間し `pointEvents` として公開 | なし。route 取得時に受ける |
| B: 規制 / 事故 | 交通情報 API の incident / regulation | API が返す地点座標を route polyline へ近接判定 | あり。案内中に polling |
| C: 渋滞 CallOut | `RouteDetail.congestionSegments` | `startPolylinePointIndex` の座標を anchor にする | なし |

### 3.1 A の負の発見

信号機 / 一時停止 / 踏切は、発話 category には出るが安定した anchor 座標を持たない。
OneNavi 側で category だけを拾っても Marker の座標は確定できない。

したがって、外部ナビ API ライブラリ側で `GuidancePoint.distanceFromStartMetres` を
`RouteGuidance.polyline` に線形補間し、`RouteGuidance.pointEvents` として公開するのを正とする。
OneNavi 側で同じ補間処理を重複実装しない。

### 3.2 B の制約

規制・事故は地点座標を持つが、route に紐付いた pathCode 群が現時点では OneNavi 側に露出していない。
理想は `listByPathCodes(pathCodes)` で route 沿いに絞ることだが、pathCode 露出までは
area 取得 + route 近接フィルタで先行する。

### 3.3 C の前提

渋滞区間は spec 26 / 27 の `CongestionSegment` で完結する。渋滞 CallOut のために
別 API を叩かない。

---

## 4. A: 信号機 / 一時停止 / 踏切アイコン

### 4.1 外部ナビ API ライブラリ側の依存事項

外部ナビ API ライブラリに次の domain を追加して公開する。

```kotlin
/** ルート上に置く地点イベントの種別。 */
enum class RoutePointEventKind {
    /** 信号機。 */
    TRAFFIC_LIGHT,

    /** 一時停止。 */
    STOP_LINE,

    /** 踏切。 */
    RAILWAY_CROSSING,
}

/**
 * ルート polyline 上へ補間済みの地点イベント。
 *
 * @property kind 地点イベントの種別。
 * @property coord 補間済み座標。
 * @property distanceFromStartMetres ルート始点からの距離。
 * @property polylinePointIndex 近傍の polyline index。
 */
data class RoutePointEvent(
    val kind: RoutePointEventKind,
    val coord: Coord,
    val distanceFromStartMetres: Double,
    val polylinePointIndex: Int,
)
```

`RouteGuidance` に `pointEvents: List<RoutePointEvent>` を default empty で追加する。
抽出元は `GuidanceCategory.TrafficLight` / `StopLine` / `RailwayCrossing` とする。

補間はライブラリ側の `GuideProtoMapper` 近辺に寄せる。すでに guidance point と polyline の
距離関係を扱う処理があるため、OneNavi 側に別の補間 helper を増やさない。

### 4.2 OneNavi 側モデル

`core/model` に中立モデルを追加し、`RouteDetail` に default empty で持たせる。

```kotlin
/** ルート上に置く地点イベントの種別。 */
enum class RoutePointEventKind {
    /** 信号機。 */
    TRAFFIC_LIGHT,

    /** 一時停止。 */
    STOP_LINE,

    /** 踏切。 */
    RAILWAY_CROSSING,
}

/**
 * ルート polyline 上へ補間済みの地点イベント。
 *
 * @param kind 地点イベントの種別
 * @param point 地図に置く座標
 * @param distanceFromStartMeters ルート始点からの距離（m）
 * @param polylinePointIndex 近傍の [RouteDetail.geometry] index
 */
@Immutable
data class RoutePointEvent(
    val kind: RoutePointEventKind,
    val point: RoutePoint,
    val distanceFromStartMeters: Double,
    val polylinePointIndex: Int,
)
```

`RouteDetail` には以下を追加する。

```kotlin
val pointEvents: ImmutableList<RoutePointEvent> = persistentListOf(),
```

`ExtNavRouteDataSource.searchRoutes()` では `buildCongestionSegments()` と同じ場所で
`buildPointEvents()` を呼び、外部ナビ API ライブラリ側の index を OneNavi の `geometry` index に
offset 補正して詰める。

### 4.3 状態配布

A は route 固定データなので、`MapViewModel` に専用 StateFlow を増やさない。

- Preview 中: `RoutePreviewState.Ready.routes[*].pointEvents`
- Guidance 中: `GuidanceState.Guiding.route.pointEvents`
- Rerouting 中: `GuidanceState.Rerouting.previousRoute.pointEvents` を必要に応じて維持

`ExtNavGuidanceTracker` は point event を所有しない。現在位置より手前の event を隠すかどうかは、
描画実装側が `GuidanceProgress.currentCumulativeMeters` と `RoutePointEvent.distanceFromStartMeters`
を組み合わせて判断する。

### 4.4 描画実装時の注意

- アイコンは clean-room で自作し、N 社のナビアプリや他社アセットをトレースしない。
- 表示密度の間引きは描画タスクで決める。データモデルからは event を落とさない。
- 複数 category が同じ距離に出た場合は、`RoutePointEventKind` ごとに stable id を作り、
  UI 側で重なり処理を行う。

---

## 5. B: 規制・事故 CallOut

### 5.1 取得戦略

第 1 段階は area 取得 + route 近接フィルタで実装する。

1. `TrafficClient.listByArea(...)` 相当で route 周辺の incident / regulation を取得する。
2. road name が取れる場合は `RouteDetail.roadNamesByDistance` で粗く絞る。
3. 各 incident 座標を route polyline に投影し、距離が閾値以内のものだけ採用する。
4. レスポンス serial が同じ場合は state 更新をスキップする。

第 2 段階は外部ナビ API ライブラリが route pathCode 群を公開した後に、
`listByPathCodes(pathCodes)` へ切り替える。

| 段階 | 取得方法 | 長所 | 制約 |
|---|---|---|---|
| 1 | area + road name + route 近接 | OneNavi 側だけで先行できる | route 外の incident が混入しうる |
| 2 | pathCode 指定 | route 沿いに絞れる | 外部ナビ API ライブラリ側の pathCode 露出が必要 |

### 5.2 OneNavi 側モデル

規制・事故は `GuidanceProgress` や最新渋滞用の `TrafficOverlayState` に混ぜず、
`RegulationOverlayState` として公開する。

```kotlin
/** 交通 incident の大分類。 */
enum class TrafficIncidentKind {
    /** 規制。 */
    REGULATION,

    /** 事故。 */
    ACCIDENT,
}

/**
 * 地図上に置く交通 incident。
 *
 * @param id stable id
 * @param kind incident の大分類
 * @param point CallOut の anchor 座標
 * @param title 表示用の主文言
 * @param cause 原因。無ければ null
 * @param placeName 地点名。無ければ null
 */
@Immutable
data class TrafficIncidentMarker(
    val id: String,
    val kind: TrafficIncidentKind,
    val point: RoutePoint,
    val title: String,
    val cause: String?,
    val placeName: String?,
)

/**
 * 案内中の規制・事故 overlay state。
 *
 * @param incidents 規制・事故 marker
 * @param serial 最後に採用した取得結果の serial
 * @param updatedAtMillis 最終成功更新時刻
 */
@Immutable
data class RegulationOverlayState(
    val incidents: ImmutableList<TrafficIncidentMarker> = persistentListOf(),
    val serial: String? = null,
    val updatedAtMillis: Long? = null,
)
```

`TrafficIncidentKind.ACCIDENT` は、専用 type が無い場合は cause category から導出する。
分類できないものは初版では表示対象から外し、`OTHER` を増やして曖昧な CallOut を出さない。

### 5.3 状態配布

責務分担は次の通り。

| 層 | 責務 |
|---|---|
| `ExtNavTrafficOverlay` | spec 28 の動的交通 overlay 契約を正本として、案内開始 / 5 分 / 5 km / reroute の trigger を管理する |
| `RegulationOverlay` | 規制・事故の取得、route 近接フィルタ、`RegulationOverlayState` の公開を担当する |
| `NewGuidanceManager` | guidance lifecycle に合わせて `ExtNavTrafficOverlay` / `RegulationOverlay` を start / stop する |
| `MapViewModel` | `regulationOverlayState` を `GuidanceState.Guiding` とは別に公開する |
| `MapEffect` | `RegulationOverlayState.incidents` から `MapCallOutRequest` を作る |

`RegulationOverlay` は `ExtNavGuidanceTracker.snapshot` を読んで現在累積距離や走行距離を判断してよい。
ただし、Tracker へ incident state を戻さない。

更新条件は spec 28 の方針に合わせる。

- 案内開始直後
- 最終成功取得から 5 分以上が経過
- 最終成功地点から 5 km 以上を走行
- reroute で route id または pathCode 群が変化

area 取得 / pathCode 取得のどちらでも、この PR では polling trigger を spec 28 から変更しない。
より短い間隔が必要になった場合は、実測負荷と API 利用条件を確認したうえで spec 28 も同時に更新する。

### 5.4 CallOut request 化

B は incident 座標に固定されるため、spec 17 の `PointFixed` を使う。

```kotlin
MapCallOutRequest(
    id = "incident-${marker.id}",
    target = MapCallOutTarget.PointFixed(marker.point),
    priority = INCIDENT_CALLOUT_PRIORITY,
    contentKey = listOf(
        marker.id,
        marker.kind.name,
        marker.title,
        marker.cause.orEmpty(),
        marker.placeName.orEmpty(),
    ).joinToString(separator = "|"),
)
```

priority は maneuver CallOut より低く、渋滞 CallOut より高い初期値にする。
規制・事故が複数密集する場合は CallOut 配置 engine の重なり判定に任せ、初版では clustering を
作らない。

---

## 6. C: 渋滞 CallOut

### 6.1 データ

C は `RouteDetail.congestionSegments` で完結する。追加取得はしない。

| 表示要素 | ソース |
|---|---|
| anchor 座標 | `route.geometry[segment.startPolylinePointIndex]` |
| 区間 | `route.geometry[startPolylinePointIndex..endPolylinePointIndex]` |
| 渋滞度 | `segment.severity` |
| 表示時間 | `segment.transitMinutes` |
| 地点名 | `segment.headPointName` / `segment.tailPointName` |

`MapPolyline` はすでに `congestionSegments` を受けて本体線上へ色 overlay を描く。
CallOut はこの overlay と同じ segment を読む。

### 6.2 `+N 分` 表記の扱い

`CongestionSegment.transitMinutes` は渋滞区間の通過予想所要時間であり、遅延差分とは限らない。
通常走行時間の確かなソースがない状態で `+N 分` を遅延として表示すると誤解を招く。

初期表示は次のどちらかを UI レビューで決める。

| 案 | 表示 | データ要件 |
|---|---|---|
| 推奨 | `渋滞 N分` | `transitMinutes` だけで可能 |
| 保留 | `+N分` | 通常走行時間との差分が必要 |

データ設計としては `transitMinutes` をそのまま渡し、ラベル文言は描画実装で決める。

### 6.3 CallOut request 化

C も route 上の固定地点に出すため `PointFixed` を使う。

```kotlin
MapCallOutRequest(
    id = "congestion-${segment.startPolylinePointIndex}-${segment.endPolylinePointIndex}",
    target = MapCallOutTarget.PointFixed(route.geometry[segment.startPolylinePointIndex]),
    priority = CONGESTION_CALLOUT_PRIORITY,
    avoidancePolylines = persistentListOf(segmentPolyline),
    contentKey = listOf(
        segment.startPolylinePointIndex,
        segment.endPolylinePointIndex,
        segment.severity.name,
        segment.transitMinutes?.toString().orEmpty(),
        segment.headPointName.orEmpty(),
    ).joinToString(separator = "|"),
)
```

priority は maneuver CallOut と incident CallOut より低くする。渋滞区間が複数ある場合は、
現在位置より先で近いものを優先する。Preview では selected route のみを対象にする。

### 6.4 状態配布

- Preview 中: `RoutePreviewState.Ready.routes[selectedIndex].congestionSegments`
- Guidance 中: `GuidanceState.Guiding.route.congestionSegments`
- Rerouting 中: `GuidanceState.Rerouting.previousRoute.congestionSegments`

将来 spec 28 の `ExtNavTrafficOverlay` が最新渋滞 segment を `TrafficOverlayState` として公開した
場合は、MapEffect 側で「動的 overlay があれば優先、無ければ `RouteDetail.congestionSegments`」の
順に読む。
`RouteDetail` の初期 snapshot は fallback として残す。

---

## 7. 実装順序

今回の PR は本書の追加だけで止める。コード実装 PR は次の順に分ける。

1. **C: 渋滞 CallOut**
   - 既存 `RouteDetail.congestionSegments` と CallOut engine だけで始められる。
   - `+N 分` 表記を使うかは UI レビューで決める。
2. **A: 信号機 / 一時停止 / 踏切アイコン**
   - 外部ナビ API ライブラリで `RouteGuidance.pointEvents` を公開する。
   - OneNavi 側で `RouteDetail.pointEvents` へ詰める。
   - Marker 描画とズーム別間引きを別 PR で実装する。
3. **B: 規制・事故 CallOut**
   - area 取得 + route 近接フィルタで先行する。
   - pathCode 露出後に `listByPathCodes` へ切り替える。
   - `RegulationOverlayState` を `GuidanceProgress` と `TrafficOverlayState` とは別に配る。

---

## 8. 外部ナビ API ライブラリ側の依存事項

| 依存 | 必要な理由 | OneNavi 側の扱い |
|---|---|---|
| `RouteGuidance.pointEvents` | A の座標確定。発話 category だけでは marker anchor が決まらない | default empty で受け、未提供なら A は表示しない |
| route pathCode 群 | B を route 沿いに精密取得するため | 未提供の間は area + route 近接フィルタ |
| incident / regulation domain の中立化 | B の `title` / `cause` / `placeName` / 座標を安定して読むため | 取得できる項目だけ `TrafficIncidentMarker` に詰める |

外部ナビ API ライブラリ自体は spec 33 の通り、通常は local file Maven repository の AAR として解決する。
OneNavi 側 PR で private checkout の構成や URL を変更しない。

---

## 9. 未解決事項

| 項目 | 状態 | 次に決める場所 |
|---|---|---|
| `+N 分` を遅延表示にするか | `transitMinutes` は通過所要時間として扱う。遅延差分ソースは未確定 | C の描画 UI PR |
| A のズーム別間引き | 全 event をデータとして保持し、描画側で密度調整する | A の Marker PR |
| B の pathCode 取得 | 外部ナビ API ライブラリ側の route DTO 露出が必要 | ライブラリ側 PR |
| B の polling 間隔 | spec 28 の案内開始 / 5 分 / 5 km / reroute trigger に合わせる。短縮する場合は spec 28 も更新する | 実装 PR と実測 |
| incident の詳細分類 | 事故は cause category から導出。分類不能は初版で表示しない | B の datasource PR |
| CallOut 同士の優先順位 | maneuver > incident > congestion を初期値にする | 描画 PR のスクショ確認 |
