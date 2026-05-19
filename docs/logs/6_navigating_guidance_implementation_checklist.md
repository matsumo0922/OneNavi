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

---

## 2. 次に実装するクラス

### 2.1 `ExtNavGuidanceTracker`

現行の `ExtNavGuidanceTracker` は暫定実装扱い。下記を満たした時点で完了とする。

- [ ] `attach(payload, route)` で route geometry の累積距離を作る
- [ ] `attach(payload, route)` で GP の `distanceFromStartMetres` を geometry 距離に射影する
- [ ] `attach(payload, route)` で intersection の位置を geometry 距離に射影する
- [ ] `RouteDistanceMapper` に始点 / 終点 anchor を渡す
- [ ] 中間 anchor が取れる場合は道路名境界 / intersection 対応も `RouteDistanceMapper` に渡す
- [ ] `onLocation(location)` で raw GPS を route geometry の最近接 segment に投影する
- [ ] 初回 tick は全 segment を探索し、2 tick 目以降は前回 segment から前方探索する
- [ ] GPS jitter による小さな後退は hysteresis で抑制する
- [ ] `currentCumulativeMeters` / `distanceRemainingMeters` / `matchedSegmentIndex` を更新する
- [ ] `snappedLocation` と `bearingDegrees` を `GuidanceProgress` に入れる
- [ ] `durationRemainingSeconds` / `etaEpochMillis` を route 所要時間比率から計算する
- [ ] `nextGuidancePointIndex` を GP 累積距離の二分探索で決める
- [ ] `nextManeuver` / `followupManeuver` は `ExtNavGuidanceMapper` 経由で作る
- [ ] mapper が埋められないデータは null / empty に倒す
- [ ] off-route candidate は `projectionErrorMeters` / GPS 精度 / 速度 / bearing 差から 1 tick 分だけ判定する
- [ ] reroute の確定、再探索、音声発話、ネットワーク I/O は持たない
- [ ] `detach()` で attach 状態、前回 projection、snapshot を clear する
- [ ] ログ出力は入れない

受け入れ条件:

- [ ] attach 前の `snapshot.value` は null
- [ ] attach 後、`onLocation()` で `snapshot.value` が non-null になる
- [ ] route 上の連続 GPS tick で `currentCumulativeMeters` が単調増加する
- [ ] route 上の連続 GPS tick で `distanceRemainingMeters` が減る
- [ ] route 上の GPS では `projectionErrorMeters` が十分小さい
- [ ] GP 通過後に `nextGuidancePointIndex` が次の GP に切り替わる
- [ ] GP が 0 件の route では `nextManeuver = null`
- [ ] geometry が 0 点 / 1 点でも crash しない
- [ ] route から外れた GPS で `isOffRouteCandidate = true` になりうる
- [ ] `ExtNavGuidanceTrackerTest` で上記を確認する

### 2.2 `ExtNavGuidanceMapper`

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

### 2.3 `CurrentLocationDataSource`

- [ ] `FusedLocationProviderClient` を `callbackFlow<UserLocation>` でラップする
- [ ] `lastKnown(): UserLocation?` を実装する
- [ ] interval / minDistance の引数を持つ
- [ ] 権限チェックは呼び出し側責務にする
- [ ] Flow 側では provider callback の解除を確実に行う

受け入れ条件:

- [ ] `locationUpdates()` を collect すると 1 秒間隔相当で tick が流れる
- [ ] `lastKnown()` が取れない場合は null
- [ ] `compileDebugKotlinAndroid` が通る

### 2.4 `ExtNavGuidanceBootstrap`

- [ ] route origin 起点の `GuidanceProgress` を作る
- [ ] 残距離 / 残時間 / ETA を route summary から詰める
- [ ] 最初の GP があれば `nextManeuver` を作る
- [ ] GP が無ければ `nextManeuver = null`

受け入れ条件:

- [ ] 初回 GPS tick 前でも `GuidanceState.Guiding(route, progress)` を出せる
- [ ] bootstrap 後の通常 tick で tracker snapshot に自然に置き換わる

### 2.5 `ExtNavRerouteDetector`

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

### 2.6 `ExtNavAnnouncementScheduler`

- [ ] `attach(payload, route)` で GP 累積距離と phrase slot を準備する
- [ ] `onSnapshot(snapshot)` で予告 / 直前 / 通過 slot を判定する
- [ ] `(guidancePointIndex, phraseIndex, categoryKey)` で二重発話を防ぐ
- [ ] off-route candidate 中は未発話 phrase の enqueue を抑制する
- [ ] `detach()` で発話済み状態とキューを clear する

受け入れ条件:

- [ ] 同じ phrase が 2 回 enqueue されない
- [ ] GP 通過後に古い発話が enqueue されない
- [ ] 速度に応じた先読み / 遅延が unit test で確認できる

### 2.7 `ExtNavVoicePlayer`

- [ ] `enqueue(ExtNavAnnouncement)` を実装する
- [ ] `clear()` を実装する
- [ ] TTS / SSML / AudioFocus をこのクラスに閉じる
- [ ] scheduler は再生実装に依存せず `ExtNavVoicePlayer` だけを見る

受け入れ条件:

- [ ] `clear()` 後に未再生キューが残らない
- [ ] TTS が使えない場合も scheduler 側を壊さない

### 2.8 `NewGuidanceManager` 接続

- [ ] `ExtNavRouteRegistry.get(route.id)` から payload を取得する
- [ ] `tracker.attach(payload, route)` を呼ぶ
- [ ] `locationDataSource.lastKnown()` で初期 tick を試す
- [ ] lastKnown が null の場合は `ExtNavGuidanceBootstrap` を使う
- [ ] `locationUpdates()` を collect して `tracker.onLocation(location)` に流す
- [ ] `tracker.snapshot` を collect して `GuidanceState.Guiding(route, progress)` を更新する
- [ ] snapshot を `ExtNavRerouteDetector` / `ExtNavAnnouncementScheduler` / 到着判定へ fan-out する
- [ ] stop / release で session job、tracker、detector、scheduler を止める

受け入れ条件:

- [ ] `startGuidance()` 直後に non-null progress が出る
- [ ] GPS tick で残距離が減る
- [ ] `stopGuidance()` で `Idle`
- [ ] reroute request 時は `Rerouting` を経て新 route に attach し直す

### 2.9 DI

- [ ] `CurrentLocationDataSource` を datasource module に登録する
- [ ] `ExtNavGuidanceTracker` を navigation module に登録する
- [ ] `ExtNavGuidanceMapper` を必要に応じて登録、または internal object として使う
- [ ] `ExtNavGuidanceBootstrap` を登録する
- [ ] `ExtNavRerouteDetector` を登録する
- [ ] `ExtNavAnnouncementScheduler` を登録する
- [ ] `ExtNavVoicePlayer` の具象実装を登録する
- [ ] `NewGuidanceManager` の constructor を更新する

受け入れ条件:

- [ ] app 起動時の Koin 解決で落ちない
- [ ] `MapViewModel` から `NewGuidanceManager` が取得できる

---

## 3. UI / Map 接続

- [ ] `MapScreen` で `GuidanceState.Guiding.route` をルートラインの正とする
- [ ] `GuidanceState.Guiding.progress.snappedLocation` を自車マーカーに使う
- [ ] `progress.nextManeuver == null` なら TBT バナーを非表示にする
- [ ] ETA / 停止ボタンは `nextManeuver == null` でも表示する
- [ ] `Rerouting` 中の表示を決める
- [ ] `Arrived` 中の表示を決める

受け入れ条件:

- [ ] reroute 後に `Guiding.route.geometry` の polyline に置き換わる
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
rtk ./gradlew :core:navigation:testDebugUnitTest --tests me.matsumo.onenavi.core.navigation.extnav.RouteDistanceMapperTest
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
