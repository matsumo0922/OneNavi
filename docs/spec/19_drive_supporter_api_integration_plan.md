# 19. drive-supporter-api 統合 実装計画

> **作成日:** 2026-04-22
> **ステータス:** ドラフト（実装着手待ち）
> **対象:** `../drive-supporter-api` (別管理リポジトリ) を OneNavi の git submodule として取り込み、ルート検索 / turn-by-turn 案内を移譲する
> **前提ドキュメント:** `18_external_nav_api_migration_plan.md` が設計の正本。本書はそれを drive-supporter-api (実装済みライブラリ) に即して具体化する実装計画
> **ブランチ:** `feat/ext-nav-api-integration`

---

## 0. 本ドキュメントの位置付け

18 番の移行計画は「外部ナビ API ライブラリ」という抽象名で書かれていたが、実体となるライブラリ `drive-supporter-api` が別リポジトリで動作確認済みになり、公開 API シグネチャが確定した。本書は:

- `drive-supporter-api` の実 API に合わせた置換対象の確定
- git submodule + composite build の具体手順
- Phase 1 の再定義（ユーザー合意済みスコープ）
- タスクブレークダウンとリスク

を行う。18 番の決定ログ (D-100〜D-108) は維持する。

---

## 1. 確定事項（ユーザー合意済み）

| 論点 | 決定 |
|---|---|
| submodule 配置 | ルート直下 `OneNavi/drive-supporter-api/` |
| credential key | `EXT_NAV_LOGIN_ID` / `EXT_NAV_PASSWORD` （BuildKonfig setField 慣用） |
| Q-102 （Navigator 未起動時挙動） | 事前検証せず **NavigationView 前提で突き進む**。問題発生時に対処 |
| Phase 1 代替ルート本数 | 1 本 (`CarPriority.Recommended` 固定)。3 本対応は Phase 2 |
| TTS 戦略 | **Google Cloud TTS Chirp 3 HD に SSML サポートを追加し、`alphabet="x-toshiba-ruby"` → W3C SSML kana ruby に変換するコンバータを書く**。Android TTS フォールバックはプレーンテキストのまま |
| credential 未設定時 | エラー画面 + README 案内。ビルド自体は通る。ランタイムで `ExtNavAuthGateway.ensureSignedIn()` が失敗し UI エラー |
| Phase 1 scope | Route / Guidance / TTS / Reroute のみ。 **traffic / GuideImage (青看板・JCT 3D) は Phase 2** |

---

## 2. drive-supporter-api の公開 API 要点

調査した公開シグネチャ（詳細は `plan/00-overview.md` 他）:

```kotlin
// root package: me.matsumo.drive.supporter.api

class DriveSupporterClient(
    context: Context?,                  // tokenStore 未指定時は必須
    private val config: DriveSupporterConfig,
    engine: HttpClientEngine? = null,
    private val dispatchers: ApiDispatchers = ApiDispatchers(),
    private val json: Json = DriveSupporterJson,
) {
    val auth: AuthClient        by lazy { ... }
    val route: RouteClient      by lazy { ... }
    val guidance: GuidanceClient by lazy { ... }
    val image: GuideImageClient by lazy { ... }
    val traffic: TrafficClient  by lazy { ... }
    fun close()
}

data class DriveSupporterConfig(
    val deviceUuid: DeviceUuid,
    val appVersion: String = DEFAULT_APP_VERSION,
    val userAgent: String = DEFAULT_USER_AGENT,
    val baseUrl: String = DEFAULT_BASE_URL,
    val libraBasicCredential: String = DEFAULT_LIBRA_BASIC,
    val logLevel: LogLevel = LogLevel.NONE,
    val tokenStore: TokenStore? = null,
)
```

Phase 1 で触るメソッド:

- `AuthClient.signInWithCredentials(loginId, password): ApiResult<AuthState.SignedIn>`
- `RouteClient.search(criteria): ApiResult<ImmutableList<Route>>`
- `GuidanceClient.resolveGuidance(criteria): ApiResult<Guidance>`

ドメインモデル（import する型）:

- `core.model.Coord` / `DeviceUuid` / `LogLevel`
- `core.result.ApiResult` / `ApiFailure`
- `auth.domain.AuthState` / `Credentials`
- `route.domain.RouteSearchCriteria` / `Route` / `RouteSummary` / `CarPriority`
- `guidance.domain.Guidance` / `GuidancePoint` / `SsmlPhrase` / `GuidanceCategory`

### 2.1 逆解析ドキュメントから抜いた「実装に効く事実」

1. **Libra ヘッダの `x-up-phone-id: drive_renew` がないと HTTP 200 + 0 byte で silent fail する**（ライブラリ内で処理済み。呼び出し側は気にしなくてよい）
2. **`x-ntj-user-course-type` が空文字のレスポンスはセッション降格（匿名）**。ライブラリは `ApiFailure.Auth.Downgraded` を返す。OneNavi は再 signIn すべき
3. **`SsmlPhrase.ssml` 内の `<phoneme alphabet="x-toshiba-ruby" ph="...">...</phoneme>` は Android TTS / Google Cloud TTS どちらにもそのままでは読めない**
4. **DSR エンドポイントは 1 ルートしか返さない**（代替ルート 3 本は `priority` を変えて 3 並列）
5. **画像 ID の実在判定は ZIP entry size ≤ 4 byte。`GuideImage.isMissing` で吸収済み**（Phase 2 で利用）
6. **DeviceUuid は 1 端末 1 UUID**。毎回ランダム生成はアノマリー扱いの疑い → 初回起動時に DataStore 永続化（18 番計画 §2.3）

---

## 3. ビルド統合の具体手順

### 3.1 git submodule

```bash
git submodule add git@github.com:matsumo0922/drive-supporter-api.git drive-supporter-api
git submodule update --init --recursive
```

- `.gitmodules` がリポジトリルートに作成される
- `drive-supporter-api/` ディレクトリは commit hash でピン留め

### 3.2 settings.gradle.kts 変更

```kotlin
// OneNavi/settings.gradle.kts
pluginManagement {
    includeBuild("build-logic")
    includeBuild("drive-supporter-api/build-logic")  // 追加: drive-supporter-api 側の convention plugin
    repositories { ... }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { ... }
}

// 既存モジュール
include(":composeApp")
...

// drive-supporter-api を composite build として統合
includeBuild("drive-supporter-api")
```

#### 既知の衝突リスク: build-logic の rootProject.name 競合

- OneNavi/build-logic/settings.gradle.kts の `rootProject.name = "build-logic"`
- drive-supporter-api/build-logic/settings.gradle.kts の `rootProject.name = "build-logic"`

両方を `pluginManagement.includeBuild` で取り込むと同名 root が衝突する可能性がある。回避策:

1. **Plan A（推奨）:** drive-supporter-api 側の build-logic の rootProject.name を `drive-supporter-api-build-logic` にする PR を先に出す
2. **Plan B:** drive-supporter-api 側で `publishToMavenLocal` してから OneNavi 側では普通の maven 依存として扱う（includeBuild 断念）
3. **Plan C:** OneNavi 側の build-logic をリネームする（影響範囲が広い）

Plan A を先に通す前提で進める。

### 3.3 version mismatch リスク

| 項目 | OneNavi | drive-supporter-api |
|---|---|---|
| Kotlin | 2.3.10 | 2.2.10 |
| ktor | 3.3.3 | 3.2.2 |
| kotlinx.serialization | 1.10.0 | 1.8.0 |
| kotlinx.collections.immutable | 0.4.0 | 0.3.8 |
| AGP | 8.13.2 | 8.13.2 |
| compileSdk | 36 | 36 |
| minSdk | 28 | 26 |
| Java | 17 | 17 |

- Kotlin 2.3 コンパイラは 2.2 生成コードを読めるので実害は出にくい
- ktor / serialization は Gradle 側が最新に寄せるはず（FAIL_ON_PROJECT_REPOS でも included build 側は独自解決）
- 実ビルドで `Duplicate class` / `NoSuchMethodError` が出たら `drive-supporter-api` 側を OneNavi に合わせるアップデート PR

### 3.4 core/navigation モジュールでの依存追加

```kotlin
// core/navigation/build.gradle.kts （androidMain に限定）
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation("me.matsumo.drive.supporter:drive-supporter-api")
            // 他
        }
    }
}
```

composite build が同 group/artifact を自動置換する。

### 3.5 credential 配線

#### local.properties

```properties
# External Nav API credentials (keep out of VCS)
EXT_NAV_LOGIN_ID=...
EXT_NAV_PASSWORD=...
```

（既存の `navitime.*` は drive-supporter-api のテスト用。両方書いても良い）

#### composeApp/build.gradle.kts BuildKonfig

```kotlin
buildkonfig {
    packageName = "me.matsumo.onenavi"
    defaultConfigs {
        // 既存
        setField("GOOGLE_API_KEY", ...)
        setField("GOOGLE_CLOUD_TTS_API_KEY", ...)
        // 追加
        setField("EXT_NAV_LOGIN_ID", localProperties.getProperty("EXT_NAV_LOGIN_ID", ""))
        setField("EXT_NAV_PASSWORD", localProperties.getProperty("EXT_NAV_PASSWORD", ""))
    }
}
```

#### core/model AppConfig

```kotlin
@Immutable
data class AppConfig(
    ...
    val extNavLoginId: String,
    val extNavLoginPassword: String,
)
```

空文字の場合はランタイムで `ExtNavAuthGateway` が `ApiFailure.BadInput` を返し、UI はエラースナックバー（README 案内）。

---

## 4. OneNavi 側の置換対象マップ

### 4.1 削除

棚卸しで実在確認済みのクラス:

| パス | 処置 |
|---|---|
| `core/navigation/androidMain/NavigationSdkManager.kt` L148–170 の `setDestinations` / `startGuidance` / `stopGuidance` / Arrival listener | 削除。`NavigationApi.getNavigator()` と `RoadSnappedLocationProvider` 取得だけ残す |
| `core/navigation/androidMain/TurnByTurnUpdateBus.kt` | 削除 |
| `core/navigation/androidMain/NavigationUpdatesService.kt` | **書き換え**。常駐通知用 foreground service としてのみ残す。feed publish 削除 |
| `core/navigation/androidMain/NavigationSdkModels.kt`（NavigationFeedSnapshot / NavigationStepSnapshot / NavigationLaneSnapshot） | 削除 |
| `core/navigation/androidMain/guidance/GuidanceCoordinator.kt` | **書き換え**（`ExtNavGuidanceTracker` 入力に変更） |
| `core/navigation/androidMain/guidance/GuidancePlanner.kt` | 削除 |
| `core/navigation/commonMain/guidance/PhraseComposer.kt` | 削除 |
| `core/navigation/androidMain/guidance/SpeechDispatcher.kt` | **書き換え**（SSML 入力に変更） |
| `core/navigation/androidMain/guidance/StepTransitionTracker.kt` | 削除 |
| `core/model/commonMain/GuidanceEvent.kt` / `DistanceBucket.kt` / `FollowupDistanceBucket.kt` / `FollowupManeuver.kt` / `TtsPhraseId.kt` / `CompassDirection.kt` | 削除 |
| `core/model/commonMain/RouteResult.kt` の `platformRoute: Any?` | 削除。`ExtNavRoute` を androidMain で型安全に持つ |
| `core/datasource/androidMain/GoogleRoutesDataSource.kt` | Phase 1 は残す（Phase 2 削除） |

### 4.2 残す

| 対象 | 備考 |
|---|---|
| `core/model/commonMain/ManeuverType.kt` / `ManeuverModifier.kt` / `LaneInfo.kt` | UI アイコン決定に使うため残す。`Guidance.guidancePoints[].categories` から射影 |
| TTS エンジン 3 実装 (`TtsEngine` / `AndroidTtsEngine` / `GoogleCloudTtsEngine` / `FallbackTtsEngine`) | 残す。Google Cloud TTS に SSML サポート追加 |
| `core/ui/androidMain/callout/*` | 残す（17 番の redesign 計画準拠） |
| `RouteManager` / `HomeMapViewModel` / `HomeMapsMapEffectContent` の骨格 | 残す（ルート線データソースを外部ナビ由来に差し替え） |

### 4.3 新規追加

| 新規クラス | 配置 | 責務 |
|---|---|---|
| `ExtNavClientProvider` | `core/navigation/androidMain/extnav/` | `DriveSupporterClient` を Koin で lazy singleton 化。`AppConfig` から `DriveSupporterConfig` を組み立て、DataStore から `DeviceUuid` を読む |
| `ExtNavAuthGateway` | `core/navigation/androidMain/extnav/` | 初回 / 期限切れ時に `signInWithCredentials()`。`ApiFailure.Auth.Downgraded` をハンドルして再認証 |
| `ExtNavRoute` | `core/navigation/androidMain/extnav/` | `Route` + `Guidance` + `GuideImagePreloadHandle?` のペア |
| `ExtNavRouteDataSource` | `core/datasource/androidMain/extnav/` | `RouteClient.search()` + `GuidanceClient.resolveGuidance()` を並列 `async` で投げて `ExtNavRoute` を組む |
| `ExtNavRouteRepository` | `core/repository/` （`expect`/`actual`、impl は androidMain） | ドメイン I/F。失敗は `Result<ExtNavRoute>` で返す |
| `ExtNavGuidanceTracker` | `core/navigation/androidMain/extnav/` | `guidancePoints` + `Location` → 最近傍 GP / 残距離 / 残時間 / offRouteDistance を StateFlow で公開 |
| `ExtNavAnnouncementScheduler` | `core/navigation/androidMain/extnav/` | GP 進捗から発話キューを組み立て、`SsmlPhrase` を TTS に投入。`(gp.index, phrase.category, phrase.distanceMetres)` で dedupe |
| `ExtNavRerouteDetector` | `core/navigation/androidMain/extnav/` | offRoute 判定 + 再検索トリガ |
| `ExtNavCameraController` | `core/navigation/androidMain/extnav/` | 18 番 §1.5 の自前 auto-zoom。Phase 1 は最小実装（通常追従のみ、交差点接近ズームは Phase 2） |
| `ExtNavSsmlSpeaker` | `core/navigation/androidMain/tts/` | SSML の `<phoneme alphabet="x-toshiba-ruby">` を W3C kana ruby に変換。Google Cloud TTS は SSML のまま / Android TTS はプレーン化 |
| `PhonemeConverter` | `core/navigation/commonMain/tts/` | `alphabet="x-toshiba-ruby"` → `alphabet="x-amazon-pron-kana"`（あるいは W3C 標準 `ph` に kana そのまま）の変換ロジック。単体テスト対象 |
| `DeviceUuidStore` | `core/datasource/androidMain/extnav/` | DataStore に UUID を持つ。null なら `DeviceUuid.random()` を生成し save |

### 4.4 TTS 側の改修（決定事項 §1）

現状:
- `GoogleCloudTtsDto.SynthesizeInput` は `text: String` のみ（SSML フィールドなし）
- `FallbackTtsEngine(primary=GoogleCloud, fallback=Android)`

Phase 1 の改修:
1. `SynthesizeInput` に `ssml: String?` を追加（`text` か `ssml` の排他）
2. `GoogleCloudTtsEngine.speak(text)` を `speak(input: TtsInput)` に拡張（`TtsInput.Plain(text)` / `TtsInput.Ssml(ssml)`）
3. `ExtNavSsmlSpeaker` が `PhonemeConverter` で東芝系を W3C 標準へ変換
4. `AndroidTtsEngine` は SSML 入力でも `plainText()` 化してから喋らせる
5. `TtsAudioCache` のキーを `ssml` 全文 → 同一 SSML は再生成しない

---

## 5. データフロー（18 番の再確認 + 実 API 接続）

### 5.1 ルート検索

```
User intent share / search select
  ↓
HomeMapViewModel.onRouteSearch
  ↓
ExtNavRouteRepository.search(origin, goal, waypoints)
  ├─ ExtNavAuthGateway.ensureSignedIn()
  │    └─ AuthClient.signInWithCredentials(loginId, password) if needed
  ├─ 並列:
  │     async { RouteClient.search(criteria).getOrNull()?.firstOrNull() }
  │     async { GuidanceClient.resolveGuidance(criteria).getOrNull() }
  └─ ExtNavRoute(route, guidance)
  ↓
RouteManager.setRoute(extNavRoute)
  ↓
UI: polyline (guidance.guidancePoints[].subPathSamples から構築) + RouteSummary
```

### 5.2 ナビ中ループ

```
RoadSnappedLocationProvider.LocationListener.onLocationChanged
  ↓
ExtNavGuidanceTracker.onLocation(location)
  ├─ 最近傍 GP 探索（GP.distanceFromStart で単調増加）
  ├─ offRouteDistance 算出
  └─ GuidanceUiState (残時間/残距離/次マニューバ/次方面看板) 更新
  ↓
  ├─ ExtNavAnnouncementScheduler: phrase.distanceMetres しきい値 + dedupe → SSML 発話
  └─ ExtNavRerouteDetector: offRoute 持続 → ExtNavRouteRepository.search() で再検索
```

---

## 6. Phase 1 タスクブレークダウン（18 番を実情に合わせ更新）

| # | タスク | 依存 | 目安 |
|---|---|---|---|
| T01 | git submodule add + drive-supporter-api 側 build-logic rootProject.name リネーム PR | — | 0.5d |
| T02 | `settings.gradle.kts` に `includeBuild` 2 件追加、ビルド確認 | T01 | 0.5d |
| T03 | `local.properties` / BuildKonfig / AppConfig に credential 追加 | T02 | 0.5d |
| T04 | `DeviceUuidStore` 実装。`AppSetting` / `AppSettingDataSource` に `extNavDeviceUuid` 追加 | T03 | 0.5d |
| T05 | `ExtNavClientProvider` + Koin DI 登録 (`NavigationModule.android`) | T03 T04 | 0.5d |
| T06 | `ExtNavAuthGateway` 実装 + `ApiFailure.Auth.Downgraded` ハンドリング | T05 | 1d |
| T07 | `ExtNavRouteDataSource` / `ExtNavRouteRepository` 実装（priority=Recommended 固定） | T06 | 1d |
| T08 | `ExtNavRoute` モデル + 既存 `RouteResult` / `GoogleRoute` の androidMain 側差し替え | T07 | 1d |
| T09 | `NavigationSdkManager` から Navigator ガイダンス配線を剥離 | — (並列) | 0.5d |
| T10 | `TurnByTurnUpdateBus` / `NavigationFeedSnapshot` / `NavigationStepSnapshot` / `NavigationLaneSnapshot` 削除 | T09 | 0.5d |
| T11 | `GuidancePlanner` / `PhraseComposer` / `StepTransitionTracker` / `GuidanceEvent` / `DistanceBucket` / `FollowupManeuver` / `CompassDirection` / `TtsPhraseId` 削除 | T10 | 1d |
| T12 | `ExtNavGuidanceTracker` 実装 + unit test | T08 | 1.5d |
| T13 | `PhonemeConverter` 実装 + unit test（`x-toshiba-ruby` → W3C SSML） | — (並列) | 1d |
| T14 | `GoogleCloudTtsEngine` に SSML サポート追加 + `TtsInput` 型導入 + キャッシュ更新 | T13 | 1d |
| T15 | `ExtNavSsmlSpeaker` 実装（SSML 正規化 → TtsInput 生成 → TtsEngine） | T14 | 0.5d |
| T16 | `ExtNavAnnouncementScheduler` 実装（dedupe + priority キュー） | T12 T15 | 1.5d |
| T17 | `GuidanceCoordinator` / `SpeechDispatcher` を新 flow に書き換え（CRITICAL / NORMAL チャネル） | T16 | 1d |
| T18 | `GuidanceSessionManager` 書き換え | T17 | 1d |
| T19 | `ExtNavRerouteDetector` 実装 | T12 | 1d |
| T20 | `ExtNavCameraController` 最小実装（通常追従 + 手動操作検知） | T12 | 0.5d |
| T21 | `HomeMapsMapEffectContent` のポリライン描画を `guidance.guidancePoints[].subPathSamples` 由来に差し替え | T08 | 1d |
| T22 | `HomeMapViewModel` / `RouteManager` / Repository を新モデルに追従 | T21 | 1d |
| T23 | 到着判定（goal 50m / 20m） + 経由地通過 + Arrival UI 配線 | T18 | 0.5d |
| T24 | `NavigationUpdatesService` を常駐通知 only に書き換え（feed publish 削除） | T09 | 0.5d |
| T25 | 既存の `GuidancePlannerTest` / `SpeechDispatcherTest` 廃止。`ExtNavGuidanceTrackerTest` / `ExtNavAnnouncementSchedulerTest` / `PhonemeConverterTest` 追加 | — (並列) | 1d |
| T26 | 実機テスト（都内→千葉 / 都内→宇都宮 の 2 ルートで golden path 確認） | 上記全部 | 1d |
| T27 | 旧 provider 関連コード・旧 provider token 撤去 | T26 | 0.5d |

**合計目安: 約 20 営業日（4 週間）**。18 番の「4〜6 週間」見積りと一致。

---

## 7. リスク（18 番から変動した分）

| リスク | 対処 |
|---|---|
| **build-logic rootProject.name 衝突** | drive-supporter-api 側に rename PR を先行投入（T01） |
| **Kotlin 2.3 / 2.2 の mix build 不整合** | CI / ローカルで `assembleDebug` が通ることを T02 で確認。ダメなら drive-supporter-api を 2.3 に上げる PR |
| **ktor 3.2 / 3.3 の transitive 衝突** | 同上。`implementation` 構成なら Gradle が最新に寄せるので通常は無害 |
| **SSML phoneme 変換の完成度不足** | Phase 1 は `<phoneme>` タグの中身 (`ph` 属性のかな) を抽出し「タグ自体を剥がした + phoneme の kana 文字列を採用したプレーンテキスト」にフォールバック。W3C 標準の `alphabet="x-amazon-pron-kana"` への変換は Chirp 3 HD がサポートしない場合のみフォールバック |
| **セッション降格 (`ApiFailure.Auth.Downgraded`)** | `ExtNavAuthGateway` が `signInWithCredentials()` を再実行。3 回連続失敗で UI エラー |
| **Q-102 問題が実装後半で顕在化** | 「突き進む」方針。T21 時点で NavigationView のライフサイクルがクラッシュしたら §11.2（plain MapView 退避）に切替。工数 +1〜2 週 |

---

## 8. Open Questions（確認事項）

| Q | 内容 | 重要度 | 扱い |
|---|---|---|---|
| Q-200 | drive-supporter-api の build-logic rootProject.name リネーム PR 要否 | HIGH | T01 で試して、衝突したら PR |
| Q-201 | Kotlin / ktor 版 mismatch で実ビルド時に問題が出るか | HIGH | T02 で検証 |
| Q-202 | `<phoneme alphabet="x-toshiba-ruby" ph="...">` を Chirp 3 HD で「最も自然に」読ませる記法は何か | MEDIUM | T13 で調査。IPA 変換 / SSML `<sub alias="...">` / plaintext 置換の 3 択を比較 |
| Q-203 | `GuidancePoint.categories` から OneNavi 既存の `ManeuverType` / `ManeuverModifier` への射影テーブル | MEDIUM | T21 までに整理 |
| Q-204 | 経由地の通過タイプ（`RouteWaypoint` の `kind` フィールド相当） | LOW | Phase 1 は単純な 1 経由地対応。詳細は Phase 2 |

18 番の Q-100 / Q-101 / Q-103 / Q-104 / Q-105 は継続課題として残す（Phase 2 以降で対応）。

---

## 9. 次アクション

1. 本計画のレビュー
2. T01 着手: drive-supporter-api 側の `build-logic/settings.gradle.kts` の rootProject.name を `drive-supporter-api-build-logic` にリネームする PR（念のため先行）
3. T02: OneNavi の `settings.gradle.kts` 編集 + `./gradlew assembleDebug --no-configuration-cache` でビルド通過確認
4. T03〜T27 は番号順だが、T09–T11（既存コード削除）と T13–T14（TTS 改修）は独立して並列進行可能
