# Mapbox ベストプラクティス準拠レビュー

本ドキュメントは、OneNavi の地図・ナビゲーション関連コードを Mapbox 公式ドキュメントの推奨パターンと突き合わせ、全ての乖離を洗い出したものである。

## 目次

- [現状アーキテクチャの概要](#現状アーキテクチャの概要)
- [根本原因](#根本原因)
- [違反一覧](#違反一覧)
  - [1. Navigation SDK ライフサイクル管理](#1-navigation-sdk-ライフサイクル管理)
  - [2. ルート管理フロー — setNavigationRoutes の欠落](#2-ルート管理フロー--setnavigationroutes-の欠落)
  - [3. Route Line 描画 — Observer パターン不使用](#3-route-line-描画--observer-パターン不使用)
  - [4. Route Callout — RoutesObserver 駆動でない](#4-route-callout--routesobserver-駆動でない)
  - [5. カメラ管理 — NavigationCamera 未使用](#5-カメラ管理--navigationcamera-未使用)
  - [6. 位置情報管理 — NavigationLocationProvider 未使用](#6-位置情報管理--navigationlocationprovider-未使用)
  - [7. MapView 参照を MapEffect 外で保持](#7-mapview-参照を-mapeffect-外で保持)
  - [8. Listener の register/unregister ライフサイクル](#8-listener-の-registerunregister-ライフサイクル)
  - [9. AccessToken の設定タイミング](#9-accesstoken-の設定タイミング)
  - [10. Polyline デコードの自前実装](#10-polyline-デコードの自前実装)
  - [11. platformRoute: Any? の型安全性](#11-platformroute-any-の型安全性)
  - [12. Route Options — Traffic Congestion アノテーション](#12-route-options--traffic-congestion-アノテーション)
  - [13. Callout Adapter — DefaultRouteCalloutAdapter の検討不足](#13-callout-adapter--defaultroutecalloutadapter-の検討不足)
  - [14. Gesture Handling — Click Listener の解除](#14-gesture-handling--click-listener-の解除)
- [改善優先度](#改善優先度)
- [推奨アーキテクチャ](#推奨アーキテクチャ)
- [参考ドキュメント](#参考ドキュメント)

---

## 現状アーキテクチャの概要

現在の地図関連のデータフローは以下の通り:

```
[DataSource層]
  MapboxNavigationRouteDataSource
    └─ navigation.requestRoutes(callback) → List<NavigationRoute>

[ViewModel層]
  HomeMapViewModel
    └─ RouteResult(item, platformRoute: Any?) を StateFlow で公開
    └─ カメラ移動のトリガーは StateFlow 変更 → Compose リコンポーズ

[Compose層]
  HomeMapScreenContent
    └─ collectAsStateWithLifecycle で ViewModel の状態を購読
    └─ LaunchedEffect で MapView 参照を使ってカメラ操作
    └─ MapEffect 内で Route Line 描画・リスナー登録
```

ViewModel が全状態を管理し、Compose 側で `LaunchedEffect` / `MapEffect` を通じて命令的に地図操作を行う設計になっている。

---

## 根本原因

全ての問題は **1 つの設計判断** に帰着する:

> **Navigation SDK の Observer パターンを使わず、ViewModel の StateFlow + Compose の LaunchedEffect/MapEffect で全てを手動管理している。**

Mapbox Navigation SDK は内部で状態マシンを持ち、`RoutesObserver` / `RouteProgressObserver` / `LocationObserver` を通じて UI コンポーネントに自動通知する設計である。OneNavi はこのエコシステムに乗っかっておらず、結果として Mapbox が自動化しているルート更新・カメラ制御・位置同期を全て手動で再実装している状態にある。

---

## 違反一覧

### 1. Navigation SDK ライフサイクル管理

**対象ファイル:** `composeApp/src/androidMain/.../OneNaviApplication.kt:50-62`

**公式の推奨パターン:**

```kotlin
// Application.onCreate()
MapboxNavigationApp.setup {
    NavigationOptions.Builder(context).build()
}

// Activity or Compose のライフサイクルに紐付け
lifecycle.addObserver(object : DefaultLifecycleObserver {
    override fun onResume(owner: LifecycleOwner) {
        MapboxNavigationApp.attach(owner)
    }
    override fun onPause(owner: LifecycleOwner) {
        MapboxNavigationApp.detach(owner)
    }
})
```

`MapboxNavigation` は「アプリケーションプロセスごとに 1 インスタンスのみ」のシングルトンであり、`MapboxNavigationApp` がそのライフサイクルを管理する。`attach()` によって LifecycleOwner が `CREATED` 状態になったときにインスタンスが生成され、全ての LifecycleOwner が `DESTROYED` になると自動で破棄される。

**現状のコード:**

```kotlin
private fun setupMapboxNavigation() {
    val options = NavigationOptions.Builder(this)
        .isDebugLoggingEnabled(BuildConfig.DEBUG)
        .build()

    if (!MapboxNavigationApp.isSetup()) {
        MapboxNavigationApp.setup(options)
    }

    if (!MapboxNavigationProvider.isCreated()) {
        MapboxNavigationProvider.create(options)  // 直接生成
    }
}
```

**問題:**

- `MapboxNavigationApp.attach(lifecycleOwner)` が一切呼ばれていない
- `MapboxNavigationProvider.create()` で直接インスタンスを生成しており、ライフサイクルに紐付かない
- アプリがバックグラウンドに移行してもリソースが解放されない
- Mapbox が推奨する `MapboxNavigationObserver` パターン（`onAttached` / `onDetached` で Observer を register/unregister）が使えない

**公式ドキュメント:** [Navigation SDK - Initialization](https://docs.mapbox.com/android/navigation/guides/get-started/initialization/)

---

### 2. ルート管理フロー — setNavigationRoutes の欠落

**対象ファイル:** `core/datasource/src/androidMain/.../MapboxNavigationRouteDataSource.kt`、`feature/home/src/commonMain/.../HomeMapViewModel.kt`

**公式の推奨フロー:**

```
navigation.requestRoutes(routeOptions, callback)
    ↓
callback で List<NavigationRoute> 取得
    ↓
navigation.setNavigationRoutes(routes)   ← SDK にルートを登録
    ↓
RoutesObserver.onRoutesChanged が自動発火
    ↓
UI 更新（Route Line, Callout, Camera, etc.）
```

`requestRoutes` はルートを「取得」するだけであり、Navigation SDK の内部状態にルートを「設定」するのは `setNavigationRoutes` の役割である。`setNavigationRoutes` を呼ぶことで初めて `RoutesObserver` が発火し、UI コンポーネントへの自動通知が行われる。

**現状のフロー:**

```
navigation.requestRoutes(routeOptions, callback)
    ↓
callback で List<NavigationRoute> 取得
    ↓
RouteResult(item, platformRoute) に変換して ViewModel の StateFlow へ
    ↓
Compose が collectAsStateWithLifecycle で購読
    ↓
MapEffect 内で手動描画
```

**問題:**

- `navigation.setNavigationRoutes()` を一切呼んでいない
- Navigation SDK の内部にルートが登録されないため、全ての Observer が発火しない
- リルート時に SDK がルートを自動更新できない
- `RouteProgressObserver` がルート進行状況を計算できない
- ナビセッション中の課金最適化（リルート・リフレッシュ無料）が効かない
- Active Guidance（ターンバイターンナビ）への移行時に根本的な設計変更が必要になる

---

### 3. Route Line 描画 — Observer パターン不使用

**対象ファイル:** `feature/home/src/androidMain/.../HomeMapsMapEffectContent.kt:187-217`

**公式の推奨パターン:**

```kotlin
private val routesObserver = object : RoutesObserver {
    override fun onRoutesChanged(result: RoutesUpdatedResult) {
        val alternativesMetadata = mapboxNavigation.getAlternativeMetadataFor(
            result.navigationRoutes
        )
        routeLineApi.setNavigationRoutes(
            result.navigationRoutes,
            alternativesMetadata,   // 必須
        ) { value ->
            routeLineView.renderRouteDrawData(mapStyle, value)
        }
    }
}

// ナビ中の走行済み区間表示
private val routeProgressObserver = RouteProgressObserver { routeProgress ->
    routeLineApi.updateWithRouteProgress(routeProgress) { result ->
        routeLineView.renderRouteLineUpdate(mapStyle, result)
    }
}
```

公式は `RoutesObserver` 経由で「ルート情報が更新されるたびに新しい情報がルート線に反映される」設計を推奨している。これにより以下が自動化される:

- プライマリルートの変更
- リルート
- 混雑情報（congestion）の更新
- 無効な代替ルートの自動削除

**現状のコード:**

```kotlin
MapEffect(routeResults, selectedRouteIndex) { mapView ->
    val style = mapView.mapboxMap.style ?: return@MapEffect

    if (routeResults.isEmpty()) {
        routeLineApi.clearRouteLine { expected ->
            routeLineView.renderClearRouteLineValue(style, expected)
        }
        return@MapEffect
    }

    val navigationRoutes = routeResults.mapNotNull { it.platformRoute as? NavigationRoute }
    val reordered = reorderRoutes(navigationRoutes, selectedRouteIndex)
    routeLineApi.setNavigationRoutes(reordered) { expected ->
        routeLineView.renderRouteDrawData(style, expected)
    }
}
```

**問題:**

| 問題 | 詳細 |
|---|---|
| `RoutesObserver` 未使用 | Compose の `MapEffect` キー変更をトリガーに手動で描画しており、SDK 内部のルート更新（リルート等）を検知できない |
| `alternativesMetadata` 未提供 | `setNavigationRoutes` に `alternativesMetadata` を渡していない。公式は「代替ルートの可視化を強化し、プライマリルートと重複する部分を非表示にする」ために必須としている |
| `RouteProgressObserver` 未使用 | `updateWithRouteProgress` を呼んでいないため、走行済み区間のグレーアウトが機能しない |
| 手動 `reorderRoutes` | SDK は `setNavigationRoutes` の先頭要素をプライマリルートとして扱う。現状は選択変更のたびに配列を並び替えて再呼出ししており、ちらつき防止のために条件分岐を自前実装している |

**公式ドキュメント:** [Navigation SDK - Route Line](https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/)

---

### 4. Route Callout — RoutesObserver 駆動でない

**対象ファイル:** `feature/home/src/androidMain/.../HomeMapsMapEffectContent.kt:99-101,155-158`、`feature/home/src/androidMain/.../components/HomeMapRouteCalloutAdapter.kt`

**公式の推奨パターン:**

Route Callout は Route Line の描画フローに統合されている。`routeLineView.renderRouteDrawData(mapStyle, value)` が呼ばれた時点で、`CalloutAdapter` を通じてコールアウトも自動的に更新される。つまり:

```
RoutesObserver.onRoutesChanged
    ↓
routeLineApi.setNavigationRoutes(routes, alternativesMetadata) { value ->
    routeLineView.renderRouteDrawData(mapStyle, value)  // ← ここで Callout も更新
}
```

これにより「ルート選択変更、リルート、期間更新、無効な代替ルートの削除」が全て自動反映される。

**現状のコード:**

```kotlin
// ルート変更時 — 手動でアダプターにデータを渡す
routeCalloutAdapter.updateRouteResults(routeResults)

// 選択変更時 — View を直接操作してスタイリング変更
routeCalloutAdapter.updateSelectionStyling(selectedRoute)
```

**問題:**

- `RoutesObserver` に紐付いていないため、SDK 内部のルート更新時に Callout が追従しない
- `updateSelectionStyling` で View を直接操作しているが、SDK は Callout データの変更時に `notifyDataSetChanged()` を呼ぶことを推奨している
- `calloutViews: MutableMap<NavigationRoute, View>` で手動マッピングを管理しているが、SDK が View のライフサイクルを管理する設計と競合する可能性がある

**公式ドキュメント:** [Navigation SDK - Route Callout](https://docs.mapbox.com/android/navigation/guides/ui-components/route-callout/)

---

### 5. カメラ管理 — NavigationCamera 未使用

**対象ファイル:** `feature/home/src/androidMain/.../HomeMapScreenContent.kt:157-266`、`feature/home/src/androidMain/.../components/HomeMapControls.kt`

**公式の推奨パターン:**

Mapbox Navigation SDK はカメラ管理のために `NavigationCamera` と `MapboxNavigationViewportDataSource` を提供している。

```kotlin
// 初期化
viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap)
navigationCamera = NavigationCamera(mapboxMap, cameraPlugin, viewportDataSource)

// RoutesObserver 内でカメラにルートデータを供給
private val routesObserver = RoutesObserver { routes ->
    if (routes.isNotEmpty()) {
        viewportDataSource.onRouteChanged(routes.first())
        viewportDataSource.evaluate()  // カメラターゲット再生成
    } else {
        viewportDataSource.clearRouteData()
        viewportDataSource.evaluate()
    }
}

// LocationObserver 内で位置データを供給
private val locationObserver = object : LocationObserver {
    override fun onEnhancedLocationChanged(enhancedLocation: Location, keyPoints: List<Location>) {
        viewportDataSource.onLocationChanged(enhancedLocation)
        viewportDataSource.evaluate()
    }
}

// RouteProgressObserver 内で進行状況を供給
private val routeProgressObserver = RouteProgressObserver { routeProgress ->
    viewportDataSource.onRouteProgressChanged(routeProgress)
    viewportDataSource.evaluate()
}

// カメラモード切り替え
navigationCamera.requestNavigationCameraToFollowing()  // 追従モード
navigationCamera.requestNavigationCameraToOverview()   // 全体俯瞰モード
navigationCamera.requestNavigationCameraToIdle()       // 手動操作

// パディング設定
val density = context.resources.displayMetrics.density
viewportDataSource.followingPadding = EdgeInsets(
    180.0 * density, 40.0 * density, 150.0 * density, 40.0 * density
)
viewportDataSource.overviewPadding = EdgeInsets(
    140.0 * density, 40.0 * density, 120.0 * density, 40.0 * density
)
```

`NavigationCamera` は IDLE / FOLLOWING / OVERVIEW の 3 状態を持ち、`ViewportDataSource` からデータが供給されるたびにカメラを自動更新する。

**現状のコード:**

```kotlin
// 検索結果表示時 — LaunchedEffect 内で手動カメラ操作
LaunchedEffect(searchResults, mapView) {
    val currentMapView = mapView ?: return@LaunchedEffect
    val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(
        coordinates = points,
        coordinatesPadding = padding,
    )
    viewportState.flyTo(cameraOptions, ...)
}

// ルート表示時 — 同様に手動
LaunchedEffect(routeResults, mapView) {
    val allPoints = routeResults.flatMap { ... }
    val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(...)
    viewportState.flyTo(cameraOptions, ...)
}

// 追従モード — MapViewportState.transitionToFollowPuckState で直接制御
viewportState.transitionToFollowPuckState(
    followPuckViewportStateOptions = buildFollowPuckOptions(mode),
)
```

**問題:**

| 問題 | 詳細 |
|---|---|
| `NavigationCamera` 未使用 | FOLLOWING / OVERVIEW の自動切り替え、ルート進行に応じたズーム調整が効かない |
| `MapboxNavigationViewportDataSource` 未使用 | ルート変更・位置更新・進行状況に応じたカメラ計算を全て手動でやっている |
| `evaluate()` モデル未採用 | 公式はデータ供給後に `evaluate()` を呼ぶリアクティブモデルを推奨しているが、現状は各 `LaunchedEffect` で個別にカメラアニメーションを発火している |
| パディングのハードコード | `CAMERA_PADDING_TOP = 200.0` 等をピクセル密度を考慮せずにハードコードしている。公式は `pixelDensity` を乗じたパディングを推奨 |
| `MapView` 参照の外部使用 | `cameraForCoordinates` の呼び出しに `MapEffect` 外で `MapView` 参照を使っている（後述 #7） |

**公式ドキュメント:** [Navigation SDK - Camera](https://docs.mapbox.com/android/navigation/guides/ui-components/camera/)

---

### 6. 位置情報管理 — NavigationLocationProvider 未使用

**対象ファイル:** `feature/home/src/androidMain/.../HomeMapsMapEffectContent.kt:138-151`

**公式の推奨パターン:**

```kotlin
// NavigationLocationProvider が Navigation SDK の位置情報を Maps SDK に橋渡し
val navigationLocationProvider = NavigationLocationProvider()

// Navigation SDK の LocationObserver で位置を受け取り
val locationObserver = object : LocationObserver {
    override fun onEnhancedLocationChanged(
        enhancedLocation: Location,
        keyPoints: List<Location>,
    ) {
        // NavigationLocationProvider に位置を供給
        navigationLocationProvider.changePosition(enhancedLocation, keyPoints)
        // ViewportDataSource にも供給
        viewportDataSource.onLocationChanged(enhancedLocation)
        viewportDataSource.evaluate()
    }
    override fun onRawLocationChanged(rawLocation: Location) {}
}

// Maps SDK の location plugin に NavigationLocationProvider を設定
mapView.location.setLocationProvider(navigationLocationProvider)
```

Navigation SDK は GPS の生データを加工（マップマッチング、道路スナッピング等）して `enhancedLocation` として提供する。`NavigationLocationProvider` はこの加工済み位置情報を Maps SDK の Location Puck に反映するブリッジである。

**現状のコード:**

```kotlin
MapEffect { view ->
    view.location.enabled = true
    view.location.locationPuck = createDefault2DPuck(withBearing = true)
    view.location.puckBearing = PuckBearing.HEADING
    view.location.puckBearingEnabled = true

    // Maps SDK のデフォルト LocationProvider から位置を取得
    view.location.addOnIndicatorPositionChangedListener { point ->
        onUserLocationUpdated(point.latitude(), point.longitude())
    }
    view.location.addOnIndicatorBearingChangedListener { bearing ->
        onBearingChanged(bearing)
    }
}
```

**問題:**

- Maps SDK のデフォルト LocationProvider を使っており、Navigation SDK の `NavigationLocationProvider` を使っていない
- Navigation SDK のマップマッチング・スナッピング処理の恩恵を受けていない（Puck が道路上にスナップしない）
- `addOnIndicatorPositionChangedListener` で ViewModel に位置を手動送信しているが、Navigation SDK 内部の位置と同期がとれない
- リスナーが解除されていない（後述 #8）

---

### 7. MapView 参照を MapEffect 外で保持

**対象ファイル:** `feature/home/src/androidMain/.../HomeMapScreenContent.kt:80,157-188,217-266`

**公式の警告:**

> "Using MapView APIs within a MapEffect can introduce internal state changes that may conflict with Compose states, potentially leading to unexpected or undefined behavior."
> "Always test thoroughly when mixing Compose state management with direct MapView API calls."

MapView の以下プロパティは `MapEffect` 内でアクセスすると**クラッシュする**:
- `mapView.lifecycle`
- `mapView.compass`
- `mapView.scalebar`
- `mapView.logo`
- `mapView.attribution`

**現状のコード:**

```kotlin
// MapView を remember で保持
var mapView by remember { mutableStateOf<MapView?>(null) }

// MapEffect 外の LaunchedEffect 内で使用
LaunchedEffect(searchResults, mapView) {
    val currentMapView = mapView ?: return@LaunchedEffect
    val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(...)
    viewportState.flyTo(cameraOptions, ...)
}

LaunchedEffect(routeResults, mapView) {
    val currentMapView = mapView ?: return@LaunchedEffect
    val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(...)
    viewportState.flyTo(cameraOptions, ...)
}
```

**問題:**

- `MapView` 参照を Compose の `remember` で保持し、`MapEffect` 外の `LaunchedEffect` 内で `mapboxMap.cameraForCoordinates()` を呼んでいる
- これは公式が明確に警告している「Compose 状態との競合」パターンに該当する
- `NavigationCamera` + `ViewportDataSource` に移行すれば `MapView` 参照の外部保持は不要になる

---

### 8. Listener の register/unregister ライフサイクル

**対象ファイル:** `feature/home/src/androidMain/.../HomeMapsMapEffectContent.kt:138-184`

**公式の推奨パターン (Compose):**

```kotlin
MapboxMap {
    DisposableMapEffect(key1 = Unit) { mapView ->
        val listener = object : OnRotateListener { ... }
        mapView.gestures.addOnRotateListener(listener)
        onDispose {
            mapView.gestures.removeOnRotateListener(listener)
        }
    }
}
```

Compose で imperative リスナーを登録する場合、公式は `DisposableMapEffect` + `onDispose` を推奨している。

**現状のコード:**

```kotlin
MapEffect { view ->
    // 4 つのリスナーを登録 — 解除処理なし
    view.location.addOnIndicatorPositionChangedListener { point -> ... }
    view.location.addOnIndicatorBearingChangedListener { bearing -> ... }
    view.mapboxMap.addOnMapClickListener { point -> ... }
    routeCalloutAdapter.setOnCalloutClickListener { clickedRoute -> ... }
}
```

**問題:**

- `MapEffect`（キーなし = 初回のみ実行）で 4 つのリスナーを登録しているが、対応する解除処理が一切ない
- `addOnIndicatorPositionChangedListener` は高頻度で発火するリスナーであり、リコンポーズ時に重複登録されるとパフォーマンスが劣化する
- `addOnMapClickListener` も同様
- 公式の `DisposableMapEffect` + `onDispose` パターンを使うべき

---

### 9. AccessToken の設定タイミング

**対象ファイル:** `feature/home/src/androidMain/.../HomeMapScreenContent.kt:138-140`

**公式の推奨:** `Application.onCreate()` またはリソースファイル (`mapbox_access_token.xml`) でトークンを設定する。

**現状のコード:**

```kotlin
LaunchedEffect(viewModel.mapBoxToken) {
    MapboxOptions.accessToken = viewModel.mapBoxToken
}
```

**問題:**

- `LaunchedEffect` は Compose のレンダリング後に実行される
- `MapboxMap` composable は先にレンダリングを開始する可能性があり、トークン未設定でスタイル読み込みに失敗する
- `OneNaviApplication.onCreate()` で `MapboxOptions.accessToken` を設定すべき
- `BuildKonfig` で `local.properties` から読み込んでいるなら `Application.onCreate()` 時点で値は利用可能

---

### 10. Polyline デコードの自前実装

**対象ファイル:** `core/datasource/src/androidMain/.../MapboxNavigationRouteDataSource.kt:177-216`

**公式の API:** `com.mapbox.geojson.LineString.fromPolyline(encoded, precision)`

**現状:** 40 行の自前デコーダーを実装。precision は `1e6` (polyline6) でハードコード。

一方で `HomeMapScreenContent.kt:240-242` では SDK の `LineString.fromPolyline()` を使用しており、**同一プロジェクト内で 2 つの実装が混在**している。

**問題:**

- Mapbox の geometry は polyline6 を使うが、SDK 側の実装に委ねる方が安全
- SDK のバージョンアップで precision や encoding が変わった場合に追従できない
- `HomeMapScreenContent.kt` 側で既に SDK API を使っているのに DataSource 側で自前実装する一貫性のなさ

---

### 11. platformRoute: Any? の型安全性

**対象ファイル:** `core/model/src/commonMain/.../RouteResult.kt`

**現状のコード:**

```kotlin
@Immutable
data class RouteResult(
    val item: RouteItem,
    val platformRoute: Any? = null,
)
```

以下の箇所で `as? NavigationRoute` キャストが散在:
- `HomeMapsMapEffectContent.kt:198`
- `HomeMapScreenContent.kt:239`
- `HomeMapRouteCalloutAdapter.kt:168` (間接的に `===` 比較)

**問題:**

- KMP 制約で `NavigationRoute` を common モジュールに置けないのは理解できるが、`expect/actual` パターンで型安全にできる
- キャスト失敗時にルート線が描画されない silent failure のリスクがある
- `as? NavigationRoute` が `null` を返した場合、`navigationRoutes` が空リストになり、何も表示されずにエラーも出ない

---

### 12. Route Options — Traffic Congestion アノテーション

**対象ファイル:** `core/datasource/src/androidMain/.../MapboxNavigationRouteDataSource.kt:49-57`

**公式の推奨:**

```kotlin
val routeOptions = RouteOptions.builder()
    .profile(DirectionsCriteria.PROFILE_DRIVING_TRAFFIC)
    .annotationsList(listOf(
        DirectionsCriteria.ANNOTATION_CONGESTION_NUMERIC,
        DirectionsCriteria.ANNOTATION_DISTANCE,
    ))
    .build()
```

`ANNOTATION_CONGESTION_NUMERIC` を指定することで、各区間の混雑度数値がレスポンスに含まれ、Route Line の渋滞カラーが機能する。

**現状のコード:**

```kotlin
val baseOptions = RouteOptions.builder()
    .applyDefaultNavigationOptions()
    .applyLanguageAndVoiceUnitOptions(context)
    .coordinatesList(listOf(origin) + waypointPoints + listOf(destination))
```

**問題:**

- `applyDefaultNavigationOptions()` がどこまで設定するかに依存しているが、`ANNOTATION_CONGESTION_NUMERIC` が明示的に指定されていない
- `HomeMapsMapEffectContent.kt:81-89` で渋滞カラー（green/amber/red/purple）を細かく設定しているのに、レスポンスに congestion データが含まれていない場合、全てデフォルトカラーで描画される
- `PROFILE_DRIVING_TRAFFIC` の明示的指定も確認できない

---

### 13. Callout Adapter — DefaultRouteCalloutAdapter の検討不足

**対象ファイル:** `feature/home/src/androidMain/.../components/HomeMapRouteCalloutAdapter.kt`

**公式の推奨:**

公式は `DefaultRouteCalloutAdapter` から始めることを推奨しており、以下のカスタマイズを提供している:

```kotlin
val options = DefaultRouteCalloutAdapterOptions.Builder()
    .routeCalloutType(RouteCalloutType.NAVIGATION)     // 相対時間表示
    .similarDurationDelta(1.minutes)                    // 類似時間統合
    .backgroundColor(R.color.custom_background)         // 背景色
    .selectedBackgroundColor(R.color.custom_selected)   // 選択時背景色
    .textColor(R.color.custom_text)                     // テキスト色
    .selectedTextColor(R.color.custom_selected_text)    // 選択時テキスト色
    .build()
defaultAdapter.updateOptions(options)
```

カスタム実装が必要な場合は `MapboxRouteCalloutAdapter` を継承し、`onCreateViewHolder` と `onUpdateAnchor` をオーバーライドする。データ更新時は `notifyDataSetChanged()` を呼び出す。

**現状のコード:**

```kotlin
// 選択変更時に View を直接操作
fun updateSelectionStyling(selectedRoute: NavigationRoute?) {
    for ((route, view) in calloutViews) {
        val isSelected = route === selectedRoute
        val etaView = view.findViewById<TextView>(NavR.id.eta) ?: continue
        etaView.setTextColor(if (isSelected) SELECTED_TEXT else UNSELECTED_TEXT)
        etaView.backgroundTintList = ColorStateList.valueOf(
            if (isSelected) SELECTED_BG else UNSELECTED_BG,
        )
    }
}
```

**問題:**

- 有料道路ラベル（「一般道」「有料道路」「¥xxx」）の表示がカスタム実装の主な理由だが、`notifyDataSetChanged()` を使わずに View を直接操作しているため SDK の描画パイプラインと齟齬が生じる可能性がある
- `calloutViews: MutableMap<NavigationRoute, View>` で手動マッピングを管理しているが、SDK が `onCreateViewHolder` の呼び出しタイミングを制御するため、Map の内容と実際の View の状態が不整合になりうる
- `DefaultRouteCalloutAdapter` で対応可能な部分（時間表示、色分け）まで自前実装している

---

### 14. Gesture Handling — Click Listener の解除

**対象ファイル:** `feature/home/src/androidMain/.../HomeMapsMapEffectContent.kt:161-175`

**公式の推奨パターン (Compose):**

```kotlin
DisposableMapEffect(key1 = Unit) { mapView ->
    val clickListener = OnMapClickListener { point ->
        // ...
        false
    }
    mapView.mapboxMap.addOnMapClickListener(clickListener)
    onDispose {
        mapView.mapboxMap.removeOnMapClickListener(clickListener)
    }
}
```

**現状のコード:**

```kotlin
MapEffect { view ->
    view.mapboxMap.addOnMapClickListener { point ->
        val results = currentRouteResults.value
        if (results.isEmpty()) return@addOnMapClickListener false
        routeLineApi.findClosestRoute(point, view.mapboxMap, ROUTE_CLICK_PADDING) { result ->
            // ...
        }
        false
    }
}
```

**問題:**

- Route Line のタップ検知に `addOnMapClickListener` を使っているが、`removeOnMapClickListener` が呼ばれない
- `MapEffect` ではなく `DisposableMapEffect` + `onDispose` を使うべき
- Route Line タップ自体は Navigation SDK の命令的 API のため `MapEffect` 内で処理するのは妥当だが、リスナーのライフサイクル管理が欠けている

---

## 改善優先度

| 優先度 | # | カテゴリ | 影響 |
|---|---|---|---|
| **P0** | 2 | `setNavigationRoutes` 導入 | 全 Observer の前提条件。これなしに他の改善は意味がない |
| **P0** | 1 | `MapboxNavigationApp.attach()` | SDK ライフサイクルの土台。Observer パターンの前提 |
| **P0** | 3 | `RoutesObserver` で Route Line 更新 | ルート描画の自動化。手動描画コードの大幅削減 |
| **P0** | 5 | `NavigationCamera` + `ViewportDataSource` | カメラ管理の自動化。手動 LaunchedEffect の大幅削減 |
| **P1** | 6 | `NavigationLocationProvider` | 位置情報の一元化。マップマッチング有効化 |
| **P1** | 4 | Callout の Observer 駆動化 | Callout 自動更新。#3 の修正に付随して解決 |
| **P1** | 8 | Listener の register/unregister | メモリリーク防止。`DisposableMapEffect` 導入 |
| **P1** | 7 | `MapView` 参照の除去 | Compose 状態競合防止。#5 の修正で自然に解決 |
| **P2** | 9 | AccessToken 設定タイミング | 初回レンダリング安定化 |
| **P2** | 12 | Traffic Congestion Annotation | 渋滞カラーの実効化 |
| **P2** | 13 | `DefaultRouteCalloutAdapter` 検討 | コード削減・SDK 準拠 |
| **P2** | 11 | `platformRoute: Any?` 型安全化 | silent failure 防止 |
| **P3** | 10 | 自前 Polyline デコーダー削除 | コード整理・一貫性 |
| **P3** | 14 | Click Listener 解除 | 軽微なリーク防止 |

> **依存関係:** P0 の #2 → #1 → #3 → #5 の順で進めるのが効率的。#2 (`setNavigationRoutes`) を導入しないと #3 以降の Observer パターンが機能しない。

---

## 推奨アーキテクチャ

### 概要図

```
┌──────────────────────────────────────────────────────────────┐
│  MapboxNavigation (Singleton via MapboxNavigationApp)         │
│  ├─ setNavigationRoutes(routes)                              │
│  ├─ startTripSession() / stopTripSession()                   │
│  └─ Observers:                                               │
│      ├─ RoutesObserver ──────→ Route Line + Callout          │
│      │                  ──────→ ViewportDataSource            │
│      ├─ RouteProgressObserver ─→ Vanishing Route Line        │
│      │                        ─→ ViewportDataSource          │
│      ├─ LocationObserver ─────→ NavigationLocationProvider    │
│      │                   ─────→ ViewportDataSource           │
│      └─ VoiceInstructionsObserver ──→ TTS (将来)             │
├──────────────────────────────────────────────────────────────┤
│  ViewModel (ビジネスロジックのみ)                               │
│  ├─ requestRoutes → setNavigationRoutes                      │
│  ├─ waypoint 管理                                             │
│  ├─ 検索 (Google Places)                                      │
│  └─ 検索履歴                                                   │
├──────────────────────────────────────────────────────────────┤
│  MapboxNavigationObserver (ライフサイクル管理)                   │
│  ├─ onAttached: register all observers                       │
│  └─ onDetached: unregister all observers                     │
├──────────────────────────────────────────────────────────────┤
│  NavigationCamera + ViewportDataSource                       │
│  ├─ Following (ナビ追従中)                                     │
│  ├─ Overview (ルートプレビュー・全体俯瞰)                       │
│  └─ Idle (ユーザー手動操作中)                                   │
├──────────────────────────────────────────────────────────────┤
│  Compose Layer                                               │
│  ├─ MapboxMap + MapboxStandardStyle                          │
│  ├─ DisposableMapEffect (Route Line Observer 登録/解除)       │
│  ├─ 宣言的 Annotation (ViewAnnotation, Marker)                │
│  └─ UI Controls (Compass, Zoom, Tracking)                    │
└──────────────────────────────────────────────────────────────┘
```

### データフロー

```
[ルート検索]
ViewModel.requestRoutes()
    → DataSource: navigation.requestRoutes(callback)
    → callback: navigation.setNavigationRoutes(routes)
    → RoutesObserver.onRoutesChanged 自動発火
        → routeLineApi.setNavigationRoutes(routes, alternativesMetadata)
        → routeLineView.renderRouteDrawData(style, value)  // Route Line + Callout 自動更新
        → viewportDataSource.onRouteChanged(routes.first())
        → viewportDataSource.evaluate()  // カメラ自動更新

[位置更新]
Navigation SDK 内部の LocationEngine
    → LocationObserver.onEnhancedLocationChanged (マップマッチング済み)
        → navigationLocationProvider.changePosition(location)  // Puck 更新
        → viewportDataSource.onLocationChanged(location)
        → viewportDataSource.evaluate()  // カメラ自動更新

[ナビ進行]
Navigation SDK 内部の Trip Session
    → RouteProgressObserver.onRouteProgressChanged
        → routeLineApi.updateWithRouteProgress(progress)
        → routeLineView.renderRouteLineUpdate(style, result)  // 走行済み区間更新
        → viewportDataSource.onRouteProgressChanged(progress)
        → viewportDataSource.evaluate()  // カメラ自動更新
```

### 主な変更点

| 現状 | 改善後 |
|---|---|
| ViewModel が Route Line 描画データを StateFlow で管理 | `RoutesObserver` が Route Line を直接更新 |
| `LaunchedEffect` でカメラを手動操作 | `NavigationCamera` + `ViewportDataSource` が自動管理 |
| Maps SDK デフォルトの LocationProvider | `NavigationLocationProvider` でマップマッチング済み位置 |
| `MapView` 参照を `remember` で保持 | 不要（`NavigationCamera` が内部で管理） |
| 手動 `reorderRoutes` で選択切り替え | `setNavigationRoutes` の先頭入れ替えで SDK が自動管理 |
| `MapEffect` でリスナー登録（解除なし） | `DisposableMapEffect` + `onDispose` |
| Callout を手動で View 操作 | `RoutesObserver` → `renderRouteDrawData` で自動更新 |

---

## 参考ドキュメント

| トピック | URL |
|---|---|
| Navigation SDK 初期化 | https://docs.mapbox.com/android/navigation/guides/get-started/initialization/ |
| Route Line | https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/ |
| Route Callout | https://docs.mapbox.com/android/navigation/guides/ui-components/route-callout/ |
| Camera (NavigationCamera) | https://docs.mapbox.com/android/navigation/guides/ui-components/camera/ |
| Jetpack Compose 統合 | https://docs.mapbox.com/android/maps/guides/using-jetpack-compose/ |
| Interactions | https://docs.mapbox.com/android/maps/guides/user-interaction/interactions/ |
| Gestures | https://docs.mapbox.com/android/maps/guides/user-interaction/gestures/ |
