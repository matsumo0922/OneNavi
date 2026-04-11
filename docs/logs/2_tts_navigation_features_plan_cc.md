# TTS ナビゲーション機能 実装計画

- 作成日: 2026-04-11
- 原案: Codex
- 整理: Claude Code
- Status: Draft

---

## 1. スコープ

### 対象機能

| # | カテゴリ | 概要 |
|---|---|---|
| 1 | 基本ターンバイターン案内 | 右左折・直進・Uターン・斜め方向の距離付き案内、信号機カウント、連続案内 |
| 2 | 高速道路案内 | IC/JCT/料金所/分岐/合流/入口/出口、道路種別切替 |
| 3 | レーン案内 | 推奨車線の案内、車線減少通知 |
| 5 | 代替ルート提案 | オフルート検出、リルート通知 |
| 10 | 踏切案内 | 踏切の存在案内 |
| 11 | 一時停止 | 一時停止の存在案内、連続判定 |
| 15 | 道なり案内 | 長距離直進時の安心案内 |
| 16 | 経由地管理 | 目的地タイプ別到着フレーズ |
| 17 | ナビセッション管理 | 開始/終了/mute/resume/タイムアウト |

### 対象外（後続フェーズ）

- **#4 渋滞情報** — `PROFILE_DRIVING` では congestion annotation が実用値にならないため
- #6 交通規制・事故、#7 気象・災害、#8 天気予報、#9 速度・安全警告
- #10 の属性付き案内（開かずの踏切、事故多発踏切等）
- #11 横断歩道（API 取得可否を Phase 0/5 で検証、取れなければ静的 DB 対象）
- SA/PA 施設案内、景観ルート、ボイスコントロール

### 前提方針

1. ルート検索は **`PROFILE_DRIVING`** を継続する
   - `PROFILE_DRIVING_TRAFFIC` は目的地付近の細道に到達しない問題がある（[mapbox-navigation-android#7947](https://github.com/mapbox/mapbox-navigation-android/issues/7947)）
2. `VoiceInstructions.announcement()` のテキスト解析から脱却し、構造化データから `GuidanceEvent` を生成する
3. #10/#11 は Mapbox の intersection flags（`railwayCrossing` / `stopSign`）を一次ソースにする

---

## 2. 現状の問題

### Observer の利用状況

| Observer | 現在 | 問題 |
|---|---|---|
| `RouteProgressObserver` | UI state + カメラ更新 | 発話イベント生成に使えていない |
| `VoiceInstructionsObserver` | `announcement()` を TTS 再生 | Mapbox の英語テキストに依存 |
| `BannerInstructionsObserver` | maneuver text 更新 | レーン・方面名を TTS に使えていない |
| `OffRouteObserver` | UI の off-route flag | 「ルートから外れました」発話なし |
| `ArrivalObserver` | 固定文言の到着 TTS | 目的地タイプ別フレーズ未対応 |

### `JapaneseAnnouncementGenerator` の限界

`VoiceInstructions.announcement()` の文字列からキーワードマッチングで type/modifier を推定している。以下の構造化データが使えていない:

- `LegStep.maneuver().type()` / `.modifier()` / `.bearingBefore()` / `.bearingAfter()`
- `LegStep.intersections()` — `lanes()` / `trafficSignal()` / `stopSign()` / `railwayCrossing()`
- `BannerInstructions.primary()` / `.secondary()` / `.sub()`
- leg index / waypoint index

---

## 3. アーキテクチャ

### コンポーネント構成

```
GuidanceSessionManager          Mapbox observer lifecycle。受け取ったデータを Coordinator に渡す
  |
  v
GuidanceContextBuilder          RouteProgress + Banner + Voice から GuidanceContext を構築
  |
  v
GuidanceCoordinator             Extractor 群を呼び、GuidanceEvent を生成。SharedFlow で公開
  |
  +---> TurnGuideExtractor      #1 基本ターンバイターン + 連続案内
  +---> HighwayGuideExtractor   #2 高速道路
  +---> LaneGuideExtractor      #3 レーン
  +---> RerouteGuideExtractor   #5 代替ルート
  +---> SafetyGuideExtractor    #10 踏切 / #11 一時停止
  +---> AlongRoadGuideExtractor #15 道なり
  +---> WaypointGuideExtractor  #16 経由地
  +---> SessionGuideExtractor   #17 セッション管理
  |
  v
JapaneseGuidancePhraseComposer  GuidanceEvent -> 日本語フレーズ
  |
  v
SpeechOrchestrator              優先度 / 重複抑制 / cooldown / キュー制御
  |
  v
TtsEngine (interface)           Android TTS 実装。将来 Cloud TTS に差し替え可能
  |
  v
AudioFocusManager               AudioFocusRequest の取得/解放
```

### 購読構造

`GuidanceCoordinator` は `SharedFlow<GuidanceEvent>` を公開する。

| 購読者 | 用途 |
|---|---|
| `SpeechOrchestrator` | TTS 再生 |
| UI (maneuver panel / lane panel) | 視覚案内の表示 |
| debug logger | イベントダンプ |

TTS と UI が別々に Mapbox data を解釈しないようにする。

### GuidanceContext

各 Extractor への入力を集約する。

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

制約:
- Extractor は `speechHistory` を直接更新しない
- Extractor は発話候補の `GuidanceEvent` だけを返す
- 最終的な発話可否・優先度・重複抑制・連結は `GuidanceCoordinator` / `SpeechOrchestrator` が決める

### GuidanceEvent

```kotlin
sealed interface GuidanceEvent {
    val id: GuidanceEventId
    val priority: GuidancePriority
    val distanceMeters: Double?
}
```

発話本文を直接組み立てず、まず構造化イベントを作り、`JapaneseGuidancePhraseComposer` で日本語に変換する。

### 優先度とキュー

```kotlin
enum class GuidancePriority {
    CRITICAL,  // 到着、リルート成功、セッション停止
    HIGH,      // 直前ターン、直前分岐、高速出口、重大安全警告
    NORMAL,    // 距離予告ターン、レーン案内、道なり
    LOW,       // 補足情報、遠距離予告、検証段階の安全案内
}
```

| 優先度 | 挙動 | Android TTS |
|---|---|---|
| `CRITICAL` | 現在発話を中断して即時再生 | `QUEUE_FLUSH` |
| `HIGH` | `LOW`/`NORMAL` を中断。`CRITICAL` は中断しない | `QUEUE_FLUSH` |
| `NORMAL` | キューに追加。古い同カテゴリ候補は置き換え | `QUEUE_ADD` |
| `LOW` | キューが空なら再生。混雑時は破棄可能 | `QUEUE_ADD` |

同時発生ルール:
1. 直前ターン案内を最優先
2. 踏切/一時停止がターンと同時 → 短く前置きできる時だけ連結
3. レーン案内がターン直前 10 秒以内 → 破棄または統合
4. 道なり案内 → 他カテゴリがあれば破棄

### 重複抑制

```kotlin
data class SpokenGuideKey(
    val category: GuideCategory,
    val legIndex: Int,
    val stepIndex: Int,
    val geometryIndex: Int?,
)

class GuidanceSpeechHistory {
    fun hasSpoken(key: SpokenGuideKey): Boolean
    fun markSpoken(key: SpokenGuideKey)
    fun resetForNewRoute(routeId: String)
}
```

重複抑制キー: route id / leg index / step index / geometry index / event category / distance bucket

### TTS エンジン抽象化

```kotlin
interface TtsEngine {
    val isReady: StateFlow<Boolean>
    suspend fun speak(text: String, utteranceId: String)
    fun stop()
    fun shutdown()
}
```

- 初期: `AndroidTtsEngine` + `AudioFocusManager`
- 後続: `CloudTtsEngine` + local audio cache + offline fallback

---

## 4. 機能別設計

### #1 基本ターンバイターン案内

**使う SDK データ**: `LegStep.maneuver().type()` / `.modifier()`, `StepIntersection.trafficSignal()`, `VoiceInstructions.distanceAlongGeometry()`

#### 距離段階

```kotlin
enum class DistanceBucket(val meters: Int, val phrase: String) {
    M50(50, "およそ50m先"),    M100(100, "およそ100m先"),
    M200(200, "およそ200m先"), M300(300, "およそ300m先"),
    M400(400, "およそ400m先"), M500(500, "およそ500m先"),
    M600(600, "およそ600m先"), M700(700, "およそ700m先"),
    M800(800, "およそ800m先"), M900(900, "およそ900m先"),
    KM1(1_000, "およそ1km先"), KM2(2_000, "およそ2km先"),
    KM3(3_000, "およそ3km先"), KM4(4_000, "およそ4km先"),
    KM5(5_000, "およそ5km先"), KM10(10_000, "およそ10km先"),
}
```

#### 発話 trigger（道路種別ごと）

| 道路種別 | 遠距離予告 | 中距離 | 直前 |
|---|---:|---:|---:|
| 一般道 | 300m | 100m | 50m / まもなく |
| 高速道路 | 2km | 1km | 300m / まもなく |
| 有料道路・専用道 | 1km | 500m | 200m / まもなく |

#### 連続案内

`TurnGuideExtractor` が返した turn event に対し、`GuidanceCoordinator` が次の guide point を確認。300m 以内なら `LinkedTurnGuideEvent` に昇格:

```kotlin
data class LinkedTurnGuideEvent(
    val first: TurnGuideEvent,
    val second: TurnGuideEvent,
    val connector: TurnConnector,  // SONO_SAKI / USETSU_GO / SASETSU_GO / TSUUKA_GO 等
) : GuidanceEvent
```

発話例:
- `まもなく、右方向、その先、左方向です。`
- `右折後、左方向です。`
- `交差点を通過後、右方向です。`

#### 信号機案内

1. 現在 step から次 maneuver までの `intersections` を走査
2. `trafficSignal == true` の intersection を数える
3. maneuver 地点側に信号あり → `この信号を`
4. 次 maneuver が 2/3 番目の信号 → `2つ目の信号を` / `3つ目の信号を`
5. 信号情報がない / `null`（不明）→ 距離案内にフォールバック

発話例:
- `およそ300m先、この信号を右方向です。`
- `2つ目の信号を左方向です。`

---

### #2 高速道路案内

**使う SDK データ**: `maneuver.type` (on ramp / off ramp / fork / merge), `StepIntersection.classes()`, `StepIntersection.tollCollection()`, `BannerInstructions.primary/secondary`, `LegStep.name()` / `.ref()`

#### 検出イベント

高速入口 / 高速出口 / 有料道路入口 / 有料道路出口 / 料金所 / 分岐 / 連続分岐 / 合流 / 一般道⇔高速切替 / 有料区間進入 / 自動車専用道進入

#### 道路種別判定

```kotlin
enum class RoadKind { LOCAL, HIGHWAY, TOLL, MOTORWAY, FERRY, UNKNOWN }
```

`StepIntersection.classes()` の変化を監視。前回と現在の `RoadKind` が変わったら:
- `一般道のルートに切り替わりました`
- `高速道のルートに切り替わりました`

#### SA/PA

初期版では対象外。後続で OSM `highway=services` / `highway=rest_area` から静的 DB を構築。

---

### #3 レーン案内

**使う SDK データ**: `StepIntersection.lanes()` (valid / active / indications), `BannerInstructions.sub` の lane component

#### 推奨車線算出

```kotlin
data class LaneRecommendation(
    val totalLaneCount: Int,
    val recommendedLaneIndexesFromLeft: List<Int>,
    val recommendedLaneIndexesFromRight: List<Int>,
    val action: LaneAction,
)
```

#### 初期版の発話パターン

- `右側の車線をお進みください`
- `左側の車線をお進みください`
- `右から N 番目の車線をお進みください`
- `左から N 番目の車線をお進みください`
- `右側の車線にお入りください` / `左側の車線にお入りください`
- `右側 / 左側の車線が減ります`

#### 進行/移動の判定方針

現在車線は SDK から高精度に取れない前提。初期版では:
- 推奨車線が複数 → `お進みください`（維持）
- 片側に強く寄る場合 → `お入りください` は控えめに
- 高速出口/分岐直前 → `お早めに...をお進みください`

誤った車線変更指示は危険なため、「お進みください」中心で始める。

---

### #5 代替ルート提案

**使う SDK データ**: `OffRouteObserver`, `RoutesObserver`, `RerouteStateObserver`（安定版のみ）

#### 実装内容

1. `OffRouteObserver` の立ち上がり → `ルートから外れました。`
2. `RoutesObserver` で primary route id の変化を監視
3. off-route 中に route id が変わった → reroute 成功
4. 成功時 → `新しいルートが見つかりました。新しいルートで案内します。`
5. route 更新時に `GuidanceSpeechHistory` をリセット

#### `RerouteStateObserver` の扱い

SDK バージョン（現 `3.21.0-rc.1`）で安定利用できる場合のみ、reroute 開始/失敗の補助 signal として使う。実装を強く依存させない。

安定性の優先順位: `RoutesObserver` > `OffRouteObserver` > `RerouteStateObserver`

#### プロアクティブリルート

渋滞起因の「今より N 分早いルート」は今回対象外。route refresh / alternatives による更新の検知と「新しいルートが見つかりました」まで。

---

### #10 踏切案内

**使う SDK データ**: `StepIntersection.railwayCrossing()`

#### 発話

- 距離がある場合: `この先、踏切です。`
- 直前: `まもなく、踏切です。`

#### 発話タイミング

| 条件 | 距離 |
|---|---|
| 一般道 | 150m 前 |
| 低速時 | 80m 前 |
| 高速道路上 | 原則抑制 |

同一 intersection / geometry index に対して一度だけ発話（`SpokenGuideKey` で制御）。

#### 後続フェーズ

属性付きフレーズ（開かずの踏切 / 渋滞しやすい踏切 / 事故多発踏切）は OSM + 国交省データが必要。Mapbox flag だけでは出さない。

---

### #11 一時停止

**使う SDK データ**: `StepIntersection.stopSign()`

#### 発話

- 1件: `まもなく、一時停止です。`
- 2件以上（200m 以内）: `一時停止が連続します。`

#### 連続判定ロジック

1. 前方 300m の intersections を走査
2. `stopSign == true` を収集
3. 2件以上 → `一時停止が連続します。` / 1件 → `一時停止です。`

#### 横断歩道

初期版では実装しない。`StepIntersection` に横断歩道 flag が安定提供される前提を置けないため。Phase 5 で取得可否を検証し、取れなければ後続の静的 Safety DB 対象。

---

### #15 道なり案内

**使う SDK データ**: `RouteProgress`, upcoming steps, `maneuver.type == "continue"` / `"new name"`

#### 発話

- 次の有効 guide point まで 2km 以上 5km 未満 → `しばらく道なりです。`
- 5km 以上 → `5km以上道なりです。`

#### 発話タイミング

1. `RouteProgressObserver` 起点（`VoiceInstructionsObserver` ではない）
2. `stepIndex` が変わった時に判定
3. 完了した step が turn / fork / merge / ramp で、次の有効 guide point まで 2km 以上ある場合に候補
4. 直近 60 秒以内に道なり案内を出していなければ発話

---

### #16 経由地管理

**使う SDK データ**: `ArrivalObserver`, `MapboxNavigation.navigateNextRouteLeg()`

#### `RouteWaypoint.Place` の拡張

```kotlin
data class Place(
    val name: String,
    override val latitude: Double,
    override val longitude: Double,
    val primaryType: String?,
    val types: List<String>,
) : RouteWaypoint
```

#### 目的地タイプ分類

```kotlin
enum class DestinationKind(val arrivalPhrase: String) {
    DESTINATION("目的地付近です。"),
    STATION_ENTRANCE("駅入口付近です。"),
    BUS_STOP("バス停付近です。"),
    AIRPORT("空港付近です。"),
    FERRY_TERMINAL("フェリー乗り場付近です。"),
    WAYPOINT("経由地付近です。"),
}
```

Google Places の `primaryType` / `types` からマッピング:
- `train_station` / `subway_station` / `transit_station` → STATION_ENTRANCE
- `bus_station` → BUS_STOP
- `airport` → AIRPORT
- `ferry_terminal` → FERRY_TERMINAL

---

### #17 ナビセッション管理

#### セッションイベント

```kotlin
sealed interface SessionGuideEvent : GuidanceEvent {
    data object Started : SessionGuideEvent         // 音声案内を開始します。実際の交通規制に従って走行してください。
    data object Stopped : SessionGuideEvent         // 目的地付近です。お疲れ様でした。
    data object Resumed : SessionGuideEvent         // 音声案内を継続します。
    data object SuspendedSoon : SessionGuideEvent   // あと5分で、音声案内を中断します。
    data object Suspended : SessionGuideEvent       // 一定時間が経過したため、音声案内を中断します。
    data object GuidanceMuted : SessionGuideEvent   // 案内停止中です。
}
```

#### mute 状態

- セッション中の mute は即時反映
- 初期版ではセッションごとにリセット
- 永続化（DataStore）は後続

#### バックグラウンド中断

初期版では実装しなくてもよい。実装する場合:
- アプリバックグラウンド時刻を記録
- foreground service 中はナビ継続
- タイムアウト前に `あと5分で...` / 到達で `一定時間が経過したため...`
- ユーザー設定で「バックグラウンド案内を継続」を持つ

---

## 5. 実装フェーズ

### Phase 0: データ取得確認

**目的**: `PROFILE_DRIVING` で必要フィールドが取得できるか確認する

**作業**:
1. debug log に route response の step / intersection summary を出す
2. 以下を確認:
   - `lanes` 件数
   - `trafficSignal == true` 件数
   - `stopSign == true` 件数
   - `railwayCrossing == true` 件数
   - `tollCollection != null` 件数
   - `classes` の内容
   - 横断歩道に相当する構造化データの有無
   - congestion 系 annotation が実用値にならないことの確認
3. debug build 限定で route response JSON をファイル保存する仕組みを作る
4. 不足する場合は `RouteOptions` を明示指定

**成果物**: `RouteDebugDumper` / `RouteFixtureWriter` / fixture JSON (`core/navigation/src/androidUnitTest/resources/routes/`)

---

### Phase 1a: GuidanceEvent モデル

**目的**: 構造化イベントの型定義

**作業**:
1. `GuidanceEvent` sealed interface
2. `GuidancePriority` enum
3. `SpokenGuideKey` / `GuidanceSpeechHistory`
4. `DistanceBucket` / `GuideDirection` / `RoadKind` enum

**成果物**: モデル定義 + unit test。既存挙動は変更しない。

---

### Phase 1b: GuidanceContext

**目的**: `RouteProgress` から Extractor 用の文脈を構築する

**作業**:
1. `GuidanceContext` / `UpcomingStepContext` / `UpcomingIntersectionContext`
2. `GuidanceContextBuilder`
3. `RouteDebugDumper` と抽出ロジックを共有

---

### Phase 1c: SpeechOrchestrator / TtsEngine

**目的**: `GuidanceSessionManager` から TTS 責務を分離する

**作業**:
1. `TtsEngine` interface + `AndroidTtsEngine`
2. `AudioFocusManager`
3. `SpeechOrchestrator`（優先度付きキュー、`QUEUE_FLUSH` / `QUEUE_ADD` policy）

**成果物**: 既存 Android TTS の動作維持 + 優先度キューの最小実装

---

### Phase 1d: Phrase Composer

**目的**: 既存テンプレートを GuidanceEvent -> Phrase パイプラインに移行する

**作業**:
1. `JapaneseGuidancePhraseComposer`
2. #17 開始フレーズ / #16 到着フレーズを移行
3. 既存 `JapaneseAnnouncementGenerator` は fallback に留める

**成果物**: `音声案内を開始します...` / `経由地付近です。` / `目的地付近です。お疲れ様でした。`

---

### Phase 2: #1 基本ターンバイターン + #15 道なり

**作業**:
1. `TurnGuideExtractor`
2. 距離段階判定 + signal ordinal 判定
3. 連続案内判定（`LinkedTurnGuideEvent`）
4. `AlongRoadGuideExtractor`

**成果物**: `およそ300m先、この信号を右方向です。` / `右方向、その先、左方向です。` / `しばらく道なりです。`

---

### Phase 3: #2 高速道路 + #3 レーン

**作業**:
1. `HighwayGuideExtractor` + `RoadKind` 推定
2. `LaneGuideExtractor` + lane expression formatter
3. 車線減少検出

**成果物**: `まもなく、分岐です。` / `高速出口です。` / `料金所です。` / `左側の車線をお進みください。`

---

### Phase 4: #5 代替ルート

**作業**:
1. `RoutesObserver` の primary route id 変化を監視
2. `OffRouteObserver` の立ち上がりで発話 event
3. off-route 中の route id 変化 = reroute 成功
4. route 更新時に speech history リセット
5. `RerouteStateObserver` は SDK 安定性確認後に補助追加

**成果物**: `ルートから外れました。` / `新しいルートが見つかりました。` / `新しいルートで案内します。`

---

### Phase 5: #10 踏切 + #11 一時停止

**作業**:
1. `SafetyGuideExtractor`
2. `railwayCrossing` / `stopSign` 検出
3. 横断歩道 flag の取得可否を検証
4. 連続一時停止判定
5. `SpokenGuideKey` に geometry index を含める

**成果物**: `まもなく、踏切です。` / `まもなく、一時停止です。` / `一時停止が連続します。`

---

### Phase 6: #16 経由地 + #17 セッション仕上げ

**作業**:
1. `RouteWaypoint.Place` に `primaryType` / `types` を保持
2. `DestinationKindClassifier`
3. waypoint / final arrival の phrase 差し替え
4. mute / pause / resume / stopped state

**成果物**: `駅入口付近です。` / `空港付近です。` / `音声案内を継続します。` / `案内停止中です。`

---

## 6. テスト計画

### Unit Test

| 対象 | 検証内容 |
|---|---|
| `DistanceBucket` | 距離値 → bucket 変換 |
| `GuideDirection` | modifier → 8方向分類 |
| `SignalOrdinalResolver` | intersection 列 → N 番目の信号 |
| `LaneExpressionFormatter` | 車線データ → 日本語表現 |
| `RoadKindClassifier` | intersection classes → RoadKind |
| `DestinationKindClassifier` | Google Places type → DestinationKind |
| `JapaneseGuidancePhraseComposer` | GuidanceEvent → フレーズ文字列 |
| `GuidanceSpeechHistory` | 重複抑制キーの一致/リセット |

### Fixture Test

Mapbox route response JSON を使って Extractor をテスト。

**fixture 取得方法**:
1. debug build で `RouteFixtureWriter` を有効化
2. 実機 or fake GPS でルート検索
3. `NavigationRoute.directionsRoute.toJson()` を app-specific storage に保存
4. `core/navigation/src/androidUnitTest/resources/routes/` に手動コピー
5. 個人情報・自宅座標に近い fixture はコミットしない

**fixture 一覧**: 一般道右左折 / 信号あり交差点 / 複数信号 / 高速入口・出口 / JCT・fork / lane data あり / 踏切 flag / stop sign flag / 複数 waypoint / route id 更新

### Manual Test

実機 or fake GPS で以下を確認:

- 同じ案内を毎秒繰り返さない
- 右左折案内が遅すぎない
- レーン案内が過剰に出ない
- 踏切・一時停止が route 上でだけ出る
- off-route 時に発話する
- reroute 成功時に発話する
- waypoint 到着後に次 leg へ進む
- final arrival で到着画面へ遷移する

---

## 7. 後続フェーズで必要な外部データ

| データソース | 用途 |
|---|---|
| Geofabrik Japan OSM extract | 各種 POI の一括取得 |
| OSM `highway=traffic_signals` | 信号機カバレッジ向上 |
| OSM `railway=level_crossing` | 踏切カバレッジ向上 |
| OSM `highway=stop` / `highway=crossing` | 一時停止・横断歩道 |
| OSM `highway=services` / `highway=rest_area` | SA/PA |
| 国交省 踏切道安全通行カルテ | 属性付き踏切案内 |
| 自治体オープンデータ | 通学路・スクールゾーン・横断歩道 |

---

## 参考

- [Mapbox Directions API](https://docs.mapbox.com/api/navigation/directions/)
- [Mapbox Navigation SDK - Build the route](https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/build-the-route/)
- [Mapbox Navigation SDK - Route progress](https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/route-progress/)
- [Mapbox Navigation SDK - Route updates and rerouting](https://docs.mapbox.com/android/ja/navigation/guides/turn-by-turn-navigation/rerouting-and-refresh/)
- [Geofabrik Japan extract](https://download.geofabrik.de/asia/japan.html)
- [driving-traffic issue #7947](https://github.com/mapbox/mapbox-navigation-android/issues/7947)
