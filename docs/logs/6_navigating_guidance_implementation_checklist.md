# Navigating Guidance Implementation Checklist

> 作成日: 2026-05-19
> 参照仕様: `docs/spec/28_navigating_state_and_guidance_progress_design.md`
> 目的: 別環境でも実装の残タスクと受け入れ条件を追えるようにする。仕様本文ではなく作業チェックリスト。

---

## 0. 前提

- 事業者名・製品名・パッケージ root の実名は追加コード / コメント / ドキュメントに書かない。
- 外部 API 由来の公開表記は「外部ナビ API ライブラリ」、クラス prefix は `ExtNav` を使う。
- 案内中の通常 GPS tick では ROUTE / GUIDE を再取得しない。
- 渋滞・規制 overlay は tracker / `GuidanceProgress` に混ぜない。
- `GuidanceProgress` は UI 向け、`ExtNavProgressSnapshot` は周辺コンポーネント向け。

---

## 1. 完了済み

- [x] `GuidanceState.Guiding(route, progress)` のモデル化
- [x] `GuidanceProgress` / `GuidanceManeuverInfo` / `LaneGuidance` / `DirectionSign` / `HighwayPanel`
- [x] `UserLocation`
- [x] `ExtNavProgressSnapshot`
- [x] `ExtNavRerouteDecision`
- [x] `ExtNavAnnouncement`
- [x] `RouteDistanceMapper`
- [x] `ExtNavGuidanceTracker` の初期実装（attach / origin tick / snapshot）
- [x] `ExtNavGuidanceTracker` の DI 登録
- [x] `NewGuidanceManager` から tracker へ origin tick を流す smoke 接続

---

## 2. 次の実装順

### 2.1 案内中の route polyline 描画

リルートの実走 / Fake GPS 確認を先に行うため、TBT バナーや ETA などの UI より先に、
案内中の地図へ `GuidanceState.Guiding.route` を反映する。

- [x] `MapScreen` で `MapViewModel.newGuidanceState` を collect する
- [x] `MapEffect` に `guidanceState: GuidanceState` を渡す
- [x] `MapEffect` の `Navigating` 分岐で `GuidanceState.Guiding.route.geometry` を `MapPolyline` で描画する
- [x] 案内中 polyline は `Guiding.route.roadClassSegments` / `Guiding.route.congestionSegments` を渡して描画する
- [x] `GuidanceState.Guiding.route` が差し替わったら、古い polyline を破棄して新しい geometry を描画する
- [x] `RoutePreviewState` に依存せず、案内中は `GuidanceState.Guiding.route` だけを route line の正とする

受け入れ条件:

- [ ] ナビ開始直後に選択 route の polyline が地図に表示される
- [ ] `GuidanceState.Guiding.route` がリルート後の route に変わると polyline も置き換わる
- [ ] `GuidanceState.Idle` / `Rerouting` / `Failed` では案内中 route line を誤って残さない

### 2.2 案内中のカメラ制御

polyline 描画と同じタイミングで、リルート確認に必要な最低限のカメラ制御だけを入れる。
TBT バナー、ETA、停止ボタン、自車マーカーなどの UI は後回しにする。

- [ ] `MapCameraEffect` に `guidanceState: GuidanceState` を渡す
- [ ] `GuidanceState.Guiding.route.id` が変わったときだけ `Guiding.route.geometry` を `showRouteOverview()` で収める
- [ ] `GuidanceState.Guiding.progress.snappedLocation` を案内中カメラの追従 target にする
- [ ] `GuidanceState.Guiding.progress.bearingDegrees` を案内中カメラの bearing に使う
- [ ] `MapCameraState` に案内中 target / bearing / zoom / tilt を指定できる API を追加する
- [ ] GPS tick ごとに route 全体 fit を再実行せず、route 差し替え時と現在地追従を分ける

受け入れ条件:

- [ ] ナビ開始時に route 全体が一度カメラに収まる
- [ ] GPS tick 更新でカメラ中心が `progress.snappedLocation` に追従する
- [ ] リルート後は新 route 全体が一度収まり、その後は新 route 上の `snappedLocation` に追従する
- [ ] `GuidanceState` が案内中でないときは案内中カメラ制御を止める

### 2.3 route id / registry session 化

ナビ開始ログで `routeId=Recommended` のままになっている。連続検索やリルート検証での payload 取り違えを避けるために直す。

- [ ] `ExtNavRouteRegistry.beginSession()` を追加し、検索開始時に旧 payload を clear する
- [ ] `ExtNavRouteDataSource.searchRoutes()` で session id を作り、全 `RouteDetail.id` / `ExtNavRoutePayload.id` に含める
- [ ] `routeGuidance.priority?.name ?: "route-${index}"` だけを route id にしない
- [ ] `ExtNavRouteRegistryTest` を追加する

受け入れ条件:

- [ ] 連続で別検索しても古い payload を取得できない
- [ ] ナビ開始ログの `routeId` が session id 付きになっている

### 2.4 `CurrentLocationDataSource`

Tracker の origin smoke は通ったので、次は実 GPS tick を流して進捗が変わるかを確認する。

- [x] `FusedLocationProviderClient` を `callbackFlow<UserLocation>` でラップする
- [x] `lastKnown(): UserLocation?` を実装する
- [x] interval / minDistance の引数を持つ
- [x] 権限チェックは呼び出し側責務にする
- [x] Flow 側では provider callback の解除を確実に行う

受け入れ条件:

- [ ] `locationUpdates()` を collect すると 1 秒間隔相当で tick が流れる
- [x] `lastKnown()` が取れない場合は null
- [x] `compileDebugKotlinAndroid` が通る

### 2.5 `NewGuidanceManager` 接続

- [x] `ExtNavRouteRegistry.get(route.id)` から payload を取得する
- [x] `tracker.attach(payload, route)` を呼ぶ
- [x] origin を 1 tick として `tracker.onLocation(location)` に流す
- [x] `locationDataSource.lastKnown()` で初期 tick を試す
- [ ] lastKnown が null の場合は `ExtNavGuidanceBootstrap` を使う
- [x] `locationUpdates()` を collect して `tracker.onLocation(location)` に流す
- [x] origin tick 直後の `tracker.snapshot.value` を `GuidanceState.Guiding(route, progress)` に反映する
- [x] `tracker.snapshot` を collect して `GuidanceState.Guiding(route, progress)` を継続更新する
- [ ] snapshot を `ExtNavRerouteDetector` / `ExtNavAnnouncementScheduler` / 到着判定へ fan-out する
- [x] stop / release で tracker を detach する
- [x] stop / release で session job を止める
- [ ] stop / release で detector、scheduler を止める

受け入れ条件:

- [x] `startGuidance()` 直後に non-null progress が出る
- [ ] GPS tick で残距離が減る
- [x] `stopGuidance()` で `Idle`
- [ ] reroute request 時は `Rerouting` を経て新 route に attach し直す

### 2.6 `ExtNavGuidanceTracker`

初期実装と origin tick smoke は完了。実 GPS / Fake GPS で連続 tick を確認した時点で完了扱いにする。

- [x] `attach(payload, route)` で route geometry の累積距離を作る
- [x] `attach(payload, route)` で GP の `distanceFromStartMetres` を geometry 距離に射影する
- [x] `attach(payload, route)` で intersection の位置を geometry 距離に射影する
- [x] `RouteDistanceMapper` に始点 / 終点 anchor を渡す
- [ ] 中間 anchor が取れる場合は道路名境界 / intersection 対応も `RouteDistanceMapper` に渡す
- [x] `onLocation(location)` で raw GPS を route geometry の最近接 segment に投影する
- [x] 初回 tick は全 segment を探索し、2 tick 目以降は前回 segment から前方探索する
- [x] GPS jitter による小さな後退は hysteresis で抑制する
- [x] `currentCumulativeMeters` / `distanceRemainingMeters` / `matchedSegmentIndex` を更新する
- [x] `snappedLocation` と `bearingDegrees` を `GuidanceProgress` に入れる
- [x] `durationRemainingSeconds` / `etaEpochMillis` を route 所要時間比率から計算する
- [x] `nextGuidancePointIndex` を GP 累積距離の二分探索で決める
- [ ] `nextManeuver` / `followupManeuver` は `ExtNavGuidanceMapper` 経由で作る
- [x] mapper が埋められないデータは null / empty に倒す
- [x] off-route candidate は `projectionErrorMeters` / GPS 精度 / 速度 / bearing 差から 1 tick 分だけ判定する
- [x] reroute の確定、再探索、音声発話、ネットワーク I/O は持たない
- [x] `detach()` で attach 状態、前回 projection、snapshot を clear する

受け入れ条件:

- [x] attach 前の `snapshot.value` は null
- [x] attach 後、`onLocation()` で `snapshot.value` が non-null になる
- [ ] route 上の連続 GPS tick で `currentCumulativeMeters` が単調増加する
- [ ] route 上の連続 GPS tick で `distanceRemainingMeters` が減る
- [x] origin tick では `projectionErrorMeters = 0` になる
- [ ] route 上の連続 GPS では `projectionErrorMeters` が十分小さい
- [ ] GP 通過後に `nextGuidancePointIndex` が次の GP に切り替わる
- [ ] GP が 0 件の route では `nextManeuver = null`
- [ ] geometry が 0 点 / 1 点でも crash しない
- [ ] route から外れた GPS で `isOffRouteCandidate = true` になりうる
- [ ] `ExtNavGuidanceTrackerTest` で上記を確認する

### 2.7 `ExtNavGuidanceMapper`

- [ ] `GuidancePoint.phrases[]` の category から `ManeuverType` を決める
- [ ] geometry の前後 bearing から `ManeuverModifier` を決める
- [ ] GP と最近接 `Intersection` を距離基準で対応付ける
- [ ] `GuidanceManeuverInfo.intersectionName` を詰める
- [ ] `DirectionSign` は `directionSignA/B` など取得済みの実データだけで作る
- [ ] `LaneGuidance` は意味付けが確実なフィールドだけ使い、不確実なら empty
- [ ] `HighwayPanel` は IC / JCT / SA / PA 種別と名称・距離だけ作る

受け入れ条件:

- [ ] mapper は外部ライブラリ型を UI 層へ漏らさない
- [ ] 不確実なデータは null / empty に倒す
- [ ] `ExtNavGuidanceTracker` の簡易 maneuver 生成を mapper 呼び出しに置き換えられる

### 2.8 `ExtNavGuidanceBootstrap`

- [ ] route origin 起点の `GuidanceProgress` を作る
- [ ] 残距離 / 残時間 / ETA を route summary から詰める
- [ ] 最初の GP があれば `nextManeuver` を作る
- [ ] GP が無ければ `nextManeuver = null`

受け入れ条件:

- [ ] 初回 GPS tick 前でも `GuidanceState.Guiding(route, progress)` を出せる
- [ ] bootstrap 後の通常 tick で tracker snapshot に自然に置き換わる

### 2.9 `ExtNavRerouteDetector`

- [ ] `attach(route)` / `onSnapshot(snapshot)` / `detach()` を実装する
- [ ] off-route candidate の連続 tick / 継続秒数を debounce する
- [ ] cooldown を持つ
- [ ] 目的地直前では reroute を抑制する
- [ ] `Request.origin` は raw GPS 位置を使う
- [ ] 未通過の経由地だけを `remainingViaPoints` に残す

受け入れ条件:

- [ ] 1 tick だけの逸脱では `None`
- [ ] 連続逸脱で `Request`
- [ ] cooldown 中は再要求しない
- [ ] `rawLocation == null` では `Request` を出さない

### 2.10 `ExtNavAnnouncementScheduler`

- [ ] `attach(payload, route)` で GP 累積距離と phrase slot を準備する
- [ ] `onSnapshot(snapshot)` で予告 / 直前 / 通過 slot を判定する
- [ ] `(guidancePointIndex, phraseIndex, categoryKey)` で二重発話を防ぐ
- [ ] off-route candidate 中は未発話 phrase の enqueue を抑制する
- [ ] `detach()` で発話済み状態とキューを clear する

受け入れ条件:

- [ ] 同じ phrase が 2 回 enqueue されない
- [ ] GP 通過後に古い発話が enqueue されない
- [ ] 速度に応じた先読み / 遅延が unit test で確認できる

### 2.11 `ExtNavVoicePlayer`

- [ ] `enqueue(ExtNavAnnouncement)` を実装する
- [ ] `clear()` を実装する
- [ ] TTS / SSML / AudioFocus をこのクラスに閉じる
- [ ] scheduler は再生実装に依存せず `ExtNavVoicePlayer` だけを見る

受け入れ条件:

- [ ] `clear()` 後に未再生キューが残らない
- [ ] TTS が使えない場合も scheduler 側を壊さない

### 2.12 DI

- [x] `CurrentLocationDataSource` を datasource module に登録する
- [x] `ExtNavGuidanceTracker` を navigation module に登録する
- [ ] `ExtNavGuidanceMapper` を必要に応じて登録、または internal object として使う
- [ ] `ExtNavGuidanceBootstrap` を登録する
- [ ] `ExtNavRerouteDetector` を登録する
- [ ] `ExtNavAnnouncementScheduler` を登録する
- [ ] `ExtNavVoicePlayer` の具象実装を登録する
- [x] `NewGuidanceManager` の constructor を更新する

受け入れ条件:

- [ ] app 起動時の Koin 解決で落ちない
- [x] `MapViewModel` から `NewGuidanceManager` が取得できる

---

## 3. 後回しの UI / Map 接続

- [ ] `GuidanceState.Guiding.progress.snappedLocation` を自車マーカーに使う
- [ ] 目的地マーカー / 経由地マーカーを案内中 map layer に出す
- [ ] `progress.nextManeuver == null` なら TBT バナーを非表示にする
- [ ] ETA / 停止ボタンは `nextManeuver == null` でも表示する
- [ ] `Rerouting` 中の表示を決める
- [ ] `Arrived` 中の表示を決める

受け入れ条件:

- [ ] 自車マーカーが raw GPS ではなく snappedLocation で動く

---

## 4. テスト

### Unit

- [x] `RouteDistanceMapperTest`
- [ ] `ExtNavGuidanceTrackerTest`
- [ ] `ExtNavGuidanceMapperTest`
- [ ] `ExtNavGuidanceBootstrapTest`
- [ ] `ExtNavRerouteDetectorTest`
- [ ] `ExtNavAnnouncementSchedulerTest`
- [ ] `NewGuidanceManagerTest`

### Manual / Fake GPS

- [x] ナビ開始時に `GuidanceState.Guiding` が出る
- [ ] ルート上を進むと `currentCumulativeMeters` が単調増加する
- [ ] ルート上を進むと `distanceRemainingMeters` が減る
- [ ] 次 GP の距離が減り、通過後に次 GP へ切り替わる
- [ ] ルート外に外すと detector が reroute request を出す
- [ ] reroute 後に新 route の geometry と GP に切り替わる
- [ ] 予告 / 直前 / 通過の発話が 1 回ずつ enqueue される

---

## 5. 確認コマンド

```bash
rtk ./gradlew :core:navigation:compileDebugKotlinAndroid
```

```bash
rtk ./gradlew :core:navigation:detekt
```

```bash
rtk ./gradlew :composeApp:compileDebugKotlinAndroid
```

```bash
rtk ./gradlew :core:navigation:testDebugUnitTest --tests me.matsumo.onenavi.core.navigation.extnav.RouteDistanceMapperTest
```

```bash
rtk adb logcat -s NewGuidanceManager CurrentLocationDataSource
```

```bash
rtk git diff --check
```

禁止語確認は git-ignored な pattern file を使って差分に対して実施する。

```bash
rtk git diff --cached | grep -iEf .claude/forbidden.txt
```

---

## 6. 既知の注意点

- `core:navigation:testDebugUnitTest` は既存の `PhonemeConverterTest` の未解決参照で compile が落ちる状態がある。
  mapper / guidance 系のテスト確認時は、この既存問題と切り分ける。
- `ExtNavGuidanceTracker` の `LaneGuidance` / `DirectionSign` / `HighwayPanel` は mapper 実装後に埋める。
- `RouteDistanceMapper` は中間アンカーを受け取れるが、tracker 側はまず始点 / 終点アンカーで動かす。
  GP のズレが大きい route では mapper に中間アンカーを渡す処理を追加する。
- 現在の route id は `Recommended` など priority 名だけなので、継続走行テストを詰める前に session id 付きへ変更する。
