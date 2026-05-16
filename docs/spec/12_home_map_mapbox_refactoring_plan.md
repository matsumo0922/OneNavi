# 12. Home Map Mapbox リファクタリング実装計画

## Overview

`11_home_map_mapbox_best_practice_audit_final.md` で確定した全 Finding (F-01〜F-10, C-01〜C-04) を解消するための実装計画。

- 対象: `feature/home` モジュールおよび関連する `core/*` モジュール
- 前提: Android 集中。Mapbox 推奨パターン準拠を最優先し、無理な commonMain 共通化は行わない

---

## Design Decisions

監査ドキュメントを基に、以下の設計判断を確定した。

| # | 項目 | 決定 | 理由 |
|---|------|------|------|
| D-01 | Search provider (F-02) | **Google Places を維持** | 日本語検索精度を優先。仕様書を更新し「Mapbox ベストプラクティス準拠を一部諦める設計判断」として明記 |
| D-02 | 実装スコープ | **Phase 0〜3 全部** | 根本的に改善する |
| D-03 | Route ownership (F-03) | **プレビュー段階でも即 `setNavigationRoutes`** | Observer パターンに完全に乗る。Trip Session の課金影響は実装時に確認 |
| D-04 | Route model (F-07) | **`RouteResult` を androidMain に移動** | `NavigationRoute` を直接型安全に保持。`Any?` キャスト完全撤廃 |
| D-05 | Camera (F-09/C-01) | **`NavigationCamera` + `ViewportDataSource` 採用** | Mapbox 公式推奨。active guidance 移行がスムーズ |
| D-06 | 位置更新 (F-10/C-02) | **`NavigationLocationProvider` 導入 + ViewModel throttle** | enhanced location でカーナビ UX 向上。検索 bias 用に 3〜5 秒間隔で ViewModel に流す |
| D-07 | ViewModel 構造 | **`HomeMapViewModel` ごと androidMain に移動** | Mapbox 依存が多い。検索も Google Places (Android 固有) |
| D-08 | KMP 境界 | **Home feature 丸ごと androidMain** | Mapbox 準拠を最優先。`core/model` の共通ドメインモデルは commonMain に残す |

---

## Architecture: Before / After

### Before (現状)

```
feature/home/
  commonMain/
    HomeMapScreen.kt            ← expect fun HomeMapScreenContent
    HomeMapViewModel.kt         ← 全 state を commonMain で管理
    HomeMapViewEvent.kt         ← sealed interface
    di/HomeModule.kt
  androidMain/
    HomeMapScreenContent.kt     ← actual fun、MapView 操作
    HomeMapsMapEffectContent.kt ← Route Line / Callout / Listener
    components/                 ← UI コンポーネント

core/model/commonMain/
    RouteResult.kt              ← platformRoute: Any?
```

**問題点:**
- `HomeMapViewModel` が commonMain にあるが、Mapbox / Google Places 依存
- `RouteResult.platformRoute` が `Any?` で KMP 境界を破っている
- `HomeMapScreenContent` が `expect/actual` だが、iOS 実装はなく形骸化

### After (リファクタリング後)

```
feature/home/
  commonMain/
    HomeNavigation.kt           ← ナビゲーション定義（残す）
    HomeNavDestination.kt       ← NavDestination（残す）
    HomeRoute.kt                ← route 定義（残す）
    HomeScreen.kt               ← 親画面（残す）
    HomeViewModel.kt            ← 親 ViewModel（残す）
    di/HomeModule.kt            ← Koin module（残す、expect/actual 化）
  androidMain/
    HomeMapScreen.kt            ← expect 不要、直接配置
    HomeMapViewModel.kt         ← ★ 移動: Mapbox/GPlaces 依存の全 state
    HomeMapViewEvent.kt         ← ★ 移動
    HomeMapScreenContent.kt     ← actual 不要、直接配置
    HomeMapNavigationManager.kt ← ★ 新規: MapboxNavigation Observer 管理
    HomeMapsMapEffectContent.kt ← リファクタリング
    di/HomeModule.kt            ← ★ actual: ViewModel 登録
    components/                 ← 既存 + リファクタリング

core/model/commonMain/
    RouteResult.kt              ← ★ 削除 (androidMain に移動)
    RouteItem.kt                ← 残す (UI summary、共通ドメイン)
```

---

## 新規ファイル設計

### `HomeMapNavigationManager`

Navigation SDK の Observer 管理を集約するクラス。ViewModel と MapView の間に立つ。

```kotlin
// feature/home/src/androidMain/
class HomeMapNavigationManager(
    private val mapboxNavigation: MapboxNavigation,
) {
    // --- Route 管理 ---
    private val _routes = MutableStateFlow<List<NavigationRoute>>(emptyList())
    val routes: StateFlow<List<NavigationRoute>> = _routes.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    // --- Observer ---
    private val routesObserver = RoutesObserver { result ->
        _routes.value = result.navigationRoutes
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) {}
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            navigationLocationProvider.changePosition(
                locationMatcherResult.enhancedLocation,
                locationMatcherResult.keyPoints,
            )
            _enhancedLocation.value = locationMatcherResult.enhancedLocation
        }
    }

    // --- NavigationLocationProvider ---
    val navigationLocationProvider = NavigationLocationProvider()

    private val _enhancedLocation = MutableStateFlow<Location?>(null)
    val enhancedLocation: StateFlow<Location?> = _enhancedLocation.asStateFlow()

    // --- NavigationCamera ---
    // MapView 初期化後にセットアップ
    var navigationCamera: NavigationCamera? = null
        private set
    var viewportDataSource: MapboxNavigationViewportDataSource? = null
        private set

    fun setupCamera(mapboxMap: MapboxMap, cameraPlugin: CameraAnimationsPlugin) {
        viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap)
        navigationCamera = NavigationCamera(mapboxMap, cameraPlugin, viewportDataSource!!)
    }

    // --- Lifecycle ---
    fun onAttached() {
        mapboxNavigation.registerRoutesObserver(routesObserver)
        mapboxNavigation.registerLocationObserver(locationObserver)
    }

    fun onDetached() {
        mapboxNavigation.unregisterRoutesObserver(routesObserver)
        mapboxNavigation.unregisterLocationObserver(locationObserver)
    }

    // --- Route 操作 ---
    fun setRoutes(routes: List<NavigationRoute>) {
        mapboxNavigation.setNavigationRoutes(routes)
    }

    fun selectRoute(index: Int) {
        _selectedRouteIndex.value = index
        val current = _routes.value
        if (index in current.indices) {
            val reordered = listOf(current[index]) + current.filterIndexed { i, _ -> i != index }
            mapboxNavigation.setNavigationRoutes(reordered)
        }
    }

    fun clearRoutes() {
        mapboxNavigation.setNavigationRoutes(emptyList())
        _selectedRouteIndex.value = 0
    }
}
```

### `RouteResult` (androidMain 版)

```kotlin
// feature/home/src/androidMain/
@Immutable
data class RouteResult(
    val item: RouteItem,
    val navigationRoute: NavigationRoute,
)
```

`core/model` の `RouteResult` は削除。`RouteItem` (UI summary) は `core/model/commonMain` に残す。

---

## Phase 0 — 土台修正

### P0-1. Navigation ownership 一本化 (F-01)

**対象ファイル:**
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/OneNaviApplication.kt`

**変更内容:**
1. `MapboxNavigationProvider.create(options)` を削除
2. `MapboxNavigationApp.setup { ... }` のみ残す
3. `MapboxNavigationApp.attach(lifecycleOwner)` を Activity (または Compose の `LocalLifecycleOwner`) で呼ぶ

```kotlin
// Before
if (!MapboxNavigationApp.isSetup()) {
    MapboxNavigationApp.setup(options)
}
if (!MapboxNavigationProvider.isCreated()) {
    MapboxNavigationProvider.create(options)
}

// After
if (!MapboxNavigationApp.isSetup()) {
    MapboxNavigationApp.setup {
        NavigationOptions.Builder(this@OneNaviApplication)
            .isDebugLoggingEnabled(BuildConfig.DEBUG)
            .build()
    }
}
```

**影響範囲:**
- `MapboxNavigationProvider.retrieve()` を使っている箇所を全て `MapboxNavigationApp.current()` に置換
- `MapboxNavigationRouteDataSource` の `navigation` 取得方法を変更

### P0-2. Search provider 方針明記 (F-02)

**対象ファイル:**
- `docs/spec/03_technology_evaluation.md`

**変更内容:**

```markdown
## 11. Geocoding / Search (検索)

### Google Places API — **ADOPTED**
- 日本語の検索精度が高い
- autocomplete / place details / text search が充実
- Mapbox Geocoding API より日本の POI カバレッジが優秀

### Mapbox Geocoding API — **NOT SELECTED**
- Mapbox エコシステム統一の観点では理想的
- しかし日本語検索精度で Google Places に劣る
- 地図タップ時の POI identity が Mapbox Maps SDK と一致しない問題は、
  座標ベースのフォールバックで許容する

### Decision
**Google Places API を採用。** 日本語検索精度を最優先する設計判断。
Mapbox ベストプラクティスの Search SDK 統一からは外れるが、
カーナビアプリとして検索品質を優先する。
```

### P0-3. Listener の DisposableMapEffect 化 (F-06)

**対象ファイル:**
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`

**変更内容:**

`MapEffect { view -> ... }` を `DisposableMapEffect(key1 = Unit) { mapView -> ... onDispose { ... } }` に変更。
4 つのリスナー全てに対応する remove を追加:

- `addOnIndicatorPositionChangedListener` → `removeOnIndicatorPositionChangedListener`
- `addOnIndicatorBearingChangedListener` → `removeOnIndicatorBearingChangedListener`
- `addOnMapClickListener` → `removeOnMapClickListener`
- `setOnCalloutClickListener(null)` で解除

### P0-4. Home feature の commonMain → androidMain 移動 (D-07, D-08)

**対象ファイル:**
- `feature/home/src/commonMain/.../map/HomeMapViewModel.kt` → androidMain に移動
- `feature/home/src/commonMain/.../map/HomeMapViewEvent.kt` → androidMain に移動
- `feature/home/src/commonMain/.../map/HomeMapScreen.kt` → androidMain に移動 (expect/actual 解消)
- `feature/home/src/commonMain/.../di/HomeModule.kt` → expect/actual 化

**HomeMapScreen の変更:**

```kotlin
// Before: commonMain に expect
@Composable
internal expect fun HomeMapScreenContent(
    viewModel: HomeMapViewModel,
    modifier: Modifier = Modifier,
)

// After: androidMain に直接配置、expect/actual 不要
@Composable
internal fun HomeMapScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeMapViewModel = koinViewModel(),
) {
    HomeMapScreenContent(
        viewModel = viewModel,
        modifier = modifier,
    )
}
```

**HomeModule の変更:**

```kotlin
// commonMain
expect val homeMapModule: Module

val homeModule = module {
    includes(homeMapModule)
    viewModelOf(::HomeViewModel)
}

// androidMain
actual val homeMapModule = module {
    viewModelOf(::HomeMapViewModel)
    // HomeMapNavigationManager は Phase 1 で追加
}
```

---

## Phase 1 — Observer パターン導入

### P1-1. `setNavigationRoutes()` 導入 + `HomeMapNavigationManager` 作成 (F-03)

**新規ファイル:**
- `feature/home/src/androidMain/.../map/HomeMapNavigationManager.kt`

**変更内容:**
1. `HomeMapNavigationManager` を作成（上記設計参照）
2. `HomeMapViewModel` のルート探索結果を `navigationManager.setRoutes()` に接続
3. Koin で `HomeMapNavigationManager` を提供

**ルート探索フロー変更:**

```
// Before
routeRepository.searchRoutes() → _routeResults StateFlow → MapEffect で手動描画

// After
routeRepository.searchRoutes() → navigationManager.setRoutes(routes)
    → RoutesObserver が自動発火
    → UI 自動更新
```

### P1-2. RoutesObserver で Route Line / Callout を自動更新 (F-04, F-05)

**対象ファイル:**
- `feature/home/src/androidMain/.../map/HomeMapsMapEffectContent.kt`

**変更内容:**
1. `MapEffect(routeResults, selectedRouteIndex)` の手動描画を削除
2. `RoutesObserver` 内で `routeLineApi.setNavigationRoutes()` を呼ぶ
3. `alternativesMetadata` を必ず渡す:

```kotlin
// RoutesObserver 内
val alternativesMetadata = mapboxNavigation.getAlternativeMetadataFor(
    result.navigationRoutes,
)
routeLineApi.setNavigationRoutes(
    result.navigationRoutes,
    alternativesMetadata,
) { value ->
    routeLineView.renderRouteDrawData(mapStyle, value)
}
```

4. `reorderRoutes()` 自前関数を削除（`setNavigationRoutes` の順序で SDK が処理）

### P1-3. `RouteResult` 再設計 + shared model 整理 (F-07)

**変更内容:**
1. `core/model/commonMain/RouteResult.kt` を削除
2. `feature/home/src/androidMain/` に新しい `RouteResult` を作成:

```kotlin
@Immutable
data class RouteResult(
    val item: RouteItem,
    val navigationRoute: NavigationRoute,
)
```

3. `RouteItem` は `core/model/commonMain` に残す（Mapbox 依存なし）
4. `RouteRepository.searchRoutes()` の戻り値を変更:
   - commonMain の interface は `List<RouteItem>` を返す
   - androidMain の実装は `List<RouteResult>` を ViewModel に直接提供（または `HomeMapNavigationManager` 経由）
5. `as? NavigationRoute` キャストを全箇所から削除

---

## Phase 2 — Camera / Location 整理

### P2-1. `NavigationCamera` + `ViewportDataSource` 導入 (F-09, C-01)

**対象ファイル:**
- `feature/home/src/androidMain/.../map/HomeMapScreenContent.kt`
- `feature/home/src/androidMain/.../map/HomeMapNavigationManager.kt`
- `feature/home/src/androidMain/.../map/components/HomeMapControls.kt`

**変更内容:**
1. 複数の `LaunchedEffect` でのカメラ操作 (lines 142, 157, 190, 217) を全て削除
2. `HomeMapNavigationManager.setupCamera()` で `NavigationCamera` を初期化
3. カメラモード切替を `NavigationCamera` のメソッドに置換:

```kotlin
// Follow mode
navigationCamera.requestNavigationCameraToFollowing()

// Route overview
viewportDataSource.onRouteChanged(route)
viewportDataSource.evaluate()
navigationCamera.requestNavigationCameraToOverview()

// Idle (ユーザーがジェスチャーで操作)
navigationCamera.requestNavigationCameraToIdle()
```

4. `HomeMapControls` の `trackingMode` を `NavigationCamera` の state と連動
5. `MapView` を `remember` で保持して `LaunchedEffect` で使う構図 (C-03) が自然に解消

### P2-2. `NavigationLocationProvider` 導入 + throttle (F-10, C-02)

**対象ファイル:**
- `feature/home/src/androidMain/.../map/HomeMapsMapEffectContent.kt`
- `feature/home/src/androidMain/.../map/HomeMapViewModel.kt`
- `feature/home/src/androidMain/.../map/HomeMapNavigationManager.kt`

**変更内容:**
1. `HomeMapNavigationManager` の `navigationLocationProvider` を MapView に接続:

```kotlin
mapView.location.setLocationProvider(navigationLocationProvider)
```

2. `addOnIndicatorPositionChangedListener` / `addOnIndicatorBearingChangedListener` を削除
3. enhanced location を `HomeMapNavigationManager.enhancedLocation` StateFlow で提供
4. ViewModel への位置更新を throttle (3〜5 秒間隔):

```kotlin
navigationManager.enhancedLocation
    .filterNotNull()
    .sample(5.seconds)
    .onEach { location ->
        _userLatitude.value = location.latitude
        _userLongitude.value = location.longitude
    }
    .launchIn(viewModelScope)
```

5. bearing は `NavigationCamera` が管理するため、`onBearingChanged` コールバック削除

---

## Phase 3 — Cleanup

### P3-1. 旧 CallOut adapter の整理 (F-08)

**対象ファイル:**
- 削除済み実装のため、現行対象なし

**変更内容:**
1. `calloutViews: MutableMap<NavigationRoute, View>` の手動管理を削除
2. `updateSelectionStyling()` の View 直接操作を `notifyDataSetChanged()` に置換
3. `updateRouteResults()` を削除（RoutesObserver 経由で SDK が自動管理）
4. adapter の責務を「View の生成 + バインド」のみに限定

### P3-2. Route preview → active guidance の境界定義 (C-04)

**設計:**

```
[Route Preview]
  setNavigationRoutes(routes) → RoutesObserver → Route Line + Callout 描画
  NavigationCamera: Overview mode
  Trip Session: Free Drive (課金影響を確認の上)

[Active Guidance 開始]
  mapboxNavigation.startTripSession(withForegroundService = true)
  NavigationCamera: Following mode
  RouteProgressObserver 登録
  Banner/Voice instruction 開始
```

- preview → guidance の遷移は「ナビ開始」ボタンタップで明示的に行う
- 現時点では Home 画面は preview のみ。guidance UI は Phase 1 (ロードマップ) で実装

### P3-3. Token source of truth 集約

**対象ファイル:**
- `composeApp/src/androidMain/res/values/strings.xml` (mapbox_access_token)
- `feature/home/src/androidMain/.../map/HomeMapScreenContent.kt` (MapboxOptions.accessToken 設定)
- `build-logic` / `local.properties` (BuildKonfig)

**変更内容:**
1. `strings.xml` の `mapbox_access_token` を唯一のソースとする
2. `MapboxOptions.accessToken` への明示的な設定 (`LaunchedEffect(viewModel.mapBoxToken)`) を削除
3. `BuildKonfig` の `mapBoxToken` は初期設定用途のみに残す（token が XML にない場合のフォールバック）

### P3-4. Polyline デコーダー統一

**対象ファイル:**
- `core/datasource/src/androidMain/.../MapboxNavigationRouteDataSource.kt`

**変更内容:**
1. 自前の `decodePolyline()` (lines 177-216) を削除
2. `LineString.fromPolyline(encodedPolyline, precision)` に統一

---

## 移動対象ファイル一覧

| ファイル | 移動元 | 移動先 | 備考 |
|---------|--------|--------|------|
| `HomeMapViewModel.kt` | `commonMain/.../map/` | `androidMain/.../map/` | Mapbox/GPlaces 依存 |
| `HomeMapViewEvent.kt` | `commonMain/.../map/` | `androidMain/.../map/` | ViewModel と同居 |
| `HomeMapScreen.kt` | `commonMain/.../map/` | `androidMain/.../map/` | expect/actual 解消 |
| `RouteResult.kt` | `core/model/commonMain/` | `feature/home/androidMain/.../map/` | NavigationRoute 直接保持 |

## 新規ファイル一覧

| ファイル | 配置先 | 責務 |
|---------|--------|------|
| `HomeMapNavigationManager.kt` | `androidMain/.../map/` | Navigation SDK Observer 管理、Camera、LocationProvider |

## 削除対象

| ファイル / コード | 理由 |
|------------------|------|
| `core/model/commonMain/RouteResult.kt` | androidMain に移動 |
| `MapboxNavigationProvider.create()` 呼び出し | ownership 一本化 |
| `reorderRoutes()` | SDK が管理 |
| `decodePolyline()` 自前実装 | `LineString.fromPolyline()` に統一 |
| 複数の camera `LaunchedEffect` | `NavigationCamera` に統合 |
| `addOnIndicator*Listener` | `NavigationLocationProvider` に統合 |

---

## 依存関係グラフ

```
P0-1 (Navigation ownership)
  ↓
P0-3 (Listener dispose) ← 独立して実施可能
  ↓
P0-4 (commonMain → androidMain 移動)
  ↓
P1-1 (setNavigationRoutes + NavigationManager)
  ↓
P1-2 (RoutesObserver) ← P1-1 必須
  ↓
P1-3 (RouteResult 再設計) ← P1-1 必須
  ↓
P2-1 (NavigationCamera) ← P1-2 推奨
  ↓
P2-2 (NavigationLocationProvider) ← P0-1 必須
  ↓
P3-1 (Callout cleanup) ← P1-2 必須
P3-2 (session 境界) ← P1-1 必須
P3-3 (Token 集約) ← 独立
P3-4 (Polyline 統一) ← 独立

P0-2 (仕様書更新) ← 完全独立
```

---

## Reference

- 監査結果: `docs/spec/11_home_map_mapbox_best_practice_audit_final.md`
- ロードマップ: `docs/spec/07_phased_roadmap.md`
- アーキテクチャ: `docs/spec/05_architecture.md`
- 技術選定: `docs/spec/03_technology_evaluation.md`
