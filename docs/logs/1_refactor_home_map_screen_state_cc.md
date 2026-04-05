# HomeMapScreen 状態管理リファクタリング計画

**作成日**: 2026-04-05  
**対象**: `feature/home` モジュール — HomeMap 関連ファイル群  
**ステータス**: 計画中

---

## 1. 背景・目的

`HomeMapScreen` は複数の画面状態を持つが、現状はそれらを**複数のフラグの組み合わせ**で表現している。
フラグ間の依存関係が暗黙的で、カメラ移動の競合・BottomSheet 開閉ロジックの複雑化・デバッグ困難といった問題が既に顕在化している。

本ドキュメントでは現状の問題点を網羅的に整理し、`sealed interface HomeMapScreenState` を軸とした状態機械への移行計画を記述する。

---

## 2. 現状の問題点

### 2.1 状態がフラグの組み合わせで表現されている

現在、画面状態は以下のフラグ群の AND/OR で決まる:

```
// ViewModel から流れてくるデータ
searchResults: ImmutableList<SearchResultItem>  // 空 or 非空
selectedResult: SearchResultItem?               // null or non-null
routeResults:   ImmutableList<RouteResult>      // 空 or 非空
waypoints:      ImmutableList<RouteWaypoint>    // 空 or 非空
editingWaypointIndex: Int?                      // null or non-null
navigationState: NavigationState                // Browsing / RoutePreview / ActiveGuidance / Arrival

// Composable ローカル変数 (HomeMapScreenContent)
isNavigating = navigationState is ActiveGuidance
isArrived    = navigationState is Arrival
allowSheetHide: Boolean  // BottomSheet 強制非表示フラグ
```

これだけで 8+ 個のフラグが存在し、理論上は 256 通りの組み合わせがある。
実際に有効な状態は 6〜7 通りだが、**無効な組み合わせをコードで防ぐ仕組みがない**。

例えば `routeResults.isNotEmpty() && searchResults.isNotEmpty()` という状態は本来ありえないが、
`onSearch()` 実行後に `onRouteSearch()` が非同期で完了したとき、一瞬その状態になりうる。

---

### 2.2 カメラ移動の LaunchedEffect が競合している

`HomeMapScreenContent` には 4 つのカメラ制御 LaunchedEffect が存在する:

| # | キー | 処理 |
|---|------|------|
| 1 | `LaunchedEffect(searchResults, mapView)` | 検索結果一覧表示 → 全結果が収まるよう `flyTo` |
| 2 | `LaunchedEffect(selectedResult)` | 単一地点選択 → 地点へ `easeTo` |
| 3 | `LaunchedEffect(isNavigating)` | ナビ開始/終了 → シートの開閉 + location provider 切り替え |
| 4 | `LaunchedEffect(routeResults)` | ルート表示 → `cameraManager.requestCameraOverview()` |

問題は「複数のキーが同時に変化するケース」で競合が起きることだ。

**具体例: ルート検索完了時**

```
searchRoutes() 完了
  → _routeResults.value = featureResults  // LaunchedEffect(routeResults) が発火
  → _selectedResult.value はそのまま非null   // LaunchedEffect(selectedResult) も残存
```

`LaunchedEffect(selectedResult)` が以前の `selectedResult` に反応して `easeTo` を発行しつつ、
`LaunchedEffect(routeResults)` が `requestCameraOverview()` を発行する。  
どちらが後勝ちするかは Coroutine のスケジューリング次第で非決定的。

**具体例: ナビ終了時**

`isNavigating = false` になると:
1. `LaunchedEffect(isNavigating)` が発火 → `partialExpand()` で Sheet を再表示
2. 同じタイミングで `routeResults` は既に非空なので `LaunchedEffect(routeResults)` が残存
3. `cameraManager.requestCameraOverview()` と `location.enabled = true` が競合

---

### 2.3 BottomSheet の開閉制御が脆弱

`allowSheetHide` は `confirmValueChange` をバイパスするためのフラグで、以下のパターンで使われている:

```kotlin
allowSheetHide = true
scaffoldState.bottomSheetState.hide()
allowSheetHide = false
```

この実装には 2 つのリスクがある:

1. `hide()` が suspend 関数のため、並行する Coroutine と競合すると `allowSheetHide = false` が先に実行される可能性がある（現状は `LaunchedEffect` 内なので直線的だが、構造が変わると壊れる）
2. Sheet の表示/非表示判断がコードの複数箇所に散らばっており、どの LaunchedEffect が現在 Sheet を制御しているかの把握が困難

---

### 2.4 ViewModel が独立した StateFlow を 10 個公開している

```kotlin
val suggestions:           StateFlow<ImmutableList<SearchSuggestionItem>>
val searchResults:         StateFlow<ImmutableList<SearchResultItem>>
val selectedResult:        StateFlow<SearchResultItem?>
val histories:             StateFlow<ImmutableList<SearchHistory>>
val routeResults:          StateFlow<ImmutableList<RouteResult>>
val selectedRouteIndex:    StateFlow<Int>
val waypoints:             StateFlow<ImmutableList<RouteWaypoint>>
val editingWaypointIndex:  StateFlow<Int?>
val waypointEditResult:    StateFlow<Pair<Int, RouteWaypoint.Place>?>
val navigationState:       StateFlow<NavigationState>
```

これらの整合性保証はコードに明示されていない。
例として `routeResults` と `waypoints` は必ず同時に更新されるべきだが、
`_routeResults.value = ...` と `_waypoints.value = ...` は別行で更新されるため、
Compose の recomposition が間に挟まると、`routeResults.isNotEmpty() && waypoints.isEmpty()` という中間状態が UI に届く。

---

### 2.5 マーカー表示の優先順位チェーンが脆弱

`HomeMapsMapEffectContent` 内のマーカー表示は優先順位ベースの if-else チェーン:

```kotlin
if (isNavigating) {
    // マーカーなし
} else if (searchResults.isNotEmpty()) {
    // 番号付きピン
} else if (waypoints.isNotEmpty()) {
    // 目的地ピン
} else {
    selectedResult?.let { /* 赤ピン */ }
}
```

`waypoints.isNotEmpty() && selectedResult != null` という状態は「ルートプレビュー」として有効だが、
`searchResults.isNotEmpty() && selectedResult != null` は「どの状態なのか」が一見不明瞭。
状態の意味がコードから読めず、新しい画面状態を追加するときに正しい優先順位を把握しにくい。

---

### 2.6 `HomeMapViewEvent` が肥大化している

15 種類のイベントが 1 つの sealed interface に詰め込まれており、どのイベントがどの画面状態で有効かが明示されていない。  
例えば `OnDismissRoutes` は `RoutePreview` 状態でしか意味を持たないが、`Default` 状態でも呼び出し可能。

---

## 3. 改善案

### 3.1 `HomeMapScreenState` sealed interface の導入

画面状態を排他的な sealed interface で表現する。

```kotlin
/**
 * HomeMapScreen の画面状態。
 * 各状態は排他的で、同時に複数の状態になることはない。
 */
sealed interface HomeMapScreenState {

    /** 通常状態。地図表示・TopAppBar での地点検索が可能 */
    data object Default : HomeMapScreenState

    /**
     * 地点検索結果状態。
     * - [selected] が null のとき: 検索結果リストを BottomSheet に表示、全結果にカメラフィット
     * - [selected] が non-null のとき: 単一地点の詳細を BottomSheet に表示、地点にカメラ移動
     */
    @Immutable
    data class PlaceSearch(
        val query: String,
        val results: ImmutableList<SearchResultItem>,
        val selected: SearchResultItem?,
    ) : HomeMapScreenState

    /**
     * ルートプレビュー状態。
     * ルートライン・時間 CallOut を地図に表示し、BottomSheet にルート概要を表示する。
     * [editingWaypointIndex] が non-null のとき、経由地編集オーバーレイを追加表示する。
     */
    @Immutable
    data class RoutePreview(
        val waypoints: ImmutableList<RouteWaypoint>,
        val routes: ImmutableList<RouteResult>,
        val selectedRouteIndex: Int,
        val editingWaypointIndex: Int?,
    ) : HomeMapScreenState

    /** ナビゲーション中状態 */
    @Immutable
    data class Navigating(
        val navigationState: NavigationState.ActiveGuidance,
        val activeRoute: RouteResult,
    ) : HomeMapScreenState

    /** 到着状態 */
    @Immutable
    data class Arrived(
        val destination: RouteWaypoint.Place,
        val activeRoute: RouteResult,
    ) : HomeMapScreenState
}
```

**設計上の判断点**:

- `editingWaypointIndex` は `RoutePreview` のサブフィールドとする。  
  経由地編集は「ルートプレビュー画面の上に被さるオーバーレイ」であり、独立した画面状態ではない。
- `PlaceSearch.selected` も同様に `PlaceSearch` のフィールドとする。  
  「検索結果リスト」と「単一選択」はどちらも PlaceSearch フェーズの UI バリエーションに過ぎない。
- `Navigating` と `Arrived` は `NavigationState` の値と 1 対 1 で対応するため分離する。  
  `isNavigating`/`isArrived` フラグを廃止できる。

---

### 3.2 状態遷移図

```
         ┌─────────────────────────────────────────────┐
         │                                             │
         ▼                                             │
      Default ──[地点検索]──► PlaceSearch(selected=null)
         ▲                         │
         │                   [検索結果選択 / 地点タップ]
         │                         ▼
         │               PlaceSearch(selected=Item)
         │                         │
         │                   [ルート検索]
         │                         ▼
         │                   RoutePreview ──────────────►  Navigating
         │                   (editingWaypointIndex=null)       │
         │                         ▲                          │
         │                  [ナビ停止]◄────────────────────────┘
         │                         │
         │          [経由地タップ]  │ [ルート検索完了]
         │          ┌──────────────┘
         │          ▼
         │   RoutePreview
         │   (editingWaypointIndex=n)
         │          │
         │   [編集完了 / キャンセル]
         │          │
         │          ▼
         │   RoutePreview(editingWaypointIndex=null)
         │
         │                   Navigating ──[到着]──► Arrived
         │                                              │
         └──────────────────────────────────────────────┘
                                                  [閉じる]
```

---

### 3.3 ViewModel の整理

`_screenState: MutableStateFlow<HomeMapScreenState>` を 1 つ持ち、全状態をここに集約する。
個別の StateFlow は内部実装の都合で残してもよいが、**Composable に公開するのは `screenState` のみとする**。

```kotlin
class HomeMapViewModel(...) : ViewModel() {

    // --- 非公開: 内部状態 ---
    private val _suggestions = MutableStateFlow<ImmutableList<SearchSuggestionItem>>(persistentListOf())
    private val _userLatitude  = MutableStateFlow<Double?>(null)
    private val _userLongitude = MutableStateFlow<Double?>(null)

    // --- 公開: 画面状態の単一 source of truth ---
    private val _screenState = MutableStateFlow<HomeMapScreenState>(HomeMapScreenState.Default)
    val screenState: StateFlow<HomeMapScreenState> = _screenState.asStateFlow()

    // --- 公開: 状態に依存しない補助データ ---
    val suggestions: StateFlow<ImmutableList<SearchSuggestionItem>> = _suggestions.asStateFlow()
    val histories:   StateFlow<ImmutableList<SearchHistory>>        = ...
    val navigationState: StateFlow<NavigationState>                  = guidanceSessionManager.navigationState

    // --- 状態遷移メソッド (旧 onViewEvent の代替) ---
    fun onPlaceSearched(results: ImmutableList<SearchResultItem>, query: String) { ... }
    fun onPlaceSelected(result: SearchResultItem) { ... }
    fun onRouteSearchRequested() { ... }
    fun onRouteSelected(index: Int) { ... }
    fun onNavigationStarted() { ... }
    fun onNavigationStopped() { ... }
    fun onWaypointEditStarted(index: Int) { ... }
    fun onWaypointEditCompleted(index: Int, waypoint: RouteWaypoint.Place) { ... }
    fun onWaypointEditCancelled() { ... }
    fun onWaypointsConfirmed(waypoints: ImmutableList<RouteWaypoint>) { ... }
    fun onDismissed() { ... }  // 現在の状態から1つ前に戻す
}
```

**`onViewEvent(HomeMapViewEvent)` については**、
段階的移行としてそのまま残し、内部で状態遷移メソッドに委譲するラッパーとして機能させることも可能。
最終的には廃止して個別メソッドに置き換える。

---

### 3.4 カメラ移動の一元化

最重要改善点。`HomeMapScreenContent` の LaunchedEffect を **1 つに統合**する。

```kotlin
// 旧: 4 つの LaunchedEffect が競合
LaunchedEffect(searchResults, mapView) { ... }
LaunchedEffect(selectedResult) { ... }
LaunchedEffect(isNavigating) { ... }
LaunchedEffect(routeResults) { ... }

// 新: 状態遷移に 1 回だけ反応する LaunchedEffect
LaunchedEffect(screenState) {
    when (val state = screenState) {
        is HomeMapScreenState.Default -> {
            cameraManager.restoreTracking()
        }
        is HomeMapScreenState.PlaceSearch -> {
            val selected = state.selected
            if (selected != null) {
                viewportState.easeTo(
                    cameraOptions = CameraOptions.Builder()
                        .center(fromLngLat(selected.longitude, selected.latitude))
                        .zoom(FOLLOW_PUCK_ZOOM)
                        .pitch(0.0).bearing(0.0)
                        .build(),
                    animationOptions = MapAnimationOptions.Builder().duration(1500).build(),
                )
            } else if (state.results.isNotEmpty()) {
                val points = state.results.map { fromLngLat(it.longitude, it.latitude) }
                val cameraOptions = mapView?.mapboxMap?.cameraForCoordinates(points, ...)
                cameraOptions?.let { viewportState.flyTo(it, ...) }
            }
        }
        is HomeMapScreenState.RoutePreview -> {
            viewModel.routeManager.routes.first { it.isNotEmpty() }
            cameraManager.applyNavigationPadding(...)
            cameraManager.requestCameraOverview()
        }
        is HomeMapScreenState.Navigating -> {
            // NavSDK のカメラに委譲。何もしない。
        }
        is HomeMapScreenState.Arrived -> {
            cameraManager.restoreTracking()
        }
    }
}
```

状態が変わった瞬間に 1 回だけ動く。
前の状態の LaunchedEffect が残存して競合することがなくなる。

---

### 3.5 UI コンポーネントの分岐整理

`HomeMapScreenContent` の UI 表示分岐が `when(screenState)` 1 か所に集約される:

```kotlin
// TopAppBar
when (screenState) {
    is HomeMapScreenState.Default,
    is HomeMapScreenState.PlaceSearch -> HomeMapTopAppBar(...)
    is HomeMapScreenState.RoutePreview -> HomeMapRouteTopAppBar(...)
    is HomeMapScreenState.Navigating,
    is HomeMapScreenState.Arrived -> { /* なし */ }
}

// BottomSheet の表示/非表示
val shouldShowSheet = screenState is HomeMapScreenState.PlaceSearch ||
                      screenState is HomeMapScreenState.RoutePreview

// BottomSheet の内容
when (screenState) {
    is HomeMapScreenState.PlaceSearch -> {
        if (screenState.selected != null) HomeMapSelectedResultSheet(...)
        else HomeMapSearchResultSheet(...)
    }
    is HomeMapScreenState.RoutePreview -> HomeMapRouteResultSheet(...)
    else -> { /* 表示なし */ }
}

// 操作 UI
when (screenState) {
    is HomeMapScreenState.Navigating -> HomeMapNaviContent(...)
    else -> HomeMapControls(...)
}

// 経由地編集オーバーレイ
if (screenState is HomeMapScreenState.RoutePreview &&
    screenState.editingWaypointIndex != null) {
    HomeMapWaypointSearchScreen(...)
}
```

---

### 3.6 マーカー表示の整理

`HomeMapsMapEffectContent` のマーカー分岐も `when(screenState)` で明確化:

```kotlin
when (val state = screenState) {
    is HomeMapScreenState.Default -> { /* マーカーなし */ }
    is HomeMapScreenState.PlaceSearch -> {
        if (state.selected != null) {
            Marker(point = Point.fromLngLat(state.selected.longitude, state.selected.latitude), ...)
        } else {
            state.results.forEachIndexed { index, result ->
                HomeMapNumberedPin(point = ..., number = index + 1)
            }
        }
    }
    is HomeMapScreenState.RoutePreview -> {
        // 目的地ピン
        state.waypoints.lastOrNull()?.let { Marker(...) }
        // 経由地アイコン
        if (state.waypoints.size > 2) {
            state.waypoints.drop(1).dropLast(1).forEachIndexed { index, waypoint ->
                HomeMapWaypointPin(point = ..., label = "K${index + 1}")
            }
        }
        // ルート吹き出し
        state.routes.forEachIndexed { index, result -> HomeMapRouteCallout(...) }
    }
    is HomeMapScreenState.Navigating,
    is HomeMapScreenState.Arrived -> { /* マーカーなし */ }
}
```

優先順位の if-else チェーンがなくなり、各状態で何を表示するかが明確になる。

---

### 3.7 `allowSheetHide` の廃止

`allowSheetHide` は `screenState` から派生した `shouldShowSheet: Boolean` で置き換える。
`BottomSheetScaffold` の `confirmValueChange` は削除し、代わりに `LaunchedEffect(screenState)` の中で
Sheet の表示・非表示を制御する:

```kotlin
LaunchedEffect(screenState) {
    when (screenState) {
        is HomeMapScreenState.PlaceSearch,
        is HomeMapScreenState.RoutePreview -> scaffoldState.bottomSheetState.partialExpand()
        else -> scaffoldState.bottomSheetState.hide()
    }
}
```

ユーザーがスワイプで Sheet を閉じた場合の対応は、`confirmValueChange` で `Hidden` を許可し、
そのイベントを ViewModel に通知して状態を更新する方向で解決できる（ただし UX 設計として要検討）。

---

## 4. 実装計画

### Phase 1: `HomeMapScreenState` の定義と ViewModel 移行

**対象ファイル**:
- 新規: `feature/home/src/androidMain/.../map/HomeMapScreenState.kt`
- 変更: `HomeMapViewModel.kt`

**手順**:
1. `HomeMapScreenState.kt` に sealed interface を定義（コンパイルのみ確認）
2. `HomeMapViewModel` に `_screenState: MutableStateFlow<HomeMapScreenState>` を追加
3. 既存の StateFlow を内部に残しつつ、状態遷移時に `_screenState` も更新するよう修正
4. 新しい状態遷移メソッドを追加（旧 `onViewEvent` と共存させる）
5. ビルドが通ることを確認

**完了基準**: ViewModel が `screenState` を公開し、既存の動作を破壊しないこと

---

### Phase 2: `HomeMapScreenContent` のカメラ制御統合

**対象ファイル**:
- 変更: `HomeMapScreenContent.kt`

**手順**:
1. `screenState` の collect を追加
2. 既存 4 つの `LaunchedEffect` を `LaunchedEffect(screenState)` に置き換え
3. `allowSheetHide` を廃止し、Sheet 制御を `LaunchedEffect(screenState)` に集約
4. UI 分岐 (`if (isNavigating)` 等) を `when(screenState)` に書き換え
5. 動作確認

**完了基準**: カメラ競合バグが再現しないこと、全画面状態で正常に動作すること

---

### Phase 3: `HomeMapsMapEffectContent` のマーカー表示整理

**対象ファイル**:
- 変更: `HomeMapsMapEffectContent.kt`

**手順**:
1. `screenState` を引数に追加（`isNavigating`, `searchResults`, `selectedResult`, `routeResults`, `waypoints` を削除）
2. マーカー表示の if-else チェーンを `when(screenState)` に書き換え
3. ルートライン描画の条件も `screenState` ベースに変更
4. 動作確認

**完了基準**: マーカー表示が各画面状態で正確に切り替わること

---

### Phase 4: `HomeMapSheetContent` の整理

**対象ファイル**:
- 変更: `HomeMapSheetContent.kt`

**手順**:
1. 引数を `screenState: HomeMapScreenState` に変更（`searchResults`, `selectedResult`, `routeResults` を削除）
2. `when(screenState)` で Sheet コンテンツを分岐
3. 動作確認

**完了基準**: 各画面状態で正しい Sheet コンテンツが表示されること

---

### Phase 5: `HomeMapViewEvent` の整理と旧 API 廃止

**対象ファイル**:
- 変更: `HomeMapViewEvent.kt`, `HomeMapViewModel.kt`
- 変更: `HomeMapTopAppBar.kt`, `HomeMapRouteTopAppBar.kt`, `HomeMapWaypointSearchScreen.kt` 等

**手順**:
1. `HomeMapViewEvent` を廃止し、ViewModel の個別メソッドを直接 Composable から呼び出すよう変更
2. 各 Composable の `onViewEvent: (HomeMapViewEvent) -> Unit` 引数を型安全な個別コールバックに置き換え
3. `HomeMapViewModel.onViewEvent()` を削除
4. 動作確認

**完了基準**: `HomeMapViewEvent` が削除され、コールバック引数が型安全になること

---

### Phase 6: クリーンアップ

**手順**:
1. 廃止した StateFlow の公開 API 削除（`selectedResult`, `routeResults`, `waypoints`, `editingWaypointIndex` 等）
2. `isNavigating`, `isArrived` ローカル変数の削除
3. `HomeMapScreenContent` の不要 import 整理
4. detekt 実行・確認
5. ビルド確認

---

## 5. リスク・注意事項

### 5.1 状態遷移の完全性

`_screenState` への更新が複数箇所でアトミックに行われることを保証する必要がある。
`viewModelScope.launch` 内の更新では、Coroutine の中断点をまたぐ更新に注意する。

```kotlin
// NG: 中断点をまたいで 2 回更新するため中間状態が存在する
_screenState.value = currentState.copy(routes = emptyList())   // 中間状態
delay(100)
_screenState.value = HomeMapScreenState.Default

// OK: 1 回の代入でアトミックに遷移
_screenState.value = HomeMapScreenState.Default
```

### 5.2 NavigationState との二重管理

`guidanceSessionManager.navigationState` と `HomeMapScreenState` は別管理になる。
`NavigationState.ActiveGuidance` を受信したタイミングで `_screenState.value = Navigating(...)` に遷移する処理が必要。
これは ViewModel の `init` ブロックで `navigationState.onEach { ... }.launchIn(viewModelScope)` として実装する。

```kotlin
init {
    guidanceSessionManager.navigationState
        .onEach { navState ->
            when (navState) {
                is NavigationState.ActiveGuidance -> {
                    val current = _screenState.value
                    if (current is HomeMapScreenState.RoutePreview) {
                        _screenState.value = HomeMapScreenState.Navigating(
                            navigationState = navState,
                            activeRoute = current.routes[current.selectedRouteIndex],
                        )
                    }
                }
                is NavigationState.Arrival -> {
                    val current = _screenState.value
                    if (current is HomeMapScreenState.Navigating) {
                        val dest = current.activeRoute.item.waypoints.lastOrNull()
                            as? RouteWaypoint.Place ?: return@onEach
                        _screenState.value = HomeMapScreenState.Arrived(
                            destination = dest,
                            activeRoute = current.activeRoute,
                        )
                    }
                }
                else -> { /* RoutePreview / Browsing はユーザー操作で遷移するため何もしない */ }
            }
        }
        .launchIn(viewModelScope)
}
```

### 5.3 カメラ競合の根絶

`LaunchedEffect(screenState)` に統合した後も、`cameraManager.requestCameraOverview()` のような
SDK 側のカメラ制御と競合する可能性がある。  
`RoutePreview` 状態へ遷移したときは `routeManager.routes.first { it.isNotEmpty() }` で
ルートデータが SDK に反映されるのを待ってからカメラ移動することで解決する（現行実装と同様）。

### 5.4 段階的移行

Phase 1〜3 は独立した変更として進められる。各 Phase でビルドが通ることを確認してからコミットする。
旧 API（`onViewEvent`）は Phase 5 まで残存させ、コンパイルエラーが発生しないよう並行期間を設ける。

---

## 6. 変更ファイル一覧

| ファイル | 変更種別 | Phase |
|---------|---------|-------|
| `map/HomeMapScreenState.kt` | 新規作成 | 1 |
| `map/HomeMapViewModel.kt` | 大規模変更 | 1, 5, 6 |
| `map/HomeMapScreenContent.kt` | 大規模変更 | 2 |
| `map/HomeMapsMapEffectContent.kt` | 中規模変更 | 3 |
| `map/HomeMapSheetContent.kt` | 中規模変更 | 4 |
| `map/HomeMapViewEvent.kt` | 廃止 | 5 |
| `map/components/topappbar/HomeMapTopAppBar.kt` | 引数変更 | 5 |
| `map/components/topappbar/HomeMapRouteTopAppBar.kt` | 引数変更 | 5 |
| `map/components/topappbar/HomeMapWaypointSearchScreen.kt` | 引数変更 | 5 |
| `map/components/bottomsheet/HomeMapRouteResultSheet.kt` | 引数変更 | 4 |
| `map/components/bottomsheet/HomeMapSearchResultSheet.kt` | 引数変更 | 4 |
| `map/components/bottomsheet/HomeMapSelectedResultSheet.kt` | 引数変更 | 4 |
