# 11. Home Map Mapbox Best-Practice Audit Reviewed

## Overview

本ドキュメントは、以下 2 つの監査結果を突き合わせたうえで、最終的に採用する評価だけを整理したものである。

- `docs/spec/11_home_map_mapbox_best_practice_audit_codex.md`
- `docs/spec/11_home_map_mapbox_best_practice_audit_cc.md`

判断基準は次の通り。

1. Mapbox 公式ドキュメントに明確な推奨があるか
2. 現在の Home 画面の責務に対して、本当に「違反」と言えるか
3. 将来の active guidance / free-drive 拡張を考えたときに、土台として危険か
4. 単なるコード品質論ではなく、Mapbox integration 上の論点として有効か

## Final Verdict

最終判断としては、`codex` 版の方向性が全体としてより妥当で、`cc` 版は詳細だが一部に「将来の理想実装」をそのまま「現時点の違反」と見なしている箇所がある。

採用判断は次の 3 段階に分ける。

- `Confirmed`: 現時点で明確に改善対象
- `Conditional`: 将来の Navigation SDK 活用を前提にすれば強く推奨だが、現時点では直ちに違反と断定しない
- `Rejected / Downgraded`: 根拠が弱い、または Mapbox best practice 論点としては過剰

## Confirmed

### 1. Navigation ownership が破綻している

**判定:** Confirmed / Critical

`OneNaviApplication` では `MapboxNavigationApp.setup(...)` と `MapboxNavigationProvider.create(...)` を同時に使っている。Mapbox 公式 API reference は両者を同時利用してはいけないと明記している。

この問題は `cc` 版の「attach がない」より一段根本的で、まず「どちらを所有者にするのか」が未決定な点が本質である。

最終判断:

- `MapboxNavigationApp` ベースに寄せるなら `attach/registerObserver` まで含めて採用
- `MapboxNavigationProvider` ベースに寄せるなら `MapboxNavigationApp` を消す
- 現状の併用は非準拠

### 2. Search provider が Mapbox と Google Places に分裂している

**判定:** Confirmed / Critical

これは `codex` 版が拾えていて、`cc` 版が落としていた最重要論点のひとつ。

現状は以下の分裂が起きている。

- 地図、POI interaction、ルート描画は Mapbox
- 検索候補、検索結果、詳細取得は Google Places
- Mapbox POI タップ後に Google Places へテキスト再検索している

Mapbox Search SDK を依存に入れているのに、Home 導線が Search SDK の `search/select/reverse geocoding` パターンに乗っていない。仕様書とも不整合である。

最終判断:

- Home 画面を Mapbox-native にするなら Search は Mapbox Search SDK に統一すべき
- Google Places を残すなら、それは「Mapbox ベストプラクティス準拠を諦める設計判断」として明示すべき

### 3. Route ownership が Navigation SDK に接続されていない

**判定:** Confirmed / Critical

`cc` 版の「`setNavigationRoutes()` の欠落」は本質的に正しい。ただし表現は少し補正が必要。

補正後の判断:

- `requestRoutes()` だけで route line を描くこと自体は技術的には可能
- しかし、Mapbox が route line / route callout / route progress / reroute を observer 駆動で扱う推奨パターンに乗るには、取得した routes を Navigation SDK の current routes に登録する必要がある
- 現状は route preview が ViewModel 内の state に閉じており、Navigation SDK の route lifecycle に入っていない

そのため、以下に接続できていない。

- `RoutesObserver`
- `RouteProgressObserver`
- reroute / refresh / invalid alternative 更新
- selected primary route の SDK 内反映

最終判断:

- 現状は「Mapbox Navigation UI component としての route 管理」には未接続
- これは callout / route line / 将来の active guidance すべての土台の欠陥

### 4. Route line / route callout が observer-driven ではない

**判定:** Confirmed / High

`cc` 版・`codex` 版ともに一致している論点で、これは採用する。

現状は `routeResults` と `selectedRouteIndex` の Compose 側差分で `MapboxRouteLineApi` を叩いている。Mapbox docs は route line / route callout ともに `RoutesObserver` ベースを推奨している。

特に callout は、ユーザーが示した `route-callout` doc の通り、`RoutesObserver` により以下を自然に追従させる設計が前提である。

- primary route change
- reroute
- duration refresh
- invalid alternatives removal

現状の `updateSelectionStyling()` は局所最適化としては理解できるが、SDK 推奨パターンではない。

### 5. `alternativesMetadata` を渡していない

**判定:** Confirmed / High

これは `codex` 版の指摘を採用する。

Mapbox 公式の route line / route callout ドキュメントは、代替ルート可視化改善のため `alternativesMetadata` を使うよう明記している。現状は `setNavigationRoutes(reordered)` のみで metadata を渡していない。

そのため、代替ルートの重なり処理と可視化品質は公式推奨に未到達である。

### 6. Compose listener の dispose が欠落している

**判定:** Confirmed / High

`cc` 版の listener 指摘は妥当。`MapEffect` 内で次を登録しているが、remove が無い。

- `addOnIndicatorPositionChangedListener`
- `addOnIndicatorBearingChangedListener`
- `addOnMapClickListener`

Mapbox の Compose / gestures ドキュメントは `DisposableMapEffect` + `onDispose` を推奨している。これは明確な改善対象。

なお `Gesture Handling — Click Listener の解除` は独立項目ではなく、この問題に統合する。

### 7. Shared model に Android SDK object が漏れている

**判定:** Confirmed / High

これは `codex` 版の方が論点整理として良い。

`RouteResult.platformRoute: Any?` は KMP 境界を破って Android の `NavigationRoute` を shared model に持ち込む逃げ道になっている。`cc` 版の「型安全性」論点は正しいが、問題は単なるキャスト危険性ではなく、アーキテクチャ境界破りである。

加えて、現状の route selection は `selectedRouteIndex` と `===` 比較に依存しており、refresh/reroute 後の安定性が低い。

最終判断:

- shared 層は UI summary と stable route id だけにすべき
- `NavigationRoute` 対応表は Android 層に隔離すべき

### 8. camera 制御が ad-hoc で state machine 化されていない

**判定:** Confirmed / Medium

`cc` 版はこれを `NavigationCamera 未使用` として強く断定しているが、そこまでは言わない。とはいえ、現状の camera 制御が散らかっているのは事実である。

現状の問題:

- follow puck は `transitionToFollowPuckState()`
- search / selected result / route overview は `easeTo` / `flyTo`
- `ViewportStatus.Idle` を監視して `trackingMode = null` に戻す局所制御がある

これは明らかに mode が分散しており、将来的な free-drive / active guidance 拡張に弱い。

最終判断:

- `NavigationCamera` 採用自体は conditional
- ただし camera mode を明示 state machine として整理する必要は confirmed

### 9. high-frequency な位置更新を shared ViewModel に流している

**判定:** Confirmed / Medium

`OnIndicatorPositionChangedListener` は高頻度で発火する可能性がある。これを shared ViewModel にそのまま流すのは、Map rendering frequency と shared business state を混ぜている構図になる。

Mapbox docs の直接禁止事項ではないが、設計として危険であり、Android map layer と shared state の責務分離が必要。

## Conditional

### 1. `setNavigationRoutes()` を今すぐ必須にするか

**判定:** Conditional but strongly recommended

`cc` 版はこれを最優先 P0 としているが、そこは少し補正する。

- 単なる route preview と static route line 表示だけなら、必ずしも `setNavigationRoutes()` は必要ない
- しかし OneNavi の仕様書は将来的にナビ中の経由地追加、reroute、進行中更新を前提にしている
- その前提では `setNavigationRoutes()` ベースの route ownership へ移行しない限り、後で必ず詰む

したがって「今すぐ route preview が壊れるから必須」ではなく、「このアプリの方向性では早めにやらないと設計負債になる」項目として採用する。

### 2. `NavigationCamera` / `MapboxNavigationViewportDataSource`

**判定:** Conditional

`cc` 版のここはやや未来寄り。

Mapbox docs が `NavigationCamera` を推奨するのは事実だが、それは「navigation scenarios」で route / location / progress を camera に食わせ続ける場合の標準解である。

現時点の Home 画面はまだ active guidance UI ではないため、`NavigationCamera` 未使用を即違反と断定はしない。ただし、以下をやりたいならかなり有力な移行先になる。

- free-drive camera
- route progress に応じた following/overview
- 進行中の自動ズーム
- Android Auto / phone での一貫した nav camera

最終判断:

- 「今すぐ違反」ではない
- ただし route ownership を Navigation SDK 側へ寄せるなら、最有力の改善先

### 3. `NavigationLocationProvider`

**判定:** Conditional

これも `cc` 版は少し強い。

Home 画面が単なる route preview / place exploration なら、Maps SDK の location component を直接使うこと自体は成立する。だが以下をやるなら `NavigationLocationProvider` が有利になる。

- enhanced location / map matching された puck
- free-drive / active guidance との統合
- Navigation SDK の location stream との一貫性

最終判断:

- 現時点では must ではない
- Navigation SDK の trip/session を本格利用する段階では採用すべき

### 4. `MapView` 参照を `MapEffect` 外で保持している件

**判定:** Conditional / Medium

`cc` 版の「公式警告に該当する」は半分正しいが、少し盛っている。

Compose docs は `MapEffect` の乱用に注意しろと言っているが、クラッシュが明示されているのは `lifecycle`, `compass`, `logo`, `attribution` など特定の API であって、`cameraForCoordinates()` 自体が即アウトとは書いていない。

それでも、

- `MapView` を `remember` で保持
- `LaunchedEffect` から imperative に camera 計算

という構図は、長期的には mode 分散と競合の温床なので整理対象ではある。

最終判断:

- 「明確な違反」ではなく「危険な構図」
- camera state machine 整理や `NavigationCamera` 採用で自然解消したい

## Rejected / Downgraded

### 1. `AccessToken` 設定タイミング問題

**判定:** Rejected as stated

`cc` 版は「`LaunchedEffect` だと style 読み込み前に token が無くて失敗する」としているが、現プロジェクトにはすでに `composeApp/src/androidMain/res/values/strings.xml` に `mapbox_access_token` resource が存在する。

そのため、「token 未設定で初回ロード失敗」という主張は現状コードだけでは成立しない。

ただし問題がゼロではなく、最終的に残る論点は次だけ。

- source of truth が分散している
- `BuildKonfig`, string resource, `MapboxOptions.accessToken` 再設定が混在している

したがってこの項目は「タイミング不良」ではなく「設定責務の分散」として Low に格下げする。

### 2. Traffic congestion annotation が不足している

**判定:** Rejected

`cc` 版のこの指摘は採用しない。

Mapbox route line docs は、`RouteOptions.builder().applyDefaultNavigationOptions()` を使えば `PROFILE_DRIVING_TRAFFIC` と required annotations が入ると明記している。現コードはその API を使っているため、「congestion annotation が足りない」とは言えない。

### 3. Polyline デコードの自前実装

**判定:** Downgraded to cleanup issue

自前 polyline decoder と SDK API の混在はたしかに一貫性が悪い。だが、これは Mapbox best-practice 監査の主論点ではなく、保守性の話でしかない。

最終判断:

- 後で整理はしたい
- ただし監査の優先課題には入れない

### 4. `DefaultRouteCalloutAdapter` を使っていないこと自体

**判定:** Rejected

有料道路ラベルや料金表示のカスタム要件がある以上、独自 adapter を作る判断自体は正当化できる。問題は custom adapter の存在ではなく、更新経路が SDK 推奨の redraw / observer パターンに乗っていない点である。

### 5. 「全問題の根本原因は 1 つ」という整理

**判定:** Rejected

`cc` 版は root cause を「Observer パターンを使わず、StateFlow + Compose で手動管理していること」に一元化しているが、これは少し雑である。

実際には少なくとも以下の独立論点がある。

- Navigation ownership の二重化
- Search provider の分裂
- Route ownership の未接続
- shared / platform 境界の破れ

Observer 不使用は大きいが、全てをそれ一つに還元するのは不正確。

## Consolidated Action List

最終的に実装タスクへ落とすべきなのは次の順序が妥当。

### P0

1. Navigation ownership を一本化する  
   `MapboxNavigationApp` か `MapboxNavigationProvider` のどちらかに寄せる。現状の併用は廃止。

2. Home の Search provider 方針を決める  
   Mapbox-native に寄せるなら Google Places を外し、Mapbox Search SDK へ統一。

3. Route ownership を Navigation SDK に接続する  
   `requestRoutes()` の結果を ViewModel state のみで閉じず、observer-driven 更新へ移行する土台を作る。

### P1

4. Route line / route callout を `RoutesObserver` ベースへ移す  
   同時に `alternativesMetadata` を入れる。

5. listener を `DisposableMapEffect` ベースで add/remove 対にする

6. shared model から `NavigationRoute` を追い出し、stable route id へ移行する

### P2

7. camera mode を state machine として整理する  
   そのうえで必要なら `NavigationCamera` / `ViewportDataSource` に寄せる。

8. Navigation SDK の位置 stream を本格利用する段階で `NavigationLocationProvider` を採用する

9. token 設定責務と polyline 実装のような cleanup を行う

## Final Assessment

最終判断として、以下を採用する。

- `codex` 版の「Search 分裂」「Navigation ownership 二重化」「shared/platform 境界破れ」は必須で残す
- `cc` 版の「`setNavigationRoutes` 未接続」「`RoutesObserver` / callout パターン」「listener dispose 欠落」は採用する
- `cc` 版の「NavigationCamera 未使用」「NavigationLocationProvider 未使用」は将来の本格 Navigation 統合前提の conditional 項目に落とす
- `cc` 版の「AccessToken タイミング」「congestion annotation 不足」「DefaultRouteCalloutAdapter 不使用」はそのままでは採用しない

要するに、お兄ちゃんが今本当に直すべきなのは「Mapbox の部品を個別に使う」状態から、「Mapbox が想定する ownership / observer / provider の流れに戻す」こと。そこを外したまま周辺だけ直しても、根本改善にはならない。

## Reference Documents

- https://docs.mapbox.com/android/ja/navigation/guides/initialization/
- https://docs.mapbox.com/android/navigation/api/coreframework/3.8.5/navigation/com.mapbox.navigation.core/-mapbox-navigation-provider/
- https://docs.mapbox.com/android/navigation/api/coreframework/3.7.2/navigation/com.mapbox.navigation.core.lifecycle/-mapbox-navigation-app/index.html
- https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/
- https://docs.mapbox.com/android/ja/navigation/guides/ui-components/route-callout/
- https://docs.mapbox.com/android/navigation/guides/ui-components/camera/
- https://docs.mapbox.com/android/navigation/api/coreframework/3.10.0/ui-maps/com.mapbox.navigation.ui.maps.location/-navigation-location-provider/
- https://docs.mapbox.com/android/maps/guides/using-jetpack-compose/
- https://docs.mapbox.com/android/ja/maps/guides/user-interaction/gestures/
- https://docs.mapbox.com/android/ja/search/guides/search-engine/geocoding/
