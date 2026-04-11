# TTS Required Navigation Features Implementation Plan

Date: 2026-04-11
Author: Codex
Status: Draft

## 目的

`docs/note/tts-required-features.md` のうち、以下の TTS 必要機能を OneNavi に実装するための計画をまとめる。

- #1 基本ターンバイターン案内
- #2 高速道路案内
- #3 レーン案内
- #5 代替ルート提案
- #10 踏切案内
- #11 一時停止・横断歩道
- #15 道なり案内
- #16 経由地管理
- #17 ナビセッション管理

今回の方針では、#4 渋滞情報は一旦対象外とする。`PROFILE_DRIVING_TRAFFIC` は目的地付近の細道へ到達しない問題があり、ナビ本線には採用しない。`PROFILE_DRIVING` では Mapbox Directions API の交通 annotation が実質使えないため、渋滞 TTS は別途 Mapbox Traffic tileset 等を検討する後続フェーズに回す。

また、#10 踏切案内と #11 一時停止・横断歩道は、現段階では「通常踏切がある」「一時停止がある」程度の存在案内までを対象にする。横断歩道は Mapbox route response から安定取得できるか確認し、取れない場合は後続の静的 Safety DB 対象にする。開かずの踏切、渋滞しやすい踏切、事故多発踏切、歩行者注意地点などの属性付き案内は後続フェーズに回す。

---

## 結論

現段階の優先方針は以下。

1. ナビ本線のルート検索は `PROFILE_DRIVING` を継続する。
   - `PROFILE_DRIVING_TRAFFIC` は目的地付近の細道へ到達しない問題があり、到着判定を壊すため本線には使わない。
   - 関連 issue: https://github.com/mapbox/mapbox-navigation-android/issues/7947
2. 渋滞 TTS は今回の実装対象から外す。
   - Mapbox Directions API の `annotation.congestion` / `annotation.congestion_numeric` / `incidents` / `closures` は `mapbox/driving-traffic` 前提であるため。
   - `PROFILE_DRIVING` で `congestion` を要求しても、公式仕様上は `unknown` 扱いになり、TTS の根拠にはしない。
3. TTS 実装は、`VoiceInstructions.announcement()` の文字列変換から脱却する。
   - Mapbox の `RouteProgress` / `LegStep` / `StepIntersection` / `BannerInstructions` / `VoiceInstructions` を材料に、アプリ側で `GuidanceEvent` を生成する。
4. #10/#11 は Mapbox の intersection flags を一次ソースにする。
   - `railway_crossing`
   - `stop_sign`
   - 横断歩道は Mapbox Directions API の標準 step intersection だけでは安定取得できないため、初期版では実装対象を「一時停止」と「踏切」に寄せる。
   - 横断歩道 TTS は API 上の取得可否を追加検証し、取得できない場合は後続の静的 Safety DB 対象とする。

---

## レビュー対応方針

レビュー指摘のうち、以下は反映する。

- `GuidanceContext` の具体定義を追加する。
- `GuidancePriority` と `SpeechOrchestrator` のキュー方針を具体化する。
- 連続案内、距離段階、道なり発話タイミングを明確化する。
- Phase 1 を小さく分割する。
- `RerouteStateObserver` は SDK バージョン確認つきの optional 扱いにし、安定策として `RoutesObserver` を主経路にする。
- fixture 取得方法を Phase 0 に含める。
- `TtsEngine` / `AudioFocusManager` の分離を Phase 1 に含める。
- `GuidanceEvent` を TTS だけでなく UI にも流せる設計にする。

以下は反映しない、または限定的に扱う。

- #4 渋滞情報を今回実装対象へ戻す指摘は採用しない。ユーザー方針として渋滞案内は一旦置くため。
- `PROFILE_DRIVING` でも `congestion_numeric` が返る場合がある、という前提は採用しない。公式仕様上、交通 congestion は `driving-traffic` 用であり、`driving` では信頼できる交通情報として扱わない。
- #10/#11 に外部 DB を必須化する指摘は初期版では採用しない。Mapbox intersection flags で存在案内できる範囲を先に実装し、属性付き案内と横断歩道の安定対応だけ後続 DB 対象にする。

---

## 現状整理

### 現在使っている Mapbox SDK 機能

`GuidanceSessionManager` は以下の observer を登録済み。

- `RouteProgressObserver`
- `VoiceInstructionsObserver`
- `BannerInstructionsObserver`
- `OffRouteObserver`
- `ArrivalObserver`

該当箇所:

- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/GuidanceSessionManager.kt`

現在の役割は以下。

| Observer | 現在の用途 | 問題 |
|---|---|---|
| `RouteProgressObserver` | UI state 更新、カメラ更新 | 発話イベント生成には使えていない |
| `VoiceInstructionsObserver` | `announcement()` を TTS 再生 | Mapbox の英語/不自然な発話文字列に依存している |
| `BannerInstructionsObserver` | UI の maneuver text 更新 | 方面名、レーン、看板情報を TTS に使えていない |
| `OffRouteObserver` | UI の off-route flag 更新 | 「ルートから外れました」発話に繋げていない |
| `ArrivalObserver` | 経由地/目的地到着 TTS | 目的地タイプ別フレーズには未対応 |

### 現在の TTS 生成の問題

`JapaneseAnnouncementGenerator` は `VoiceInstructions.announcement()` の文字列から maneuver type / modifier を推定している。

これは初期実装としては動くが、次の情報を使えない。

- `LegStep.maneuver().type()`
- `LegStep.maneuver().modifier()`
- `LegStep.maneuver().bearingBefore()/bearingAfter()`
- `LegStep.intersections()`
- `StepIntersection.lanes()`
- `StepIntersection.trafficSignal()`
- `StepIntersection.stopSign()`
- `StepIntersection.railwayCrossing()`
- `BannerInstructions.primary()/secondary()/sub()`
- route leg index / waypoint index

本実装では、`VoiceInstructions` は「発話タイミングの参考」程度に留め、発話内容はアプリ側の `GuidanceEvent` から生成する。

### 現在の RouteOptions

`MapboxNavigationRouteDataSource` は `RouteOptions.builder()` に以下を指定している。

- `applyDefaultNavigationOptions(DirectionsCriteria.PROFILE_DRIVING)`
- `applyLanguageAndVoiceUnitOptions(context)`
- `coordinatesList(...)`
- `alternatives(true)`

この方針は維持する。

ただし、実装時には実ルートレスポンスに以下が含まれているかをテストで確認する。

- `steps`
- `voiceInstructions`
- `bannerInstructions`
- `intersections`
- `intersections.lanes`
- `intersections.traffic_signal`
- `intersections.stop_sign`
- `intersections.railway_crossing`
- `annotation.congestion` / `annotation.congestion_numeric` が `PROFILE_DRIVING` では実用値にならないこと

不足する場合は、`applyDefaultNavigationOptions()` に任せず、必要な `RouteOptions` を明示する。

### `PROFILE_DRIVING` と渋滞情報

`PROFILE_DRIVING` では、渋滞 TTS の根拠にできる交通情報は得られない前提で設計する。

Mapbox Directions API の仕様では、交通情報に関わる以下は `mapbox/driving-traffic` 用である。

- `annotation.congestion`
- `annotation.congestion_numeric`
- `incidents`
- `closures`
- `duration_typical`
- `weight_typical`

`PROFILE_DRIVING` の route response で `annotation.congestion` が存在しても、`unknown` の可能性が高く、渋滞発話の根拠にはしない。

そのため今回の Phase 0 では、`PROFILE_DRIVING` で congestion 系の実用値が取れるかを確認するだけに留める。実装対象には入れない。

---

## 公式 SDK / API で使える仕組み

### RouteProgress

Navigation SDK の `RouteProgressObserver` で、現在のナビ進捗を受け取れる。

主な用途:

- 現在の route / leg / step
- 現在 step の残距離
- 現在 leg index
- route 全体の残距離、残時間
- off-route / stale location などの状態

使う機能:

- #1 基本ターンバイターン
- #5 リルート後の状態更新
- #15 道なり案内
- #16 経由地管理
- #17 ナビセッション管理

### LegStep / StepManeuver

Directions API の `steps=true` で route leg に step が含まれる。

主な用途:

- `maneuver.type`
- `maneuver.modifier`
- `maneuver.bearing_before`
- `maneuver.bearing_after`
- `maneuver.location`
- step distance / duration
- road name / ref

使う機能:

- #1 右左折、直進、Uターン、斜め方向
- #2 on ramp / off ramp / fork / merge / toll
- #15 道なり

### StepIntersection

Directions API の step には `intersections` が含まれる。

主な用途:

- `traffic_signal`
- `stop_sign`
- `railway_crossing`
- `lanes`
- `classes`
- `toll_collection`
- `bearings`
- `entry`
- `geometry_index`

使う機能:

- #1 信号機案内
- #2 高速道路 / 有料道路 / 料金所 / 合流補助
- #3 レーン案内
- #10 踏切案内
- #11 一時停止案内

### BannerInstructions

`BannerInstructionsObserver` で、視覚案内用の情報を受け取れる。

主な用途:

- primary / secondary の text
- components
- maneuver type / modifier
- 方面名
- 出口番号
- 道路番号 shield
- lane component

使う機能:

- #1 交差点名、道路名補助
- #2 JCT / IC / 出口 / 方面案内
- #3 レーン案内補助

### VoiceInstructions

`VoiceInstructionsObserver` で Mapbox が想定する発話タイミングを受け取れる。

主な用途:

- `distanceAlongGeometry`
- 発話タイミングの参考

本実装では、`announcement()` の本文は基本的に採用しない。

### GuidanceContext

各 extractor に渡す入力は `GuidanceContext` に集約する。

```kotlin
data class GuidanceContext(
    val routeId: String,
    val routeProgress: RouteProgress,
    val currentLegIndex: Int,
    val currentStepIndex: Int,
    val currentStep: LegStep?,
    val nextStep: LegStep?,
    val upcomingSteps: List<UpcomingStepContext>,
    val upcomingIntersections: List<UpcomingIntersectionContext>,
    val currentRoadKind: RoadKind,
    val previousRoadKind: RoadKind?,
    val lastVoiceInstruction: VoiceInstructions?,
    val lastBannerInstruction: BannerInstructions?,
    val sessionState: GuidanceSessionState,
    val waypointKinds: List<DestinationKind>,
    val speechHistory: GuidanceSpeechHistory,
)

data class UpcomingStepContext(
    val legIndex: Int,
    val stepIndex: Int,
    val step: LegStep,
    val distanceFromCurrentMeters: Double,
)

data class UpcomingIntersectionContext(
    val legIndex: Int,
    val stepIndex: Int,
    val intersectionIndex: Int,
    val geometryIndex: Int?,
    val distanceFromCurrentMeters: Double,
    val intersection: StepIntersection,
)
```

`GuidanceContextBuilder` は `RouteProgress` を source of truth にして、必要に応じて直近の `VoiceInstructions` / `BannerInstructions` を補助情報として合成する。

重要な制約:

- `speechHistory` は extractor が直接更新しない。
- extractor は発話候補の `GuidanceEvent` だけを返す。
- 最終的な発話可否、優先度、重複抑制、連結は `GuidanceCoordinator` / `SpeechOrchestrator` が決める。

### OffRoute / Reroute / RoutesObserver

Navigation SDK は off-route detection と reroute を内蔵している。

使う機能:

- `OffRouteObserver`
- `RoutesObserver`
- `RerouteStateObserver` は SDK バージョンで安定利用できる場合のみ補助的に使う

用途:

- #5 ルートから外れました
- #5 新しいルートが見つかりました
- #5 新しいルートで案内します

現状 `RouteManager` は `RoutesObserver` を持っているが、更新理由や旧ルートとの差分は扱っていない。

### ArrivalObserver

Navigation SDK は waypoint arrival / final destination arrival を observer で通知できる。

使う機能:

- #16 経由地付近です
- #17 目的地付近です
- #17 お疲れ様でした

現状は固定文言のみ。目的地タイプ別差し替えは未対応。

---

## 機能別実装計画

## #1 基本ターンバイターン案内

### SDK で足りるか

概ね足りる。

使う情報:

- `RouteProgress.currentLegProgress`
- `RouteProgress.currentStepProgress`
- `LegStep.maneuver().type()`
- `LegStep.maneuver().modifier()`
- `LegStep.maneuver().bearingBefore()/bearingAfter()`
- `LegStep.intersections()`
- `StepIntersection.trafficSignal()`
- `VoiceInstructions.distanceAlongGeometry()`

### 実装内容

`RouteProgress` から次の案内地点を `GuidePoint` として抽出する。

```kotlin
data class GuidePoint(
    val legIndex: Int,
    val stepIndex: Int,
    val distanceMeters: Double,
    val maneuverType: ManeuverType,
    val direction: GuideDirection,
    val roadName: String?,
    val intersectionName: String?,
    val signalOrdinal: Int?,
)
```

`JapaneseAnnouncementGenerator` は `VoiceInstructions` ではなく、`GuidanceEvent.Turn` を受け取る。

```kotlin
data class TurnGuideEvent(
    val distanceBucket: DistanceBucket,
    val target: TurnTarget,
    val direction: GuideDirection,
    val timing: TimingPhrase,
)
```

### 距離段階

距離案内は連続値をそのまま読まず、固定 bucket に丸める。

```kotlin
enum class DistanceBucket(val meters: Int, val phrase: String) {
    M50(50, "およそ50m先"),
    M100(100, "およそ100m先"),
    M200(200, "およそ200m先"),
    M300(300, "およそ300m先"),
    M400(400, "およそ400m先"),
    M500(500, "およそ500m先"),
    M600(600, "およそ600m先"),
    M700(700, "およそ700m先"),
    M800(800, "およそ800m先"),
    M900(900, "およそ900m先"),
    KM1(1_000, "およそ1km先"),
    KM2(2_000, "およそ2km先"),
    KM3(3_000, "およそ3km先"),
    KM4(4_000, "およそ4km先"),
    KM5(5_000, "およそ5km先"),
    KM10(10_000, "およそ10km先"),
}
```

発話 trigger は道路種別で変える。

| 道路種別 | 遠距離予告 | 中距離 | 直前 |
|---|---:|---:|---:|
| 一般道 | 300m | 100m | 50m / まもなく |
| 高速道路 | 2km | 1km | 300m / まもなく |
| 有料道路・自動車専用道 | 1km | 500m | 200m / まもなく |

`DistanceBucket` は「読み上げ文言」、`DistanceBasedTrigger` は「いつ発話するか」を担当し、責務を分ける。

### 連続案内

連続案内は `TurnGuideExtractor` 単体ではなく、`GuidanceCoordinator` が組み立てる。

処理:

1. `TurnGuideExtractor` が現在の turn event を返す。
2. `GuidanceContext.upcomingSteps` から次の有効 guide point を見る。
3. 現在 guide point から次 guide point までが 300m 以内なら `LinkedTurnGuideEvent` に昇格する。
4. `JapaneseGuidancePhraseComposer` が連結用 direction phrase と文末 phrase を使い分ける。

```kotlin
data class LinkedTurnGuideEvent(
    val first: TurnGuideEvent,
    val second: TurnGuideEvent,
    val connector: TurnConnector,
) : GuidanceEvent
```

発話例:

- `まもなく、右方向、その先、左方向です。`
- `右折後、左方向です。`
- `交差点を通過後、右方向です。`

連続案内は長くなりやすいため、`SpeechOrchestrator` は同じタイミングの lane / safety より turn 連結を優先する。

### 信号機案内

Mapbox の `traffic_signal` を使う。

処理:

1. 現在 step から次 maneuver までの `intersections` を走査する。
2. `trafficSignal == true` の intersection を数える。
3. maneuver 地点側に信号がある場合は `この信号を` を優先する。
4. 前方に複数信号があり、次 maneuver が 2 番目または 3 番目なら `2つ目の信号を` / `3つ目の信号を` を使う。
5. 信号情報がない場合は距離案内へフォールバックする。

注意:

- `traffic_signal == null` は「信号なし」ではなく「不明」と扱う。
- 信号案内は誤案内が危険なので、確度が低い場合は出さない。

### 外部データ

初期版では不要。

将来カバレッジを上げる場合:

- OSM の `highway=traffic_signals` を Geofabrik Japan extract から事前抽出する。
- アプリ内で Overpass API は叩かない。

---

## #2 高速道路案内

### SDK で足りるか

IC / JCT / 入口 / 出口 / 分岐 / 合流 / 料金所は概ね足りる。

SA/PA は不足するため、初期版では以下のどちらかにする。

1. route step / banner に出る範囲だけ案内する
2. SA/PA 案内は後続フェーズへ回す

今回の計画では、SA/PA 静的 DB は後続フェーズとする。

### 使う情報

- `maneuver.type == "on ramp"`
- `maneuver.type == "off ramp"`
- `maneuver.type == "fork"`
- `maneuver.type == "merge"`
- `StepIntersection.classes()`
- `StepIntersection.tollCollection()`
- `BannerInstructions.primary/secondary.components`
- `LegStep.name()`
- `LegStep.ref()`

### 実装内容

現在の `detectHighwayInfo()` を `HighwayGuideExtractor` として独立させる。

```kotlin
class HighwayGuideExtractor {
    fun extract(context: GuidanceContext): List<GuidanceEvent.Highway>
}
```

検出するイベント:

- 高速入口
- 高速出口
- 有料道路入口
- 有料道路出口
- 料金所
- 分岐
- 連続分岐
- 合流
- 一般道 / 高速道切替
- 有料区間進入
- 自動車専用道進入

### 一般道 / 高速道切替

`StepIntersection.classes()` と step の連続状態から道路種別を推定する。

実装案:

```kotlin
enum class RoadKind {
    LOCAL,
    HIGHWAY,
    TOLL,
    MOTORWAY,
    FERRY,
    UNKNOWN,
}
```

前回の `RoadKind` と現在の `RoadKind` が変わった時に、以下を発話する。

- 一般道のルートに切り替わりました
- 高速道のルートに切り替わりました

### 外部データ

初期版では不要。

後続フェーズ:

- SA/PA: OSM の `highway=services` / `highway=rest_area` を Geofabrik Japan extract から事前抽出する。
- 施設情報: OSM tags から `toilets` / `fuel` / `restaurant` / `charging_station` などを抽出する。

---

## #3 レーン案内

### SDK で足りるか

主要道路・高速道路では概ね足りる。

Mapbox Directions API の `intersections[].lanes` を使う。

### 使う情報

- `StepIntersection.lanes()`
- lane indications
- lane valid
- lane active
- lane valid indication
- `BannerInstructions.sub` の lane component

### 実装内容

`LaneGuideExtractor` を作る。

```kotlin
class LaneGuideExtractor {
    fun extract(context: GuidanceContext): GuidanceEvent.Lane?
}
```

内部では `LaneRecommendation` を作る。

```kotlin
data class LaneRecommendation(
    val totalLaneCount: Int,
    val recommendedLaneIndexesFromLeft: List<Int>,
    val recommendedLaneIndexesFromRight: List<Int>,
    val action: LaneAction,
)
```

### 発話パターン

初期版で対応する表現:

- 右側の車線をお進みください
- 左側の車線をお進みください
- 右から N 番目の車線をお進みください
- 左から N 番目の車線をお進みください
- 右側の車線にお入りください
- 左側の車線にお入りください
- 右側 / 左側の車線が減ります

### 車線変更と維持の判定

現在車線は SDK から高精度には取れない前提で始める。

そのため初期版は以下。

- 進行方向に必要な推奨車線が複数ある場合: `お進みください`
- 推奨車線が片側に強く寄る場合: `お入りください` は控えめに使う
- 高速出口 / 分岐直前: `お早めに左側の車線をお進みください` などを優先

誤った車線変更指示は危険なので、初期版では「車線をお進みください」中心にする。

### 外部データ

不要。

カバレッジ不足は Mapbox のデータ品質に依存する。

---

## #5 代替ルート提案

### SDK で足りるか

オフルート検出とリルートは SDK に組み込み済み。

使う情報:

- `OffRouteObserver`
- `RoutesObserver`
- `MapboxNavigation.setRerouteEnabled(...)`
- `RerouteStateObserver` は SDK バージョンで利用可否と安定性を確認できた場合のみ使う

### 実装内容

現状:

- `OffRouteObserver` は UI state を更新するだけ。
- `RouteManager` は `RoutesObserver` でルート一覧を受けるだけ。

必要な追加:

1. off-route 状態の立ち上がりで `ルートから外れました。` を発話する。
2. `RoutesObserver` で旧 primary route と新 primary route を比較する。
3. off-route 中に primary route id が変わった場合は reroute 成功として扱う。
4. 成功時に `新しいルートが見つかりました。新しいルートで案内します。` を発話する。
5. `RerouteStateObserver` は現 SDK (`3.21.0-rc.1`) で API 安定性を確認できた場合だけ、reroute 開始 / 失敗の補助 signal として使う。

安定性の優先順位:

1. `RoutesObserver` による route id 変化
2. `OffRouteObserver` の状態変化
3. `RerouteStateObserver` の状態変化

`RerouteStateObserver` に実装を強く依存させない。

### プロアクティブリルート

渋滞案内を今回対象外にするため、交通起因の「今より5分早いルート」は今回やらない。

今回やる範囲:

- オフルート時の自動リルート
- route refresh / alternatives による route 更新の検知
- 発話は「新しいルートが見つかりました」まで

時間短縮案内は後続フェーズ。

### 外部データ

不要。

---

## #10 踏切案内

### SDK で足りるか

通常踏切の存在案内なら一部足りる。

使う情報:

- `StepIntersection.railwayCrossing()`

### 実装内容

`SafetyGuideExtractor` を作り、route progress の前方一定距離を調べる。

```kotlin
class SafetyGuideExtractor {
    fun extract(context: GuidanceContext): List<GuidanceEvent.Safety>
}
```

検出:

- 前方 intersection に `railwayCrossing == true` がある

発話:

- `まもなく、踏切です。`
- 距離がある場合は `この先、踏切です。`

### 発話タイミング

初期値:

- 一般道: 150m 前
- 低速時: 80m 前
- 高速道路上では原則抑制

### 重複抑制

同じ intersection / geometry index に対して一度だけ発話する。

```kotlin
data class SpokenGuideKey(
    val category: GuideCategory,
    val legIndex: Int,
    val stepIndex: Int,
    val geometryIndex: Int?,
)
```

### 外部データ

初期版では不要。

後続フェーズ:

- OSM `railway=level_crossing`
- 国交省の踏切道安全通行カルテ系データ

属性付きフレーズ:

- 開かずの踏切です
- 渋滞しやすい踏切です
- 事故多発踏切です

これらは Mapbox flag だけでは出さない。

---

## #11 一時停止・横断歩道

### SDK で足りるか

一時停止は一部足りる。

横断歩道と歩行者注意地点は、Mapbox Directions API の通常 route step だけでは安定実装できない可能性が高い。

今回の対象:

- 一時停止です
- 一時停止が連続します

横断歩道案内は、API で取得可能か追加検証後に判断する。

### 使う情報

- `StepIntersection.stopSign()`

### 実装内容

`SafetyGuideExtractor` で `stopSign == true` を検出する。

発話:

- `まもなく、一時停止です。`
- 一定距離内に複数ある場合: `一時停止が連続します。`

### 連続一時停止判定

初期値:

- 連続判定距離: 200m
- 件数: 2 件以上

処理:

1. 前方 300m の intersections を走査する。
2. `stopSign == true` を収集する。
3. 2 件以上あれば `一時停止が連続します。`
4. 1 件なら `一時停止です。`

### 横断歩道

初期版では実装しない、または feature flag の下で検証する。

理由:

- `StepIntersection` に横断歩道 flag が標準で安定提供される前提を置けない。
- 誤案内を避ける必要がある。

後続フェーズ:

- OSM の `highway=crossing`
- 自治体オープンデータ
- 独自 Safety DB

### 外部データ

初期版では不要。

後続フェーズで横断歩道 / 歩行者注意をやる場合は必要。

---

## #15 道なり案内

### SDK で足りるか

足りる。

使う情報:

- `RouteProgress`
- current step remaining distance
- upcoming steps
- next meaningful maneuver
- `maneuver.type == "continue"` / `"new name"`

### 実装内容

`AlongRoadGuideExtractor` を作る。

```kotlin
class AlongRoadGuideExtractor {
    fun extract(context: GuidanceContext): GuidanceEvent.AlongRoad?
}
```

判定:

- 次の有効な turn / fork / merge / ramp / arrive までの距離が長い
- 直前の turn / fork / merge / ramp が完了している
- step 遷移後 3 秒以内、または次 guide point までの距離が初めて 2km / 5km bucket に入った
- 直近 60 秒以内に道なり案内を出していない
- off-route ではない

発話:

- 5km 未満: `しばらく道なりです。`
- 5km 以上: `5km以上道なりです。`

発話タイミング:

1. `RouteProgress.currentStepProgress.stepIndex` が変わった時に判定する。
2. 完了した step が turn / fork / merge / ramp で、次の有効 guide point まで 2km 以上ある場合に候補を出す。
3. 5km 以上なら `5km以上道なりです。` を優先する。
4. 2km 以上 5km 未満なら `しばらく道なりです。` を出す。
5. `VoiceInstructionsObserver` ではなく `RouteProgressObserver` 起点で処理する。

### 外部データ

不要。

---

## #16 経由地管理

### SDK で足りるか

複数経由地ルート、leg progression、経由地到着は SDK で足りる。

目的地タイプ別フレーズはアプリ側の検索結果 metadata が必要。

### 現状

`HomeMapViewModel` は `SearchResultItem` を `RouteWaypoint.Place` に変換する時、以下だけを保持している。

- name
- latitude
- longitude

しかし `SearchResultItem` には以下がある。

- `primaryType`
- `primaryTypeDisplayName`
- `types`

目的地タイプ別フレーズにはこれが必要。

### 実装内容

`RouteWaypoint.Place` を拡張する。

```kotlin
data class Place(
    val name: String,
    override val latitude: Double,
    override val longitude: Double,
    val primaryType: String?,
    val types: List<String>,
) : RouteWaypoint
```

目的地タイプ分類:

```kotlin
enum class DestinationKind {
    DESTINATION,
    STATION_ENTRANCE,
    BUS_STOP,
    AIRPORT,
    FERRY_TERMINAL,
    WAYPOINT,
}
```

Google Places の `primaryType/types` から分類する。

例:

- `train_station`, `subway_station`, `transit_station` -> station
- `bus_station` -> bus stop
- `airport` -> airport
- `ferry_terminal` -> ferry

### 発話

経由地:

- `経由地付近です。`

最終目的地:

- `目的地付近です。`
- `駅入口付近です。`
- `バス停付近です。`
- `空港付近です。`
- `フェリー乗り場付近です。`

### SDK 連携

使う observer:

- `ArrivalObserver.onWaypointArrival(...)`
- `ArrivalObserver.onFinalDestinationArrival(...)`
- `MapboxNavigation.navigateNextRouteLeg(...)`

### 外部データ

不要。

Google Places の検索結果 metadata を保持すればよい。

---

## #17 ナビセッション管理

### SDK で足りるか

開始 / 停止 / 到着 / ForegroundService は SDK と現実装で概ね足りる。

アプリ側で必要なもの:

- 案内停止 / 再開状態
- バックグラウンド中断タイマー
- 発話 enable / mute state
- セッションイベントの重複抑制

### 実装内容

`GuidanceSessionManager` から TTS 直接呼び出しを減らし、`SessionGuideEvent` を発行する。

```kotlin
sealed interface SessionGuideEvent : GuidanceEvent {
    data object Started : SessionGuideEvent
    data object Stopped : SessionGuideEvent
    data object Resumed : SessionGuideEvent
    data object SuspendedSoon : SessionGuideEvent
    data object Suspended : SessionGuideEvent
    data object GuidanceMuted : SessionGuideEvent
}
```

発話:

- `音声案内を開始します。実際の交通規制に従って走行してください。`
- `音声案内を継続します。`
- `あと5分で、音声案内を中断します。`
- `一定時間が経過したため、音声案内を中断します。`
- `案内停止中です。`
- `目的地付近です。お疲れ様でした。`

### バックグラウンド中断

初期版では実装しなくてもよい。

実装する場合:

- アプリがバックグラウンドに入った時刻を記録
- foreground service 中はナビ継続
- ユーザー設定で「バックグラウンド案内を継続」を持つ
- タイムアウト前に `あと5分で...`
- タイムアウト到達で `一定時間が経過したため...`

### 外部データ

不要。

---

## 推奨アーキテクチャ

## 1. `GuidanceSessionManager` の責務を減らす

現状は以下が 1 クラスに混ざっている。

- Mapbox observer 登録
- UI state 更新
- TTS 初期化
- TTS 再生
- 日本語文言生成
- route step 抽出
- 高速道路情報推定
- 到着処理

本実装では、最低限以下に分ける。

```text
GuidanceSessionManager
  - Mapbox observer lifecycle
  - RouteProgress / Banner / Voice / OffRoute / Arrival を受ける
  - GuidanceCoordinator に渡す

GuidanceCoordinator
  - 最新 context を保持
  - extractors を呼ぶ
  - 発話すべき GuidanceEvent を決める

GuidanceContextBuilder
  - RouteProgress から現在地・前方 step・intersection を構築

GuideEventExtractor
  - TurnGuideExtractor
  - HighwayGuideExtractor
  - LaneGuideExtractor
  - SafetyGuideExtractor
  - AlongRoadGuideExtractor
  - RerouteGuideExtractor
  - WaypointGuideExtractor
  - SessionGuideExtractor

JapaneseGuidancePhraseComposer
  - GuidanceEvent から日本語フレーズへ変換

SpeechOrchestrator
  - 優先度
  - 重複抑制
  - cooldown
  - TTS engine 呼び出し

TtsEngine
  - Android TextToSpeech 実装
  - 将来の Google Cloud TTS 実装

AudioFocusManager
  - AudioFocusRequest の取得 / 解放
```

## 2. `GuidanceEvent` を source of truth にする

発話本文を直接組み立てながら判定しない。

まずイベントを作る。

```kotlin
sealed interface GuidanceEvent {
    val id: GuidanceEventId
    val priority: GuidancePriority
    val distanceMeters: Double?
}
```

その後、日本語 phrase に変換する。

こうすると、UI 表示、ログ、テスト、TTS が同じイベントを共有できる。

`GuidanceCoordinator` は `SharedFlow<GuidanceEvent>` を公開する。

```kotlin
interface GuidanceCoordinator {
    val events: SharedFlow<GuidanceEvent>
}
```

購読側:

- TTS: `SpeechOrchestrator`
- UI: maneuver panel / lane panel / safety banner
- debug logger: event dump

TTS と UI が別々に Mapbox data を解釈しないようにする。

## 3. 重複抑制を必ず入れる

ナビTTSでは、毎秒同じ発話が出るのが一番まずい。

必要な state:

```kotlin
class GuidanceSpeechHistory {
    fun hasSpoken(key: SpokenGuideKey): Boolean
    fun markSpoken(key: SpokenGuideKey)
    fun resetForNewRoute(routeId: String)
}
```

重複抑制キー:

- route id
- leg index
- step index
- geometry index
- event category
- distance bucket

## 4. 優先度と TTS キュー

`SpeechOrchestrator` は `GuidanceEvent` を受け取り、優先度、重複抑制、cooldown、キュー制御をまとめて判断する。

```kotlin
enum class GuidancePriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
}
```

| 優先度 | 主なカテゴリ | 挙動 |
|---|---|---|
| `CRITICAL` | 到着、リルート成功、セッション停止 | 現在発話を中断して即時再生 |
| `HIGH` | 直前ターン、直前分岐、高速出口、重大安全警告 | `LOW` / `NORMAL` を中断。`CRITICAL` は中断しない |
| `NORMAL` | 距離予告ターン、レーン案内、道なり | キューに追加。古い同カテゴリ候補は置き換える |
| `LOW` | 補足情報、遠距離予告、検証段階の安全案内 | キューが空なら再生。混雑時は破棄可能 |

Android `TextToSpeech` への渡し方:

- `CRITICAL` / 割り込みが必要な `HIGH`: `QUEUE_FLUSH`
- それ以外: `QUEUE_ADD`
- Cloud TTS 移行後も同じ policy を使う

同時発生時の基本方針:

1. 直前ターン案内を最優先する。
2. 踏切 / 一時停止が直前ターンと同時に来た場合は、安全案内を短く前置きできる時だけ連結する。
3. レーン案内はターン案内の直前 10 秒以内なら破棄または統合する。
4. 道なり案内は他カテゴリがあれば破棄する。

## 5. TTS エンジン抽象化

現状は Android `TextToSpeech` だけだが、Google Cloud TTS を後で入れる前提で interface を切る。

```kotlin
interface TtsEngine {
    val isReady: StateFlow<Boolean>
    suspend fun speak(text: String, utteranceId: String)
    fun stop()
    fun shutdown()
}
```

初期実装:

- `AndroidTtsEngine`
- `AudioFocusManager`
- `SpeechOrchestrator`

後続実装:

- `CloudTtsEngine`
- local audio cache
- offline fallback

## 6. ミュート状態

#17 の案内停止 / 再開に関わるため、mute state は `GuidanceSessionSettings` として分ける。

初期方針:

- セッション中の mute は即時反映する。
- アプリ再起動後も維持するかは設定仕様に依存する。
- 実装するなら DataStore に保存する。
- 最小実装ではセッションごとにリセットし、永続化は後続でよい。

---

## 実装フェーズ

## Phase 0: データ取得確認

目的:

`PROFILE_DRIVING` のまま必要フィールドが取得できるか確認する。

作業:

1. debug log に route response の step / intersection summary を出す。
2. 以下を確認する。
   - lanes 件数
   - `trafficSignal == true` 件数
   - `stopSign == true` 件数
   - `railwayCrossing == true` 件数
   - `tollCollection != null` 件数
   - `classes` の内容
   - 横断歩道に相当する構造化データがあるか
   - `PROFILE_DRIVING` の congestion 系 annotation が実用値にならないこと
3. debug build 限定で route response JSON を保存できる仕組みを作る。
4. 不足する場合は `RouteOptions` を明示指定する。

成果物:

- `RouteDebugDumper`
- `RouteFixtureWriter`
- debug build 限定ログ
- fixture JSON: `core/navigation/src/androidUnitTest/resources/routes/*.json`

## Phase 1a: GuidanceEvent モデル

目的:

発話を文字列ではなく構造化イベントとして扱うためのモデルを作る。

作業:

1. `GuidanceEvent` sealed interface を作る。
2. `GuidancePriority` を作る。
3. `SpokenGuideKey` を作る。
4. `GuidanceSpeechHistory` を作る。
5. `DistanceBucket` / `GuideDirection` / `RoadKind` の enum を作る。

成果物:

- 既存挙動はまだ変えない
- unit test で enum / key / history を確認

## Phase 1b: GuidanceContext

目的:

`RouteProgress` から extractor が使う文脈を作る。

作業:

1. `GuidanceContext` を作る。
2. `GuidanceContextBuilder` を作る。
3. `UpcomingStepContext` を作る。
4. `UpcomingIntersectionContext` を作る。
5. `RouteDebugDumper` と同じ抽出ロジックを共有する。

成果物:

- `RouteProgress` から current / next / upcoming を安定取得できる

## Phase 1c: SpeechOrchestrator / TtsEngine

目的:

`GuidanceSessionManager` から TTS / AudioFocus / queue 責務を切り出す。

作業:

1. `TtsEngine` interface を作る。
2. `AndroidTtsEngine` を作る。
3. `AudioFocusManager` を作る。
4. `SpeechOrchestrator` を作る。
5. `QUEUE_FLUSH` / `QUEUE_ADD` の policy を実装する。

成果物:

- 既存 Android TTS の動作維持
- 優先度付きキューの最小実装

## Phase 1d: Phrase Composer

目的:

既存テンプレートを `GuidanceEvent -> Phrase` へ移行する。

作業:

1. `JapaneseGuidancePhraseComposer` を作る。
2. #16 到着フレーズを移行する。
3. #17 開始フレーズを移行する。
4. 既存 `JapaneseAnnouncementGenerator` は fallback に留める。

成果物:

- `音声案内を開始します。実際の交通規制に従って走行してください。`
- `経由地付近です。`
- `目的地付近です。お疲れ様でした。`

## Phase 2: #1 / #15

目的:

基本ターンバイターンと道なり案内を Mapbox route data から生成する。

作業:

1. `TurnGuideExtractor`
2. `DistanceBucket`
3. `GuideDirection`
4. signal ordinal 判定
5. 連続案内判定
6. `AlongRoadGuideExtractor`

成果物:

- `およそ300m先、この信号を右方向です。`
- `2つ目の信号を左方向です。`
- `右方向、その先、左方向です。`
- `しばらく道なりです。`
- `5km以上道なりです。`

## Phase 3: #2 / #3

目的:

高速道路案内とレーン案内を追加する。

作業:

1. `HighwayGuideExtractor`
2. `RoadKind` 推定
3. `LaneGuideExtractor`
4. lane expression formatter
5. 車線減少検出

成果物:

- `まもなく、分岐です。`
- `高速出口です。`
- `料金所です。`
- `左側の車線をお進みください。`
- `右側の車線が減ります。`

## Phase 4: #5

目的:

オフルートとリルート発話を実装する。

作業:

1. `RoutesObserver` の primary route id 変化を監視する。
2. `OffRouteObserver` の立ち上がりで発話 event を出す。
3. off-route 中の route id 変化を reroute 成功と関連付ける。
4. route更新時に speech history を reset する。
5. `RerouteStateObserver` は SDK で安定利用できる場合のみ補助的に追加する。

成果物:

- `ルートから外れました。`
- `新しいルートが見つかりました。`
- `新しいルートで案内します。`

## Phase 5: #10 / #11

目的:

外部DBなしで踏切と一時停止の存在案内を追加する。

作業:

1. `SafetyGuideExtractor`
2. `railwayCrossing` 検出
3. `stopSign` 検出
4. 横断歩道 flag / component が route response から取れるか検証
5. 連続一時停止判定
6. spoken key に geometry index を含める

成果物:

- `まもなく、踏切です。`
- `まもなく、一時停止です。`
- `一時停止が連続します。`
- 横断歩道は取得可能な構造化データが確認できた場合のみ `横断歩道があります。`

## Phase 6: #16 / #17 仕上げ

目的:

目的地タイプ別フレーズとセッション制御を整理する。

作業:

1. `RouteWaypoint.Place` に `primaryType/types` を保持する。
2. `DestinationKindClassifier` を作る。
3. waypoint arrival / final arrival の phrase を差し替える。
4. mute / pause / resume / stopped state を整理する。

成果物:

- `駅入口付近です。`
- `バス停付近です。`
- `空港付近です。`
- `フェリー乗り場付近です。`
- `音声案内を継続します。`
- `案内停止中です。`

---

## テスト計画

## Unit Test

対象:

- `DistanceBucket`
- `GuideDirection`
- `SignalOrdinalResolver`
- `LaneExpressionFormatter`
- `RoadKindClassifier`
- `DestinationKindClassifier`
- `JapaneseGuidancePhraseComposer`
- `GuidanceSpeechHistory`

## Fixture Test

Mapbox route response の JSON fixture を保存し、extractor をテストする。

取得方法:

1. debug build で `RouteFixtureWriter` を有効化する。
2. 実機または fake GPS / route preview でルート検索する。
3. `NavigationRoute.directionsRoute.toJson()` 相当の JSON を app-specific storage に保存する。
4. 保存した JSON を手動で `core/navigation/src/androidUnitTest/resources/routes/` に移す。
5. 個人情報や自宅座標に近い fixture はコミットしない。

直接 Directions API を叩くスクリプトは、初期段階では必須にしない。SDK 経由の route response と実アプリの `RouteOptions` を一致させる方が重要なため。

fixture:

1. 一般道右左折
2. 信号あり交差点
3. 複数信号
4. 高速入口 / 出口
5. JCT / fork
6. lane data あり交差点
7. 踏切 flag あり
8. stop sign flag あり
9. 複数 waypoint
10. off-route / reroute 相当の route id 更新

## Manual Test

実機または fake GPS で確認する。

確認項目:

- 同じ案内を毎秒繰り返さない
- 右左折案内が遅すぎない
- lane 案内が過剰に出ない
- stop sign / railway crossing が route 上でだけ出る
- off-route 時に発話する
- reroute 成功時に発話する
- waypoint 到着後に次 leg へ進む
- final arrival で到着画面へ遷移する

---

## 後回しにするもの

今回やらないもの:

- #4 渋滞情報
- `PROFILE_DRIVING_TRAFFIC` をナビ本線に使うこと
- traffic-aware ETA
- 渋滞回避ルート提案
- 開かずの踏切
- 渋滞しやすい踏切
- 事故多発踏切
- 横断歩道の確定案内
- 歩行者注意地点
- SA/PA 施設案内
- 景観ルート
- 気象、災害、規制、事故

後続で必要な外部データ候補:

- Geofabrik Japan OSM extract
- OSM `highway=traffic_signals`
- OSM `railway=level_crossing`
- OSM `highway=stop`
- OSM `highway=crossing`
- OSM `highway=services`
- OSM `highway=rest_area`
- 国交省 踏切関連オープンデータ
- 自治体の通学路 / スクールゾーン / 横断歩道データ

---

## 参考

- Mapbox Directions API: https://docs.mapbox.com/api/navigation/directions/
- Mapbox Navigation SDK - Build the route: https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/build-the-route/
- Mapbox Navigation SDK - Route progress: https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/route-progress/
- Mapbox Navigation SDK - Route updates and rerouting: https://docs.mapbox.com/android/ja/navigation/guides/turn-by-turn-navigation/rerouting-and-refresh/
- Geofabrik Japan extract: https://download.geofabrik.de/asia/japan.html
- driving-traffic issue: https://github.com/mapbox/mapbox-navigation-android/issues/7947
