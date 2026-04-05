# HomeMapScreen 状態管理リファクタリング計画 v2

**作成日**: 2026-04-05  
**対象**: `feature/home` モジュール — HomeMap 関連ファイル群  
**ステータス**: 計画確定（セルフレビュー反映済み）  
**経緯**: v1 は CC 初案 → Codex レビュー 2 回 → 仕様深掘りを経て本 v2 に統合

---

## 1. 背景・目的

`HomeMapScreen` は複数の画面状態を持つが、現状はそれらを**複数のフラグの組み合わせ**で表現している。
フラグ間の依存関係が暗黙的で、カメラ移動の競合・BottomSheet 開閉ロジックの複雑化・デバッグ困難といった問題が既に顕在化している。

本ドキュメントでは現状の問題点を整理し、`sealed interface HomeMapScreenState` を `combine` で導出する設計への移行計画を記述する。

---

## 2. 現状の問題点

### 2.1 状態がフラグの組み合わせで表現されている

画面状態は以下のフラグ群の AND/OR で決まる:

```
// ViewModel
searchResults: ImmutableList<SearchResultItem>   // 空 or 非空
selectedResult: SearchResultItem?                // null or non-null
routeResults: ImmutableList<RouteResult>         // 空 or 非空
waypoints: ImmutableList<RouteWaypoint>          // 空 or 非空
editingWaypointIndex: Int?                       // null or non-null
navigationState: NavigationState                 // Browsing / RoutePreview / ActiveGuidance / Arrival

// Composable ローカル
isNavigating = navigationState is ActiveGuidance
isArrived    = navigationState is Arrival
allowSheetHide: Boolean
```

8+ 個のフラグで理論上 256 通り。有効な状態は 6〜7 通りだが、**無効な組み合わせを防ぐ仕組みがない**。

### 2.2 カメラ移動の LaunchedEffect が競合

`HomeMapScreenContent` に 4 つのカメラ制御 LaunchedEffect が存在し、複数キーが同時に変化するケースで競合が起きる。

| # | キー | 処理 |
|---|------|------|
| 1 | `LaunchedEffect(searchResults, mapView)` | 全結果が収まるよう `flyTo` |
| 2 | `LaunchedEffect(selectedResult)` | 地点へ `easeTo` |
| 3 | `LaunchedEffect(isNavigating)` | Sheet 開閉 + location provider 切り替え |
| 4 | `LaunchedEffect(routeResults)` | `cameraManager.requestCameraOverview()` |

例: ルート検索完了時に LaunchedEffect(routeResults) と LaunchedEffect(selectedResult) が同時発火し、どちらが勝つかが非決定的。

### 2.3 BottomSheet の開閉制御が脆弱

`allowSheetHide` フラグで `confirmValueChange` をバイパスするパターンが複数 LaunchedEffect に散在。

### 2.4 ViewModel が独立した StateFlow を 10 個公開

整合性保証がない。`_routeResults.value` と `_waypoints.value` を別行で更新するため中間状態が UI に漏れる。

### 2.5 マーカー表示の優先順位チェーンが脆弱

`HomeMapsMapEffectContent` の if-else 連鎖で状態の意味がコードから読めない。

### 2.6 `HomeMapViewEvent` が肥大化

15 種の sealed interface にどの画面状態で有効かが明示されていない。

---

## 3. 仕様決定事項

### 3.1 BottomSheet のユーザー操作

**BottomSheet はユーザーが swipe で閉じることはできない。**

Sheet の表示/非表示は screenState から完全に導出される。`allowSheetHide` は廃止。`confirmValueChange` で `SheetValue.Hidden` を拒否する。

| screenState | Sheet |
|---|---|
| `Browsing` | Hidden |
| `SearchResultsList` | PartiallyExpanded |
| `PlaceDetails` | PartiallyExpanded |
| `RoutePreview` | PartiallyExpanded |
| `Navigating` | Hidden |
| `Arrived` | Hidden（将来変更可能） |

### 3.2 Android 戻るボタン（BackHandler）

固定の戻り先。遷移元を記憶するスタックは不要。

| 現在の state | Back 先 | 補足 |
|---|---|---|
| `SearchResultsList` | `Browsing` | searchResults をクリア |
| `PlaceDetails` | `Browsing` | selectedResult をクリア |
| `RoutePreview` | `PlaceDetails` | selectedResult は維持 |
| `Navigating` | `RoutePreview` | **確認ダイアログなし**。waypoints 保持してルート再検索 |
| `Arrived` | `Browsing` | 全リセット |
| Overlay 表示中 | Overlay を閉じるだけ | screenState は変えない |

### 3.3 Arrived（到着）画面

- sealed interface に `Arrived` 状態を**定義する**
- **UI は今回実装しない**。到着検知時はナビ停止と同じ扱い（ルート再検索 → RoutePreview）
- 将来 Phase で到着サマリー UI を追加可能な設計にしておく

### 3.4 ナビ停止時のルート情報

- ナビ停止時は **waypoints を保持したままルートを再検索する**
- reroute で変わった可能性があるため旧 routeResults / selectedRouteIndex は使わない
- `Navigating` state に `selectedRouteIndex` を**持たせない**（stale になるため）

### 3.5 Loading 表示

- 各 screenState に `isLoading: Boolean` フィールドを持たせる
- `reduceScreenState()` に `_isRouteSearching` 等の loading flag を入力として渡す
- BottomSheet 内で loading を表現（ボタン loading 状態、progress indicator 等）

### 3.6 エラー表示

今回のスコープ外。現状のログ出力のみを維持する。

---

## 4. 設計

### 4.1 アーキテクチャ: combine 導出 + Overlay State + Effect Stream

```
┌─────────────── ViewModel ───────────────┐
│                                         │
│  Raw States (_searchResults, etc.)      │
│         │                               │
│         ▼                               │
│  combine(...) + reduceScreenState()     │
│         │                               │
│         ▼                               │
│  screenState: StateFlow (derived)       │
│                                         │
│  _overlayState: MutableStateFlow        │
│  _effects: Channel                      │
│                                         │
└─────────────────────────────────────────┘
         │              │            │
    screenState    overlayState   effects
         │              │            │
         ▼              ▼            ▼
┌─────────────── Composable ──────────────┐
│                                         │
│  when(screenState) → UI 分岐            │
│  LaunchedEffect(shouldShowSheet) → Sheet│
│  LaunchedEffect(Unit) { collect } → cam │
│  if (overlay) → WaypointSearch          │
│                                         │
└─────────────────────────────────────────┘
```

**3 層の役割**:

| 層 | 型 | 用途 | 復元 |
|---|---|---|---|
| Screen State | `StateFlow<HomeMapScreenState>` | 今どの画面か + データ | combine 導出のため自動復元 |
| Overlay State | `StateFlow<HomeMapOverlayState>` | 画面上のオーバーレイ | ViewModel 生存中は保持 |
| Effect Stream | `Channel<HomeMapEffect>` | one-shot 副作用 | 復元不要（初期復元ロジックで対応） |

**なぜ `_screenState` 手動更新ではなく combine 導出か**:

手動更新方式では、ViewModel の各メソッドに `when(_screenState.value)` のガードが入り、
Composable にあったフラグ分岐が ViewModel の遷移メソッドに移動するだけになる。
combine 導出なら:
- ViewModel のメソッドは**ほぼ今のまま**（raw state を更新するだけ）
- フラグ分岐は `reduceScreenState()` **1 箇所の pure function** に集約
- pure function なのでユニットテストが容易

**なぜ Sheet を Effect に含めないか**:

Sheet の表示/非表示は screenState から 100% 導出可能（仕様 3.1）。
`Channel` は replay しないため Activity 再生成時に復元できないが、
screenState から派生する `LaunchedEffect(shouldShowSheet)` で制御すればこの問題は発生しない。

### 4.2 `HomeMapScreenState`

```kotlin
sealed interface HomeMapScreenState {

    data object Browsing : HomeMapScreenState

    @Immutable
    data class SearchResultsList(
        val query: String,
        val results: ImmutableList<SearchResultItem>,
        val isLoading: Boolean = false,
    ) : HomeMapScreenState

    @Immutable
    data class PlaceDetails(
        val place: SearchResultItem,
        val isLoading: Boolean = false,
    ) : HomeMapScreenState

    @Immutable
    data class RoutePreview(
        val waypoints: ImmutableList<RouteWaypoint>,
        val routes: ImmutableList<RouteResult>,
        val selectedRouteIndex: Int,
        val topBarMode: RoutePreviewTopBarMode,
        val isLoading: Boolean = false,
    ) : HomeMapScreenState

    data object Navigating : HomeMapScreenState

    @Immutable
    data class Arrived(
        val destination: RouteWaypoint,
    ) : HomeMapScreenState
}

@Immutable
sealed interface RoutePreviewTopBarMode {
    data object Viewing : RoutePreviewTopBarMode

    @Immutable
    data class Editing(
        val draftWaypoints: ImmutableList<RouteWaypoint?>,
    ) : RoutePreviewTopBarMode
}
```

**判断点**:

- `SearchResultsList` / `PlaceDetails` 分離 — カメラ・Sheet・マーカーが全て異なるため別状態
- `Navigating` は `data object` — route も selectedRouteIndex も保持しない。ナビ停止時はルート再検索
- `Arrived.destination` は `RouteWaypoint`（Place 限定しない。swap で CurrentLocation が目的地になりうる）
- 各状態に `isLoading` — reduce 関数に loading flag を入力

### 4.3 `reduceScreenState()` — pure function

**注意**: Kotlin の `combine` overload は最大 5 引数。8 個以上の Flow を合成する場合は
`combine(flows: Iterable<Flow<*>>) { values: Array<*> -> }` を使用する（型安全性が落ちるため、
reduce 内のキャスト箇所にコメントを残すこと）。

```kotlin
internal fun reduceScreenState(
    searchResults: ImmutableList<SearchResultItem>,
    selectedResult: SearchResultItem?,
    routeResults: ImmutableList<RouteResult>,
    waypoints: ImmutableList<RouteWaypoint>,
    selectedRouteIndex: Int,
    topBarMode: RoutePreviewTopBarMode,
    lastSearchQuery: String,
    navigationState: NavigationState,
    isRouteSearching: Boolean,
): HomeMapScreenState = when {
    // TODO: 将来 Arrived UI を実装する際はこの分岐を有効化する
    // navigationState is NavigationState.Arrival -> { ... }
    navigationState is NavigationState.ActiveGuidance -> {
        HomeMapScreenState.Navigating
    }
    routeResults.isNotEmpty() && waypoints.isNotEmpty() -> {
        HomeMapScreenState.RoutePreview(
            waypoints = waypoints,
            routes = routeResults,
            selectedRouteIndex = selectedRouteIndex,
            topBarMode = topBarMode,
            isLoading = isRouteSearching,
        )
    }
    searchResults.isNotEmpty() -> {
        HomeMapScreenState.SearchResultsList(
            query = lastSearchQuery,
            results = searchResults,
        )
    }
    selectedResult != null -> {
        HomeMapScreenState.PlaceDetails(
            place = selectedResult,
            isLoading = isRouteSearching,
        )
    }
    else -> HomeMapScreenState.Browsing
}
```

優先順位: `Arrival > ActiveGuidance > RoutePreview > SearchResultsList > PlaceDetails > Browsing`

### 4.4 ViewModel — combine 導出

```kotlin
class HomeMapViewModel(
    private val searchRepository: SearchRepository,
    private val routeRepository: RouteRepository,
    internal val routeManager: RouteManager,
    internal val cameraManager: CameraManager,
    internal val guidanceSessionManager: GuidanceSessionManager,
) : ViewModel() {

    // ── 既存 raw state（ほぼそのまま維持）──
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    private val _selectedResult = MutableStateFlow<SearchResultItem?>(null)
    private val _routeResults = MutableStateFlow<ImmutableList<RouteResult>>(persistentListOf())
    private val _selectedRouteIndex = MutableStateFlow(0)
    private val _waypoints = MutableStateFlow<ImmutableList<RouteWaypoint>>(persistentListOf())

    // ── 新規追加 ──
    private val _topBarMode = MutableStateFlow<RoutePreviewTopBarMode>(RoutePreviewTopBarMode.Viewing)
    private val _isRouteSearching = MutableStateFlow(false)
    private val _lastSearchQuery = MutableStateFlow("")

    // ── 導出: screenState（Array 版 combine を使用）──
    val screenState: StateFlow<HomeMapScreenState> = combine(
        _searchResults, _selectedResult, _routeResults, _waypoints,
        _selectedRouteIndex, _topBarMode, _lastSearchQuery,
        guidanceSessionManager.navigationState, _isRouteSearching,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        reduceScreenState(
            searchResults = values[0] as ImmutableList<SearchResultItem>,
            selectedResult = values[1] as SearchResultItem?,
            routeResults = values[2] as ImmutableList<RouteResult>,
            waypoints = values[3] as ImmutableList<RouteWaypoint>,
            selectedRouteIndex = values[4] as Int,
            topBarMode = values[5] as RoutePreviewTopBarMode,
            lastSearchQuery = values[6] as String,
            navigationState = values[7] as NavigationState,
            isRouteSearching = values[8] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeMapScreenState.Browsing)

    init {
        // Arrival 検知 → 即ナビ停止扱い
        // TODO: 将来 Arrived UI を実装する際はこの監視を外し、reduceScreenState の Arrival 分岐を有効化する
        guidanceSessionManager.navigationState
            .onEach { navState ->
                if (navState is NavigationState.Arrival) {
                    onNavigationStopped()
                }
            }
            .launchIn(viewModelScope)
    }

    // ── Overlay State ──
    private val _overlayState = MutableStateFlow<HomeMapOverlayState>(HomeMapOverlayState.None)
    val overlayState: StateFlow<HomeMapOverlayState> = _overlayState.asStateFlow()

    // ── Effect Stream ──
    private val _effects = Channel<HomeMapEffect>(Channel.BUFFERED)
    val effects: Flow<HomeMapEffect> = _effects.receiveAsFlow()

    // ── 補助データ ──
    val suggestions: StateFlow<ImmutableList<SearchSuggestionItem>> = ...
    val histories: StateFlow<ImmutableList<SearchHistory>> = ...

    // ── メソッド例 ──

    private fun onSearch(query: String, latitude: Double?, longitude: Double?) {
        _lastSearchQuery.value = query  // 検索実行時にクエリを保存（キーストロークごとではない）
        _selectedResult.value = null
        viewModelScope.launch {
            searchRepository.searchMultiple(query, latitude, longitude)
                .onSuccess { results -> _searchResults.value = results.toImmutableList() }
                .onFailure { _searchResults.value = persistentListOf() }
        }
        _effects.trySend(HomeMapEffect.MoveCameraToSearchResults(...))
    }

    private fun onNavigationStarted() {
        guidanceSessionManager.startSession()
        cameraManager.requestCameraFollowing(pitch3D = true)
        _effects.trySend(HomeMapEffect.EnterGuidanceFollowing)
        _effects.trySend(HomeMapEffect.SetKeepScreenOn(enabled = true))
        _effects.trySend(HomeMapEffect.UseNavigationLocationProvider(enabled = true))
    }

    private fun onRouteSearch() {
        val destination = _selectedResult.value ?: return
        val originLat = _userLatitude.value ?: return
        val originLng = _userLongitude.value ?: return

        val newWaypoints = persistentListOf(
            RouteWaypoint.CurrentLocation(originLat, originLng),
            RouteWaypoint.Place(destination.name, destination.latitude, destination.longitude),
        )
        _waypoints.value = newWaypoints
        _routeResults.value = persistentListOf()  // 重要: 旧ルートをクリアしてから検索
        _isRouteSearching.value = true

        viewModelScope.launch {
            routeRepository.searchRoutes(...)
                .onSuccess { results ->
                    val featureResults = results.mapNotNull { it.toFeatureRouteResult() }
                    _selectedRouteIndex.value = 0
                    _topBarMode.value = RoutePreviewTopBarMode.Viewing  // リセット
                    routeManager.setRoutes(featureResults.map { it.navigationRoute })
                    guidanceSessionManager.setNavigationState(NavigationState.RoutePreview)
                    _routeResults.value = featureResults.toImmutableList()
                    _isRouteSearching.value = false
                    _effects.trySend(HomeMapEffect.MoveCameraToRouteOverview)
                }
                .onFailure {
                    _isRouteSearching.value = false
                }
        }
    }

    fun onNavigationStopped() {
        guidanceSessionManager.stopSession()
        guidanceSessionManager.setNavigationState(NavigationState.Browsing)
        _effects.trySend(HomeMapEffect.SetKeepScreenOn(enabled = false))
        _effects.trySend(HomeMapEffect.UseNavigationLocationProvider(enabled = false))
        // waypoints を保持してルート再検索
        searchRoutesFromWaypoints(_waypoints.value)
    }

    fun onBackPressed() {
        when (screenState.value) {
            is HomeMapScreenState.SearchResultsList -> {
                _searchResults.value = persistentListOf()
            }
            is HomeMapScreenState.PlaceDetails -> {
                _selectedResult.value = null
            }
            is HomeMapScreenState.RoutePreview -> {
                _routeResults.value = persistentListOf()
                _waypoints.value = persistentListOf()
                _topBarMode.value = RoutePreviewTopBarMode.Viewing
                routeManager.clearRoutes()
                // selectedResult は維持 → reduce が PlaceDetails を返す
                val place = _selectedResult.value
                if (place != null) {
                    _effects.trySend(HomeMapEffect.MoveCameraToPlace(place))
                }
            }
            is HomeMapScreenState.Navigating -> {
                onNavigationStopped()
            }
            is HomeMapScreenState.Arrived -> {
                _routeResults.value = persistentListOf()
                _waypoints.value = persistentListOf()
                _selectedResult.value = null
                routeManager.clearRoutes()
            }
            else -> { /* Browsing: 何もしない */ }
        }
    }
}
```

### 4.5 `HomeMapOverlayState`

```kotlin
@Immutable
sealed interface HomeMapOverlayState {
    data object None : HomeMapOverlayState

    @Immutable
    data class WaypointSearch(
        val index: Int,
        val initialQuery: String?,
    ) : HomeMapOverlayState
}
```

### 4.6 `HomeMapEffect`

**Sheet の表示/非表示は Effect に含めない**（screenState から派生で十分）。

```kotlin
sealed interface HomeMapEffect {
    data class MoveCameraToSearchResults(
        val results: ImmutableList<SearchResultItem>,
    ) : HomeMapEffect

    data class MoveCameraToPlace(
        val place: SearchResultItem,
    ) : HomeMapEffect

    data object MoveCameraToRouteOverview : HomeMapEffect

    data object EnterGuidanceFollowing : HomeMapEffect

    data object RestoreTracking : HomeMapEffect

    data class SetKeepScreenOn(
        val enabled: Boolean,
    ) : HomeMapEffect

    data class UseNavigationLocationProvider(
        val enabled: Boolean,
    ) : HomeMapEffect
}
```

### 4.7 Sheet 制御 — screenState から派生

```kotlin
val shouldShowSheet = screenState is HomeMapScreenState.SearchResultsList ||
                      screenState is HomeMapScreenState.PlaceDetails ||
                      screenState is HomeMapScreenState.RoutePreview

LaunchedEffect(shouldShowSheet) {
    if (shouldShowSheet) {
        scaffoldState.bottomSheetState.partialExpand()
    } else {
        scaffoldState.bottomSheetState.hide()
    }
}
```

Activity 再生成時も screenState が combine から再導出されるため、Sheet は自動復元される。

### 4.8 カメラ制御 — Effect + 初期復元

```kotlin
LaunchedEffect(Unit) {
    // 初期復元: Activity 再生成後、現在の screenState に応じてカメラを合わせる
    when (val state = viewModel.screenState.value) {
        is HomeMapScreenState.SearchResultsList -> {
            val points = state.results.map { fromLngLat(it.longitude, it.latitude) }
            mapView?.mapboxMap?.cameraForCoordinates(points, ...)?.let { viewportState.flyTo(it) }
        }
        is HomeMapScreenState.PlaceDetails -> {
            viewportState.easeTo(CameraOptions.Builder()
                .center(fromLngLat(state.place.longitude, state.place.latitude))
                .zoom(FOLLOW_PUCK_ZOOM).pitch(0.0).bearing(0.0).build())
        }
        is HomeMapScreenState.RoutePreview -> {
            cameraManager.requestCameraOverview()
        }
        is HomeMapScreenState.Navigating -> {
            cameraManager.requestCameraFollowing(pitch3D = true)
        }
        else -> { /* Browsing: ユーザー追従 */ }
    }

    // 以降は Effect を collect して遷移時カメラ移動を処理
    viewModel.effects.collect { effect ->
        when (effect) {
            is HomeMapEffect.MoveCameraToSearchResults -> {
                trackingMode = null
                val points = effect.results.map { fromLngLat(it.longitude, it.latitude) }
                val padding = EdgeInsets(CAMERA_PADDING_TOP, CAMERA_PADDING, CAMERA_PADDING_BOTTOM, CAMERA_PADDING)
                val opts = mapView?.mapboxMap?.cameraForCoordinates(points, padding, 0.0, 0.0)
                opts?.let { viewportState.flyTo(it, MapAnimationOptions.Builder().duration(1500).build()) }
            }
            is HomeMapEffect.MoveCameraToPlace -> {
                trackingMode = null
                viewportState.easeTo(
                    cameraOptions = CameraOptions.Builder()
                        .center(fromLngLat(effect.place.longitude, effect.place.latitude))
                        .zoom(FOLLOW_PUCK_ZOOM).pitch(0.0).bearing(0.0).build(),
                    animationOptions = MapAnimationOptions.Builder().duration(1500).build(),
                )
            }
            is HomeMapEffect.MoveCameraToRouteOverview -> {
                trackingMode = null
                viewModel.routeManager.routes.first { it.isNotEmpty() }
                cameraManager.applyNavigationPadding(
                    followingPadding = EdgeInsets(0.0, 0.0, 0.0, 0.0),
                    overviewPadding = EdgeInsets(topPadding, horizontal, bottomPadding, endPadding),
                )
                cameraManager.requestCameraOverview()
            }
            is HomeMapEffect.EnterGuidanceFollowing -> {
                cameraManager.requestCameraFollowing(pitch3D = true)
            }
            is HomeMapEffect.RestoreTracking -> {
                trackingMode = LocationTrackingMode.TiltedHeading
            }
            is HomeMapEffect.SetKeepScreenOn -> {
                if (effect.enabled) {
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            is HomeMapEffect.UseNavigationLocationProvider -> {
                if (effect.enabled) {
                    mapView?.location?.setLocationProvider(cameraManager.navigationLocationProvider)
                } else {
                    mapView?.location?.enabled = true
                }
            }
        }
    }
}
```

---

## 5. 状態遷移

### 5.1 遷移図

```
         ┌───────────────────────────────────────────────────────────┐
         │                                                           │
         ▼              [Back]                                       │
      Browsing ──[テキスト検索]──► SearchResultsList ──[Back]──► Browsing
         │                              │
         │                        [結果タップ]
         │                              ▼              [Back]
         ├──[地図タップ / 履歴]──► PlaceDetails ────────────────► Browsing
         │                              │
         │                        [ルート検索成功]
         │                              ▼              [Back]
         │                        RoutePreview ────────────────► PlaceDetails
         │                        (Viewing)
         │                              │
         │                        [ナビ開始]
         │                              ▼              [Back = ナビ停止]
         │                        Navigating ──────────────────► RoutePreview
         │                              │                        (ルート再検索)
         │                        [到着]
         │                              ▼              [Back]
         │                        Arrived ─────────────────────► Browsing
         │                                                       │
         └───────────────────────────────────────────────────────┘

    Overlay は screenState と直交:
    RoutePreview 中に [waypoint タップ] → overlay: WaypointSearch
                     [overlay 閉じる]  → overlay: None
```

### 5.2 遷移テーブル

| イベント | 現在 state | raw state 変更 | Effect |
|---|---|---|---|
| テキスト検索実行 | `Browsing` / `PlaceDetails` | `_selectedResult = null`, `_searchResults = results` | `MoveCameraToSearchResults` |
| 検索結果タップ | `SearchResultsList` | `_searchResults = empty`, `_selectedResult = result` | `MoveCameraToPlace` |
| 地図タップ / POI / 履歴 | `Browsing` / `PlaceDetails` | `_searchResults = empty`, `_selectedResult = result` | `MoveCameraToPlace` |
| ルート検索開始 | `PlaceDetails` | `_routeResults = empty`, `_isRouteSearching = true` | なし |
| ルート検索成功 | `PlaceDetails(isLoading)` | `_routeResults = results`, `_isRouteSearching = false` | `MoveCameraToRouteOverview` |
| ルート選択変更 | `RoutePreview` | `_selectedRouteIndex = index` | `MoveCameraToRouteOverview` |
| 編集開始 | `RoutePreview(Viewing)` | `_topBarMode = Editing(draft)` | なし |
| waypoint タップ | `RoutePreview` | `_overlayState = WaypointSearch` | なし |
| waypoint 確定 | overlay → None | draft 更新, `_overlayState = None` | なし |
| 編集完了 | `RoutePreview(Editing)` | `_topBarMode = Viewing`, 再検索開始 | 成功後 `MoveCameraToRouteOverview` |
| ナビ開始 | `RoutePreview` | guidance session 開始 → `navigationState = ActiveGuidance` | `EnterGuidanceFollowing`, `SetKeepScreenOn(true)`, `UseNavigationLocationProvider(true)` |
| ナビ停止 / Back | `Navigating` | guidance 停止 → `navigationState = Browsing`, ルート再検索 | `SetKeepScreenOn(false)`, `UseNavigationLocationProvider(false)`, 成功後 `MoveCameraToRouteOverview` |
| 到着（当面） | `Navigating` | ナビ停止と同じ扱い（ルート再検索） | 同上 |
| Back | `SearchResultsList` | `_searchResults = empty` | なし |
| Back | `PlaceDetails` | `_selectedResult = null` | なし |
| Back | `RoutePreview` | `_routeResults = empty`, `_waypoints = empty`, route クリア | `MoveCameraToPlace` |
| Back | `Arrived` | 全クリア | なし |
| Back (Overlay) | 変化なし | `_overlayState = None` | なし |

---

## 6. UI 分岐

### 6.1 TopAppBar

```kotlin
when (screenState) {
    is Browsing, is SearchResultsList, is PlaceDetails -> HomeMapTopAppBar(...)
    is RoutePreview -> HomeMapRouteTopAppBar(topBarMode = screenState.topBarMode, ...)
    is Navigating, is Arrived -> { /* なし */ }
}
```

### 6.2 BottomSheet 内容

```kotlin
when (screenState) {
    is SearchResultsList -> HomeMapSearchResultSheet(...)
    is PlaceDetails -> HomeMapSelectedResultSheet(isLoading = screenState.isLoading, ...)
    is RoutePreview -> HomeMapRouteResultSheet(...)
    else -> { /* 表示なし */ }
}
```

### 6.3 操作 UI

```kotlin
when (screenState) {
    is Navigating -> HomeMapNaviContent(...)
    else -> HomeMapControls(...)
}
```

### 6.4 マーカー

```kotlin
when (val state = screenState) {
    is Browsing -> { /* なし */ }
    is SearchResultsList -> {
        state.results.forEachIndexed { index, result ->
            HomeMapNumberedPin(point = ..., number = index + 1)
        }
    }
    is PlaceDetails -> {
        Marker(point = Point.fromLngLat(state.place.longitude, state.place.latitude), ...)
    }
    is RoutePreview -> {
        state.waypoints.lastOrNull()?.let { Marker(...) }
        if (state.waypoints.size > 2) {
            state.waypoints.drop(1).dropLast(1).forEachIndexed { index, waypoint ->
                HomeMapWaypointPin(point = ..., label = "K${index + 1}")
            }
        }
        state.routes.forEachIndexed { index, result -> HomeMapRouteCallout(...) }
    }
    is Navigating, is Arrived -> { /* なし */ }
}
```

### 6.5 Overlay

```kotlin
if (overlayState is HomeMapOverlayState.WaypointSearch) {
    HomeMapWaypointSearchScreen(...)
}
```

---

## 7. 実装計画

### Phase 1: 型定義 + combine 導出

**新規ファイル**:
- `map/state/HomeMapScreenState.kt`
- `map/state/HomeMapOverlayState.kt`
- `map/state/HomeMapEffect.kt`
- `map/state/RoutePreviewTopBarMode.kt`
- `map/state/ReduceScreenState.kt`

**変更**: `HomeMapViewModel.kt`

1. sealed interface + `reduceScreenState()` を定義
2. `_topBarMode`, `_isRouteSearching`, `_overlayState`, `_effects` を追加
3. `combine(...).stateIn(...)` で `screenState` を導出
4. 旧 `onViewEvent` は内部で raw state 更新 + Effect emit に変更
5. ビルド確認

### Phase 2: Compose 切り替え + Effect 導入

**変更**: `HomeMapScreenContent.kt`, `HomeMapSheetContent.kt`, `HomeMapNaviContent.kt`, `HomeMapScreen.kt`

1. `screenState`, `overlayState` を collect
2. 旧 4 つの `LaunchedEffect` を削除
3. Sheet: `LaunchedEffect(shouldShowSheet)` で制御。`allowSheetHide` 廃止
4. カメラ: `LaunchedEffect(Unit) { 初期復元 + effects.collect }` で制御
5. UI 分岐を `when(screenState)` に書き換え
6. BackHandler 追加
7. `HomeMapNaviContent` の引数から `viewModel: HomeMapViewModel` を削除し、必要なデータを個別引数で渡す
8. `HomeMapScreen.kt` (actual) の `onNavigatingChanged` を `screenState is Navigating` から導出する

### Phase 3: マーカー表示整理

**変更**: `HomeMapsMapEffectContent.kt`

1. `screenState` を引数に追加（個別フラグ群を削除）
2. if-else チェーンを `when(screenState)` に書き換え

### Phase 4: RoutePreview 編集 reducer 化

**変更**: `HomeMapViewModel.kt`, `HomeMapRouteTopAppBar.kt` 系, `HomeMapWaypointSearchScreen.kt`

1. `RoutePreviewTopBarMode.Viewing / Editing(draftWaypoints)` を活用
2. waypoint タップ → `_overlayState = WaypointSearch`
3. `editingWaypointIndex`, `waypointEditResult` 廃止
4. `HomeMapRouteTopAppBar.isEditing` ローカル state 廃止

### Phase 5: `HomeMapViewEvent` 廃止

1. `onViewEvent` → 個別メソッドコールバックに置き換え
2. `HomeMapViewEvent.kt` 削除

### Phase 6: クリーンアップ

1. raw state の UI 公開を private に閉じる
2. 不要 import 整理
3. detekt + ビルド確認

---

## 8. リスク・注意事項

### 8.1 combine の中間状態

複数 raw state を順に更新すると中間状態が一瞬届く可能性がある。
`reduceScreenState` の優先順位が正しければ中間状態は「正しい方向」に倒れる。

**重要**: ルート再検索時は **旧 routeResults を先にクリア** する。

```kotlin
_routeResults.value = persistentListOf()  // ← 必ず先にクリア
_isRouteSearching.value = true
searchRoutesFromWaypoints(...)
```

クリアしないと旧 routeResults + 新 waypoints で不正な RoutePreview が一瞬表示される。

### 8.2 カメラ復元

`Channel` は replay しないため Activity 再生成時にカメラ Effect は失われる。
`LaunchedEffect(Unit)` の先頭で `screenState.value` から初期カメラ位置を復元する（セクション 4.8）。

### 8.3 NavigationState との関係

`guidanceSessionManager.navigationState` は `combine` の入力。
NavigationState が変わると自動的に screenState が再計算される。
手動 `_screenState` 更新は不要（combine 導出方式の利点）。

### 8.4 ルート情報の ownership

ナビ中のルート情報は `RouteManager.routes`（RoutesObserver 経由）が source of truth。
`Navigating` は route を保持しない。reroute でルートが変わっても screenState は stale にならない。

### 8.5 段階的移行

Phase 1〜3 は独立。各 Phase でビルド確認してコミットする。
旧 `onViewEvent` は Phase 5 まで残存させる。

---

## 9. 変更ファイル一覧

ベースパス: `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/`

| ファイル | 変更種別 | Phase |
|---------|---------|-------|
| `map/state/HomeMapScreenState.kt` | 新規 | 1 |
| `map/state/HomeMapOverlayState.kt` | 新規 | 1 |
| `map/state/HomeMapEffect.kt` | 新規 | 1 |
| `map/state/RoutePreviewTopBarMode.kt` | 新規 | 1 |
| `map/state/ReduceScreenState.kt` | 新規 | 1 |
| `map/HomeMapViewModel.kt` | 大規模 | 1, 4, 5, 6 |
| `map/HomeMapScreenContent.kt` | 大規模 | 2 |
| `map/HomeMapSheetContent.kt` | 中規模 | 2 |
| `map/HomeMapsMapEffectContent.kt` | 中規模 | 3 |
| `map/HomeMapViewEvent.kt` | 廃止 | 5 |
| `map/components/topappbar/HomeMapTopAppBar.kt` | 引数変更 | 5 |
| `map/components/topappbar/HomeMapRouteTopAppBar.kt` | 大規模 | 4, 5 |
| `map/components/topappbar/HomeMapRouteTopAppBarConfirmed.kt` | 引数変更 | 4 |
| `map/components/topappbar/HomeMapRouteTopAppBarEditing.kt` | 引数変更 | 4 |
| `map/components/topappbar/HomeMapWaypointSearchScreen.kt` | 引数変更 | 4, 5 |
| `map/components/bottomsheet/HomeMapRouteResultSheet.kt` | 引数変更 | 2 |
| `map/components/bottomsheet/HomeMapSearchResultSheet.kt` | 引数変更 | 2 |
| `map/components/bottomsheet/HomeMapSelectedResultSheet.kt` | 引数変更 | 2 |
| `map/components/navi/HomeMapNaviContent.kt` | 中規模（ViewModel 直参照を解消） | 2 |
| `map/HomeMapScreen.kt` (androidMain actual) | 中規模（`onNavigatingChanged` を screenState から導出） | 2 |

---

## 10. レビュー対応記録

### Codex 第 1 回レビュー

| # | 指摘 | 対応 |
|---|------|------|
| 1 | `PlaceSearch(selected?)` は再分岐するだけ | `SearchResultsList` / `PlaceDetails` に分離 |
| 2 | `LaunchedEffect(screenState)` は overlay 変更でも再発火 | `Channel<HomeMapEffect>` one-shot に変更 |
| 3 | `Navigating`/`Arrived` の `activeRoute` が stale | route 保持せず `RouteManager.routes` が SoT |
| 4 | `Arrived(RouteWaypoint.Place)` が swap で落ちる | `RouteWaypoint` に拡大 |
| 5 | Sheet swipe hide 未定義 | Sheet は swipe hide 不可に仕様確定 |

### Codex 第 2 回レビュー

| # | 指摘 | 対応 |
|---|------|------|
| 1 | adapter 方式と言いつつ手動二重管理 | `combine().stateIn()` 真の導出方式に変更 |
| 2 | Channel-only で復元不能 | Sheet は screenState 派生。カメラは初期復元ロジック追加 |
| 3 | `Navigating.selectedRouteIndex` が stale | `Navigating` を `data object` に。停止時ルート再検索 |

### 仕様深掘り（Dig）

| 決定事項 | 内容 |
|---------|------|
| SoT 方式 | combine 導出。reduceScreenState() pure function |
| Back 遷移 | 固定の戻り先。スタック不要。Navigating 確認ダイアログ不要 |
| Arrived UI | sealed interface に定義するが UI は今作らない |
| ナビ停止 | waypoints 保持してルート再検索 |
| Sheet gesture | ユーザーは swipe で閉じられない |
| Loading | 各 state に isLoading。reduce に flag を入力 |
| Error | スコープ外 |
