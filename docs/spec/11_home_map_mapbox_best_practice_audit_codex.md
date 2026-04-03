# 11. Home Map Mapbox Best-Practice Audit

## Overview

本ドキュメントは、Home 画面の地図実装が Mapbox Maps SDK / Navigation SDK / Search SDK の公式推奨パターンにどの程度準拠しているかを監査した結果をまとめる。

- 対象日: 2026-04-03
- 対象実装:
  - `feature/home/src/commonMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapViewModel.kt`
  - `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt`
  - `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`
  - `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapControls.kt`
  - `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapRouteCalloutAdapter.kt`
  - `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/MapboxNavigationRouteDataSource.kt`
  - `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/GooglePlacesSearchDataSource.kt`
  - `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/OneNaviApplication.kt`

## Executive Summary

結論として、現在の Home 地図実装は「Mapbox SDK を利用している」が、「Mapbox エコシステムの推奨アーキテクチャに沿っている」とは言いづらい。

特に問題が大きいのは次の 5 点である。

1. Navigation の所有者とライフサイクル管理が二重化している。
2. Search が Mapbox と Google Places に分裂しており、地図上の POI 操作と検索結果の整合性が崩れている。
3. Route line / route callout が `RoutesObserver` / `RouteProgressObserver` 駆動ではなく、画面ローカル state の差分駆動になっている。
4. Compose 上で登録した Mapbox listener が適切に dispose されていない。
5. `commonMain` の ViewModel / model が Android の `NavigationRoute` に引きずられている。

この状態では、reroute、traffic refresh、primary route 切替、invalid alternatives の除去、active guidance への拡張など、Mapbox Navigation SDK が前提とする更新経路に十分に乗れない。

## Current Implementation Snapshot

現状の実装責務はおおむね以下のようになっている。

- `HomeMapViewModel`
  - 検索クエリ、候補、検索結果、選択地点、ルート結果、経由地、選択ルート index を保持
  - `ViewEvent` ごとに検索 / ルート探索 / 経由地更新を直接呼び出す
- `HomeMapScreenContent`
  - `MapViewportState` を保持
  - `selectedResult` / `searchResults` / `routeResults` の変化に応じて camera を直接 `easeTo` / `flyTo`
- `HomeMapsMapEffectContent`
  - `MapView` に直接アクセスして location component, route line, callout, map click listener を設定
  - `routeResults` の変更で `MapboxRouteLineApi.setNavigationRoutes()` を呼ぶ
- `MapboxNavigationRouteDataSource`
  - `MapboxNavigation.requestRoutes()` でプレビュー用ルートを取得
  - 取得した `NavigationRoute` を `RouteResult.platformRoute: Any?` に保持
- `GooglePlacesSearchDataSource`
  - 検索候補、検索結果、place detail 取得を Google Places で実装

## Findings

### Critical-1. Navigation ownership が二重化している

該当コード:

- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/OneNaviApplication.kt`

現状:

- `MapboxNavigationApp.setup(options)` を呼んでいる
- 同時に `MapboxNavigationProvider.create(options)` も呼んでいる
- データソース側では `MapboxNavigationProvider.retrieve()` を直接参照している

問題点:

- Mapbox のライフサイクル管理は `MapboxNavigationApp` と `MapboxNavigationProvider` の両方を同時に所有する想定ではない
- observer 登録、画面 attach/detach、ルート更新通知の責務が分裂する
- 将来的に `MapboxNavigationObserver`, `RoutesObserver`, `RouteProgressObserver` を導入する際の土台が崩れる

必要な修正:

- Navigation 所有者を一本化する
- `MapboxNavigationApp` ベースに寄せるなら、Android 専用の navigation session controller を作り、observer 登録をそこへ集約する
- `MapboxNavigationProvider.retrieve()` の直参照をやめる

### Critical-2. Search provider が Mapbox と Google Places に分裂している

該当コード:

- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/di/DataSourceModule.android.kt`
- `core/datasource/src/androidMain/kotlin/me/matsumo/onenavi/core/datasource/GooglePlacesSearchDataSource.kt`
- `feature/home/src/commonMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapViewModel.kt`

現状:

- 地図表示とルートは Mapbox
- 検索候補 / 選択 / テキスト検索は Google Places
- Mapbox Standard の POI タップ後は、`name + coordinate` を使って Google Places に再検索している

問題点:

- Mapbox 地図上でタップした POI と、最終的に選択される検索結果が別 provider の別 object になる
- place id, ranking, address normalization, reverse geocoding 結果が Mapbox 側と一致しない
- Mapbox Search SDK を導入済みなのに、Home 画面はその推奨フローに乗っていない
- 仕様書では `Mapbox Geocoding API (検索)` を採用済みだが、実装は Google Places になっている

典型例:

- `OnMapLandmarkSelected` は POI 名があると `searchRepository.searchMultiple(name, latitude, longitude)` を呼ぶ
- これは「Mapbox で取れた地点」を「Google のテキスト検索で近いものに引き直す」処理であり、reverse geocoding ではない

必要な修正:

- Home 地図検索を Mapbox Search SDK に統一する
- 文字列検索は `search -> select`
- 地図タップ / 長押し / POI タップは reverse geocoding ベースに統一する
- provider 混在を残すなら、それは Mapbox best practice 準拠を捨てる設計判断として明記する

### Critical-3. Route line / route callout が `RoutesObserver` 駆動ではない

該当コード:

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`

現状:

- `routeResults` と `selectedRouteIndex` をキーに `MapEffect(routeResults, selectedRouteIndex)` を実行
- `routeResults` の参照が変わった時だけ `routeLineApi.setNavigationRoutes()` を呼ぶ
- `selectedRouteIndex` 変更時は callout view の色だけを直接変更する

問題点:

- Mapbox 公式は route line / route callout ともに `RoutesObserver` を推奨している
- 公式が想定する更新対象:
  - selected primary route の変更
  - reroute 後の再描画
  - traffic / duration refresh
  - fork 通過後の invalid alternative 除去
- 現状実装ではこれらが MapboxNavigation の route state と同期しない
- ルート選択は UI state の `selectedRouteIndex` にしか載っておらず、Navigation SDK の primary route 概念に接続されていない

必要な修正:

- `RoutesObserver` で route line / callout 更新を一本化する
- preview 中でも route ownership を Android navigation layer に持たせる
- ルート選択変更時は UI の色替えだけではなく、Navigation SDK 側の selected primary route を更新する経路を作る

### Critical-4. `alternativesMetadata` を使っていない

該当コード:

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`

現状:

- `routeLineApi.setNavigationRoutes(reordered)` だけを呼んでいる

問題点:

- Mapbox docs は alternative route の重なり制御と可視化向上のため `alternativesMetadata` の利用を推奨している
- metadata を使わないと、primary route と代替ルートの重なり処理が弱くなる
- route line だけでなく callout の見え方にも悪影響が出る

必要な修正:

- `mapboxNavigation.getAlternativeMetadataFor(routes)` を route update 時に取得して `setNavigationRoutes(routes, alternativesMetadata)` へ渡す

### High-1. Compose listener を登録しっぱなしで dispose していない

該当コード:

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`

現状:

- `addOnIndicatorPositionChangedListener`
- `addOnIndicatorBearingChangedListener`
- `addOnMapClickListener`

を登録しているが remove がない

問題点:

- Mapbox Compose docs は listener 登録に `DisposableMapEffect` と `onDispose` を使う例を示している
- 同一 composable の再生成や `MapView` 差し替え時に callback が重複する可能性がある
- location listener は更新頻度が高いため、重複時の負荷も大きい

必要な修正:

- listener 登録は `DisposableMapEffect`
- `onDispose` で
  - `removeOnIndicatorPositionChangedListener`
  - `removeOnIndicatorBearingChangedListener`
  - `removeOnMapClickListener`
  を必ず対にする

### High-2. camera 制御が ViewportPlugin の state model と喧嘩している

該当コード:

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapControls.kt`

現状:

- follow puck は `transitionToFollowPuckState()` で制御
- 一方で検索結果・選択地点・ルート表示では `easeTo` / `flyTo` を直接呼ぶ
- `ViewportStatus.Idle` を見て `trackingMode = null` に戻すローカル制御が入っている

問題点:

- camera state machine が UI の if/else と side effect に散らばる
- follow / non-follow / route overview の切替を viewport state として定義していない
- route overview や search results overview が ad-hoc な camera 操作になっている

必要な修正:

- camera mode を明示的な state machine として設計する
- 例:
  - `FollowPuck(mode = TiltedHeading | TopDownHeading | TopDownNorth)`
  - `SelectedPlace(point)`
  - `SearchResultsOverview(points)`
  - `RouteOverview(routes, padding)`
- `MapViewportState` の遷移先を state 単位で管理し、`Idle` ハックを廃止する

### High-3. high-frequency な位置更新を shared ViewModel に流している

該当コード:

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`
- `feature/home/src/commonMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapViewModel.kt`

現状:

- `OnIndicatorPositionChangedListener` の結果を `onUserLocationUpdated()` で ViewModel に保存している

問題点:

- indicator position listener は animation frame 単位で飛ぶことがある
- ルート探索に必要な「最新現在地」と、UI の毎フレーム位置更新を同じ shared state に載せている
- shared ViewModel を Map rendering frequency で揺らしている

必要な修正:

- 現在地の責務を二分する
  - UI / camera / route line 用の high-frequency position は Android map layer に閉じ込める
  - ルート探索や検索 bias に必要な coarse な現在地だけを shared 層へ渡す

### High-4. `commonMain` の model に Android SDK object が漏れている

該当コード:

- `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/RouteResult.kt`

現状:

- `platformRoute: Any?` に Android の `NavigationRoute` を格納している

問題点:

- KMP shared model が Android SDK object を暗黙に前提としている
- `commonMain` の ViewModel が結果的に Android route identity に依存する
- reroute / refresh / replace 後の route identity 管理が脆い

必要な修正:

- shared model は UI 表示に必要な値だけを持つ
- Android 専用の `NavigationRoute` 対応表は Android layer 側で管理する
- `RouteResult` に stable id を導入し、UI 選択状態はその id で持つ

### Medium-1. route selection が index と参照同一性頼み

該当コード:

- `feature/home/src/commonMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapViewModel.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapsMapEffectContent.kt`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapRouteCalloutAdapter.kt`

現状:

- `selectedRouteIndex: Int`
- callout click 時は `it.platformRoute === clickedRoute` で対応付け

問題点:

- route list refresh で順序や object identity が変わると選択状態が不安定になる
- traffic refresh や reroute を受ける設計にした瞬間に破綻しやすい

必要な修正:

- stable route id を導入する
- UI 選択は id ベース
- Android layer で `routeId -> NavigationRoute` を解決する

### Medium-2. custom route callout adapter が SDK の redraw パスから外れている

該当コード:

- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/components/HomeMapRouteCalloutAdapter.kt`

現状:

- `calloutViews` を内部 map で保持
- 選択変更時は view を直接塗り替える
- `notifyDataSetChanged()` を利用していない

問題点:

- Mapbox route callout docs は、アプリ側データ変更後に redraw したい場合 `notifyDataSetChanged()` を呼ぶ前提
- view cache を長く保持する設計は stale view を残しやすい
- SDK の標準更新経路を外れるため、将来の route refresh と合わせにくい

必要な修正:

- custom adapter の更新は SDK の redraw パスに寄せる
- 直接 view を mutate する最適化は、observer 駆動設計へ移行後に必要性を再評価する

### Medium-3. route preview と active navigation の境界が曖昧

現状:

- ルート検索は `MapboxNavigation.requestRoutes()` を使っている
- しかし取得した route を Navigation SDK の current routes として保持していない

問題点:

- preview 用 route と active guidance 用 route の境界が曖昧
- その結果、route line / callout / progress 更新の公式パターンに接続しづらい

必要な修正:

- 少なくとも Android 専用 layer で route preview session を定義する
- 「検索結果から作った preview route」と「開始後の active route」を同じ route ownership model に載せる

### Low-1. token の source of truth が分散している

該当コード:

- `composeApp/src/androidMain/res/values/strings.xml`
- `feature/home/src/androidMain/kotlin/me/matsumo/onenavi/feature/home/map/HomeMapScreenContent.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarAppService.kt`

現状:

- `mapbox_access_token` string resource
- `BuildKonfig.MAPBOX_TOKEN`
- `MapboxOptions.accessToken = ...` の複数設定箇所

問題点:

- 実害は小さいが source of truth が多い
- 設定漏れ、差し替え漏れの余地がある

必要な修正:

- token source を 1 箇所に寄せる
- Mapbox 初期化責務を application レベルへ集約する

## Positive Observations

すべてが悪いわけではない。以下は Mapbox 推奨に比較的近い。

- `MapViewportState` を UI 側で保持している
- `MapboxStandardStyle` を利用している
- location puck の表示自体は Mapbox の location component を使っている
- `RouteOptions.builder().applyDefaultNavigationOptions()` を利用しており、route line 表示に必要な annotation 要件に乗りやすい
- route line / callout の有効化手順自体は概ね SDK の想定に近い

## Recommended Target Architecture

### 1. Shared layer

`commonMain` には以下のみを置く。

- 検索クエリ
- 選択地点
- ルート一覧の UI 用 summary
- 選択 route id
- 経由地一覧
- bottom sheet / top bar の UI state

持ち込まないもの:

- `MapView`
- `NavigationRoute`
- `MapboxRouteLineApi`
- `MapboxRouteLineView`
- Mapbox listener

### 2. Android map layer

Android 専用 controller / coordinator を作り、以下を集約する。

- `MapboxNavigationApp` との接続
- `RoutesObserver`
- `RouteProgressObserver`
- `MapViewportState`
- location listener
- route line / callout adapter
- map click / long click / POI click

### 3. Search layer

Search provider を Mapbox Search SDK に統一する。

- suggestions: `search`
- selected suggestion: `select`
- map tap / long press / arbitrary coordinate: reverse geocoding
- category / POI search が必要なら Search SDK の適切な engine を利用

### 4. Camera state machine

camera mode を enum / sealed interface で定義する。

例:

- `FollowPuck(mode)`
- `SelectedPlace(point)`
- `SearchResultsOverview(points)`
- `RouteOverview(routeIds, padding)`

これを Android map layer が `MapViewportState` に変換する。

### 5. Route ownership

route list は Android navigation layer が所有する。

- shared 層には `RouteUiModel(id, duration, distance, toll, labels...)`
- Android 層には `routeId -> NavigationRoute`
- route selection 更新時は UI と Navigation SDK の両方を同期

## Migration Plan

### Phase 0. Stop the bleeding

- `MapboxNavigationApp` / `MapboxNavigationProvider` の二重化をやめる
- listener 登録を `DisposableMapEffect` に移す
- `platformRoute: Any?` のこれ以上の依存追加を止める

### Phase 1. Search unification

- `GooglePlacesSearchDataSource` を Home 地図導線から切り離す
- Mapbox Search SDK ベースの `SearchDataSource` を追加する
- map tap / POI tap を reverse geocoding ベースへ置換する

### Phase 2. Navigation ownership

- Android 専用の `HomeMapNavigationController` を作る
- `RoutesObserver` / `RouteProgressObserver` を登録する
- route preview を navigation-owned routes に乗せる

### Phase 3. Camera state machine

- `trackingMode: LocationTrackingMode?` と `ViewportStatus.Idle -> null` の制御を廃止する
- camera mode を明示 state に置き換える

### Phase 4. Route line / callout cleanup

- `alternativesMetadata` を使う
- route callout 更新を observer 駆動へ移す
- custom adapter の redraw 戦略を `notifyDataSetChanged()` ベースへ整理する

### Phase 5. Shared model cleanup

- `RouteResult.platformRoute: Any?` を廃止する
- shared / Android 境界を再定義する

## Source Documents

監査に用いた主な Mapbox 公式ドキュメント:

1. https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/
2. https://docs.mapbox.com/android/ja/navigation/guides/ui-components/route-callout/
3. https://docs.mapbox.com/android/maps/guides/user-location/location-on-map/
4. https://docs.mapbox.com/android/maps/guides/using-jetpack-compose/
5. https://docs.mapbox.com/android/ja/maps/guides/user-interaction/gestures/
6. https://docs.mapbox.com/android/ja/search/guides/search-engine/geocoding/
7. https://docs.mapbox.com/android/search/guides/search-engine/geocoder-migration/
8. https://docs.mapbox.com/android/maps/api/11.9.2/mapbox-maps-android/com.mapbox.maps.plugin.locationcomponent/-location-component-plugin/
9. https://docs.mapbox.com/android/maps/api/11.12.1/mapbox-maps-android/com.mapbox.maps/-mapbox-map/
10. https://docs.mapbox.com/android/maps/api/11.1.0/mapbox-maps-android/com.mapbox.maps.extension.compose/-map-effect.html

## Final Assessment

現状の Home 地図実装は、Mapbox SDK の個別機能を利用すること自体はできている。
しかし、Mapbox が推奨する以下の中核パターンには未到達である。

- Navigation lifecycle ownership の一本化
- Search provider の統一
- observer-driven route updates
- Compose 向けの listener disposal
- viewport state machine の明示化
- shared / platform 境界の分離

したがって、部分修正ではなく、Search / Navigation ownership / camera / route rendering の 4 領域をまとめて再設計するのが望ましい。
