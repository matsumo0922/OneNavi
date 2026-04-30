# 24. New Guidance Manager 設計 — spec/23 を本番統合する Kotlin 実装の正本仕様

## 0. このドキュメントの目的

`docs/spec/23_route_compare_dev_tool.md` で確立した「外部ナビ API ライブラリの
ルートを Google Routes API + waypoint sampling で polyline レベルに再現する
アルゴリズム」を **Android 本番側 (`:feature:map`) に統合する** ための設計仕様。

context を失った状態からでも本ドキュメント単体で実装着手できる粒度で記述する。

### 0.1 前提とする決定事項のサマリ

| 項目 | 決定 |
|---|---|
| 実装モジュール | `:feature:map` (新規ファイル群を追加) |
| 旧実装の扱い | `:feature:home` 配下の旧 `RouteManager` / `GuidanceManager` は当面残置 (touch しない)。本ドキュメントで定義する新クラス群は `New` prefix で新規作成 |
| 地図 View | 既存 `MapItem.kt` の `NavigationView` をそのまま流用 (内蔵 UI は全 OFF 済み) |
| リルート検知元 | **NavigationView / Map SDK 側**。`Navigator.ReroutingListener` を購読 |
| 目的地到達検知 | **NavigationView / Map SDK 側**。`Navigator.ArrivalListener` を購読 |
| 走行進捗取得 | `Navigator.OnRemainingTimeOrDistanceChangedListener` |
| 外部ナビ API ライブラリの責務 | **ルート探索のみ** (リルート検知 / 到達検知 / GPS 監視は持たない) |
| 音声案内・マニューバ表示 | 外部ナビ API ライブラリ由来の GP テキストを SDK 進捗にトリガーで連動再生 (本ドキュメント範囲外、別仕様で詳細化) |
| 課金・ライセンス | OSS 公開停止済み・個人利用前提。Mobility Services Agreement 等の確認は不要 |

### 0.2 用語

- **外部ナビ API ライブラリ**: 別管理プライベートリポジトリ `drive-supporter-api/` に
  存在する N 社製 API のラッパライブラリ。本リポジトリ (OneNavi) からは公開
  interface 経由で参照する。本ドキュメントは Kotlin 統合側のみ扱う
- **N 社**: 外部ナビ API 提供事業者の代替表記。具体名は本ドキュメント・コード上に
  一切露出させない (`CLAUDE.md` §厳命)
- **chunk**: spec/23 §7.1 の Routes API 分割単位。1 chunk = 1 origin + 最大 25
  intermediates + 1 destination
- **route_token**: Routes API v2 から発行される識別子。Navigation SDK の
  `Navigator.setDestinations(waypoints, CustomRoutesOptions)` に渡すことで
  「事前計算済みの特定ルートで案内せよ」という指示になる。1 つの Routes API
  call につき 1 つ発行される
- **active chunk**: Guidance 中に SDK へ現在投入している chunk
- **refined polyline**: spec/23 のアルゴリズムを通して Routes API で再現した
  polyline (= 外部ナビ API ライブラリのルート形状を Routes API 上の道路に
  snap させた結果)

---

## 1. 全体アーキテクチャ

### 1.1 役割分担

```
┌──────────────────────────────────────────────────────────────┐
│ feature/map (Compose UI)                                     │
│   MapScreen / MapItem (NavigationView wrap)                  │
│       │                                                      │
│       ▼                                                      │
│   ┌──────────────────────────┐    ┌──────────────────────┐  │
│   │ NewRouteManager          │    │ NewGuidanceManager   │  │
│   │ (Preview 期の管理)       │───▶│ (Guidance 期の管理)  │  │
│   └──────────────────────────┘    └──────────────────────┘  │
│       │                              │                       │
│       │            ┌─────────────────┘                       │
│       ▼            ▼                                         │
│   ┌──────────────────────────┐                               │
│   │ ExtNavRouteRefiner       │  ← spec/23 の Kotlin port    │
│   │ (純粋ロジック)            │                               │
│   └──────────────────────────┘                               │
│       │                                                      │
│       ▼                                                      │
│   ┌──────────────────────────┐                               │
│   │ RoutesApiClient (interface) │                            │
│   │   └─ DefaultRoutesApiClient (Ktor 実装)                 │
│   └──────────────────────────┘                               │
└──────────────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────────┐
│ 外部ナビ API ライブラリ (drive-supporter-api/, private repo) │
│   - ルート探索のみ                                            │
│   - alternative routes を含む List<ExtNavRoute> を返す       │
│   - リルート検知・GPS 監視・到達検知は提供しない              │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ Google Navigation SDK                                        │
│   - NavigationView (UI)                                      │
│   - Navigator                                                │
│       - setDestinations(waypoints, CustomRoutesOptions)      │
│       - startGuidance / stopGuidance                         │
│       - addReroutingListener (逸脱検知)                      │
│       - addArrivalListener (到達検知)                        │
│       - addRemainingTimeOrDistanceChangedListener (進捗)     │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 データフロー (Preview から Guidance まで)

```
[1. ユーザが目的地確定]
    ↓
[2. NewRouteManager.searchRoutes(origin, dest)]
    ↓
[3a. 外部ナビ API ライブラリからルート候補 List<ExtNavRoute> 取得]
    ↓
[3b. 各候補を ExtNavRouteRefiner で並列に refine]
    │     │ 各候補ごとに:
    │     │   - polyline 内側頂点を FPS sampling
    │     │   - waypoint chunk 化 (intermediateMax=25)
    │     │   - 各 chunk を Routes API に POST → polyline + route_token
    │     │   - 全 chunk の polyline を結合、token は配列で保持
    ↓
[4. 全候補の refine 完了を待ってから UI に渡す]
    ↓
[5. Compose 側 MapRoutePreviewSheet で複数候補表示]
    │     │ - 全候補の自前 polyline を MapPolyline で描画
    │     │ - selected route のみ Navigator.setDestinations(chunk0)
    ↓
[6. ユーザがルート選択切り替え → 事前取得済み token を再投入]
    ↓
[7. 「案内開始」ボタン → Navigator.startGuidance()]
    ↓
[8. Guidance 中: chunk 走行 80% で次 chunk の token に切替]
    │     │ 失敗時 3 回リトライ → 失敗で「案内続行不可能」として停止
    ↓
[9a. 目的地到達 → Navigator.ArrivalListener で検知 → Idle へ]
[9b. 逸脱検知 → ReroutingListener で検知 → 再探索 → setDestinations 上書き]
```

---

## 2. 既存実装との関係

### 2.1 旧実装の扱い

`:feature:home` 配下の旧 `RouteManager` / `GuidanceManager` は **触らない**。
git で履歴を残すだけでなく、当面 runtime にも残置する。理由:

- 開発者は単独 (個人プロジェクト)。リファクタ漏れによる退行リスクを避けたい
- 新実装が安定するまでロールバック先として旧実装を保持

新実装が安定したら旧実装を削除する PR を別途行う (本ドキュメントの範囲外)。

### 2.2 新規モジュールではなく `:feature:map` 配下に追加する根拠

spec/23 §11.2.5 では `feature/navigation/` 新設を案として書いていたが、
`:feature:map` には既に `MapItem.kt` (NavigationView wrap) や `MapCameraState` が
あり、SDK との接合点はここに集中している。新クラス群を別モジュールに切り出すと
DI 配線とインポートが増えるだけで利益が小さい。

将来コードが膨らんで分離したくなった時点で `:feature:navigation` 等への切り出しを
検討する。

### 2.3 ファイル配置

```
feature/map/src/androidMain/kotlin/me/matsumo/onenavi/feature/map/
├── MapScreen.android.kt              # 既存。MapScreenState 分岐に
│                                       NewRouteManager / NewGuidanceManager の
│                                       state を購読する記述を追加
├── MapItem.kt                        # 既存。NavigationView wrap。Navigator
│                                       取得 API を追加で公開する (§10.1)
├── components/
│   └── ...                           # 既存
├── state/
│   ├── MapCameraState.kt             # 既存
│   └── MapScreenState.kt             # 既存
└── navigation/                       # 新規ディレクトリ
    ├── NewRouteManager.kt
    ├── NewGuidanceManager.kt
    ├── ExtNavRouteRefiner.kt
    ├── RoutesApiClient.kt            # interface
    ├── DefaultRoutesApiClient.kt     # Ktor 実装
    ├── PolylineDecoder.kt            # Google encoded polyline → List<LatLng>
    └── model/
        ├── RefinedRoute.kt
        ├── RefinedChunk.kt
        ├── RoutesApiWaypoint.kt
        └── GuidanceState.kt
```

DI module は `feature/map` の既存 module ファイル (`MapModule.kt` 等) に追記。

---

## 3. クラス構成

### 3.1 `ExtNavRouteRefiner` (純粋関数群)

spec/23 §11.2.3 の Kotlin port。proto / guide には依存しない。

```kotlin
/**
 * 外部ナビ API ライブラリのルート polyline を、Google Routes API を
 * 使って同じ道路を選ぶように再現する純粋関数群。spec/23 のアルゴリズムを
 * Kotlin に移植したもの。
 *
 * 入出力は polyline (List<LatLng>) と origin/destination のみ。proto や
 * 案内地点メタデータは不要 (spec/23 §0.1 の決定済み運用設定下では maneuver
 * 解析を使わない)。
 *
 * @property routesApiClient Routes API v2 クライアント (DI で注入)
 */
internal class ExtNavRouteRefiner(
    private val routesApiClient: RoutesApiClient,
) {
    /**
     * spec/23 §5-§6 の polyline FPS。内側頂点 (1..N-2) から targetGap で間引いた
     * waypoint 列を返す。各 waypoint には進行方向 heading を付与。
     *
     * @param extPolyline 外部ナビ API ライブラリ由来の polyline (WGS84)
     * @param targetGapMeters waypoint 間隔の目標値。spec/23 で実測 4000m が最適
     */
    fun samplePolylineWaypoints(
        extPolyline: List<LatLng>,
        targetGapMeters: Double = DEFAULT_TARGET_GAP_METERS,
    ): List<RoutesApiWaypoint>

    /**
     * spec/23 §7.1 の chunk 分割。stepSize = intermediateMax + 1 で
     * 隣接 chunk の終点と始点を共有する。
     */
    fun chunkWaypoints(
        waypoints: List<RoutesApiWaypoint>,
        intermediateMax: Int = ROUTES_API_INTERMEDIATE_MAX,
    ): List<List<RoutesApiWaypoint>>

    /**
     * 各 chunk を Routes API に投げて polyline + route_token を取得する。
     * useVia=true で intermediates を pass-through 扱いにする (spec/23 §7.3)。
     *
     * @return chunk 順の RefinedChunk リスト。各 RefinedChunk は polyline / token /
     *         waypoints をひとまとめにする
     */
    suspend fun computeChunkedRoute(
        chunks: List<List<RoutesApiWaypoint>>,
        useVia: Boolean = true,
    ): List<RefinedChunk>

    /**
     * 高レベルエントリ。外部 polyline + origin + destination を受け取り、
     * RefinedRoute (= 全 chunk と結合 polyline) を返す。
     */
    suspend fun refine(
        extPolyline: List<LatLng>,
        origin: LatLng,
        destination: LatLng,
    ): RefinedRoute

    companion object {
        const val DEFAULT_TARGET_GAP_METERS = 4000.0
        const val ROUTES_API_INTERMEDIATE_MAX = 25
    }
}
```

実装ポイント:

- `samplePolylineWaypoints` は spec/23 §6.2 の FPS をそのまま移植。seeds は
  `[0, totalLength]` から始め、`bestMin < targetGap * 0.4` で早期打ち切り
- `chunkWaypoints` は隣接 chunk 境界で waypoint を **重複させて** 保持する。
  例: 60 点 / chunkSize=25 → chunk0=[0..26], chunk1=[26..52], chunk2=[52..59]
- `computeChunkedRoute` は chunks を順に await。並列化は **行わない** (Routes API
  rate limit 配慮 + 失敗時の分かりやすさ優先)
- 各 chunk は origin (=waypoints[0]), destination (=waypoints.last()),
  intermediates (中間) として組み立て、intermediates のみ via=true
- `refine` は `samplePolylineWaypoints` → chunks 化 → 各 chunk を Routes API →
  `RefinedRoute` 構築 の順

### 3.2 `RoutesApiClient` (interface)

```kotlin
internal interface RoutesApiClient {
    /**
     * Routes API v2 `/directions/v2:computeRoutes` を呼ぶ。
     *
     * @param chunk 1 chunk 分の waypoints (origin / intermediates / destination)
     * @param useVia intermediates に via:true を付けるか
     * @return polyline と route_token をペアで返す
     */
    suspend fun computeRoute(
        chunk: List<RoutesApiWaypoint>,
        useVia: Boolean,
    ): Result<RoutesApiResponse>
}

internal data class RoutesApiResponse(
    val polyline: List<LatLng>,      // decode 済み
    val routeToken: String,
    val distanceMeters: Int,
    val durationSeconds: Long,
)
```

実装は `DefaultRoutesApiClient` (Ktor):

- リクエスト body: spec/23 §7.5 のテンプレート
- ヘッダ:
  - `X-Goog-Api-Key`: BuildKonfig 経由 (`local.properties` の `GOOGLE_MAPS_API_KEY`)
  - `X-Goog-FieldMask`: `routes.polyline.encodedPolyline,routes.routeToken,routes.distanceMeters,routes.duration`
- レスポンスの encoded polyline は `PolylineDecoder.decode()` で `List<LatLng>` に展開

### 3.3 `NewRouteManager` (Preview 期)

`MapScreenState.RoutePreview` を駆動する状態保持クラス。

```kotlin
internal class NewRouteManager(
    private val extNavRouteRefiner: ExtNavRouteRefiner,
    private val extNavRouteRepository: ExtNavRouteRepository,
) {
    private val _state = MutableStateFlow<RoutePreviewState>(RoutePreviewState.Idle)
    val state: StateFlow<RoutePreviewState> = _state.asStateFlow()

    suspend fun searchRoutes(origin: LatLng, destination: LatLng) {
        _state.value = RoutePreviewState.Searching
        runCatching {
            // 1. 外部ライブラリからルート候補取得 (alternatives 含む List)
            val extRoutes = extNavRouteRepository.search(origin, destination)
            // 2. 全候補を順次 refine (並列ではなく逐次。Routes API rate limit と
            //    エラーハンドリング簡略化のため)
            val refined = extRoutes.map { extRoute ->
                extNavRouteRefiner.refine(
                    extPolyline = extRoute.polyline,
                    origin = origin,
                    destination = destination,
                )
            }
            refined
        }.onSuccess { routes ->
            _state.value = RoutePreviewState.Ready(
                routes = routes.toImmutableList(),
                selectedIndex = 0,
            )
        }.onFailure { error ->
            _state.value = RoutePreviewState.Failed(error)
        }
    }

    fun selectRoute(index: Int) {
        val current = _state.value as? RoutePreviewState.Ready ?: return
        require(index in current.routes.indices)
        _state.value = current.copy(selectedIndex = index)
    }
}

@Immutable
internal sealed interface RoutePreviewState {
    data object Idle : RoutePreviewState
    data object Searching : RoutePreviewState
    @Immutable
    data class Ready(
        val routes: ImmutableList<RefinedRoute>,
        val selectedIndex: Int,
    ) : RoutePreviewState
    @Immutable
    data class Failed(val error: Throwable) : RoutePreviewState
}
```

`extNavRouteRepository.search` の interface は外部ライブラリ側 repo
(`drive-supporter-api/`) で定義済み。本ドキュメントでは「`List<ExtNavRoute>` を
返す suspend 関数」として参照のみ。

### 3.4 `NewGuidanceManager` (Guidance 期)

選択されたルートで案内を実行する。Navigator との接合点。

```kotlin
internal class NewGuidanceManager(
    private val routesApiClient: RoutesApiClient,
    private val extNavRouteRefiner: ExtNavRouteRefiner,
    private val extNavRouteRepository: ExtNavRouteRepository,
) {
    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    private var navigator: Navigator? = null
    private var route: RefinedRoute? = null
    private var activeChunkIndex: Int = 0

    /** Guidance 開始。selected RefinedRoute と Navigator を受け取る */
    fun startGuidance(navigator: Navigator, route: RefinedRoute) { ... }

    /** Guidance 停止 */
    fun stopGuidance() { ... }

    // Navigator の listener コールバック内から呼ばれる
    private fun onRemainingDistanceChanged(remainingMeters: Int) { ... }
    private fun onReroutingRequestedByOffRoute() { ... }
    private fun onArrival() { ... }

    // 内部処理
    private suspend fun advanceToNextChunk() { ... }
    private suspend fun reroute(currentLocation: LatLng) { ... }
}
```

詳細は §5, §6 で。

---

## 4. RoutePreview フェーズ

### 4.1 シーケンス

```
ユーザ目的地確定
   │
   ▼
NewRouteManager.searchRoutes(origin, dest)
   │  state = Searching
   ▼
extNavRouteRepository.search()
   │  (List<ExtNavRoute>)
   ▼
For each ext route:
   ExtNavRouteRefiner.refine()
       ├ polyline FPS sampling
       ├ chunk 化
       └ chunk 数だけ Routes API 呼び出し
            (各 chunk に route_token がつく)
   │
   ▼
全候補 refined 完了
   │  state = Ready(routes, selectedIndex=0)
   ▼
Compose UI 再構成
   ├ MapPolyline で全候補を自前描画 (selected = 通常色, unselected = 薄色)
   └ Navigator.setDestinations(selected.chunks[0].waypoints,
                                CustomRoutesOptions(selected.chunks[0].token))
```

### 4.2 描画方針

| layer | 描画主体 | 内容 |
|---|---|---|
| 全候補 polyline (unselected) | 自前 `MapPolyline` | 薄色 (例: alpha 0.4) |
| 全候補 polyline (selected) | 自前 `MapPolyline` (色変えのみ) | 通常色 |
| selected の chunk 0 | NavigationView (SDK) | SDK が token から自動描画 |

**疑問への回答**: selected ルートは「自前の通常色 polyline」と「SDK の chunk 0
polyline」が **重なって描画される**。ただし両者は同じ道路に乗っているので
視覚上は問題ない (僅かなアンチエイリアスズレが出る程度)。SDK の polyline が
上に来ることで、SDK 側のスタイル (進行方向矢印等) が見える。

### 4.3 ルート選択切り替え

`selectRoute(index)` が呼ばれたら:

1. `RoutePreviewState.Ready.selectedIndex` を更新
2. Compose の `LaunchedEffect(selectedIndex)` で:
   - `Navigator.clearDestinations()` (一旦クリア)
   - `Navigator.setDestinations(newSelected.chunks[0].waypoints, CustomRoutesOptions(newSelected.chunks[0].token))`
3. 自前 `MapPolyline` の selected/unselected 色も updateScene

Preview 中は `startGuidance()` を **呼ばない**。guidance 開始は明示的なボタン押下。

### 4.4 Routes API 呼び出し回数の見積り

```
total_calls = sum over alternatives [ ceil(route_length / chunk_max_length) ]
```

shakuji-tsukuba (74km) で alternatives=3 → 3 calls。
東京-青森 (700km) で alternatives=3、各 5 chunk → 15 calls。

並列化はしない方針 (§3.1)。逐次で 1 call ~1-2 秒として、最大で 30 秒程度の
待機時間が発生し得る。Preview 表示までのロード UI は **本ドキュメント範囲外**
(後で追加実装)。当面は全 fetch 完了まで `RoutePreviewState.Searching` のまま
画面に何も出さない。

---

## 5. Guidance フェーズ

### 5.1 開始

```kotlin
fun startGuidance(navigator: Navigator, route: RefinedRoute) {
    this.navigator = navigator
    this.route = route
    this.activeChunkIndex = 0

    navigator.addRemainingTimeOrDistanceChangedListener(remainingListener)
    navigator.addReroutingListener(reroutingListener)
    navigator.addArrivalListener(arrivalListener)

    val chunk0 = route.chunks[0]
    navigator.setDestinations(
        waypoints = chunk0.waypoints.toNavWaypoints(),
        customRoutesOptions = CustomRoutesOptions.Builder()
            .setRouteToken(chunk0.routeToken)
            .setTravelMode(TravelMode.DRIVING)
            .build(),
    )
    navigator.startGuidance()

    _state.value = GuidanceState.Guiding(activeChunkIndex = 0)
}
```

### 5.2 token chain 切替 (chunk 内 80% トリガー)

#### 5.2.1 トリガー判定

`OnRemainingTimeOrDistanceChangedListener` で残距離を購読:

```kotlin
private val remainingListener = OnRemainingTimeOrDistanceChangedListener {
    val navigator = navigator ?: return@OnRemainingTimeOrDistanceChangedListener
    val route = route ?: return@OnRemainingTimeOrDistanceChangedListener
    val activeChunk = route.chunks[activeChunkIndex]

    val remainingMeters = navigator.currentTimeAndDistance.distanceMeters
    val chunkLength = activeChunk.distanceMeters
    val progressFraction = 1.0 - (remainingMeters.toDouble() / chunkLength)

    if (progressFraction >= 0.8 && activeChunkIndex + 1 < route.chunks.size) {
        // 既に切替処理中なら何もしない (重複起動防止)
        if (_state.value !is GuidanceState.AdvancingChunk) {
            launch { advanceToNextChunk() }
        }
    }
}
```

**重要**: 「全体の 80%」ではなく「**現 active chunk の走行 80%**」。chunk が短い
場合も長い場合も同じロジックで動く。

#### 5.2.2 切替処理

```kotlin
private suspend fun advanceToNextChunk() {
    _state.value = GuidanceState.AdvancingChunk(from = activeChunkIndex)

    val nextIndex = activeChunkIndex + 1
    val nextChunk = route?.chunks?.getOrNull(nextIndex) ?: return

    runCatching {
        // 次 chunk の token を SDK に投入。waypoints も chunk 一致を保つ
        navigator?.setDestinations(
            waypoints = nextChunk.waypoints.toNavWaypoints(),
            customRoutesOptions = CustomRoutesOptions.Builder()
                .setRouteToken(nextChunk.routeToken)
                .setTravelMode(TravelMode.DRIVING)
                .build(),
        )
    }.onSuccess {
        activeChunkIndex = nextIndex
        _state.value = GuidanceState.Guiding(activeChunkIndex = nextIndex)
    }.onFailure { error ->
        retryAdvance(error, retryCount = 0)
    }
}

private suspend fun retryAdvance(lastError: Throwable, retryCount: Int) {
    if (retryCount >= 3) {
        _state.value = GuidanceState.Failed("chunk 切替に 3 回失敗しました")
        return
    }
    delay(RETRY_DELAY_MS * (retryCount + 1))
    runCatching {
        navigator?.setDestinations( ... )  // 同じ呼び出しを再試行
    }.onSuccess {
        activeChunkIndex++
        _state.value = GuidanceState.Guiding(activeChunkIndex)
    }.onFailure { error ->
        retryAdvance(error, retryCount + 1)
    }
}
```

#### 5.2.3 Routes API 失敗時のリトライポリシー

**注意**: chunk 切替の Routes API 呼び出し自体は Preview 段階で **既に完了して
いる** (token は事前取得済み)。つまり「token 取得失敗」は起きない。`setDestinations`
の失敗 (token 期限切れ等) のみが対象。

リトライ間隔:

| 試行 | delay |
|---|---|
| 1 回目失敗後 | 5 秒 |
| 2 回目失敗後 | 10 秒 |
| 3 回目失敗後 | 中止 → `GuidanceState.Failed` |

### 5.3 描画方針 (重ね描画)

| layer | 描画主体 | 内容 |
|---|---|---|
| 全 chunk polyline (薄色) | 自前 `MapPolyline` | 走行済み区間も含めて常に全て描画 |
| active chunk polyline | NavigationView (SDK) | SDK が token から描画 (通常色 + 進行方向矢印) |

**ポイント**: 自前薄レイヤーは **全 chunk を常時描画**。SDK の polyline はその
上に重なって表示される。chunk 切替時に自前側を消す処理は行わない (連続性が出る)。
切替の瞬間、SDK polyline が chunk 0 の終点で消えて chunk 1 の始点で出現するが、
自前薄レイヤーがあるので「ルートが消えた」ように見えることはない。

### 5.4 進捗取得

`OnRemainingTimeOrDistanceChangedListener` のみを購読する。`Navigator.currentTimeAndDistance`
で現 chunk の残距離 / 残時間が取れる。

**全体進捗** (= 走行済み chunk 0..n-1 の累計距離 + 現 chunk の走行済み距離) は
`route.chunks` のメタデータと組み合わせて自前計算する。これは UI (ETA 表示等)
で必要になるが、本ドキュメントの範囲は token chain 切替まで。

### 5.5 音声案内・マニューバ表示との同期 (本ドキュメント範囲外)

外部ナビ API ライブラリ由来の GP テキスト ("およそ 500m 先三軒寺左方向です" 等)
を、SDK の進捗情報 (`OnRoadSnappedLocationUpdatedListener` で得る road-snapped
位置) と組み合わせて発話する。ロジックは別仕様 (spec/21 派生) で詳細化する。

本ドキュメントでは **NewGuidanceManager は token chain と RefinedRoute の管理に
専念**、音声・マニューバは別 manager (例: `AnnouncementManager`) が
NavigationView の listener を別途購読する設計とする。

---

## 6. リルート

### 6.1 検知元

外部ナビ API ライブラリは GPS 監視を持たないため、リルート検知は **Navigation
SDK 側を正本とする**:

```kotlin
private val reroutingListener = ReroutingListener {
    launch { reroute() }
}
```

### 6.2 シーケンス

```
[GPS 逸脱]
   │
   ├─→ SDK が独自に逸脱検知 → 自動的にリルート計算開始
   │   (route_token 投入下でも SDK は勝手にリルートする。これは
   │    Google Navigation SDK の仕様。docs/spec/24 §0.1 参照)
   │
   ├─→ SDK が ReroutingListener.onReroutingRequestedByOffRoute() を発火
   │
   ▼
NewGuidanceManager.reroute()
   ├─ state = GuidanceState.Rerouting
   ├─ navigator.currentLocation を取得
   ├─ extNavRouteRepository.search(currentLocation, destination)
   │   (alternatives は最初の 1 本のみ採用 — リルート時は迷う UI を出さない)
   ├─ ExtNavRouteRefiner.refine() で全 chunk + token 取得
   ├─ navigator.setDestinations(chunk[0].waypoints, CustomRoutesOptions(chunk[0].token))
   │   ← この時点で SDK が独自に作っていたリルート結果を上書き
   ├─ activeChunkIndex = 0
   └─ state = GuidanceState.Guiding(0)
```

### 6.3 チラつきの許容

`ReroutingListener` 発火から `setDestinations` 上書き完了までの間 (~1-3 秒程度
想定)、SDK が独自計算した「正規ではないリルート結果」が一瞬地図上に表示され得る。

**この一瞬のチラつきは UX として許容する** (お兄ちゃんの決定)。

許容できなくなったら以下の代替案を検討:

- (A) `Navigator.clearDestinations()` で SDK のリルート進行を中断、外部ライブラリ
  の探索完了まで polyline 非表示。**ただし `clearDestinations` で auto-reroute
  が止まる保証は公式 docs に無い**。要実機検証
- (B) Custom Navigator 路線 (spec/22) に戻る。実装複雑度大

---

## 7. 目的地到達

```kotlin
private val arrivalListener = ArrivalListener { event ->
    navigator?.stopGuidance()
    detachListeners()
    _state.value = GuidanceState.Arrived
}
```

外部ナビ API ライブラリ側は到達検知を持たないため、SDK 側 `ArrivalListener` を
正本とする。`stopGuidance()` 呼び出し後、Navigator は次の `setDestinations` まで
idle 状態になる。

---

## 8. State Machine

```kotlin
@Immutable
internal sealed interface GuidanceState {
    data object Idle : GuidanceState

    @Immutable
    data class Guiding(val activeChunkIndex: Int) : GuidanceState

    @Immutable
    data class AdvancingChunk(val from: Int) : GuidanceState

    data object Rerouting : GuidanceState

    data object Arrived : GuidanceState

    @Immutable
    data class Failed(val message: String) : GuidanceState
}
```

遷移図:

```
                    ┌─────────────────────────────────┐
                    │                                 │
                    ▼                                 │
                  Idle ────startGuidance()──→ Guiding(0)
                    ▲                          │  │
                    │                          │  │
                    │ stopGuidance()           │  │
                    │                          │  │
            ┌───────┴──────────┐               │  │
            │                  │               │  │
        Arrived             Failed             │  │
            ▲                  ▲               │  │
            │                  │               │  │
            │              ┌───┴────┐          │  │
            │              │        │          │  │
            │       AdvancingChunk Rerouting   │  │
            │              ▲        ▲          │  │
            │              │        │          │  │
            │              │ 80%    │ off-route│  │
            │              │ 到達    │ 検知     │  │
            │              │        │          │  │
ArrivalListener           │        │          │  │
            │              └────────┴──────────┘  │
            │                       │              │
            └─────────── Guiding(n) ───────────────┘
```

---

## 9. データ構造

### 9.1 `RefinedRoute`

```kotlin
/**
 * 1 本の外部ルートを Routes API で再現した結果。chunk のリストを保持し、
 * 結合 polyline と全長距離を派生プロパティとして提供する。
 */
@Immutable
internal data class RefinedRoute(
    val chunks: ImmutableList<RefinedChunk>,
    val origin: LatLng,
    val destination: LatLng,
) {
    /** 全 chunk の polyline を結合した全体形状 */
    val mergedPolyline: ImmutableList<LatLng> by lazy {
        // chunk 境界は重複頂点になるので末尾 1 点を除いて結合
        chunks.flatMapIndexed { idx, chunk ->
            if (idx == chunks.lastIndex) chunk.polyline else chunk.polyline.dropLast(1)
        }.toImmutableList()
    }
    val totalDistanceMeters: Int = chunks.sumOf { it.distanceMeters }
    val totalDurationSeconds: Long = chunks.sumOf { it.durationSeconds }
}
```

### 9.2 `RefinedChunk`

```kotlin
/**
 * 1 chunk = 1 回分の Routes API 呼び出し結果。
 * waypoints / routeToken / polyline はセットで保持する (setDestinations 時に
 * 同時に必要)。
 */
@Immutable
internal data class RefinedChunk(
    val waypoints: ImmutableList<RoutesApiWaypoint>,
    val routeToken: String,
    val polyline: ImmutableList<LatLng>,
    val distanceMeters: Int,
    val durationSeconds: Long,
)
```

### 9.3 `RoutesApiWaypoint`

```kotlin
/**
 * Routes API の Waypoint 表現。spec/23 §7.2 と対応。
 *
 * @param heading 進行方向 (compass bearing 0-359)。null なら指定しない
 */
@Immutable
internal data class RoutesApiWaypoint(
    val latLng: LatLng,
    val heading: Int? = null,
)
```

---

## 10. NavigationView 連携

### 10.1 既存 `MapItem` の流用

現状 `MapItem.kt` は `NavigationView` を rememberNavigationViewWithLifecycle で
保持し、`getMapAsync` で `GoogleMap` を取得して `MapCameraState.attachMap()` に
渡している。

**追加で必要なこと**: `Navigator` インスタンスの取得を公開する。

```kotlin
// MapItem.kt に追加
@Composable
internal fun MapItem(
    googleMap: GoogleMap?,
    navigator: Navigator?,                       // ← 追加
    cameraState: MapCameraState,
    onMapUpdate: (GoogleMap?) -> Unit,
    onNavigatorUpdate: (Navigator?) -> Unit,     // ← 追加
    modifier: Modifier = Modifier,
) {
    val navigationView = rememberNavigationViewWithLifecycle()

    LaunchedEffect(navigationView) {
        // NavigationApi.getNavigator は Task<Navigator> を返す
        NavigationApi.getNavigator(activity, object : NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                onNavigatorUpdate(nav)
            }
            override fun onError(@ErrorCode code: Int) {
                onNavigatorUpdate(null)
            }
        })
    }

    ...
}
```

`activity` の取得は `LocalContext.current` を `findActivity()` で Activity に
キャスト。

`MapScreen.android.kt` 側で `var navigator by remember { mutableStateOf<Navigator?>(null) }`
を持ち、`MapItem` に渡し、`NewGuidanceManager.startGuidance(navigator!!, route)`
で利用する。

### 10.2 setDestinations / startGuidance のタイミング

| Screen state | setDestinations | startGuidance |
|---|---|---|
| Browsing | clear | stop (idle) |
| PlaceDetails | clear | stop |
| SearchResultsList | clear | stop |
| RoutePreview (selectedIndex 設定時) | selected.chunks[0] | **呼ばない** |
| Navigating (案内中) | active chunk | 開始済み |
| Arrived | clear | stop |

`MapScreen.android.kt` の `LaunchedEffect(screenState)` 内で上記遷移を実装。

---

## 11. エラーハンドリング

### 11.1 Routes API 失敗 (Preview 期)

`NewRouteManager.searchRoutes` の `runCatching` で全体を包む。1 alternative の
1 chunk でも失敗したら全体失敗扱い (`RoutePreviewState.Failed`)。

部分成功 (失敗した alternative だけ除外する) は **実装しない**。理由: 候補数が
減ると selectedIndex の整合性管理が複雑になり、レアケースに対する複雑度の
見合いが取れない。

### 11.2 token expired

Preview で取得した token が Guidance 開始までに expire する可能性がある
(spec/23 §0.1 / `Routes API` docs では明確な TTL 記載なし、「直後に使用推奨」
とのみ)。お兄ちゃんの方針で **再取得しない**:

- Preview の token をそのまま startGuidance で使用
- expire していたら `setDestinations` が失敗 → `GuidanceState.Failed`
- ユーザは Preview に戻ってルート再検索する

将来 TTL 検出と再取得を入れたくなったら、`startGuidance` の冒頭で「token 古い
ようなら refine し直す」ロジックを追加する余地は残してある (RefinedRoute に
取得時刻を記録する等)。

### 11.3 chunk 切替リトライ

§5.2.3 の通り、5 秒 → 10 秒 → 中止。

### 11.4 GPS 信号喪失

SDK 側の挙動に委ねる。本マネージャは特別なハンドリングをしない。SDK が信号復帰
時に勝手に再 snap する。

### 11.5 SDK 初期化失敗

`NavigationApi.getNavigator` が `onError` を返すケース (Mobility license 不正、
ネットワーク不可等)。`MapScreen` 側で navigator が null のまま remain → 案内
開始ボタンを disabled に。

---

## 12. テスト戦略

### 12.1 `ExtNavRouteRefiner` 単体テスト

dev tool (TypeScript) で確認した shakuji-tsukuba の inflation ~1.00x が Kotlin
実装で再現できることを fixture テストで確認する。

```
feature/map/src/androidUnitTest/kotlin/.../navigation/ExtNavRouteRefinerTest.kt
```

- `RoutesApiClient` を fake に差し替え
- shakuji-tsukuba の extPolyline (geojson から生成した固定 List<LatLng>) を入力
- `samplePolylineWaypoints(targetGap=4000.0)` の出力数 / 累積距離分布をスナップ
  ショット
- `chunkWaypoints(intermediateMax=25)` の chunk 数 / 境界 index を assert

fixture: `feature/map/src/androidUnitTest/resources/fixtures/shakuji-tsukuba-polyline.json`
(dev tool の geojson を WGS84 変換済みの形で簡易化したもの)

### 12.2 `NewGuidanceManager` 単体テスト

`Navigator` を mock (`mockk`) に差し替えて state 遷移を検証。

ケース:

- 初期状態 Idle → `startGuidance(nav, route)` → `Guiding(0)`
- `Guiding(0)` で残距離 80% → `AdvancingChunk(0)` → `setDestinations` 成功 → `Guiding(1)`
- `Guiding(0)` で残距離 80% → `setDestinations` 失敗 → 5 秒後再試行 → 成功 → `Guiding(1)`
- `Guiding(0)` で setDestinations 3 回失敗 → `Failed`
- `Guiding(1)` で `ReroutingListener` 発火 → `Rerouting` → 新 route で `Guiding(0)`
- `Guiding(n)` で `ArrivalListener` 発火 → `Arrived`
- 重複起動防止: 残距離 80% を超えた状態が連続更新されても `advanceToNextChunk`
  が 1 回しか走らない

### 12.3 `NewRouteManager` 単体テスト

- 全候補 refine 成功 → `Ready(routes, selectedIndex=0)`
- `selectRoute(2)` → `Ready(routes, selectedIndex=2)`
- 1 候補でも refine 失敗 → `Failed`

### 12.4 統合テスト (実機)

- shakuji-tsukuba ルートを実機ドライブシミュレーションで案内
- chunk 切替が起きるかどうか目視
- リルート (意図的な逸脱) で setDestinations 上書きが起きるか目視
- 目的地到達で `Arrived` 状態になるか確認

実機テストは再現性が低いので CI には乗せない。手動 QA リスト化。

---

## 13. 実装フェーズ分割

### Phase 1: 純粋ロジック層

- `PolylineDecoder.kt` (1 ファイル ~50 行)
- `ExtNavRouteRefiner.kt` (純粋関数、Routes API 呼び出しは interface 経由)
- `RoutesApiClient.kt` interface
- `DefaultRoutesApiClient.kt` (Ktor 実装、BuildKonfig 経由 API key)
- model 群 (`RefinedRoute`, `RefinedChunk`, `RoutesApiWaypoint`)
- 単体テスト (§12.1)

### Phase 2: 状態管理層

- `NewRouteManager.kt`
- `RoutePreviewState.kt`
- 単体テスト (§12.3)

### Phase 3: SDK 接合層

- `MapItem.kt` 改修 (`Navigator` 取得 API 追加)
- `NewGuidanceManager.kt`
- `GuidanceState.kt`
- 単体テスト (§12.2)

### Phase 4: UI 接合

- `MapScreen.android.kt` で新 manager を購読
- `MapScreenContent` の `RoutePreview` 分岐で全候補自前 polyline 描画 +
  selected の SDK 投入
- `Navigating` 分岐で全 chunk 薄レイヤー + SDK
- 既存 `MapPolyline` / `MapMarker` の流用または改修

### Phase 5: 実機検証

- shakuji-tsukuba ドライブシミュレーション
- 都市部での chunk 切替挙動
- リルート挙動

各 Phase 完了時に `./gradlew assembleDebug --no-configuration-cache` で
ビルド確認。`make detekt` で lint 確認。

---

## 14. 命名ポリシーと制約

### 14.1 N 社名露出禁止 (`CLAUDE.md` §厳命)

本ドキュメント・新規実装ファイル中で N 社の事業者名・製品名・パッケージ名・
ドメイン名を **一切露出させない**。代替表記:

- 事業者: 「N 社」
- ライブラリ: 「外部ナビ API ライブラリ」
- クラス prefix: `ExtNav`
- BuildKonfig: `EXT_NAV_*`

### 14.2 `New` prefix の運用

旧実装と並走させるための一時 prefix。

- `NewRouteManager` / `NewGuidanceManager`
- 旧実装削除と同時に prefix を外す PR を別途行う

### 14.3 Compose 安定性

- `RoutePreviewState` / `GuidanceState` は `@Immutable` を付与
- `RefinedRoute` / `RefinedChunk` / `RoutesApiWaypoint` も `@Immutable`
- collection は `kotlinx.collections.immutable` の `ImmutableList`

### 14.4 Composable 命名 (新規 UI を追加する場合)

- 親 Screen の prefix を引き継ぐ (`MapRoutePreview*`, `MapNavigation*` 等)
- private fun デフォルト
- `modifier: Modifier = Modifier` を必ず含める

---

## 15. 残課題・既知の制約

### 15.1 token TTL の不明確さ

公式 docs に明確な TTL 記載なし。実機で expire を観測したら本ドキュメントに
追記し、Preview→Guidance 遷移時の token 再取得ロジックを §11.2 に追加する。

### 15.2 SDK 自動リルートの抑制不可

`route_token` を投入してもなお SDK は逸脱時に自動リルートする (§6.3)。完全に
止める公式 API は確認できなかった。チラつき許容で運用する。

### 15.3 `ReroutingListener` の発火タイミング不明確

公式 docs 上、コールバックが SDK のリルート計算「前 / 後」かが明記されていない。
本設計では「呼ばれた時点で SDK は既に独自計算を始めている可能性がある」と保守的
に仮定し、`setDestinations` 上書き戦略を取る。

### 15.4 `Navigator.getNavigator` が Activity context 必須

Compose の `LocalContext` から `findActivity()` でキャストする必要がある。
NavigationApi の初期化が非同期なので、`navigator: Navigator?` の null 時間
窓を UI 側で吸収する設計。

### 15.5 `setDestinations(waypoints, customRoutesOptions)` の waypoints 一致制約

token 取得時の Routes API 呼び出しの waypoints と、Navigator に渡す waypoints は
**一致する必要がある**。本設計では `RefinedChunk.waypoints` をそのまま渡すので
自動的に一致するが、後で waypoint をフィルタしたり加工したりすると崩れる。
変更時は注意。

### 15.6 Preview ロード UI の未実装

全 alternative × 全 chunk の Routes API 呼び出し完了まで画面に何も出さない方針
(§4.4)。お兄ちゃんが後ほどローディング UI を別途追加する。

### 15.7 dev tool との fixture 共有

spec/23 dev tool の TypeScript 実装と Kotlin 実装が同じ動作をすることを保証する
仕組みは未整備。Phase 1 の単体テストで shakuji-tsukuba の expected 出力を共有
fixture (`shakuji-tsukuba-polyline.json` 等) で押さえることで近似する。

---

## 16. 参考リンク

- `docs/spec/18_external_nav_api_migration_plan.md` — 全体の移行計画
- `docs/spec/19_drive_supporter_api_integration_plan.md` — 外部ライブラリ統合計画
- `docs/spec/22_route_token_and_custom_navigator_evaluation.md` — Custom Navigator
  評価 (本設計が採用しなかった代替案)
- `docs/spec/23_route_compare_dev_tool.md` — 本設計が前提とする refine
  アルゴリズムの正本仕様
- Google Routes API v2: <https://developers.google.com/maps/documentation/routes>
- Google Navigation SDK Android `Navigator`:
  <https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/libraries/navigation/Navigator>
- Google Navigation SDK Android `CustomRoutesOptions.Builder`:
  <https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/libraries/navigation/CustomRoutesOptions.Builder>
- Google Navigation SDK Android `ReroutingListener`:
  <https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/libraries/navigation/Navigator.ReroutingListener>
