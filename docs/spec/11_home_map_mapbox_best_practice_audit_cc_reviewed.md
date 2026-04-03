# 11. Home Map Mapbox Best-Practice Audit — Reviewed

## Overview

本ドキュメントは、CC (Claude Code Opus) と Codex (GPT) がそれぞれ独立に実施した Mapbox ベストプラクティス監査結果を統合・相互レビューし、最終的な評価と改善方針を確定したものである。

- 対象日: 2026-04-03
- 入力ドキュメント:
  - `11_home_map_mapbox_best_practice_audit_cc.md` — CC による監査 (14 カテゴリ)
  - `11_home_map_mapbox_best_practice_audit_codex.md` — Codex による監査 (11 カテゴリ)

---

## Executive Summary

両監査の結論は完全に一致している:

> **現在の Home 地図実装は Mapbox SDK の個別機能を利用しているが、Mapbox エコシステムの推奨アーキテクチャには乗っていない。部分修正ではなく再設計が必要。**

根本原因も同一の診断:

> Navigation SDK の Observer パターン（`RoutesObserver` / `RouteProgressObserver` / `LocationObserver`）を使わず、ViewModel の StateFlow + Compose の LaunchedEffect/MapEffect で全てを手動管理している。

---

## 監査結果の比較

### 両者が合意した指摘

| ID | カテゴリ | CC | Codex | 判定 |
|---|---|---|---|---|
| F-01 | Navigation SDK ライフサイクル二重化 (`MapboxNavigationApp` + `MapboxNavigationProvider`) | #1 | Critical-1 | **Critical — 確定** |
| F-02 | `setNavigationRoutes` の欠落 | #2 | Critical-3, Medium-3 | **Critical — 確定** |
| F-03 | Route Line が `RoutesObserver` 駆動でない | #3 | Critical-3 | **Critical — 確定** |
| F-04 | `alternativesMetadata` 未提供 | #3 内 | Critical-4 | **Critical — 確定** |
| F-05 | Route Callout が `RoutesObserver` 駆動でない | #4 | Critical-3 | **Critical — 確定** |
| F-06 | Listener の register/unregister 漏れ | #8, #14 | High-1 | **High — 確定** |
| F-07 | Camera 制御が Viewport state model と整合していない | #5 | High-2 | **High — 確定** |
| F-08 | 高頻度位置更新の shared ViewModel 直送 | #6 内 | High-3 | **High — 確定** |
| F-09 | `platformRoute: Any?` の型安全性 / KMP 境界 | #11 | High-4 | **High — 確定** |
| F-10 | Route selection が index + 参照同一性頼み | #3 内 | Medium-1 | **Medium — 確定** |
| F-11 | Custom Callout Adapter が SDK redraw パスから外れている | #13 | Medium-2 | **Medium — 確定** |
| F-12 | Token の source of truth 分散 | #9 | Low-1 | **Low — 確定** |

### CC のみが指摘（Codex に該当なし）

| ID | カテゴリ | CC # | 最終判定 | 理由 |
|---|---|---|---|---|
| F-13 | `NavigationCamera` + `MapboxNavigationViewportDataSource` 未使用 | #5 | **Critical — 採用** | Codex は High-2 で「camera state machine が散らばっている」と指摘したが、具体的な `NavigationCamera` / `ViewportDataSource` の推奨には踏み込んでいない。CC の指摘はより具体的かつ公式ドキュメントの推奨に直結しており、ルート/位置/進行状況の 3 データソースからカメラを自動制御する仕組みが欠けている点は Critical レベル |
| F-14 | `NavigationLocationProvider` 未使用 | #6 | **High — 採用** | Navigation SDK のマップマッチング・道路スナッピング処理を Maps SDK の Location Puck に橋渡しする公式パターン。Codex は High-3 で位置更新頻度の問題を指摘したが、`NavigationLocationProvider` という具体的な解決策には触れていない。カーナビアプリにとってスナッピングは UX に直結するため High |
| F-15 | `MapView` 参照を `MapEffect` 外で保持 | #7 | **High — 採用** | 公式が明確に警告している Compose 状態競合パターン。F-13 (`NavigationCamera` 導入) により自然に解消されるが、独立した問題として記録する価値がある |
| F-16 | Polyline デコードの自前実装 | #10 | **Low — 採用** | SDK の `LineString.fromPolyline()` と同一プロジェクト内で混在。実害は小さいがコード整理として妥当 |
| F-17 | Route Options — Traffic Congestion Annotation 未指定 | #12 | **Medium — 採用** | 渋滞カラーを `RouteLineColorResources` で細かく設定しているのに、レスポンスに congestion データが含まれない可能性がある。`applyDefaultNavigationOptions()` が内部で何を設定するか次第だが、明示的な指定がない点は指摘として妥当。ただし `applyDefaultNavigationOptions()` のソースを確認し、既にアノテーションが含まれている場合は取り下げる |

### Codex のみが指摘（CC に該当なし）

| ID | カテゴリ | Codex # | 最終判定 | 理由 |
|---|---|---|---|---|
| F-18 | Search provider が Mapbox と Google Places に分裂 | Critical-2 | **Critical — 採用** | CC が完全に見落としていた重大な指摘。仕様書 (`03_technology_evaluation.md`) では Mapbox Geocoding API を採用と記載されているが、実装は Google Places を使っている。地図上の POI タップ後に Google Places で再検索するフローは、provider 間の place identity が一致しない根本的な問題を抱えている。Mapbox エコシステム準拠の観点から Critical |
| F-19 | Route preview と active navigation の境界が曖昧 | Medium-3 | **Medium — 採用** | F-02 (`setNavigationRoutes` 欠落) の派生問題だが、「preview route をどのタイミングで Navigation SDK に ownership を渡すか」という設計判断は独立して記録する価値がある |

### 不採用・統合した指摘

| 元の指摘 | 判断 | 理由 |
|---|---|---|
| CC #14 (Click Listener 解除) | **F-06 に統合** | F-06 (Listener 漏れ) の具体例の一つ。独立した項目にする必要なし |
| CC #13 (`DefaultRouteCalloutAdapter` 検討不足) | **F-11 に統合** | Codex Medium-2 と同じ問題。有料道路ラベル表示のためにカスタム実装が必要な点は正当だが、SDK の redraw パスに乗せるべきという指摘は同一 |

---

## 確定 Finding 一覧

### Critical（再設計の前提条件）

| ID | カテゴリ | 概要 |
|---|---|---|
| **F-01** | Navigation SDK ライフサイクル | `MapboxNavigationApp` と `MapboxNavigationProvider` の二重所有。`attach/detach` 未使用。Observer パターンの土台が崩れている |
| **F-02** | `setNavigationRoutes` 欠落 | `requestRoutes` 後に SDK にルートを登録していない。全 Observer が発火しない前提条件の欠落 |
| **F-03** | Route Line — Observer 不使用 | `RoutesObserver` ではなく Compose の `MapEffect` キー変更で手動描画。リルート・混雑更新・代替ルート無効化が UI に反映されない |
| **F-04** | `alternativesMetadata` 未提供 | `setNavigationRoutes` に metadata を渡していない。プライマリルートと代替ルートの重複区間が非表示にならない |
| **F-05** | Route Callout — Observer 不使用 | Callout 更新が `RoutesObserver` に紐付いていない。SDK 内部のルート更新時に Callout が追従しない |
| **F-13** | `NavigationCamera` / `ViewportDataSource` 未使用 | カメラ管理を全て手動 `LaunchedEffect` で実装。ルート進行に応じた自動ズーム・Following/Overview 切替が効かない |
| **F-18** | Search provider 分裂 | 仕様書は Mapbox 採用だが実装は Google Places。POI タップ → Google 再検索で place identity 不一致 |

### High（機能正常化に必要）

| ID | カテゴリ | 概要 |
|---|---|---|
| **F-06** | Listener 漏れ | `addOnIndicatorPositionChangedListener` / `addOnIndicatorBearingChangedListener` / `addOnMapClickListener` の解除なし。`DisposableMapEffect` + `onDispose` を使うべき |
| **F-07** | Camera state machine 不整合 | Follow puck は `transitionToFollowPuckState`、検索/ルートは `easeTo`/`flyTo` 直接呼出し。camera mode が UI の if/else と side effect に散在 |
| **F-08** | 高頻度位置更新の ViewModel 直送 | `OnIndicatorPositionChangedListener` (animation frame 単位) の結果を共有 ViewModel に保存。UI 用の high-frequency position とルート検索用の coarse position を分離すべき |
| **F-09** | `platformRoute: Any?` / KMP 境界 | `commonMain` の model が Android の `NavigationRoute` を `Any?` で暗黙に前提。`as? NavigationRoute` キャスト散在。silent failure リスク |
| **F-14** | `NavigationLocationProvider` 未使用 | Maps SDK デフォルト LocationProvider を使用。Navigation SDK のマップマッチング・道路スナッピングが Location Puck に反映されない |
| **F-15** | `MapView` 参照の外部保持 | `remember { mutableStateOf<MapView?>() }` で保持し `LaunchedEffect` 内で使用。公式が警告する Compose 状態競合パターン |

### Medium

| ID | カテゴリ | 概要 |
|---|---|---|
| **F-10** | Route selection が index + 参照同一性頼み | route list refresh で順序/object identity が変わると選択が不安定。stable route id を導入すべき |
| **F-11** | Callout Adapter が SDK redraw パスから外れている | `notifyDataSetChanged()` を使わず View 直接操作。`calloutViews` Map の手動管理が SDK のライフサイクルと競合する可能性 |
| **F-17** | Traffic Congestion Annotation | `ANNOTATION_CONGESTION_NUMERIC` の明示的指定なし。渋滞カラー設定が機能していない可能性。`applyDefaultNavigationOptions()` の内部実装次第で取り下げ |
| **F-19** | Route preview / active navigation 境界 | preview route を Navigation SDK に ownership を渡すタイミングが未定義。Active Guidance 移行時の設計が必要 |

### Low

| ID | カテゴリ | 概要 |
|---|---|---|
| **F-12** | Token の source of truth 分散 | `strings.xml` / `BuildKonfig` / `LaunchedEffect` の複数設定箇所。Application レベルに一本化 |
| **F-16** | Polyline デコーダー自前実装 | SDK の `LineString.fromPolyline()` が存在するのに 40 行の自前デコーダー。同一プロジェクト内で混在 |

---

## 両監査間の相違点と裁定

### 1. Search provider 問題の重大度

- **Codex:** Critical-2 として最重要問題の一つに位置づけ
- **CC:** 完全に見落とし

**裁定: Codex の判断を採用 (Critical)**

理由: 仕様書と実装の乖離は設計問題の中でも最も根本的。Mapbox 地図上の POI と Google Places の検索結果が別 object になる問題は、ユーザー体験に直接影響する。ただし、Google Places の採用が**意図的な設計判断**（例: Mapbox Search SDK の機能不足、Google Places の方がデータ品質が高い等）である可能性もあるため、仕様書の更新 or 実装の変更のどちらが正しいかはプロジェクトオーナーの判断を仰ぐべき。

### 2. NavigationCamera / ViewportDataSource の具体性

- **CC:** 公式コード例付きで `NavigationCamera` + `MapboxNavigationViewportDataSource` の具体的パターンを提示。Critical 判定
- **Codex:** High-2 として「camera state machine が散らばっている」と抽象的に指摘。具体的な SDK コンポーネント名には触れず

**裁定: CC の具体性を採用、重大度は Critical**

理由: 公式ドキュメントが `NavigationCamera` + `ViewportDataSource` + `RoutesObserver` / `LocationObserver` / `RouteProgressObserver` の 3 データソース連携を明確に推奨しており、これは Observer パターン全体の設計に直結する。Codex の「sealed interface で camera mode を定義する」提案は独自設計としては良いが、SDK が既に提供している仕組みを使う方が Mapbox エコシステム準拠の観点では正しい。

### 3. NavigationLocationProvider

- **CC:** 独立した指摘 (#6) として詳細に記述
- **Codex:** High-3 で位置更新頻度を指摘したが、`NavigationLocationProvider` には未言及

**裁定: CC の指摘を採用 (High)**

理由: カーナビアプリにとってマップマッチング・道路スナッピングは UX の根幹。`NavigationLocationProvider` は Navigation SDK と Maps SDK を繋ぐ公式ブリッジであり、これを使わないのは明確なベストプラクティス違反。

### 4. Traffic Congestion Annotation

- **CC:** #12 として指摘。渋滞カラー設定と実データの不整合の可能性
- **Codex:** 明示的には指摘なし。ただし Positive Observations で「`applyDefaultNavigationOptions()` を利用しており route line 表示に必要な annotation 要件に乗りやすい」と言及

**裁定: CC の指摘を条件付き採用 (Medium)**

理由: `applyDefaultNavigationOptions()` が内部で `ANNOTATION_CONGESTION_NUMERIC` を含んでいる可能性が高い（Codex もその可能性を示唆）。ただし明示的な指定がない以上、動作確認なしに「問題なし」とは言えない。ソースコード確認後に取り下げの可能性あり。

### 5. Camera state machine の設計提案

- **CC:** `NavigationCamera` の Following / Overview / Idle をそのまま使う提案
- **Codex:** カスタム sealed interface (`FollowPuck` / `SelectedPlace` / `SearchResultsOverview` / `RouteOverview`) の提案

**裁定: 両方を統合**

理由: `NavigationCamera` はナビゲーション中の Following / Overview に最適だが、**ルートプレビュー前の検索結果表示や選択地点表示**は `NavigationCamera` のスコープ外。Codex の「検索結果表示」「選択地点」はアプリ独自のカメラモードとして設計する必要がある。推奨は:

- ナビゲーション関連（Following / Overview / Idle）: `NavigationCamera` + `ViewportDataSource`
- 検索・選択関連（`SelectedPlace` / `SearchResultsOverview`）: アプリ側の camera state → `MapViewportState` 操作

---

## 改善優先度（最終版）

### Phase 0 — 土台修正（即時対応）

| 優先度 | ID | 内容 | 依存 |
|---|---|---|---|
| **P0-1** | F-01 | `MapboxNavigationProvider.create()` 廃止。`MapboxNavigationApp.setup()` + `attach(lifecycleOwner)` に一本化 | なし |
| **P0-2** | F-02 | `requestRoutes` 後に `navigation.setNavigationRoutes(routes)` を呼ぶ | P0-1 |
| **P0-3** | F-06 | 全 Listener を `DisposableMapEffect` + `onDispose` に移行 | なし |
| **P0-4** | F-12 | AccessToken を `Application.onCreate()` に一本化 | なし |

### Phase 1 — Observer パターン導入

| 優先度 | ID | 内容 | 依存 |
|---|---|---|---|
| **P1-1** | F-03, F-04, F-05 | `RoutesObserver` 導入。Route Line + Callout の更新を Observer 駆動に。`alternativesMetadata` を渡す | P0-2 |
| **P1-2** | F-13 | `NavigationCamera` + `MapboxNavigationViewportDataSource` 導入。手動 `LaunchedEffect` カメラ操作を廃止 | P0-2 |
| **P1-3** | F-14 | `NavigationLocationProvider` 導入。Maps SDK の LocationProvider を差し替え | P0-1 |
| **P1-4** | F-15, F-07 | `MapView` 参照の外部保持を廃止。P1-2 で自然に解消 | P1-2 |

### Phase 2 — Search 統一

| 優先度 | ID | 内容 | 依存 |
|---|---|---|---|
| **P2-1** | F-18 | Search provider を Mapbox Search SDK に統一するか、Google Places 維持を設計判断として明記 | なし（設計判断） |
| **P2-2** | F-18 | 統一する場合: POI タップ / 長押し / 検索を Mapbox Search SDK の `search` / `select` / reverse geocoding に移行 | P2-1 の判断後 |

### Phase 3 — モデル・コード整理

| 優先度 | ID | 内容 | 依存 |
|---|---|---|---|
| **P3-1** | F-09 | `RouteResult.platformRoute: Any?` 廃止。Android layer で `routeId → NavigationRoute` マッピングを管理 | P1-1 |
| **P3-2** | F-10 | stable route id 導入。選択状態を id ベースに | P3-1 |
| **P3-3** | F-08 | 位置更新の責務分離。high-frequency position は Android layer に閉じ込め、coarse position のみ shared 層へ | P1-3 |
| **P3-4** | F-11 | Callout Adapter を `notifyDataSetChanged()` ベースに。または `DefaultRouteCalloutAdapter` で十分か再評価 | P1-1 |
| **P3-5** | F-19 | Route preview session の設計。preview → active guidance の遷移ポイントを定義 | P1-1 |
| **P3-6** | F-17 | `ANNOTATION_CONGESTION_NUMERIC` の明示的指定。`applyDefaultNavigationOptions()` のソース確認後に判断 | なし |
| **P3-7** | F-16 | 自前 Polyline デコーダー削除。`LineString.fromPolyline()` に統一 | なし |

---

## 推奨アーキテクチャ（最終版）

### レイヤー構成

```
┌─────────────────────────────────────────────────────────────────┐
│  MapboxNavigation (Singleton via MapboxNavigationApp)            │
│  ├─ attach(lifecycleOwner) / detach                             │
│  ├─ setNavigationRoutes(routes)                                 │
│  ├─ startTripSession() / stopTripSession()                      │
│  └─ Observers (MapboxNavigationObserver で一括管理):             │
│      ├─ RoutesObserver                                          │
│      ├─ RouteProgressObserver                                   │
│      ├─ LocationObserver                                        │
│      └─ VoiceInstructionsObserver (将来)                         │
├─────────────────────────────────────────────────────────────────┤
│  Android Navigation Layer (新設)                                 │
│  ├─ HomeMapNavigationController                                 │
│  │   ├─ RoutesObserver → Route Line + Callout 更新              │
│  │   ├─ RoutesObserver → ViewportDataSource.onRouteChanged      │
│  │   ├─ RouteProgressObserver → Vanishing Route Line            │
│  │   ├─ RouteProgressObserver → ViewportDataSource              │
│  │   ├─ LocationObserver → NavigationLocationProvider            │
│  │   └─ LocationObserver → ViewportDataSource                   │
│  ├─ NavigationCamera (Following / Overview / Idle)              │
│  ├─ routeId → NavigationRoute マッピング                         │
│  └─ route line / callout lifecycle                              │
├─────────────────────────────────────────────────────────────────┤
│  ViewModel (ビジネスロジックのみ — commonMain)                    │
│  ├─ 検索（Mapbox Search SDK or Google Places — 要判断）          │
│  ├─ 検索履歴                                                     │
│  ├─ waypoint 管理                                                │
│  ├─ RouteUiModel(id, duration, distance, toll, labels) 公開     │
│  ├─ selectedRouteId                                              │
│  └─ camera mode (sealed interface)                              │
│      ├─ FollowPuck(mode) → NavigationCamera.Following           │
│      ├─ RouteOverview → NavigationCamera.Overview                │
│      ├─ SelectedPlace(point) → アプリ独自 easeTo                 │
│      ├─ SearchResultsOverview(points) → アプリ独自 fitBounds     │
│      └─ Idle → NavigationCamera.Idle                             │
├─────────────────────────────────────────────────────────────────┤
│  Compose Layer                                                   │
│  ├─ MapboxMap + MapboxStandardStyle                              │
│  ├─ DisposableMapEffect (Observer 登録/解除)                      │
│  ├─ 宣言的 Annotation (ViewAnnotation, Marker)                   │
│  └─ UI Controls (Compass, Zoom, Tracking)                        │
└─────────────────────────────────────────────────────────────────┘
```

### データフロー

```
[ルート検索]
ViewModel.searchRoutes(waypoints)
    → DataSource: navigation.requestRoutes(callback)
    → callback: navigation.setNavigationRoutes(routes)
    → RoutesObserver.onRoutesChanged 自動発火
        → routeLineApi.setNavigationRoutes(routes, alternativesMetadata)
        → routeLineView.renderRouteDrawData(style, value)
        → viewportDataSource.onRouteChanged(routes.first())
        → viewportDataSource.evaluate()
    → ViewModel: RouteUiModel リストを StateFlow で公開（UI 表示用）

[ルート選択変更]
UI: ルートタップ or Callout タップ
    → navigation.setNavigationRoutes(reorderedRoutes)
    → RoutesObserver.onRoutesChanged 自動発火
    → Route Line + Callout 自動再描画

[位置更新]
Navigation SDK → LocationObserver.onEnhancedLocationChanged
    → navigationLocationProvider.changePosition(location)  // Puck 自動更新
    → viewportDataSource.onLocationChanged(location)
    → viewportDataSource.evaluate()                        // Camera 自動更新
    → (coarse position のみ) ViewModel.updateUserLocation() // ルート検索用

[検索]
ViewModel.search(query)
    → SearchDataSource (Mapbox Search SDK or Google Places)
    → 候補/結果を StateFlow で公開
    → camera mode = SearchResultsOverview(points) or SelectedPlace(point)
```

---

## Positive Observations

両監査が認めた、現状実装で Mapbox 推奨に近い点:

- `MapViewportState` を Compose 側で `rememberMapViewportState()` で保持 — 正しい
- `MapboxStandardStyle` + `StandardStyleState` を使用 — 正しい
- Location Puck 表示は Mapbox の location component を使用 — 正しい
- `RouteOptions.builder().applyDefaultNavigationOptions()` の利用 — 正しい
- Route Line / Callout の有効化手順自体は SDK の想定に近い — 正しい
- `RouteLineColorResources` での渋滞カラー設定 — 正しい（データが来ていれば）
- Compose 宣言的 Annotation (`ViewAnnotation`, `Marker`) の活用 — 正しい
- `DisposableEffect` での `routeLineApi.cancel()` / `routeLineView.cancel()` — 正しい
- 検索クエリの `debounce(300ms)` + `distinctUntilChanged()` — 正しい
- Toll-free ルート優先の 2 段階検索ロジック — 独自だが妥当

---

## Final Assessment

### 再設計が必要な領域

1. **Navigation ownership** — `MapboxNavigationApp` への一本化 + Observer パターン導入
2. **Route rendering** — `RoutesObserver` 駆動の Route Line + Callout
3. **Camera** — `NavigationCamera` + `ViewportDataSource` + アプリ独自 camera mode
4. **Location** — `NavigationLocationProvider` + 位置更新責務の分離
5. **Search** — Mapbox Search SDK 統一 or Google Places 維持の設計判断
6. **KMP 境界** — `platformRoute: Any?` 廃止、stable route id 導入

### 部分修正で対応可能な領域

- Listener の `DisposableMapEffect` 移行
- AccessToken の `Application.onCreate()` 一本化
- Polyline デコーダーの SDK API 統一
- Traffic Congestion Annotation の明示的指定

### 設計判断が必要な点

- **Search provider**: Mapbox Search SDK に統一するか、Google Places を維持するか。仕様書は Mapbox 採用だが、Google Places の方がデータ品質が高い可能性もある。**プロジェクトオーナーが判断し、仕様書を更新すること**
- **Route preview session**: preview 段階で `setNavigationRoutes` を呼ぶべきか。呼ぶと Trip Session との関係が発生し課金に影響する可能性がある。**Mapbox の pricing model を確認の上判断すること**
- **`NavigationCamera` の Compose 互換性**: `NavigationCamera` は View ベースの API。Compose の `MapViewportState` とどう共存させるかは実装時に検証が必要

---

## Source Documents

| ドキュメント | 用途 |
|---|---|
| [Navigation SDK - Initialization](https://docs.mapbox.com/android/navigation/guides/get-started/initialization/) | F-01: ライフサイクル管理 |
| [Navigation SDK - Route Line](https://docs.mapbox.com/android/navigation/guides/ui-components/route-line/) | F-03, F-04: Route Line Observer パターン |
| [Navigation SDK - Route Callout](https://docs.mapbox.com/android/navigation/guides/ui-components/route-callout/) | F-05, F-11: Callout Observer パターン |
| [Navigation SDK - Camera](https://docs.mapbox.com/android/navigation/guides/ui-components/camera/) | F-07, F-13: NavigationCamera + ViewportDataSource |
| [Maps SDK - Jetpack Compose](https://docs.mapbox.com/android/maps/guides/using-jetpack-compose/) | F-06, F-15: MapEffect / DisposableMapEffect |
| [Maps SDK - Interactions](https://docs.mapbox.com/android/maps/guides/user-interaction/interactions/) | F-06: Click/LongClick handling |
| [Maps SDK - Gestures](https://docs.mapbox.com/android/maps/guides/user-interaction/gestures/) | F-06: Gesture listener lifecycle |
| [Maps SDK - Location](https://docs.mapbox.com/android/maps/guides/user-location/location-on-map/) | F-14: NavigationLocationProvider |
| [Search SDK - Geocoding](https://docs.mapbox.com/android/search/guides/search-engine/geocoding/) | F-18: Search provider 統一 |
