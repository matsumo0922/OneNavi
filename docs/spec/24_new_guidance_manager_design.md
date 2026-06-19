# 24. New Guidance Manager 設計 — NavigationView を地図描画専用にした自前案内設計

## 0. このドキュメントの目的

OneNavi の経路案内 (Preview / Guidance) を **すべて自前実装** するための設計仕様。
地図描画は Google Maps の `NavigationView` をビューワとしてのみ使い、ルート探索は
外部API ライブラリに委譲する。turn-by-turn の進捗追跡・callout 表示・音声案内も
自前で行う。

context を失った状態からでも本ドキュメント単体で実装着手できる粒度で記述する。

### 0.0 改訂履歴

- **v2 (現行)**: Google Navigation SDK の `Navigator` を使った案内 (route_token + chunk
  分割 + `setDestinations`) を **全廃**。`NavigationView` は地図描画専用にし、polyline /
  callout / 音声案内を自前で持つ方式に変更。外部API ライブラリは元々デコード済みの
  polyline と GP / 交差点 / maneuver データを返すため、Routes API による polyline 再現
  (旧 `ExtApiRouteRefiner` 系) は不要になった。
- **v1 (廃止)**: spec/23 の Routes API + waypoint sampling アルゴリズムで外部ルートを
  再現し、`Navigator.setDestinations(waypoints, CustomRoutesOptions(route_token))` で
  案内する方式。`ExtApiRouteRefiner` / `RoutesApiClient` / `DefaultRoutesApiClient` /
  `PolylineDecoder` / `NewNavigationSdkManager` / `RefinedRoute` / `RefinedChunk` /
  `RoutesApiWaypoint` はこの方式の遺物で、v2 で削除済み。

### 0.1 前提とする決定事項のサマリ

| 項目 | 決定 |
|---|---|
| 実装モジュール | ルート探索・案内ロジックは `:core:navigation`、UI は `:feature:map` |
| 地図 View | 既存 `MapItem.kt` の `NavigationView` を **地図描画専用** で流用 (内蔵案内 UI は全 OFF 済み)。`Navigator` は使わない |
| ルート探索 | 外部API ライブラリ → `ExtApiRouteDataSource` → `RouteRepository`。デコード済み polyline + GP / 交差点 / maneuver データを返す |
| ルート描画 | `GoogleRoute.geometry` を自前 `MapPolyline` で描画 |
| 走行進捗 / リルート検知 / 到達検知 | **自前**。生 GPS (`FusedLocationProviderClient`) を polyline に投影して算出する (`ExtApiGuidanceTracker` 系) |
| callout / 音声案内 | 外部API ライブラリ由来の GP テキストを進捗にトリガーして自前で発話 (`ExtApiAnnouncementScheduler` + `tts/*`) |
| route_token / chunk 分割 | **不要 (廃止)** |
| 旧 `:feature:home` 系 | nav graph から外れた旧画面 (`HomeMapViewModel` 等) はそのまま残置。これが参照する legacy の `CameraManager` / `RouteManager` / `NavigationSdkManager` / `GuidanceSessionManager` も残置 (新実装が安定したら別 PR で整理) |
| 課金・ライセンス | OSS 公開停止済み・個人利用前提。Mobility Services Agreement 等の確認は不要 |

### 0.2 用語

- **外部API ライブラリ**: 別管理のプライベートリポジトリに存在する 外部API 提供元 API の
  ラッパライブラリ。本リポジトリ (OneNavi) からは公開 interface 経由で参照する。
  本ドキュメントは Kotlin 統合側のみ扱う
- **外部API 提供元**: 外部API 提供事業者の代替表記。具体名は本ドキュメント・コード上に一切
  露出させない (`CLAUDE.md` §厳命)
- **GP (案内ポイント)**: 外部API ライブラリの案内テキスト発火点。"およそ 500m 先
  三軒寺左方向です" のようなテキストと発話距離を持つ
- **進捗投影**: 生 GPS 位置を polyline 上の最近接セグメントに射影し、単調増加な走行距離 /
  残距離 / 次 GP / 次交差点 / 次 maneuver を算出する処理 (`ExtApiGuidanceTracker`)

---

## 1. 全体アーキテクチャ

### 1.1 役割分担

```
┌──────────────────────────────────────────────────────────────┐
│ feature/map (Compose UI)                                     │
│   MapScreen / MapItem (NavigationView = 地図描画のみ)        │
│       │           │                                          │
│       ▼           ▼                                          │
│   ┌──────────────────┐   ┌──────────────────────┐           │
│   │ NewRouteManager  │   │ NewGuidanceManager   │           │
│   │ (Preview 期)     │──▶│ (Guidance 期)        │           │
│   └──────────────────┘   └──────────────────────┘           │
│       │                       │                              │
│       │                       ▼ (今後接続)                   │
│       │                  ┌──────────────────────────────┐    │
│       │                  │ ExtApiGuidanceTracker        │    │
│       │                  │ ExtApiAnnouncementScheduler  │    │
│       │                  │ ExtApiRerouteDetector + TTS  │    │
│       │                  └──────────────────────────────┘    │
└───────┼──────────────────────────────────────────────────────┘
        ▼
┌──────────────────────────────────────────────────────────────┐
│ core/navigation                                              │
│   ExtApiRouteDataSource (RouteDataSource impl)               │
│     └─ 外部API ライブラリ (別管理プライベート repo)     │
│          - ルート探索のみ                                     │
│          - デコード済み polyline + GP / 交差点 / maneuver     │
│          - リルート検知・GPS 監視・到達検知は持たない          │
│                                                              │
│   FusedLocationProviderClient (生 GPS)                       │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ Google Maps (NavigationView 経由)                            │
│   - 地図タイル描画 / カメラ操作 / 自前 polyline/marker の親  │
│   - Navigator は使わない (案内 UI / route_token / 進捗監視   │
│     はすべて自前)                                            │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 データフロー (Preview から Guidance まで)

```
[1. ユーザが目的地確定]
    ↓
[2. NewRouteManager.searchRoutes(waypoints)]
    ↓  state = Searching
[3. 外部API ライブラリにルート探索を問い合わせ (RouteRepository.searchRoutes)]
    ↓  List<RouteResult>。platformRoute = GoogleRoute (geometry を含む)
[4. state = Ready(routes: List<GoogleRoute>, selectedIndex=0)]
    ↓
[5. Compose 側]
    │   - MapRoutePreviewSheet で候補一覧 (所要時間 / 距離)
    │   - MapPolyline で全候補の geometry を描画 (selected = 通常色, 他 = 薄色)
    ↓
[6. ユーザがルート選択切り替え → selectedIndex 更新 → polyline 色だけ更新]
    ↓
[7. 「案内開始」ボタン → NewGuidanceManager.startGuidance(selectedRoute)]
    ↓  state = Guiding
[8. Guidance 中 (今後実装)]
    │   - 生 GPS を ExtApiGuidanceTracker で polyline に投影
    │   - 進捗に応じて GP テキストを TTS で発話、callout を更新
    │   - polyline からの逸脱を ExtApiRerouteDetector で検知
    ↓
[9a. 目的地到達 → state = Arrived]
[9b. 逸脱検知 → 外部API ライブラリで再探索 → 新 geometry に差し替え (state = Rerouting → Guiding)]
```

---

## 2. 既存実装との関係

### 2.1 旧 `:feature:home` 系の扱い

`composeApp` の nav graph は `homeEntry()` をコメントアウトし `mapEntry()` のみ有効
(`Destination.Home` も `MapScreen` に解決される)。旧 `feature/home` のマップ画面
(`HomeMapViewModel` / `HomeMapScreenContent` 等) と、それが参照する `:core:navigation`
の legacy クラス (`CameraManager` / `RouteManager` / `NavigationSdkManager` /
`GuidanceSessionManager`) は **触らない**。

理由: これらを消すと旧 `feature/home` の UI を大改修することになるため。「UI は極力
触らない」方針を優先する。新実装 (`NewRouteManager` / `NewGuidanceManager`) が安定したら、
旧 `feature/home` ごと整理する PR を別途行う。

> 注: legacy `NavigationSdkManager` は `composeApp/MainActivity.kt` からも `inject` され、
> 起動時に Navigation SDK の terms ダイアログを出すために初期化される。これも当面残置。

### 2.2 ファイル配置

```
core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/
├── newguidance/
│   ├── NewRouteManager.kt           # Preview 期。外部ライブラリにルート探索を投げ List<GoogleRoute> を保持
│   ├── NewGuidanceManager.kt        # Guidance 期。現状は GuidanceState の state machine スタブ
│   └── model/
│       ├── RoutePreviewState.kt     # Idle / Searching / Ready(routes, selectedIndex) / Failed
│       └── GuidanceState.kt         # Idle / Guiding / Rerouting / Arrived / Failed
├── extapi/                          # 外部API ライブラリ統合層 + 自前進捗/音声の部品 (既存)
│   ├── ExtApiRouteDataSource.kt     # RouteDataSource 実装。GoogleRoute.geometry を構築
│   ├── ExtApiRouteRegistry.kt       # GoogleRoute.id → ExtApiRoutePayload (route + guidance)
│   ├── ExtApiGuidanceTracker.kt     # polyline 投影による進捗追跡 (Guidance 接続予定)
│   ├── ExtApiAnnouncementScheduler.kt  # GP テキストの発話スケジューラ (同上)
│   ├── ExtApiRerouteDetector.kt     # polyline 逸脱検知 (同上)
│   ├── ExtApiSsmlSpeaker.kt
│   ├── ExtApiAuthGateway.kt / ExtApiClientProvider.kt
├── tts/                             # TTS エンジン群 (Google Cloud TTS + Android TTS フォールバック, 既存)
└── (legacy) CameraManager / RouteManager / NavigationSdkManager / GuidanceSessionManager  # 残置

feature/map/src/androidMain/kotlin/me/matsumo/onenavi/feature/map/
├── MapScreen.android.kt             # RoutePreview 分岐で MapPolyline / MapRoutePreviewSheet
├── MapViewModel.kt                  # NewRouteManager / NewGuidanceManager を注入
├── MapItem.kt                       # NavigationView wrap (地図描画専用)。getMapAsync で GoogleMap を公開
├── components/MapPolyline.kt        # ImmutableList<RoutePoint> を GoogleMap に描画
├── components/MapMarker.kt
├── components/bottomsheet/MapRoutePreviewSheet.kt   # ImmutableList<GoogleRoute>
└── state/MapScreenState.kt          # Browsing / SearchResultsList / PlaceDetails / RoutePreview / Navigating / Arrived
```

DI は `:core:navigation` の `NavigationModule.kt` で `NewRouteManager` / `NewGuidanceManager` を
`single` 登録、`:feature:map` の `MapModule` で `MapViewModel` を `viewModelOf` 登録。

---

## 3. クラス構成

### 3.1 `NewRouteManager` (Preview 期)

`MapScreenState.RoutePreview` を駆動する状態保持クラス。

```kotlin
class NewRouteManager(
    private val routeRepository: RouteRepository,
) {
    private val _state = MutableStateFlow<RoutePreviewState>(RoutePreviewState.Idle)
    val state: StateFlow<RoutePreviewState> = _state.asStateFlow()

    suspend fun searchRoutes(waypoints: List<RouteWaypoint>) {
        require(waypoints.size >= 2)
        _state.value = RoutePreviewState.Searching

        val origin = waypoints.first().toRoutePoint()
        val destination = waypoints.last().toRoutePoint()
        val intermediates = waypoints.subList(1, waypoints.lastIndex).map { it.toRoutePoint() }

        runCatching {
            val results = routeRepository.searchRoutes(
                originLatitude = origin.latitude, originLongitude = origin.longitude,
                destinationLatitude = destination.latitude, destinationLongitude = destination.longitude,
                intermediateWaypoints = intermediates.map { it.latitude to it.longitude },
            ).getOrThrow()
            require(results.isNotEmpty())
            results.map { requireNotNull(it.platformRoute as? GoogleRoute) }
        }.onSuccess { routes ->
            _state.value = RoutePreviewState.Ready(routes.toImmutableList(), selectedIndex = 0)
        }.onFailure { error ->
            _state.value = RoutePreviewState.Failed(error)
        }
    }

    fun selectRoute(index: Int) { /* Ready のときだけ selectedIndex を更新 */ }
    fun reset() { _state.value = RoutePreviewState.Idle }
}
```

`RoutePreviewState`:

```kotlin
@Immutable
sealed interface RoutePreviewState {
    data object Idle : RoutePreviewState
    data object Searching : RoutePreviewState
    @Immutable
    data class Ready(val routes: ImmutableList<GoogleRoute>, val selectedIndex: Int) : RoutePreviewState {
        val selectedRoute: GoogleRoute get() = routes[selectedIndex]
    }
    @Immutable
    data class Failed(val error: Throwable) : RoutePreviewState
}
```

> 現状 `ExtApiRouteDataSource` は候補 1 本固定 (priority=Recommended)。複数候補化は外部ライブラリ
> 側の対応と合わせて別途。複数候補化したら `Ready.routes` がそのまま 2 本以上になる。

### 3.2 `NewGuidanceManager` (Guidance 期)

選択ルートで案内を実行する。現状は state machine のスタブ。

```kotlin
class NewGuidanceManager {
    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    fun startGuidance(route: GoogleRoute) { _state.value = GuidanceState.Guiding }
    fun stopGuidance() { _state.value = GuidanceState.Idle }
    fun release() { stopGuidance() }
}
```

`GuidanceState`:

```kotlin
@Immutable
sealed interface GuidanceState {
    data object Idle : GuidanceState
    data object Guiding : GuidanceState
    data object Rerouting : GuidanceState
    data object Arrived : GuidanceState
    @Immutable
    data class Failed(val message: String) : GuidanceState
}
```

**今後の実装 (§5)**: `startGuidance` で
`ExtApiRouteRegistry` から `ExtApiRoutePayload` (route + guidance) を引き、
`ExtApiGuidanceTracker.attach(guidance)` + `FusedLocationProviderClient` の位置更新を購読し、
進捗に応じて `ExtApiAnnouncementScheduler` で GP を発話、`ExtApiRerouteDetector` で逸脱検知、
逸脱したら `RouteRepository.searchRoutes(currentLocation, destination)` で再探索する。

---

## 4. RoutePreview フェーズ

### 4.1 シーケンス

```
ユーザ目的地確定
   │
   ▼
MapViewModel: pushScreenState(RoutePreview(waypoints, ...)) ; NewRouteManager.searchRoutes(waypoints)
   │  state = Searching
   ▼
RouteRepository.searchRoutes() → ExtApiRouteDataSource → 外部API ライブラリ
   │  List<RouteResult>。各 RouteResult.platformRoute = GoogleRoute (geometry を持つ)
   ▼
state = Ready(routes, selectedIndex=0)
   ▼
Compose UI 再構成
   ├ MapEffect: 全候補 route.geometry を MapPolyline で描画 + destination marker
   └ MapScreenBottomSheetContent: MapRoutePreviewSheet(routes, selectedIndex)
```

### 4.2 描画方針

| layer | 描画主体 | 内容 |
|---|---|---|
| 全候補 polyline | 自前 `MapPolyline` (`GoogleMap.addPolyline`) | selected = 通常色、unselected = 薄色 (色だけ差し替え) |
| destination / 経由地 marker | 自前 `MapMarker` (`GoogleMap.addMarker`) | `screenState.waypoints.drop(1)` |

`NavigationView` は地図タイルとカメラだけ提供する。案内系の SDK overlay は `MapItem.kt` で
全て `false` に設定済み (`setHeaderEnabled(false)` 等)。

### 4.3 ルート選択切り替え

`selectRoute(index)` → `RoutePreviewState.Ready.selectedIndex` 更新 → Compose が再構成され、
`MapPolyline` の色 (selected / unselected) と `MapRoutePreviewSheet` のハイライトが更新される。
Preview 中は `startGuidance()` を呼ばない。案内開始は明示的なボタン押下 (`MapUiEvent.OnNavigationStart`)。

### 4.4 ロード UI

`RouteRepository.searchRoutes` 完了まで `RoutePreviewState.Searching` のまま画面に何も出さない。
ローディング表示は別途追加 (本ドキュメント範囲外)。Routes API を介さなくなったので待ち時間は
外部ライブラリの 1 リクエスト分のみ。

---

## 5. Guidance フェーズ (今後実装)

> 現状 `NewGuidanceManager` は `GuidanceState` の遷移スタブのみ。以下は実装方針。

### 5.1 開始

```
NewGuidanceManager.startGuidance(route: GoogleRoute)
   ├ ExtApiRouteRegistry.get(route.id) → ExtApiRoutePayload (route + guidance)
   ├ ExtApiGuidanceTracker.attach(payload.guidance)   # polyline / 交差点 / GP の累積距離を前計算
   ├ FusedLocationProviderClient で位置更新を購読 → tracker.onLocation(lat, lng)
   ├ ExtApiAnnouncementScheduler を tracker.state に接続 (GP 到達で発話)
   ├ ExtApiRerouteDetector を位置更新に接続
   └ state = Guiding
```

`ExtApiGuidanceTracker` / `ExtApiAnnouncementScheduler` / `ExtApiRerouteDetector` は既に
`:core:navigation` に実装済み (旧 `GuidanceSessionManager` がこれらを束ねていたが、そちらは
`RoadSnappedLocationProvider` 依存だった)。`NewGuidanceManager` ではこれらを **生 GPS** に
繋ぎ直して使う。

### 5.2 進捗・callout・音声

- 進捗 (走行距離 / 残距離 / 残時間 / 次 GP / 次交差点 / 次 maneuver): `ExtApiGuidanceTracker.state`
- 音声: `ExtApiAnnouncementScheduler` が GP の発話距離に達したら `ExtApiSsmlSpeaker` 経由で発話。
  CRITICAL カテゴリは throttle をバイパス
- callout / マニューバパネル: `tracker.state` を UI が購読して描画 (Compose)

### 5.3 リルート

`ExtApiRerouteDetector` が「polyline からの垂直距離 > 80m が 5 秒継続」で `onOffRoute()` を発火
→ `state = Rerouting` → `RouteRepository.searchRoutes(currentLocation, destination)` で再探索
→ 新 `GoogleRoute` の `geometry` で polyline を差し替え、`ExtApiGuidanceTracker.attach` をやり直し
→ `state = Guiding`。SDK の自動リルートは存在しない (Navigator を使わないため) のでチラつきも無い。

### 5.4 到達

`ExtApiGuidanceTracker` の残距離が閾値以下で到達とみなす (外部ライブラリの `summary` も参照)
→ `state = Arrived`。

---

## 6. NavigationView 連携

`MapItem.kt` は `NavigationView` を `AndroidView` で保持し、`getMapAsync { map -> onMapUpdate(map) }`
で `GoogleMap` を公開する。`MapScreen.android.kt` 側で `var googleMap by remember { mutableStateOf<GoogleMap?>(null) }`
を持ち、`MapPolyline` / `MapMarker` / `MapCameraState` に渡す。`Navigator` の取得・初期化は行わない。

> `NavigationView` ではなく `com.google.android.gms.maps.MapView` に置き換えれば
> `com.google.android.libraries.navigation` 依存ごと外せるが、現状は legacy `feature/home` も
> `NavigationView` を使っているため見送り (UI を触らない方針)。

---

## 7. エラーハンドリング

| ケース | 挙動 |
|---|---|
| ルート探索失敗 (Preview) | `NewRouteManager.searchRoutes` の `runCatching` で捕捉 → `RoutePreviewState.Failed(error)` |
| 候補 0 件 | `require(results.isNotEmpty())` → `Failed` |
| GPS 信号喪失 (Guidance) | `ExtApiGuidanceTracker` は最後の投影点を保持。復帰時に再投影 (今後実装) |
| 再探索失敗 (Guidance) | `GuidanceState.Failed`。ユーザは Preview に戻って再検索 (今後実装) |

---

## 8. テスト戦略

### 8.1 単体テスト (CI)

- `NewRouteManagerTest`: `RouteDataSource` を fake に差し替え。`Idle → Searching → Ready/Failed`、
  `selectRoute`、`reset`、intermediate waypoint の伝播を検証
- `NewGuidanceManagerTest`: `Idle → startGuidance → Guiding`、`stopGuidance → Idle`、`release` の冪等性
- `ExtApiGuidanceTrackerTest` (既存): polyline 投影・進捗計算
- 進捗追跡・音声スケジューラを `NewGuidanceManager` に組み込んだら、対応するケースを追加

### 8.2 統合テスト (実機・手動 QA)

- 開発ツール (`docs/spec/23` の route-compare / `docs/spec/15` の Fake GPS) でドライブシミュレーション
- shakuji-tsukuba ルートで GP の発話タイミング・callout 更新を目視
- 意図的な逸脱で再探索 → polyline 差し替えを目視
- 目的地到達で `Arrived` 状態になるか確認

---

## 9. 命名ポリシーと制約

### 9.1 外部API 提供元名露出禁止 (`CLAUDE.md` §厳命)

本ドキュメント・実装ファイル中で 外部API 提供元の事業者名・製品名・パッケージ名・ドメイン名を
**一切露出させない**。代替表記: 事業者 = 「外部API 提供元」、ライブラリ = 「外部API ライブラリ」、
クラス prefix = `ExtApi`、BuildKonfig = `EXT_API_*`。

### 9.2 `New` prefix の運用

旧 `:feature:home` 系の `RouteManager` / `GuidanceManager` と並走させるための一時 prefix。
旧画面を整理する PR で prefix を外す。

### 9.3 Compose 安定性

- `RoutePreviewState` / `GuidanceState` は `@Immutable`
- `GoogleRoute` / `RouteItem` / `RoutePoint` は `core/model` 側で `@Immutable` 済み
- collection は `kotlinx.collections.immutable.ImmutableList`

### 9.4 Composable 命名 (新規 UI を追加する場合)

- 親 Screen の prefix を引き継ぐ (`MapRoutePreview*`, `MapNavigation*` 等)
- private fun デフォルト、`modifier: Modifier = Modifier` を必ず含める

---

## 10. 残課題・今後やること

1. **Guidance の中身**: `NewGuidanceManager` を `ExtApiGuidanceTracker` / `ExtApiAnnouncementScheduler` /
   `ExtApiRerouteDetector` / `tts/*` + 生 GPS (`FusedLocationProviderClient`) に接続する (§5)
2. **複数ルート候補**: `ExtApiRouteDataSource` の候補 1 本固定を解除 (外部ライブラリ側の対応と合わせて)
3. **Preview ロード UI**: 探索中の表示
4. **`feature/home` の整理**: nav graph から外れた旧マップ画面と legacy `CameraManager` /
   `RouteManager` / `NavigationSdkManager` / `GuidanceSessionManager` を削除する PR
5. **`MapView` への移行検討**: `NavigationView` → `com.google.android.gms.maps.MapView` にすれば
   `com.google.android.libraries.navigation` 依存ごと外せる (4 と同時にやるのが筋)

---

## 11. 参考リンク

- `docs/spec/18_external_api_migration_plan.md` — 全体の移行計画
- `docs/spec/19_external_api_integration_plan.md` — 外部ライブラリ統合計画
- `docs/spec/21_ext_api_guide_proto_and_announcement.md` — GP / 音声案内の検討
- `docs/spec/22_route_token_and_custom_navigator_evaluation.md` — Navigator 路線の評価 (v1 で採用したが v2 で廃止)
- `docs/spec/23_route_compare_dev_tool.md` — Routes API による polyline 再現 (v1 で前提にしたが v2 で不要に)
- `docs/spec/15_fake_gps_dev_tool.md` — ドライブシミュレーション用 Fake GPS
