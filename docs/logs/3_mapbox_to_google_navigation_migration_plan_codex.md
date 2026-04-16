# Mapbox to Google Full Migration Plan

Date: 2026-04-16
Author: Codex
Status: Draft

> 2026-04-16 時点の補足:
> この文書は移行着手前の計画書であり、PR #10 の実装結果とは一部差分がある。
> 現在の Android 実装は `android-maps-compose` + Google Routes API + Places API + 独自 guidance を採用しており、
> Android Auto は manifest 公開を外して別タスクへ分離、レーン案内と道路名抽出も Google Routes API 単体では未達である。

## 目的

OneNavi の Android 実装を Mapbox Navigation SDK 中心の構成から Google Maps Platform / Google Navigation SDK 中心の構成へ完全移行する作業計画をまとめる。

最終状態では、Mapbox SDK / Mapbox token / Mapbox Maven repository / Mapbox Android Auto extension をすべて削除し、現在 OneNavi が持つ機能を Google 側の SDK/API で実現する。

この文書は、過去の会話コンテキストなしで読めることを前提に、以下を網羅する。

1. 移行検討の背景
2. 現在の Mapbox 依存範囲
3. Google 側で採用する SDK/API
4. Mapbox 完全削除を前提にした段階的な移行手順
5. 工数見積もり
6. リスクと検証項目
7. 完了条件

---

## 背景

同一の出発地・目的地で Google Maps と OneNavi を比較したところ、Google Maps は銚子信用金庫本店付近の 3 車線区間で、約 150m 先の推奨レーン案内を表示した。

一方、OneNavi は同地点のレーン案内を表示せず、約 2km 先の分岐案内を先に表示した。

対象ルート:

- 出発地: マクドナルド イオンモール銚子店
- 目的地: ヤマタくん
- Google Maps URL: ユーザー提供の `google.com/maps/dir/...`
- 問題地点: 銚子信用金庫本店付近、県道 244 号周辺

Mapbox Directions API に対して、以下のようなパラメータでルートレスポンスを確認した。

- profile: `mapbox/driving`
- profile: `mapbox/driving-traffic`
- `steps=true`
- `banner_instructions=true`
- `voice_instructions=true`
- `language=ja`
- `overview=full`
- `alternatives=true`

確認結果として、問題地点周辺の step / intersection / banner instruction に、Google Maps が表示した 150m 先の推奨レーン案内に相当する lane component が入っていなかった。

つまり、OneNavi の UI が単に表示し忘れているだけではなく、現時点では Mapbox のルートレスポンス自体に当該レーン案内イベントが含まれていない可能性が高い。

この背景から、OneNavi は Mapbox の案内品質に依存し続けず、Google Maps Platform / Google Navigation SDK へ完全移行する前提で作業を計画する。

---

## 結論

今回の目的は、Google Maps と同等に近い案内品質、特にレーン案内品質を OneNavi 内で得ることである。

したがって、単なる Google Maps SDK への置換では不十分であり、ナビ本線は Google Navigation SDK へ移行する。

最終状態:

1. Mapbox SDK は全削除する。
2. Mapbox token は不要にする。
3. Mapbox Maven repository / credentials は削除する。
4. ルート検索、経路プレビュー、地図表示、現在地追従、ナビセッション、TTS 連携を Google 側で実現する。
5. レーン案内、道路名抽出、Android Auto は別途不足を埋める。
6. OneNavi の既存機能は Google 実装で機能 parity を満たす。

推奨方針は以下。

1. まず Google Navigation SDK の PoC を作る。
2. 問題の銚子ルートで、Google Navigation SDK が期待するレーン案内を出せるか確認する。
3. PoC 結果を踏まえて Google 実装の不足箇所を洗い出す。
4. 不足箇所を Google Routes API、Navigation SDK turn-by-turn data feed、Places API、Android Auto integration、必要な補助 DB / adapter で埋める。
5. 最後に Mapbox dependency を削除し、Mapbox へ戻す経路を残さない。

Google Maps SDK / Maps Compose は地図表示用であり、Google Maps アプリ相当のターンバイターン案内・リルート・レーン案内の中核ではない。

そのため、本計画では次の構成を採用する。

| 領域 | 採用する Google 側コンポーネント | 理由 |
|---|---|---|
| 地図表示 | Google Navigation SDK `NavigationView` | Navigation SDK が Maps SDK を内包するため |
| 経路プレビュー | Google Routes API + route token | ナビ開始前の代替ルート表示と route token 連携のため |
| ナビセッション | Google Navigation SDK `Navigator` | ターンバイターン、リルート、到着判定のため |
| レーン / maneuver | Navigation SDK turn-by-turn data feed | 既存 OneNavi UI に案内情報を流すため |
| 検索 | Google Places API | 既に導入済みで、Google waypoint と相性がよいため |
| Android Auto | Google Navigation SDK for Auto | Mapbox Android Auto extension を削除するため |

---

## 公式ドキュメント上の重要制約

### Google Navigation SDK は Maps SDK を内包する

Google Navigation SDK for Android v5 以降は Maps SDK for Android を含む。

そのため、Navigation SDK を使う場合、同じアプリに別途 Maps SDK for Android を依存関係として追加する構成は避ける必要がある。

この制約により、既存の Mapbox Compose map を単純に Google Maps Compose に差し替え、その上で別途 Google Navigation SDK を併用する、という設計は危険である。

本移行では、ナビ本線は Google Navigation SDK の `NavigationView` / `Navigator` を中心に設計する。

参考:

- Google Navigation SDK overview: https://developers.google.com/maps/documentation/navigation/android-sdk/overview
- Google Navigation SDK setup overview: https://developers.google.com/maps/documentation/navigation/android-sdk/setup-overview
- `NavigationView` reference: https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/libraries/navigation/NavigationView

### Routes API は経路プレビューに使える

Google Routes API は route token を返せる。

Navigation SDK 側で route token を使える場合、経路プレビューで見せたルートと実ナビを近づけられる。

ただし、交通状況・SDK 内部判断・利用 API の制約によって、プレビューと実ナビが完全一致しない可能性は残る。

参考:

- Routes API overview: https://developers.google.com/maps/documentation/routes/overview
- Route token: https://developers.google.com/maps/documentation/routes/route_token
- Navigation SDK route customization: https://developers.google.com/maps/documentation/navigation/android-sdk/customize-route

### Turn-by-turn data feed が必要

既存 OneNavi は独自 UI と独自 TTS を持つ。

Google 標準 UI / 標準音声へ全面的に寄せるなら実装は比較的単純になるが、既存 UI を維持するなら Google Navigation SDK の turn-by-turn data feed から maneuver / lane / step 情報を受け取り、既存の `ManeuverInfo` / `LaneInfo` / `GuidanceEvent` に変換する必要がある。

参考:

- Turn-by-turn data feed: https://developers.google.com/maps/documentation/navigation/android-sdk/tbt-feed

### Android Auto は別リスク

Google Navigation SDK には Android Auto 向けの `NavigationViewForAuto` があるが、公式ドキュメント上は Preview であり、Google Play リリース前の審査も必要になる。

Android Auto は本体移行とは別フェーズに分ける。

参考:

- Navigation for Android Auto: https://developers.google.com/maps/documentation/navigation/android-sdk/android-auto

---

## 現在の Mapbox 依存範囲

調査時点で、`com.mapbox` / `Mapbox` / `mapbox` に関係する Kotlin ファイルは約 33 ファイルある。

影響は単なる地図表示に留まらず、以下にまたがっている。

1. Gradle dependency / Maven repository
2. アプリ初期化
3. 経路取得
4. 経路状態管理
5. 地図描画
6. カメラ制御
7. 現在地・enhanced location
8. ナビセッション
9. レーン・maneuver UI
10. TTS / guidance event 生成
11. Android Auto
12. debug fake GPS
13. common model の provider-specific な型

### Build configuration

対象:

- `gradle/libs.versions.toml`
- `settings.gradle.kts`
- `composeApp/build.gradle.kts`
- `core/navigation/build.gradle.kts`
- `core/datasource/build.gradle.kts`
- `feature/home/build.gradle.kts`

現在の主な Mapbox dependency:

- `com.mapbox.navigationcore:android-ndk27`
- `com.mapbox.navigationcore:ui-maps`
- `com.mapbox.navigationcore:ui-components`
- `com.mapbox.navigationcore:tripdata`
- `com.mapbox.extension:maps-compose`
- `com.mapbox.extension:maps-compose-style`
- `com.mapbox.extension:maps-compose-localization`
- `com.mapbox.search:mapbox-search-android`
- `com.mapbox.navigationcore:androidauto`

既に存在する Google dependency:

- `com.google.android.libraries.places:places`
- `com.google.android.gms:play-services-location`

移行後に追加・調整する候補:

- Google Navigation SDK for Android
- Google Routes API client or direct REST client
- Google Maps Platform API key configuration

### アプリ初期化

対象:

- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/OneNaviApplication.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/MainActivity.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarAppService.kt`

現在の役割:

- `MapboxCommonSettings.LANGUAGE`
- `MapboxCommonSettings.WORLDVIEW`
- `MapboxNavigationApp.setup(...)`
- `MapboxNavigationApp.attach(...)`
- `MapboxOptions.accessToken`

移行方針:

- Mapbox 初期化を削除する。
- Google Navigation SDK の初期化方式へ置換する。
- API key は `BuildKonfig` / manifest metadata / Gradle property のどれを正とするか決める。
- 既存の `AppConfig.mapBoxToken` は削除または deprecated にする。

### 経路取得

対象:

- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/MapboxNavigationRouteDataSource.kt`
- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/di/DataSourceModule.android.kt`
- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/RouteItem.kt`
- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/RouteResult.kt`

現在の役割:

- `MapboxNavigation.requestRoutes(...)`
- `RouteOptions.builder()`
- `coordinatesList(...)`
- `alternatives(true)`
- `DirectionsRoute` から距離・時間・geometry・toll 情報を抽出
- `NavigationRoute` を `platformRoute` として保持

移行方針:

- `MapboxNavigationRouteDataSource` を `GoogleRouteDataSource` に置換する。
- プレビュー用途では Google Routes API の `computeRoutes` を使う。
- 実ナビ用途では Navigation SDK に渡せる route token / waypoint / routing options を保持する。
- `platformRoute` に SDK 型を直接入れる設計は縮小する。

注意:

- Google Routes API の route response と Navigation SDK の route/session model は Mapbox の `NavigationRoute` と 1:1 対応しない。
- 代替ルートの ID、選択、ラベル、所要時間、料金表示は再設計が必要。

### 経路状態管理

対象:

- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/RouteManager.kt`

現在の役割:

- `StateFlow<List<NavigationRoute>>`
- `RoutesObserver`
- `MapboxNavigation.setNavigationRoutes(...)`
- alternative metadata
- primary route の選択

移行方針:

- `NavigationRoute` ではなく、provider-neutral な `RouteHandle` / `RouteSelection` を持つ。
- Google Routes API の route token、encoded polyline、summary 情報を関連付ける。
- 実ナビ開始時に Google Navigation SDK の `Navigator` へ渡す。

再設計対象:

- route id
- selected route
- alternative route
- reroute 後の route replacement
- UI に表示する route metadata

### 地図描画

対象:

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenEffects.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapNumberedPin.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapWaypointPin.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapRouteCallout.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/util/MapCalloutPositioner.kt`

現在の役割:

- Mapbox Compose `MapboxMap`
- `MapViewportState`
- `MapboxStandardStyle`
- `MapEffect` / `DisposableMapEffect`
- Mapbox `Marker`
- route line API
- POI click
- long click
- location puck
- route polyline click hit-test
- route callout positioning

移行方針:

- Google Navigation SDK 本線なら `NavigationView` を Compose の `AndroidView` でホストする。
- ルートプレビューも最終的には Google Navigation SDK / Google Maps Platform 側の map view 上で実現する。
- 既存 Mapbox route line API は廃止する。
- ルート選択の hit-test は Google 側で自前実装するか、UI でリスト選択中心に寄せる。

判断ポイント:

- ナビ中も既存デザインを維持するか。
- Google 標準 `NavigationView` UI に寄せるか。
- ルートプレビューとナビ中画面を同じ view で扱うか、分けるか。

### カメラ制御

対象:

- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/CameraManager.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenEffects.kt`

現在の役割:

- `NavigationCamera`
- `MapboxNavigationViewportDataSource`
- `NavigationLocationProvider`
- `MapView.cameraForCoordinates(...)`
- following / overview / idle
- maneuver zoom
- route padding
- enhanced location の反映

移行方針:

- Google Navigation SDK の camera control / `NavigationView` の標準追従を使う。
- プレビュー中のカメラは `LatLngBounds` / `CameraUpdateFactory` 相当で再実装する。
- 既存の following / overview / idle state は維持してもよいが、内部実装は全面置換になる。

注意:

- Mapbox の `NavigationCamera` は高機能で、Google 側に同等抽象がそのままあるとは限らない。
- 既存 UI に合わせて細かい padding / zoom / tilt を制御する場合、追加検証が必要。

### ナビセッション

対象:

- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/GuidanceSessionManager.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/guidance/GuidanceCoordinator.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/guidance/GuidanceContextBuilder.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/guidance/GuidanceAnnouncementManager.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/guidance/GuidanceContext.kt`

現在の役割:

- `RouteProgressObserver`
- `VoiceInstructionsObserver`
- `BannerInstructionsObserver`
- `OffRouteObserver`
- `RoutesObserver`
- `ArrivalObserver`
- `RouteProgress` から trip progress / maneuver / lane を構築
- Mapbox `LegStep` / `StepIntersection` から `GuidanceEvent` を生成

移行方針:

- `GuidanceSessionManager` を Google `Navigator` ベースに置換する。
- Mapbox observer lifecycle は廃止する。
- Turn-by-turn data feed を受け取り、既存 UI state に変換する adapter を作る。
- off-route / reroute / arrival / remaining distance / remaining time の取得方法を Google API に寄せる。

残せる可能性があるもの:

- `JapaneseGuidancePhraseComposer`
- `SpeechOrchestrator`
- `GuidancePriority`
- `TtsEngine`
- 音声キュー制御

置換が必要なもの:

- `GuidanceContextBuilder`
- Mapbox `RouteProgress` 前提の extractor
- Mapbox `VoiceInstructions` / `BannerInstructions` 前提の announcement manager

### レーン案内

対象:

- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/LaneInfo.kt`
- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/ManeuverInfo.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/navi/NaviManeuverPanel.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/navi/HomeMapNaviContent.kt`

現在の役割:

- Mapbox `BannerInstructions.sub` の lane component
- `StepIntersection.lanes()`
- `active` / `valid` / `indications` を `LaneInfo` に変換

移行方針:

- Google turn-by-turn data feed の lane 情報を `LaneInfo` に変換する。
- UI component はできる限り維持する。
- Google 標準 UI を使う場合、既存 lane panel は非表示または補助表示にする。

重要:

- 今回の移行価値はここで決まる。
- PoC で、問題の銚子ルートに対して Navigation SDK が lane guidance を出すことを最初に確認する。
- Google Maps アプリに出る案内が、Navigation SDK の data feed に必ず同じ粒度で出るとは限らない。

### TTS

対象:

- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/guidance/GuidanceAnnouncementManager.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/guidance/JapaneseGuidancePhraseComposer.kt`
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/guidance/SpeechOrchestrator.kt`

移行オプション:

| 方針 | 内容 | 工数 | 品質/自由度 |
|---|---|---:|---|
| Google 標準音声に寄せる | Navigation SDK の標準案内を使う | 小 | Google 品質だが OneNavi 独自発話は弱い |
| 既存 TTS を維持 | TBT feed から `GuidanceEvent` を生成する | 中〜大 | OneNavi 独自発話を維持できる |
| ハイブリッド | 標準音声を主、独自 TTS は補助 | 中 | 二重発話制御が難しい |

推奨:

- PoC では Google 標準音声を優先する。
- 本移行では既存 UI / 独自 TTS を維持したいかを別途決める。
- 独自 TTS を維持するなら、Mapbox extractor を Google TBT feed adapter に置換する。

### 検索

対象:

- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/GooglePlacesSearchDataSource.kt`
- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/SearchDataSource.kt`
- `core/repository/src/commonMain/kotlin/me/matsumo/onenavi/core/repository/SearchRepository.kt`

現在は Google Places が既に使われている。

そのため、検索領域の移行リスクは低い。

対応内容:

- Mapbox Search 依存が残っていないか確認する。
- Google Places result と Navigation SDK waypoint の変換を整理する。
- place id / latLng / display name / address の扱いを明確化する。

### Android Auto

対象:

- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarSession.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarMapObserver.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarMapScreen.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarAppService.kt`

現在の役割:

- `mapboxMapInstaller()`
- Mapbox car map observer
- Mapbox style
- location puck
- Android Auto `NavigationTemplate`

移行方針:

- 本体 Android 移行とは別フェーズに分ける。
- Google Navigation SDK の Android Auto 対応を検証する。
- Preview API / Play 審査 / template 制約を確認する。

リスク:

- Mapbox Android Auto extension と Google Navigation SDK for Auto は設計が異なる。
- 現在の Android Auto 機能が map-only に近い場合でも、置換には別検証が必要。

### Debug / Fake GPS

対象:

- `composeApp/src/androidDebug/kotlin/me/matsumo/onenavi/FakeGpsServer.kt`
- `dev-tools/fake-gps/`

現在の役割:

- FusedLocationProvider の mock location
- Google Maps JS を使ったルートシミュレーション

移行方針:

- Google Navigation SDK が mock location をどう扱うか確認する。
- 既存 fake GPS は原則維持できる可能性が高い。
- 実走なしで PoC / regression test できるよう、銚子ルート fixture を追加する。

### Common model

対象:

- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/RouteResult.kt`
- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/RouteItem.kt`
- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/ManeuverInfo.kt`
- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/LaneInfo.kt`
- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/SearchSuggestionItem.kt`

移行方針:

- commonMain に Mapbox SDK 型を漏らさない。
- `platformRoute: Any?` のような provider-specific escape hatch は縮小する。
- `RouteSummary`, `RouteGeometry`, `RouteToken`, `NavigationRouteHandle` などの provider-neutral model を導入する。

---

## 機能 parity チェックリスト

Mapbox SDK をすべて削除した後も、以下の機能を Google 実装で成立させる。

### 地図 / 検索

- 現在地表示
- 現在地追従
- 地図の pan / zoom / rotate
- POI タップ
- 地図長押しによる地点指定
- 検索結果一覧
- 検索結果番号ピン
- 単一地点詳細
- 経由地 pin
- 目的地 pin
- ダークモード相当の地図表示
- 交通情報表示の可否確認

### 経路プレビュー

- 現在地から目的地までの経路取得
- 経由地あり経路取得
- 複数代替ルート表示
- 選択ルートの強調
- 非選択ルートの表示
- 距離表示
- 所要時間表示
- 有料道路 / toll 情報表示
- ルート吹き出し
- ルート全体を収めるカメラ移動
- ルート再検索

### ナビゲーション

- ナビ開始
- ナビ停止
- foreground / background lifecycle
- 現在地追従 camera
- route overview camera
- maneuver approach camera
- remaining distance
- remaining duration
- current road / next road
- basic turn-by-turn
- fork / merge / ramp
- lane guidance
- off-route detection
- reroute
- waypoint arrival
- final destination arrival
- navigation state restoration

### UI / TTS

- `HomeMapNaviContent`
- `NaviManeuverPanel`
- lane panel
- trip progress 表示
- arrival UI
- Google 標準音声または OneNavi 独自 TTS
- 二重発話防止
- TTS の audio focus
- speech queue / priority

### Android Auto

- car app service
- navigation template
- map display
- turn-by-turn feed
- route progress
- arrival
- Mapbox Android Auto extension 削除

### Debug / QA

- fake GPS route simulation
- 銚子ルート regression
- route / guidance log
- screenshot / video capture
- API key / quota / billing monitoring

---

## 段階的移行計画

## Phase 0: PoC / Gap 判定

見積もり: 3〜5 営業日

目的:

- Google Navigation SDK で現機能を再現する際の不足箇所を最小実装で洗い出す。
- Mapbox 完全削除のために、Google 標準機能だけで足りる部分と、OneNavi 側で補完実装が必要な部分を分ける。

作業:

1. Google Cloud 側で必要 API を有効化する。
   - Navigation SDK for Android
   - Routes API
   - Places API
2. API key 制限を設定する。
   - Android package name
   - SHA-1 certificate fingerprint
   - 必要 API のみ許可
3. Google Navigation SDK の sample 相当を別ブランチまたは小さな debug 画面で起動する。
4. 出発地・目的地を銚子ルートに固定する。
5. 実機または fake GPS で問題地点を通過させる。
6. 以下を確認する。
   - Google Navigation SDK の UI が 150m 先レーン案内を出すか
   - Turn-by-turn data feed に lane 情報が出るか
   - 音声案内が出るか
   - ルートが Google Maps アプリと大きくズレないか
7. ログを保存する。
   - route id / token
   - step
   - maneuver
   - lane
   - distance to maneuver
   - timestamp
   - screenshot

Success 条件:

- 問題地点で Google Navigation SDK が期待するレーン案内を出す。
- API key / billing / SDK 利用条件にアプリとして問題がない。
- OneNavi の UI 方針と Google SDK の表示制約が致命的に衝突しない。

Gap 条件:

- Google Navigation SDK でも対象レーン案内が出ない。
- Google Maps アプリには出るが Navigation SDK の data feed には出ない。
- SDK 利用条件・審査・費用が受け入れられない。

Gap が出た場合の扱い:

- Mapbox へ戻すのではなく、Google SDK/API だけで補完可能かを設計する。
- 補完できない場合は known limitation として production scope に含めるか判断する。
- 費用・審査・規約が block になる場合は、Google 完全移行そのものの前提を再確認する。

成果物:

- PoC branch
- route / lane log
- screenshot
- Google 移行 gap list
- 補完実装方針

## Phase 1: Provider-neutral model の導入

見積もり: 2〜3 営業日

目的:

- Mapbox 型を UI / ViewModel / common model から剥がす。
- Google 実装を差し込める境界を作る。

作業:

1. `RouteResult` / `RouteItem` の provider-specific 領域を整理する。
2. `NavigationRoute` を直接参照している feature 層を抽象化する。
3. `RouteHandle` を導入する。
   - provider
   - route id
   - encoded polyline
   - route token
   - distance
   - duration
   - toll summary
   - raw handle optional
4. `RouteDataSource` interface を Google 実装に差し替えやすくする。
5. UI は `RouteHandle` / `RouteSummary` だけを見るようにする。

注意:

- この段階では Mapbox 実装をまだ残す。
- 動作を変えずに型の境界だけ整える。

完了条件:

- Home map feature が `NavigationRoute` を直接要求しない。
- Mapbox 実装を DI で差し替えられる。
- 既存 Mapbox ナビが動作する。

## Phase 2: Google route preview の実装

見積もり: 4〜6 営業日

目的:

- 目的地選択後のルート一覧・ルート線・所要時間表示を Google データで表示できるようにする。

作業:

1. `GoogleRouteDataSource` を追加する。
2. Routes API `computeRoutes` を呼び出す。
3. 取得する情報を定義する。
   - distance
   - duration
   - static duration
   - polyline
   - route labels
   - toll information
   - route token
4. `RouteResult` / `RouteItem` に変換する。
5. 代替ルートの UI 表示を接続する。
6. Google route preview を既存 UI に接続する。
7. 開発中だけ Mapbox route preview と比較できる debug toggle を用意する。
8. Google route preview が parity を満たしたら、Mapbox route preview 経路を削除する。

注意:

- Routes API は課金・クォータ管理が必要。
- route token は実ナビ連携に必要になる可能性があるため保存する。
- プレビュー polyline と Navigation SDK 実ナビ route が完全一致する保証は置かない。

完了条件:

- 検索結果から Google route preview を表示できる。
- 複数ルートがある場合に一覧表示できる。
- 距離・時間・経路線が UI に出る。
- Mapbox 経路取得なしで route preview が成立する。
- route token または Navigation SDK に渡すための route handle が保存される。

## Phase 3: Map view の置換

見積もり: 5〜8 営業日

目的:

- Mapbox Compose map を Google Navigation SDK / Google map view に置換する。

作業:

1. `HomeMapsMapEffectContent` を分割する。
   - map host
   - marker renderer
   - route renderer
   - camera controller
   - map event bridge
2. Google `NavigationView` を Compose へ組み込む。
3. ライフサイクルを接続する。
   - `onCreate`
   - `onStart`
   - `onResume`
   - `onPause`
   - `onStop`
   - `onDestroy`
   - low memory
4. 検索結果 pin を再実装する。
5. 目的地 pin / waypoint pin を再実装する。
6. route preview polyline を再実装する。
7. POI click / long click の代替を実装する。
8. 現在地表示を実装する。
9. light / dark / traffic / building など style 方針を決める。

注意:

- `NavigationView` 標準 UI と OneNavi 独自 overlay の重なりを検証する。
- Mapbox Standard style と Google map style は見た目が変わる。
- route callout の画面座標変換は Mapbox 前提なので作り直す。

完了条件:

- Browsing / SearchResultsList / PlaceDetails / RoutePreview が Google map 上で成立する。
- ピン、ルート線、カメラ移動が機能する。
- 既存の主要操作が退行していない。

## Phase 4: Camera / location の置換

見積もり: 3〜5 営業日

目的:

- Mapbox `CameraManager` を Google 実装へ置換する。

作業:

1. following / overview / idle の状態を provider-neutral にする。
2. route bounds camera を Google API で実装する。
3. user location following を実装する。
4. maneuver approach camera を検証する。
5. map padding を top app bar / bottom sheet / nav panel に合わせる。
6. fake GPS で追従挙動を確認する。

注意:

- Mapbox `NavigationCamera` と同等の自動制御を期待しすぎない。
- Google 標準カメラに寄せる部分と OneNavi 独自カメラを分ける。

完了条件:

- ルートプレビュー時に全経路が収まる。
- ナビ中に現在地追従する。
- BottomSheet / Navi panel に route や puck が隠れない。

## Phase 5: Guidance session の置換

見積もり: 6〜10 営業日

目的:

- Mapbox observer ベースのナビセッションを Google `Navigator` ベースへ移行する。

作業:

1. Google Navigation SDK の session lifecycle を導入する。
2. waypoint / destination を `Navigator` に渡す。
3. guidance start / stop を接続する。
4. remaining distance / remaining time を UI state に流す。
5. current maneuver を UI state に流す。
6. off-route / reroute を検出する。
7. arrival を検出する。
8. route changed event を扱う。
9. foreground service / notification / permission の差分を確認する。

注意:

- Mapbox の `startTripSession(withForegroundService = true)` と Google の session 管理は別物。
- Android の background location / foreground service policy に注意する。

完了条件:

- Google SDK でナビ開始・停止できる。
- ナビ中 UI が更新される。
- リルートと到着が最低限動く。
- route preview から guidance へ自然に遷移する。

## Phase 6: Lane / maneuver UI の接続

見積もり: 4〜8 営業日

目的:

- Google Navigation SDK の案内情報を既存 OneNavi UI に表示する。

作業:

1. Turn-by-turn data feed adapter を作る。
2. Google maneuver を `ManeuverInfo` に変換する。
3. Google lane を `LaneInfo` に変換する。
4. 距離単位・道路名・方面名を整形する。
5. `NaviManeuverPanel` に接続する。
6. 対象ルートで 150m レーン案内が出ることを確認する。
7. Google 標準 UI と独自 UI が二重表示にならないよう整理する。

注意:

- Google Maps アプリで出る UI と Navigation SDK data feed の公開情報は完全一致しない可能性がある。
- 既存 lane icon と Google lane indication の対応表が必要。

完了条件:

- 基本右左折、分岐、レーン案内が既存 UI に表示される。
- 問題地点のレーン案内が回帰テスト化される。

## Phase 7: TTS 移行

見積もり: 4〜10 営業日

目的:

- 音声案内を Google 標準または既存 OneNavi TTS に接続する。

方針 A: Google 標準音声

- 実装は軽い。
- Google の案内品質を利用できる。
- OneNavi 独自フレーズは弱くなる。

方針 B: 既存 TTS 維持

- TBT feed から `GuidanceEvent` を生成する。
- `JapaneseGuidancePhraseComposer` / `SpeechOrchestrator` を活かせる。
- 実装と検証は重い。

推奨:

- 初期移行では Google 標準音声を使う。
- OneNavi 独自 TTS は Phase 7b として後追いする。

完了条件:

- ナビ中に適切なタイミングで音声案内が出る。
- 二重発話しない。
- レーン案内と右左折案内の優先順位が破綻しない。

## Phase 8: Android Auto 移行

見積もり: 1〜2 週間以上

目的:

- Mapbox Android Auto extension 依存を削除し、Google Navigation SDK for Auto へ移行する。

作業:

1. Google Navigation SDK for Auto の利用条件を確認する。
2. Preview API の制限を確認する。
3. Play Console / Google 審査の要件を確認する。
4. `NavigationViewForAuto` の PoC を作る。
5. `NavigationTemplate` と turn-by-turn feed を接続する。
6. 実車または DHU で確認する。

注意:

- Android Auto は本体 Android 移行とは別 milestone に分ける。
- ただし production 完了条件には Android Auto の Google 移行と Mapbox Auto 削除を含める。

完了条件:

- Android Auto で地図または案内が表示される。
- 審査要件を満たす見込みがある。
- Mapbox Android Auto dependency を削除できる。

## Phase 9: Mapbox 削除

見積もり: 2〜4 営業日

目的:

- Google 移行後に残った Mapbox 依存を取り除く。

作業:

1. Gradle dependency を削除する。
2. Mapbox Maven repository / credentials を削除する。
3. `mapbox_access_token` resource を削除する。
4. `BuildKonfig.MAPBOX_TOKEN` を削除する。
5. `AppConfig.mapBoxToken` を削除する。
6. Mapbox import を全削除する。
7. Mapbox model comments を更新する。
8. README / setup docs を更新する。

完了条件:

- `rg "com\\.mapbox|Mapbox|mapbox"` で不要な参照が残らない。
- clean build が通る。
- Mapbox token が不要になる。

## Phase 10: QA / Regression

見積もり: 1〜2 週間

目的:

- Mapbox から Google への移行で、ナビ体験が壊れていないことを確認する。

必須テスト:

1. 銚子ルートのレーン案内
2. 近距離目的地
3. 長距離目的地
4. 経由地あり
5. 複数代替ルート
6. ルート外れ
7. リルート
8. 到着判定
9. 目的地直前の細道
10. 高速道路入口
11. 高速道路出口
12. JCT / fork
13. トンネル / 高架
14. GPS drift
15. アプリ background / foreground
16. 権限拒否
17. ダークモード
18. 横画面
19. 低メモリ復帰
20. Android Auto

成果物:

- QA checklist
- fake GPS scenario
- screenshot / video
- route logs
- known issues

---

## 工数見積もり

前提:

- Android 担当 1 名
- 既存 UI を大きく崩さない
- Google Cloud の API 利用・課金・SDK 利用条件が通る
- デザイン刷新は含めない
- iOS 移行は含めない
- 最終的に Mapbox SDK はすべて削除する
- Android Auto も Google 実装へ移行する
- 既存機能 parity を production 完了条件に含める

| Scope | 見積もり | 内容 |
|---|---:|---|
| PoC only | 3〜5 営業日 | Google Navigation SDK で対象レーン案内が出るか確認 |
| Android 本体 MVP | 4〜6 週間 | Google 経路、地図、ナビ開始、基本案内、レーン UI |
| Android 本体 production | 6〜8 週間 | リルート、到着、TTS、QA、Mapbox 削除まで |
| Full production | 8〜12 週間以上 | Android Auto、独自 UI/TTS parity、Mapbox 完全削除、審査対応込み |

推奨スケジュール:

1. Week 1
   - Phase 0
   - Google 移行 gap 判定
2. Week 2
   - Phase 1
   - Phase 2
3. Week 3
   - Phase 3
   - Phase 4
4. Week 4
   - Phase 5
   - Phase 6
5. Week 5
   - Phase 7
   - regression
6. Week 6
   - Phase 9
   - QA
   - release preparation
7. Week 7+
   - Android Auto
   - custom TTS polish
   - edge case handling
   - Mapbox fallback / debug toggle removal

---

## リスク

### 1. Navigation SDK が Google Maps アプリと完全一致しない

Google Maps アプリの表示と、外部アプリ向け Navigation SDK の data feed が完全に同じとは限らない。

対策:

- Phase 0 で対象ルートのレーン案内を必ず確認する。
- 標準 UI と TBT feed の両方を確認する。

### 2. SDK / API 利用条件

Google Navigation SDK は通常の Maps SDK より利用条件・課金・審査の確認が重要。

対策:

- 実装前に Google Cloud 設定、Billing、API restrictions、Terms を確認する。
- OSS / 個人開発 / 無料アプリでの利用可否を明確化する。

### 3. UI の自由度

Google Navigation SDK の標準 UI に寄せるほど工数は下がるが、OneNavi 独自 UI との差分が出る。

対策:

- PoC で標準 UI を確認する。
- 独自 UI 維持が必須な箇所を事前にリスト化する。

### 4. カメラ挙動

Mapbox `NavigationCamera` 相当を Google 側で完全再現できるとは限らない。

対策:

- 既存挙動の完全再現を初期目標にしない。
- ナビ品質に効く following / overview / maneuver approach から順に作る。

### 5. Android Auto

Android Auto は SDK 状態、審査、template 制約のリスクが大きい。

対策:

- 本体移行と Android Auto の実装 milestone は分離する。
- ただし production 完了条件には Android Auto の Google 移行を含める。
- 審査や Preview API の制約で block される場合は、production release scope を分ける判断を明記する。

### 6. 既存 TTS との二重化

Google 標準音声と OneNavi 独自 TTS を同時に扱うと二重発話しやすい。

対策:

- 初期版はどちらか一方を source of truth にする。
- ハイブリッドは後続に回す。

### 7. Route preview と actual navigation の不一致

Routes API の preview route と Navigation SDK の actual guidance route がズレる可能性がある。

対策:

- route token を使う。
- UI 上は「選択したルートを元に案内開始」程度の表現にする。
- 実ナビ開始後の route update を UI に反映する。

### 8. Google Maps Compose との併用

Navigation SDK v5 以降は Maps SDK を内包するため、Google Maps Compose と Navigation SDK の併用は依存関係上の確認が必要。

対策:

- ナビ本線は `NavigationView` を前提に設計する。
- Google Maps Compose は本移行の主経路にしない。
- 経路プレビューも `NavigationView` / Navigation SDK と競合しない構成で実現する。

---

## 移行中の切り替え方針

移行中は一時的に Mapbox と Google を同居させてもよい。

ただし、これは検証と rollback のための一時措置であり、production 完了時には Mapbox 側の実装・dependency・token・repository を削除する。

候補:

```kotlin
enum class NavigationProvider {
    Mapbox,
    Google,
}
```

開発中の一時切り替え対象:

1. route data source
2. map renderer
3. guidance session manager
4. TTS source
5. Android Auto provider

推奨順:

1. route data source
2. route preview map rendering
3. guidance session
4. lane / maneuver UI
5. TTS
6. Android Auto
7. Mapbox dependency removal

production merge 前に削除するもの:

1. `NavigationProvider.Mapbox`
2. Mapbox route data source binding
3. Mapbox map renderer
4. Mapbox guidance session manager
5. Mapbox TTS extractor
6. Mapbox Android Auto integration
7. Mapbox token / Maven credentials

---

## Provider-neutral model 案

### RouteHandle

```kotlin
data class RouteHandle(
    val provider: NavigationProvider,
    val routeId: String,
    val encodedPolyline: String?,
    val routeToken: String?,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val staticDurationSeconds: Double?,
    val summary: String?,
    val hasTolls: Boolean,
    val rawHandle: Any?,
)
```

### NavigationProgressSnapshot

```kotlin
data class NavigationProgressSnapshot(
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val currentRoadName: String?,
    val nextRoadName: String?,
    val maneuver: ManeuverInfo?,
    val lanes: List<LaneInfo>,
    val isOffRoute: Boolean,
    val routeId: String?,
)
```

### GuidanceAdapter

```kotlin
interface GuidanceAdapter {
    val progress: StateFlow<NavigationProgressSnapshot?>
    val events: Flow<GuidanceEvent>

    suspend fun start(route: RouteHandle, waypoints: List<Waypoint>)
    suspend fun stop()
}
```

注意:

- `rawHandle: Any?` は段階移行用に限定する。
- 最終的には provider-specific 型を feature 層から消す。

---

## 検証ログ仕様

Phase 0 以降、以下をログに残す。

### Route log

- provider
- route id
- route token
- origin
- destination
- waypoints
- requested options
- selected alternative index
- distance
- duration
- encoded polyline length

### Guidance log

- timestamp
- location lat/lng
- snapped location lat/lng
- remaining distance
- remaining duration
- current step index
- next maneuver type
- next maneuver modifier
- road name
- lane count
- recommended lane index
- raw lane indications
- voice announcement
- UI displayed maneuver

### Regression fixture

銚子ルートを fixture 化する。

必須チェック:

- 銚子信用金庫本店付近で 150m レーン案内が出ること
- その後、2km 先の分岐案内が出ること
- レーン案内が分岐案内に上書きされて消えないこと
- 音声案内と UI 案内のタイミングが破綻しないこと

---

## 実装順の推奨

最初にやるべきこと:

1. Google Navigation SDK PoC
2. 銚子ルートのレーン案内確認
3. SDK 利用条件・課金・審査確認

次にやるべきこと:

1. provider-neutral model
2. Google Routes API preview
3. Google map / NavigationView host

最後にやるべきこと:

1. custom TTS polish
2. Android Auto
3. Mapbox fallback / debug toggle removal
4. Mapbox dependency full removal

やってはいけない順序:

1. PoC 前に Mapbox dependency を削除し、比較検証できない状態にする。
2. Google Maps Compose だけに置換して、Navigation SDK の検証を後回しにする。
3. Android Auto を本体移行と同じ実装 milestone に詰め込む。
4. Google Maps アプリに出る案内が Navigation SDK data feed に必ず出ると仮定する。
5. production 完了時に Mapbox fallback を残す。

---

## 完了条件

Android 本体 MVP の完了条件:

- Google Navigation SDK でナビ開始できる。
- 検索結果から目的地を選び、Google route preview を表示できる。
- route preview から guidance に遷移できる。
- ナビ中に現在地追従する。
- remaining distance / duration が更新される。
- basic maneuver が表示される。
- lane guidance が表示される。
- 銚子ルートの対象地点で期待するレーン案内が出る。
- off-route / reroute が最低限動く。
- arrival が検出される。
- Android 本体の通常導線で Mapbox SDK を使わない。

Production の完了条件:

- Mapbox dependency が削除されている。
- Mapbox token が不要になる。
- Mapbox Maven repository / credentials が削除されている。
- Mapbox import が production source に残っていない。
- Mapbox Android Auto extension が削除されている。
- Google API key restriction が設定されている。
- Google billing / quota の監視がある。
- major route scenarios の regression が通る。
- Android Auto が Google 実装で成立するか、別 release scope として明示的に切り出されている。
- TTS source of truth が Google 標準音声または OneNavi 独自 TTS のどちらかに統一されている。
- 現在の OneNavi 機能 parity がチェックリストで確認されている。
- docs / README / setup が更新されている。

---

## 最終判断

本計画の最終目標は、Mapbox SDK を完全に削除し、OneNavi の既存機能を Google Maps Platform / Google Navigation SDK で実現することである。

ただし、移行価値と実現方法は Google Navigation SDK が OneNavi 内で対象レーン案内を出せるかに強く依存する。

そのため、最初のマイルストーンは全面実装ではなく、対象地点での Google Navigation SDK レーン案内 PoC である。

PoC が成功した場合、Android 本体の移行は 4〜6 週間で MVP、6〜8 週間で production 相当が現実的である。

Android Auto と独自 TTS の完全 parity、Mapbox fallback 全削除、審査対応まで含めるなら、8〜12 週間以上を見込む。

PoC が失敗した場合でも、Mapbox 完全削除を目標にするなら、Google SDK/API だけで不足案内を補完する追加設計が必要になる。

その場合の選択肢:

1. Google 標準 UI / 標準音声に寄せ、data feed で取れない情報は独自 UI に出さない。
2. Routes API / Navigation SDK で足りない safety / lane 情報を、外部 DB または独自 heuristics で補う。
3. Google Maps アプリ相当の案内を完全再現できない範囲を known limitation として明示する。
