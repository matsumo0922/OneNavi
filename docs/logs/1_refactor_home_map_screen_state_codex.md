# HomeMap Screen State Refactor Notes

Date: 2026-04-05
Author: Codex
Status: Draft

## 目的

`feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/` 配下の状態管理を見直し、`HomeMapScreen` を「複数の生 state の組み合わせで偶然成立する画面」から、「画面単位の screen state によって明示的に切り替わる画面」へ整理する。

今回の主眼は以下の 3 点である。

1. 画面モードの source of truth を 1 つにする
2. カメラ移動と BottomSheet 制御を分散した `LaunchedEffect` 群から切り離し、競合を防ぐ
3. Route preview / guidance / arrival を今後拡張できる構造にする

この文書は、現状の問題点、推奨する設計、実装の段階的な進め方をまとめた設計メモである。

---

## 結論

ユーザー案の「画面ごとの screen state に寄せる」は正しい方向性である。

ただし、実装としては単純に `sealed interface HomeMapScreenState` を作るだけでは不十分で、次の 3 層に分けるのが最も安定する。

1. `screen state`
   - 今どの画面なのか
   - その画面に必要なデータは何か
2. `overlay state`
   - その画面の上に何が重なっているか
   - 例: waypoint 検索オーバーレイ
3. `one-shot effect`
   - カメラ移動、BottomSheet の show/hide、位置 provider 切り替え、画面点灯維持など
   - 再コンポーズのたびに再実行してはいけない処理

つまり、今回の推奨案は「screen state 化」に賛成だが、さらに一歩進めて「screen state + overlay state + effect stream」に分ける設計である。

---

## 対象ファイル

今回の調査対象は主に以下。

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapViewModel.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapSheetContent.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/topappbar/HomeMapTopAppBar.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/topappbar/HomeMapRouteTopAppBar.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/navi/HomeMapNaviContent.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/CameraManager.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/RouteManager.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/GuidanceSessionManager.kt`
- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/NavigationState.kt`

---

## 現状整理

## 1. 現在の source of truth

現状の `HomeMapScreen` は、明示的な screen state を持っていない。
代わりに、以下の複数 state の組み合わせから Compose 側が画面モードを推測している。

### ViewModel が持っている state

- `searchResults`
- `selectedResult`
- `routeResults`
- `selectedRouteIndex`
- `waypoints`
- `editingWaypointIndex`
- `waypointEditResult`
- `navigationState`
- `userLatitude`
- `userLongitude`
- `suggestions`
- `histories`

### Compose がローカルで持っている state

- `mapView`
- `trackingMode`
- `deviceBearing`
- `allowSheetHide`
- `contentHeight`
- `topAppBarHeightPx`
- `sheetPeekHeight`
- `sheetVisibleHeight`
- `HomeMapTopAppBar.showSearchResult`
- `HomeMapRouteTopAppBar.isEditing`

つまり、画面の意味を決める state が ViewModel と Compose ローカルに分散している。

---

## 2. 現在存在する画面状態

ユーザーが列挙した 4 状態は大筋として正しいが、実装上はそれより少し細かい状態に分かれている。

実際には次の 6 状態として扱う方が自然である。

| 状態 | 実装上の条件 | 地図 | TopAppBar | BottomSheet |
|---|---|---|---|---|
| Browsing | `searchResults` 空、`selectedResult` null、`routeResults` 空、`navigationState` が guidance/arrival ではない | 地図のみ | 通常検索バー | なし |
| SearchResultsList | `searchResults` 非空 | 検索結果の複数ピン | 通常検索バー | 検索結果一覧 |
| PlaceDetails | `selectedResult != null` かつ `routeResults` 空 | 単一ピン、地点中心へカメラ移動 | 通常検索バーだが内容は選択地点に寄る | 地点詳細 |
| RoutePreview | `routeResults` 非空、`waypoints` 非空、`navigationState` が guidance/arrival ではない | ルート線、目的地ピン、経由地アイコン、コールアウト | ルート用 TopAppBar | ルート概要 |
| Guidance | `navigationState is ActiveGuidance` | 追従カメラ、ナビ UI | 非表示 | 非表示 |
| Arrival | `navigationState is Arrival` | 現状では半端な混合状態 | 非表示 | 明示されていない |

重要なのは、ユーザー案の「地点検索結果画面」は実装上さらに次の 2 つに分かれている点である。

1. 検索結果一覧
   - 複数地点に番号ピン
   - BottomSheet は結果一覧
2. 単一点詳細
   - 選択地点 1 件にピン
   - BottomSheet は地点詳細

この 2 つは地図の描画も BottomSheet もカメラも異なるので、screen state として分けた方がよい。

---

## 3. 現在の分岐ロジック

### TopAppBar の切り替え

`HomeMapScreenContent` では以下のように分岐している。

- `!isNavigating && !isArrived && routeResults.isNotEmpty() && waypoints.isNotEmpty()` なら `HomeMapRouteTopAppBar`
- それ以外で `!isNavigating && !isArrived` なら `HomeMapTopAppBar`

問題は、ここで見ている条件が「RoutePreview かどうか」を型で表していないこと。
`routeResults` と `waypoints` の両方が揃っていることを画面モードの代わりに使っている。

### BottomSheet の切り替え

`HomeMapSheetContent` では以下の優先順位で分岐している。

1. `routeResults.isNotEmpty()`
2. `searchResults.isNotEmpty()`
3. `selectedResult != null`

これは screen state の代わりに生 data の有無で優先順位を決めているだけであり、画面モードが増えるほど破綻しやすい。

### 地図上の描画切り替え

`HomeMapsMapEffectContent` では以下のような優先順位になっている。

1. `isNavigating` ならマーカーなし
2. `searchResults` 非空なら複数ピン
3. `waypoints` 非空なら目的地マーカー
4. それ以外は `selectedResult` マーカー

この `else if` 連鎖は、「どの screen state なのか」を直接表しておらず、偶然成立する優先順位でしかない。

### カメラ制御

カメラ移動は次の複数箇所に分散している。

- `LaunchedEffect(searchResults, mapView)`
- `LaunchedEffect(selectedResult)`
- `LaunchedEffect(isNavigating)`
- `LaunchedEffect(routeResults)`
- `HomeMapNaviContent.LaunchedEffect(maneuverPanelHeightPx)`
- `HomeMapControls` の操作
- `CameraManager.requestCameraFollowing()`
- `CameraManager.requestCameraOverview()`

この構造では、状態遷移の途中で複数の `LaunchedEffect` が連続して発火しやすく、どのカメラ命令が最終的に勝つのかが読みづらい。

---

## 問題点

## 1. source of truth が存在しない

いまの画面は「`searchResults`, `selectedResult`, `routeResults`, `waypoints`, `navigationState` の組み合わせ」から意味を推測している。

これは次の問題を生む。

- 画面遷移の条件がコードのあちこちに散る
- ある状態のときに何が表示されるかを追いづらい
- 条件の組み合わせに抜け漏れが出る
- future state の追加が怖くなる

`screen state` を作るなら、この問題を根本から消せる。

---

## 2. state の更新順に依存した中間状態が発生する

例えば `onSearchResultSelected()` は次の順で更新している。

1. `searchResults = empty`
2. `selectedResult = result`

この間、Compose は一瞬 `Browsing` 相当の状態を見る可能性がある。

同様に `onDismissRoutes()` は次の順で更新している。

1. `routeResults = empty`
2. `selectedRouteIndex = 0`
3. `waypoints = empty`
4. `routeManager.clearRoutes()`
5. `navigationState = Browsing`

この途中でも UI 側の条件分岐は複数回走る。
今はたまたま大きな破綻が出ていないだけで、カメラや sheet のような副作用が絡むと簡単に不整合になる。

---

## 3. `NavigationState` が feature 画面 state と混ざっている

`core/model/NavigationState` は次の状態を持っている。

- `Browsing`
- `Search`
- `RoutePreview`
- `ActiveGuidance`
- `Arrival`
- `FreeDrive`

しかし実際の UI は `NavigationState.Search` を source of truth として使っていない。
`Search` や `RoutePreview` は feature/home 画面の都合であり、navigation engine の状態ではない。

現状は以下のような歪みがある。

- `GuidanceSessionManager` が `RoutePreview` や `Browsing` を持っている
- それとは別に `searchResults` や `selectedResult` や `routeResults` が UI モードを決めている
- 結果として、screen state が二重化している

推奨は次のどちらか。

1. 近い将来の最小変更
   - `GuidanceSessionManager.navigationState` はいったん残す
   - ただし guidance 関連の判定にのみ使う
   - HomeMap 画面の state は別に作る
2. 理想形
   - `NavigationState` を `GuidancePhase` などに縮小する
   - `Search` や `RoutePreview` は feature state へ移す

---

## 4. カメラ制御が分散し、命令競合が起きやすい

いまのカメラ命令は次の性質の異なるものが混在している。

- 通常地図の手動 camera move
- 検索結果一覧に対する fit
- 単一点への ease
- ルート全体 overview
- guidance の following
- guidance UI 高さに応じた padding 更新

さらに、次の 2 系統が混ざっている。

- `MapViewportState` への直接命令
- `CameraManager` 経由の `NavigationCamera` 命令

この構造自体は悪ではないが、命令の発火点が分散しているためバグになりやすい。
特に RoutePreview と Guidance の境目で不安定になりやすい。

---

## 5. BottomSheet が state-driven ではなく imperative

現在の BottomSheet 制御は次のような構造になっている。

- `allowSheetHide` フラグ
- `confirmValueChange`
- `scaffoldState.bottomSheetState.hide()`
- `partialExpand()`

これは screen state に応じて表示内容を変えているように見えて、実際には「いつ hide するか」「いつ partialExpand するか」をあちこちの `LaunchedEffect` が imperative に叩いている。

結果として、

- どの画面で sheet が見えるべきかが明示されていない
- RoutePreview から Guidance へ入るときや戻るときの復元ルールが分かりづらい
- Arrival を追加したときの sheet 挙動が宙に浮く

---

## 6. `HomeMapRouteTopAppBar` の編集状態がローカル state で、画面 state から見えない

`HomeMapRouteTopAppBar.isEditing` は composable ローカルにある。
これは route preview の画面モードの一部であり、単なる widget の内部状態ではない。

今の構造だと次が起きる。

- RoutePreview の「確定表示」と「編集表示」が screen state に現れない
- `editingWaypointIndex` や `waypointEditResult` と責務が割れる
- route editor の挙動をテストしづらい

ルート編集は route preview 画面の sub-state として持つべきである。

---

## 7. `editingWaypointIndex` と `waypointEditResult` が不自然な往復を作っている

現在は次の流れになっている。

1. waypoint をタップする
2. `editingWaypointIndex` をセットする
3. オーバーレイ検索画面を表示する
4. 選択結果を `waypointEditResult` に一時退避する
5. TopAppBar 側 `LaunchedEffect` でその結果を読む
6. waypoints を作り直して ViewModel に戻す

これはかなり回り道である。

本来は ViewModel が以下を一貫して持つ方が自然である。

- いま route preview 編集中か
- 編集対象 index はどれか
- draft waypoint list は何か

そうすれば、地点選択の結果は ViewModel が直接 draft に反映し、TopAppBar は完成した draft を受け取るだけでよい。

---

## 8. route selection が二重管理になっている

`selectedRouteIndex` は ViewModel にもあり、`RouteManager` にもある。

現状はたまたま同期しているが、責務が曖昧である。

- UI が見たい selected route index は feature の screen state
- SDK に対してどの route を primary にするかは `RouteManager`

この場合は、UI source of truth を feature state に寄せ、`RouteManager` は side-effect service として index を受け取るだけにした方が素直である。

---

## 9. Arrival が未完成で、状態遷移の歪みが露出している

Arrival は現状かなり中途半端である。

- 外側の `HomeMapScreen` では Arrival を navigating 扱いしている
- `HomeMapScreenContent` では `isNavigating` と `isArrived` を分けている
- `HomeMapsMapEffectContent` には `isNavigating || isArrived` を渡している
- しかし `HomeMapNaviContent` は `isNavigating` のときしか出ない
- その一方で `HomeMapControls` は Arrival 時に出る

つまり Arrival は 1 つの画面 state ではなく、複数分岐の結果として不整合な混合状態になっている。

これは「screen state を作るべき」ことの強い根拠である。

---

## 10. テストしづらい

いまの UI ロジックは `if (routeResults.isNotEmpty()) ...` のような条件分岐が Compose に直接散っている。

この場合、テストで確認したいのは本当は次の問いである。

- `PlaceDetails` のとき BottomSheet は何を表示するか
- `RoutePreview.Editing` のとき TopBar はどう見えるか
- `SearchResultsList` に遷移したときカメラはどう動くか

しかし現在はこれが「複数 state の組み合わせを手で作って Composable を観察する」形になっており、モデルの意図がコード上に現れていない。

---

## 設計目標

今回の refactor で達成したい目標は次の通り。

1. 画面モードを型で表す
2. invalid state を型で表現不可能にする
3. カメラ移動を 1 箇所で管理する
4. BottomSheet の表示ルールを 1 箇所に集約する
5. route preview の編集状態を feature state として持つ
6. guidance / arrival を後から実装しても条件分岐が増殖しない構造にする
7. widget のローカル state と app-level state を分離する
8. reducer テスト可能な構造にする

---

## 推奨アーキテクチャ

## 1. state を 4 層に分ける

### A. Domain / service state

サービスや repository から来る state。
feature の画面モードとは別物。

例:

- 現在地
- suggestions
- histories
- guidanceUiState
- arrivalInfo
- `RouteManager.routes`
- `CameraManager.cameraState`

### B. Screen state

「今どの画面なのか」を表す state。
これは UI の source of truth になる。

### C. Overlay state

画面の上に一時的に重なる UI。

例:

- waypoint 検索画面
- 将来の error dialog
- 将来の arrival summary modal

### D. Effect

再コンポーズで再実行してはいけない命令。

例:

- カメラを地点へ移動する
- 検索結果一覧を fit する
- ルート全体を overview する
- BottomSheet を hide / partial expand する
- navigation location provider を切り替える
- keep screen on を有効化する

---

## 2. `HomeMapScreenState` を導入する

screen state は次の形を推奨する。

```kotlin
@Immutable
sealed interface HomeMapScreenState {
    data object Browsing : HomeMapScreenState

    data class SearchResultsList(
        val query: String,
        val results: ImmutableList<SearchResultItem>,
    ) : HomeMapScreenState

    data class PlaceDetails(
        val place: SearchResultItem,
    ) : HomeMapScreenState

    data class RoutePreview(
        val waypoints: ImmutableList<RouteWaypoint>,
        val routes: ImmutableList<RouteResult>,
        val selectedRouteIndex: Int,
        val topBarState: RoutePreviewTopBarState = RoutePreviewTopBarState.Viewing,
    ) : HomeMapScreenState

    data class Guidance(
        val selectedRouteIndex: Int,
    ) : HomeMapScreenState

    data class Arrival(
        val selectedRouteIndex: Int?,
        val arrivalInfo: ArrivalInfo?,
    ) : HomeMapScreenState
}
```

この構造のポイントは次の通り。

- `SearchResultsList` と `PlaceDetails` を分ける
- `RoutePreview` に必要な data を丸ごと抱える
- route preview の編集状態は nested state に寄せる
- guidance / arrival を screen として明示する

---

## 3. `RoutePreviewTopBarState` を nested state にする

`HomeMapRouteTopAppBar.isEditing` を廃止し、route preview 内の sub-state として持つ。

```kotlin
@Immutable
sealed interface RoutePreviewTopBarState {
    data object Viewing : RoutePreviewTopBarState

    data class Editing(
        val draftWaypoints: ImmutableList<RouteWaypoint?>,
    ) : RoutePreviewTopBarState
}
```

これにより次が可能になる。

- route preview の表示モードが screen state から見える
- 編集開始、地点削除、並び替え、完了が reducer で表現できる
- TopAppBar は pure UI に近づく

---

## 4. `OverlayState` を導入する

waypoint 検索画面は route preview の sub-state ではあるが、画面全体にかぶさる overlay として扱う方が自然である。

```kotlin
@Immutable
sealed interface HomeMapOverlayState {
    data object None : HomeMapOverlayState

    data class WaypointSearch(
        val index: Int,
        val initialQuery: String?,
    ) : HomeMapOverlayState
}
```

この導入により、`editingWaypointIndex` と `waypointEditResult` は不要になる。

理想的な流れはこうなる。

1. `RoutePreview.Viewing` or `RoutePreview.Editing` で waypoint をタップ
2. `overlay = WaypointSearch(index, initialQuery)`
3. overlay で suggestion/history を選ぶ
4. ViewModel が draft waypoint に直接反映
5. `overlay = None`

結果を一時 state に退避して TopAppBar 側 `LaunchedEffect` で吸い上げる必要がなくなる。

---

## 5. `Effect` を導入する

screen state だけでは、カメラ移動のような one-shot 命令は表しきれない。
そのため、effect を分離する。

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

実装としては `Channel<HomeMapEffect>` か `MutableSharedFlow<HomeMapEffect>(replay = 0)` を推奨する。

---

## 6. `screen state` から `render state` を導出する

重要なのは、UI 用の state をさらに二重保持しないこと。

やりたいのは次のどちらか。

1. Composable 側で `when (screenState)` する
2. ViewModel で `screenState -> topBar/sheet/map` の pure mapper を作って渡す

推奨は 2 番だが、段階移行では 1 番でも十分効果がある。

例えば次のような explicit state にできる。

```kotlin
sealed interface HomeMapTopBarState {
    data class Search(
        val selectedPlace: SearchResultItem?,
    ) : HomeMapTopBarState

    data class Route(
        val waypoints: ImmutableList<RouteWaypoint>,
        val topBarState: RoutePreviewTopBarState,
    ) : HomeMapTopBarState

    data object Hidden : HomeMapTopBarState
}

sealed interface HomeMapBottomSheetState {
    data object Hidden : HomeMapBottomSheetState

    data class SearchResults(
        val results: ImmutableList<SearchResultItem>,
    ) : HomeMapBottomSheetState

    data class PlaceDetails(
        val place: SearchResultItem,
    ) : HomeMapBottomSheetState

    data class RoutePreview(
        val routes: ImmutableList<RouteResult>,
        val selectedRouteIndex: Int,
    ) : HomeMapBottomSheetState
}
```

ただし、これらを別 source of truth にしてはならない。
あくまで `HomeMapScreenState` から導く派生表現に留めるべきである。

---

## 7. `MapSceneState` を作る

`HomeMapsMapEffectContent` の `else if` 連鎖をやめ、地図描画に必要な要素を明示する。

```kotlin
@Immutable
data class HomeMapSceneState(
    val markers: ImmutableList<HomeMapMarker>,
    val waypointPins: ImmutableList<HomeMapWaypointPinState>,
    val routeCallouts: ImmutableList<HomeMapRouteCalloutState>,
    val routeLineState: HomeMapRouteLineState,
    val routeTapEnabled: Boolean,
)
```

これを `screenState` から導くようにすると、地図描画は次のように単純化できる。

- `for (marker in scene.markers) { ... }`
- `if (scene.routeLineState.visible) { ... }`
- `if (scene.routeTapEnabled) { ... }`

優先順位連鎖で意味を推測する必要がなくなる。

---

## 8. カメラは `HomeMapScreenContent` に 1 箇所だけ collector を置く

これは今回の refactor で最も重要なポイントの 1 つである。

現在は screen state と無関係に、各 `LaunchedEffect` がそれぞれカメラ命令を出している。
これをやめ、`HomeMapScreenContent` で effect を 1 本 collect し、そこでだけカメラと sheet を叩く。

理想形はこうなる。

```kotlin
LaunchedEffect(Unit) {
    viewModel.effects.collect { effect ->
        when (effect) {
            is HomeMapEffect.MoveCameraToSearchResults -> ...
            is HomeMapEffect.MoveCameraToPlace -> ...
            is HomeMapEffect.MoveCameraToRouteOverview -> ...
            is HomeMapEffect.EnterGuidanceFollowing -> ...
            is HomeMapEffect.HideBottomSheet -> ...
            is HomeMapEffect.ShowBottomSheetPartially -> ...
            is HomeMapEffect.SetKeepScreenOn -> ...
            is HomeMapEffect.UseNavigationLocationProvider -> ...
        }
    }
}
```

これで以下を削除できる。

- `LaunchedEffect(searchResults, mapView)`
- `LaunchedEffect(selectedResult)`
- `LaunchedEffect(isNavigating)`
- `LaunchedEffect(routeResults)`

副作用の発火源が 1 箇所にまとまるため、競合調査が圧倒的に楽になる。

---

## 9. `CameraManager` の責務を絞る

`CameraManager` 自体は低レベル service として有用なので残してよい。
ただし、責務は次のように限定した方がよい。

- `NavigationCamera`
- `ViewportDataSource`
- `NavigationLocationProvider`
- following / overview / idle の低レベル操作
- padding の設定

逆に、次は `CameraManager` の責務にしない方がよい。

- いつ地点へ飛ぶか
- いつ検索結果を fit するか
- どの screen state で overview に入るか

それらは feature 側の `screen state` / `effect` が決めるべきである。

---

## 10. `HomeMapNaviContent` を pure UI に寄せる

現状の `HomeMapNaviContent` は guidance UI であると同時に、camera padding を更新している。

これは責務として重い。

推奨は次のどちらか。

1. `HomeMapNaviContent` が UI 高さだけ親へ通知し、親が effect ハンドラ経由で `CameraManager` を更新する
2. `HomeMapNaviContent` に必要な layout metrics を親が与え、padding 計算も親が行う

どちらでもよいが、少なくとも guidance UI 自身が camera service を直接叩く構造は避けたい。

---

## ローカル state と ViewModel state の境界

すべてを ViewModel に上げる必要はない。

以下はローカル state のままでよい。

- `TextFieldState`
- `SearchBarState`
- search bar の expand / collapse アニメーション状態
- measured height / width
- drag gesture 中のオフセット
- 一時的な ripple や pressed state

以下は ViewModel / screen state に上げるべき。

- いま何の画面なのか
- route preview が viewing か editing か
- waypoint 検索 overlay が出ているか
- selected route はどれか
- guidance / arrival に入ったか
- Back で何を閉じるべきか

`HomeMapTopAppBar.showSearchResult` のように、一見 widget state に見えて実は画面モードと結びつくものはローカルに置かない方がよい。

---

## 推奨する遷移モデル

以下は現状の機能を保ちながら整理した遷移イメージである。

| イベント | 現在 state | 次 state | effect |
|---|---|---|---|
| 検索実行 | `Browsing` / `PlaceDetails` | `SearchResultsList` | `MoveCameraToSearchResults`, `ShowBottomSheetPartially` |
| 検索結果タップ | `SearchResultsList` | `PlaceDetails` | `MoveCameraToPlace`, `ShowBottomSheetPartially` |
| 地図長押し / POI タップ | `Browsing` / `PlaceDetails` | `PlaceDetails` | `MoveCameraToPlace`, `ShowBottomSheetPartially` |
| ルート検索開始 | `PlaceDetails` | `PlaceDetails` のまま or `RouteSearching` | optional |
| ルート検索成功 | `PlaceDetails` | `RoutePreview` | `MoveCameraToRouteOverview`, `ShowBottomSheetPartially` |
| ルート選択変更 | `RoutePreview` | `RoutePreview` | `MoveCameraToRouteOverview` |
| ルート編集開始 | `RoutePreview.Viewing` | `RoutePreview.Editing` | none |
| waypoint 検索開始 | `RoutePreview.*` | `RoutePreview.* + Overlay.WaypointSearch` | none |
| waypoint 確定 | `RoutePreview.* + Overlay.WaypointSearch` | `RoutePreview.Editing` | none |
| waypoint 編集完了 | `RoutePreview.Editing` | `RoutePreview.Viewing` | ルート再検索成功後 `MoveCameraToRouteOverview` |
| ナビ開始 | `RoutePreview` | `Guidance` | `HideBottomSheet`, `EnterGuidanceFollowing`, `SetKeepScreenOn(true)`, `UseNavigationLocationProvider(true)` |
| ナビ停止 | `Guidance` | `RoutePreview` | `MoveCameraToRouteOverview`, `ShowBottomSheetPartially`, `SetKeepScreenOn(false)`, `UseNavigationLocationProvider(false)` |
| 到着 | `Guidance` | `Arrival` | arrival 用の camera/sheet 制御を明示 |
| 戻る | `SearchResultsList` / `PlaceDetails` | `Browsing` | `HideBottomSheet` |
| 戻る | `RoutePreview` | `Browsing` or `PlaceDetails` | state 設計に応じる |

注記:

- ルート検索中 state を入れるかは UX 次第
- 最初の段階では loading state を導入せず、成功時のみ `RoutePreview` に遷移してもよい
- ただし将来的には `SearchingRoute` / `SearchingPlaces` のような transient state を持てる設計にしておくと拡張しやすい

---

## 実装方針

## 方針 A: 一気に全置換するのではなく、adapter を挟んで段階移行する

既存 state が多いため、いきなり `_uiState` 1 本へ完全移行すると diff が大きくなる。
そのため、まずは adapter 方式を推奨する。

### ステップ 1

既存の raw state は残したまま、`HomeMapScreenState` を導出する関数を作る。

例:

```kotlin
private fun reduceScreenState(
    searchResults: ImmutableList<SearchResultItem>,
    selectedResult: SearchResultItem?,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    waypoints: ImmutableList<RouteWaypoint>,
    guidanceState: NavigationState,
    arrivalInfo: ArrivalInfo?,
): HomeMapScreenState
```

この時点では source of truth はまだ二重化しているが、UI の分岐を集約することで効果を先に得られる。

### ステップ 2

`HomeMapScreenContent`, `HomeMapSheetContent`, `HomeMapsMapEffectContent`, TopAppBar を `screenState` ベースに切り替える。

### ステップ 3

UI 分岐が安定したら、ViewModel の raw field 群を `_screenState`, `_overlayState`, `_effects` 中心へ置き換える。

この順序なら、1 回の変更範囲を抑えながら移行できる。

---

## 実装計画

## Phase 1. 型の導入と現状分岐の吸い上げ

### 目的

- UI の分岐条件を 1 箇所にまとめる
- まずは read-only な screen state 導出から始める

### 追加候補ファイル

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/state/HomeMapScreenState.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/state/HomeMapOverlayState.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/state/RoutePreviewTopBarState.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/state/HomeMapStateMapper.kt`

### 変更候補ファイル

- `HomeMapViewModel.kt`

### やること

1. `HomeMapScreenState` を定義する
2. `HomeMapOverlayState` を定義する
3. 既存 raw state から `screenState` を導出する pure 関数を作る
4. `val screenState: StateFlow<HomeMapScreenState>` を生やす
5. `val overlayState: StateFlow<HomeMapOverlayState>` を生やす

### この段階ではまだやらないこと

- raw state の削除
- camera effect の一本化
- `NavigationState` rename

---

## Phase 2. Compose の分岐を `screenState` ベースに切り替える

### 目的

- 画面ごとの UI を `when (screenState)` に集約する
- 生 state 条件の散在を止める

### 変更候補ファイル

- `HomeMapScreenContent.kt`
- `HomeMapSheetContent.kt`
- `HomeMapsMapEffectContent.kt`
- `components/topappbar/HomeMapTopAppBar.kt`
- `components/topappbar/HomeMapRouteTopAppBar.kt`

### やること

1. `HomeMapScreenContent` で raw state 個別 collect を最小化し、`screenState` を中心に UI を組み立てる
2. TopAppBar を `screenState` から切り替える
3. BottomSheet を `screenState` から切り替える
4. `HomeMapsMapEffectContent` の引数を raw flags 群から `sceneState` へ寄せる
5. `HomeMapTopAppBar.showSearchResult` を screen state から導く
6. `HomeMapRouteTopAppBar.isEditing` を local state から外す準備をする

### 期待効果

- `if (routeResults.isNotEmpty())` のような分岐が大幅に減る
- Arrival のような半端な状態が表面化し、設計しやすくなる

---

## Phase 3. effect stream を導入し、カメラと sheet を一本化する

### 目的

- カメラ競合の解消
- sheet show/hide ロジックの明示化

### 追加候補ファイル

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/state/HomeMapEffect.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapEffectHandler.kt`

### 変更候補ファイル

- `HomeMapViewModel.kt`
- `HomeMapScreenContent.kt`
- `HomeMapNaviContent.kt`

### やること

1. ViewModel に effect channel を追加する
2. 検索結果表示、地点詳細表示、ルートプレビュー遷移、guidance 開始・停止のたびに effect を emit する
3. `HomeMapScreenContent` に 1 本の effect collector を置く
4. 現在の 4 つの `LaunchedEffect` を削除する
5. `HomeMapNaviContent` から `CameraManager.applyNavigationPadding()` の直叩きを外す
6. guidance layout に応じた padding は親で一元管理する

### 期待効果

- どのイベントでカメラが動くかが明示される
- route preview と guidance の遷移が安定する
- BottomSheet の表示/非表示復元が読みやすくなる

---

## Phase 4. route preview の編集状態を reducer 化する

### 目的

- `editingWaypointIndex` / `waypointEditResult` を撤去する
- route editor を composable ローカル state から feature state に移す

### 変更候補ファイル

- `HomeMapViewModel.kt`
- `HomeMapViewEvent.kt`
- `components/topappbar/HomeMapRouteTopAppBar.kt`
- `components/topappbar/HomeMapRouteTopAppBarConfirmed.kt`
- `components/topappbar/HomeMapRouteTopAppBarEditing.kt`
- `components/topappbar/HomeMapWaypointSearchScreen.kt`

### やること

1. `RoutePreviewTopBarState` に `Viewing` / `Editing(draftWaypoints)` を持たせる
2. waypoint タップ時は overlay を `WaypointSearch(index, initialQuery)` にする
3. suggestion/history 選択時に ViewModel が draft を直接更新する
4. `waypointEditResult` を削除する
5. `editingWaypointIndex` を削除する
6. `OnRouteWaypointsConfirmed` を reducer 的に扱い、route search 成功時に `RoutePreview.Viewing` へ戻す

### 期待効果

- Route top bar が pure UI に近づく
- state の流れが片方向になる
- waypoint 編集がテストしやすくなる

---

## Phase 5. service 境界の整理

### 目的

- feature state と core/navigation state の境界をはっきりさせる

### 変更候補ファイル

- `GuidanceSessionManager.kt`
- `NavigationState.kt`
- `RouteManager.kt`
- `CameraManager.kt`

### やること

#### 近い将来の pragmatic 案

1. `GuidanceSessionManager.navigationState` は guidance/arrival 判定専用として使う
2. `Search` と `RoutePreview` は `HomeMapScreenState` が表す
3. `RouteManager.selectedRouteIndex` は UI source of truth にしない

#### 理想案

1. `NavigationState` を guidance 専用 state に縮小する
2. `Browsing`, `Search`, `RoutePreview` を feature へ移す
3. `GuidanceSessionManager` は `ActiveGuidance`, `Arrival`, `Idle` だけを扱う

### コメント

これは影響範囲が大きいので、Phase 5 として後ろに置くのがよい。
まずは feature 内 refactor で安定化してからでも遅くない。

---

## Phase 6. Cleanup

### 削除候補

- `allowSheetHide` の特殊制御
- `showSearchResult` が持っている画面モード責務
- `HomeMapRouteTopAppBar.isEditing`
- `editingWaypointIndex`
- `waypointEditResult`
- `NavigationState.Search` の実質未使用ロジック
- 使われない重複 state

### 最終形の期待像

- Composable 側は `screenState` を見て UI を出し分けるだけ
- overlay は `overlayState` だけで出し分ける
- カメラや sheet は `effects` collector が処理する
- `RouteManager` と `CameraManager` は低レベル service に徹する

---

## 実装時の注意点

## 1. `screen state` を巨大な DTO にしすぎない

やりがちな失敗は、TopBar、BottomSheet、Map、Guidance UI の presentational data を全部 1 つの巨大な data class に入れること。

それをやると次が起きやすい。

- 何が source of truth か分からなくなる
- 派生 data を持ちすぎて二重管理になる
- 変更差分が大きくなって recompose 範囲が読みにくくなる

そのため、source of truth は `HomeMapScreenState` に留め、presentation はそこから導出する方が安全。

---

## 2. effect は再送しない

カメラ移動や sheet 操作を `StateFlow` の中に入れてしまうと、再 collect 時にもう一度動いてしまう。
これは避けたい。

よって以下を推奨する。

- `screenState`: `StateFlow`
- `effects`: `Channel` or `SharedFlow(replay = 0)`

---

## 3. layout measurement はローカルに残す

`sheetPeekHeight`, `topAppBarHeightPx`, `maneuverPanelHeightPx` のような値は runtime layout に依存するため、Composable ローカルに残してよい。

ただし、その測定値を使って camera padding を決める処理は親の 1 箇所へ寄せるべきである。

---

## 4. route search 中間状態をどうするかを最初に決める

`OnRouteSearch` 時点では route 結果がまだない。

このときの方針は先に決めておく必要がある。

### 方針 A

`PlaceDetails` のまま検索を進め、成功したら `RoutePreview` に遷移する。

利点:

- 変更量が少ない
- 今の UX に近い

欠点:

- loading 表現が弱い

### 方針 B

`RouteSearching` のような一時 state を導入する。

利点:

- 非同期境界が明確
- 将来 progress / skeleton を入れやすい

欠点:

- state 数が増える

短期的には A でよいが、設計自体は B を追加できるようにしておきたい。

---

## 5. Arrival を先送りにしない

Arrival は今の構造の歪みが最も出ている箇所なので、今回の refactor で state として明示した方がよい。

最低限でも次は決めておく必要がある。

- Arrival で TopBar は出すのか
- BottomSheet は出すのか
- Controls は出すのか
- route preview へ戻れるのか
- `onNavigatingChanged` との整合はどうするか

Arrival を `Guidance` の例外扱いにすると、また条件分岐が散らばる。

---

## テスト計画

## 1. reducer / transition unit test

最低限、ViewModel または reducer 相当の pure ロジックに対して次をテストしたい。

- 検索結果から地点詳細への遷移
- 地点詳細からルートプレビューへの遷移
- ルート選択変更
- route preview 編集開始・完了
- waypoint overlay の open / close
- guidance 開始 / 停止
- arrival 遷移
- 戻る操作

---

## 2. screen state -> presentation mapping test

次の mapping が pure ならテストできる。

- `screenState -> topBarState`
- `screenState -> sheetState`
- `screenState -> mapSceneState`

これにより、UI 条件分岐の退行をテストしやすくなる。

---

## 3. effect emission test

例えば次を確認したい。

- `SearchResultsList` 遷移時に `MoveCameraToSearchResults` が出る
- `PlaceDetails` 遷移時に `MoveCameraToPlace` が出る
- `RoutePreview` 遷移時に `MoveCameraToRouteOverview` が出る
- guidance 開始時に `HideBottomSheet`, `EnterGuidanceFollowing`, `SetKeepScreenOn(true)` が出る

---

## 4. integration / manual test 観点

画面上では次を確認したい。

1. 検索結果一覧表示中に別の `LaunchedEffect` が割り込まず、カメラが安定すること
2. 地点詳細からルート検索へ入るときに BottomSheet が意図通り切り替わること
3. route preview で route を切り替えても overview が崩れないこと
4. guidance 開始時に sheet が確実に隠れ、following に入ること
5. guidance 停止後に route preview が意図通り復元されること
6. arrival 時に UI が未定義の混合状態にならないこと

---

## 提案の採否

## 採用したい案

採用したいのは次の案である。

- `HomeMapScreenState` を導入する
- `HomeMapOverlayState` を導入する
- `HomeMapEffect` を導入する
- `HomeMapScreenContent` で effect を一元処理する
- `HomeMapsMapEffectContent` を render state ベースに寄せる
- route preview の編集状態を ViewModel 側 state に寄せる

---

## 採用しない方がよい案

### 案 1. 今の raw state を維持したまま helper 関数だけ増やす

短期的には楽だが、camera の競合と中間状態の問題は解消されない。

### 案 2. すべてを `NavigationState` に押し込む

`Search` や `RoutePreview` は navigation engine の状態ではなく feature/home の状態である。
責務が混ざるので避けたい。

### 案 3. TopBar / Sheet / Map state を全部 persistent に個別保持する

派生 state の二重管理になりやすい。
source of truth は `screenState` に絞る方がよい。

---

## 最終提案

今回の refactor は、単に「フラグを減らす」話ではない。
本質は「画面の意味を型で持ち、カメラや sheet のような副作用を state から分離する」ことにある。

最終提案を 1 行でまとめると次の通り。

> `HomeMap` を「複数の data の有無で偶然成立する画面」から、「`HomeMapScreenState` と `HomeMapEffect` によって明示的に駆動される画面」へ移行する。

この方針で進めれば、ユーザーが指摘した以下の問題は根本的に改善できる。

- フラグの絡み合い
- カメラ移動競合
- `HomeMapsMapEffectContent` の肥大化
- route preview / guidance / arrival の不整合
- future state 追加時の複雑化

次の実装着手順としては、まず Phase 1 と Phase 2 を行い、UI 分岐を `screenState` ベースに切り替えるのが最も安全である。
その後に Phase 3 で effect を導入し、最後に route editor と service 境界を整理するのがよい。
