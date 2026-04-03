# 11. Home Map Mapbox Best-Practice Audit — Final

## Overview

本ドキュメントは、OneNavi の Home 画面地図実装が Mapbox Maps SDK / Navigation SDK の公式推奨パターンにどの程度準拠しているかを監査した最終結果である。

CC (Claude Code Opus) と Codex (GPT) が独立に実施した監査結果を突き合わせ、相互レビューを 2 往復行った上で確定した。

- 対象日: 2026-04-03
- 入力ドキュメント:
  - `11_home_map_mapbox_best_practice_audit_cc.md` — CC 監査
  - `11_home_map_mapbox_best_practice_audit_codex.md` — Codex 監査
  - `11_home_map_mapbox_best_practice_audit_cc_reviewed.md` — CC 統合レビュー
  - `11_home_map_mapbox_best_practice_audit_codex_reviewd.md` — Codex クロスレビュー
  - CC ↔ Codex 相互指摘（会話ログ）

---

## Executive Summary

現在の Home 地図実装は「Mapbox SDK を利用している」が「Mapbox エコシステムの推奨アーキテクチャに沿っている」とは言えない。

問題は単一の根本原因に還元できず、以下の**独立した 4 つの構造的問題**が併存している:

1. **Navigation ownership の二重化** — `MapboxNavigationApp` と `MapboxNavigationProvider` の併用
2. **Search provider の分裂** — 地図は Mapbox、検索は Google Places
3. **Route ownership の未接続** — 取得したルートが Navigation SDK に登録されず、Observer パターンに乗っていない
4. **shared / platform 境界の破れ** — `commonMain` の model が Android の `NavigationRoute` を `Any?` で暗黙に前提

この状態では、reroute、traffic refresh、primary route 切替、invalid alternatives 除去、active guidance への拡張など、Mapbox Navigation SDK が前提とする更新経路に乗れない。

---

## Current Implementation Snapshot

```
[DataSource 層]
  MapboxNavigationRouteDataSource
    └─ navigation.requestRoutes(callback) → List<NavigationRoute>
  GooglePlacesSearchDataSource
    └─ Google Places API で検索候補・詳細取得

[ViewModel 層 — commonMain]
  HomeMapViewModel
    ├─ 検索クエリ / 候補 / 結果 / 選択地点を StateFlow で保持
    ├─ RouteResult(item, platformRoute: Any?) を StateFlow で公開
    ├─ userLatitude / userLongitude を StateFlow で保持
    └─ ViewEvent ごとに検索 / ルート探索 / 経由地更新を呼び出す

[Compose 層 — androidMain]
  HomeMapScreenContent
    ├─ MapViewportState を rememberMapViewportState() で保持
    ├─ MapView 参照を remember で保持
    ├─ selectedResult / searchResults / routeResults の変化に応じて
    │   LaunchedEffect 内で MapView 経由のカメラ操作
    └─ BottomSheetScaffold で検索結果・ルート結果を表示
  HomeMapsMapEffectContent
    ├─ MapEffect 内で location component / route line / callout / click listener を設定
    ├─ routeResults 変更時に MapboxRouteLineApi.setNavigationRoutes() で手動描画
    └─ 選択変更時は callout view を直接操作
```

---

## Confirmed Findings

以下は現時点で明確に改善対象と確定した指摘。

---

### F-01. Navigation ownership の二重化

**重大度: Critical**

**違反箇所:**

- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/OneNaviApplication.kt:55-59`

**現状:**

```kotlin
if (!MapboxNavigationApp.isSetup()) {
    MapboxNavigationApp.setup(options)
}
if (!MapboxNavigationProvider.isCreated()) {
    MapboxNavigationProvider.create(options)
}
```

`MapboxNavigationApp.setup()` と `MapboxNavigationProvider.create()` を同時に使っている。Mapbox 公式は両者の同時利用を想定していない。

さらに `MapboxNavigationApp.attach(lifecycleOwner)` が一切呼ばれておらず、Navigation SDK のインスタンスが Android ライフサイクルに紐付かない。

**問題:**

- observer 登録、画面 attach/detach、ルート更新通知の責務が分裂する
- `MapboxNavigationObserver` パターン（`onAttached` / `onDetached`）が使えない
- バックグラウンド移行時のリソース解放が行われない

**推奨パターン:**

```kotlin
// Application.onCreate()
MapboxNavigationApp.setup {
    NavigationOptions.Builder(context)
        .isDebugLoggingEnabled(BuildConfig.DEBUG)
        .build()
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

// MapboxNavigation へのアクセス
MapboxNavigationApp.current()?.requestRoutes(...)
```

所有者を `MapboxNavigationApp` に一本化し、`MapboxNavigationProvider.create()` の直接呼び出しを廃止する。

**ソース:** [Navigation SDK - Initialization](https://docs.mapbox.com/android/navigation/guides/get-started/initialization/)

---

### F-02. Search provider が Mapbox と Google Places に分裂

**重大度: Critical**

**違反箇所:**

- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/di/DataSourceModule.android.kt:22`
- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/GooglePlacesSearchDataSource.kt`
- `feature/home/src/commonMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapViewModel.kt:267-296`

**現状:**

| 機能 | 使用 SDK |
|---|---|
| 地図表示 / POI interaction | Mapbox Maps SDK |
| ルート検索 / 描画 | Mapbox Navigation SDK |
| 検索候補 / 検索結果 / 詳細取得 | Google Places API |

Mapbox Standard Style の POI タップ後に、取得した `name + coordinate` を使って Google Places にテキスト再検索している (`HomeMapViewModel.kt:275`)。これは「Mapbox で取れた地点を Google で引き直す」処理であり、provider 間の place identity が一致しない。

仕様書 (`docs/spec/03_technology_evaluation.md`) では Mapbox Geocoding API を採用と記載されているが、実装は Google Places になっている。

**問題:**

- Mapbox 地図上の POI と最終的に選択される検索結果が別 provider の別 object になる
- place id、ranking、address normalization が Mapbox 側と一致しない
- Mapbox Search SDK の `search / select / reverse geocoding` 推奨フローに乗っていない
- 仕様書と実装の不整合

**必要な判断:**

- Mapbox-native にするなら Google Places を外し、Mapbox Search SDK に統一
- Google Places を残すなら、それは「Mapbox ベストプラクティス準拠を諦める設計判断」として仕様書を更新

**ソース:** [Mapbox Search SDK - Geocoding](https://docs.mapbox.com/android/search/guides/search-engine/geocoding/)

---

### F-03. Route ownership が Navigation SDK に接続されていない

**重大度: Confirmed (strongly recommended)**

**違反箇所:**

- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/MapboxNavigationRouteDataSource.kt:58-66`
- `feature/home/src/commonMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapViewModel.kt:248-264`

**現状のフロー:**

```
navigation.requestRoutes(routeOptions, callback)
    ↓
callback で List<NavigationRoute> 取得
    ↓
RouteResult(item, platformRoute: Any?) に変換
    ↓
ViewModel の StateFlow へ
    ↓
Compose MapEffect で手動描画
```

`navigation.setNavigationRoutes(routes)` が一切呼ばれていない。取得したルートが Navigation SDK の内部状態に登録されないため、`RoutesObserver` が発火せず、SDK が前提とする observer-driven な更新経路に接続できない。

**推奨フロー:**

```
navigation.requestRoutes(routeOptions, callback)
    ↓
callback で List<NavigationRoute> 取得
    ↓
navigation.setNavigationRoutes(routes)   ← SDK にルートを登録
    ↓
RoutesObserver.onRoutesChanged が自動発火
    ↓
UI 更新（Route Line, Callout, Camera 等）
```

**論理的根拠:**

F-04（Route Line / Callout の observer-driven 化）が Confirmed である以上、その前提条件である `setNavigationRoutes()` も Confirmed でなければ整合しない。`RoutesObserver` は `setNavigationRoutes()` が呼ばれた時に発火するため、一方を採用して他方を見送ることはできない。

**設計判断が必要な点:**

`setNavigationRoutes()` をルートプレビュー段階で呼ぶ場合、Trip Session との関係で課金が発生する可能性がある。**Mapbox の pricing model を確認の上、preview 段階でどのセッション境界で SDK ownership に渡すかを設計判断すること。** Conditional なのは API 呼び出しの必要性ではなく、この設計判断の方である。

**ソース:** [Navigation SDK - Route Line](https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/), [Navigation SDK - Route Callout](https://docs.mapbox.com/android/navigation/guides/ui-components/route-callout/)

---

### F-04. Route Line / Route Callout が observer-driven ではない

**重大度: High**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt:187-217`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapRouteCalloutAdapter.kt:36-57`

**現状 (Route Line):**

```kotlin
MapEffect(routeResults, selectedRouteIndex) { mapView ->
    val navigationRoutes = routeResults.mapNotNull { it.platformRoute as? NavigationRoute }
    val reordered = reorderRoutes(navigationRoutes, selectedRouteIndex)
    routeLineApi.setNavigationRoutes(reordered) { expected ->
        routeLineView.renderRouteDrawData(style, expected)
    }
}
```

Compose の `MapEffect` キー変更をトリガーに手動で描画している。

**現状 (Callout):**

```kotlin
routeCalloutAdapter.updateRouteResults(routeResults)          // ルート変更時
routeCalloutAdapter.updateSelectionStyling(selectedRoute)     // 選択変更時 — View 直接操作
```

`notifyDataSetChanged()` を使わず View を直接操作している。

**推奨パターン:**

```kotlin
private val routesObserver = object : RoutesObserver {
    override fun onRoutesChanged(result: RoutesUpdatedResult) {
        val alternativesMetadata = mapboxNavigation.getAlternativeMetadataFor(
            result.navigationRoutes
        )
        routeLineApi.setNavigationRoutes(
            result.navigationRoutes,
            alternativesMetadata,
        ) { value ->
            routeLineView.renderRouteDrawData(mapStyle, value)
            // ↑ ここで Callout も自動更新される
        }
    }
}
```

これにより以下が自動化される:

- primary route の変更
- reroute 後の再描画
- traffic / duration refresh
- fork 通過後の invalid alternative 除去

**ソース:** [Navigation SDK - Route Line](https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/), [Navigation SDK - Route Callout](https://docs.mapbox.com/android/navigation/guides/ui-components/route-callout/)

---

### F-05. `alternativesMetadata` を渡していない

**重大度: High**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt:209`

**現状:**

```kotlin
routeLineApi.setNavigationRoutes(reordered) { expected ->
    routeLineView.renderRouteDrawData(style, expected)
}
```

`setNavigationRoutes` に `alternativesMetadata` を渡していない。

**推奨パターン:**

```kotlin
val alternativesMetadata = mapboxNavigation.getAlternativeMetadataFor(routes)
routeLineApi.setNavigationRoutes(
    routes,
    alternativesMetadata,   // 必須
) { value ->
    routeLineView.renderRouteDrawData(mapStyle, value)
}
```

**問題:**

- プライマリルートと代替ルートの重複区間が非表示にならない
- 代替ルートの可視化品質が公式推奨に未到達

**ソース:** [Navigation SDK - Route Line](https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/)

---

### F-06. Compose Listener の dispose 欠落

**重大度: High**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt:144`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt:150`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt:161`

**現状:**

```kotlin
MapEffect { view ->
    view.location.addOnIndicatorPositionChangedListener { point -> ... }
    view.location.addOnIndicatorBearingChangedListener { bearing -> ... }
    view.mapboxMap.addOnMapClickListener { point -> ... }
    routeCalloutAdapter.setOnCalloutClickListener { clickedRoute -> ... }
}
```

4 つのリスナーを登録しているが、対応する remove が一切ない。

**推奨パターン:**

```kotlin
DisposableMapEffect(key1 = Unit) { mapView ->
    val positionListener = OnIndicatorPositionChangedListener { point -> ... }
    val bearingListener = OnIndicatorBearingChangedListener { bearing -> ... }
    val clickListener = OnMapClickListener { point -> ... }

    mapView.location.addOnIndicatorPositionChangedListener(positionListener)
    mapView.location.addOnIndicatorBearingChangedListener(bearingListener)
    mapView.mapboxMap.addOnMapClickListener(clickListener)

    onDispose {
        mapView.location.removeOnIndicatorPositionChangedListener(positionListener)
        mapView.location.removeOnIndicatorBearingChangedListener(bearingListener)
        mapView.mapboxMap.removeOnMapClickListener(clickListener)
    }
}
```

**問題:**

- `addOnIndicatorPositionChangedListener` は高頻度で発火するリスナー。重複登録時のパフォーマンス劣化リスクが大きい
- Composable の再生成や `MapView` 差し替え時にリスナーが蓄積する

**ソース:** [Maps SDK - Gestures](https://docs.mapbox.com/android/maps/guides/user-interaction/gestures/), [Maps SDK - Jetpack Compose](https://docs.mapbox.com/android/maps/guides/using-jetpack-compose/)

---

### F-07. Shared model に Android SDK object が漏れている

**重大度: High**

**違反箇所:**

- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/RouteResult.kt:13`

**現状:**

```kotlin
@Immutable
data class RouteResult(
    val item: RouteItem,
    val platformRoute: Any? = null,
)
```

`commonMain` の model が Android の `NavigationRoute` を `Any?` で暗黙に前提している。以下の箇所で `as? NavigationRoute` キャストが散在:

- `HomeMapsMapEffectContent.kt:198`
- `HomeMapScreenContent.kt:239`
- `HomeMapRouteCalloutAdapter.kt:168`（間接的に `===` 比較）

加えて、ルート選択が `selectedRouteIndex: Int` と `===` 参照比較に依存しており、refresh / reroute 後に object identity が変わると選択状態が不安定になる。

**問題:**

- KMP 境界を破って Android SDK object を shared model に持ち込んでいる
- キャスト失敗時にルート線が描画されない silent failure のリスク
- route identity 管理が脆弱

**推奨:**

- shared 層は UI summary と stable route id のみ保持
- `NavigationRoute` 対応表は Android layer に隔離
- UI 選択状態は stable id ベースに移行

---

### F-08. Custom Callout Adapter が SDK の redraw パスから外れている

**重大度: Medium**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapRouteCalloutAdapter.kt:36-57`

**現状:**

```kotlin
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

`notifyDataSetChanged()` を使わずに View を直接操作している。`calloutViews: MutableMap<NavigationRoute, View>` で手動マッピングを管理しているが、SDK が `onCreateViewHolder` の呼び出しタイミングを制御するため、Map の内容と実際の View 状態が不整合になりうる。

有料道路ラベル（「一般道」「有料道路」「¥xxx」）表示のためにカスタム adapter を作る判断自体は正当。問題は adapter の存在ではなく、更新経路が SDK 推奨の redraw / observer パターンに乗っていない点。

**推奨:** F-04 で observer-driven に移行した後、adapter の更新を `notifyDataSetChanged()` ベースに整理する。

**ソース:** [Navigation SDK - Route Callout](https://docs.mapbox.com/android/navigation/guides/ui-components/route-callout/)

---

### F-09. Camera 制御が ad-hoc で state machine 化されていない

**重大度: Medium**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt:142`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt:157`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt:190`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt:217`

**現状:**

- follow puck は `transitionToFollowPuckState()` で制御
- 検索結果・選択地点は `easeTo` / `flyTo` を `LaunchedEffect` で直接呼ぶ
- ルート表示は `flyTo` を `LaunchedEffect` で直接呼ぶ
- `ViewportStatus.Idle` を監視して `trackingMode = null` に戻すローカル制御

**問題:**

- camera mode が UI の if/else と side effect に散らばっている
- follow / non-follow / route overview の切替が ad-hoc
- 将来の free-drive / active guidance 拡張に弱い

**推奨:** camera mode を明示的な state として定義する:

```kotlin
sealed interface CameraMode {
    data class FollowPuck(val trackingMode: LocationTrackingMode) : CameraMode
    data class SelectedPlace(val point: Point) : CameraMode
    data class SearchResultsOverview(val points: List<Point>) : CameraMode
    data class RouteOverview(val routeIds: List<String>) : CameraMode
    data object Idle : CameraMode
}
```

この state を Android map layer が `MapViewportState` の遷移に変換する。

---

### F-10. 高頻度な位置更新を shared ViewModel に流している

**重大度: Medium**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt:144`
- `feature/home/src/commonMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapViewModel.kt:172-175`

**現状:**

```kotlin
// HomeMapsMapEffectContent
view.location.addOnIndicatorPositionChangedListener { point ->
    onUserLocationUpdated(point.latitude(), point.longitude())
}

// HomeMapViewModel
fun onUserLocationUpdated(latitude: Double, longitude: Double) {
    _userLatitude.value = latitude
    _userLongitude.value = longitude
}
```

`OnIndicatorPositionChangedListener` は animation frame 単位で発火する可能性がある。これを shared ViewModel にそのまま流すと、Map rendering frequency と shared business state が混ざる。

**推奨:**

- UI / camera 用の high-frequency position は Android map layer に閉じ込める
- ルート探索や検索 bias に必要な coarse な現在地だけを shared 層へ渡す

---

## Conditional Findings

以下は将来の Navigation SDK 本格活用を前提にすれば強く推奨だが、現時点の Home 画面（ルートプレビュー段階）では直ちに違反と断定しない。

---

### C-01. `NavigationCamera` / `MapboxNavigationViewportDataSource` 未使用

**分類: Conditional but strongly recommended**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt:157-266`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapControls.kt:81-86`

**現状:**

カメラ管理を全て `LaunchedEffect` 内の `easeTo` / `flyTo` / `transitionToFollowPuckState` で手動実装している。

**公式推奨:**

```kotlin
viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap)
navigationCamera = NavigationCamera(mapboxMap, cameraPlugin, viewportDataSource)

// RoutesObserver 内
viewportDataSource.onRouteChanged(routes.first())
viewportDataSource.evaluate()

// LocationObserver 内
viewportDataSource.onLocationChanged(enhancedLocation)
viewportDataSource.evaluate()

// RouteProgressObserver 内
viewportDataSource.onRouteProgressChanged(routeProgress)
viewportDataSource.evaluate()

// モード切り替え
navigationCamera.requestNavigationCameraToFollowing()
navigationCamera.requestNavigationCameraToOverview()
```

**判断根拠:**

- F-09 で camera state machine の問題を Confirmed にしている以上、`NavigationCamera` はその SDK 公式解として最有力の移行先
- ただし現時点の Home 画面は active guidance UI ではないため、「未使用それ自体が即違反」とまでは言わない
- route ownership を Navigation SDK 側へ寄せる段階（F-03 実施後）では、ほぼ必然的に採用することになる

**ソース:** [Navigation SDK - Camera](https://docs.mapbox.com/android/navigation/guides/ui-components/camera/)

---

### C-02. `NavigationLocationProvider` 未使用

**分類: Conditional**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt:140-143`

**現状:**

```kotlin
view.location.enabled = true
view.location.locationPuck = createDefault2DPuck(withBearing = true)
view.location.puckBearing = PuckBearing.HEADING
```

Maps SDK のデフォルト LocationProvider を使用している。

**公式推奨:**

```kotlin
val navigationLocationProvider = NavigationLocationProvider()

val locationObserver = object : LocationObserver {
    override fun onEnhancedLocationChanged(enhancedLocation: Location, keyPoints: List<Location>) {
        navigationLocationProvider.changePosition(enhancedLocation, keyPoints)
    }
    override fun onRawLocationChanged(rawLocation: Location) {}
}

mapView.location.setLocationProvider(navigationLocationProvider)
```

**判断根拠:**

- Navigation SDK はマップマッチング・道路スナッピング処理を行い `enhancedLocation` として提供する。カーナビアプリにとってスナッピングは UX に直結する
- ただし、route preview / place exploration 段階では Maps SDK の location component を直接使うこと自体は成立する
- free-drive / active guidance で Navigation SDK の location stream を主にする段階で採用すべき

**ソース:** [Navigation SDK - Location](https://docs.mapbox.com/android/maps/guides/user-location/location-on-map/)

---

### C-03. `MapView` 参照を `MapEffect` 外で保持

**分類: Conditional / 危険な構図**

**違反箇所:**

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt:80`

**現状:**

```kotlin
var mapView by remember { mutableStateOf<MapView?>(null) }

// LaunchedEffect 内で使用
LaunchedEffect(searchResults, mapView) {
    val currentMapView = mapView ?: return@LaunchedEffect
    val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(...)
    viewportState.flyTo(cameraOptions, ...)
}
```

**公式の警告:**

> "Using MapView APIs within a MapEffect can introduce internal state changes that may conflict with Compose states, potentially leading to unexpected or undefined behavior."

ただし、クラッシュが明示されているのは `lifecycle`, `compass`, `logo`, `attribution` など特定の API であって、`cameraForCoordinates()` 自体が即アウトとは書かれていない。

**判断根拠:**

- 明確な違反ではなく「危険な構図」
- C-01 (`NavigationCamera` 採用) や F-09 (camera state machine 整理) で自然に解消される
- 独立して急いで直す必要はないが、上記の改善時に消滅させる

**ソース:** [Maps SDK - Jetpack Compose](https://docs.mapbox.com/android/maps/guides/using-jetpack-compose/)

---

### C-04. Route preview と active navigation の境界

**分類: Conditional**

F-03 (`setNavigationRoutes` 導入) の設計判断に付随する問題。preview route を Navigation SDK に ownership を渡すタイミングが未定義。

- 「検索結果から作った preview route」と「ナビ開始後の active route」を同じ route ownership model に載せる設計が必要
- Trip Session のライフサイクルと課金の影響を調査の上、boundary を定義すること

---

## Rejected / Downgraded

以下は監査過程で指摘されたが、最終的に finding として不採用とした項目。

| 項目 | 判断 | 理由 |
|---|---|---|
| AccessToken 設定タイミング | **Rejected** | `composeApp/src/androidMain/res/values/strings.xml:4` に `mapbox_access_token` リソースが既に存在。Maps SDK はリソースからトークンを自動取得するため、初回ロード失敗は発生しない。残る論点は設定責務の分散のみで、cleanup issue として P3 に格下げ |
| Traffic Congestion Annotation 不足 | **Rejected** | `MapboxNavigationRouteDataSource.kt:49` で `applyDefaultNavigationOptions()` を使用済み。公式ドキュメントはこの API が `PROFILE_DRIVING_TRAFFIC` と必要な annotations を含むと記載しており、不足とは言えない |
| `DefaultRouteCalloutAdapter` 不使用 | **Rejected** | 有料道路ラベル・料金表示のカスタム要件がある以上、独自 adapter の選択は正当。問題は adapter の存在ではなく redraw パスの乖離 (F-08 で対応) |
| Polyline デコーダー自前実装 | **Downgraded** | SDK の `LineString.fromPolyline()` との混在は一貫性が悪いが、Mapbox best-practice 論点ではなく保守性の話。cleanup issue として P3 |
| Token source of truth 分散 | **Downgraded** | `strings.xml` / `BuildKonfig` / `MapboxOptions.accessToken` の複数設定箇所。cleanup issue として P3 |

---

## Positive Observations

現状実装で Mapbox 推奨に近い点:

- `MapViewportState` を Compose 側で `rememberMapViewportState()` で保持 — 正しい
- `MapboxStandardStyle` + `StandardStyleState` を使用 — 正しい
- Location Puck 表示は Mapbox の location component を使用 — 正しい
- `RouteOptions.builder().applyDefaultNavigationOptions()` の利用 — 正しい
- Route Line / Callout の有効化手順自体は SDK の想定に近い — 正しい
- `RouteLineColorResources` での渋滞カラー設定 — 正しい
- Compose 宣言的 Annotation (`ViewAnnotation`, `Marker`) の活用 — 正しい
- `DisposableEffect` での `routeLineApi.cancel()` / `routeLineView.cancel()` — 正しい
- 検索クエリの `debounce(300ms)` + `distinctUntilChanged()` — 正しい
- Toll-free ルート優先の 2 段階検索ロジック — 独自だが妥当
- POI タップの `standardStyleState.interactionsState.onPoiClicked` — Compose API 正しく使用

---

## Action Plan

### Phase 0 — 土台修正

| # | 対応 Finding | 内容 |
|---|---|---|
| P0-1 | F-01 | Navigation ownership を `MapboxNavigationApp` に一本化。`MapboxNavigationProvider.create()` 廃止、`attach/detach` 導入 |
| P0-2 | F-02 | Search provider 方針を決定。Mapbox Search SDK 統一 or Google Places 維持を設計判断として明記 |
| P0-3 | F-06 | 全 Listener を `DisposableMapEffect` + `onDispose` に移行 |

### Phase 1 — Observer パターン導入

| # | 対応 Finding | 内容 | 前提 |
|---|---|---|---|
| P1-1 | F-03 | `setNavigationRoutes()` 導入。preview/session 境界の設計判断を先に確定 | P0-1, pricing 調査 |
| P1-2 | F-04, F-05 | `RoutesObserver` 導入。Route Line + Callout を observer-driven に。`alternativesMetadata` を渡す | P1-1 |
| P1-3 | F-07 | shared model から `NavigationRoute` を追い出し、stable route id を導入 | P1-1 |

### Phase 2 — Camera / Location 整理

| # | 対応 Finding | 内容 | 前提 |
|---|---|---|---|
| P2-1 | F-09 | Camera mode を state machine として整理。sealed interface で明示化 | なし |
| P2-2 | C-01 | 必要に応じて `NavigationCamera` / `ViewportDataSource` を採用 | P1-2, P2-1 |
| P2-3 | F-10, C-02 | 位置更新の責務分離。必要に応じて `NavigationLocationProvider` 採用 | P0-1 |
| P2-4 | C-03 | `MapView` 参照の外部保持を解消（P2-1, P2-2 で自然に解消される見込み） | P2-1 |

### Phase 3 — Cleanup

| # | 対応 Finding | 内容 |
|---|---|---|
| P3-1 | F-08 | Callout Adapter を `notifyDataSetChanged()` ベースに整理。observer-driven 移行後に再評価 |
| P3-2 | C-04 | Route preview session の設計。preview → active guidance の遷移ポイントを定義 |
| P3-3 | — | Token source of truth を 1 箇所に集約 |
| P3-4 | — | 自前 Polyline デコーダー削除。`LineString.fromPolyline()` に統一 |

---

## Reference Documents

| トピック | URL |
|---|---|
| Navigation SDK 初期化 | https://docs.mapbox.com/android/navigation/guides/get-started/initialization/ |
| MapboxNavigationApp API | https://docs.mapbox.com/android/navigation/api/coreframework/3.7.2/navigation/com.mapbox.navigation.core.lifecycle/-mapbox-navigation-app/index.html |
| MapboxNavigationProvider API | https://docs.mapbox.com/android/navigation/api/coreframework/3.8.5/navigation/com.mapbox.navigation.core/-mapbox-navigation-provider/ |
| Route Line | https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/ |
| Route Callout | https://docs.mapbox.com/android/navigation/guides/ui-components/route-callout/ |
| Camera (NavigationCamera) | https://docs.mapbox.com/android/navigation/guides/ui-components/camera/ |
| NavigationLocationProvider API | https://docs.mapbox.com/android/navigation/api/coreframework/3.10.0/ui-maps/com.mapbox.navigation.ui.maps.location/-navigation-location-provider/ |
| Maps SDK - Jetpack Compose | https://docs.mapbox.com/android/maps/guides/using-jetpack-compose/ |
| Maps SDK - Interactions | https://docs.mapbox.com/android/maps/guides/user-interaction/interactions/ |
| Maps SDK - Gestures | https://docs.mapbox.com/android/maps/guides/user-interaction/gestures/ |
| Mapbox Search SDK - Geocoding | https://docs.mapbox.com/android/search/guides/search-engine/geocoding/ |
| Maps SDK - MapEffect API | https://docs.mapbox.com/android/maps/api/11.1.0/mapbox-maps-android/com.mapbox.maps.extension.compose/-map-effect.html |
| MapboxMap API | https://docs.mapbox.com/android/maps/api/11.12.1/mapbox-maps-android/com.mapbox.maps/-mapbox-map/ |
