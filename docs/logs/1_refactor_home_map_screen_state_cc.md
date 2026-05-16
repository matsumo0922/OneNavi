# HomeMapScreen 状態管理リファクタリング計画

**作成日**: 2026-04-05  
**最終更新**: 2026-04-05 (Codex レビュー反映 + 仕様深掘り完了)  
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

## 3. 仕様決定事項

本リファクタリングの実装に先立ち、以下の UX / 設計仕様を確定した。

### 3.1 BottomSheet のユーザー操作

- **BottomSheet はユーザーが swipe で閉じる（Hidden にする）ことはできない**
- Sheet の表示/非表示は screenState から完全に導出される
- `allowSheetHide` フラグは廃止。`confirmValueChange` で `SheetValue.Hidden` を拒否する

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

| 現在の state | Back の遷移先 | 補足 |
|---|---|---|
| `SearchResultsList` | `Browsing` | 検索結果をクリア |
| `PlaceDetails` | `Browsing` | 選択地点をクリア |
| `RoutePreview` | `PlaceDetails` | 目的地情報（`selectedResult`）は維持 |
| `Navigating` | `RoutePreview` | **確認ダイアログなし**。ルート再検索で復帰 |
| `Arrived` | `Browsing` | 全リセット |
| Overlay 表示中 | Overlay を閉じるだけ | screenState は変えない |

### 3.3 Arrived（到着）画面

- sealed interface に `Arrived` 状態を**定義する**
- **UI は今回実装しない**。到着時はナビ停止と同じ扱いで RoutePreview に戻す
- 将来の Phase で到着サマリー UI を追加可能な設計にしておく

### 3.4 ナビ停止時のルート情報

- ナビ停止時は **waypoints を保持したままルートを再検索する**
- reroute で変わった可能性があるため、旧 routeResults / selectedRouteIndex は使わない
- `Navigating` state には `selectedRouteIndex` を**持たせない**（stale になるため）

### 3.5 Loading 表示

- 各 screenState に `isLoading: Boolean` フィールドを持たせる
- BottomSheet 内で loading を表現する（ボタンの loading 状態、progress indicator 等）
- reduce 関数に `_isRouteSearching` 等の loading flag を入力として渡す

### 3.6 エラー表示

- 今回のリファクタリングのスコープ外。現状のログ出力のみの挙動を維持する

---

## 4. 改善案

### 4.1 3 層アーキテクチャ: combine 導出 + Overlay State + Effect Stream

状態管理を以下の 3 層に分離する。

1. **Screen State** — 今どの画面なのか + その画面に必要なデータ
2. **Overlay State** — 画面の上に一時的に重なる UI（経由地検索オーバーレイ等）
3. **Effect Stream** — one-shot の副作用（カメラ移動、Sheet 操作、画面点灯等）

Screen State を `StateFlow` で、Overlay State を `StateFlow` で、
Effect を `Channel<HomeMapEffect>` で管理する。

### 3.2 `HomeMapScreenState` sealed interface の導入

画面状態を排他的な sealed interface で表現する。

```kotlin
/**
 * HomeMapScreen の画面状態。
 * 各状態は排他的で、同時に複数の状態になることはない。
 */
sealed interface HomeMapScreenState {

    /** 通常状態。地図表示・TopAppBar での地点検索が可能 */
    data object Browsing : HomeMapScreenState

    /**
     * 検索結果一覧状態。
     * 複数地点に番号ピンを表示し、BottomSheet に検索結果リストを表示する。
     * カメラは全結果が収まるようフィットする。
     */
    @Immutable
    data class SearchResultsList(
        val query: String,
        val results: ImmutableList<SearchResultItem>,
    ) : HomeMapScreenState

    /**
     * 地点詳細状態。
     * 単一地点にピンを表示し、BottomSheet に地点詳細を表示する。
     * カメラは選択地点へ移動する。
     *
     * 地図タップ・履歴選択・検索結果選択のいずれからも遷移可能。
     */
    @Immutable
    data class PlaceDetails(
        val place: SearchResultItem,
    ) : HomeMapScreenState

    /**
     * ルートプレビュー状態。
     * ルートライン・時間 CallOut を地図に表示し、BottomSheet にルート概要を表示する。
     * カメラはルート全体が収まるよう移動する。
     *
     * TopAppBar の表示モードは [topBarMode] で制御する。
     */
    @Immutable
    data class RoutePreview(
        val waypoints: ImmutableList<RouteWaypoint>,
        val routes: ImmutableList<RouteResult>,
        val selectedRouteIndex: Int,
        val topBarMode: RoutePreviewTopBarMode,
    ) : HomeMapScreenState

    /** ナビゲーション中状態。ルートの実体は RouteManager が保持する。 */
    @Immutable
    data class Navigating(
        val selectedRouteIndex: Int,
    ) : HomeMapScreenState

    /** 到着状態。目的地は RouteWaypoint.Place に限定しない（swap で CurrentLocation が目的地になりうる）。 */
    @Immutable
    data class Arrived(
        val destination: RouteWaypoint,
    ) : HomeMapScreenState
}

/**
 * ルートプレビュー画面の TopAppBar 表示モード。
 */
@Immutable
sealed interface RoutePreviewTopBarMode {
    /** 確定表示。経由地一覧を表示する。 */
    data object Viewing : RoutePreviewTopBarMode

    /** 編集中。draft waypoints を操作する。 */
    @Immutable
    data class Editing(
        val draftWaypoints: ImmutableList<RouteWaypoint?>,
    ) : RoutePreviewTopBarMode
}
```

**設計上の判断点**:

- `SearchResultsList` と `PlaceDetails` を分離する。  
  旧案の `PlaceSearch(selected: SearchResultItem?)` は `selected != null` フラグで再分岐するだけで、
  元の問題を 1 段内側に移しただけだった。両者はカメラ挙動・BottomSheet・マーカーが全て異なるため、別状態とする。
  `PlaceDetails` は `query` を持たないため、地図タップや履歴選択からの遷移とも整合する。
- `RoutePreview` に `RoutePreviewTopBarMode` をネストする。  
  旧 `HomeMapRouteTopAppBar.isEditing` をローカル state から feature state に引き上げる。
  `editingWaypointIndex` は Overlay State 側で管理する（後述）。
- `Navigating` / `Arrived` は route を保持しない。  
  ナビ中は reroute で `RouteManager.routes` が変わるため、screen state に preview 時点の `RouteResult` を
  固定すると stale になる。ルート情報が必要な場合は `RouteManager.routes` を直接 collect する。
- `Arrived.destination` は `RouteWaypoint` とする（`RouteWaypoint.Place` に限定しない）。  
  `onSwapOriginDestination()` により目的地が `CurrentLocation` になるケースがあるため。

### 3.3 `HomeMapOverlayState` の導入

画面の上に一時的に重なる UI を独立した state で管理する。
これにより `editingWaypointIndex` / `waypointEditResult` の不自然な往復パターンを廃止できる。

```kotlin
@Immutable
sealed interface HomeMapOverlayState {
    /** オーバーレイなし */
    data object None : HomeMapOverlayState

    /** 経由地検索オーバーレイ */
    @Immutable
    data class WaypointSearch(
        val index: Int,
        val initialQuery: String?,
    ) : HomeMapOverlayState
}
```

遷移フロー:
1. `RoutePreview` で waypoint をタップ → `overlay = WaypointSearch(index, initialQuery)`
2. overlay 上で suggestion/history を選択 → ViewModel が draft waypoints を直接更新
3. `overlay = None` に戻す

旧実装の `waypointEditResult` による一時退避 → TopAppBar LaunchedEffect 吸い上げが不要になる。

### 3.4 `HomeMapEffect` の導入

カメラ移動・BottomSheet 操作・画面点灯のような one-shot 副作用を `Channel` で管理する。

```kotlin
sealed interface HomeMapEffect {
    data class MoveCameraToSearchResults(
        val results: ImmutableList<SearchResultItem>,
    ) : HomeMapEffect

    data class MoveCameraToPlace(
        val place: SearchResultItem,
    ) : HomeMapEffect

    data class MoveCameraToRouteOverview(
        val selectedRouteIndex: Int,
    ) : HomeMapEffect

    data object EnterGuidanceFollowing : HomeMapEffect

    data object RestoreTracking : HomeMapEffect

    data object HideBottomSheet : HomeMapEffect

    data object ShowBottomSheetPartially : HomeMapEffect

    data class SetKeepScreenOn(
        val enabled: Boolean,
    ) : HomeMapEffect

    data class UseNavigationLocationProvider(
        val enabled: Boolean,
    ) : HomeMapEffect
}
```

**`LaunchedEffect(screenState)` を採用しない理由**:

旧案では `LaunchedEffect(screenState)` でカメラを制御する設計だったが、
`RoutePreview` の `topBarMode` や `selectedRouteIndex` が変わるだけでも `data class` の `equals` が変わり、
LaunchedEffect が再発火して `requestCameraOverview()` が毎回走る。
`Channel` なら状態遷移メソッド内で明示的に emit した場合のみ発火するため、意図しない再実行がない。

```kotlin
// ViewModel
private val _effects = Channel<HomeMapEffect>(Channel.BUFFERED)
val effects: Flow<HomeMapEffect> = _effects.receiveAsFlow()

// 状態遷移時に明示的に emit
private fun onPlaceSelected(result: SearchResultItem) {
    _screenState.value = HomeMapScreenState.PlaceDetails(place = result)
    _effects.trySend(HomeMapEffect.MoveCameraToPlace(result))
    _effects.trySend(HomeMapEffect.ShowBottomSheetPartially)
}

// Composable 側: 1 本の collector
LaunchedEffect(Unit) {
    viewModel.effects.collect { effect ->
        when (effect) {
            is HomeMapEffect.MoveCameraToSearchResults -> { ... }
            is HomeMapEffect.MoveCameraToPlace -> { ... }
            is HomeMapEffect.MoveCameraToRouteOverview -> { ... }
            is HomeMapEffect.EnterGuidanceFollowing -> { ... }
            is HomeMapEffect.RestoreTracking -> { ... }
            is HomeMapEffect.HideBottomSheet -> { ... }
            is HomeMapEffect.ShowBottomSheetPartially -> { ... }
            is HomeMapEffect.SetKeepScreenOn -> { ... }
            is HomeMapEffect.UseNavigationLocationProvider -> { ... }
        }
    }
}
```

旧 4 つの `LaunchedEffect` を全て削除でき、副作用の発火源が 1 箇所にまとまる。

---

### 3.5 状態遷移図

```
         ┌──────────────────────────────────────────────────────┐
         │                                                      │
         ▼                                                      │
      Browsing ──[テキスト検索]──► SearchResultsList             │
         │                              │                       │
         │                        [結果タップ]                   │
         │                              ▼                       │
         ├──[地図タップ / 履歴]──► PlaceDetails                  │
         │                              │                       │
         │                        [ルート検索]                   │
         │                              ▼                       │
         │                        RoutePreview ──[ナビ開始]──► Navigating
         │                        (Viewing)                      │
         │                              ▲                        │
         │                       [ナビ停止]◄─────────────────────┘
         │                              │
         │   [overlay: WaypointSearch]  │  [overlay: None]
         │              ┌───────────────┤
         │              ▼               │
         │   RoutePreview(Editing)      │
         │              │               │
         │   [waypoint 確定]            │
         │              └───────────────┘
         │
         │                        Navigating ──[到着]──► Arrived
         │                                                  │
         └──────────────────────────────────────────────────┘
                                                      [閉じる]
```

### 3.6 遷移テーブル

| イベント | 現在 state | 次 state | Effect |
|---|---|---|---|
| テキスト検索実行 | `Browsing` / `PlaceDetails` | `SearchResultsList` | `MoveCameraToSearchResults`, `ShowBottomSheetPartially` |
| 検索結果タップ | `SearchResultsList` | `PlaceDetails` | `MoveCameraToPlace`, `ShowBottomSheetPartially` |
| 地図タップ / POI タップ / 履歴選択 | `Browsing` / `PlaceDetails` | `PlaceDetails` | `MoveCameraToPlace`, `ShowBottomSheetPartially` |
| ルート検索成功 | `PlaceDetails` | `RoutePreview(Viewing)` | `MoveCameraToRouteOverview`, `ShowBottomSheetPartially` |
| ルート選択変更 | `RoutePreview` | `RoutePreview` (index 更新) | `MoveCameraToRouteOverview` |
| 編集開始 | `RoutePreview(Viewing)` | `RoutePreview(Editing)` | なし |
| waypoint タップ | `RoutePreview` | overlay → `WaypointSearch` | なし |
| waypoint 確定 | overlay → `None` | `RoutePreview(Editing)` | なし |
| waypoint 編集完了 | `RoutePreview(Editing)` | `RoutePreview(Viewing)` | ルート再検索成功後 `MoveCameraToRouteOverview` |
| ナビ開始 | `RoutePreview` | `Navigating` | `HideBottomSheet`, `EnterGuidanceFollowing`, `SetKeepScreenOn(true)`, `UseNavigationLocationProvider(true)` |
| ナビ停止 | `Navigating` | `RoutePreview` | `MoveCameraToRouteOverview`, `ShowBottomSheetPartially`, `SetKeepScreenOn(false)`, `UseNavigationLocationProvider(false)` |
| 到着 | `Navigating` | `Arrived` | 到着用 camera/sheet 制御 |
| Sheet swipe hide | `SearchResultsList` / `PlaceDetails` | `Browsing` | `HideBottomSheet` |
| Sheet swipe hide | `RoutePreview` | `RoutePreview` (状態維持) | なし（UX 要検討） |
| 戻る | `RoutePreview` | `PlaceDetails` or `Browsing` | `HideBottomSheet` |

---

### 3.7 ViewModel の整理

ViewModel は以下の 3 つの公開 API を持つ:

```kotlin
class HomeMapViewModel(...) : ViewModel() {

    // --- Screen State ---
    private val _screenState = MutableStateFlow<HomeMapScreenState>(HomeMapScreenState.Browsing)
    val screenState: StateFlow<HomeMapScreenState> = _screenState.asStateFlow()

    // --- Overlay State ---
    private val _overlayState = MutableStateFlow<HomeMapOverlayState>(HomeMapOverlayState.None)
    val overlayState: StateFlow<HomeMapOverlayState> = _overlayState.asStateFlow()

    // --- Effect Stream (one-shot) ---
    private val _effects = Channel<HomeMapEffect>(Channel.BUFFERED)
    val effects: Flow<HomeMapEffect> = _effects.receiveAsFlow()

    // --- 状態に依存しない補助データ ---
    val suggestions: StateFlow<ImmutableList<SearchSuggestionItem>> = ...
    val histories:   StateFlow<ImmutableList<SearchHistory>>        = ...

    // --- 非公開: 内部状態 ---
    private val _userLatitude  = MutableStateFlow<Double?>(null)
    private val _userLongitude = MutableStateFlow<Double?>(null)

    // --- 状態遷移メソッド ---
    fun onSearchExecuted(query: String, latitude: Double?, longitude: Double?) { ... }
    fun onSearchResultSelected(result: SearchResultItem) { ... }
    fun onPlaceSelected(result: SearchResultItem) { ... }
    fun onRouteSearchRequested() { ... }
    fun onRouteSelected(index: Int) { ... }
    fun onNavigationStarted() { ... }
    fun onNavigationStopped() { ... }
    fun onEditingStarted() { ... }
    fun onEditingCompleted(waypoints: ImmutableList<RouteWaypoint>) { ... }
    fun onWaypointEditStarted(index: Int) { ... }
    fun onWaypointSelected(index: Int, waypoint: RouteWaypoint.Place) { ... }
    fun onWaypointEditCancelled() { ... }
    fun onSheetDismissed() { ... }  // Sheet swipe hide → screenState を適切に戻す
    fun onDismissed() { ... }       // 戻る操作
}
```

**`onViewEvent(HomeMapViewEvent)` については**、
段階的移行としてそのまま残し、内部で状態遷移メソッドに委譲するラッパーとして機能させることも可能。
最終的には廃止して個別メソッドに置き換える。

---

### 3.8 カメラ移動の一元化

最重要改善点。`HomeMapScreenContent` の 4 つの LaunchedEffect を全て削除し、
**Effect Stream の 1 本の collector** に統合する。

旧案の `LaunchedEffect(screenState)` は、`RoutePreview` 内の `topBarMode` や `selectedRouteIndex` が
変わるだけでも `data class.equals` が変わり LaunchedEffect が再発火するため採用しない。
`Channel<HomeMapEffect>` なら ViewModel の状態遷移メソッド内で明示的に `trySend` した場合のみ発火する。

```kotlin
// Composable 側: 1 本の effect collector
LaunchedEffect(Unit) {
    viewModel.effects.collect { effect ->
        when (effect) {
            is HomeMapEffect.MoveCameraToSearchResults -> {
                val points = effect.results.map { fromLngLat(it.longitude, it.latitude) }
                val cameraOptions = mapView?.mapboxMap?.cameraForCoordinates(points, ...)
                cameraOptions?.let { viewportState.flyTo(it, ...) }
            }
            is HomeMapEffect.MoveCameraToPlace -> {
                viewportState.easeTo(
                    cameraOptions = CameraOptions.Builder()
                        .center(fromLngLat(effect.place.longitude, effect.place.latitude))
                        .zoom(FOLLOW_PUCK_ZOOM)
                        .pitch(0.0).bearing(0.0)
                        .build(),
                    animationOptions = MapAnimationOptions.Builder().duration(1500).build(),
                )
            }
            is HomeMapEffect.MoveCameraToRouteOverview -> {
                routeManager.routes.first { it.isNotEmpty() }
                cameraManager.applyNavigationPadding(...)
                cameraManager.requestCameraOverview()
            }
            is HomeMapEffect.EnterGuidanceFollowing -> {
                cameraManager.requestCameraFollowing(pitch3D = true)
            }
            is HomeMapEffect.RestoreTracking -> { ... }
            is HomeMapEffect.HideBottomSheet -> {
                scaffoldState.bottomSheetState.hide()
            }
            is HomeMapEffect.ShowBottomSheetPartially -> {
                scaffoldState.bottomSheetState.partialExpand()
            }
            is HomeMapEffect.SetKeepScreenOn -> { ... }
            is HomeMapEffect.UseNavigationLocationProvider -> { ... }
        }
    }
}
```

これで以下の旧 LaunchedEffect を全て削除できる:
- `LaunchedEffect(searchResults, mapView)` → `MoveCameraToSearchResults`
- `LaunchedEffect(selectedResult)` → `MoveCameraToPlace`
- `LaunchedEffect(isNavigating)` → `EnterGuidanceFollowing` + `HideBottomSheet` + etc.
- `LaunchedEffect(routeResults)` → `MoveCameraToRouteOverview`

---

### 3.9 UI コンポーネントの分岐整理

`HomeMapScreenContent` の UI 表示分岐が `when(screenState)` で明確化される:

```kotlin
// TopAppBar
when (screenState) {
    is HomeMapScreenState.Browsing,
    is HomeMapScreenState.SearchResultsList,
    is HomeMapScreenState.PlaceDetails -> HomeMapTopAppBar(...)
    is HomeMapScreenState.RoutePreview -> HomeMapRouteTopAppBar(
        topBarMode = screenState.topBarMode,
        ...
    )
    is HomeMapScreenState.Navigating,
    is HomeMapScreenState.Arrived -> { /* なし */ }
}

// BottomSheet の内容 (表示/非表示は Effect で制御)
when (screenState) {
    is HomeMapScreenState.SearchResultsList -> HomeMapSearchResultSheet(...)
    is HomeMapScreenState.PlaceDetails -> HomeMapSelectedResultSheet(...)
    is HomeMapScreenState.RoutePreview -> HomeMapRouteResultSheet(...)
    else -> { /* 表示なし */ }
}

// 操作 UI
when (screenState) {
    is HomeMapScreenState.Navigating -> HomeMapNaviContent(...)
    else -> HomeMapControls(...)
}

// 経由地編集オーバーレイ (Overlay State で制御)
val overlayState by viewModel.overlayState.collectAsStateWithLifecycle()
if (overlayState is HomeMapOverlayState.WaypointSearch) {
    HomeMapWaypointSearchScreen(...)
}
```

---

### 3.10 マーカー表示の整理

`HomeMapsMapEffectContent` のマーカー分岐も `when(screenState)` で明確化:

```kotlin
when (val state = screenState) {
    is HomeMapScreenState.Browsing -> { /* マーカーなし */ }
    is HomeMapScreenState.SearchResultsList -> {
        state.results.forEachIndexed { index, result ->
            HomeMapNumberedPin(point = ..., number = index + 1)
        }
    }
    is HomeMapScreenState.PlaceDetails -> {
        Marker(point = Point.fromLngLat(state.place.longitude, state.place.latitude), ...)
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
        state.routes.forEachIndexed { index, result -> RouteCallOut(...) }
    }
    is HomeMapScreenState.Navigating,
    is HomeMapScreenState.Arrived -> { /* マーカーなし */ }
}
```

旧実装の優先順位 if-else チェーンがなくなり、��状態で何を表示するかが `when` 分岐で明確になる。
`SearchResultsList` と `PlaceDetails` が別状態になったことで、内部の `selected != null` 分岐も不要になった。

---

### 3.11 `allowSheetHide` の廃止と Sheet 制御方針

`allowSheetHide` を廃止する。Sheet の表示・非表示は **Effect Stream** 経由で制御する。

```kotlin
// ViewModel の状態遷移メソッド内で明示的に emit
private fun onSearchResultSelected(result: SearchResultItem) {
    _screenState.value = HomeMapScreenState.PlaceDetails(place = result)
    _effects.trySend(HomeMapEffect.MoveCameraToPlace(result))
    _effects.trySend(HomeMapEffect.ShowBottomSheetPartially)
}

private fun onNavigationStarted() {
    _screenState.value = HomeMapScreenState.Navigating(selectedRouteIndex = ...)
    _effects.trySend(HomeMapEffect.HideBottomSheet)
    _effects.trySend(HomeMapEffect.EnterGuidanceFollowing)
    _effects.trySend(HomeMapEffect.SetKeepScreenOn(enabled = true))
    _effects.trySend(HomeMapEffect.UseNavigationLocationProvider(enabled = true))
}
```

**ユーザーが swipe で Sheet を閉じた場合**:

`confirmValueChange` で `SheetValue.Hidden` を検知し、ViewModel の `onSheetDismissed()` を呼び出す。
`onSheetDismissed()` の挙動は screenState に応じて変わる:

- `SearchResultsList` → `Browsing` に戻す
- `PlaceDetails` → `Browsing` に戻す
- `RoutePreview` → **UX 要検討**。ルートラインを残すべきかどうかで判断が分かれる。
  暫定: 状態は維持し Sheet だけ閉じる（`HideBottomSheet` effect は不要、swipe 自体で閉じているため）

この方針により `allowSheetHide` フラグも `LaunchedEffect(screenState)` での Sheet 制御も不要になる。

---

## 4. 実装計画

### Phase 1: 型の導入と adapter 方式での導出

**方針**: 既存の raw state は残したまま、`HomeMapScreenState` を導出する。
source of truth はまだ二重化するが、UI の分岐を先に集約して効果を得る。

**対象ファイル**:
- 新規: `feature/home/src/androidMain/.../map/state/HomeMapScreenState.kt`
- 新規: `feature/home/src/androidMain/.../map/state/HomeMapOverlayState.kt`
- 新規: `feature/home/src/androidMain/.../map/state/HomeMapEffect.kt`
- 新規: `feature/home/src/androidMain/.../map/state/RoutePreviewTopBarMode.kt`
- 変更: `HomeMapViewModel.kt`

**手順**:
1. `HomeMapScreenState`, `HomeMapOverlayState`, `HomeMapEffect`, `RoutePreviewTopBarMode` を定義
2. ViewModel に `_screenState`, `_overlayState`, `_effects` を追加
3. 既存 raw state を残しつつ、状態遷移メソッドで `_screenState` と `_effects` を同時更新
4. 旧 `onViewEvent` は内部で新メソッドに委譲するラッパーとして残す
5. ビルドが通ることを確認

**完了基準**: ViewModel が `screenState`, `overlayState`, `effects` を公開し、既存の動作を破壊しないこと

---

### Phase 2: Compose の分岐を `screenState` ベースに切り替え + Effect 導入

**対象ファイル**:
- 変更: `HomeMapScreenContent.kt`
- 変更: `HomeMapSheetContent.kt`

**手順**:
1. `screenState`, `overlayState` の collect を追加
2. 既存 4 つの `LaunchedEffect` を削除し、`LaunchedEffect(Unit) { effects.collect { ... } }` に置き換え
3. `allowSheetHide` を廃止。Sheet 表示/非表示は Effect handler 内で制御
4. UI 分岐 (`if (isNavigating)` 等) を `when(screenState)` に書き換え
5. Sheet コンテンツ分岐を `when(screenState)` に書き換え
6. Overlay 表示を `overlayState` ベースに変更
7. 動作確認

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

### Phase 4: RoutePreview 編集状態の reducer 化

**対象ファイル**:
- 変更: `HomeMapViewModel.kt`
- 変更: `HomeMapRouteTopAppBar.kt`, `HomeMapRouteTopAppBarConfirmed.kt`, `HomeMapRouteTopAppBarEditing.kt`
- 変更: `HomeMapWaypointSearchScreen.kt`

**手順**:
1. `RoutePreviewTopBarMode.Viewing / Editing(draftWaypoints)` を活用
2. waypoint タップ時は `overlayState = WaypointSearch(index, initialQuery)` に変更
3. suggestion/history 選択時に ViewModel が draft waypoints を直接更新
4. `editingWaypointIndex`, `waypointEditResult` を廃止
5. `HomeMapRouteTopAppBar.isEditing` ローカル state を廃止し、`topBarMode` から導出
6. 動作確認

**完了基準**: Route TopAppBar が pure UI に近づき、`waypointEditResult` の往復パターンがなくなること

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
1. 廃止した StateFlow の公開 API 削除（`selectedResult`, `routeResults`, `waypoints`, `editingWaypointIndex`, `waypointEditResult` 等）
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
                            selectedRouteIndex = current.selectedRouteIndex,
                        )
                        _effects.trySend(HomeMapEffect.HideBottomSheet)
                        _effects.trySend(HomeMapEffect.EnterGuidanceFollowing)
                        _effects.trySend(HomeMapEffect.SetKeepScreenOn(enabled = true))
                        _effects.trySend(HomeMapEffect.UseNavigationLocationProvider(enabled = true))
                    }
                }
                is NavigationState.Arrival -> {
                    val current = _screenState.value
                    if (current is HomeMapScreenState.Navigating) {
                        // 目的地は _waypoints (内部保持) の最後の要素
                        // RouteWaypoint.Place に限定しない (swap で CurrentLocation が目的地になりうる)
                        val dest = _waypoints.value.lastOrNull() ?: return@onEach
                        _screenState.value = HomeMapScreenState.Arrived(
                            destination = dest,
                        )
                        _effects.trySend(HomeMapEffect.SetKeepScreenOn(enabled = false))
                    }
                }
                else -> { /* RoutePreview / Browsing はユーザー操作で遷移するため何もしない */ }
            }
        }
        .launchIn(viewModelScope)
}
```

### 5.3 カメラ競合の根絶

Effect Stream による one-shot 方式では、`trySend` した場合のみ副作用が発火する。
ただし `MoveCameraToRouteOverview` の場合、`RouteManager.routes` に SDK 側のルートデータが
反映されるのを待ってからカメラを移動する必要がある（現行実装と同様）。
Effect handler 内で `routeManager.routes.first { it.isNotEmpty() }` を待つことで解決する。

### 5.4 ルート情報の ownership

ナビ中のルート情報は `RouteManager.routes`（`RoutesObserver` 経由）が source of truth。
`HomeMapScreenState.Navigating` / `Arrived` は `RouteResult` を保持しない。
reroute やトラフィックリフレッシュによりルートが変わっても、screen state が stale になることはない。
ナビ UI (`HomeMapNaviContent`) がルート情報を必要とする場合は `RouteManager.routes` を直接 collect する。

### 5.4 段階的移行

Phase 1〜3 は独立した変更として進められる。各 Phase でビルドが通ることを確認してからコミットする。
旧 API（`onViewEvent`）は Phase 5 まで残存させ、コンパイルエラーが発生しないよう並行期間を設ける。

---

## 6. 変更ファイル一覧

| ファイル | 変更種別 | Phase |
|---------|---------|-------|
| `map/state/HomeMapScreenState.kt` | 新規作成 | 1 |
| `map/state/HomeMapOverlayState.kt` | 新規作成 | 1 |
| `map/state/HomeMapEffect.kt` | 新規作成 | 1 |
| `map/state/RoutePreviewTopBarMode.kt` | 新規作成 | 1 |
| `map/HomeMapViewModel.kt` | 大規模変更 | 1, 4, 5, 6 |
| `map/HomeMapScreenContent.kt` | 大規模変更 | 2 |
| `map/HomeMapSheetContent.kt` | 中規模変更 | 2 |
| `map/HomeMapsMapEffectContent.kt` | 中規模変更 | 3 |
| `map/HomeMapViewEvent.kt` | 廃止 | 5 |
| `map/components/topappbar/HomeMapTopAppBar.kt` | 引数変更 | 5 |
| `map/components/topappbar/HomeMapRouteTopAppBar.kt` | 大規模変更 | 4, 5 |
| `map/components/topappbar/HomeMapRouteTopAppBarConfirmed.kt` | 引数変更 | 4 |
| `map/components/topappbar/HomeMapRouteTopAppBarEditing.kt` | 引数変更 | 4 |
| `map/components/topappbar/HomeMapWaypointSearchScreen.kt` | 引数変更 | 4, 5 |
| `map/components/bottomsheet/HomeMapRouteResultSheet.kt` | 引数変更 | 2 |
| `map/components/bottomsheet/HomeMapSearchResultSheet.kt` | 引数変更 | 2 |
| `map/components/bottomsheet/HomeMapSelectedResultSheet.kt` | 引数変更 | 2 |
| `map/components/navi/HomeMapNaviContent.kt` | 中規模変更 | 2 |

---

## 7. Codex レビューへの対応記録

| # | 指摘 | 判断 | 対応内容 |
|---|------|------|---------|
| 1 | `PlaceSearch(selected?)` は内部フラグで再分岐するだけ | **受け入れ** | `SearchResultsList` と `PlaceDetails` に分離 |
| 2 | `LaunchedEffect(screenState)` は overlay 変更でも再発火する | **受け入れ** | `Channel<HomeMapEffect>` による one-shot effect に変更 |
| 3 | `Navigating`/`Arrived` に `activeRoute` を固定すると stale になる | **受け入れ** | route を保持せず `RouteManager.routes` を source of truth にする |
| 4 | `Arrived(destination: RouteWaypoint.Place)` は swap ケースで落ちる | **受け入れ** | `RouteWaypoint` に拡大 |
| 5 | Sheet swipe hide が未定義 | **半分受け入れ** | `onSheetDismissed()` で screenState を戻す方式。独立 sheet state は不要と判断（反論あり） |
