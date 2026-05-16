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
    data object RoutePreview : NavigationState
    data object ActiveGuidance : NavigationState
    data object Arrival : NavigationState
    data object FreeDrive : NavigationState  // Phase 1 では未使用、定義のみ
}
```

> **設計判断:** NavigationState は全て data object（マーカー）とする。各状態のデータは別の StateFlow で管理する:
> - RoutePreview のデータ → `routeResults` / `selectedRouteIndex` / `waypoints`（既存 StateFlow）
> - ActiveGuidance のデータ → `GuidanceSessionManager.guidanceUiState`（別 StateFlow）
> - Arrival のデータ → `GuidanceSessionManager.arrivalInfo`（別 StateFlow）
>
> **理由:** NavigationState にデータを埋め込むと、RouteProgress の毎秒更新で navigationState が変わり、全 UI が毎秒再コンポーズされる。マーカー方式なら navigationState は状態遷移時のみ変わる。

### 2.2 状態遷移図

```
                  ┌──────────┐
          ┌──────►│ Browsing │◄──────────────────────────┐
          │       └────┬─────┘                           │
          │            │ 検索開始                    閉じる / タイマー
          │            ▼                                 │
          │       ┌──────────┐                           │
          ├───────┤  Search  │ ← 検索閉じるで Browsing   │
          │       └────┬─────┘                           │
          │            │ ルート検索                       │
          │            ▼                                 │
          │    ┌──────────────┐                          │
          ├────┤ RoutePreview │◄──── ルート再検索         │
          │    └──────┬───────┘ ← 閉じるで Browsing      │
          │           │ ナビ開始                          │
          │           ▼                                  │
          │  ┌────────────────┐                          │
          ├──┤ ActiveGuidance │◄──── リルート（自動）     │
          │  └──┬──────────┬──┘                          │
          │     │          │ ✕ / back（手動終了）         │
          │     │ 目的地到着 └───────────────────┐        │
          │     ▼                               │        │
          │┌──────────┐                         │        │
          ││ Arrival  │─────────────────────────┼────────┘
          │└──────────┘                         │
          │                                     │
          └─────────────────────────────────────┘
```

> **手動終了フロー:** トリップカードの ✕ ボタン **または system back キー** → `stopTripSession()` / `clearRoutes()` / FLAG 解除 → `NavigationState.Browsing` に直接遷移（Arrival 画面は表示しない、確認ダイアログなし）
>
> **その他の Browsing 復帰パス:**
> - Search → Browsing: 検索を閉じる（OnDismissSearchResult）
> - RoutePreview → Browsing: ルートを閉じる（OnDismissRoutes）

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

core/navigation/                     ← Mapbox 依存のナビ管理（新規モジュール、androidMain のみ）
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
- reorderedRoutes() の提供
- setRoutes() / selectRoute() / clearRoutes()
- RoutesObserver 内で冪等チェック（ルートが実際に変わった場合のみ StateFlow 更新）
```

> **注意:** RouteLineApi / RouteLineView は描画に `MapView` の `Style` が必要なため、**feature/home の HomeMapsMapEffectContent に残す**。RouteManager はデータ管理のみ。描画は UI 層が RouteManager の StateFlow を observe して RouteLineApi に渡す。

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
  │                                   ├── StateFlow<routes> 更新（冪等チェック付き）
  │                                   └── CameraManager.onRouteChanged()
  │                                   ↓ UI 層が StateFlow を observe
  │                                   HomeMapsMapEffectContent → RouteLineApi.setNavigationRoutes()
  │
  ├── LocationObserver ────────────► CameraManager
  │                                   ├── NavigationLocationProvider.changePosition()
  │                                   └── viewportDataSource.onLocationChanged()
  │
  ├── RouteProgressObserver ───────► GuidanceSessionManager
  │                                   ├── StateFlow<GuidanceUiState>
  │                                   └── CameraManager.onRouteProgressChanged()
  │                                   ↓ UI 層が RouteProgress を受けて
  │                                   HomeMapsMapEffectContent → RouteLineApi.updateWithRouteProgress()
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
    val isTtsAvailable: Boolean,
    val isLocationStale: Boolean,
)
```

### 4.4 ArrivalInfo

```kotlin
@Immutable
data class ArrivalInfo(
    val destinationName: String,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
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
- ルート候補の時間差分 CallOut とは別の Point fixed CallOut として扱う
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
| 16 | `{distance}先、鋭角左折です` | modifier=sharp left |
| 17 | `{distance}先、鋭角右折です` | modifier=sharp right |

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

### 9.1 TripSession と ForegroundService

Mapbox SDK は `withForegroundService = true` で **SDK 自身が ForegroundService と Notification を管理する**。独自に ForegroundService を作ると通知が二重になるため、**Mapbox の仕組みをそのまま利用する**。

```kotlin
mapboxNavigation.startTripSession(
    withForegroundService = true,
)
```

### 9.2 Notification

- **Phase 1:** Mapbox SDK のデフォルト通知をそのまま使用
- **Phase 2:** `TripNotification` インターフェースを実装して独自通知に差し替え

```kotlin
// Phase 2 での差し替え例:
class OneNaviTripNotification(context: Context) : TripNotification {
    override fun getNotification(): Notification { /* 次マニューバ + ETA */ }
    override fun onTripSessionStarted() { /* チャネル作成等 */ }
    override fun onTripSessionStopped() { /* クリーンアップ */ }
    override fun getNotificationId(): Int = NOTIFICATION_ID
}

// NavigationOptions で登録（OneNaviApplication.kt）
NavigationOptions.Builder(context)
    .tripNotification(OneNaviTripNotification(context))
    .build()
```

> **理由:** 独自 ForegroundService を作ると Mapbox SDK の service と競合する。SDK の `TripNotification` をカスタマイズする方が安全。Phase 1 ではデフォルト通知で十分機能する。

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
  ├─→ StateFlow<routes> 更新（冪等チェック: ルートが実際に変わった場合のみ）
  └─→ CameraManager.viewportDataSource.onRouteChanged()
  ↓ UI 層が StateFlow を observe
HomeMapsMapEffectContent
  ├─→ RouteLineApi.setNavigationRoutes(routes, alternativesMetadata)
  └─→ RouteLineView.renderRouteDrawData(style, result)

対象イベント:
- 初回ルート設定（setNavigationRoutes()）
- リルート（自動）
- トラフィックリフレッシュ（5 分毎）
- 代替ルート探索（5 分毎）
- ユーザーによるルート切替（タップ）
```

> **循環呼び出し防止:** `setNavigationRoutes()` を呼ぶと RoutesObserver がコールバックされる。RouteManager 内で冪等チェック（ルートの ID や geometry が実際に変わったかを比較）を行い、不要な StateFlow 更新を防ぐ。

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

### 11.1 経由地到着後の継続

```kotlin
ArrivalObserver {
    onWaypointArrival(routeProgress) {
        tts.speak("経由地に到着しました")
        mapboxNavigation.navigateNextRouteLeg()
        // UI は ActiveGuidance のまま
        // マニューバパネルが次 leg の情報に自動切替
    }
    onFinalDestinationArrival(routeProgress) {
        // NavigationState.Arrival に遷移
    }
}
```

> **設計判断:** `AutoArrivalController` で即継続（Google Maps と同じ）。停車確認 UI は表示しない。運転中のタップ操作を避けるため。

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

### 13.1 呼び出し順序と課金境界

```
前提: RoutePreview 時点で setNavigationRoutes() は呼び済み
      （ルートライン描画のために必要）

ユーザーが「ナビ開始」ボタンをタップ
  ↓
HomeMapViewEvent.OnNavigationStarted を発行
  ↓
HomeMapViewModel.onNavigationStarted()
  ├── guidanceSessionManager.startSession()
  │     ├── mapboxNavigation.startTripSession(withForegroundService = true)
  │     │   ↑ ルートが既に set 済みなので即 Active Guidance に入る
  │     │   ↑ この時点で Mapbox 課金の trip カウントが増加
  │     ├── 全 Observer を登録
  │     └── Android TTS を初期化（非同期、失敗時は isTtsAvailable=false）
  ├── cameraManager.requestCameraFollowing(pitch3D = true)
  │     ├── viewportDataSource.followingPadding = (上方に余白大)
  │     └── ※初回 location 受信後に Following が安定する
  ├── NavigationState → ActiveGuidance に遷移
  └── FLAG_KEEP_SCREEN_ON を設定
```

> **重要:** `setNavigationRoutes()` は RoutePreview 時に既に呼ばれている。ナビ開始時は `startTripSession()` のみ呼ぶ。この順序が課金境界に直結する（RoutePreview では TripSession を開始しないので Active Guidance 課金されない）。

### 13.2 TTS 初期化

- TTS は `startSession()` 内で初期化（`TextToSpeech(context, listener)`）
- 初期化は非同期で 1-2 秒かかる。初期化完了前の VoiceInstructions は破棄される
- 初期化失敗時は `isTtsAvailable = false` を設定し、視覚ガイドのみで継続

### 13.3 「まもなく」テンプレートのトリガー

- Mapbox SDK の `VoiceInstructions` が距離に応じて「まもなく」相当のタイミングを制御する
- SDK が提供する `announcement` テキストに "まもなく" が含まれるか、または `distanceAlongGeometry` が小さい場合にテンプレート #5/#6 を使用
- 独自の閾値定義は Phase 2（速度連動タイミング）で実装

---

## 14. Observer ライフサイクル

### 14.1 登録・解除タイミング

| Observer | 登録タイミング | 解除タイミング | 管理者 |
|----------|-------------|-------------|--------|
| RoutesObserver | MapboxNavigation attach 時 | MapboxNavigation detach 時 | RouteManager |
| LocationObserver | MapboxNavigation attach 時 | MapboxNavigation detach 時 | CameraManager |
| RouteProgressObserver | startSession() 時 | stopSession() 時 | GuidanceSessionManager |
| VoiceInstructionsObserver | startSession() 時 | stopSession() 時 | GuidanceSessionManager |
| BannerInstructionsObserver | startSession() 時 | stopSession() 時 | GuidanceSessionManager |
| OffRouteObserver | startSession() 時 | stopSession() 時 | GuidanceSessionManager |
| ArrivalObserver | startSession() 時 | stopSession() 時 | GuidanceSessionManager |
| RoadNameObserver | startSession() 時 | stopSession() 時 | GuidanceSessionManager |

> **設計意図:** RoutesObserver と LocationObserver はナビ外（RoutePreview / Browsing）でも必要なため、MapboxNavigation のライフサイクルに合わせる。ナビ固有の Observer は startSession/stopSession で明示的に管理する。

### 14.2 MapboxNavigationObserver パターン

```kotlin
// RouteManager / CameraManager は MapboxNavigationObserver を実装
class RouteManager : MapboxNavigationObserver {
    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.registerRoutesObserver(routesObserver)
    }
    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterRoutesObserver(routesObserver)
    }
}
```

---

## 15. エラーハンドリング（Phase 1）

### 15.1 対応するエラーケース

| エラー | 検出方法 | 対応 |
|--------|---------|------|
| TTS 初期化失敗 | `TextToSpeech.OnInitListener` の status | `isTtsAvailable = false`、音声ボタンに「利用不可」表示、視覚ガイドのみで継続 |
| 位置権限の途中剥奪 | LocationObserver でのコールバック停止 or runtime check | ナビ強制終了 → Browsing に戻る → 権限要求ダイアログ |
| GPS 信号喪失 | `RouteProgress.stale` or LocationObserver のタイムアウト | `isLocationStale = true`、UI に「GPS なし」バッジ表示、enhanced location で補完継続 |

### 15.2 Phase 2 以降で対応するエラーケース

- process death からのセッション復帰
- POST_NOTIFICATIONS 未許可（Android 13+）
- battery optimization によるプロセス kill
- screen rotation 時の状態復元

---

## 16. Enhanced Location と Puck 切替

### 16.1 ナビ開始時

ナビ開始時に MapView の location component を `NavigationLocationProvider` に切り替える:

```kotlin
// ナビ開始時（HomeMapsMapEffectContent 内）
mapView.location.setLocationProvider(cameraManager.navigationLocationProvider)

// ナビ終了時
mapView.location.setLocationProvider(null) // デフォルトに戻す
// or location.enabled = true で raw GPS に復帰
```

### 16.2 LocationObserver → NavigationLocationProvider → MapView

```
LocationObserver.onNewRawLocation(rawLocation)
LocationObserver.onNewLocationMatcherResult(matcherResult)
  ↓
CameraManager:
  navigationLocationProvider.changePosition(
      matcherResult.enhancedLocation,
      matcherResult.keyPoints,
  )
  viewportDataSource.onLocationChanged(matcherResult.enhancedLocation)
  viewportDataSource.evaluate()
  ↓
MapView の location puck が enhanced location に追従
```

> **重要:** 現在のコード（HomeMapsMapEffectContent L136-139）は raw GPS の `createDefault2DPuck` を使用している。ナビ中は `NavigationLocationProvider` を経由した map-matched / enhanced location に切り替えること。

---

## 17. ルートライン消失の詳細

### 17.1 必要な処理

vanishingRouteLineEnabled は 2 つの更新元が必要:

```kotlin
// 1. RouteProgressObserver 内（GuidanceSessionManager が通知）
routeLineApi.updateWithRouteProgress(routeProgress) { result ->
    routeLineView.renderRouteLineUpdate(style, result)
}

// 2. OnIndicatorPositionChangedListener 内（スムーズな消失のため）
mapView.location.addOnIndicatorPositionChangedListener { point ->
    val result = routeLineApi.updateTraveledRouteLine(point)
    routeLineView.renderRouteLineUpdate(style, result)
}
```

> **注意:** `updateWithRouteProgress` だけでは消失がカクつく。`updateTraveledRouteLine` を位置更新ごとに呼ぶことでスムーズになる。

### 17.2 代替ルートの重複部分

```kotlin
// 代替ルートの metadata を取得して主ルートとの重複部分を非表示にする
val alternativesMetadata = mapboxNavigation.getAlternativeMetadataFor(routes)
routeLineApi.setNavigationRoutes(routes, alternativesMetadata) { result ->
    routeLineView.renderRouteDrawData(style, result)
}
```

---

## 18. 実装順序

以下の順序で段階的に実装する。各ステップはビルド可能な状態を維持する。

### Step 1: core/model にデータモデル追加

- `NavigationState.kt`
- `GuidanceUiState.kt`
- `ManeuverInfo.kt`
- `TripProgressInfo.kt`
- `ArrivalInfo.kt`

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

- Mapbox SDK のデフォルト通知をそのまま使用（Phase 1）
- FLAG_KEEP_SCREEN_ON の制御
- Enhanced Location への puck 切替（ナビ開始/終了時）

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

## 19. Mapbox SDK バージョン

- **Navigation SDK:** `3.21.0-rc.1`（RC のまま進行）
- **Maps SDK:** `11.20.2`
- stable リリース時に差分対応を行う（RC → stable の差分は通常小さい）

---

## 20. 課金境界の注意

- `startTripSession()` + `setNavigationRoutes(routes)` で **Active Guidance** 扱いになる
- RoutePreview 状態では TripSession を開始しない（Free Drive 課金を避ける）
- Phase 1 の無料枠: 100 MAU / 1,000 trips（個人利用では十分）
