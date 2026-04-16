# 16. ターンバイターンナビゲーション フロー詳細

> 2026-04-16 更新:
> この文書の多くは Mapbox ベース実装時のフローを記述している。
> 現在の Android head は Google Routes API + 独自 guidance に移行しており、
> レーン案内と道路名抽出、Android Auto はこの仕様どおりには未実装である。

## 概要

本ドキュメントでは、`OnNavigationStarted` イベントの発火からナビゲーション走行中、`OnNavigationStopped` による終了までの処理フローを解説する。

### 関連ファイル一覧

| ファイル | 役割 |
|---|---|
| `feature/home/.../HomeMapViewEvent.kt` | UI イベント定義（`OnNavigationStarted`, `OnNavigationStopped`） |
| `feature/home/.../HomeMapViewModel.kt` | イベントハンドリング・各 Manager の呼び出し |
| `core/navigation/.../GuidanceSessionManager.kt` | セッションライフサイクル・Observer 管理・TTS |
| `core/navigation/.../RouteManager.kt` | ルートデータの一元管理 |
| `core/navigation/.../CameraManager.kt` | カメラ制御・位置情報管理 |
| `core/navigation/.../JapaneseAnnouncementGenerator.kt` | 日本語音声案内テンプレート |
| `core/model/.../NavigationState.kt` | ナビゲーション状態の定義 |
| `core/model/.../GuidanceUiState.kt` | ナビ中 UI 表示データ |
| `core/model/.../ManeuverInfo.kt` | マニューバ情報 |
| `core/model/.../TripProgressInfo.kt` | トリップ進捗情報 |
| `core/model/.../ArrivalInfo.kt` | 到着時集計情報 |

---

## 1. ナビゲーション状態モデル

`NavigationState` はナビゲーション画面全体の状態を表す sealed interface。各状態は `data object`（マーカー）とし、状態固有のリアルタイムデータ（`GuidanceUiState` 等）は別の `StateFlow` で管理する。これにより `RouteProgress` の毎秒更新で `NavigationState` が変わらず、不要な再コンポーズを防ぐ。

```
Browsing → Search → RoutePreview → ActiveGuidance → Arrival → Browsing
```

| 状態 | 説明 |
|---|---|
| `Browsing` | 地図ブラウジング中 |
| `Search` | 検索結果表示中 |
| `RoutePreview` | ルートプレビュー中（候補表示） |
| `ActiveGuidance` | ターンバイターンナビゲーション中 |
| `Arrival` | 目的地到着 |
| `FreeDrive` | フリードライブモード（Phase 1 未使用） |

---

## 2. ナビゲーション開始 (`OnNavigationStarted`)

### 2.1 イベント発火

UI 層で「ナビ開始」ボタンがタップされると `HomeMapViewEvent.OnNavigationStarted` が発火する。

```kotlin
// HomeMapViewEvent.kt
data object OnNavigationStarted : HomeMapViewEvent
```

### 2.2 ViewModel での処理

`HomeMapViewModel.onNavigationStarted()` が 2 つの処理を実行する。

```kotlin
// HomeMapViewModel.kt:128-131
private fun onNavigationStarted() {
    guidanceSessionManager.startSession()           // ① セッション開始
    cameraManager.requestCameraFollowing(pitch3D = true)  // ② 3D カメラ追従
}
```

### 2.3 `GuidanceSessionManager.startSession()` の詳細

ナビゲーションセッションの中核処理。以下を順に実行する。

```kotlin
// GuidanceSessionManager.kt:165-183
fun startSession() {
    val navigation = mapboxNavigation ?: return

    sessionStartTimeMillis = System.currentTimeMillis()  // 走行記録用タイムスタンプ
    lastRouteProgress = null

    // ① フォアグラウンドサービス付き TripSession を開始
    navigation.startTripSession(withForegroundService = true)

    // ② 5 つの Observer を登録
    navigation.registerRouteProgressObserver(routeProgressObserver)
    navigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
    navigation.registerBannerInstructionsObserver(bannerInstructionsObserver)
    navigation.registerOffRouteObserver(offRouteObserver)
    navigation.registerArrivalObserver(arrivalObserver)

    // ③ TTS エンジンを初期化
    initializeTts()

    // ④ 状態を ActiveGuidance に遷移
    _navigationState.value = NavigationState.ActiveGuidance
    _guidanceUiState.value = GuidanceUiState.Initial.copy(isTtsAvailable = false)
}
```

**ポイント:**

- `startTripSession(withForegroundService = true)` により、バックグラウンドでもナビが継続する
- TTS 初期化は非同期で完了し、準備完了後に「音声案内を開始します。実際の交通規制に従って、走行してください。」と発話する
- `GuidanceUiState.Initial` で UI 状態をリセットしてから開始する

---

## 3. 走行中に動作する Observer 群

`startSession()` で登録される 5 つの Observer が、ナビゲーション中のリアルタイム処理を担う。加えて `CameraManager` が持つ `LocationObserver` も常時動作する。

### 3.1 `RouteProgressObserver` — 進捗監視（約 1 Hz）

**最も重要な Observer。** Mapbox SDK が位置情報のマップマッチング結果を約 1 秒ごとにコールバックする。

```kotlin
// GuidanceSessionManager.kt:74-79
private val routeProgressObserver = RouteProgressObserver { routeProgress ->
    lastRouteProgress = routeProgress
    updateGuidanceUiState(routeProgress)            // (a) UI 状態を更新
    cameraManager.onRouteProgressChanged(routeProgress)  // (b) カメラ位置を更新
    onRouteProgressForRouteLine?.invoke(routeProgress)   // (c) ルートライン消費アニメーション
}
```

#### (a) `updateGuidanceUiState()` の処理

`RouteProgress` から以下を抽出し、`GuidanceUiState` の `StateFlow` を更新する。

```kotlin
// GuidanceSessionManager.kt:300-338
private fun updateGuidanceUiState(routeProgress: RouteProgress) {
    // 現在のマニューバ（次の曲がり角）
    val currentManeuver = currentStep?.maneuver()?.let { maneuver ->
        ManeuverInfo(
            type = maneuver.type().orEmpty(),        // "turn", "fork", "merge" 等
            modifier = maneuver.modifier(),           // "left", "right", "slight left" 等
            distanceMeters = currentStepProgress.distanceRemaining.toDouble(),
            instruction = currentStep.name().orEmpty(),  // 交差点名
        )
    }

    // 次のマニューバ（先読み）
    val nextManeuver = currentLegProgress?.upcomingStep?.maneuver()?.let { ... }

    // トリップ進捗
    val tripProgress = TripProgressInfo(
        distanceRemainingMeters = routeProgress.distanceRemaining.toDouble(),
        durationRemainingSeconds = routeProgress.durationRemaining,
        estimatedArrivalTimeMillis = System.currentTimeMillis() + (routeProgress.durationRemaining * 1000).toLong(),
    )

    // 現在の道路名 + ロケーション鮮度
    _guidanceUiState.value = _guidanceUiState.value.copy(
        currentManeuver = currentManeuver,
        nextManeuver = nextManeuver,
        tripProgress = tripProgress,
        currentRoadName = currentStep?.name()?.takeIf { it.isNotBlank() },
        isLocationStale = routeProgress.stale,
    )
}
```

#### (b) カメラ位置の更新

```kotlin
// CameraManager.kt:145-148
fun onRouteProgressChanged(routeProgress: RouteProgress) {
    viewportDataSource?.onRouteProgressChanged(routeProgress)
    viewportDataSource?.evaluate()  // カメラ位置を再計算
}
```

#### (c) ルートライン消費アニメーション

UI 層から設定されるコールバック。走った部分のルートラインを消していく処理に使用される。

---

### 3.2 `VoiceInstructionsObserver` — 音声案内

曲がり角に接近すると Mapbox SDK が距離に応じて複数回コールバックする（例：「300メートル先、左折です」→「まもなく左折です」）。

```kotlin
// GuidanceSessionManager.kt:81-88
private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
    val announcement = voiceInstructions.announcement()
    if (announcement != null) {
        speakTts(
            generateJapaneseAnnouncement(voiceInstructions) ?: announcement,
        )
    }
}
```

**処理フロー:**

1. Mapbox SDK が英語ベースの `VoiceInstructions` を生成
2. `JapaneseAnnouncementGenerator.generate()` が日本語テンプレートに変換を試みる
3. 変換成功 → 日本語テキストを TTS で読み上げ
4. 変換失敗（テンプレート非該当） → Mapbox デフォルトの announcement をそのまま読み上げ

#### 日本語テンプレート一覧（`JapaneseAnnouncementGenerator`）

17 種類のテンプレートで日本語音声案内を生成する。

| # | 条件 | 案内テキスト例 |
|---|---|---|
| 1 | 左折（距離あり） | `"300メートル先、左折です"` |
| 2 | 右折（距離あり） | `"300メートル先、右折です"` |
| 3 | 斜め左（まもなく） | `"まもなく斜め左です"` |
| 4 | 斜め右（まもなく） | `"まもなく斜め右です"` |
| 5 | 左折（まもなく） | `"まもなく左折です"` |
| 6 | 右折（まもなく） | `"まもなく右折です"` |
| 7 | 直進 | `"直進です"` |
| 8 | 合流 | `"合流です、ご注意ください"` |
| 9 | 分岐左 | `"左方向です"` |
| 10 | 分岐右 | `"右方向です"` |
| 11 | 出口 | `"◯◯方面、出口です"` |
| 12 | 最終目的地到着 | `"目的地に到着しました"` |
| 13 | 経由地到着 | `"経由地に到着しました"` |
| 15 | U ターン | `"まもなくUターンです"` / `"◯◯先、Uターンです"` |
| 16 | 鋭角左折 | `"まもなく鋭角左折です"` / `"◯◯先、鋭角左折です"` |
| 17 | 鋭角右折 | `"まもなく鋭角右折です"` / `"◯◯先、鋭角右折です"` |

**距離の読み上げフォーマット:**

- 1km 未満: `"300メートル"` (整数)
- 1km 以上: `"2.3キロ"` (小数点1桁)
- 端数なし: `"2キロ"` (2.05km → `"2キロ"`)

**「まもなく」の閾値:** `distanceAlongGeometry < 100m`

---

### 3.3 `BannerInstructionsObserver` — 画面表示テキスト更新

音声案内とは別に、マニューバパネルに表示する交差点名・JCT 名を更新する。

```kotlin
// GuidanceSessionManager.kt:90-92
private val bannerInstructionsObserver = BannerInstructionsObserver { bannerInstructions ->
    updateManeuverFromBanner(bannerInstructions)
}
```

```kotlin
// GuidanceSessionManager.kt:341-351
private fun updateManeuverFromBanner(bannerInstructions: BannerInstructions) {
    val primary = bannerInstructions.primary()
    val instruction = primary.text()

    val currentManeuver = _guidanceUiState.value.currentManeuver
    if (currentManeuver != null && instruction.isNotEmpty()) {
        _guidanceUiState.value = _guidanceUiState.value.copy(
            currentManeuver = currentManeuver.copy(instruction = instruction),
        )
    }
}
```

`RouteProgressObserver` が距離を更新し、`BannerInstructionsObserver` がテキストを更新する、という分担になっている。

---

### 3.4 `OffRouteObserver` — ルート逸脱検知

ユーザーがルートから外れたことを検知する。

```kotlin
// GuidanceSessionManager.kt:94-99
private val offRouteObserver = OffRouteObserver { isOffRoute ->
    _guidanceUiState.value = _guidanceUiState.value.copy(isOffRoute = isOffRoute)
    if (isOffRoute) {
        Napier.d(tag = TAG) { "Off route detected, waiting for reroute..." }
    }
}
```

**リルートの仕組み:**

1. `OffRouteObserver` が `isOffRoute = true` を検知 → UI に反映
2. Mapbox SDK が**自動的にリルート**を実行
3. `RouteManager` の `RoutesObserver` が新しいルートを受信

```kotlin
// RouteManager.kt:43-56
private val routesObserver = object : RoutesObserver {
    override fun onRoutesChanged(result: RoutesUpdatedResult) {
        val newRoutes = result.navigationRoutes
        val newIds = newRoutes.map { it.id }

        if (newIds != lastRouteIds) {
            lastRouteIds = newIds
            _routes.value = newRoutes  // 自動で新ルートに切り替わる
            _alternativesMetadata.value = mapboxNavigation
                ?.getAlternativeMetadataFor(newRoutes)
                .orEmpty()
        }
    }
}
```

4. ルート変更 → ルートライン再描画・カメラ更新・進捗リセットが自動で行われる

---

### 3.5 `ArrivalObserver` — 到着検知

経由地と最終目的地の 2 種類の到着を処理する。

```kotlin
// GuidanceSessionManager.kt:101-131
private val arrivalObserver = object : ArrivalObserver {
    // 経由地到着
    override fun onWaypointArrival(routeProgress: RouteProgress) {
        speakTts("経由地に到着しました")
        mapboxNavigation?.navigateNextRouteLeg {
            Napier.d(tag = TAG) { "Navigating to next route leg: index=$it" }
        }
    }

    override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {
        Napier.d(tag = TAG) { "Next route leg started" }
    }

    // 最終目的地到着
    override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
        val elapsedSeconds = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000.0
        val distanceTraveled = routeProgress.distanceTraveled.toDouble()
        val destinationName = routeProgress.navigationRoute.waypoints
            ?.lastOrNull()?.name().orEmpty()

        _arrivalInfo.value = ArrivalInfo(
            destinationName = destinationName,
            totalDistanceMeters = distanceTraveled,
            totalDurationSeconds = elapsedSeconds,
        )

        speakTts("目的地に到着しました")
        _navigationState.value = NavigationState.Arrival
    }
}
```

**経由地到着時:** TTS で案内 → `navigateNextRouteLeg()` で次の区間に自動進行
**最終目的地到着時:** 走行統計を `ArrivalInfo` に集計 → TTS で案内 → `NavigationState.Arrival` に遷移

---

### 3.6 `LocationObserver`（CameraManager 所有）— 位置追跡

`CameraManager` が `MapboxNavigationObserver` 経由で常時登録している。`startSession()` とは独立して動作する。

```kotlin
// CameraManager.kt:57-76
private val locationObserver = object : LocationObserver {
    override fun onNewRawLocation(rawLocation: Location) {
        // raw location は使わない、enhanced location を優先
    }

    override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
        val enhancedLocation = locationMatcherResult.enhancedLocation

        // ① 車アイコン（puck）の位置を更新
        navigationLocationProvider.changePosition(
            location = enhancedLocation,
            keyPoints = locationMatcherResult.keyPoints,
        )

        // ② カメラ追従の計算を更新
        viewportDataSource?.onLocationChanged(enhancedLocation)
        viewportDataSource?.evaluate()

        // ③ 現在地の StateFlow を更新
        _currentLocation.value = Point.fromLngLat(
            enhancedLocation.longitude,
            enhancedLocation.latitude,
        )
    }
}
```

**enhanced location** は Mapbox SDK がマップマッチング処理を行った補正済み位置。道路上にスナップされるため、生の GPS よりも正確。

---

## 4. カメラ制御

### 4.1 カメラモード

| モード | 説明 | pitch |
|---|---|---|
| Following 3D | 進行方向を上にして斜め視点で追従（ナビ中のデフォルト） | 45° |
| Following 2D | 真上から北固定で追従 | 0° |
| Overview | ルート全体を表示 | — |
| Idle | ユーザーが手動でマップ操作中 | — |

### 4.2 ユーザー操作時の遷移

`NavigationBasicGesturesHandler` により、ユーザーがマップをパン/ズームすると自動的に Idle モードに遷移する。UI 上に「現在地に戻る」ボタンが表示され、タップで Following モードに復帰する。

### 4.3 コンパストグル

```kotlin
// CameraManager.kt:182-184
fun toggleCompass() {
    requestCameraFollowing(pitch3D = !_isFollowing3D.value)
}
```

3D（45°）と 2D（0°）を切り替える。

---

## 5. TTS（Text-to-Speech）

### 5.1 初期化

```kotlin
// GuidanceSessionManager.kt:217-248
private fun initializeTts() {
    audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.JAPAN)
            isTtsReady = result != LANG_MISSING_DATA && result != LANG_NOT_SUPPORTED
            if (isTtsReady) {
                speakTts(START_ANNOUNCEMENT)  // "音声案内を開始します。..."
            }
        }
    }

    // 読み上げ完了時にオーディオフォーカスを解放
    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onDone(utteranceId: String?) { releaseAudioFocus() }
        override fun onError(utteranceId: String?) { releaseAudioFocus() }
        override fun onStart(utteranceId: String?) = Unit
    })
}
```

### 5.2 発話処理

```kotlin
// GuidanceSessionManager.kt:258-270
private fun speakTts(text: String) {
    if (!isTtsReady) return
    requestAudioFocus()                        // オーディオフォーカス取得
    tts?.stop()                                 // 前の発話をキャンセル
    tts?.speak(text, QUEUE_FLUSH, null, UUID.randomUUID().toString())
}
```

**オーディオフォーカス:**

- `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` で取得（音楽等の音量を一時的に下げる）
- `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `CONTENT_TYPE_SPEECH` のオーディオ属性
- 発話完了後に `abandonAudioFocusRequest()` で解放

---

## 6. データモデル

### 6.1 `GuidanceUiState`

ナビ中の UI 表示に必要な全情報を集約する。`GuidanceSessionManager` が Observer 群から収集し、`StateFlow` で公開する。

```kotlin
@Immutable
data class GuidanceUiState(
    val currentManeuver: ManeuverInfo?,   // 次の曲がり角
    val nextManeuver: ManeuverInfo?,      // その次の曲がり角（先読み）
    val tripProgress: TripProgressInfo,   // 残り距離・時間・ETA
    val currentRoadName: String?,         // 現在の道路名。Google Routes API 実装では通常 null
    val isOffRoute: Boolean,              // ルート逸脱中か
    val isTtsAvailable: Boolean,          // TTS 利用可能か
    val isLocationStale: Boolean,         // GPS 信号ロスト中か
)
```

### 6.2 `ManeuverInfo`

```kotlin
@Immutable
data class ManeuverInfo(
    val type: String,          // "turn", "fork", "merge", "on ramp", "off ramp", "arrive" 等
    val modifier: String?,     // "left", "right", "slight left", "sharp right", "straight", "uturn" 等
    val distanceMeters: Double, // 次のマニューバまでの残り距離（メートル）
    val instruction: String,   // 交差点名 / JCT 名 / Google が生成した案内文
)
```

### 6.3 `TripProgressInfo`

```kotlin
@Immutable
data class TripProgressInfo(
    val distanceRemainingMeters: Double,   // 残り距離（メートル）
    val durationRemainingSeconds: Double,  // 残り時間（秒）
    val estimatedArrivalTimeMillis: Long,  // 到着予想時刻（エポックミリ秒）
)
```

### 6.4 `ArrivalInfo`

```kotlin
@Immutable
data class ArrivalInfo(
    val destinationName: String,       // 目的地名
    val totalDistanceMeters: Double,   // 総走行距離（メートル）
    val totalDurationSeconds: Double,  // 総走行時間（秒）
)
```

---

## 7. ナビゲーション停止 (`OnNavigationStopped`)

### 7.1 トリガー

以下のいずれかで `HomeMapViewEvent.OnNavigationStopped` が発火する：

- ナビ中のトリップカードの「停止」ボタン
- 到着画面の「閉じる」ボタン（10 秒で自動消去）

### 7.2 状態遷移の方針

ナビゲーション停止時は **ルート選択画面（`RoutePreview`）に復帰** する。ルートやウェイポイントはクリアせず、ユーザーが別のルートを選んだり再度ナビを開始したりできるようにする。ルートを完全に破棄したい場合は、ルート選択画面の「戻る」操作（`OnDismissRoutes`）で行う。

```
ActiveGuidance / Arrival → (OnNavigationStopped) → RoutePreview
RoutePreview → (OnDismissRoutes) → Browsing（ルート・ウェイポイント全クリア）
```

### 7.3 ViewModel での処理

```kotlin
// HomeMapViewModel.kt
private fun onNavigationStopped() {
    guidanceSessionManager.stopSession()                              // ① セッション停止
    guidanceSessionManager.setNavigationState(NavigationState.RoutePreview)  // ② RoutePreview に復帰
    cameraManager.requestCameraOverview()                             // ③ ルート全体を表示するカメラに切替
}
```

**ポイント:**

- ルート（`routeManager`）・ウェイポイント（`_waypoints`）・検索結果（`_routeResults`）はクリアしない
- `NavigationState.RoutePreview` への遷移により、UI 層で BottomSheet がルート選択画面として復元される
- カメラは Overview モードに切り替え、ルート全体を見渡せるようにする

### 7.4 `GuidanceSessionManager.stopSession()` の詳細

```kotlin
// GuidanceSessionManager.kt:189-206
fun stopSession() {
    val navigation = mapboxNavigation ?: return

    // 全 Observer を解除
    navigation.unregisterRouteProgressObserver(routeProgressObserver)
    navigation.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
    navigation.unregisterBannerInstructionsObserver(bannerInstructionsObserver)
    navigation.unregisterOffRouteObserver(offRouteObserver)
    navigation.unregisterArrivalObserver(arrivalObserver)

    // TripSession を停止（フォアグラウンドサービスも終了）
    navigation.stopTripSession()

    // TTS を破棄
    releaseTts()

    // 状態をリセット
    _guidanceUiState.value = GuidanceUiState.Initial
    _arrivalInfo.value = null
    lastRouteProgress = null
    onRouteProgressForRouteLine = null
}
```

---

## 8. データフロー全体図

```
Mapbox Navigation SDK（位置マッチング ~1Hz）
    │
    ├─→ RouteProgressObserver
    │       ├─→ updateGuidanceUiState()  →  GuidanceUiState (StateFlow)  →  UI 再描画
    │       ├─→ cameraManager.onRouteProgressChanged()  →  カメラ位置更新
    │       └─→ onRouteProgressForRouteLine()  →  ルートライン消費アニメーション
    │
    ├─→ VoiceInstructionsObserver
    │       └─→ JapaneseAnnouncementGenerator.generate()  →  TTS 読み上げ
    │
    ├─→ BannerInstructionsObserver
    │       └─→ updateManeuverFromBanner()  →  ManeuverInfo.instruction 更新
    │
    ├─→ OffRouteObserver
    │       └─→ isOffRoute 更新  →  Mapbox SDK 自動リルート  →  RoutesObserver
    │
    ├─→ ArrivalObserver
    │       ├─→ 経由地: TTS + navigateNextRouteLeg()
    │       └─→ 最終: ArrivalInfo 集計 + TTS + NavigationState.Arrival
    │
    └─→ LocationObserver（CameraManager）
            ├─→ navigationLocationProvider  →  車アイコン移動
            ├─→ viewportDataSource  →  カメラ追従計算
            └─→ currentLocation (StateFlow)
```

---

## 9. UI コンポーネント（ナビ中に表示）

| コンポーネント | 配置 | 内容 |
|---|---|---|
| `HomeMapGuidanceManeuverPanel` | 画面上部 | マニューバ矢印 + 距離 + 交差点名 + 次のマニューバプレビュー |
| `HomeMapGuidanceTripCard` | 画面下部 | 残り時間・距離・ETA + 操作ボタン（停止・概観・経由地追加） |
| `HomeMapGuidanceControlColumn` | 画面右端 | 設定・音量・コンパス（2D/3D 切替）・ズーム |
| `HomeMapGuidanceRoadNameBadge` | 左下 | 走行中の道路名 |
| `HomeMapGuidanceReturnButton` | 下中央 | カメラ Idle 時のみ表示、タップで Following 復帰 |
| `HomeMapGuidanceArrivalScreen` | 中央オーバーレイ | 到着時表示（目的地名・走行統計・10 秒自動消去） |

全コンポーネントは `GuidanceUiState` の `StateFlow` を `collectAsState()` で監視し、約 1 秒ごとに更新される。

---

## 10. ルート管理（`RouteManager`）

`RouteManager` はルートデータの single source of truth。`RoutesObserver` で全てのルート変更（初回設定・リルート・トラフィックリフレッシュ・代替ルート探索）を一元受信する。

### 主要な StateFlow

| StateFlow | 型 | 説明 |
|---|---|---|
| `routes` | `List<NavigationRoute>` | 現在のルート一覧 |
| `selectedRouteIndex` | `Int` | 選択中のルートインデックス |
| `alternativesMetadata` | `List<AlternativeRouteMetadata>` | 代替ルートの重複部分メタデータ |

### ルートの選択と並び替え

Mapbox SDK は先頭のルートをプライマリとして描画するため、`reorderedRoutes()` で選択ルートを先頭に並び替える。

```kotlin
// RouteManager.kt:91-101
fun reorderedRoutes(): List<NavigationRoute> {
    val current = _routes.value
    val primaryIndex = _selectedRouteIndex.value
    if (primaryIndex !in current.indices) return current
    return buildList {
        add(current[primaryIndex])
        current.forEachIndexed { index, route ->
            if (index != primaryIndex) add(route)
        }
    }
}
```

---

## 11. DI 構成

3 つの Manager は全て Koin の `single`（シングルトン）として提供される。

```kotlin
// NavigationModule.kt
actual val navigationModule: Module = module {
    single { RouteManager() }
    single { CameraManager() }
    single { GuidanceSessionManager(androidContext(), get()) }
}
```

ライフサイクルは Activity ではなくアプリケーションに紐づく。`MapboxNavigationApp.registerObserver()` / `unregisterObserver()` で Mapbox SDK とのバインドを管理する。
