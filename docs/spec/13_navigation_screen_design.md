# 13. Navigation Screen Design — Phase 1 実装設計書

> **作成日:** 2026-04-04
> **ステータス:** 承認済み（実装着手可）
> **対象:** Phase 1 ナビゲーション画面の設計判断・アーキテクチャ・UI 仕様

---

## 1. 概要

`HomeMapRouteResultSheet` の「ナビ開始」ボタン（`onNavigationClicked`）を起点として、ターンバイターンナビゲーションを実装する。本ドキュメントは実装に先立ち、全設計判断を確定させたものである。

### 1.1 Phase 1 で実装するもの

- ナビゲーション状態マシン（6 状態）
- ナビセッション管理（TripSession / Observer 群）
- マニューバパネル（方向アイコン + 距離 + 地点名 + 次マニューバ）
- トリップ進捗カード（残時間 / 残距離 / ETA）
- コントロールボタン群（設定 / 音量 / コンパス / ズーム）
- ナビ用 CallOut（方向 + 地点名）
- Android 内蔵 TTS + 基本日本語テンプレート（10-15 パターン）
- ForegroundService + バックグラウンド音声案内
- 通知（次マニューバ + ETA）
- ルートライン消失エフェクト（走行済み部分のグレーアウト）
- 画面 ON 維持（FLAG_KEEP_SCREEN_ON）
- 現在の道路名バッジ（RoadNameObserver）
- 自動リルート + トースト通知
- 代替ルート表示（ナビ中もタップ切替可）
- 到着画面（自動終了 10 秒タイマー or 閉じるボタン）
- 縦横両対応レイアウト（WindowSizeClass）

### 1.2 Phase 1 で実装しないもの

- Google Cloud TTS (Chirp 3: HD) ストリーミング
- 速度連動の案内タイミング
- 信号機カウント案内
- 通称辞書（ニックネーム）
- 車線案内パネル（データは取得可能だが UI は Phase 2）
- 高速パネル（IC/JCT/SA/PA 一覧）
- 速度制限表示
- 料金表示
- Junction View
- Android Auto 対応
- FreeDrive モード（定義のみ、UI なし）

---

## 2. 状態マシン

### 2.1 NavigationState

```kotlin
sealed interface NavigationState {
    data object Browsing : NavigationState
    data object Search : NavigationState
    data class RoutePreview(
        val routes: ImmutableList<RouteResult>,
        val selectedIndex: Int,
        val waypoints: ImmutableList<RouteWaypoint>,
    ) : NavigationState
    data class ActiveGuidance(
        val guidanceUiState: GuidanceUiState,
    ) : NavigationState
    data class Arrival(
        val destinationName: String,
        val totalDistanceMeters: Double,
        val totalDurationSeconds: Double,
    ) : NavigationState
    data object FreeDrive : NavigationState  // Phase 1 では未使用、定義のみ
}
```

### 2.2 状態遷移図

```
                  ┌──────────┐
                  │ Browsing │◄──────────────────────────┐
                  └────┬─────┘                           │
                       │ 検索開始                    閉じる / タイマー / 手動終了
                       ▼                                 │
                  ┌──────────┐                           │
                  │  Search  │                           │
                  └────┬─────┘                           │
                       │ ルート検索                       │
                       ▼                                 │
               ┌──────────────┐                          │
               │ RoutePreview │◄──── ルート再検索         │
               └──────┬───────┘                          │
                      │ ナビ開始                          │
                      ▼                                  │
             ┌────────────────┐                          │
             │ ActiveGuidance │◄──── リルート（自動）     │
             └──┬──────────┬──┘                          │
                │          │ ✕ボタン（手動終了）          │
                │ 目的地到着 └────────────────────────────┤
                ▼                                        │
           ┌──────────┐                                  │
           │ Arrival  │──────────────────────────────────┘
           └──────────┘
```

> **手動終了フロー:** トリップカードの ✕ ボタンをタップ → `stopTripSession()` / `clearRoutes()` / FLAG 解除 → `NavigationState.Browsing` に直接遷移（Arrival 画面は表示しない）

### 2.3 各状態でのコンポーネント表示

| コンポーネント | Browsing | Search | RoutePreview | ActiveGuidance | Arrival |
|---|---|---|---|---|---|
| 検索バー | ○ | ○ | × | × | × |
| ルート TopAppBar | × | × | ○ | × | × |
| BottomSheet | △ | ○ | ○ | × | × |
| マニューバパネル | × | × | × | ○ | × |
| トリップカード | × | × | × | ○ | × |
| コントロール列 | ○ | × | ○ | ○ | × |
| 到着画面 | × | × | × | × | ○ |
| ルート CallOut | × | × | ○(時間差分) | ○(方向+地名) | × |
| 道路名バッジ | × | × | × | ○ | × |
| 「現在地に戻る」 | × | × | × | △(Idle時) | × |

---

## 3. アーキテクチャ

### 3.1 モジュール構成

```
core/model/                          ← Mapbox 非依存のデータモデル
├── NavigationState.kt
├── GuidanceUiState.kt
├── ManeuverInfo.kt
└── TripProgressInfo.kt

core/navigation/                     ← Mapbox 依存のナビ管理（新規モジュール）
├── RouteManager.kt                  ← ルート管理 + RoutesObserver
├── CameraManager.kt                 ← カメラ管理（Following/Overview/Idle）
├── GuidanceSessionManager.kt        ← セッション管理 + Observer 群
└── di/
    └── NavigationModule.kt          ← Koin DI モジュール

feature/home/                        ← UI コンポーネント（既存モジュール拡張）
├── map/
│   ├── HomeMapScreenContent.kt      ← 状態に応じた UI 切替
│   ├── HomeMapViewModel.kt          ← NavigationState の StateFlow 追加
│   ├── HomeMapsMapEffectContent.kt  ← RoutesObserver 駆動に変更
│   └── components/
│       ├── guidance/                ← ナビ UI コンポーネント（新規）
│       │   ├── HomeMapGuidanceManeuverPanel.kt
│       │   ├── HomeMapGuidanceTripCard.kt
│       │   ├── HomeMapGuidanceControlColumn.kt
│       │   ├── HomeMapGuidanceCallout.kt
│       │   ├── HomeMapGuidanceArrivalScreen.kt
│       │   ├── HomeMapGuidanceReturnButton.kt
│       │   └── NavigationColors.kt
│       └── ...
```

### 3.2 責務分離

#### RouteManager（元 HomeMapNavigationManager のルート部分）

```
責務:
- ルートの保持（StateFlow<List<NavigationRoute>>）
- 選択ルートインデックスの管理
- RoutesObserver の登録 → ルート変更の一元受信
  - 初回ルート設定
  - リルート
  - トラフィックリフレッシュ（5 分毎）
  - 代替ルート探索
- RouteLineApi / RouteLineView の管理
- reorderedRoutes() の提供
- setRoutes() / selectRoute() / clearRoutes()
```

#### CameraManager（元 HomeMapNavigationManager のカメラ部分）

```
責務:
- NavigationCamera の初期化・破棄
- MapboxNavigationViewportDataSource の管理
- Following / Overview / Idle モードの切替
- followingPadding / overviewPadding の設定
- NavigationLocationProvider の保持
- LocationObserver → viewportDataSource.onLocationChanged() のフィード
- RouteProgress → viewportDataSource.onRouteProgressChanged() のフィード
```

#### GuidanceSessionManager（新規）

```
責務:
- TripSession のライフサイクル管理
  - startTripSession(withForegroundService = true)
  - stopTripSession()
- Observer 群の登録・解除
  - RouteProgressObserver → GuidanceUiState 更新
  - VoiceInstructionsObserver → TTS トリガー
  - BannerInstructionsObserver → マニューバ情報更新
  - OffRouteObserver → リルート通知
  - ArrivalObserver → 到着検出
  - RoadNameObserver → 現在道路名更新
- StateFlow<GuidanceUiState> の公開
- StateFlow<NavigationState> への反映
- ForegroundService 通知の管理
- Android TTS の初期化・再生・停止
```

### 3.3 データフロー

```
MapboxNavigation (SDK)
  │
  ├── RoutesObserver ──────────────► RouteManager
  │                                   ├── StateFlow<routes>
  │                                   ├── RouteLineApi.setNavigationRoutes()
  │                                   └── CameraManager.onRouteChanged()
  │
  ├── LocationObserver ────────────► CameraManager
  │                                   ├── NavigationLocationProvider.changePosition()
  │                                   └── viewportDataSource.onLocationChanged()
  │
  ├── RouteProgressObserver ───────► GuidanceSessionManager
  │                                   ├── StateFlow<GuidanceUiState>
  │                                   ├── RouteLineApi.updateWithRouteProgress()
  │                                   └── CameraManager.onRouteProgressChanged()
  │
  ├── VoiceInstructionsObserver ───► GuidanceSessionManager
  │                                   └── TTS 再生（カスタムテンプレート）
  │
  ├── BannerInstructionsObserver ──► GuidanceSessionManager
  │                                   └── ManeuverInfo 更新
  │
  ├── OffRouteObserver ────────────► GuidanceSessionManager
  │                                   └── トースト通知
  │
  ├── ArrivalObserver ─────────────► GuidanceSessionManager
  │                                   └── NavigationState.Arrival 遷移
  │
  └── RoadNameObserver ────────────► GuidanceSessionManager
                                      └── StateFlow<currentRoadName>
```

### 3.4 ViewModel との統合

```kotlin
class HomeMapViewModel(
    private val searchRepository: SearchRepository,
    private val routeRepository: RouteRepository,
    internal val routeManager: RouteManager,
    internal val cameraManager: CameraManager,
    internal val guidanceSessionManager: GuidanceSessionManager,
) : ViewModel() {

    // 既存（そのまま維持）
    val routeResults: StateFlow<ImmutableList<RouteResult>>
    val selectedRouteIndex: StateFlow<Int>
    val waypoints: StateFlow<ImmutableList<RouteWaypoint>>

    // 追加
    val navigationState: StateFlow<NavigationState>
    // UI の表示切り替え判断にのみ使用
    // ナビ中のデータは GuidanceSessionManager.guidanceUiState から取得
}
```

---

## 4. データモデル（core/model）

### 4.1 GuidanceUiState

```kotlin
@Immutable
data class GuidanceUiState(
    val currentManeuver: ManeuverInfo?,
    val nextManeuver: ManeuverInfo?,
    val tripProgress: TripProgressInfo,
    val currentRoadName: String?,
    val isOffRoute: Boolean,
)
```

### 4.2 ManeuverInfo

```kotlin
@Immutable
data class ManeuverInfo(
    val type: String,           // "turn", "fork", "merge", "on ramp", "off ramp", "arrive" 等
    val modifier: String?,      // "left", "right", "slight left", "sharp right", "straight", "uturn" 等
    val distanceMeters: Double, // 次のマニューバまでの距離
    val instruction: String,    // 交差点名 / JCT 名 / 道路名
)
```

### 4.3 TripProgressInfo

```kotlin
@Immutable
data class TripProgressInfo(
    val distanceRemainingMeters: Double,
    val durationRemainingSeconds: Double,
    val estimatedArrivalTimeMillis: Long,
)
```

---

## 5. UI 仕様

### 5.1 画面レイアウト

#### Compact（縦画面・スマホ）

```
┌─────────────────────────────┐
│ ┌─────────────────────────┐ │
│ │  ↑ 800 m                │ │  ← マニューバパネル（緑背景）
│ │  後飯町                  │ │     方向アイコンは案内下部に配置
│ │  [↑ TurnIcon]           │ │
│ └─────────────────────────┘ │
│                         [⚙] │
│                         [🔊] │  ← コントロール列（右端）
│       地 図              [⌖] │
│                              │
│         ▲                [+] │  ← 自車位置は手前側（下部寄り）
│                         [-] │
│ ┌─────────────────────────┐ │
│ │ 6分  3.4km  到着 0:25   │ │  ← トリップカード
│ │ [✕] [⊕] [🔄] [···]    │ │     ボタン: 終了 / 経由地追加 / 概要 / その他
│ └─────────────────────────┘ │
│ ┌───────┐                   │
│ │県道244号│                  │  ← 道路名バッジ（左下）
│ └───────┘                   │
└─────────────────────────────┘
```

#### Expanded（横画面・タブレット・Android Auto）

```
┌────────────────────────────────────────────────────────┐
│                               ┌──────────────────┐[⚙] │
│                               │  ← 290 m         │[🔊] │
│                               │  銚子大橋前       │[⌖] │
│                               │  [← TurnIcon]    │    │
│         地 図                  └──────────────────┘    │
│                                                   [+] │
│              ▲                                    [-] │
│                               ┌──────────────────┐    │
│ ┌───────┐                     │ 8分 4.7km  0:25  │    │
│ │県道244号│                    │ [✕][⊕][🔄][···] │    │
│ └───────┘                     └──────────────────┘    │
└────────────────────────────────────────────────────────┘
```

### 5.2 マニューバパネル

- **背景色:** ダークグリーン（Google Maps AA 準拠、`NavigationColors` object で一括管理）
- **方向アイコン:** Mapbox TurnIconComponent を使用、パネル下部に配置（img_5 参照）
- **距離:** `ManeuverInfo.distanceMeters` を `DisplayFormatter.formatDistance()` でフォーマット
  - 999m 以下: `m` 表記（例: `300 m`）
  - 1000m 以上: `km` 表記・小数点 1 桁（例: `1.2 km`）
- **地点名:** `ManeuverInfo.instruction`（交差点名 / JCT 名 / 道路名）
- **次マニューバ:** 小さく「次: 500m 右折」のようなプレビュー表示

### 5.3 トリップカード

- **残時間:** `TripProgressInfo.durationRemainingSeconds` → `DisplayFormatter.formatDuration()`
- **残距離:** `TripProgressInfo.distanceRemainingMeters` → `DisplayFormatter.formatDistance()`
- **到着予想時刻:** `TripProgressInfo.estimatedArrivalTimeMillis` → 時刻フォーマット
- **ボタン群:**
  - ✕ ナビ終了（`callback: () -> Unit`、Phase 1 では空実装可）
  - ⊕ 経由地追加
  - 🔄 ルート全体プレビュー（Overview モード切替）
  - ··· その他メニュー

### 5.4 コントロール列

右端に縦並びで配置:

| アイコン | 機能 | Phase 1 動作 |
|---------|------|-------------|
| ⚙ 設定 | 設定画面遷移 | callback のみ |
| 🔊 音量 | TTS ミュート切替 | callback のみ |
| ⌖ コンパス | Following 3D ↔ Following 2D（北固定）トグル | 実装する |
| + ズームイン | 地図ズームイン | callback のみ |
| − ズームアウト | 地図ズームアウト | callback のみ |

### 5.5 ナビ用 CallOut

- 地図上のマニューバ地点に青い吹き出しで表示
- **表示内容:** 方向アイコン（← / → 等） + 地点名（例: `← 銚子大橋前`）
- 既存の `HomeMapRouteCallout`（時間差分バッジ）とは別コンポーネント
- `BannerInstructions` のデータから生成

### 5.6 道路名バッジ

- 画面左下に小さなバッジとして表示
- `RoadNameObserver` から取得した現在走行中の道路名
- 例: `県道244号`、`東名高速道路`

### 5.7 「現在地に戻る」ボタン

- ナビ中にユーザーが地図をドラッグしてカメラが Idle になったとき表示
- タップで Following モード（3D or 2D、直前の設定）に復帰
- 画面中央下部に配置

### 5.8 到着画面

```
ArrivalObserver.onFinalDestinationArrival()
  ↓
NavigationState.Arrival に遷移
  ↓
TTS: 「目的地に到着しました」
  ↓
到着画面表示:
  - 目的地名
  - 総走行距離
  - 総走行時間
  ↓
「閉じる」タップ or 自動タイマー（10 秒）
  ↓
NavigationState.Browsing に戻る
stopTripSession()
clearRoutes()
```

---

## 6. 配色管理

```kotlin
object NavigationColors {
    // マニューバパネル
    val maneuverBackground = Color(0xFF1B5E20)  // ダークグリーン
    val maneuverText = Color.White
    val maneuverDistance = Color.White

    // トリップカード
    val tripCardBackground = Color(0xFF2C2C2C)
    val tripCardText = Color.White
    val tripCardSecondary = Color(0xFFB0B0B0)

    // CallOut
    val calloutBackground = Color(0xFF1565C0)   // ブルー
    val calloutText = Color.White

    // コントロール
    val controlBackground = Color(0xFF2C2C2C)
    val controlIcon = Color.White

    // 道路名バッジ
    val roadNameBackground = Color(0xFF2C2C2C)
    val roadNameText = Color.White
}
```

> **Note:** 配色は後で一括変更可能なように `NavigationColors` object に集約する。上記の値は仮であり、実装時に Google Maps AA を参考に調整する。

---

## 7. カメラ動作

### 7.1 ナビ開始時

- `NavigationCamera` を **Following モード** に切替
- **ピッチ:** 45-60°（3D パースペクティブ）
- **自車位置:** 画面手前側（下部寄り）に配置
  - `viewportDataSource.followingPadding` で上方に大きめの余白を設定
- **進行方向:** 常に画面上方

### 7.2 コンパストグル

| 状態 | ピッチ | 方位 | 説明 |
|------|--------|------|------|
| Following 3D（デフォルト） | 45-60° | 進行方向が上 | カーナビ標準 |
| Following 2D（北固定） | 0° | 北が上 | 地図を俯瞰で確認 |

- コンパスアイコンをタップするたびにトグル
- `Following 2D（進行方向）` は**ナビ画面では登場しない**

### 7.3 ユーザー操作時

- 地図ドラッグ → Idle に自動遷移（`NavigationBasicGesturesHandler` が処理）
- 「現在地に戻る」ボタン表示
- ボタンタップ → 直前の Following モード（3D or 2D）に復帰

### 7.4 Overview モード

- トリップカードの「ルート全体プレビュー」ボタンで切替
- ルート全体を表示
- 再度タップまたは「現在地に戻る」で Following に復帰

---

## 8. 音声案内（TTS）

### 8.1 エンジン

- **Phase 1:** Android 内蔵 `TextToSpeech`
- **Phase 2:** Google Cloud TTS (Chirp 3: HD, Laomedeia) に差し替え

### 8.2 タイミング

- `VoiceInstructionsObserver` で Mapbox SDK からのタイミング通知を受信
- 独自タイミング制御（速度連動）は Phase 2

### 8.3 基本テンプレート

| # | テンプレート | トリガー条件 |
|---|-------------|-------------|
| 1 | `{distance}先、左折です` | maneuver.type=turn, modifier=left |
| 2 | `{distance}先、右折です` | maneuver.type=turn, modifier=right |
| 3 | `{distance}先、斜め左です` | modifier=slight left |
| 4 | `{distance}先、斜め右です` | modifier=slight right |
| 5 | `まもなく左折です` | 距離が近い + left |
| 6 | `まもなく右折です` | 距離が近い + right |
| 7 | `直進です` | modifier=straight |
| 8 | `合流です、ご注意ください` | maneuver.type=merge |
| 9 | `左方向です` | maneuver.type=fork, modifier=left |
| 10 | `右方向です` | maneuver.type=fork, modifier=right |
| 11 | `{name}方面、出口です` | maneuver.type=off ramp |
| 12 | `目的地に到着しました` | maneuver.type=arrive (final) |
| 13 | `経由地に到着しました` | maneuver.type=arrive (waypoint) |
| 14 | `ルートを再計算しました` | リルート完了時 |
| 15 | `Uターンです` | modifier=uturn |

### 8.4 距離の読み上げ

- 999m 以下: `{n}メートル` （例: 「300メートル先」）
- 1000m 以上: `{n}キロ` （例: 「1.2キロ先」）

### 8.5 キュー管理

- **新しい案内が来たら、前の発話を即座に切って新しい方を再生**
- `TextToSpeech.stop()` → `TextToSpeech.speak()` の順序
- 理由: 「まもなく〇〇」系の緊急案内が最優先

### 8.6 オーディオフォーカス

- `AudioFocusRequest` で `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` を使用
- 音楽再生中は音量を一時的に下げて案内を再生
- 案内終了後にオーディオフォーカスを解放

---

## 9. バックグラウンド・ForegroundService

### 9.1 TripSession

```kotlin
mapboxNavigation.startTripSession(
    withForegroundService = true,
)
```

### 9.2 Notification

- **チャネル:** ナビゲーション用の専用 NotificationChannel
- **表示内容:**
  - タイトル: 次マニューバ情報（例: `300m 先、左折`）
  - テキスト: ETA（例: `到着 11:05 · 残り 3.4km`）
- **更新:** `RouteProgressObserver` のコールバック毎に更新
- **タップ動作:** アプリのナビ画面を開く

### 9.3 画面 ON 維持

```kotlin
// ナビ開始時
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

// ナビ終了時
window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
```

---

## 10. ルート管理

### 10.1 Source of Truth: RoutesObserver

現在の Compose StateFlow 直接駆動から、RoutesObserver を source of truth に変更する。

```
RoutesObserver (SDK からの通知)
  ↓
RouteManager.onRoutesChanged(result)
  ├─→ StateFlow<routes> 更新
  ├─→ RouteLineApi.setNavigationRoutes()
  ├─→ CameraManager.viewportDataSource.onRouteChanged()
  └─→ UI（代替ルート表示等）

対象イベント:
- 初回ルート設定（setNavigationRoutes()）
- リルート（自動）
- トラフィックリフレッシュ（5 分毎）
- 代替ルート探索（5 分毎）
- ユーザーによるルート切替（タップ）
```

### 10.2 ルートライン消失エフェクト

```kotlin
val routeLineApiOptions = MapboxRouteLineApiOptions.Builder()
    .vanishingRouteLineEnabled(true)
    .build()

// RouteProgressObserver 内:
routeLineApi.updateWithRouteProgress(routeProgress) { result ->
    routeLineView.renderRouteLineUpdate(style, result)
}
```

### 10.3 リルート

- **方式:** 自動採用（Mapbox デフォルト）
- **通知:** トースト「ルートを再計算しました」
- **TTS:** テンプレート #14「ルートを再計算しました」を再生
- **RoutesObserver** 経由で新ルートが自動反映

### 10.4 代替ルート

- ナビ中も代替ルートを半透明で表示
- Mapbox SDK が 5 分毎に自動探索
- RoutesObserver 経由で受信
- タップで切替可能

---

## 11. 経由地

- Phase 1 では全て **stop**（立ち寄り地点）
- 各経由地で Arrival イベントが発生し、TTS テンプレート #13「経由地に到着しました」を再生
- leg が分かれるため、ETA は次の経由地（または目的地）までの値
- DataSource の API は将来 silent waypoint に対応可能な設計にしておく

---

## 12. レイアウト切替

### 12.1 WindowSizeClass による分岐

```kotlin
val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

when {
    windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT -> {
        // 縦画面レイアウト: マニューバ上部、トリップカード下部
        CompactGuidanceLayout(...)
    }
    else -> {
        // 横画面レイアウト: マニューバ右上、トリップカード右下
        ExpandedGuidanceLayout(...)
    }
}
```

### 12.2 Android Auto への展望

- Expanded レイアウトは将来の Android Auto (Phase 3) でほぼそのまま再利用可能
- `core/navigation` のロジックは Phone / Auto で完全共有
- UI コンポーネントのみ Platform 固有（Phone: Compose、Auto: CarApp Template）

---

## 13. onNavigationClicked の実装フロー

```
ユーザーが「ナビ開始」ボタンをタップ
  ↓
HomeMapViewEvent.OnNavigationStarted を発行
  ↓
HomeMapViewModel.onNavigationStarted()
  ├── guidanceSessionManager.startSession(routes, selectedIndex)
  │     ├── mapboxNavigation.startTripSession(withForegroundService = true)
  │     ├── 全 Observer を登録
  │     ├── Android TTS を初期化
  │     └── ForegroundService 通知を作成
  ├── cameraManager.requestCameraFollowing(pitch3D = true)
  │     ├── viewportDataSource.followingPadding = (上方に余白大)
  │     └── navigationCamera.requestNavigationCameraToFollowing()
  ├── NavigationState → ActiveGuidance に遷移
  └── FLAG_KEEP_SCREEN_ON を設定
```

---

## 14. 実装順序

以下の順序で段階的に実装する。各ステップはビルド可能な状態を維持する。

### Step 1: core/model にデータモデル追加

- `NavigationState.kt`
- `GuidanceUiState.kt`
- `ManeuverInfo.kt`
- `TripProgressInfo.kt`

### Step 2: core/navigation モジュール新設

- モジュール作成（build.gradle.kts + Convention Plugin）
- `RouteManager.kt`（既存 HomeMapNavigationManager のルート部分を移植）
- `CameraManager.kt`（既存 HomeMapNavigationManager のカメラ部分を移植）
- `GuidanceSessionManager.kt`（新規、TripSession + Observer 群）
- `NavigationModule.kt`（Koin DI）

### Step 3: 既存コードのリファクタリング

- `HomeMapNavigationManager` を削除、3 Manager に置換
- `HomeMapViewModel` に `NavigationState` の `StateFlow` を追加
- `HomeMapsMapEffectContent` を RoutesObserver 駆動に変更
- `HomeMapViewEvent` に `OnNavigationStarted` / `OnNavigationStopped` を追加
- `HomeMapSheetContent` の `onNavigationClicked` を接続

### Step 4: ナビ UI コンポーネント

- `NavigationColors.kt`
- `HomeMapGuidanceManeuverPanel.kt`
- `HomeMapGuidanceTripCard.kt`
- `HomeMapGuidanceControlColumn.kt`
- `HomeMapGuidanceCallout.kt`
- `HomeMapGuidanceArrivalScreen.kt`
- `HomeMapGuidanceReturnButton.kt`
- `HomeMapScreenContent` に WindowSizeClass 分岐を追加

### Step 5: ForegroundService + 通知

- NotificationChannel 作成
- 通知の構築・更新ロジック
- FLAG_KEEP_SCREEN_ON の制御

### Step 6: TTS パイプライン

- Android TextToSpeech の初期化・ライフサイクル管理
- 日本語テンプレートエンジン（maneuver.type + modifier → 日本語テキスト）
- VoiceInstructionsObserver → テンプレート適用 → TTS 再生
- オーディオフォーカス管理
- キュー管理（前の発話を切って新しい方を再生）

### Step 7: 到着フロー

- ArrivalObserver の登録
- 到着画面 UI
- 10 秒タイマー + 閉じるボタン
- ナビ終了処理（stopTripSession / clearRoutes / FLAG 解除）

---

## 15. Mapbox SDK バージョン

- **Navigation SDK:** `3.21.0-rc.1`（RC のまま進行）
- **Maps SDK:** `11.20.2`
- stable リリース時に差分対応を行う（RC → stable の差分は通常小さい）

---

## 16. 課金境界の注意

- `startTripSession()` + `setNavigationRoutes(routes)` で **Active Guidance** 扱いになる
- RoutePreview 状態では TripSession を開始しない（Free Drive 課金を避ける）
- Phase 1 の無料枠: 100 MAU / 1,000 trips（個人利用では十分）
