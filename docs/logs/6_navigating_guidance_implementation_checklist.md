# Navigating Guidance Implementation Checklist

> 作成日: 2026-05-19
> 更新日: 2026-05-21
> 参照仕様: `docs/spec/28_navigating_state_and_guidance_progress_design.md`
> 目的: 別環境でも実装の残タスクと受け入れ条件を追えるようにする。仕様本文ではなく作業チェックリスト。

---

## 0. 前提

- 事業者名・製品名・パッケージ root の実名は追加コード / コメント / ドキュメントに書かない。
- 外部 API 由来の公開表記は「外部ナビ API ライブラリ」、クラス prefix は `ExtNav` を使う。
- 案内中の通常 GPS tick では ROUTE / GUIDE を再取得しない。
- 渋滞・規制 overlay は tracker / `GuidanceProgress` に混ぜない。
- `GuidanceProgress` は UI 向け、`ExtNavProgressSnapshot` は周辺コンポーネント向け。
- `GuidanceProgress.snappedLocation` は案内 route geometry 上に投影した位置であり、案内中以外の現在地モデルにはしない。
- 地図表示用の自車位置は `VehicleLocationState` を正とし、案内中は route-snapped、案内中以外は SDK road-snapped / raw GPS を内部で切り替える。

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

## 2. 実装チェックリスト

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

- [x] ナビ開始直後に選択 route の polyline が地図に表示される
- [x] `GuidanceState.Guiding.route` がリルート後の route に変わると polyline も置き換わる
- [x] `GuidanceState.Idle` / `Rerouting` / `Failed` では案内中 route line を誤って残さない

### 2.2 地図向け自車位置 state の統一

地図 UI は `GuidanceProgress` と SDK 現在地レイヤーを直接切り替えない。
自車アイコン・カメラは常に `VehicleLocationState` だけを読み、state の生成元を内部で切り替える。
案内中の正は `GuidanceState.Guiding.progress.snappedLocation`、案内中以外の正は SDK road-snapped location とする。
Preview 中の route geometry に現在地を勝手に投影しない。

- [x] `VehicleLocationSource` を追加し、`ROUTE_SNAPPED` / `SDK_ROAD_SNAPPED` / `RAW_GPS` を表現する
- [x] `VehicleLocationState` を追加し、座標、方位、精度、時刻、source を保持する
- [x] `RoadSnappedLocationProvider` を `callbackFlow` でラップし、collect 中だけ listener を登録する
- [x] `RoadSnappedLocationProvider.LocationListener.IS_ROAD_SNAPPED_KEY` が取れる場合は `SDK_ROAD_SNAPPED` / `RAW_GPS` の判定に使う
- [x] SDK provider が未初期化、または road-snapped event が来ない場合は raw GPS fallback を使えるようにする
- [x] `MapViewModel` で `GuidanceState` と SDK / raw GPS 由来の位置 state を合成し、`vehicleLocationState` を公開する
- [x] `GuidanceState.Guiding` 中は `Guiding.progress.snappedLocation` / `bearingDegrees` から `VehicleLocationState(source = ROUTE_SNAPPED)` を作る
- [x] `GuidanceState.Guiding` 以外では SDK / raw GPS 由来の `VehicleLocationState` をそのまま使う
- [x] `MapEffect` の自車アイコン effect は `vehicleLocationState` だけを見る
- [x] 案内中 / 非案内中とも SDK / GoogleMap の my-location layer とは二重表示しない
- [x] 自車アイコン asset を compose resources の vector XML として追加する
- [x] 過去の `ic_vehicle_puck.xml` を compose resources に移植する
- [x] 移植元は `4b4cfe6` の `feature/home/src/androidMain/res/drawable/ic_vehicle_puck.xml` とし、64dp / 64 viewport の影付き・白縁・青系矢印 puck の見た目を使う
- [x] compose resources で gradient / `aapt:attr` が扱いにくい場合は、単色 fill に落として描画安定性を優先する
- [x] 自車アイコンは route polyline より前面、callout / 操作 UI より背面の zIndex に置く

受け入れ条件:

- [x] 地図 UI は自車アイコン座標を `VehicleLocationState` からだけ読む
- [x] 案内中の `VehicleLocationState.source` は `ROUTE_SNAPPED`
- [x] 案内中の自車アイコンが route geometry 上の `snappedLocation` に表示される
- [x] 案内中以外の `VehicleLocationState.source` は `SDK_ROAD_SNAPPED` または `RAW_GPS`
- [x] 案内中以外は Preview route に現在地が吸着しない
- [x] `bearingDegrees` 更新で自車アイコンの向きが変わる
- [x] 案内中 / 非案内中で自車アイコンが二重表示されない
- [x] map 画面を離れる、または collect が止まって 5 秒猶予後に SDK road-snapped listener が解除される

### 2.3 案内中のカメラ制御

自車位置を `VehicleLocationState` に統一した後、リルート確認に必要な最低限のカメラ制御を入れる。
TBT バナー、ETA、停止ボタンなどの UI は後回しにする。
案内中カメラは `VehicleLocationState` を正として追従し、案内開始時は現在設定に関わらず
3D / `DEFAULT_CAMERA_ZOOM` にアニメーションする。通常時の自車アイコンは padding 考慮後の画面中心に置き、
案内中 3D のときだけ画面手前側に見える target 補正を行う。
案内地点接近時の一時フォーカスでは、ユーザーが選んだ 3D / 真上モードとズーム値を
フォーカス解除後に復元できるようにする。
フォーカス開始前から案内地点を視覚的に把握できるよう、次の案内地点とその次の案内地点の
最大 2 件に CallOut を表示する。カメラは次の案内地点 100m 手前で heading-up の真上表示かつ
拡大表示へ切り替える。

- [x] `MapCameraEffect` に `vehicleLocationState: VehicleLocationState?` を渡す
- [x] `VehicleLocationState.location` を案内中カメラの追従 target にする
- [x] `VehicleLocationState.bearingDegrees` を案内中カメラの bearing に使う
- [x] `MapCameraState` に案内開始時 target / bearing / zoom / tilt を指定する API を追加する
- [x] ナビ開始時は現在設定に関わらず 3D / `DEFAULT_CAMERA_ZOOM` へアニメーションする
- [x] 通常時は 3D / ノースアップとも自車アイコンが padding 考慮後の画面中心に表示される
- [x] 案内中 3D のみ自車アイコンが画面中央より手前側に表示されるよう target を補正する
- [x] コンパス button で 3D heading-up / 2D north-up を手動で切り替えられる
- [x] コンパス button 操作後は自車追従へ復帰する
- [x] コンパス button の切り替えは `flyCameraTo` と同じ減衰補間でアニメーションする
- [x] follow 中の `+` / `-` zoom 操作では追従を維持する
- [x] gesture zoom / rotate / tilt はユーザー操作として追従を解除する
- [x] 自車アイコンと follow 中カメラは frame ごとの推定 pose で滑らかに更新する
- [x] 静止時の GPS / heading ブレ、古い lastKnown、粗い初期 fix、遠距離 jump を抑制する
- [x] 手動で選んだ通常モードと通常ズーム値を案内地点フォーカス復元用に `MapCameraState` に保持する
- [x] `progress.nextManeuver.distanceToManeuverMeters <= 100m` になったら案内地点フォーカスを 1 回だけ開始する
- [x] 案内地点フォーカス中は自動で heading-up の真上モードにし、案内地点確認用のズーム値へ自動拡大する
- [x] `nextGuidancePointIndex` が変わる、または対象 GP を通過したら案内地点フォーカスを解除する
- [x] 案内地点フォーカス解除後は、フォーカス前の 3D / 真上モードとズーム値へ戻し、追従中は最新 pose の向き・傾きへ戻す
- [x] 案内地点フォーカス中にユーザーが手動操作した場合の解除 / 継続ルールを決める
- [ ] GPS tick ごとに route 全体 fit を再実行せず、route 差し替え時と現在地追従を分ける

受け入れ条件:

- [x] ナビ開始時に 3D / `DEFAULT_CAMERA_ZOOM` へアニメーションする
- [x] GPS tick 更新でカメラ中心が `VehicleLocationState.location` に追従する
- [ ] リルート後は新 route geometry の polyline に置き換わり、その後は `VehicleLocationState(source = ROUTE_SNAPPED)` に追従する
- [x] `GuidanceState` が案内中でないときは案内中カメラ制御を止める
- [x] 案内中に 3D / 真上モードを切り替えられる
- [x] 次の案内地点 100m 手前で自動で heading-up の真上モードかつ拡大表示になる
- [x] 案内地点通過後に、接近前のモードとズーム値へ戻り、追従中は最新 pose の向き・傾きへ戻る
- [x] 次の案内地点でも同じフォーカス動作を 1 回だけ行う

### 2.4 route id / registry session 化

ナビ開始ログで `routeId=Recommended` のままになっている。連続検索やリルート検証での payload 取り違えを避けるために直す。

- [ ] `ExtNavRouteRegistry.beginSession()` を追加し、検索開始時に旧 payload を clear する
- [ ] `ExtNavRouteDataSource.searchRoutes()` で session id を作り、全 `RouteDetail.id` / `ExtNavRoutePayload.id` に含める
- [ ] `routeGuidance.priority?.name ?: "route-${index}"` だけを route id にしない
- [ ] `ExtNavRouteRegistryTest` を追加する

受け入れ条件:

- [ ] 連続で別検索しても古い payload を取得できない
- [ ] ナビ開始ログの `routeId` が session id 付きになっている

### 2.5 `CurrentLocationDataSource`

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

### 2.6 `NewGuidanceManager` 接続

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

### 2.7 `ExtNavGuidanceTracker`

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

### 2.8 `ExtNavGuidanceMapper`

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

### 2.9 `ExtNavGuidanceBootstrap`

- [ ] route origin 起点の `GuidanceProgress` を作る
- [ ] 残距離 / 残時間 / ETA を route summary から詰める
- [ ] 最初の GP があれば `nextManeuver` を作る
- [ ] GP が無ければ `nextManeuver = null`

受け入れ条件:

- [ ] 初回 GPS tick 前でも `GuidanceState.Guiding(route, progress)` を出せる
- [ ] bootstrap 後の通常 tick で tracker snapshot に自然に置き換わる

### 2.10 `ExtNavRerouteDetector`

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

### 2.11 `ExtNavAnnouncementScheduler`

- [ ] `attach(payload, route)` で GP 累積距離と phrase slot を準備する
- [ ] `onSnapshot(snapshot)` で予告 / 直前 / 通過 slot を判定する
- [ ] `(guidancePointIndex, phraseIndex, categoryKey)` で二重発話を防ぐ
- [ ] off-route candidate 中は未発話 phrase の enqueue を抑制する
- [ ] `detach()` で発話済み状態とキューを clear する

受け入れ条件:

- [ ] 同じ phrase が 2 回 enqueue されない
- [ ] GP 通過後に古い発話が enqueue されない
- [ ] 速度に応じた先読み / 遅延が unit test で確認できる

### 2.12 `ExtNavVoicePlayer`

- [ ] `enqueue(ExtNavAnnouncement)` を実装する
- [ ] `clear()` を実装する
- [ ] TTS / SSML / AudioFocus をこのクラスに閉じる
- [ ] scheduler は再生実装に依存せず `ExtNavVoicePlayer` だけを見る

受け入れ条件:

- [ ] `clear()` 後に未再生キューが残らない
- [ ] TTS が使えない場合も scheduler 側を壊さない

### 2.13 DI

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

### 2.14 案内地点 CallOut と 100m カメラフォーカス

案内地点に近づく前から地図上で「どこで曲がるか」を把握できるよう、案内中 route 上の
次の案内地点とその次の案内地点に CallOut を表示する。CallOut は地図上の案内地点に tail を固定し、
カメラフォーカスは次の案内地点のみを対象にする。

- [x] `GuidanceManeuverInfo` に案内地点座標を追加する、または map 表示専用の `GuidancePointCallOutState` を追加する
- [x] `ExtNavGuidanceTracker.buildManeuverInfo()` で GP の geometry 累積距離から案内地点座標を算出して渡す
- [x] `progress.nextManeuver` / `progress.followupManeuver` から最大 2 件の案内地点 CallOut request を作る
- [x] CallOut の target は `MapCallOutTarget.PointFixed` を使い、tail 先端を案内地点座標に固定する
- [x] CallOut の優先度は「次の案内地点 > その次の案内地点」とし、route preview callout より案内中 CallOut を優先表示する
- [x] CallOut の表示は方向アイコン + 交差点名を基本にし、交差点名が無い場合はアイコンのみを表示する
- [x] CallOut には距離を表示せず、1m 単位の更新で marker bitmap を作り直さない
- [x] `MapEffect` の `Navigating` 分岐で案内地点 CallOut を描画し、`GuidanceState.Guiding` 以外では破棄する
- [x] `MapCameraEffect` に `guidanceState: GuidanceState` を渡し、`Guiding.progress.nextManeuver` からフォーカス判定する
- [x] `MapCameraState` に案内地点フォーカス状態を追加し、フォーカス前の perspective / zoom / 追従状態を保持する
- [x] `distanceToManeuverMeters <= 100` で対象 GP に 1 回だけフォーカスし、heading-up の真上モード + 案内地点確認用ズームへ `flyCameraTo` する
- [x] 案内地点フォーカス開始 / 復元は `flyCameraTo` の既定時間・減衰補間を使う
- [x] 案内地点フォーカス開始 / 復元アニメーション中も、frame ごとに最新 pose から終点 target / bearing を更新する
- [x] フォーカス中も自車位置追従は維持し、`updateVehiclePose` は heading-up の真上モード + フォーカス zoom で camera target を更新する
- [x] 対象 `guidancePointIndex` が変わった、`distanceToManeuverMeters <= 0`、案内停止、またはユーザー gesture でフォーカスを解除する
- [x] ユーザー gesture で解除した GP には再進入せず、次の GP に切り替わるまで通常追従を尊重する
- [x] フォーカス解除後は保存していた perspective / zoom へ戻す。追従中は最新 pose の target / bearing に追従し、ユーザー gesture 解除時はユーザー操作後の状態を優先する

受け入れ条件:

- [x] 案内中、次の案内地点とその次の案内地点に最大 2 件の CallOut が表示される
- [x] GP 通過後は古い CallOut が消え、新しい次 / その次の案内地点へ更新される
- [x] 案内地点 CallOut は route polyline より前面、自車アイコンより前面、操作 UI より背面に見える
- [x] 次の案内地点 100m 手前で 1 回だけ heading-up の真上 + 拡大表示へ切り替わる
- [x] 案内地点通過後、または次 GP へ切り替わった後に通常の追従モードへ戻る
- [x] フォーカス中にユーザーが地図 gesture を行うと自動フォーカスが解除され、同じ GP では再度自動フォーカスしない
- [x] route 逸脱中 (`OFF_ROUTE_CANDIDATE` / `OFF_ROUTE_CONFIRMED`) は古い route 上の GP フォーカスを開始しない

---

## 3. 後回しの UI / Map 接続

- [ ] 目的地マーカー / 経由地マーカーを案内中 map layer に出す
- [ ] `progress.nextManeuver == null` なら TBT バナーを非表示にする
- [ ] ETA / 停止ボタンは `nextManeuver == null` でも表示する
- [ ] `Rerouting` 中の表示を決める
- [ ] `Arrived` 中の表示を決める

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
- [x] ナビ開始時に選択 route の polyline が表示される
- [x] ナビ開始時に 3D / `DEFAULT_CAMERA_ZOOM` へアニメーションする
- [x] 案内中以外で静止時の位置ブレ・heading ブレが抑制される
- [x] follow 中の自車アイコンとカメラが滑らかに追従する
- [ ] ルート上を進むと `currentCumulativeMeters` が単調増加する
- [ ] ルート上を進むと `distanceRemainingMeters` が減る
- [ ] 次 GP の距離が減り、通過後に次 GP へ切り替わる
- [ ] ルート外に外すと detector が reroute request を出す
- [ ] reroute 後に新 route の geometry と GP に切り替わる
- [ ] 予告 / 直前 / 通過の発話が 1 回ずつ enqueue される

---

## 5. 確認コマンド

```bash
rtk ./gradlew :feature:map:compileDebugKotlinAndroid --no-configuration-cache
```

```bash
rtk ./gradlew detekt --no-configuration-cache
```

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
