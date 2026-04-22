# 18. 外部ナビ API 移行計画

> **作成日:** 2026-04-22
> **ステータス:** ドラフト（レビュー待ち）
> **対象:** ルート検索・turn-by-turn 案内源を **外部ナビ API**（N 社の某カーナビアプリを参考にした第三者実装のライブラリ / 別管理のプライベートリポジトリ）に移譲し、地図描画は Google Maps SDK (NavigationView) を維持する方針への大規模計画変更

> **用語:** 本ドキュメントでは実在事業者名・製品名を直接書かず、当該事業者を「**N 社**」、当該ライブラリを「**外部ナビ API ライブラリ**」と総称する。API 仕様・解析資料・認証情報はすべて当該プライベートリポジトリ側に閉じ込め、本リポジトリ（OneNavi）側には実名・エンドポイント・プロトコル詳細を持ち込まない。

---

## 0. 本ドキュメントの位置付け

07_phased_roadmap / 13_navigation_screen_design / 16_turn_by_turn_navigation_flow は
「Mapbox → Google Routes + 自前 guidance」移行までを前提としていた。
今回、別管理のプライベートリポジトリで **外部ナビ API** を叩く Android ライブラリを自前で
仕上げ、合法性リスクを自己責任の範囲に閉じ込めた上で「ルート検索 / turn-by-turn 案内 /
方面看板・交差点画像 / 交通情報」を **N 社相当の外部ナビ API** に委譲する方向へ舵を切る。

本ドキュメントは以降の実装計画の**正本**となり、これ以前の Mapbox / 自前音声テンプレ
関連の設計は「既定方針の再定義」として上書きする。既存ドキュメント (08 Decision Log
等) は残したうえで、本計画で決めた判断を D-100 番台として追記する。

---

## 1. 方針サマリ

### 1.1 責務分担

| レイヤ | 担当 | 備考 |
|---|---|---|
| ルート検索（候補取得・料金・所要時間・距離） | 外部ナビ API `RouteClient.search()` | ルート検索エンドポイント（JSON）|
| turn-by-turn 案内データ（音声テキスト・交差点・方面看板・GP シーケンス・ポリライン） | 外部ナビ API `GuidanceClient.resolveGuidance()` | 案内バイナリエンドポイント（protobuf）|
| 案内画像（青看板・高速 3D・料金所） | 外部ナビ API `GuideImageClient.preload()` | 画像 fixdata エンドポイント |
| 交通情報（渋滞・規制・事故） | 外部ナビ API `TrafficClient` | 交通情報エンドポイント群 |
| 地図描画（ベクタータイル・POI・信号/停止線・建物・ダーク切替） | Google Maps SDK（`NavigationView` が内包する `GoogleMap`） | 現状維持 |
| 自車位置（map-matched） | Google Navigation SDK `RoadSnappedLocationProvider` | SDK 初期化だけ残し、Navigator では setDestinations しない |
| カメラ追従（chevron / tilted / overview） | `GoogleMap.followMyLocation()` | 現状維持 |
| ルートライン・代替ルート・Callout・ピン | 自前（`GoogleMap.addPolyline` / `CalloutLayer`） | 現状維持 |
| 音声案内の発話 | 自前 TTS エンジン（Android TTS / Google Cloud TTS Chirp 3 HD） | テキスト組み立ては外部ナビ API の SSML をそのまま利用 |
| ナビ状態マシン・セッション管理 | 自前（`GuidanceSessionManager` の書き換え） | Navigator には依存しない |
| リルート判定・到着判定・経由地通過判定 | 自前 | 外部ナビ API 再検索を自前で走らせる |

### 1.2 「Google Navigation SDK をやめない」理由

- `NavigationView` は通常の `MapView` より
  - 信号機・一時停止線・横断歩道レイヤ
  - 3D 建物の美しさ
  - `TrafficIncidentCards`（Navigator 非依存で使える）
  - `ForceNightMode` / ナビ向けにチューニングされた map style
  - `setCustomControl(View, CustomControlPosition)` によるカスタム View オーバーレイ
    （§1.4 で活用。NavigationView 唯一の拡張点）
  - `RoadSnappedLocationProvider` による map matching（Navigator 初期化が前提。§1.6 Q-102）
  を標準で持つ。これらは外部ナビ API 側に無く、自前で再実装すると追加コストが大きい。
- Navigator（`setDestinations` / `startGuidance`）だけ使わないことで、
  **「ルーティング/turn-by-turn は外部ナビ API、描画は Google」** という分担が成立する。
- Android Auto の `NavigationTemplate` は `CarContext` 前提（`androidx.car.app.navigation.*`）
  で、通常アプリ内オーバーレイからは使えない。将来の Auto 対応はカーアプリ側で別実装が必要
  （Phase 3）。

> **注意:** 初版では「`followMyLocation` の交差点自動ズーム」を NavigationView の利点として
> 挙げていたが、Codex の独立調査で `followMyLocation(CameraPerspective)` は Navigator 初期化
> が前提（未初期化で呼ぶと例外）と確定したため、利点から除外した。自動ズームは §1.5 の自前
> 実装で代替する。

### 1.3 「Google Navigation SDK に外部ナビ API のルートを食わせない」理由

Google 公式ドキュメント + Navigation SDK 6.0.0 AAR の decompile 調査
（Claude + Codex による独立 2 セッション）で、以下が確定済み:

1. **`CustomRoutesOptions.setRouteToken()` は Google Routes API 発行の routeToken 限定**。
   外部ナビ API のポリラインを直接食わせる API は存在しない。
2. Silent Waypoint 相当（Routes API の `Waypoint.via: true`）は存在するが、
   **routeToken 発行時に via waypoint を含めてはならない** と公式が明示
   （"Ensure that none of your route waypoints are via waypoints when requesting a route token"）。
   併用不可。
3. routeToken 取得のため通常 intermediates を使う場合、**Nav SDK にも同じ intermediates を
   渡す必要がある** （"same destination waypoints" の仕様解釈。Codex が v6.0.0 AAR で確認）。
   各 intermediate で `ArrivalListener.onArrival` が発火し
   （公式: "If more than one destination is set, this is invoked multiple times"）、
   UI 上で「目的地 N に到着」バナーが intermediate の数だけ挟まる → UX 破綻。
4. `continueToNextDestination()` で auto-advance は可能だが、intermediate arrival 時の
   バナー/callout 瞬間表示を選択抑制する API は公開・非公開含めて存在しない
   （Codex 決定コメント）。
5. `Waypoint.Builder` に `setVia` / `setSilent` / `setPassThrough` 等のフラグは存在しない
   （v6.0.0 AAR の公開メソッド列挙で確認）。
6. routeToken 使用時であっても、ルートは Google が "best matching guideline" として扱い、
   **完全追従は保証されない**（公式明記）。外部ナビのポリラインに厳密に沿わせることは
   Google の設計上不可能。
7. NavigationView に外部 `NavInfo` を注入する公開/非公開 API は、6.0.0 AAR decompile でも
   発見できず。`setCustomNavInfo` / `injectNavInfo` 等の候補メソッドはいずれも存在しない。

これらの結論から、**ナビ中は Navigator をそもそも起動しない**。`startGuidance` /
`stopGuidance` / `setDestinations` / `CustomRoutesOptions` は全面的に使用を停止する。
`NavigationApi.getNavigator` は呼ぶ（`RoadSnappedLocationProvider` 取得のため。ただし
このプロバイダが Navigator 未起動で動くかは §1.6 Q-102 で実機検証する）。
`Navigator.AudioGuidance` / `setAudioGuidance(SILENT)` も不要。

### 1.4 TBT UI のオーバーレイ戦略（`setCustomControl`）

Navigator を起動しないため、NavigationView の純正 TBT ウィジェット
（`setHeaderEnabled` / `setEtaCardEnabled` / `setSpeedometerEnabled` / `setSpeedLimitIconEnabled`
/ `setTripProgressBarEnabled` / `setTrafficPromptsEnabled` 等）は**データ供給源が無く
使えない**。これらはすべて Navigator の guidance セッションに完全依存する
（Codex の 6.0.0 AAR 調査で確認）。

代替として、**`NavigationView.setCustomControl(View, CustomControlPosition)`** を使う。
これは NavigationView 唯一の公開拡張点で、Navigator 非依存:

```kotlin
navigationView.setCustomControl(
    myComposeView,
    CustomControlPosition.BOTTOM_START_BELOW_ETA,
)
```

`CustomControlPosition` は `TOP_START` / `TOP_END` / `BOTTOM_START` / `BOTTOM_END` /
`BOTTOM_START_BELOW_ETA` 等を提供。自前 Compose で組んだ以下の UI をオーバーレイする:

| UI 要素 | overlay 位置 | データソース |
|---|---|---|
| 次ターンバナー（矢印 + 距離 + 交差点名） | `TOP_START` | 外部ナビ `Guidance.guidancePoints[next].intersectionName` + distance remaining |
| レーンガイダンス | `TOP_START` 配下 | 外部ナビ `GuidancePoint.laneMarkers[]` |
| 方面看板（DirectionSign） | `TOP_END` | 外部ナビ `GuidancePoint.directionSigns[]` |
| 高速パネル（IC/JCT/SA/PA） | `TOP_END` 配下 or 独立レイヤ | 外部ナビ `intersections[]` + `roadName` |
| ETA / 残距離 / 残時間 | `BOTTOM_END` | `ExtNavGuidanceTracker` の進捗計算結果 |
| 速度計 + 速度制限 | `BOTTOM_START` | 現在地の速度 + 外部ナビ `GuidePoint.speedLimit` |
| リルート中インジケータ | `TOP_START` 被せ | `ExtNavRerouteDetector` の状態 |

実装時の注意:
- `setCustomControl` は UI thread で呼ぶ
- カスタム View のライフサイクルは NavigationView 側で管理されない → `onStart` / `onStop` で
  set / 解除を制御
- Compose View を渡す場合は `ComposeView` でラップ

### 1.5 カメラ auto-zoom の自前実装仕様

Navigator 非起動では `followMyLocation(CameraPerspective)` が使えない（例外）。代わりに
`GoogleMap.animateCamera(CameraUpdateFactory.newCameraPosition(...))` で手動制御する。
Codex が既存 OSS 実装から観測した数値域をベースラインとする:

| フェーズ | zoom | tilt | bearing |
|---|---|---|---|
| 通常走行（次 GP まで 300m 超） | 15〜17 | 0〜30° | 進行方向 |
| 交差点接近（300m〜100m） | 17〜18 | 30〜45° | 進行方向 → 次 GP 方向へ緩やかに遷移 |
| 交差点直前（100m〜20m） | 18〜20 | 45° | 次 GP 方向 |
| 直進継続・高速走行 | 14〜16 | 0° | 進行方向 |

実装骨子:

```kotlin
class ExtNavCameraController(
    private val googleMap: GoogleMap,
    private val tracker: ExtNavGuidanceTracker,
) {
    fun onTick(location: Location) {
        val phase = resolvePhase(location, tracker.nextGuidancePoint)
        val target = /* snapped location */
        val bearing = interpolateBearing(location.bearing, tracker.nextGpBearing, phase)
        val (zoom, tilt) = zoomTiltOf(phase)
        googleMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(bearing)
                    .build(),
            ),
        )
    }
}
```

- `animateCamera` の多重発火を避ける（1 tick = 1 発火）
- ユーザーが手動で地図を触った場合は `OnCameraMoveStartedListener` で検知し自動追従を一時停止
- Recenter ボタンを自前で `setCustomControl` に載せて復帰させる

### 1.6 Navigator 初期化の残存論点（Q-102）

`NavigationApi.getNavigator()` は呼ぶが `setDestinations` / `startGuidance` を呼ばない
状態で、以下がどこまで動くかが未検証:

- **(a)** `RoadSnappedLocationProvider.requestLocationUpdates()` が map-matched 座標を流すか
- **(b)** `followMyLocation(FOLLOWING)` が例外投げずに動くか（Codex は「Navigator 初期化必要」
  までしか確定していない。未 `startGuidance` での挙動未確定）
- **(c)** `NavigationView` のライフサイクル（`onStart` / `onResume`）が crash せず完走するか

(a) が動けば合格。(b) が動けば §1.5 のカメラ制御を一部 Google に委譲可能（実装簡略化）。
(c) が NG なら `NavigationView` 自体を使えない → §11.2 フォールバック（plain `MapView`）へ。

---

## 2. 取り込み対象: 外部ナビ API ライブラリ

### 2.1 モジュール導入

- 外部ナビ API ライブラリは **別管理のプライベートリポジトリ** に存在する。
  OneNavi リポジトリ側には **コードも生パスも含めない**。
- 取り込みは **composite build (`settings.gradle.kts` の `includeBuild`)** を使う。
  ローカルパスは gradle property `extNavApiPath`（`gradle.properties` もしくは環境変数）で
  与え、未設定ならスタブ実装へフォールバックする（OSS 利用者が credential / ライブラリを
  持たない状態でもビルドは通る構成にする）。
- 依存方向: `core/datasource` → 外部ナビ API ライブラリ（Android 専用。`androidMain` のみ）。
- 既存 `core/datasource/GoogleRoutesDataSource` は残しつつ、ビルド時フラグまたは単純な
  差し替えで切り替え可能にする（完全削除は Phase 2 以降）。

### 2.2 認証情報の配置

現状の `local.properties` に外部ナビ API の credential を追加する:

```properties
# External Nav API credentials (keep out of VCS)
EXT_NAV_LOGIN_ID=...
EXT_NAV_PASSWORD=...
```

`composeApp/build.gradle.kts` の BuildKonfig に以下を追加:

```kotlin
setField("EXT_NAV_LOGIN_ID")
setField("EXT_NAV_PASSWORD")
```

`core/model/AppConfig` に 2 フィールド追加:

```kotlin
data class AppConfig(
    ...,
    val extNavLoginId: String,
    val extNavLoginPassword: String,
)
```

OSS 公開を維持する以上、**credential はリポジトリにコミットしない**。OSS 利用者に対しては
「自分が持つ外部ナビ API の契約情報を local.properties に書く」形式を強制する。外部ナビ API
を叩く旨は README の冒頭で明示し、**本アプリの利用 = 利用者自身の責任** であることを
明文化する。事業者名・製品名・エンドポイント URL は README / 公開ドキュメントに記載しない。

> **注意:** 将来的に二輪車向けの同系 SKU に対応する場合は credential を 2 ペア管理する
> 可能性がある（Phase 4 の判断）。

### 2.3 DeviceUuid の永続化

外部ナビ API ライブラリ側の `deviceUuid` は毎回ランダム発番するとサーバー側でアノマリー扱い
される疑いがあるため、**初回起動時に生成し DataStore に保存**する。`AppSetting` /
`AppSettingDataSource` に `extNavDeviceUuid: String?` を追加し、null なら新規生成し
保存する。

---

## 3. 置換対象コード

### 3.1 削除・大幅書き換え

| 対象 | 処置 |
|---|---|
| `core/navigation/NavigationSdkManager.startNavigation` 以下の setDestinations/startGuidance 系 | **削除**。`NavigationApi.getNavigator()` の初期化は残すが、設定するのは arrival / route-changed / rerouting listener ではなく、`RoadSnappedLocationProvider` 取得のためだけ。 |
| `TurnByTurnUpdateBus` / `NavigationUpdatesService` / `NavigationFeedSnapshot` / `NavigationStepSnapshot` / `NavigationLaneSnapshot` | **削除**。SDK からの NavInfo に依存しない構造にする。代わりに `GuidanceFeed`（外部ナビ API 由来）を定義する。 |
| `guidance/GuidanceCoordinator` / `guidance/GuidancePlanner` / `guidance/PhraseComposer` / `guidance/SpeechDispatcher` / `StepTransitionTracker` | **大幅縮退**。日本語テンプレートを自分で組み立てる責務を失うため、Planner / PhraseComposer / StepTransitionTracker は削除。Coordinator は `GuidanceFeed` を受け取り発話判定だけする薄いクラスに書き直す。`SpeechDispatcher` は CRITICAL / NORMAL チャネルの概念は残すが、入力イベントが変わるので全面書き換え。 |
| `core/model/GuidanceEvent` / `DistanceBucket` / `FollowupDistanceBucket` / `FollowupManeuver` / `ManeuverType` / `ManeuverModifier` / `LaneInfo` / `LanePosition` / `StraightforwardLevel` / `GuidancePhrase` / `TtsPhraseId` / `CompassDirection` | **ほぼ全削除**。外部ナビ API の SSML をそのまま読み上げるため、マニューバ分類・距離バケット・フレーズ ID などは不要になる。UI に必要な `ManeuverType` / `ManeuverModifier` だけは「現在 GP の方向アイコン」を決める用途で残す（外部ナビ API の guidance category や approach angle から射影する）。 |
| `core/datasource/GoogleRoutesDataSource` | Phase 1 は残し、Phase 2 で完全削除（後述）。 |
| `core/model/RouteResult.platformRoute: Any?` | **削除**（`Any?` のまま外部ナビ API の Route を突っ込まない）。Android 側の feature で型安全に外部ナビ API の `Route` / 自前 `ExtNavRoute` を保持する。既存の Home リファクタ計画 12 の方針を徹底する。 |
| `core/model/GoogleRoute` の `routeToken` | **削除可**。Navigator に渡さないため不要。`GoogleRoute` 自体も「Google 由来」という意味が薄れるので `ExtNavRoute`（External Nav Route）にリネームする。 |

### 3.2 新規導入

| 新規クラス | 配置 | 責務 |
|---|---|---|
| `ExtNavRoute` | `core/navigation/androidMain` | 外部ナビ API の Route + Guidance のペア。UI に渡す単位。|
| `ExtNavRouteRepository` | `core/repository`（ただし実装は androidMain） | 外部ナビ API の `RouteClient.search()` + `GuidanceClient.resolveGuidance()` を並列呼び出しして `ExtNavRoute` の候補リストを返す |
| `ExtNavRouteDataSource` | `core/datasource/androidMain` | ライブラリのファサード（`ExtNavClient` 相当）を注入。認証ゲートを経由してルート検索する |
| `ExtNavAuthGateway` | `core/navigation/androidMain` | 起動時に `AuthClient.currentState()` を読み、SignedOut なら `signInWithCredentials(AppConfig)` を実行。失敗時は UI にエラー表示 |
| `ExtNavGuidanceTracker` | `core/navigation/androidMain` | `Guidance.guidancePoints` + 現在地（`RoadSnappedLocationProvider`）を入力とし、最近傍 GP を判定して進捗を算出する |
| `ExtNavAnnouncementScheduler` | `core/navigation/androidMain` | `ExtNavGuidanceTracker` の進捗から「この GP の `phrases[]` のどれを今発話すべきか」を決定し、SSML を TTS に渡す |
| `ExtNavRerouteDetector` | `core/navigation/androidMain` | 現在地がルート形状から閾値以上離れたら `onOffRoute()` を発火。一定時間後に `ExtNavRouteRepository.resolveGuidance(current → goal + 残経由地)` で再検索 |
| `ExtNavTrafficOverlay` | `core/navigation/androidMain` | `TrafficClient.listByPathCodes(...)` を経路に紐付くコードで定期ポーリング（Phase 2） |
| `ExtNavGuideImagePreloader` | `core/navigation/androidMain` | ルート確定時に `GuideImageClient.preload(guidance)` を走らせ、画像を Coil ディスクキャッシュに落とす |
| `ExtNavSsmlSpeaker` | `core/navigation/tts` | 既存 `TtsEngine` をラップ。外部ナビ API の SSML 内に含まれる独自 phoneme 拡張を、発話対象 TTS で読める形に射影する（詳細 §7）|

---

## 4. データフロー

### 4.1 ルート検索フロー（Browsing → RoutePreview）

```
User intent share / search select
  │
  ▼
HomeMapViewModel.onRouteSearch
  │
  ▼
ExtNavRouteRepository.search(criteria)
  ├─ ExtNavAuthGateway.ensureSignedIn()
  │   └─ (初回 or 期限切れ時) AuthClient.signInWithCredentials(loginId, password)
  │
  ├─ 並列:
  │    ① RouteClient.search(criteria) → List<Route>  (JSON)
  │    ② GuidanceClient.resolveGuidance(criteria)   → Guidance (protobuf)
  │
  ├─ criteria.limit = 3 → 候補 1..3 件
  │   ただし ② は「1 候補分」しか返らない制約がある → §4.4 で言及
  │
  └─ 候補ごとに ExtNavRoute(route = routes[i], guidance = guidance[i], imagePreloadHandle)
  │
  ▼
RouteManager.setRoutes(extNavRoutes)
  │
  ▼
UI:
  ├─ 地図: 各候補のポリラインを自前で描画
  │        (guidance.guidancePoints[].sub_path_samples を連結 + 整形)
  ├─ RouteTopAppBar: 出発地 / 経由地 / 目的地
  ├─ RouteResultSheet: RouteSummary (distance, time, toll, fromTime, toTime)
  └─ Callout: AvoidOverlap 戦略（17_callout_redesign に準拠）
```

### 4.2 ナビ開始フロー（RoutePreview → ActiveGuidance）

```
User taps "ナビ開始"
  │
  ▼
HomeMapViewModel.onNavigationStarted
  │
  ▼
GuidanceSessionManager.startSession(selected: ExtNavRoute)
  ├─ RoadSnappedLocationProvider を購読開始（NavigationApi 初期化は app 起動時）
  ├─ ExtNavGuidanceTracker.attach(route, location)
  ├─ ExtNavAnnouncementScheduler.attach(tracker)
  ├─ ExtNavRerouteDetector.attach(route, location)
  ├─ ExtNavSsmlSpeaker.startSessionGreeting()
  │   → 「ルート案内を開始します」（事前用意した OneNavi 側テンプレート）
  └─ CameraManager.requestCameraFollowing(pitch3D = true)
  │
  ▼
ForegroundService (別途)
  └─ 画面OFF時も RoadSnappedLocationProvider が動く前提。SDK の foreground service
     オプションが有効にならないため、OneNavi 側で `NavigationUpdatesService` を
     転用する（"turn-by-turn feed" を publish しない、常駐通知だけ出す）。
```

### 4.3 ナビ中のイベントループ

```
RoadSnappedLocationProvider.LocationListener.onLocationChanged(location)
  │
  ▼
ExtNavGuidanceTracker
  ├─ 最近傍 GP を探す（距離計算 + GP.distanceFromStart で単調増加を保つ）
  │    → 現 GP / 次 GP / これまでに通過した GP の集合
  ├─ route geometry への最短距離 = offRouteDistance を算出
  └─ GuidanceUiState（残時間・残距離・次マニューバ・次方面看板・次交差点名）を更新
  │
  ▼
ExtNavAnnouncementScheduler
  ├─ 各 phrase（SsmlPhrase）の `distanceMetres`（「何 m 手前で読む」）を参照
  ├─ 現在位置からの手前距離がしきい値を下抜けたタイミングで発話キュー投入
  ├─ 同一フレーズの二重発火防止（`stepCounter × category × bucket` 相当のキー）
  ├─ 優先度: CRITICAL = 出発/到着/誤経路 / HIGH = IntersectionGuide / NORMAL = Landmark 等
  └─ SSML を ExtNavSsmlSpeaker に投入
  │
  ▼
ExtNavSsmlSpeaker
  ├─ SSML から独自 phoneme 拡張を読み取り、エンジン側で使える形に再構成
  │   - Android TTS: `<speak>` タグ + `<phoneme alphabet="ipa">` に翻訳
  │   - Google Cloud TTS: SSML そのまま（Chirp 3 HD は SSML を受け付ける）
  ├─ キャッシュ（TtsAudioCache）を SSML 単位で再利用
  └─ 合成 PCM 再生
  │
  ▼
ExtNavRerouteDetector
  ├─ offRouteDistance > threshold が N 秒継続 → onOffRoute()
  ├─ 即座に ExtNavSsmlSpeaker で "ルートから外れました" を発話（CRITICAL）
  └─ ExtNavRouteRepository.search(criteria=currentLoc→goal+remainingViaPoints) を実行
    ├─ Guidance 差し替え
    ├─ ExtNavGuidanceTracker を新ルートに attach し直す
    └─ 発話マーク（spoken）を全クリア
  │
  ▼
Arrival 判定
  ├─ 現在地が目的地 < 50m で `ExtNavSsmlSpeaker.speak("まもなく目的地です")`
  ├─ < 20m で到着確定 → NavigationState.Arrival に遷移
  └─ Arrival UI を 10s 表示 → Browsing 復帰
```

### 4.4 「案内 API は 1 候補しか返さない」問題への対処

外部ナビ API の案内バイナリエンドポイント（protobuf）は `priority` 系クエリで 1 つのルートしか返さない（ROUTE1 固定）。代替ルート 3 つを表示したい場合は **priority を変えて 3 回叩く** のが自然な解。

| candidate | priority 種別 | 説明 |
|---|---|---|
| recommended | 標準 | 既定 |
| time | 時間優先 | 所要時間最短 |
| distance | 距離優先 | 走行距離最短 |
| toll-avoid | 有料回避 | 無料優先 |

- UI 上の「ルート 1 / ルート 2 / ルート 3」は priority で意味付けできるため UX 的にもわかりやすい
- 3 回の HTTP 呼び出しは並列化。1 ルートあたりのレスポンスは軽量（数十 KB）のため 3 並列は帯域的に許容範囲
- `RouteClient.search(limit=3)` も並列で叩いて summary（時間・距離・料金）を取得し、案内の
  1 候補ずつと突合する（`summary.distanceMetres` / `timeSeconds` で対応判定）
- Phase 1 では **recommended 1 本のみ表示**。Phase 2 で 3 priority 対応

### 4.5 画像プリロード

- ルート確定時: `GuideImageClient.preload(guidance, format=Webp)` を発火
- 結果 `GuideImage[]` を **Coil の MemoryCache + DiskCache** に `ext-nav-image-<minor>` キーで保持
- UI で表示するときは `rememberAsyncImagePainter(key="ext-nav-image-<minor>")` で参照
- `isMissing=true` の画像は表示自体スキップ

---

## 5. 状態マシンへの影響

13_navigation_screen_design の 6 状態マシン（Browsing / Search / RoutePreview /
ActiveGuidance / Arrival / FreeDrive）は**そのまま維持**する。各状態の責務は以下に
置き換わる:

| 状態 | 現在の実装 | 新実装 |
|---|---|---|
| Browsing | GoogleMap のみ | GoogleMap + RoadSnappedLocationProvider（起動済み） |
| Search | Google Places | 現状維持（外部ナビ API の spot 検索は Phase 4 以降の判断） |
| RoutePreview | Google Routes 結果を 3 本表示 | 外部ナビ API の結果を 1〜3 本表示 |
| ActiveGuidance | Navigator 主導 | ExtNavGuidanceTracker / ExtNavAnnouncementScheduler 主導 |
| Arrival | Navigator arrival event | 自前判定（goal 50m → 20m）|
| FreeDrive | 定義のみ | 現状維持 |

---

## 6. ポリライン精度の問題

### 6.1 取得ソース

外部ナビ API のネイティブ SDK 専用バイナリ（ROUTE 系）は自前解析不可。したがって
**ポリラインは案内 protobuf の `GuidePoint[].sub_path_samples`** から取り出す。

- `sub_path_samples` は「間引き済みの補間点」。GP 1 つあたり数十点。
- 実サンプル（都内→北関東の長距離経路）では GP 146 件 × 合計約 1,500 点程度が確認できている。
- Google Routes の polyline（数千〜数万点）と比べて粗いが、ナビ表示には実用範囲。
- 必要に応じてライブラリ側のマッパで補間（隣接 2 点の間を n 等分）を
  行うか、`LatLngBounds` ベースの描画簡略化で凌ぐ。

### 6.2 自車の map matching とのズレ

`RoadSnappedLocationProvider` は **Google が描いている地図に対して map-match する**。
外部ナビ API のポリラインと完全一致しないため、ルート線と自車 chevron がズレる瞬間が発生する。

緩和策:
- ルート線の太さを従来の 24f → 28f に広げて視覚的に吸収
- ルート外とみなす閾値を緩めに設定（例: 50m → 80m）
- 交差点・カーブ付近の GP は `sub_path_samples` を密に取る（現状のデータは十分密）

Phase 3 で Android Auto 対応するときにも同じ問題が出るが、その時点では Google と
外部ナビ API の地図スタイルを揃える検討をする（現状 Mapbox Studio 相当のカスタムは Google
側に存在しないため根本解決は困難。許容範囲で妥協する）。

---

## 7. 音声案内の扱い

### 7.1 SSML を誰が読むか

外部ナビ API が返す `SsmlPhrase.ssml` は以下のような形（独自 phoneme 拡張あり）:

```xml
直進方向、<phoneme alphabet="x-vendor-ruby" ph="しゅとこう・ぎんざ・がいかん・こうや">首都高・銀座・外環・高谷</phoneme>方面です。
```

OneNavi の TTS エンジンは 2 系統:

1. **Android 内蔵 TTS**: SSML を一応受け付けるが独自 alphabet は動かない。
   → タグを剥がしてプレーンテキスト化する（ライブラリの `SsmlPhrase.plainText()` 経由）。
   読み間違いは外部ナビ API の意図した読みと異なる可能性があるが、漢字読みは TTS 側に委ねる。

2. **Google Cloud TTS Chirp 3 HD**: SSML サポートあり。ただし独自 `alphabet` は
   非対応。代替として:
   - プレーンテキスト化して渡す（Chirp 3 HD は日本語の読みが極めて優秀なので、
     通称・固有名詞もかなり読める）
   - 将来的に `ph` 属性を IPA / kana → SSML kana に変換するコンバータを書けば高品質化可能
     （Phase 2 の裁量）

いずれにせよ **発話タイミング・発話テキストは外部ナビ API が決める**。OneNavi 側が持つのは:
- 発話キューと優先度制御
- TTS 合成・再生・エラーフォールバック
- 定型フレーズ（「ルート案内を開始します」「目的地に到着しました」など、GP に属さない
  セッション境界フレーズ）

### 7.2 発話タイミング戦略

`SsmlPhrase.distanceMetres` は「この GP の **手前何 m で読む**」値（例: 1000 / 500 /
300 / 100 / 50 / 0）。ExtNavAnnouncementScheduler は以下ロジックで発火:

```
tick (location 更新ごと):
  for gp in ルート上前方の GP (走行順で次の N 個):
    distanceToGp = haversine(location, gp.coord)
    for phrase in gp.phrases:
      key = (gp.index, phrase.category, phrase.distanceMetres)
      if spoken.contains(key): continue
      if distanceToGp <= phrase.distanceMetres + HYSTERESIS:
        enqueue(phrase, priority = priorityOf(phrase.category))
        spoken.add(key)
```

- `spoken` はセッション単位で持つ（既発話マーク）
- リルート時に全クリア
- `HYSTERESIS` は 10〜20m（GPS jitter 対策）
- `priorityOf(category)`:
  - `CRITICAL` = `WrongWayDriving`, `WrongEntry`, `Zone30`
  - `HIGH` = `IntersectionGuide`, `IntersectionGuideSoon`, `HighwayRecommendedLane`,
    `Merge`, `MergeAttention`, `HighwayLaneReduction`
  - `NORMAL` = 残り

### 7.3 「その他のフレーズ」の扱い

外部ナビ API が対応していない境界フレーズ（「ルート検索中です」「ルート案内を開始します」
「再検索しました」等）は OneNavi の `core/resource/strings` に置き、既存 TTS 経路で喋らせる。
Phase 1 の範囲では 10 フレーズ程度で十分。

---

## 8. Phase 分割の再定義

既存 07_phased_roadmap は **上書きされる**。

### Phase 1（本移行の最小スコープ）

- 外部ナビ API ライブラリを composite build で取り込み、ビルドが通る状態にする
- `ExtNavAuthGateway` / `ExtNavRouteDataSource` / `ExtNavRouteRepository` を実装
- `NavigationSdkManager` から `setDestinations` / `startGuidance` を削除し、
  `RoadSnappedLocationProvider` の取得だけを残す
- `GuidanceSessionManager` / `GuidanceCoordinator` / `GuidancePlanner` / `PhraseComposer`
  を新実装に置き換え（既存は削除）
- `ExtNavGuidanceTracker` / `ExtNavAnnouncementScheduler` / `ExtNavRerouteDetector` の初版実装
- `HomeMapsMapEffectContent` のポリライン描画を外部ナビ API 由来に差し替え
- Android 内蔵 TTS で SSML プレーン発話
- **代替ルートは 1 本のみ**。priority=recommended 固定
- 到着判定・経由地通過の最小実装
- `local.properties` + BuildKonfig 経由の credential 配線
- ユニットテスト: 既存 `GuidancePlannerTest` / `SpeechDispatcherTest` を廃止し、
  `ExtNavGuidanceTrackerTest` / `ExtNavAnnouncementSchedulerTest` を追加

### Phase 2

- 代替ルート 3 本対応（priority を複数送る並列検索 + 代表ルートの切替）
- `GuideImageClient.preload` による青看板・JCT 3D の表示
- `TrafficClient.listByPathCodes` による経路上渋滞ハイライト
- Google Cloud TTS Chirp 3 HD の SSML サポート（独自 phoneme → kana ruby 簡易変換）
- 高速パネル（IC/JCT/SA/PA）表示: 案内の `intersections[].position` と
  `roadName`（高速）で抽出

### Phase 3

- Android Auto 対応
- 外部ナビ API 依存を前提にした高速パネルの Auto ページネーション（6 件制限）
- 速度制限・速度警告（`GuidePoint.speed_limit` を利用）

### Phase 4

- 二輪車向け同系 SKU 対応の可否検証
- オフライン耐性（認証トークン永続 + 案内 ZIP キャッシュ）
- 拡張機能（MyRoute 相当など）の検討

---

## 9. リスク

| リスク | 影響 | 対処 |
|---|---|---|
| 外部ナビ API 事業者の利用規約違反 | 個人開発であっても BAN / 法的リスク | 本実装は**利用者個人の契約を用いた自己利用のための API クライアント**であり、credential を公開しない。OSS としては「コードのみ」公開、事業者との契約は利用者自身が個別に結ぶことを README に明記。公開ドキュメントに事業者名・製品名・エンドポイント URL を書かない。第三者実装は事業者のサポート対象外であり、利用規約の範囲は利用者が確認する前提。 |
| 仕様変更で API が壊れる | アプリが動かない | 案内 protobuf はリバース推定が含まれるため、壊れたらライブラリ側の解析資料を再収集して proto 更新（ライブラリ側リポジトリで完結）。 |
| JWT 失効 / 認証ヘッダ不整合 | silent fail (200 + 0 byte) | `ApiFailure.EmptyResponse` を拾ってゲスト認証 → signIn リトライのフォールバック実装 |
| ポリラインの粗さで自車がずれる | UX 劣化 | §6.2 の緩和策 + GP の sub_path_samples 補間 |
| 案内 API が 1 候補しか返さない | 代替ルート UI が貧弱 | §4.4 の priority 切替 3 並列 |
| 外部ナビ API のデータ更新頻度 | 道路変更に追随しない可能性 | `header.json.M-Format_Version` を監視し古いキャッシュを捨てる運用 |
| Google Navigation SDK の「Navigator を起動しない」使い方が非推奨 | 将来のアップデートで壊れる可能性 | plain `MapView` + `FusedLocationProviderClient` への退避経路を設計メモとして残す（§11.2） |
| SSML phoneme が正しく読まれない | 通称・固有名詞の読み間違い | Phase 2 で独自 phoneme → kana 変換実装。Phase 1 はプレーンテキスト |
| 外部ナビ API の Geocoding / Autocomplete を使わない | 目的地検索の日本語品質 | 現状の Google Places を継続。検索は外部ナビ API に委譲しない |

---

## 10. 決定ログ (D-100〜)

### D-100: ルート検索・turn-by-turn を外部ナビ API に移譲
- **Date:** 2026-04-22
- **Context:** 別管理のプライベートリポジトリに auth/route/guidance/image/traffic 5 feature を揃えた
  Android ライブラリが実 API で動作確認済み。音声案内の品質が Mapbox / 自前テンプレ / Google Routes を大幅に上回る。
- **Decision:** 採用。D-001 での「外部ナビ API 不採用」を上書き。個人利用・自己責任範囲での
  使用に限定し、配布時は credential を含めない。
- **Rationale:** 日本のカーナビ UX の根幹である「通称」「信号機案内」「方面看板」「合流・
  車線減少注意喚起」「SA/PA 位置」を一気に獲得できる。ROI は飛び抜けて高い。

### D-101: 地図描画は Google Maps SDK（NavigationView）を維持
- **Date:** 2026-04-22
- **Decision:** `NavigationView` を残し、Navigator の setDestinations / startGuidance は使わない。
- **Rationale:** §1.2 / §1.3 の通り。最小工数で最大の描画品質を維持できる。

### D-102: Mapbox フルスタック（D-002）は破棄
- **Date:** 2026-04-22
- **Decision:** Mapbox Maps / Navigation / Geocoding への依存は撤去する。`local.properties` の
  `MAPBOX_TOKEN` も削除対象。
- **Rationale:** Google + 外部ナビ API の分担で全要件をカバーできる。

### D-103: 自前日本語テンプレート（D-003）も破棄
- **Date:** 2026-04-22
- **Decision:** 通称辞書・信号機案内・合流案内テンプレート・速度連動タイミング・自然な日本語
  テンプレートの自前構築は全て撤回。外部ナビ API の SSML をそのまま読み上げる。
- **Rationale:** 外部ナビ API が既に「完璧」なテキストを返すため、自前実装は冗長。

### D-104: 音声エンジンの選択（D-004）は維持
- **Date:** 2026-04-22
- **Decision:** Google Cloud TTS Chirp 3 HD（Laomedeia）をメイン、Android 内蔵 TTS を
  フォールバック、という構成は維持する。ただし入力テキストの組み立ては外部ナビ API 側に委譲。
- **Rationale:** Chirp 3 HD の音声品質は変わらず優秀。自前テンプレートが消えるだけで、
  エンジンは同じ。

### D-105: 交差点拡大図（Junction View）は外部ナビ API の案内画像で代替
- **Date:** 2026-04-22
- **Decision:** `GuideImageClient.preload` による高速 JCT 3D 分岐イラスト、一般道交差点の
  青看板を直接表示する。Mapbox Junction View の日本対応検証は不要になる（Q-001 クローズ）。
- **Rationale:** 外部ナビ API が返す 3D JCT 画像は商用品質。自前プロシージャル生成より優秀。

### D-106: SA/PA データの同梱（D-005）は不要になる
- **Date:** 2026-04-22
- **Decision:** `assets/sapa.json` / OSM Overpass API による事前抽出パイプラインは不要。
  外部ナビ API の案内に SA/PA が含まれる。
- **Rationale:** 自前 DB 管理の保守コスト削減。

### D-107: 外部ナビ API credential の配置
- **Date:** 2026-04-22
- **Decision:** `local.properties` の `EXT_NAV_LOGIN_ID` / `EXT_NAV_PASSWORD` を BuildKonfig
  で読み取り、`AppConfig` に保持。DeviceUuid は DataStore で永続化。
- **Rationale:** OSS 維持と credential 秘匿の両立。

### D-108: 公開ドキュメントで API 提供事業者名・製品名を出さない
- **Date:** 2026-04-22
- **Decision:** 本リポジトリ（OneNavi）の公開ドキュメント・README・コミットメッセージ・
  クラス名・識別子・コメントには、外部ナビ API の事業者名・製品名・エンドポイント URL を
  一切記載しない。総称として「外部ナビ API」「N 社」を用いる。プロトコル詳細・解析資料は
  別管理のプライベートリポジトリ側に閉じ込める。
- **Rationale:** 非公開 API を第三者実装で扱う以上、権利侵害・BAN リスクを公開側で極小化する。

---

## 11. Open Questions

### Q-100: 案内 API の `priority` コード一覧
- **Status:** UNRESOLVED
- **Question:** time / distance / toll-avoid に対応する priority の整数値は
  具体的に何か。ライブラリ側の逆解析資料（デコンパイル済み `CarPriority` 定数テーブル）を
  直接読んで確定する必要がある（確認作業はプライベートリポジトリ側で完結する）。
- **Priority:** HIGH（Phase 2 で 3 本ルート UI を作る時に必須）

### Q-101: 経由地の通過タイプ
- **Status:** UNRESOLVED
- **Question:** 経由地の「必ず通る / 近くを通る」等の挙動差を検証する必要がある。
- **Priority:** MEDIUM

### Q-102: Navigator 未起動時の NavigationView / RoadSnappedLocationProvider 挙動
- **Status:** UNRESOLVED
- **Question:** `NavigationApi.getNavigator()` のみ呼び、`setDestinations` /
  `startGuidance` を呼ばない状態で下記が動作するかを実機検証:
  - (a) `RoadSnappedLocationProvider.requestLocationUpdates()` が map-matched location を流すか
  - (b) `followMyLocation(FOLLOWING)` が例外を投げずに動作するか
  - (c) `NavigationView` のライフサイクル（`onStart` / `onResume`）が crash せず完走するか
- **Blocking:** (a) が NG なら方針不成立 → Navigator に妥協的な Google Routes ルートを
  食わせる or plain MapView への完全退避（§11.2）を迫られる
- **Priority:** BLOCKER（Phase 1 の T00。他タスク全着手前）

### Q-103: 外部ナビ API 事業者の利用規約・個人利用範囲
- **Status:** UNRESOLVED
- **Question:** 個人契約ユーザーが自作アプリで内部 API を叩くことの規約上の扱い。事業者
  サポートに問い合わせるか、規約条文を精読する必要がある。
- **Priority:** HIGH

### Q-104: guidance category のうち UI 表示すべきもの
- **Status:** UNRESOLVED
- **Question:** 速度取締・警察活動ゾーン・低速ゾーン・踏切等、**表示すると法的・倫理的
  懸念のあるカテゴリ**が含まれる。本家アプリでも ON/OFF 設定される類の情報は、OneNavi での
  デフォルト OFF ポリシーを決める。
- **Priority:** MEDIUM（Phase 2 設定画面で決める）

### Q-105: DeviceUuid の一意性要件
- **Status:** UNRESOLVED
- **Question:** 外部ナビ API 側で `deviceUuid` は 1 端末 = 1 UUID を期待しているのか、
  複数の UUID を同一契約で回すと BAN 対象になるのか。
- **Priority:** MEDIUM

---

## 11.2 フォールバック: NavigationView 完全撤去パス（念のため）

Q-102 の結果次第では `NavigationView` を捨て plain `MapView` 化する必要が出る。そのときの
設計メモ:

- `MapView` + `SupportMapFragment` に差し替え
- 自車位置は `FusedLocationProviderClient` で取得（map matching 自前）
- `NavigationApi` / `Navigator` / `RoadSnappedLocationProvider` / `NavigationUpdatesService`
  は全削除
- カメラ追従は自前 `CameraPosition` アニメーション
- 信号機・停止線レイヤは失われる（地図スタイル限界）
- 工数影響: +1〜2 週間程度

Phase 1 の最初のタスクで Q-102 を実機検証し、NavigationView が使えるならそのまま進める。

---

## 12. タスクブレークダウン（Phase 1 実装順）

```
T00. BLOCKER: Q-102 実機検証
       - (a) Navigator 未起動で RoadSnappedLocationProvider が動作するか
       - (b) followMyLocation(FOLLOWING) が例外投げずに動作するか
       - (c) NavigationView ライフサイクル正常性
     NG(a) なら §11.2 にスイッチ。ここで方針確定してから T01 以降着手
T01. Gradle 設定: composite build で外部ナビ API ライブラリ取り込み（パスは gradle property）
T02. BuildKonfig / AppConfig / local.properties に credential 追加
T03. DeviceUuid 永続化（AppSetting / AppSettingDataSource 拡張）
T04. 外部ナビ API ファサードを Koin DI に登録（設定オブジェクト組み立て含む）
T05. ExtNavAuthGateway 実装 + ensureSignedIn() を RouteDataSource のエントリで呼ぶ
T06. ExtNavRouteDataSource / ExtNavRouteRepository 実装（priority=recommended 固定）
T07. ExtNavRoute / GoogleRoute 削除 / RouteItem 互換層
T08. NavigationSdkManager から Navigator ガイダンス配線を剥離（getNavigator 初期化だけ残す）
     - setDestinations / startGuidance / stopGuidance / Arrival listener 全削除
     - setAudioGuidance（SILENT）も不要（呼ばない）
T09. GuidanceSessionManager を ExtNavGuidanceTracker / ExtNavAnnouncementScheduler 対応に書き換え
T10. 既存 GuidanceCoordinator / GuidancePlanner / PhraseComposer / SpeechDispatcher / StepTransitionTracker / GuidanceEvent / DistanceBucket / FollowupManeuver / CompassDirection を削除
T11. ExtNavGuidanceTracker 実装（最近傍 GP / 残距離 / 残時間算出）
T12. ExtNavAnnouncementScheduler 実装（phrase.distanceMetres ベースの発火）
T13. ExtNavSsmlSpeaker 実装（SSML プレーン化 → 既存 TtsEngine に投入）
T14. ExtNavRerouteDetector 実装（offRoute 判定 + 再検索）
T15. ExtNavCameraController 実装（§1.5 の zoom/tilt/bearing ロジック + manual gesture 一時停止）
T16. 自前 TBT UI（Compose）実装: 次ターンバナー / レーン / ETA カード / 速度計 / 方面看板
T17. NavigationView.setCustomControl 配線（§1.4 の overlay 位置表通り）
T18. HomeMapsMapEffectContent のポリライン描画を外部ナビ API 由来（sub_path_samples）に差し替え
T19. RouteResult / HomeMapViewModel / Repository を新モデルに追従させる
T20. 到着判定・経由地通過・Arrival UI 配線
T21. ForegroundService（常駐通知。turn-by-turn feed publish は行わない）
T22. 実機テスト: 長距離・中距離 2 ルートで Phase 1 動作確認
T23. 既存 Mapbox 関連コード・local.properties 上の MAPBOX_TOKEN 撤去（Phase 1 末に実施）
```

T00 は実測ベースで 0.5〜1 日。残り 23 タスクは 1〜3 日目安。合計 4〜6 週間の見込み
（§1.4 / §1.5 / カメラ制御で当初計画より +1 週間の見積り増）。

---

## 13. 次アクション

1. 本計画書 rev.2（§1.2〜1.6 / §12 更新反映版）のレビュー
2. **T00 着手**: Q-102 (a)(b)(c) を最小検証アプリで実機確認
   - (a) NG なら §11.2 へ
   - (b) NG なら §1.5 を完全自前でチューニング（既定動線）
3. Q-103（外部ナビ API 事業者の利用規約）の条文精読
4. T00 の結果を決定ログ D-109 として追記、T01 以降着手
