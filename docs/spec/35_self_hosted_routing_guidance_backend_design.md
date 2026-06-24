# OneNavi 自作Ktorルート・案内データ基盤 詳細設計書

**文書種別:** 実装詳細設計  
**対象:** Androidカーナビ OneNavi / Ktor API / PostgreSQL + PostGIS  
**入力アーカイブ:** `onenavi-here-migration(1).zip`  
**入力SHA-256:** `c4fe972ab24e412958679a61560a677dff956869bdbde2bf0c954d93854dae48`  
**生成日:** 2026-06-23  
**最終改訂日:** 2026-06-24（設計レビュー差分 C1-C7 反映、複数名前付き候補ルートの契約追加、命名中立化）  
**状態:** 実装着手可能。本文中の「要実機検証」「契約確認」は未確定事項として明示的に残す。

> **本リポジトリ収録にあたっての注記:** 本書は外部設計支援で生成した詳細設計を取り込んだもの。public repo 収録のため、外部API 提供元・参照実装アプリ由来の識別子は中立名（`nav-core` / `ExtApi` / `NavCoreClient` 等）へ統一済み。引用されるパッケージパス・クラス名は実装と1対1で一致しない場合がある（中立化のため）。

> 本書はフル実装ではない。設計を一意にするためのinterface、data class、主要シグネチャ、API契約、DDL、SQL骨格、擬似コードだけを含む。関数本体、完成サーバ、全画面改修は対象外とする。

## 0. 読了順序・設計根拠・文書の読み方

### 0.1 入力の読了順序

本設計は指定された順序を依存関係として扱う。後段資料が前段の確定事項を上書きするのではなく、前段の制約を具体化する。

| 順序 | 入力 | 本書での用途 |
|---:|---|---|
| 1 | `README.md` | 全体像、対象範囲、非機能制約の抽出 |
| 2 | `HANDOFF-PLAN.md` | 既定、ドメインモデル対応、gap、要検証、設計判断の正本 |
| 3 | `golden-sample-shakuji-tsukuba/` | 発話block/ann/prefix、SSML、パネル、通過点の受入基準 |
| 4 | `nav-core/` | 再利用interface、共有ドメインモデル、参照backendの意味契約 |
| 5 | `onenavi-consumption/` | Android消費境界、`RouteDetail`、TTS、地図描画、DI差替え点 |
| 6 | `research/` と `naming-policy-AGENTS.md` | 外部制約、ライセンス、名称中立化のゲート |

### 0.2 判断の優先順位

1. 既存OneNaviの消費契約を壊さない。
2. `HANDOFF-PLAN.md` の確定事項と §3 のモデル対応を守る。
3. golden sampleの意味・順序・fallbackを再現する。
4. 外部APIの差異はサーバのanti-corruption layerで吸収する。
5. 情報が取れない場合は、誤案内より欠損・一般表現を選ぶ。

### 0.3 用語

- **route measure:** ルート始点からの累積距離m。すべてのspan、maneuver、沿線地物、発話triggerを同じ一次元軸へ写像する。
- **decision point:** 進路判断を必要とするmaneuver位置。
- **passage point:** 案内判断点ではないが、表示・進捗・文脈に使う通過交差点、IC、JCT、SA、PA等。
- **source revision:** OSM、N06、JARTIC、読み辞書それぞれの取込世代。1回のroute build中は固定する。
- **wire model:** Androidとサーバが共有する既存同型の`Guidance`系モデル。

## 1. 目的、対象範囲、絶対不変条件

### 1.1 目的

OneNaviのルート探索・案内データ取得先を、自作Ktor APIへ置き換える。Ktor側はHEREのroute/traffic/toll/junction情報と、日本のオープンデータ・読み辞書を統合し、Androidがすでに消費している`Guidance`、`RouteGuidance`、`GuidancePoint`、`Intersection`、`SsmlPhrase`、`GuideAnnouncementBlock`、`ManeuverHint`、congestion、speedLimit等を一括生成する。

### 1.2 設計対象

- route plan、reroute、traffic refreshのAPI契約
- HERE接続、PostGIS沿線補完、案内点・通過点・発話・読み・料金・渋滞の組立
- 共有interfaceとwire modelの所有境界
- OSM、国土数値情報N06、JARTIC、読み辞書のDDLと前処理
- Android側のDI・通信・認証stubだけに閉じた最小差分
- golden sampleを基準にしたcontract/replay/実機試験

### 1.3 非対象

- 完成したKtorサーバ全実装
- Android案内UI、TTSプレイヤ、Google Maps描画ロジックの再設計
- 独自地図レンダラ、独自経路探索エンジン
- 外部データの再配布サービス
- 課金・契約条件の法的結論

### 1.4 絶対不変条件

| ID | 不変条件 | 実装への拘束 |
|---|---|---|
| INV-01 | OneNavi消費層を極力無改修 | responseは既存`Guidance`系wire modelを直接返す。アプリ内mapperを再発明しない。 |
| INV-02 | 地図はGoogle Maps SDKを継続 | `RouteDetail.geometry`をWGS84 polylineとして渡し、描画APIを変えない。 |
| INV-03 | 座標はWGS84度 | wire/DB canonical geometryはEPSG:4326。旧datum・旧単位変換は参照backend境界外へ出さない。 |
| INV-04 | 音声生成はサーバ完結 | Androidは受信SSMLをGoogle Cloud TTSへ渡すだけ。読み推測を端末に残さない。 |
| INV-05 | HERE keyはサーバ秘匿 | Android bundle、ログ、responseにkeyを入れない。 |
| INV-06 | route確定時に一括取得 | 案内中はローカル駆動。通信はreroute/traffic refresh/route確定時だけ。 |
| INV-07 | 欠損を捏造しない | toll不明を0円、名称不明を架空名、traffic不明を空いている状態へ変換しない。 |
| INV-08 | source revisionを固定 | 1 route build中のDB参照は同じrevision snapshotを使う。 |
| INV-09 | provider固有型を共有層へ漏らさない | HERE DTOはserver adapter内でcanonical modelへ変換する。 |
| INV-10 | 名称中立 | 共有package、identifier、設計用語は`nav-core`/`ExtApi`/`GuidanceDataSource`等に統一する。 |

### 1.5 golden sampleの機械集計

- announcement block: **860**
- `ann`/`prefix`配下の発話要素: **860**
- `ann`キー出現数: **0**
- `prefix`キー出現数: **0**
- SSML非空行: **304**
- `<sub>`要素: **0**
- `<phoneme>`要素: **74**

この規模と構造から、発話は単一テンプレートではなく、maneuver意味部品、距離段階、先読み、prefix復元、読み付与を分離したdeterministic plannerとして実装する。

## 2. 確定アーキテクチャ

### 2.1 コンポーネント

```text
┌──────────────────────────── OneNavi Android ────────────────────────────┐
│ Route search UI ─ RouteRepository ─ RouteDataSource (既存境界)         │
│   └─ ServerRouteDataSource (ExtNavRouteDataSourceと並ぶ新実装)         │
│        └─ GuidanceDataSource ── ServerGuidanceDataSource (nav-core内)  │
│             └─ GuidanceApiClient                                       │
│                                                                        │
│ RouteDataSource実装 ──> RouteDetail ──> Google Maps SDK               │
│ GuideAnnouncementBlock/SsmlPhrase ──> Google Cloud TTS                 │
│ Local progress/off-route detector ──> reroute request only when needed │
└───────────────────────────────┬────────────────────────────────────────┘
                                │ HTTPS / JSON
┌───────────────────────────────▼────────────────────────────────────────┐
│ Ktor API                                                               │
│  api → application service → canonical route pipeline                  │
│                    ├─ HERE Routing v8 adapter                          │
│                    ├─ HERE Browse adapter                              │
│                    ├─ HERE Junction View adapter                       │
│                    ├─ HERE Toll Cost adapter                           │
│                    ├─ PostGIS corridor repositories                    │
│                    ├─ Guidance assembler                               │
│                    ├─ Speech planner + Reading resolver                │
│                    └─ Route/asset cache                                │
└───────────────────────────────┬────────────────────────────────────────┘
                                │ JDBC/R2DBC boundary
┌───────────────────────────────▼────────────────────────────────────────┐
│ PostgreSQL + PostGIS                                                   │
│ revision | OSM roads/intersections/signals | N06 facilities            │
│ JARTIC links/snapshots | named features | reading lexicon | cache      │
└────────────────────────────────────────────────────────────────────────┘
```

### 2.2 route buildの固定順序

1. request検証と正規化。
2. active dataset revisionを一括取得し`RouteBuildContext`へpin。
3. HERE Routing v8で候補routeを取得。
4. routeをcanonical sections/spans/maneuvers/measureへ正規化。
5. 選択routeについてToll Cost、junction view、Browseを並列取得。
6. PostGISでroute corridor候補を粗検索。
7. route measure、方位、道路階層、接続、順序でmap-match。
8. guidance point、intersection、passage point、speed limit、congestionを組立。
9. semantic guidance planを作り、block/ann/prefixへ展開。
10. ReadingResolverで読みを確定し、安全なSSMLへ変換。
11. wire model validatorとgolden contract invariantを通す。
12. responseをroute cacheへ保存し、`Guidance`を直接返す。

### 2.3 失敗分離

- **route essential:** Routing v8、polyline復号、wire model validation。失敗時は5xx/4xxでrouteを返さない。
- **enrichment optional:** Browse、junction view、PostGIS名称、JARTIC、Toll Cost、読み辞書。個別失敗はdegraded responseで継続する。
- **speech essential-but-degradable:** SSML生成に失敗したphraseだけplain textへ降格。block自体を全消去しない。
- **image optional:** nullで継続。
- **fee unknown:** null/UNKNOWNで継続し、0へ変換しない。

## 3. 設計判断一覧

各判断は「判断 → 根拠 → 固定するもの」の順で記載する。

| ID | 設計判断 | 根拠 | 固定する契約 |
|---|---|---|---|
| D01 | wire modelを`nav-core-model`単一所有にする | Android/サーバの二重定義はfield driftを生む | Kotlin Multiplatformまたは純Kotlin/JVM moduleとしてserializerを共有 |
| D02 | 外部API DTOとwire modelの間にcanonical route modelを置く | API responseの変更をAndroidへ伝播させない | provider adapter以外はHERE JSON型を参照禁止 |
| D03 | 複数data sourceを同じ`GuidanceDataSource`で切替える | fixture、server、replayを消費層無変更で使う | DI qualifier/configだけで選択。UIで分岐しない |
| D04 | route measureを統合キーにする | span、maneuver、地物、音声triggerの座標系を揃える | 始点0m、単調増加、section境界を連続化 |
| D05 | WGS84をcanonicalにし、距離計算だけmetric projectionを使う | Google Maps互換と正確な距離の両立 | DB/wire 4326、候補採点はroute chunkごとのUTMまたはgeography |
| D06 | PostGISは粗選別、application mapperは最終採点を担う | SQLだけではmaneuver文脈・順序制約が複雑 | GiST + ST_DWithin後にKotlinでscore/assignment |
| D07 | source revisionをroute単位でpinする | 更新中の混在で名称やリンクが不整合になる | `RouteBuildContext.datasetRevisions`を全repositoryへ渡す |
| D08 | 発話をsemantic atom→utterance→blockの3段階で生成する | 距離段階、先読み、prefix、読みを独立検証できる | templateはversion付き、同一入力でbyte-stableなJSON/SSML |
| D09 | `prefix`は「前段を聞けなかった場合の文脈復元」として生成する | golden sampleのprefixは単なる接頭辞ではない | prefix単独でも安全な案内文。annとの重複抑制規則を固定 |
| D10 | 固有名詞読みは辞書レイヤ優先、形態素解析は最終fallback | 誤読を減らし、出典・優先度を追跡する | manual exact override→exact→alias→suffix→compound→Sudachi/UniDic→一般表現 |
| D11 | 交差点名欠損は道路名→次の信号→一般turn文へfallback | 全国で名称完全性を保証できない | 信頼度閾値未満の名称を発話しない |
| D12 | off-route確定はAndroid、route再計算はserver | GPS時系列は端末側が最も持つ。serverをstatelessに保てる | serverは現在地・方位・残経由地を受けるだけ |
| D13 | traffic refreshも完全な`Guidance`を返す | delta適用のAndroid改修と整合バグを避ける | stable IDを維持し、既存adapterを再利用 |
| D14 | junction assetはroute確定時prefetch。wireは`GuideImageRef`互換major/minor key、不透明assetIdはserver内部 | key秘匿と既存image gateway無改修を両立 | アプリは既存gateway経由で(major,minor)をfetch・cache。画像なしでも進行 |
| D15 | Toll Cost失敗をroute失敗にしない | 料金情報は補助で、経路・案内を止めるべきでない | `UNKNOWN`/nullとpartial flagを使う |
| D16 | authはno-opまたは固定secretに縮退 | 本要件ではユーザ認証を持たない | HERE keyはserverのみ。`AuthClient/AuthState`は常時利用可能stub |
| D17 | response wrapperを追加しない | 既存decoder/adapterへの波及を避ける | top-level responseは`Guidance`。metadataは既存fieldまたはHTTP header |
| D18 | 互換性はJSON Schema + golden replayで固定する | Kotlin型だけではnull/empty/order/enum wire差を捕捉できない | schema、canonical JSON、SSML canonicalizationをCI gate化 |
| D19 | enrichmentはprovenance/confidenceを内部保持する | 誤補完の原因調査と閾値調整が必要 | wireへ不要なら出さず、trace/metricsとdebug endpointで参照 |
| D20 | mixed-map表示・データ保持は本番移行gateで契約確認する | 技術的可否と利用条件は別問題 | 法務/契約確認TODOを閉じるまで本番release不可 |

## 4. module構成と依存方向

```text
:nav-core-model          // 既存wire model + request DTO + serializer
:nav-core-contract       // GuidanceDataSource、validator、schema fixture
:server-api              // Ktor routes、auth filter、error mapping
:server-application      // RouteApplicationService、transaction orchestration
:server-domain           // canonical route、measure、guidance/speech意味モデル
:server-provider-here    // HERE HTTP DTO/client/adapter（server限定）
:server-geodata          // PostGIS repositories、corridor query
:server-guidance         // point/panel/passages/traffic/speed mapper
:server-speech           // block planner、reading、SSML
:server-persistence      // revision、route cache、asset cache
:server-ops              // config、metrics、health、logging
:android-extnav          // network data source、既存adapterへのbridge
```

依存方向は外側から内側へ一方向とする。

```text
api/android -> contract/model
api -> application -> domain
provider/geodata/speech/persistence -> domain ports
application -> domain ports（実装型へ直接依存しない）
```

禁止する依存:

- `nav-core-model` → Ktor/HERE/PostGIS/Android
- Android feature → HERE DTO
- provider adapter → Android `RouteDetail`
- speech module → Google Cloud TTS client
- repository → Ktor call object

## 5. 共有interfaceとwire model設計

### 5.1 API要求モデル

`Guidance`系responseは既存定義を正とする。新規に共有するのはrequest、error、data source contractだけである。

```kotlin
package jp.onenavi.navcore.contract

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class ViaPoint(
    val point: GeoPoint,
    val passThrough: Boolean = false,
    val staySeconds: Int = 0,
)

@Serializable
data class VehicleProfile(
    val kind: VehicleKind = VehicleKind.PASSENGER_CAR,
    val heightCm: Int? = null,
    val widthCm: Int? = null,
    val lengthCm: Int? = null,
    val grossWeightKg: Int? = null,
    val axleCount: Int? = null,
    val hasElectronicTollCollection: Boolean? = null,
)

enum class VehicleKind { PASSENGER_CAR, LIGHT_VEHICLE, TRUCK, UNKNOWN }
enum class AvoidFeature { TOLL_ROAD, FERRY, TUNNEL, UNPAVED_ROAD }

/**
 * 候補ルートの種別。既存OneNaviは1回の検索で「推奨/渋滞回避/高速優先/一般道優先/距離優先」を
 * ラベル付きで並べて表示する（`RoutePriority`/`CarPriority`に対応）。
 * 単一の最適化軸ではなく、UIに並べる候補種別の集合としてrequestへ渡す（§11.3.1で具体化）。
 */
enum class RoutePreference {
    RECOMMENDED,      // 推奨
    AVOID_CONGESTION, // 渋滞回避
    EXPRESS,          // 高速優先
    FREE,             // 一般道優先（有料回避）
    DISTANCE,         // 距離優先
}

@Serializable
data class RouteOptions(
    /**
     * 返してほしい候補ルート種別。serverは各種別ごとに独立したHERE routing呼び出しを行い、
     * 結果を`RouteGuidance.priority`でラベル付けして`Guidance.routes`へ格納する。
     * 既存の候補選択UI（推奨/高速優先/一般道優先タブ）を無改修で再現するための契約。
     *
     * 順序が意味を持つため`List`とする（先頭が第一候補、重複geometry集約時に残すlabelの優先順）。
     * 重複要素はserver入口validatorで先勝ちにdistinctする。空listは`INVALID_REQUEST`。
     */
    val requestedPriorities: List<RoutePreference> = listOf(RoutePreference.RECOMMENDED),
    val avoid: Set<AvoidFeature> = emptySet(),
    /** 1種別あたりの追加alternative数（HERE alternatives）。0なら各種別1本。 */
    val maxAlternativesPerPriority: Int = 0,
    val language: String = "ja-JP",
)

@Serializable
data class RoutePlanRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val viaPoints: List<ViaPoint> = emptyList(),
    val departureTime: Instant? = null,
    val initialHeadingDegrees: Int? = null,
    val vehicle: VehicleProfile = VehicleProfile(),
    val options: RouteOptions = RouteOptions(),
)

@Serializable
data class RerouteRequest(
    val previousRouteId: String?,
    val currentPosition: GeoPoint,
    val currentHeadingDegrees: Int?,
    val remainingViaPoints: List<ViaPoint>,
    val destination: GeoPoint,
    val departureTime: Instant? = null,
    val vehicle: VehicleProfile = VehicleProfile(),
    val options: RouteOptions = RouteOptions(),
)

@Serializable
data class TrafficRefreshRequest(
    val routeId: String,
    val currentPosition: GeoPoint?,
    val remainingViaPoints: List<ViaPoint>,
    val destination: GeoPoint,
    val vehicle: VehicleProfile = VehicleProfile(),
    val options: RouteOptions = RouteOptions(),
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val traceId: String,
    val details: Map<String, String> = emptyMap(),
)

typealias RoutePlanResponse = Guidance
typealias RerouteResponse = Guidance
typealias TrafficRefreshResponse = Guidance
```

Validationはserver入口で行う。緯度[-90, 90]、経度[-180, 180]、heading[0, 359]、経由地上限、車両寸法範囲、origin-destination同一点を検査する。`GeoPoint`の`init`で例外化せず、API errorへ変換可能なvalidatorに集約する。

### 5.2 同一interfaceによるdata source切替

```kotlin
interface GuidanceDataSource {
    suspend fun route(request: RoutePlanRequest): Guidance
    suspend fun reroute(request: RerouteRequest): Guidance
    suspend fun refreshTraffic(request: TrafficRefreshRequest): Guidance
}

enum class GuidanceDataSourceId {
    SERVER,
    FIXTURE,
    REPLAY,
}

interface GuidanceDataSourceSelector {
    fun select(id: GuidanceDataSourceId): GuidanceDataSource
}

class ServerGuidanceDataSource(
    private val api: GuidanceApiClient,
) : GuidanceDataSource {
    override suspend fun route(request: RoutePlanRequest): Guidance =
        api.route(request)

    override suspend fun reroute(request: RerouteRequest): Guidance =
        api.reroute(request)

    override suspend fun refreshTraffic(request: TrafficRefreshRequest): Guidance =
        api.refreshTraffic(request)
}

interface GuidanceApiClient {
    suspend fun route(request: RoutePlanRequest): Guidance
    suspend fun reroute(request: RerouteRequest): Guidance
    suspend fun refreshTraffic(request: TrafficRefreshRequest): Guidance
    suspend fun fetchJunctionAsset(assetId: String): ByteArray
}
```

既存`nav-core`のinterface名・method名が上記と異なる場合、既存宣言を変更せず`GuidanceDataSourceAdapter`を1枚置く。消費featureが2種類のinterfaceを意識する構造は禁止する。

> **重要（OneNavi側の真の境界）:** OneNaviのアプリ層は`GuidanceDataSource`を直接injectしない。実際の消費境界は既存の **`RouteDataSource`**（`core/datasource`、`searchRoutes(...) : Result<List<RouteResult>>`）であり、`RouteRepository`（`core/repository`）→ `NewRouteManager`（`core/navigation`）の順に消費され、DIは`NavigationModule`の`single<RouteDataSource> { ExtNavRouteDataSource(...) }`で束ねられている。
>
> よって本書の`GuidanceDataSource`/`ServerGuidanceDataSource`/`GuidanceApiClient`は **`RouteDataSource`実装の内側**（＝新backend用の`RouteDataSource`実装が内部で呼ぶnav-core/server向けinterface）に位置づける。OneNaviの差分は、`ExtNavRouteDataSource`と並ぶ新しい`RouteDataSource`実装（例: `ServerRouteDataSource`、内部で`GuidanceApiClient`を呼ぶ）を追加し、`single<RouteDataSource>`の束ね先を差し替える。新実装は`RouteResult`/`RouteDetail`を組むだけでなく、**`ExtNavRouteRegistry`へ`ExtNavRoutePayload`（server由来`RouteGuidance`＋sapaDetails）も登録**する（tracker/voiceがregistry経由でpayloadを消費するため。§18.1注記）。`RouteRepository`・`NewRouteManager`・tracker・voice・feature・UIは型・呼び出し無改修。`GuidanceDataSource`をfeatureへ注入する設計（旧§18.3の記述）は採らない。

### 5.3 provider非依存のserver port

```kotlin
interface RouteEngineGateway {
    suspend fun calculate(request: CanonicalRouteRequest): ProviderRoute
}

interface AlongRouteSearchGateway {
    suspend fun browse(request: AlongRouteBrowseRequest): List<ProviderPlace>
}

interface JunctionViewGateway {
    suspend fun fetch(request: JunctionViewRequest): List<JunctionViewAsset>
}

interface TollCostGateway {
    suspend fun calculate(request: TollCostRequest): TollCostResult
}

interface CorridorFeatureRepository {
    suspend fun findCandidates(
        corridor: RouteCorridor,
        revisions: DatasetRevisionSet,
        kinds: Set<CorridorFeatureKind>,
    ): List<CorridorCandidate>
}

interface TrafficRepository {
    suspend fun findLatest(
        corridor: RouteCorridor,
        observedAfter: Instant,
        revisions: DatasetRevisionSet,
    ): List<TrafficObservation>
}

interface ReadingResolver {
    suspend fun resolve(request: ReadingRequest): ReadingResult
}

interface GuidanceAssembler {
    fun assemble(context: GuidanceBuildContext): Guidance
}

interface GuidanceSpeechPlanner {
    fun plan(context: SpeechPlanContext): List<GuideAnnouncementBlock>
}
```

### 5.4 canonical domain model

```kotlin
@JvmInline
value class RouteMeasureMeters(val value: Double)

data class MeasureRange(
    val start: RouteMeasureMeters,
    val endExclusive: RouteMeasureMeters,
)

data class CanonicalRoute(
    val providerRouteId: String?,
    val sections: List<CanonicalSection>,
    val geometry: List<GeoPoint>,
    val totalLengthMeters: Long,
    val durationSeconds: Long,
    val baseDurationSeconds: Long?,
    val notices: List<RouteNotice>,
)

data class CanonicalSection(
    val index: Int,
    val measure: MeasureRange,
    val geometryStartIndex: Int,
    val geometryEndIndexInclusive: Int,
    val spans: List<CanonicalSpan>,
    val maneuvers: List<CanonicalManeuver>,
)

data class CanonicalSpan(
    val measure: MeasureRange,
    val roadNames: List<LocalizedName>,
    val routeNumbers: List<String>,
    val speedLimitKph: Int?,
    val trafficSpeedKph: Double?,
    val baseSpeedKph: Double?,
    val jamFactor: Double?,
    val functionalClass: Int?,
    val attributes: Set<RoadAttribute>,
)

data class CanonicalManeuver(
    val measure: RouteMeasureMeters,
    val position: GeoPoint,
    val action: ManeuverAction,
    val direction: TurnDirection?,
    val currentRoad: RoadLabel?,
    val nextRoad: RoadLabel?,
    val signpost: Signpost?,
    val laneHints: List<LaneHint>,
    val providerInstruction: String?,
)

data class MatchedAlongRouteFeature(
    val source: DataProvenance,
    val kind: CorridorFeatureKind,
    val sourceObjectId: String,
    val position: GeoPoint,
    val projectedMeasure: RouteMeasureMeters,
    val lateralDistanceMeters: Double,
    val headingDifferenceDegrees: Double?,
    val name: String?,
    val reading: String?,
    val confidence: Double,
)

data class DatasetRevisionSet(
    val osm: Long,
    val n06: Long,
    val jartic: Long?,
    val readingLexicon: Long,
)
```

canonical modelはwire modelを真似ない。外部API解釈と日本向け補完を終えた後、最後のassemblerだけがwire modelを生成する。

### 5.5 ZIPから抽出した互換宣言

以下は入力ソースから宣言部だけを機械抽出し、名称中立化したもの。実装時はこの宣言を正本として、上記の新規contractをadapterで接続する。宣言が複数moduleに重複している場合は`nav-core`側を優先する。

#### `Guidance` — `nav-core/src/main/kotlin/me/matsumo/navcore/guidance/domain/Guidance.kt`

```kotlin
data class Guidance(
    /** 候補ルート (常に 1 件以上)。先頭が `ROUTE1` に対応する第一候補 */
    val routes: ImmutableList<RouteGuidance>,
    /**
     * 応答に乗っていた VICS データソース (mainnet) のビルド時刻。
     *
     * ROUTE バイナリの `EngineCost.params.signal.last_built_unix` を Unix 秒として読み出す。
     * 同フィールドはサーバ内部で `/usr/local/one/data/mainnet/v2/<YYYYMMDDHHMMSS>.mainnet` の
     * ファイル名と一致しており、VICS 5 分間隔の最新スナップショット時刻を表す。
     *
     * `useTrafficInfo=false` のリクエスト、または ROUTE バイナリが取れていない / 旧フォーマット
     * の場合は null。N 社アプリの "VICS の時刻 18:21" 表示と同じ値の想定。
     */
    val vicsTime: Instant? = null,
) {
    /** 第一候補 (ROUTE1) を取り出すヘルパ */
    val primary: RouteGuidance get() = routes.first()
}
```

#### `RouteGuidance` — `nav-core/src/main/kotlin/me/matsumo/navcore/guidance/domain/Guidance.kt`

```kotlin
data class RouteGuidance(
    /** ZIP 内のルートインデックス (1, 2, 3, ...)。`RSROUTE1/ROUTE<index>/` に対応 */
    val index: Int,
    /**
     * このルートが応答した priority。`summary.priority` (rspCode) から逆引きしたもの。
     * サーバ既知の rspCode に逆引きできなかった場合は null。
     */
    val priority: CarPriority?,
    /** ルート全体の概要 (summary.json 由来) */
    val summary: ExtRouteSummary,
    /** 走行順の全ガイドポイント */
    val guidancePoints: ImmutableList<GuidancePoint>,
    /** 走行順の交差点 / IC / JCT */
    val intersections: ImmutableList<Intersection>,
    /** プリロード推奨の画像 ID 一覧 (`submit=fix` にそのまま渡せる) */
    val imageIds: ImmutableList<GuideImageRef>,
    /**
     * 地図描画用 dense polyline (ROUTE バイナリ由来)。
     *
     * `RSROUTE1/ROUTE<index>/ROUTE` の `RouteDetail.polyline` をそのまま展開したもの。
     * shakuji→筑波サンプルで 74.4km に 960 点 (約 77m 間隔) と、描画に十分な密度がある。
     * 外部API の turn-by-turn ZIP に ROUTE が含まれない / decode 失敗した場合は空リスト。
     */
    val polyline: ImmutableList<Coord>,
    /**
     * ルート上の制限速度区間。
     *
     * GUIDE バイナリの速度規制情報を source 距離基準の `[start, end)` 区間に展開したもの。
     * OneNavi など geometry 距離で進捗を持つ呼び出し側は、必要に応じて source 距離から
     * geometry 距離へ変換して利用する。
     */
    val speedLimitSegments: ImmutableList<SpeedLimitSegment> = persistentListOf(),
    /**
     * ルート計算時点でルート上にあった渋滞区間（VICS 由来。`GUIDE` バイナリ埋め込みデータから構築）。
     *
     * `Crowded` / `Jam`、およびレベル不明の `Unknown` の区間のみを含む（`Smooth` は含めない）。
     * 渋滞が無い / `GUIDE` の渋滞構造をデコードできない場合は空リスト。区間は走行順に並ぶ。
     * これはルート計算時点のスナップショットで、走行中の更新は別途（呼び出し側のルート再検索等）。
     */
    val congestionSegments: ImmutableList<RouteCongestionSegment> = persistentListOf(),
    /**
     * ルート polyline 上へ補間済みの地点イベント。
     *
     * 信号機 / 一時停止 / 踏切のようなルート上の地点を [polyline] へ投影したもの。
     * 由来データが座標を持たない場合は [GuidancePoint.distanceFromStartMetres] から補間する。
     * polyline が空、または対象地点が無い場合は空リスト。
     */
    val pointEvents: ImmutableList<RoutePointEvent> = persistentListOf(),
    /**
     * ルート上の規制・事故イベント一覧。
     *
     * GUIDE バイナリの各 [GuidePoint] に埋め込まれた `CongestionAttrEx` から構築する。
     * 渋滞情報（[congestionSegments]）とは別物で、車線規制・工事・事故など「交通障害情報」に相当する。
     * `CongestionAttrEx` を持つ [GuidePoint] が無い、または polyline が空の場合は空リスト。
     * 距離昇順に並ぶ。
     */
    val routeIncidents: ImmutableList<RouteIncident> = persistentListOf(),
)

/** `submit=fix` への入力として使う minor ID のみの一覧を返す */
fun RouteGuidance.allImageMinorIds(): ImmutableList<Int> =
    imageIds.map { it.minor }.distinct().toImmutableList()

/**
 * 全候補ルート横断で必要な画像 minor ID をユニオンして返す。
 * 並走候補のプリロードを 1 回でまとめたい時に使用。
 */
fun Guidance.allImageMinorIds(): ImmutableList<Int> =
    routes.flatMap { it.imageIds }.map { it.minor }.distinct().toImmutableList()
```

> **移行時の意味契約:** 上記コメントは参照backendでの`vicsTime`の由来を記録したものである。新backendにはVICS snapshotが存在しないため、既定は`null`固定とする。`summary.duration`/`baseDuration`を代入してはならない。traffic観測時刻を代入する案は、provider応答に同じ意味のsnapshot timestampが存在すると契約検証できた場合に限る（§11.2、§22.3 TODO-16）。

#### `GuidancePoint` — `nav-core/src/main/kotlin/me/matsumo/navcore/guidance/domain/GuidancePoint.kt`

```kotlin
data class GuidancePoint(
    /** ルート先頭からの連番 */
    val index: Int,
    /** GUIDE 内 `gp_type` (定義未確定だが raw を保持) */
    val gpType: Int,
    /** 前 GP からの距離 (m) */
    val distanceFromPrevMetres: Int,
    /** ルート開始地点からの距離 (m) */
    val distanceFromStartMetres: Int,
    /** この GP に紐付く音声フレーズ ([announcementBlocks] の派生ビュー) */
    val phrases: ImmutableList<SsmlPhrase>,
    /** この GP に紐付く案内ブロック群 (発話片を結合せず保持した正本) */
    val announcementBlocks: ImmutableList<GuideAnnouncementBlock>,
    /** この GP に紐付く画像 ID 群 */
    val imageRefs: ImmutableList<GuideImageRef>,
    /** マニューバ補助情報。該当 GuidePoint を解決できなかったときは null */
    val maneuver: ManeuverHint?,
)
```

#### `Intersection` — `nav-core/src/main/kotlin/me/matsumo/navcore/guidance/domain/Intersection.kt`

```kotlin
data class Intersection(
    /** intersection_id */
    val id: Int,
    /** 地点名 (例: "三原台一丁目") */
    val name: String,
    /** 地点名ルビ (Shift_JIS 半角カナ由来) */
    val nameRuby: String,
    /**
     * 路線番号 (`label.road.number` 由来)。
     *
     * 高速では `"C3"` / `"E1"` のような路線番号、一般道では `"51"` / `"63"` のような
     * 番号が入る。正式名称 (例: `"新目白通り"`) や道路区分の識別はこのフィールド単独では
     * 判定できず、[ExtRouteSummary.streets] (`summary.json` の `Streets[].Name.Official`) や
     * path-code 情報を参照する必要がある。
     */
    val roadName: String,
    /**
     * 正式路線名 (例: `"首都都心環状線"` / `"東名高速道路"`)。
     * `GuidePointLabel.road_name_primary` 由来。
     */
    val roadNameOfficial: String,
    /**
     * 路線記号 (例: `"C1"` / `"E1"`)。
     * `GuidePointLabel.road_number_sign` 由来。高速道路で主に入る。
     */
    val roadNumberSign: String,
    /** 方面看板 A 面テキスト (例: "外環・三郷") */
    val directionSignA: String,
    /** 方面看板 A 面ルビ (Shift_JIS 半角カナ由来) */
    val directionSignAKana: String,
    /** 方面看板 B 面テキスト (例: "大泉学園") */
    val directionSignB: String,
    /** 方面看板 B 面ルビ (Shift_JIS 半角カナ由来) */
    val directionSignBKana: String,
    val position: Coord,
    /**
     * 進入方位 (コンパス基準 0-359°)。`GuidePointAttr.angle_in` 由来。
     *
     * 0 のみ観測の場合はデータ欠落 (データ未提供 GP)。
     */
    val angleIn: Int,
    /**
     * 退出方位 (コンパス基準 0-359°)。`GuidePointAttr.angle_out` 由来。
     */
    val angleOut: Int,
    /**
     * (angleIn, angleOut) から導出した進行方向分類。
     */
    val direction: ManeuverDirection,
    /** この交差点に紐付く画像 ID 群 */
    val imageRefs: ImmutableList<GuideImageRef>,
    /** IC / PA / 料金所など、進路選択とは独立した通過施設ヒント。 */
    val facilityHint: GuidanceFacilityHint?,
)
```

#### `SsmlPhrase` — `nav-core/src/main/kotlin/me/matsumo/navcore/guidance/domain/SsmlPhrase.kt`

```kotlin
data class SsmlPhrase(
    val ssml: String,
    /** 発声タイミング (m)。「交差点手前何 m でこの台詞」を示す */
    val distanceMetres: Int,
    /** カテゴリ */
    val category: GuidanceCategory,
)
```

#### `GuideAnnouncementBlock` — `nav-core/src/main/kotlin/me/matsumo/navcore/guidance/domain/GuideAnnouncementBlock.kt`

```kotlin
data class GuideAnnouncementBlock(
    val id: String,
    val anchor: ExternalGuideAnchor,
    val triggerDistanceMetres: Int,
    val groupId: Int,
    val window: GuideAnnouncementWindow?,
    val pieces: ImmutableList<GuideAnnouncementPiece>,
    val hasBlankAnnouncementSlot: Boolean,
    val categories: ImmutableSet<GuidanceCategory>,
)
```

#### `ManeuverHint` — `nav-core/src/main/kotlin/me/matsumo/navcore/guidance/domain/ManeuverHint.kt`

```kotlin
data class ManeuverHint(
    val angleIn: Int,
    val angleOut: Int,
    val direction: ManeuverDirection,
    val laneInfo: LaneInfo?,
    val specialNode: SpecialNode?,
    val flagsGroup: ImmutableList<FlagsGroupEntry>,
    val mergeSide: MergeSide?,
    val facilityHint: GuidanceFacilityHint?,
)
```

#### `Congestion` — `nav-core/src/main/kotlin/me/matsumo/navcore/traffic/domain/TrafficIncident.kt`

```kotlin
data class Congestion(
        override val roadName: String,
        override val roadRuby: String,
        override val direction: Direction,
        override val from: IncidentPoint,
        override val to: IncidentPoint?,
        override val distanceMetres: Int?,
        override val pathCodes: ImmutableList<PathCode>,
        override val startPathCodes: ImmutableList<PathCode>,
        val level: CongestionLevel,
        /** 通過予想 (分) */
        val transitMinutes: Int?,
    ) : TrafficIncident
```

#### `RouteDetail` — `onenavi-consumption/core-model/RouteItem.kt`

```kotlin
data class RouteDetail(
    val id: String,
    val origin: RoutePoint,
    val destination: RoutePoint,
    val intermediateWaypoints: ImmutableList<RoutePoint>,
    val geometry: ImmutableList<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: ImmutableList<RouteStepInfo>,
    val roadClassSegments: ImmutableList<RoadClassSegment> = persistentListOf(),
    val congestionSegments: ImmutableList<CongestionSegment> = persistentListOf(),
    val pointEvents: ImmutableList<RoutePointEvent> = persistentListOf(),
    val priority: RoutePriority? = null,
    val tollFee: Int? = null,
    val tollDetails: ImmutableList<TollSegmentFee> = persistentListOf(),
    val roadSegments: ImmutableList<RoadSegmentDistance> = persistentListOf(),
    val routeWaypoints: ImmutableList<RouteWaypoint> = persistentListOf(),
    val routeIncidents: ImmutableList<RouteIncidentMarker> = persistentListOf(),
) {
    /**
     * ルート上で最初に高速道路に入る IC / JCT 名。
     * 高速区間が複数ある場合は最初の入口、高速区間が無い / 名前が取れない場合は null。
     */
    val entryInterchangeName: String?
        get() = roadClassSegments
            .firstOrNull { segment -> segment.roadClass == RoadClass.HIGHWAY }
            ?.entryInterchangeName

    /**
     * ルート上で最後に高速道路から出る IC / JCT 名。
     * 高速区間が複数ある場合は最後の出口、高速区間が無い / 名前が取れない場合は null。
     */
    val exitInterchangeName: String?
        get() = roadClassSegments
            .lastOrNull { segment -> segment.roadClass == RoadClass.HIGHWAY }
            ?.exitInterchangeName

    /**
     * 通る道路を走行距離の長い順に並べた名前リスト。
     * 同じ道路名が分割されて [roadSegments] に複数入っていても距離を合算した上で 1 件に集約する。
     */
    val roadNamesByDistance: ImmutableList<String>
        get() = roadSegments
            .groupingBy { segment -> segment.roadName }
            .fold(0L) { accumulated, segment -> accumulated + segment.distanceMeters }
            .entries
            .sortedByDescending { entry -> entry.value }
            .map { entry -> entry.key }
            .toImmutableList()
}
```

#### `ExtNavRouteDataSource` — `onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavRouteDataSource.kt`

```kotlin
class ExtNavRouteDataSource internal constructor(
    private val backend: ExtNavRouteDataSourceBackend,
    private val registry: ExtNavRouteRegistry,
    private val roadTypeGateway: ExtNavRoadTypeGateway? = null,
) : RouteDataSource {

    constructor(
        clientProvider: ExtNavClientProvider,
        authGateway: ExtNavAuthGateway,
        registry: ExtNavRouteRegistry,
        roadTypeGateway: ExtNavRoadTypeGateway? = null,
    ) : this(
        backend = DefaultExtNavRouteDataSourceBackend(
            clientProvider = clientProvider,
            authGateway = authGateway,
        ),
        registry = registry,
        roadTypeGateway = roadTypeGateway,
    )

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
        originDirectionDegrees: Int?,
    ): Result<List<RouteResult>> = runCatching {
        backend.ensureSignedIn().getOrThrow()

        val criteria = RouteSearchCriteria(
            start = Coord.fromDegrees(originLatitude, originLongitude),
            goal = Coord.fromDegrees(destinationLatitude, destinationLongitude),
            waypoints = intermediateWaypoints
                .map { (lat, lng) -> RouteWaypoint(coord = Coord.fromDegrees(lat, lng)) }
                .toImmutableList(),
            priorities = persistentSetOf(
                CarPriority.Recommended,
                CarPriority.AvoidCongestion,
                CarPriority.Express,
                CarPriority.Free,
            ),
            startDirection = originDirectionDegrees ?: RouteSearchCriteria.DIRECTION_UNSPECIFIED,
        )

        val routeGuidances = backend.resolveGuidanceRoutes(criteria).getOrThrow()
        if (routeGuidances.isEmpty()) {
            error("guidance.resolveGuidance returned no routes")
        }

        val originPoint = RoutePoint(originLatitude, originLongitude)
        val destinationPoint = RoutePoint(destinationLatitude, destinationLongitude)
        val intermediates = intermediateWaypoints
            .map { (lat, lng) -> RoutePoint(lat, lng) }
            .toImmutableList()

        routeGuidances.map { routeGuidance ->
            val routeId = routeIdFor(routeGuidance)
            val geometry = buildGeometry(routeGuidance, originPoint, destinationPoint)
            val distanceMetres = routeGuidance.summary.distanceMetres.toDouble()
            val roadClassSegments = refineShortRouteRoadClassSegments(
                geometry = geometry,
                routeDistanceMetres = distanceMetres,
                roadClassSegments = buildRoadClassSegments(routeGuidance, geometry),
            )
            val congestionSegments = buildCongestionSegments(routeGuidance, geometry)
            val pointEvents = ExtNavRoutePointEventMapper.map(routeGuidance, geometry)
            val routeIncidents = ExtNavRouteIncidentMapper.map(routeGuidance, geometry)
            val tollYen = routeGuidance.summary.tollYen.takeIf { it > 0 }
            val timeSeconds = routeGuidance.summary.timeSeconds.toDouble()

            val routePriority = routePriorityFor(routeGuidance.priority)
            val tollDetails = routeGuidance.summary.tollDetails
                .map { detail -> TollSegmentFee(roadName = detail.road, amount = detail.amount) }
                .toImmutableList()
            val roadSegments = buildRoadSegmentDistances(routeGuidance)

            val routeDetail = RouteDetail(
                id = routeId,
                origin = originPoint,
                destination = destinationPoint,
                intermediateWaypoints = intermediates,
                geometry = geometry,
                distanceMeters = distanceMetres,
                durationSeconds = timeSeconds,
                steps = persistentListOf(),
                roadClassSegments = roadClassSegments,
                congestionSegments = congestionSegments,
                pointEvents = pointEvents,
                priority = routePriority,
                tollFee = tollYen,
                tollDetails = tollDetails,
                roadSegments = roadSegments,
                routeIncidents = routeIncidents,
            )

            registry.put(
                ExtNavRoutePayload(
                    id = routeId,
                    routeGuidance = routeGuidance,
                ),
            )

            val item = RouteItem(
                durationSeconds = timeSeconds,
                distanceMeters = distanceMetres,
                geometry = geometry,
                viaRoadNames = persistentListOf(),
                hasTolls = tollYen != null,
                tollFee = tollYen,
                congestionSegments = congestionSegments,
                priorityLabel = routePriority?.label,
            )

            RouteResult(
                item = item,
                detail = routeDetail,
            )
        }
    }

    private suspend fun refineShortRouteRoadClassSegments(
        geometry: ImmutableList<RoutePoint>,
        routeDistanceMetres: Double,
        roadClassSegments: ImmutableList<RoadClassSegment>,
    ): ImmutableList<RoadClassSegment> {
        val gateway = roadTypeGateway ?: return roadClassSegments
        val shouldRefine = ExtNavRoadClassSegmentRefiner.shouldRefineShortRoute(
            routeDistanceMeters = routeDistanceMetres,
            geometry = geometry,
            roadClassSegments = roadClassSegments,
        )
        if (!shouldRefine) return roadClassSegments

        val samplePoints = ExtNavRoadClassSegmentRefiner.samplePoints(geometry)
        val roadClassSamples = samplePoints
            .mapNotNull { point -> gateway.fetchRoadClass(point).getOrNull() }
        if (roadClassSamples.size != samplePoints.size) return roadClassSegments

        return ExtNavRoadClassSegmentRefiner.refineShortRoute(
            routeDistanceMeters = routeDistanceMetres,
            geometry = geometry,
            roadClassSegments = roadClassSegments,
            roadClassSamples = roadClassSamples,
        )
    }

    private fun routeIdFor(routeGuidance: RouteGuidance): String =
        routeGuidance.priority?.name ?: "route-${routeGuidance.index}"

    private fun buildGeometry(
        routeGuidance: RouteGuidance,
        originPoint: RoutePoint,
        destinationPoint: RoutePoint,
    ): ImmutableList<RoutePoint> {
        // ROUTE バイナリ由来の dense polyline を最優先で使う (74.4km に 960 点 ≒ 77m 間隔)。
        // ROUTE 欠落 / decode 失敗時のみ intersection 連結 (≒ 500m 間隔) にフォールバック。
        val dense = routeGuidance.polyline.map { coord ->
            RoutePoint(coord.latDegrees, coord.lonDegrees)
        }
        val raw = dense.ifEmpty {
            routeGuidance.intersections.map { intersection ->
                RoutePoint(intersection.position.latDegrees, intersection.position.lonDegrees)
            }
        }
        if (raw.isEmpty()) {
            return listOf(originPoint, destinationPoint).toImmutableList()
        }
        return buildList {
            if (raw.first() != originPoint) add(originPoint)
            addAll(raw)
            if (raw.last() != destinationPoint) add(destinationPoint)
        }.toImmutableList()
    }

    /**
     * [routeGuidance] の渋滞区間（外部API ライブラリが route バイナリから算出済み）を中立モデルに詰め替える。
     *
     * polyline index はライブラリ側で [RouteGuidance.polyline] に対して計算されている。OneNavi の
     * [geometry] は先頭に出発地を足している場合があるため、その分だけ index をずらしてから [geometry] の
     * 範囲にクランプする。座標マッチや測地系変換は行わない。
     */
    private fun buildCongestionSegments(
        routeGuidance: RouteGuidance,
        geometry: ImmutableList<RoutePoint>,
    ): ImmutableList<CongestionSegment> {
        if (routeGuidance.congestionSegments.isEmpty() || geometry.isEmpty()) {
            return persistentListOf()
        }

        val polyline = routeGuidance.polyline
        val originPrepended = polyline.isNotEmpty() && (geometry.first().latitude != polyline.first().latDegrees || geometry.first().longitude != polyline.first().lonDegrees)
        val indexOffset = if (originPrepended) 1 else 0

        return routeGuidance.congestionSegments
            .map { segment -> segment.toModel(indexOffset, geometry.lastIndex) }
            .toImmutableList()
    }

    private fun RouteCongestionSegment.toModel(
        indexOffset: Int,
        lastGeometryIndex: Int,
    ): CongestionSegment {
        val startIndex = (polylineStartIndex + indexOffset).coerceIn(0, lastGeometryIndex)
        val endIndex = (polylineEndIndex + indexOffset).coerceIn(startIndex, lastGeometryIndex)

        return CongestionSegment(
            startPolylinePointIndex = startIndex,
            endPolylinePointIndex = endIndex,
            severity = level.toSeverity(),
            startDistanceMeters = startDistanceFromRouteStartMetres.toDouble(),
            endDistanceMeters = endDistanceFromRouteStartMetres.toDouble(),
            congestionDistanceMeters = congestionDistanceMetres.toDouble(),
            transitMinutes = transitTimeSeconds?.let { seconds -> (seconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE },
            trend = trend.toModel(),
            isIntermittent = isIntermittent,
            headPointName = headPointName?.ifBlank { null },
            headPointKana = headPointKana?.ifBlank { null },
            headRoadNumbering = headRoadNumbering?.ifBlank { null },
            tailPointName = tailPointName?.ifBlank { null },
// ... declaration truncated for document safety ...
```

#### `AuthClient` — `nav-core/src/main/kotlin/me/matsumo/navcore/auth/AuthClient.kt`

```kotlin
class AuthClient internal constructor(
    private val api: AuthApi,
    private val tokenStore: TokenStore,
    private val cookiesStorage: ClearableCookiesStorage,
    private val dispatchers: ApiDispatchers,
    private val json: Json,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /**
     * 永続化されている認証状態を返す。token 失効時は [AuthState.SignedOut]。
     *
     * 自動で DataStore を clear することはしない。次回ログインで上書き前提。
     */
    suspend fun currentState(): AuthState = withContext(dispatchers.io) {
        val session = tokenStore.load() ?: return@withContext AuthState.SignedOut
        if (session.isExpired(clock())) return@withContext AuthState.SignedOut
        AuthMapper.toState(
            session = session,
            jwtPayload = AuthMapper.decodeJwtOrNull(session.authToken, json),
        )
    }

    /**
     * 認証状態の Flow。[TokenStore.observe] を購読して状態を射影する。
     */
    fun observeState(): Flow<AuthState> = tokenStore.observe().map { session ->
        if (session == null || session.isExpired(clock())) {
            AuthState.SignedOut
        } else {
            AuthMapper.toState(
                session = session,
                jwtPayload = AuthMapper.decodeJwtOrNull(session.authToken, json),
            )
        }
    }

    /**
     * ゲスト認証。軽量 API のレスポンスで AuthPlugin が匿名 session を保存する。
     */
    suspend fun signInAsGuest(): ApiResult<AuthState.Anonymous> = withContext(dispatchers.io) {
        runCatchingApi {
            resetLocalSession()
            api.bootstrapAnonymousSession()
            val session = requireSession()
            AuthState.Anonymous(session)
        }
    }

    /**
     * ExtApi ID + password でサインインする。
     *
     * 匿名 bootstrap → loginpage → login (form) → userstatuscheck のフローを完走させる。
     * 最後の userstatuscheck を呼び忘れると JWT が匿名のまま固定されるため、
     * 必ずまとめて呼び切る。
     *
     * userstatuscheck はサーバー側の session 反映遅延で稀に匿名 session を返す。
     * その場合は短く再取得し、それでも匿名なら login (form) 直後に保存された非匿名
     * session を正として復元する (即時リトライで回復する一過性の事象のため)。
     */
    suspend fun signInWithCredentials(
        loginId: String,
        password: String,
    ): ApiResult<AuthState.SignedIn> = withContext(dispatchers.io) {
        runCatchingApi {
            resetLocalSession()
            api.bootstrapAnonymousSession()
            val page = api.fetchLoginPage()
            api.submitCredentials(loginId, password, page.deviceId)
            // login (form) のレスポンスで AuthPlugin が保存した非匿名 session を控える。
            val signedInSession = requireSession().takeUnless { it.isAnonymous }

            var status = api.fetchUserStatus()
            var session = requireSession()
            var attempt = 0
            while (session.isAnonymous && attempt < USERSTATUS_RETRY_COUNT) {
                delay(USERSTATUS_RETRY_DELAY_MILLIS.milliseconds)
                status = api.fetchUserStatus()
                session = requireSession()
                attempt++
            }

            if (session.isAnonymous) {
                session = signedInSession ?: throw AuthDowngradeException("credentials sign-in downgraded")
            }

            session = AuthMapper.mergeUserStatus(session, status)
            tokenStore.save(session)

            val payload = AuthMapper.decodeJwtOrNull(session.authToken, json)
            val state = AuthMapper.toState(session = session, userStatus = status, jwtPayload = payload)
            state as? AuthState.SignedIn
                ?: throw AuthDowngradeException("signed-in state に落ちない")
        }
    }

    /**
     * [Credentials] の型で分岐するショートカット。
     */
    suspend fun signIn(credentials: Credentials): ApiResult<AuthState> = when (credentials) {
        Credentials.Guest -> signInAsGuest()
        is Credentials.Password -> signInWithCredentials(credentials.loginId, credentials.password)
    }

    /**
     * ログアウト。サーバー側失敗は許容し、TokenStore と Cookie storage を必ず clear する。
     *
     * Cookie を残したままゲストログインし直すと旧セッション Cookie が新セッションへ被さり、
     * サーバー側で 401 / silent fail が発生するため明示的にクリアする。
     */
    suspend fun signOut(): ApiResult<Unit> = withContext(dispatchers.io) {
        runCatchingApi {
            runCatching { api.logout() }
            tokenStore.clear()
            cookiesStorage.clearAll()
        }
    }

    private suspend fun requireSession(): AuthSession =
        tokenStore.load() ?: throw AuthUnauthenticatedException("session が保存されなかった")

    private suspend fun resetLocalSession() {
        tokenStore.clear()
        cookiesStorage.clearAll()
    }

    private companion object {
        /** userstatuscheck が匿名 session を返したときの追加再取得回数 */
        const val USERSTATUS_RETRY_COUNT = 2

        /** userstatuscheck 再取得の間隔 (ミリ秒) */
        const val USERSTATUS_RETRY_DELAY_MILLIS = 250L
    }
}
```

#### `AuthState` — `nav-core/src/main/kotlin/me/matsumo/navcore/auth/domain/AuthState.kt`

```kotlin
sealed interface AuthState {
    /** 未認証 (トークンなし or 失効済) */
    @Immutable
    data object SignedOut : AuthState

    /** ゲスト (匿名) として認証済み */
    @Immutable
    data class Anonymous(val session: AuthSession) : AuthState

    /** ユーザー名 / パスワードで認証済み */
    @Immutable
    data class SignedIn(
        val session: AuthSession,
        /** ExtApi ID (メールアドレスまたは内部 ID) */
        val ExtApiId: String,
        /** コース種別 (normal / premium 等) */
        val courseType: String,
    ) : AuthState
}
```

## 6. Ktor API詳細

### 6.1 endpoint一覧

| Method / Path | Request | Response | 性質 |
|---|---|---|---|
| `POST /api/v1/guidance/routes` | `RoutePlanRequest` | top-level `Guidance` | route新規確定。`Idempotency-Key`対応 |
| `POST /api/v1/guidance/reroutes` | `RerouteRequest` | top-level `Guidance` | off-route確定後に全再生成。**S1〜S3はAndroid未配線**（previousRouteIdを渡す境界が無く、rerouteは`/routes`へ畳む）。`/reroutes`採用はS4で消費層追加が前提（§19.0、TODO-20） |
| `POST /api/v1/guidance/traffic-refreshes` | `TrafficRefreshRequest` | top-level `Guidance` | 同一目的地をtraffic-awareで再評価。deltaではない。**S1〜S3はAndroid未配線**（既存reroute/再searchで代替）。S4で配線する場合は消費層追加が要る（§19.0、TODO-20） |
| `GET /api/v1/guidance/assets/junction/{assetId}` | path | image bytes | route確定直後のprefetch用。ETag/cache制御 |
| `GET /health/live` | なし | status JSON | process生存のみ |
| `GET /health/ready` | なし | status JSON | DB、revision、必須configを検査。外部API障害は詳細に表示 |
| `GET /internal/datasets` | fixed secret | revision JSON | 開発・運用診断。アプリからは呼ばない |

### 6.2 HTTP contract

- `Content-Type: application/json; charset=utf-8`
- `Accept-Language`未指定時は`ja-JP`。
- Android→serverの認証: ローカル/private運用では無認証または`X-OneNavi-Key`固定secret。**ただし固定secretはAPKから抽出可能で実質的な保護にならない**ため、課金リスク（HERE有料API）を負うpublic/共有運用では固定secretを保護手段と見なさない。下記abuse対策を本番release前の完成条件とする（§20.5、TODO-18）。
  - 個人/private専用に留める（信頼できる端末のみへ配布、IP allowlist、VPN/プライベートネットワーク前提）。または
  - server側にper-IP/per-device rate limit、1日あたりroute呼び出し上限、HERE quotaへのcost cap/circuit breaker、異常検知を実装する。アカウント認証や端末attestation（Play Integrity等）はprivate運用なら任意。
- `X-Trace-Id`をrequest/responseで伝播する。
- `Idempotency-Key`はroute/rerouteで受理し、同一body hashに同一responseを返す。
- top-level responseへ新wrapperを追加しない。
- metadataは既存`Guidance`内のfieldを優先し、不足分は`X-Route-Id`、`X-Data-Revisions`、`ETag`に置く。
- unknown JSON fieldはclientで無視、required field欠損はcontract errorとする。

### 6.3 request例

```json
{
  "origin": {"latitude": 35.7438, "longitude": 139.6060},
  "destination": {"latitude": 36.0820, "longitude": 140.1110},
  "viaPoints": [],
  "departureTime": "2026-06-23T08:30:00Z",
  "initialHeadingDegrees": 75,
  "vehicle": {
    "kind": "PASSENGER_CAR",
    "hasElectronicTollCollection": true
  },
  "options": {
    "requestedPriorities": ["RECOMMENDED", "EXPRESS", "FREE"],
    "avoid": [],
    "maxAlternativesPerPriority": 0,
    "language": "ja-JP"
  }
}
```

### 6.4 error contract

| HTTP | code | 条件 | retry |
|---:|---|---|---|
| 400 | `INVALID_REQUEST` | 座標、経由地、車両profile、enum不正 | false |
| 404 | `ROUTE_NOT_FOUND` | 到達可能routeなし | 条件付き |
| 409 | `IDEMPOTENCY_CONFLICT` | 同じkeyでbodyが異なる | false |
| 422 | `UNSUPPORTED_ROUTE_OPTION` | providerで表現できない制約 | false |
| 429 | `RATE_LIMITED` | server保護 | true |
| 502 | `ROUTE_PROVIDER_ERROR` | 必須Routing呼出失敗/不正response | true |
| 503 | `DATASET_NOT_READY` | active revisionなし | true |
| 504 | `ROUTE_BUILD_TIMEOUT` | 全体deadline超過 | true |

optional enrichmentの失敗を上記HTTP errorへ昇格させない。`Guidance`内の既存notice、またはserver log/metricにdegraded reasonを残す。

### 6.5 Ktor route骨格

```kotlin
fun Application.guidanceModule(
    service: RouteApplicationService,
    assets: JunctionAssetService,
) {
    routing {
        route("/api/v1/guidance") {
            post("/routes") {
                val request = call.receive<RoutePlanRequest>()
                call.respond(service.route(request, call.requestContext()))
            }
            post("/reroutes") {
                val request = call.receive<RerouteRequest>()
                call.respond(service.reroute(request, call.requestContext()))
            }
            post("/traffic-refreshes") {
                val request = call.receive<TrafficRefreshRequest>()
                call.respond(service.refreshTraffic(request, call.requestContext()))
            }
            get("/assets/junction/{assetId}") {
                assets.respond(call)
            }
        }
    }
}

interface RouteApplicationService {
    suspend fun route(
        request: RoutePlanRequest,
        context: RequestContext,
    ): Guidance

    suspend fun reroute(
        request: RerouteRequest,
        context: RequestContext,
    ): Guidance

    suspend fun refreshTraffic(
        request: TrafficRefreshRequest,
        context: RequestContext,
    ): Guidance
}
```

`assets.respond`やserviceの中身は本書の対象外。route handlerにprovider呼出、SQL、speech生成を直書きしない。

## 7. HERE連携adapter設計

### 7.1 共通HTTP方針

- HERE API keyはenvironment/secret storeからserver起動時に読み、request URLを含むログではmaskする。
- provider clientごとにconnect/read/overall timeout、retry、circuit breaker、quota metricを分離する。
- 4xxは原則retryしない。429/5xx/network errorだけ指数backoff+jitterで最大1回再試行する。
- response DTOは`server-provider-here`内部限定。OpenAPIまたは保存fixtureでcontract testする。
- provider responseのraw bodyは本番通常ログへ出さない。
- provider termsに従うretentionをconfig化し、route cacheとは別policyにする。

### 7.2 Routing v8

#### request方針

- endpoint family: Routing API v8 routes。
- `transportMode=car`。
- `routingMode`/avoidは要求された各`RoutePreference`から§11.3.1の表で明示変換する。1検索で複数の名前付き候補を返すため、`requestedPriorities`の要素ごとに本呼び出しを1回行う。
- origin/destination/viaは`lat,lon`で送り、WGS84以外を受理しない。
- 現在出発はtraffic-awareになる時刻指定、予約routeはrequestの`departureTime`を使う。
- response要求は少なくともpolyline、summary、actions/instructions、spans。
- span属性はnames、routeNumbers、speedLimit、dynamicSpeedInfo、functionalClass、road/street/car attributesを要求対象とする。
- `maxAlternativesPerPriority > 0`のときだけ種別内でHERE alternativesを要求する。種別をまたいだ同一geometryは§11.3.1の規則で重複排除し、`requestedPriorities`のlist順でlabelを決める。

```kotlin
data class HereRoutingRequestPolicy(
    val transportMode: String = "car",
    val routingMode: String,
    val departureTime: Instant,
    val returnFields: Set<String>,
    val spanFields: Set<String>,
    val language: String = "ja-JP",
)

class HereRouteEngineGateway(
    private val client: HereRoutingClient,
    private val mapper: HereRouteMapper,
) : RouteEngineGateway {
    override suspend fun calculate(
        request: CanonicalRouteRequest,
    ): ProviderRoute
}
```

#### response処理

1. `routes`空、sectionなし、polylineなしをfatalにする。
2. Flexible Polylineをsection単位で復号する。
3. section境界の同一点を1点に畳み、global geometry indexを作る。
4. section summaryのlength/duration/baseDurationを検証する。
5. span/action offsetをglobal route measureへ変換する。
6. unknown action/direction/attributeを`UNKNOWN`へ明示写像し、metricを上げる。
7. noticeのseverityを評価し、法的・物理的制約に関わるnoticeはroute candidateを落とす。
8. provider instruction本文はdebug用に保持するが、最終日本語発話の正本にしない。

### 7.3 Geocoding & Search Browse along-route

Browseはroute沿線のPOI/landmark候補に使い、通過交差点の完全性を依存させない。

- route polylineをAPIのcorridor上限に合わせてchunk化する。
- chunk間を重複させ、境界漏れを防ぐ。
- categoryを案内に有用な施設へ絞る。
- `items[].position`、title、category、access等をcanonical `ProviderPlace`へ変換する。
- 同一provider ID、50m以内の同名正規化、route measure近接でdeduplicateする。
- nameは表示/補助文脈に利用できるが、decision point名として使う場合はPostGIS道路接続との整合を要求する。
- API query構文・上限は採用時点の公式OpenAPI versionへpinし、URL文字列をdomain層へ埋め込まない。

```kotlin
data class AlongRouteBrowseRequest(
    val encodedCorridor: String,
    val radiusMeters: Int,
    val categories: Set<String>,
    val language: String,
    val limit: Int,
)
```

### 7.4 Junction View

- 対象は高速道路の出口、分岐、JCT、複雑なlane decisionを優先する。
- canonical maneuver、provider section/action key、進行方向をrequest keyにする。
- route確定処理中に取得し、server asset cacheへ保存する。
- **app-facing keyは既存`GuideImageRef`/`GuideImageKey(major, minor)`互換のsurrogate keyを維持する**（現行UI＝`MapViewModel`が`ExtNavGuideImageGateway`具象と`GuideImageKey(major, minor)`に依存しているため）。serverが`major`/`minor`を採番し、内部で`minor`→junction asset（不透明assetId/外部URL/key）へmapする。HEREの外部URL/keyはwireへ出さない。
- 画像取得は既存の image gateway 経由のまま。`ExtNavGuideImageGateway`の**型・signatureは変えず**、内部fetch先を新serverの`GET /assets/junction/{assetId}`（または`major/minor`受けのserver endpoint）へ向け替える。これによりMapViewModel/loader/UIは無改修を保つ。
  - 代替案（より綺麗だが小さな消費層変更を伴う）: 中立interface `NavGuideImageGateway`を切り出し、MapViewModelをそれに依存させる。採るなら「最小差分」ではなく明示的な消費層変更として扱う（§18.2、TODO-19）。
- `/assets/junction/{assetId}`はserver内部・gateway実装が使う詳細であり、wire model（`imageRefs`/`imageIds`）に格納するのは不透明URIではなく`major/minor`互換keyとする。
- Androidは案内開始前にprefetchする（既存image cache境界をそのまま使う）。
- assetなしは`null`。代替の架空画像を生成しない。
- 画像寸法、format、利用可能期間、再配布/キャッシュ条件は要契約確認。

### 7.5 Toll Cost

- route candidate確定後に1回だけ呼ぶ。
- provider route handleを第一選択とし、未提供時はAPIが許容するroute definitionへ変換する。
- `VehicleProfile`をHERE vehicle/toll profileへ明示変換する。
- 通貨、合計、料金所・区間明細、割引/支払方式、partial/coverageをinternal modelへ正規化する。
- 日本の車種区分、ETC、時間帯割引、スマートIC可否は実機・契約環境で検証する。
- request失敗時はrouteを返し、料金だけUNKNOWNにする。

```kotlin
sealed interface TollCostResult {
    data class Available(
        val currency: String,
        val total: DecimalAmount,
        val components: List<TollComponent>,
        val partial: Boolean,
    ) : TollCostResult

    data class Unavailable(
        val reason: TollUnavailableReason,
    ) : TollCostResult
}
```

## 8. route正規化とroute measure

### 8.1 geometry正規化

- input/outputは`latitude`、`longitude`のdouble度。
- section polylineは復号精度を落とさない。
- 隣接点がgeodesic 0.05m未満なら重複とみなす。
- 連続点が異常跳躍したrouteはprovider errorにする。
- `RouteDetail.geometry`用の座標列は全sectionを順序通り連結する。
- map描画の簡略化はAndroid既存実装に任せ、server response原本を勝手に簡略化しない。

### 8.2 measure index

```kotlin
interface RouteMeasureIndex {
    val totalLengthMeters: Double

    fun pointAt(measure: RouteMeasureMeters): GeoPoint
    fun measureAt(polylineOffset: Int): RouteMeasureMeters
    fun project(point: GeoPoint): RouteProjection
    fun headingAt(measure: RouteMeasureMeters, windowMeters: Double = 20.0): Double?
}

data class RouteProjection(
    val measure: RouteMeasureMeters,
    val projectedPoint: GeoPoint,
    val lateralDistanceMeters: Double,
    val segmentIndex: Int,
)
```

- 累積長はWGS84 geodesicで計算する。
- 投影・角度計算はroute chunkの中心経度から選んだUTM zoneへ一時変換する。
- UTM zone跨ぎまたは長距離routeは50km以下のchunkへ分ける。
- measureはdoubleで保持し、wireの整数距離へ変換する地点だけ丸める。
- section/action/spanのoffsetがpoint indexの場合はglobal point index→measure tableで変換する。

### 8.3 ID体系（3種を責務分離）

1つのIDを「同一ルート判定」「DB cache PK」「app registry key」に兼用すると破綻する。`guidance_json`はrequest/出発時刻/車両/options/dataset revision/trafficで変わるため、geometry-only IDをcache PKにすると別内容が衝突する。よって**3種を明確に分ける**。

| ID | 算出 | 何を表すか | 使う場所 |
|---|---|---|---|
| `routeCandidateId` | `sha256(modelVersion + routeGeometryFingerprint)` | **ルート形状の同一性**（traffic/出発時刻が違っても同じ形なら同じ） | reroute/refreshの「同一ルート？」判定、要素stable IDの土台、dedup |
| `routePackageId` | `sha256(routeCandidateId + requestHash)`（requestHash=§17.3: origin/dest/via/出発bucket/vehicle/options/datasetRevisions/trafficBucket） | **生成された案内パッケージ1個の同一性**（形＋生成文脈） | `RouteDetail.id`、app registry key、DB cache PK、atomic swap単位 |
| `element-stable-id` | `base32(sha256(routeCandidateId + kind + round(measure*10) + occurrence))[0..19]` | パッケージ内の案内要素 | guidance point / intersection / block.id 等 |

#### 8.3.1 routeCandidateId（形状の同一性）

- `routeGeometryFingerprint`: 正規化済みpolyline（WGS84、重複端点除去、座標量子化）から算出する**唯一の形状キー**。どのpriorityで生成されても、provider alternatives順がどうでも、形が同じなら同一。
- priority/label・provider alternatives順（alternativeIndex）は含めない。真に異なるalternativesは異なるgeometryで自然に分かれる。量子化衝突時のみ full-precision polyline sha256 等の**geometry由来の決定的な値**でtie-break（連番index等の不安定値は使わない）。
- **既存`ExtNavRouteDataSource.routeIdFor`（`priority?.name`）は廃止**。priorityベースIDは同一priority alternativesで衝突し、refreshでも揺れる。

#### 8.3.2 routePackageId（cache/registry/RouteDetail.id）

- `RouteDetail.id` = `routePackageId`。app registryのkeyもこれ。同一形でも出発時刻/traffic/options違いは別パッケージ＝別IDになり、cacheやregistryが内容を取り違えない。
- DB `route_cache`のPKは`routePackageId`（§15.2）。`requestHash`は§17.3でこのIDへ畳む。
- `routeCandidateId`はwireにも別fieldとして載せ、Android/サーバが「形が同じか」をpackage跨ぎで比較できるようにする（refresh時のatomic swap判定・stable element照合に使う）。

#### 8.3.3 要素 stable ID

- `routeCandidateId`基準で算出するため、traffic refreshで形が同じなら要素IDも維持される。geometryが変わった区間だけ新ID、未変更prefixはfingerprint照合で再利用してよい。
- `RouteDetail.id`の算出を「routeId + route measure...」と循環説明しない（candidate→package→element の順で確定する）。

## 9. 沿線スナップ・map-match詳細

### 9.1 役割分担

- PostGIS: active revision絞込、corridor bbox/GiST、`ST_DWithin`による候補削減、距離/投影の初期値。
- Kotlin: source別閾値、route heading、maneuver近接、道路接続、候補順序、重複排除、one-to-one割当。
- wire assembler: confidence閾値を超えた候補だけをfieldへ反映。

### 9.2 corridor半径の初期値

| 地物 | 都市部 | 高速道路/地方部 | 備考 |
|---|---:|---:|---|
| named intersection | 35m | 55m | 交差道路接続を必須評価 |
| traffic signal | 25m | 40m | maneuver前後の進行方向を評価 |
| IC/JCT/ramp/gate | 80m | 120m | 本線とrampの道路階層を評価 |
| SA/PA | 150m | 200m | 本線側・反対車線側を方向で除外 |
| JARTIC link | 40m | 80m | link directionとroute headingを必須評価 |
| POI/landmark | 100m | 200m | 発話利用は別の信頼度閾値 |

値はconfig化するが、無制限拡大は禁止。候補ゼロなら名称なしfallbackへ進む。

### 9.3 score

```text
score =
    0.35 * distanceScore
  + 0.20 * headingScore
  + 0.15 * maneuverProximityScore
  + 0.10 * roadClassScore
  + 0.10 * topologyScore
  + 0.05 * nameQualityScore
  + 0.05 * sourcePriorityScore
  - duplicatePenalty
  - wrongCarriagewayPenalty
```

- `distanceScore = exp(-lateralDistance / sigma(kind))`
- heading差は0〜180度へ正規化。90度超はJARTIC/SA・PAで原則棄却。
- named intersectionは交差道路がrouteの前後headingと整合することを確認。
- candidate measureが直前の確定候補より後退する割当は禁止。
- 同じsource objectを複数maneuverへ割り当てない。
- confidence 0.75以上を発話可、0.60以上を表示可、未満は不採用の初期値とする。

### 9.4 擬似コード

```text
function enrichAlongRoute(route, maneuvers, revisions):
    chunks = splitRoute(route, maxLength=50km, overlap=250m)
    rawCandidates = []

    for chunk in chunks:
        rawCandidates += repository.findCandidates(
            corridor=buffer(chunk, radiusByKind),
            revisions=revisions,
            kinds={INTERSECTION, SIGNAL, IC, JCT, SA, PA, TRAFFIC_LINK}
        )

    candidates = deduplicateBySourceObject(rawCandidates)

    for candidate in candidates:
        metricRoute = metricProjectionFor(candidate, route)
        projection = metricRoute.project(candidate.position)
        candidate.measure = projection.measure
        candidate.distance = projection.lateralDistance
        candidate.routeHeading = route.headingAt(candidate.measure)
        candidate.headingDiff = angularDifference(
            candidate.direction, candidate.routeHeading
        )
        candidate.baseScore = scoreCandidate(candidate)

    decisionAssignments = minCostMonotonicAssignment(
        maneuvers=sortByMeasure(maneuvers),
        candidates=filterDecisionCandidates(candidates),
        cost=1-score,
        constraints={oneToOne, monotonicMeasure, maxMeasureDeltaByKind}
    )

    passages = selectPassagePoints(
        candidates - decisionAssignments.used,
        minSpacingByKind,
        directionAndRoadClassRules
    )

    return Enrichment(
        decisionFeatures=acceptAboveThreshold(decisionAssignments),
        passageFeatures=acceptAboveThreshold(passages)
    )
```

### 9.5 SQL骨格

```sql
WITH route AS (
    SELECT
        ST_SetSRID(ST_GeomFromGeoJSON(:route_geojson), 4326) AS geom,
        :metric_srid::integer AS metric_srid
), candidates AS (
    SELECT
        i.intersection_id,
        i.name,
        i.is_signal,
        i.geom,
        ST_Distance(i.geom::geography, r.geom::geography) AS lateral_m
    FROM geo.osm_intersection i
    CROSS JOIN route r
    WHERE i.revision_id = :osm_revision
      AND ST_DWithin(i.geom::geography, r.geom::geography, :radius_m)
    ORDER BY i.geom <-> r.geom
    LIMIT :candidate_limit
)
SELECT
    c.*,
    ST_LineLocatePoint(
        ST_Transform(r.geom, r.metric_srid),
        ST_Transform(c.geom, r.metric_srid)
    ) AS route_fraction
FROM candidates c
CROSS JOIN route r;
```

GiST KNNは粗順位であり、`lateral_m`とapplication scoreで最終決定する。

## 10. guidance point・intersection・passage生成

### 10.1 decision point生成

1. HERE actionを必ず1つのcanonical maneuverへ正規化。
2. action offsetをroute measureへ変換。
3. ±距離窓内の交差点/IC/JCT候補を取得。
4. road name、signpost、接続道路、signalを統合。
5. shared enumへaction/direction/typeを写像。
6. panel、maneuver hint、announcement plannerへ同じsemantic objectを渡す。

別々のmapperで表示名と発話名を決めない。`DecisionPointSemantic`を正本とする。

```kotlin
data class DecisionPointSemantic(
    val id: String,
    val measure: RouteMeasureMeters,
    val position: GeoPoint,
    val maneuver: CanonicalManeuver,
    val displayLabel: String?,
    val speechLabel: PronounceableLabel?,
    val intersection: MatchedAlongRouteFeature?,
    val signalOrdinal: Int?,
    val nextRoad: RoadLabel?,
    val confidence: Double,
)
```

### 10.2 D11 fallback規則

発話labelは次の順で選ぶ。

1. confidence閾値以上のnamed intersection/IC/JCT。
2. HEREまたはOSMで高信頼の次道路名・通り名。
3. maneuverまでの信号個数が一意に数えられる場合の「次の信号」「二つ目の信号」。
4. 距離付き一般turn文「300メートル先、左方向です」。
5. 直前一般文「まもなく、左方向です」。

禁止:

- 低信頼候補の名前を「おそらく」付きで発話する。
- 信号データが欠損しているのに「次の信号」と断言する。
- 表示labelと発話labelで異なる交差点を選ぶ。

### 10.3 passage point

- IC/JCT/SA/PAはN06母集合を軸にOSM/Wikidata/読み辞書を統合する。
- named intersectionを全件passage化しない。最低間隔、道路階層、案内価値で間引く。
- 同一施設の入口/出口nodeは1つのlogical facilityへgroup化する。
- 反対車線側施設はheading/topologyで除外する。
- `RouteGuidance`上の順序はroute measureの昇順で固定する。

## 11. モデル→データソース実装マッピング

### 11.1 共通算出規則

- HERE JSON pathはprovider adapterだけが知る。
- PostGIS地物はsource revision、source object ID、距離、route measure、scoreを伴う。
- mapperは全fieldを明示代入し、reflectionやfield名推測を本実装で使わない。
- null、empty list、UNKNOWN、0は意味を分ける。golden sampleのwire形をcontract testで固定する。
- distance/durationは単位を名前に含むinternal modelへ一度正規化してからwireへ変換する。

### 11.2 主要semantic mapping

| wire/semantic field | 主ソース | 算出規則 | 補完・不成立時 |
|---|---|---|---|
| route geometry | Routing v8 `sections[].polyline` | 復号・section連結・WGS84度 | fatal。推測線を作らない |
| total distance | `sections[].summary.length` | section合計、polyline geodesicと乖離検査 | summary欠損時のみgeodesic再計算 |
| duration | `summary.duration` | traffic-aware値 | `baseDuration`、それもなければunknown/fatal判定 |
| base duration | `summary.baseDuration` | section合計 | null許容 |
| `Guidance.vicsTime` | 新backendでは対応するVICS sourceなし | 既定は`null`固定。`duration`/`baseDuration`から生成しない | HERE traffic応答に同義のsnapshot観測時刻があると契約検証できた場合だけ採用。無ければUIの時刻表示を撤去 |
| maneuver position | `actions[].offset` + polyline | global point index→measure→WGS84 point | section境界補正。範囲外はfatal |
| maneuver action/direction | `actions[]` | exhaustive enum mapping | UNKNOWN + metric。発話は一般表現 |
| road name | span/action names | ja優先、official name→short name→route number | OSM/N06。低信頼は無名 |
| route number | span routeNumbers/sign | 表示順序を保持し重複除去 | OSM `ref` |
| intersection name | OSM named node/way relation | corridor snap + topology + maneuver proximity | road name→signal ordinal→一般文 |
| signal | OSM highway=traffic_signals等 | route側node、方向、maneuver前の個数 | 不明ならsignal表現を使わない |
| IC/JCT | N06 + OSM | N06母集合、OSM topologyで位置精緻化 | Wikidata/Wikipediaは名称・読み補助 |
| SA/PA | N06 + OSM | 本線方向・アクセスを検査 | 反対車線候補を除外、なければ出さない |
| landmark | HERE Browse | corridor chunk検索、route measureへ投影 | Wikidata/Geolonia等のnamed feature |
| speed limit | span speedLimit | km/hへ統一、measure range化 | OSM maxspeed、なければUNKNOWN |
| congestion | dynamicSpeedInfo | jam factor/速度比を共有enumへ | 欠損区間だけJARTIC、最終UNKNOWN |
| toll total | Toll Cost | vehicle profile、currency、partialを保持 | null/UNKNOWN。0円にしない |
| toll segment | Toll Cost components | route measureまたは料金所facilityへ対応 | 対応不能明細はtotalだけ採用 |
| junction image | Junction View | decision point keyで取得・server proxy | null。画像なしpanel |
| panel title | DecisionPointSemantic | intersection/next road/signpostから決定 | maneuver一般label |
| maneuver hint | action/lane/signpost | actionとlaneを共有modelへ写像 | laneなしでもturn hintは保持 |
| announcement blocks | Speech planner | semantic maneuver列から距離段階生成 | 最小のsoon/immediate blockを必須化 |
| SSML phrase | ReadingResolver | surface+readingをescapeして`<sub alias>`等へ | plain text fallback |
| prefix | Speech planner | 直前block未再生でも文脈が成立する補助句 | 不要ならempty。annコピーは禁止 |

### 11.3 ZIP内の実field単位マッピング

> **位置付け:** 本節はfieldの網羅性確認用一覧であり、算出規則の正本ではない。実ソース・算出規則は、§11.2（主要semantic mapping）、§9（沿線スナップ）、§14（発話・読み）、§15（DDL）、付録A・B（signature・擬似コード）を正とする。本節とそれらが矛盾する場合は、§11.2／§9／§14／§15／付録A・Bを優先する。`HANDOFF-PLAN §3対応表 + 正規化コンテキスト`とだけ記載された行は未具体化のinventoryであり、実装時にfield名から推測してはならない。

| field / type | primary source | calculation | fallback / quality |
|---|---|---|---|

| `Guidance.routes` : `ImmutableList<RouteGuidance>` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `Guidance.vicsTime` : `Instant?` | 新backendでは対応sourceなし（廃止互換field） | 既定は`null`固定。`summary.duration`/`baseDuration`は`durationSeconds`系にだけ使い、`vicsTime`へ代入しない。 | HERE traffic応答のsnapshot観測時刻が契約上確認できた場合のみ、その時刻を採用できる。確認できなければ`null`を維持し、UIの時刻表示を撤去する。 |

| `RouteGuidance.index` : `Int` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `RouteGuidance.priority` : `CarPriority?` | この候補を生成した`RoutePreference`（`requestedPriorities`の要素） | §11.3.1の固定表で逆引きする。HERE応答のroute属性や候補順位から意味を推測しない。 | 未定義のpreferenceは`null` + metric。既知値を別priorityへ黙って丸めない。 |

| `RouteGuidance.summary` : `ExtRouteSummary` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `RouteGuidance.guidancePoints` : `ImmutableList<GuidancePoint>` | サーバ生成の安定ID | route candidate ID（§8.3.1） + route measure(mm丸め) + kind + occurrence をSHA-256短縮（§8.3.2）。同一候補の再生成で安定。 | 外部プロバイダの一時IDをwire契約へ露出しない。 |

| `RouteGuidance.intersections` : `ImmutableList<Intersection>` | OSM交差点・信号 + N06/JARTIC補完 | route corridor候補を距離・方位・接続道路・順序で採点し、maneuver measure近傍へ1件だけ割り当てる。 | 名称なしは道路名、次の信号、一般turn文の順にfallback。 |

| `RouteGuidance.imageIds` : `ImmutableList<GuideImageRef>` | HERE junction viewアダプタ | 高速道路系decision pointに限定して取得し、route確定時にasset cacheへ取り込む。共有モデルには既存`GuideImageRef`互換のmajor/minor surrogate keyを格納（不透明assetIdはserver内部）。 | 取得不可はnull/空。案内処理は画像なしで継続。 |

| `RouteGuidance.polyline` : `ImmutableList<Coord>` | HERE Routing v8 `routes[].sections[].polyline` | Flexible Polylineを復号し、section順に連結。重複端点を除去しWGS84度の座標列へ正規化する。 | 復号不能はルート全体を失敗扱い。描画用に簡略化しない原本を保持する。 |

| `GuidancePoint.index` : `Int` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `GuidancePoint.gpType` : `Int` | 正規化ルールテーブル | HERE enum、OSM/N06/JARTIC分類を共有enumへ明示的に写像。未認識値はUNKNOWNとして監視する。 | 推測変換をしない。 |

| `GuidancePoint.distanceFromPrevMetres` : `Int` | HERE section/route summary `length`、またはroute measure差 | 総距離はsection length合計。案内点間距離は累積measure差を整数mへ丸める。 | 欠損時はWGS84 polylineのgeodesic長を再計算。 |

| `GuidancePoint.distanceFromStartMetres` : `Int` | HERE section/route summary `length`、またはroute measure差 | 総距離はsection length合計。案内点間距離は累積measure差を整数mへ丸める。 | 欠損時はWGS84 polylineのgeodesic長を再計算。 |

| `GuidancePoint.phrases` : `ImmutableList<SsmlPhrase>` | サーバ内 GuidanceSpeechPlanner + ReadingResolver | maneuver意味部品から距離段階blockを生成し、固有名詞を辞書解決してSSML化。ann/prefix順序をgolden sample規則で確定する。 | 読み未確定は安全な表記読みまたは一般語へfallback。壊れたSSMLはplain textへ降格。 |

| `GuidancePoint.announcementBlocks` : `ImmutableList<GuideAnnouncementBlock>` | サーバ内 GuidanceSpeechPlanner + ReadingResolver | maneuver意味部品から距離段階blockを生成し、固有名詞を辞書解決してSSML化。ann/prefix順序をgolden sample規則で確定する。 | 読み未確定は安全な表記読みまたは一般語へfallback。壊れたSSMLはplain textへ降格。 |

| `GuidancePoint.imageRefs` : `ImmutableList<GuideImageRef>` | HERE junction viewアダプタ | 高速道路系decision pointに限定して取得し、route確定時にasset cacheへ取り込む。共有モデルには既存`GuideImageRef`互換のmajor/minor surrogate keyを格納（不透明assetIdはserver内部）。 | 取得不可はnull/空。案内処理は画像なしで継続。 |

| `GuidancePoint.maneuver` : `ManeuverHint?` | HERE `sections[].actions[]` / `turnByTurnActions[]` | action、direction、severity、offset、nextRoad/sign情報を正規化し共有enumへ全列挙写像する。 | 未知enumはUNKNOWN/STRAIGHTへ黙って丸めず、noticeとメトリクスを残す。 |

| `Intersection.id` : `Int` | サーバ生成の安定ID | route candidate ID（§8.3.1） + route measure(mm丸め) + kind + occurrence をSHA-256短縮（§8.3.2）。同一候補の再生成で安定。 | 外部プロバイダの一時IDをwire契約へ露出しない。 |

| `Intersection.name` : `String` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `Intersection.nameRuby` : `String` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `Intersection.roadName` : `String` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `Intersection.roadNameOfficial` : `String` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `Intersection.roadNumberSign` : `String` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `Intersection.directionSignA` : `String` | HERE signpost/toward（高速を優先） + N06/OSM沿線名称（一般道補完） | decision pointに結び付くsign destinationをprovider順で正規化し、第1候補をAへ格納する。一般道補完は§9の割当閾値を満たす名称だけを使う。 | 候補なし・低信頼は空文字。架空の方面名を生成しない。 |

| `Intersection.directionSignAKana` : `String` | `directionSignA`採用文字列 + ReadingResolver | A面文字列が採用された場合だけ、manual/exact辞書を優先して読みを解決する。 | 読み未確定・低信頼は空文字。形態素推定の誤読を表示・発話へ出さない。 |

| `Intersection.directionSignB` : `String` | HERE signpost/toward（高速を優先） + N06/OSM沿線名称（一般道補完） | decision pointに結び付くsign destinationの第2候補をBへ格納する。Aとの重複を正規化後に除去する。 | 第2候補なし・低信頼は空文字。架空の方面名を生成しない。 |

| `Intersection.directionSignBKana` : `String` | `directionSignB`採用文字列 + ReadingResolver | B面文字列が採用された場合だけ、manual/exact辞書を優先して読みを解決する。 | 読み未確定・低信頼は空文字。形態素推定の誤読を表示・発話へ出さない。 |

| `Intersection.position` : `Coord` | HERE action/span offset + route polyline | offsetをroute measureへ変換し、polyline上を補間してWGS84点を確定する。補完地物はPostGIS投影点を使う。 | 候補が閾値外ならmaneuver位置のみを採用し、補完名は付けない。 |

| `Intersection.angleIn` : `Int` | route polyline + decision pointのroute measure | maneuver手前の`measure - window`からdecision pointへ向かうbearingを算出する。windowは同一点を避けて10-30mで自動調整し、0-359度へ正規化する。 | canonical modelでは`Int?`の未提供として保持する。wire互換上だけ既存慣習の`0` sentinelへ変換し、欠損metricを残す。 |

| `Intersection.angleOut` : `Int` | route polyline + decision pointのroute measure | decision pointから`measure + window`へ向かうbearingを算出する。windowは同一点を避けて10-30mで自動調整し、0-359度へ正規化する。 | canonical modelでは`Int?`の未提供として保持する。wire互換上だけ既存慣習の`0` sentinelへ変換し、欠損metricを残す。 |

| `Intersection.direction` : `ManeuverDirection` | HERE `sections[].actions[]` / `turnByTurnActions[]` | action、direction、severity、offset、nextRoad/sign情報を正規化し共有enumへ全列挙写像する。 | 未知enumはUNKNOWN/STRAIGHTへ黙って丸めず、noticeとメトリクスを残す。 |

| `Intersection.imageRefs` : `ImmutableList<GuideImageRef>` | HERE junction viewアダプタ | 高速道路系decision pointに限定して取得し、route確定時にasset cacheへ取り込む。共有モデルには既存`GuideImageRef`互換のmajor/minor surrogate keyを格納（不透明assetIdはserver内部）。 | 取得不可はnull/空。案内処理は画像なしで継続。 |

| `Intersection.facilityHint` : `GuidanceFacilityHint?` | HERE `sections[].actions[]` / `turnByTurnActions[]` | action、direction、severity、offset、nextRoad/sign情報を正規化し共有enumへ全列挙写像する。 | 未知enumはUNKNOWN/STRAIGHTへ黙って丸めず、noticeとメトリクスを残す。 |

| `SsmlPhrase.ssml` : `String` | サーバ内 GuidanceSpeechPlanner + ReadingResolver | maneuver意味部品から距離段階blockを生成し、固有名詞を辞書解決してSSML化。ann/prefix順序をgolden sample規則で確定する。 | 読み未確定は安全な表記読みまたは一般語へfallback。壊れたSSMLはplain textへ降格。 |

| `SsmlPhrase.distanceMetres` : `Int` | HERE section/route summary `length`、またはroute measure差 | 総距離はsection length合計。案内点間距離は累積measure差を整数mへ丸める。 | 欠損時はWGS84 polylineのgeodesic長を再計算。 |

| `SsmlPhrase.category` : `GuidanceCategory` | 正規化ルールテーブル | HERE enum、OSM/N06/JARTIC分類を共有enumへ明示的に写像。未認識値はUNKNOWNとして監視する。 | 推測変換をしない。 |

| `GuideAnnouncementBlock.id` : `String` | サーバ生成の安定ID | route candidate ID（§8.3.1） + route measure(mm丸め) + kind + occurrence をSHA-256短縮（§8.3.2）。同一候補の再生成で安定。 | 外部プロバイダの一時IDをwire契約へ露出しない。 |

| `GuideAnnouncementBlock.anchor` : `ExternalGuideAnchor` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `GuideAnnouncementBlock.triggerDistanceMetres` : `Int` | HERE section/route summary `length`、またはroute measure差 | 総距離はsection length合計。案内点間距離は累積measure差を整数mへ丸める。 | 欠損時はWGS84 polylineのgeodesic長を再計算。 |

| `GuideAnnouncementBlock.groupId` : `Int` | サーバ生成の**意味的束ねキー**（hashではない） | 同一案内点内で互いに代替の距離段候補を束ねる正整数（予告/直前等のグループごとに別値）。`0`は「束ねなし＝単独候補」。音声選抜は「同一groupIdを1回だけ鳴らして消費」する既存仕様（`VoiceAnnouncementPlanBuilder`）に合わせる。block.id（要素stable id）とは**別契約**で、混同してhash化しない。 | グループが無い案内・遠方予告など互いに束ねない発話は`0`。距離override由来の段複製は`groupId`を共有させない（各段で鳴らすため）。 |

| `GuideAnnouncementBlock.window` : `GuideAnnouncementWindow?` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `GuideAnnouncementBlock.pieces` : `ImmutableList<GuideAnnouncementPiece>` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `GuideAnnouncementBlock.hasBlankAnnouncementSlot` : `Boolean` | サーバ内 GuidanceSpeechPlanner + ReadingResolver | maneuver意味部品から距離段階blockを生成し、固有名詞を辞書解決してSSML化。ann/prefix順序をgolden sample規則で確定する。 | 読み未確定は安全な表記読みまたは一般語へfallback。壊れたSSMLはplain textへ降格。 |

| `GuideAnnouncementBlock.categories` : `ImmutableSet<GuidanceCategory>` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `ManeuverHint.angleIn` : `Int` | `Intersection.angleIn`と同じroute-heading算出結果 | 同一decision pointのcanonical進入bearingを再利用し、別計算によるずれを禁止する。 | canonical未提供時のみwireで`0` sentinel。 |

| `ManeuverHint.angleOut` : `Int` | `Intersection.angleOut`と同じroute-heading算出結果 | 同一decision pointのcanonical退出bearingを再利用し、別計算によるずれを禁止する。 | canonical未提供時のみwireで`0` sentinel。 |

| `ManeuverHint.direction` : `ManeuverDirection` | HERE `sections[].actions[]` / `turnByTurnActions[]` | action、direction、severity、offset、nextRoad/sign情報を正規化し共有enumへ全列挙写像する。 | 未知enumはUNKNOWN/STRAIGHTへ黙って丸めず、noticeとメトリクスを残す。 |

| `ManeuverHint.laneInfo` : `LaneInfo?` | HERE `sections[].actions[]` / `turnByTurnActions[]` | action、direction、severity、offset、nextRoad/sign情報を正規化し共有enumへ全列挙写像する。 | 未知enumはUNKNOWN/STRAIGHTへ黙って丸めず、noticeとメトリクスを残す。 |

| `ManeuverHint.specialNode` : `SpecialNode?` | HERE action種別 + matched N06/OSM facility（料金所・フェリー・橋・トンネル等） | provider actionを内部`CanonicalSpecialNodeKind`へ全列挙写像し、既存wireの`SpecialNode(kind,id,flag)`へ変換できる既知mappingだけを出力する。外部の一時IDは使わず安定IDを付与する。 | 未知kind・wire mapping未確定は`null` + unknown metric。適当なkind/idを捏造しない。 |

| `ManeuverHint.flagsGroup` : `ImmutableList<FlagsGroupEntry>` | HERE lane assistance + maneuver action | laneを左から右の順に保持し、推奨lane・側方lane・許可方向をversion付きの全列挙表で`FlagsGroupEntry`へ変換する。未知lane directionを直進へ丸めない。 | lane情報なしは空。未知codeは該当方向だけ省略しmetricを残す。 |

| `ManeuverHint.mergeSide` : `MergeSide?` | HERE merge action + lane assistance | 合流方向が明示された場合だけ`LEFT`/`RIGHT`へ全列挙写像する。actionとlaneが矛盾した場合は採用せずmetric化する。 | 不明・非合流は`null`。`STRAIGHT`等へ黙って丸めない。 |

| `ManeuverHint.facilityHint` : `GuidanceFacilityHint?` | HERE `sections[].actions[]` / `turnByTurnActions[]` | action、direction、severity、offset、nextRoad/sign情報を正規化し共有enumへ全列挙写像する。 | 未知enumはUNKNOWN/STRAIGHTへ黙って丸めず、noticeとメトリクスを残す。 |

| `Congestion.roadName` : `String` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `Congestion.roadRuby` : `String` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `Congestion.direction` : `Direction` | HERE `sections[].actions[]` / `turnByTurnActions[]` | action、direction、severity、offset、nextRoad/sign情報を正規化し共有enumへ全列挙写像する。 | 未知enumはUNKNOWN/STRAIGHTへ黙って丸めず、noticeとメトリクスを残す。 |

| `Congestion.from` : `IncidentPoint` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `Congestion.to` : `IncidentPoint?` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `Congestion.distanceMetres` : `Int?` | HERE section/route summary `length`、またはroute measure差 | 総距離はsection length合計。案内点間距離は累積measure差を整数mへ丸める。 | 欠損時はWGS84 polylineのgeodesic長を再計算。 |

| `Congestion.pathCodes` : `ImmutableList<PathCode>` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `Congestion.startPathCodes` : `ImmutableList<PathCode>` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `Congestion.level` : `CongestionLevel` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `Congestion.transitMinutes` : `Int?` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `RouteDetail.id` : `String` | `routePackageId`（§8.3.2） | `sha256(routeCandidateId + requestHash)`。同一形でも出発時刻/traffic/options違いは別ID。app registry key・DB cache PKと同一値。 | 形の同一性比較は別fieldの`routeCandidateId`で行う。外部プロバイダの一時IDをwireへ露出しない。 |

| `RouteDetail.origin` : `RoutePoint` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `RouteDetail.destination` : `RoutePoint` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `RouteDetail.intermediateWaypoints` : `ImmutableList<RoutePoint>` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `RouteDetail.geometry` : `ImmutableList<RoutePoint>` | HERE Routing v8 `routes[].sections[].polyline` | Flexible Polylineを復号し、section順に連結。重複端点を除去しWGS84度の座標列へ正規化する。 | 復号不能はルート全体を失敗扱い。描画用に簡略化しない原本を保持する。 |

| `RouteDetail.distanceMeters` : `Double` | HERE section/route summary `length`、またはroute measure差 | 総距離はsection length合計。案内点間距離は累積measure差を整数mへ丸める。 | 欠損時はWGS84 polylineのgeodesic長を再計算。 |

| `RouteDetail.durationSeconds` : `Double` | HERE summary `duration` / `baseDuration` | traffic-aware durationを案内所要時間に採用し、baseDurationを比較用に保持する。 | 動的値欠損時はbaseDuration。未知を0で埋めない。 |

| `RouteDetail.steps` : `ImmutableList<RouteStepInfo>` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `RouteDetail.roadClassSegments` : `ImmutableList<RoadClassSegment>` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `RouteDetail.congestionSegments` : `ImmutableList<CongestionSegment>` | HERE spans `dynamicSpeedInfo`、JARTIC沿線リンク補完 | spanをroute measure区間へ写像し、jam factor・traffic/base speed比から共有enumへ変換。鮮度と方向一致を検査する。 | HEREを優先。欠損区間のみJARTIC。双方なしはUNKNOWN。 |

| `RouteDetail.pointEvents` : `ImmutableList<RoutePointEvent>` | HANDOFF-PLAN §3対応表 + 正規化コンテキスト | field名だけで推測せず、モデル契約テストに固定したmapperで明示代入する。 | 欠損可否を型とgolden sampleで確定し、null/empty/UNKNOWNを使い分ける。 |

| `RouteDetail.priority` : `RoutePriority?` | この候補を生成した`RoutePreference`（`requestedPriorities`の要素） | §11.3.1の固定表で逆引きし、`RouteGuidance.priority`と同じ意味を維持する。HERE応答から推測しない。 | 未定義preferenceは`null`。 |

| `RouteDetail.tollFee` : `Int?` | HERE Toll Costの正規化応答 | 選択済みroute handle/geometryとVehicleProfileで照会し、通貨・合計・料金所区間を共有モデルへ写像する。 | 失敗・対象外はUNKNOWN/null。0円扱いにしない。 |

| `RouteDetail.tollDetails` : `ImmutableList<TollSegmentFee>` | HERE Toll Cost components | componentを料金所facilityまたはroute measure rangeへ対応付け、区間名・金額・currencyを検証して走行順に写像する。 | 明細の対応付け不能時はprovider totalだけを`RouteDetail.tollFee`へ採用し、明細は空。Toll Cost失敗時は空 + `tollFee=null/UNKNOWN`とし、0円にしない（§13.2）。 |

| `RouteDetail.roadSegments` : `ImmutableList<RoadSegmentDistance>` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

| `RouteDetail.routeWaypoints` : `ImmutableList<RouteWaypoint>` | request `viaPoints` + route projection | 入力経由地をrequest順のままroute polylineへ投影し、route measureとWGS84座標を確定する。providerが返した名称や沿線地物で経由地を増やさない。 | 経由地なしは空。投影不能は当該requestをroute build失敗または明示degraded扱いとし、別地点へ置換しない。 |

| `RouteDetail.routeIncidents` : `ImmutableList<RouteIncidentMarker>` | HERE spans/actionsのnames・routeNumbers + PostGIS沿線名称 | 言語jaを優先し、ref・正式名・通称を優先度付きで統合。Unicode NFKCと接尾辞正規化後に読み辞書へ渡す。 | 名称信頼度が閾値未満なら発話には使用せず、表示のみまたは一般表現。 |

#### 11.3.1 priority逆引き表（要求起点）＝複数名前付き候補ルートの生成契約

既存OneNaviは1回の検索で複数の**名前付き候補ルート**（推奨/渋滞回避/高速優先/一般道優先/距離優先）を取得し、候補選択UIへタブで並べる。HEREの`alternatives`は意味ラベルを持たないため、これを**1つのpreferenceで表現することはできない**。よってrequestは`RouteOptions.requestedPriorities: List<RoutePreference>`で「並べたい候補種別を優先順で」受け、serverは各種別を**独立したHERE routing呼び出し**へ写像して結果を`RouteGuidance.priority`でラベル付けする。順序は第一候補と重複集約のlabel決定に使うため`Set`ではなく`List`とする。

`CarPriority`/`RoutePriority`は「serverがHEREへ何を要求したか」を表す互換表示値であり、HERE応答の候補順位・道路比率・所要時間から推測しない。mapperは次の`when`をexhaustiveに実装し、新しい`RoutePreference`追加時はcompile/testを失敗させる。

| `RoutePreference`（要求） | HERE呼び出しパラメータ | `RouteGuidance.priority` | `RouteDetail.priority` |
|---|---|---|---|
| `RECOMMENDED` | `routingMode=fast`（traffic-aware）, 追加avoidなし | `CarPriority.Recommended` | `RoutePriority.Recommended` |
| `AVOID_CONGESTION` | `routingMode=fast`（traffic-aware, 出発時刻now） | `CarPriority.AvoidCongestion` | `RoutePriority.AvoidCongestion` |
| `EXPRESS` | `routingMode=fast`, toll/高速をavoidしない | `CarPriority.Express` | `RoutePriority.Express` |
| `FREE` | `routingMode=fast`, `avoid[tollRoad]`（＋必要なら`controlledAccessHighway`） | `CarPriority.Free` | `RoutePriority.Free` |
| `DISTANCE` | `routingMode=short` | `CarPriority.Distance` | `RoutePriority.Distance` |
| 未定義値 | — | `null` + metric | `null` |

候補生成規則:

- `requestedPriorities`の各要素ごとに1回HEREを呼ぶ（structured concurrencyで並列、§17.2へ追加）。各呼び出しは§7.2のRouting方針に従う。
- 得た候補を`RouteGuidance.priority`でラベル付けし、`Guidance.routes`へ格納する。並び順は`requestedPriorities`のlist順に一致させ、`routes.first()`はlist先頭（慣習上`RECOMMENDED`を先頭に置く）とする。
- **同一geometryの候補は重複排除**する（例: `EXPRESS`と`RECOMMENDED`が同一経路になる場合、片方のlabelを残し1本に集約）。残すlabelは`requestedPriorities`のlist順で先に出現した方に固定する（順序が安定するため結果が一意）。重複集約で候補数がUI期待より減ることはlogへ残す（§20.2、silent dropにしない）。
- `RouteOptions.avoid`（ユーザ明示制約）は全種別に共通適用し、上表の種別由来avoidと**和集合**にする。種別由来avoidがpriority labelを書き換えることはない。
- `maxAlternativesPerPriority > 0`の場合のみ、種別内でHERE alternativesを追加要求し、同一labelで複数本を返す。

**HERE能力の限界（要検証 → §22.3 TODO-17）**: HEREには「高速優先」「渋滞回避」の明示モードが無い。`EXPRESS`/`AVOID_CONGESTION`はtraffic-aware fastとavoid調整での近似であり、既存の候補分化（明確に異なる経路が出るか）を実機で確認するまで`DATA-VALIDATION (OPEN)`とする。分化が不十分な場合は、avoidパラメータ・出発時刻・area avoidance等での誘導、または将来の自前後処理を検討する。

### 11.4 HANDOFF-PLAN §3とのtraceability

以下は仕様核の対応節を名称中立化して収録したもの。§11.2、§9、§14、§15、付録A・Bが算出規則の正本であり、§11.3はfield網羅性のinventoryとしてそれらへtraceする。

#### HANDOFF-PLAN §3 ドメインモデル → データソース対応（実装の核）

| モデル/フィールド(既存 nav-core) | 主ソース | 補完 | 備考 |
|---|---|---|---|
| Route/RouteSummary(距離/時間/料金/区間) | HERE Routing v8 | — | tollはHERE toll/料金は別途日本料金計算が要る場合あり |
| RouteGuidance.polyline | HERE(flexible-polyline) | — | WGS84度へdecode |
| RouteGuidance.speedLimitSegments | HERE spans=speedLimit | — | m/s→km/h |
| RouteGuidance.congestionSegments | HERE traffic(dynamicSpeedInfo/incidents) | — | レベル正規化 |
| GuidancePoint(maneuver点) | HERE actions | — | depart/turn/keep/roundabout/arrive |
| GuidancePoint(通過点・全交差点) | OSM交差点ノード+N06 IC/JCT を沿線スナップ | — | 比丘尼型の通過点。曲がらない点も列挙 |
| Intersection.name/nameRuby | OSM name/name:ja-Hira + 読み辞書 | 代替表現(D11) | 一般道は穴あり許容 |
| Intersection.directionSign(方面) | HERE actions signpost/toward(要検証) + N06/OSM | — | 高速◎/一般道△ |
| ManeuverHint.laneInfo | HERE ManeuverViewLaneAssistance(構造化) | — | 「左から2番目」はサーバ生成 |
| GuideAnnouncementBlock/Piece/SsmlPhrase | サーバ生成(ExtApi block/ann/prefix写経)+読み辞書 | — | D10。距離多段(700/300/まもなく/直前)・prefix fallback |
| imageRefs(案内画像) | HERE junction view | 画像なしfallback(D7) | — |
| 信号案内(category) | OSM/JARTIC信号 沿線スナップ+カウント | — | DF6 |
| 規制案内(一方通行/右左折/時間帯/車種/寸法) | HERE(routing内部)+JARTIC規制 沿線スナップ | — | 表示/発話用はJARTIC |
| SA/PA(SapaDetail) | HERE /browse + NEXCO/OSM設備 | — | DF4 |
| traffic(TrafficClient相当) | HERE Traffic | — | — |
| Auth(AuthState/Session) | スタブ | — | D3 |

## 12. traffic・speed limit設計

### 12.1 HERE spanの区間化

- span offsetをglobal route measureへ変換する。
- 次span開始またはsection終了をend measureとする。
- 同じ値で隣接する区間をmergeする。
- section境界を跨ぐmergeは道路属性・方向が同じ場合だけ許可する。
- speedは内部で`km/h`のdouble、wireで要求される型へ最後に丸める。

### 12.2 congestion分類

閾値はconfig version付きで保持する。初期分類はjam factorがあればそれを優先し、なければ`trafficSpeed/baseSpeed`比を使う。

```text
UNKNOWN: 動的値なし、鮮度超過、方向不一致
FREE:    jamFactor < 2 または speedRatio >= 0.80
SLOW:    2 <= jamFactor < 5 または 0.50 <= speedRatio < 0.80
JAM:     5 <= jamFactor < 8 または 0.25 <= speedRatio < 0.50
BLOCKED: jamFactor >= 8 または明示的通行止め
```

共有enumが異なる場合はこの分類から明示変換する。閾値境界はreplayで調整可能だが、routeごとに変えない。

### 12.3 JARTIC補完

- HERE動的spanが存在する区間を上書きしない。
- JARTIC observationは最新時刻、方向、link conflation confidenceを検査する。
- route linkとのoverlap長とheading差で採用する。
- timestampが`maxAge`を超えたらUNKNOWN。
- JARTIC link IDとOSM/HERE segment IDをwireへ露出しない。

### 12.4 traffic refresh

1. Androidが既存routeId、現在地、残経由地、目的地を送る。
2. serverは同一路線への固定更新を保証せず、traffic-awareでrouteを再計算する。
3. geometryが同一ならstable IDを維持。
4. geometryが変われば完全な`Guidance`を返し、Androidがroute packageを原子的に置換。
5. 音声queueは現在measureより後方のblockだけ再構成する。

## 13. 料金設計

### 13.1 internal model

```kotlin
data class TollSummary(
    val availability: TollAvailability,
    val currency: String?,
    val total: DecimalAmount?,
    val components: List<TollComponent>,
    val partial: Boolean,
    val calculatedAt: Instant?,
)

data class TollComponent(
    val name: String?,
    val amount: DecimalAmount?,
    val entrance: MatchedAlongRouteFeature?,
    val exit: MatchedAlongRouteFeature?,
    val measure: MeasureRange?,
)

enum class TollAvailability { AVAILABLE, NOT_APPLICABLE, UNKNOWN }
```

### 13.2 規則

- `NOT_APPLICABLE`はproviderが有料区間なしと明示した場合だけ。
- timeout、coverage不明、vehicle mapping不成立は`UNKNOWN`。
- 金額はdecimalで保持し、浮動小数で加算しない。
- currencyを欠いた金額はwireへ出さない。
- component合計とtotalが不一致でも勝手に補正せず、provider totalを採用しmetricを残す。
- wireの`tollDetails`へ出すのは料金所facilityまたはroute measureへ一意に対応付けられたcomponentだけとする。totalは得られても明細対応が不成立なら、`tollFee`だけを採用し`tollDetails`は空にする。
- 料金発話を行う既存契約がある場合、読みは数字・通貨専用formatterで生成する。

## 14. 発話block・ann・prefix・SSML設計

### 14.1 3段階モデル

```kotlin
data class GuidanceSpeechAtom(
    val kind: SpeechAtomKind,
    val surface: String,
    val pronounceable: PronounceableLabel?,
)

data class PlannedUtterance(
    val maneuverId: String,
    val trigger: GuidanceTrigger,
    val atoms: List<GuidanceSpeechAtom>,
    val prefixAtoms: List<GuidanceSpeechAtom>,
    val priority: Int,
)

data class GuidanceTrigger(
    val routeMeasure: RouteMeasureMeters,
    val remainingDistanceMeters: Int,
    val earliestSecondsBefore: Int?,
    val latestSecondsBefore: Int?,
)
```

1. **semantic atom:** 距離、対象名、操作、次操作、注意句を言語非依存の意味として持つ。
2. **planned utterance:** 速度・道路種別・次maneuver間隔から距離段階と合成を決める。
3. **wire block:** golden sampleのblock/ann/prefix構造へ変換し、phraseごとにSSMLを付ける。

**block.id と groupId は別契約（重要）:**

- `block.id`（String）= §8.3.3の要素stable id。block 1個を一意に識別する。
- `groupId`（Int）= 音声選抜の**束ねキー**。同一案内点で互いに代替の距離段（例: 予告グループ / 直前グループ）を同一`groupId`にまとめ、再生側は同一groupを1回だけ鳴らして消費する。`0`は単独候補（束ねない）。
- planner実装上: 「同一decision pointの予告系」「直前系」のように**鳴らし分けの束ね単位ごとに非ゼロの安定groupId**を採番し、互いに束ねない発話（遠方予告、距離override由来の段複製）は`0`または別groupにする。`block.id`のhashを`groupId`へ流用してはならない（同一グループが分裂し、重複発話・距離段選抜崩れを起こす）。

### 14.2 距離段階

一般道の基準は500m、300m、まもなく、直前。高速道路または高速度区間では1km/2km等の先行段階を追加できるが、共有モデルの既存distance typeへ明示変換する。

生成条件:

- maneuver間隔が短い場合、500m/300m blockを統合または省略し、誤った順序で連呼しない。
- 直前blockはdecision pointを通過する前に必ず1件残す。
- GPS更新遅延でtriggerを跨いだ場合、`prefix + ann`が単独で意味を保つ。
- U-turn、分岐、合流、高速出口、料金所は専用semantic templateを持つ。
- 距離の丸めは発話tierごとの規則で固定し、残距離値をそのまま読み上げない。

### 14.3 prefix規則

- prefixは過去案内への参照を避ける。「その先」だけで開始しない。
- annと同じ固有名詞を必ず重複させるのではなく、聞き逃し時に必要な情報だけ補う。
- 先読み複合案内では、次maneuverの関係をprefixへ保持する。
- blockが通常順に再生された場合はprefixを省略できるよう、再生側の既存選択規則を維持する。
- prefix単独のSSMLもwell-formedであることをvalidatorで検査する。

### 14.4 ReadingResolverの優先順位

```text
1. manual exact entity override
   誤読修正をversion管理し、surface + entity type + 必要な地域/feature keyの完全一致だけに適用
2. exact entity dictionary
   address/postal → station → IC/JCT/SA/PA → Wikidata/JMnedict/Geolonia
3. normalized alias
   NFKC、全半角、空白、ハイフン、旧字体/異体字alias
4. suffix rule
   IC→インター、JCT→ジャンクション、SA→サービスエリア、PA→パーキングエリア
5. compound segmentation
   道路・橋・交差点・駅・市区町村等の既知suffixを分離
6. Sudachi / UniDic fallback
7. 安全な一般表現
```

辞書entryにはsurface、normalized key、reading、entity type、source、priority、revision、license metadataを持たせる。同順位衝突はentity type、沿線位置、行政区域、source priorityで解決し、ランダム選択しない。

- 高速系のIC/JCT/SA/PA/料金所は、manual/exact/alias/suffixで確定した読みを優先し、形態素解析だけの推定を固有名詞発話へ無条件採用しない。
- 一般道交差点名は読み信頼度が受入閾値未満なら名称自体を発話せず、D11の道路名・信号・一般turn文へ落とす。誤読より読み無しを選ぶ。
- manual overrideはfuzzy matchに使わず、変更理由・レビュー者・適用開始revision・回帰fixtureを必須にする。

### 14.5 SSML生成

```kotlin
interface SsmlRenderer {
    fun render(phrases: List<ResolvedPhrase>): SsmlPhrase
}

data class ResolvedPhrase(
    val surface: String,
    val reading: String?,
    val kind: PhraseKind,
)
```

- surface/readingをXML escapeしてからtagへ入れる。
- golden sampleで使われるtag構造をserializer contractとして固定する。
- 読み置換はGoogle Cloud TTSで安定する`<sub alias="...">surface</sub>`を基本とし、sampleで別形式が正ならsampleに従う。
- raw provider text、DB text、user textをSSMLとして直接連結しない。
- `<speak>`のnested、未閉鎖tag、空alias、制御文字をvalidatorで拒否。
- phrase単位の失敗はescaped plain textへfallback。
- SSMLと表示文字列は別fieldとして保持し、UIへtagを露出しない。

### 14.6 planner擬似コード

```text
function planSpeech(decisionPoints, routeContext):
    plans = []
    for current, next in slidingWindow(decisionPoints, 2):
        tiers = selectDistanceTiers(
            roadClass=current.roadClass,
            speedLimit=current.speedLimit,
            distanceToNext=distance(current, next)
        )

        for tier in tiers:
            primary = buildPrimaryAtoms(current, tier)
            preview = buildPreviewAtoms(current, next, tier)
            prefix = buildRecoveryPrefix(current, next, tier)

            utterance = compactAndDeduplicate(primary + preview)
            if isUseful(utterance):
                plans += PlannedUtterance(
                    trigger=triggerFor(current.measure, tier),
                    atoms=utterance,
                    prefixAtoms=prefix,
                    priority=priorityOf(current, tier)
                )

    plans = resolveTriggerCollisions(plans)
    plans = ensureSoonAndImmediateForEveryDecision(plans)
    return toGuideAnnouncementBlocks(plans, readingResolver, ssmlRenderer)
```

## 15. PostGIS logical/physical schema

### 15.1 方針

- raw/stagingとactive serving tableを分離する。
- 全serving tableは`revision_id`を持ち、in-place上書きしない。
- geometryはEPSG:4326。距離検索には`geom::geography` expression indexを用意する。
- 名前検索にはnormalized name + pg_trgm。
- JARTIC observationは時系列partition + BRIN。
- proprietary response cacheはgeodata tableと分離し、保持期間を短く設定可能にする。

### 15.2 DDL

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE SCHEMA IF NOT EXISTS dataset;
CREATE SCHEMA IF NOT EXISTS staging;
CREATE SCHEMA IF NOT EXISTS geo;
CREATE SCHEMA IF NOT EXISTS lexicon;
CREATE SCHEMA IF NOT EXISTS nav;

CREATE TYPE dataset.source_kind AS ENUM (
    'OSM', 'N06', 'JARTIC', 'POSTAL', 'STATION',
    'WIKIDATA', 'WIKIPEDIA', 'JMNEDICT', 'GEOLONIA',
    'SUDACHI', 'UNIDIC', 'MANUAL'
);

CREATE TYPE dataset.revision_status AS ENUM (
    'STAGED', 'VALIDATED', 'ACTIVE', 'RETIRED', 'FAILED'
);

CREATE TABLE dataset.revision (
    revision_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_kind          dataset.source_kind NOT NULL,
    revision_key         text NOT NULL,
    source_published_at  timestamptz,
    imported_at          timestamptz NOT NULL DEFAULT now(),
    validated_at         timestamptz,
    activated_at         timestamptz,
    status               dataset.revision_status NOT NULL,
    checksum_sha256      char(64),
    source_uri_redacted  text,
    license_code         text,
    metadata             jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (source_kind, revision_key)
);

CREATE UNIQUE INDEX uq_revision_one_active_per_source
    ON dataset.revision (source_kind)
    WHERE status = 'ACTIVE';

CREATE VIEW dataset.active_revision AS
SELECT *
FROM dataset.revision
WHERE status = 'ACTIVE';

CREATE TYPE geo.road_class AS ENUM (
    'MOTORWAY', 'TRUNK', 'PRIMARY', 'SECONDARY',
    'TERTIARY', 'RESIDENTIAL', 'SERVICE', 'RAMP', 'OTHER'
);

CREATE TABLE geo.osm_road_segment (
    road_segment_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id          bigint NOT NULL REFERENCES dataset.revision(revision_id),
    source_object_id     text NOT NULL,
    road_class           geo.road_class NOT NULL,
    name                 text,
    name_normalized      text,
    route_ref            text,
    one_way              smallint NOT NULL DEFAULT 0,
    max_speed_kph        integer,
    layer                 integer,
    bridge                boolean NOT NULL DEFAULT false,
    tunnel                boolean NOT NULL DEFAULT false,
    tags                  jsonb NOT NULL DEFAULT '{}'::jsonb,
    geom                  geometry(MultiLineString, 4326) NOT NULL,
    UNIQUE (revision_id, source_object_id),
    CHECK (ST_SRID(geom) = 4326),
    CHECK (max_speed_kph IS NULL OR max_speed_kph BETWEEN 1 AND 250),
    CHECK (one_way IN (-1, 0, 1))
);

CREATE INDEX ix_osm_road_geom
    ON geo.osm_road_segment USING gist (geom);
CREATE INDEX ix_osm_road_geog
    ON geo.osm_road_segment USING gist ((geom::geography));
CREATE INDEX ix_osm_road_revision_class
    ON geo.osm_road_segment (revision_id, road_class);
CREATE INDEX ix_osm_road_name_trgm
    ON geo.osm_road_segment USING gin (name_normalized gin_trgm_ops);

CREATE TABLE geo.osm_intersection (
    intersection_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id          bigint NOT NULL REFERENCES dataset.revision(revision_id),
    source_object_id     text NOT NULL,
    name                 text,
    name_normalized      text,
    is_signal            boolean NOT NULL DEFAULT false,
    connected_road_count integer NOT NULL DEFAULT 0,
    connected_road_refs  text[] NOT NULL DEFAULT '{}',
    tags                  jsonb NOT NULL DEFAULT '{}'::jsonb,
    geom                  geometry(Point, 4326) NOT NULL,
    UNIQUE (revision_id, source_object_id),
    CHECK (ST_SRID(geom) = 4326)
);

CREATE INDEX ix_osm_intersection_geom
    ON geo.osm_intersection USING gist (geom);
CREATE INDEX ix_osm_intersection_geog
    ON geo.osm_intersection USING gist ((geom::geography));
CREATE INDEX ix_osm_intersection_revision_signal
    ON geo.osm_intersection (revision_id, is_signal);
CREATE INDEX ix_osm_intersection_name_trgm
    ON geo.osm_intersection USING gin (name_normalized gin_trgm_ops);

CREATE TYPE geo.facility_kind AS ENUM (
    'IC', 'JCT', 'SA', 'PA', 'RAMP', 'TOLL_GATE',
    'BRIDGE', 'TUNNEL', 'OTHER'
);

CREATE TABLE geo.n06_road_facility (
    facility_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id          bigint NOT NULL REFERENCES dataset.revision(revision_id),
    source_object_id     text NOT NULL,
    facility_kind        geo.facility_kind NOT NULL,
    official_name        text,
    name_normalized      text,
    route_name           text,
    route_code           text,
    direction_hint       text,
    attributes            jsonb NOT NULL DEFAULT '{}'::jsonb,
    geom                  geometry(Geometry, 4326) NOT NULL,
    UNIQUE (revision_id, source_object_id),
    CHECK (ST_SRID(geom) = 4326),
    CHECK (GeometryType(geom) IN ('POINT', 'MULTIPOINT', 'LINESTRING', 'MULTILINESTRING'))
);

CREATE INDEX ix_n06_facility_geom
    ON geo.n06_road_facility USING gist (geom);
CREATE INDEX ix_n06_facility_geog
    ON geo.n06_road_facility USING gist ((geom::geography));
CREATE INDEX ix_n06_facility_revision_kind
    ON geo.n06_road_facility (revision_id, facility_kind);
CREATE INDEX ix_n06_facility_name_trgm
    ON geo.n06_road_facility USING gin (name_normalized gin_trgm_ops);

CREATE TABLE geo.jartic_link (
    jartic_link_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id          bigint NOT NULL REFERENCES dataset.revision(revision_id),
    source_object_id     text NOT NULL,
    direction_code       text,
    road_name            text,
    name_normalized      text,
    conflated_road_ids   bigint[] NOT NULL DEFAULT '{}',
    conflation_confidence numeric(5,4),
    attributes            jsonb NOT NULL DEFAULT '{}'::jsonb,
    geom                  geometry(MultiLineString, 4326) NOT NULL,
    UNIQUE (revision_id, source_object_id),
    CHECK (ST_SRID(geom) = 4326),
    CHECK (conflation_confidence IS NULL OR
           conflation_confidence BETWEEN 0 AND 1)
);

CREATE INDEX ix_jartic_link_geom
    ON geo.jartic_link USING gist (geom);
CREATE INDEX ix_jartic_link_geog
    ON geo.jartic_link USING gist ((geom::geography));
CREATE INDEX ix_jartic_link_revision
    ON geo.jartic_link (revision_id);

CREATE TYPE geo.congestion_level AS ENUM (
    'UNKNOWN', 'FREE', 'SLOW', 'JAM', 'BLOCKED'
);

CREATE TABLE geo.jartic_traffic_observation (
    observation_id       bigint GENERATED ALWAYS AS IDENTITY,
    observed_at          timestamptz NOT NULL,
    jartic_link_id       bigint NOT NULL REFERENCES geo.jartic_link(jartic_link_id),
    travel_time_seconds  integer,
    speed_kph            numeric(6,2),
    congestion           geo.congestion_level NOT NULL,
    restriction_code     text,
    raw_quality           jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (observation_id, observed_at),
    CHECK (travel_time_seconds IS NULL OR travel_time_seconds >= 0),
    CHECK (speed_kph IS NULL OR speed_kph BETWEEN 0 AND 250)
) PARTITION BY RANGE (observed_at);

-- 実運用では月次または日次partitionをmigration/jobで先行作成する。
CREATE TABLE geo.jartic_traffic_observation_default
    PARTITION OF geo.jartic_traffic_observation DEFAULT;

CREATE INDEX ix_jartic_obs_link_time
    ON geo.jartic_traffic_observation (jartic_link_id, observed_at DESC);
CREATE INDEX ix_jartic_obs_time_brin
    ON geo.jartic_traffic_observation USING brin (observed_at);

CREATE TYPE geo.named_feature_kind AS ENUM (
    'ADDRESS', 'STATION', 'PLACE', 'FACILITY',
    'IC', 'JCT', 'SA', 'PA', 'ROAD', 'OTHER'
);

CREATE TABLE geo.named_feature (
    named_feature_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id          bigint NOT NULL REFERENCES dataset.revision(revision_id),
    source_kind          dataset.source_kind NOT NULL,
    source_object_id     text NOT NULL,
    feature_kind         geo.named_feature_kind NOT NULL,
    name                 text NOT NULL,
    name_normalized      text NOT NULL,
    reading              text,
    aliases              text[] NOT NULL DEFAULT '{}',
    admin_code           text,
    attributes            jsonb NOT NULL DEFAULT '{}'::jsonb,
    geom                  geometry(Geometry, 4326),
    UNIQUE (revision_id, source_kind, source_object_id),
    CHECK (geom IS NULL OR ST_SRID(geom) = 4326)
);

CREATE INDEX ix_named_feature_geom
    ON geo.named_feature USING gist (geom);
CREATE INDEX ix_named_feature_geog
    ON geo.named_feature USING gist ((geom::geography));
CREATE INDEX ix_named_feature_name_trgm
    ON geo.named_feature USING gin (name_normalized gin_trgm_ops);
CREATE INDEX ix_named_feature_kind_revision
    ON geo.named_feature (revision_id, feature_kind);

CREATE TYPE lexicon.entity_type AS ENUM (
    'ADDRESS', 'STATION', 'PLACE', 'FACILITY',
    'ROAD', 'IC', 'JCT', 'SA', 'PA', 'PERSON', 'OTHER'
);

CREATE TABLE lexicon.reading_entry (
    reading_entry_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id          bigint NOT NULL REFERENCES dataset.revision(revision_id),
    source_kind          dataset.source_kind NOT NULL,
    source_object_id     text,
    entity_type          lexicon.entity_type NOT NULL,
    surface              text NOT NULL,
    surface_normalized   text NOT NULL,
    reading_katakana     text NOT NULL,
    admin_code           text,
    priority             integer NOT NULL DEFAULT 0,
    confidence           numeric(5,4) NOT NULL DEFAULT 1.0,
    attribution          text,
    attributes            jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (confidence BETWEEN 0 AND 1),
    CHECK (priority BETWEEN 0 AND 1000000),
    CHECK (source_kind <> 'MANUAL' OR priority = 1000000)
);

CREATE INDEX ix_reading_surface_exact
    ON lexicon.reading_entry
       (revision_id, surface_normalized, entity_type, priority DESC);
CREATE INDEX ix_reading_surface_trgm
    ON lexicon.reading_entry USING gin (surface_normalized gin_trgm_ops);

CREATE TABLE lexicon.reading_alias (
    reading_alias_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reading_entry_id     bigint NOT NULL
                         REFERENCES lexicon.reading_entry(reading_entry_id)
                         ON DELETE CASCADE,
    alias                text NOT NULL,
    alias_normalized     text NOT NULL,
    UNIQUE (reading_entry_id, alias_normalized)
);

CREATE INDEX ix_reading_alias_exact
    ON lexicon.reading_alias (alias_normalized);
CREATE INDEX ix_reading_alias_trgm
    ON lexicon.reading_alias USING gin (alias_normalized gin_trgm_ops);

CREATE TABLE lexicon.suffix_rule (
    suffix_rule_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    suffix_surface       text NOT NULL,
    suffix_normalized    text NOT NULL,
    reading_katakana     text NOT NULL,
    entity_type          lexicon.entity_type,
    priority             integer NOT NULL,
    enabled              boolean NOT NULL DEFAULT true,
    UNIQUE (suffix_normalized, entity_type)
);

-- route_cache は「1リクエスト＝1レスポンス（複数候補を含む Guidance 全体）」単位。
-- PKはリクエスト由来の cache_id であって、候補ごとの RouteDetail.id(routePackageId) ではない。
-- guidance_json には各候補が自分の routePackageId / routeCandidateId を持って入る（§8.3）。
CREATE TABLE nav.route_cache (
    cache_id              text PRIMARY KEY,   -- sha256(request_hash + model_version + provider_contract)。レスポンス単位
    request_hash          char(64) NOT NULL,  -- §17.3: origin/dest/via/出発bucket/vehicle/options/datasetRevisions/trafficBucket
    idempotency_key       text,
    model_version         text NOT NULL,
    provider_contract     text NOT NULL,
    dataset_revisions     jsonb NOT NULL,
    guidance_json         jsonb NOT NULL,     -- Guidance 全体。候補ID(routePackageId/routeCandidateId)は内側
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL,
    last_accessed_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (request_hash, model_version, provider_contract)
);

-- 候補形状での横断検索（refresh/rerouteの「同一ルートあり？」用）が要るなら、
-- guidance_json内のrouteCandidateIdを展開した補助表 nav.route_candidate_index(cache_id, route_candidate_id, route_package_id) を別途持つ。
-- route_cache のPKを routeCandidateId に兼用しない（出発時刻/traffic違いを取り違えるため）。

CREATE INDEX ix_route_cache_expiry
    ON nav.route_cache (expires_at);
CREATE UNIQUE INDEX uq_route_cache_idempotency
    ON nav.route_cache (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 既存 ExtNavGuideImageGateway は GuideImageKey(major, minor) のうち **minor を request ID**
-- として投げ、画像 cache も **minor 単位**（同一 minor＝同一バイナリ前提）。よって新 server は
-- minor を「グローバルに一意な画像 lookup key」として扱える schema/API を持つ必要がある。
CREATE TABLE nav.guide_image (
    minor_id              bigint PRIMARY KEY,    -- グローバル一意。app/gateway が cache・request に使う key
    content_type          text NOT NULL,
    content_sha256        char(64) NOT NULL,     -- 同一バイナリの de-dup・整合検証
    byte_length           bigint NOT NULL,
    storage_uri           text NOT NULL,         -- 解決済み画像バイト（server内部cache or object store）
    provider_object_key   text,                  -- HERE 由来key/URL（server内部限定。wireへ出さない）
    expires_at            timestamptz NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (byte_length >= 0)
);

-- minor の採番は **content_sha256 から決定的に導く**（同一画像→同一minor、衝突回避は広い整数空間＋sha照合）。
-- route毎に変わらないグローバルkeyにすることで、既存 minor 単位 cache がそのまま効く。
-- major は同一案内点の表示バリアント識別にのみ使い、lookup の一意性は minor が担う。
CREATE INDEX ix_guide_image_expiry
    ON nav.guide_image (expires_at);
CREATE INDEX ix_guide_image_sha
    ON nav.guide_image (content_sha256);
```

> 画像解決契約: `GuideImageRef(major, minor)`のうち`minor`がグローバル一意lookup key。gatewayは`minor`のlistでfetchし（既存`GuideImageRequest(ids=minor...)`と同形）、serverは`guide_image.minor_id`で引いてバイトを返す。`major`は表示バリアント用で一意性には使わない。route scope・寿命・衝突回避は`minor_id` PKと`content_sha256`で担保し、route_cacheとは別TTL（§17.3）。HERE junction viewが提供されない区間は`imageRefs`空＝画像なしfallback。

### 15.3 active revision切替

```sql
BEGIN;

-- 取込jobが対象revisionをVALIDATEDまで進めた後にのみ実行する。
UPDATE dataset.revision
SET status = 'RETIRED'
WHERE source_kind = :source_kind
  AND status = 'ACTIVE';

UPDATE dataset.revision
SET status = 'ACTIVE', activated_at = now()
WHERE revision_id = :new_revision_id
  AND source_kind = :source_kind
  AND status = 'VALIDATED';

-- 1件更新・active一意性をapplication側でも検査する。
COMMIT;
```

route request開始時に全sourceのactive revisionを1 transactionで読み、IDをcontextへ固定する。build中にviewを再参照しない。

## 16. 前処理・更新pipeline

### 16.1 共通pipeline

```text
fetch → checksum/manifest → staging load → normalize → geometry repair
→ source-specific validation → cross-source conflation → serving load
→ index/analyze → quality gate → revision activation → old revision retention/cleanup
```

各runは`revision_id`とmanifestを持つ。途中失敗したrevisionは`FAILED`にしてactiveへ触れない。

### 16.2 OSM

1. 対象地域PBFを取得しchecksumを保存。
2. osm2pgsql flex等でroad/node/relationをstagingへ展開。
3. drivable road class、name/ref、oneway、maxspeed、bridge/tunnel/layerを正規化。
4. named crossingとtraffic signal nodeを抽出。
5. connected road count/refsをprecompute。
6. invalid geometry修復、4326統一、duplicate source ID検査。
7. serving tableへrevision付きinsert、GiST/GIN作成、`ANALYZE`。

更新方式は差分適用をstagingで行っても、servingはrevision snapshotとして発行する。初期運用既定は定期full snapshot、容量・時間が問題化した時点で差分materializationへ移行する。

### 16.3 国土数値情報N06

1. 公式配布単位をmanifestへ記録。
2. GML/Shapefile等を`ogr2ogr`でstagingへロードし、source CRSを確認して4326へ変換。
3. 道路施設種別を`IC/JCT/SA/PA/RAMP/TOLL_GATE/...`へ明示map。
4. official name、路線名、施設コードを保持。
5. OSM road networkへ距離・道路階層・名称でconflateし、位置補正候補を生成。
6. coverage、重複、種別別件数、都道府県別偏りをquality gateにする。

N06は母集合・公式名称の軸、OSMはtopology/位置精緻化の軸として使い分ける。

### 16.4 JARTIC

1. 静的link definitionをrevision管理して`geo.jartic_link`へロード。
2. OSM/N06 route networkと方向付きconflationを事前計算。
3. 動的observationは受信時刻・提供時刻を分け、時系列partitionへappend-only insert。
4. duplicate、時刻逆行、異常速度、未知linkを隔離。
5. serving queryは`observed_at <= routeBuildTime`かつmaxAge以内の最新値を選ぶ。
6. 古いpartitionはretention policyでdrop/archiveする。

### 16.5 読み辞書

| レイヤ | 主用途 | 取込処理 |
|---|---|---|
| manual override | 誤読・誤割当した固有名詞の最優先修正 | review済みYAML/CSVを`MANUAL` revisionとして取込み、exact entity match・`priority=1000000`で格納。fuzzy展開しない |
| 郵便番号データ | 住所 | 行政コード、住所階層、表記・読みを正規化 |
| 駅データ.jp | 駅 | 駅名、路線、位置、同名駅disambiguation |
| Wikidata | 地名・施設・IC/JCT/SA/PA | CC0 dump/SPARQL出力をrevision化、label/alias/座標を保持 |
| JMnedict | 固有名詞 | entity typeとreadingを保持 |
| Geolonia | 地名 | 配布条件に従い内部辞書へロード |
| Wikipedia | IC/JCT/SA/PAの読み補助 | N06母集合に紐づく項目だけ、出典metadata付き |
| Sudachi/UniDic | 未ヒットfallback | runtime辞書versionをmanifest化。結果を無条件永続辞書化しない |

共通normalize:

- Unicode NFKC
- 連続空白除去
- ハイフン・長音の正規化を用途別に分離
- 数字・丁目・番地読みは住所専用規則
- カタカナreadingの検査
- suffix ruleは辞書entryと別tableでversion管理

manual override運用フロー:

1. 誤読をroute trace/golden comparator/device試験からissue化し、対象surface、entity type、地域またはsource object key、正しいカタカナ読み、根拠を記録する。
2. review済みのversioned YAML/CSVへ追記し、`MANUAL`の新revisionをstagingへ取込む。
3. exact match fixture、周辺同名地物への非波及、SSML/TTS回帰を確認してactive化する。
4. 問題時は直前`MANUAL` revisionへrollbackする。runtimeの無監査編集endpointは設けない。

辞書そのものをAPIでdump可能にしない。route responseに必要な読みだけを含め、帰属表示をアプリ/配布物の所定位置に置く。

### 16.6 quality gate

- row countが前revision比で設定範囲内
- null geometry、SRID不正、invalid geometryが0または許容値以下
- unique key衝突0
- 都道府県/feature kind別coverageの急落なし
- golden corridorで候補件数・snap率・誤反対車線率が閾値内
- reading conflict率、未読率、無効カナ率が閾値内
- query planでGiST index利用を確認
- activation後smoke routeが成功

## 17. application orchestration・cache・並列性

### 17.1 RouteBuildContext

```kotlin
data class RouteBuildContext(
    val traceId: String,
    val buildStartedAt: Instant,
    val deadline: Instant,
    val modelVersion: String,
    val providerContractVersion: String,
    val templateVersion: String,
    val datasetRevisions: DatasetRevisionSet,
    val degradedReasons: MutableSet<DegradedReason>,
)
```

### 17.2 並列化

`requestedPriorities`が複数ある場合、種別ごとのRouting呼び出し自体もstructured concurrencyで並列化する（各種別がRouting essential。1種別の失敗は当該候補のみ脱落させ、全体は他候補があれば継続。全種別失敗時のみroute失敗）。重複排除と第一候補確定は全Routing完了後に1回行う。

Routing完了後、選択された各候補について以下をstructured concurrencyで並列化する。

- Toll Cost
- Junction View
- Browse chunks
- PostGIS intersection/facility候補
- JARTIC候補
- reading batch prefetch

各childには個別timeoutを設定する。親deadline超過では未完了optional childをcancelし、得られた情報でwire化する。Routing childだけは失敗を親失敗へ伝播する。

### 17.3 cache key

```text
requestHash = SHA-256(
  canonicalized origin/destination/via/departure bucket/vehicle/options
  + modelVersion
  + providerContractVersion
  + activeDatasetRevisionSet
)
```

- `requestHash`から`cache_id = sha256(requestHash + modelVersion + providerContract)`を導き、`route_cache`のPKにする（レスポンス＝Guidance全体単位）。候補ごとの`routePackageId`（=`RouteDetail.id`）はguidance_jsonの内側にあり、cache PKに兼用しない。
- departure timeをbucket化する場合、traffic変動を隠さない粒度にする。
- route plan cacheとtraffic refresh cacheはTTLを分ける。
- route responseはcanonical JSONで保存し、serializer versionを記録する。
- cache hitでもasset expiryを検査する。
- idempotency keyとrequest hashが不一致なら409。

### 17.4 初期deadline budget

| 処理 | 初期上限 | timeout時 |
|---|---:|---|
| overall route build | 10s | 504 |
| Routing v8 | 6s | 502/504 |
| Toll Cost | 3s | fee UNKNOWN |
| Browse全chunk | 2.5s | 取得済みchunkのみ |
| Junction View | 2.5s | imageなし |
| PostGIS enrichment | 1.5s | HERE主体で継続 |
| Reading batch | 1s | suffix/形態素/plainへfallback |

値は負荷試験で調整するが、optional処理がoverallを無制限に延ばさない構造は固定する。

## 18. OneNavi側の差分

> **「binding 1行差し替えだけ」ではない（前版の過小評価を訂正）。** 案内実行系（`ExtNavGuidanceTracker`、`VoiceAnnouncementController`/`VoiceAnnouncementPlanBuilder`）は`RouteDetail`だけでなく、`ExtNavRouteRegistry.get(RouteDetail.id)`で取り出す**`ExtNavRoutePayload`（中身は nav-core `RouteGuidance` ＋ `sapaDetailsByName`）を直接読む**。よって新backendを使うには、新`ServerRouteDataSource`が**`RouteDetail`の生成に加えて、registryへ`ExtNavRoutePayload`を登録する**必要がある。
>
> これは「server応答が nav-core `RouteGuidance` wire 互換である（INV-01）」前提なら成立する: `ServerRouteDataSource`がserver応答の`RouteGuidance`をそのまま`ExtNavRoutePayload.routeGuidance`へ詰め、`RouteResult`/`RouteDetail`も組む。tracker/voice/registryの**型と呼び出しは無改修**。ただし作業は「binding＋新実装1個」ではなく「新実装が registry payload まで満たす」ことを要する。payloadを中立型へ移すのは別の大きな選択（TODO-21）。

### 18.1 無改修に保つ領域

| 領域 | 方針 |
|---|---|
| `core-model/RouteItem.kt` の`RouteDetail` | shape/意味を変更しない |
| `feature-map/` | Google Maps SDKとpolyline描画をそのまま使用 |
| `tts/` | 受信した`SsmlPhrase`/SSMLをGoogle Cloud TTSへ流す既存処理を維持 |
| guidance block選択 | block/ann/prefixの選択・再生タイミングを維持 |
| local route progress | geometryへの投影、通過判定、案内進捗を維持 |
| UI/panel | 既存model fieldをserverが埋めることで維持 |
| `RouteDataSource`/`RouteRepository`/`NewRouteManager` | interface・呼び出し形を維持。`single<RouteDataSource>`の束ね先を差し替える |
| `ExtNavGuidanceTracker`/`VoiceAnnouncementController`/`VoiceAnnouncementPlanBuilder` | 型・呼び出し無改修。ただし新`ServerRouteDataSource`が`ExtNavRoutePayload`（server`RouteGuidance`＋sapaDetails）をregistryへ登録することが前提 |
| `ExtNavRouteRegistry`/`ExtNavRoutePayload` | 型維持（payload中身はserver由来`RouteGuidance`）。中立型化はTODO-21で別途判断 |

### 18.2 変更する領域

1. base URLとAPI path。
2. request DTOを共有moduleへ寄せる。
3. response decoderのimportを`nav-core-model`正本へ統一。
4. HERE keyをAndroidから完全除去。
5. user auth interceptorを除去し、必要なら固定secret headerだけ付与。
6. `single<RouteDataSource>`の束ね先を`ExtNavRouteDataSource`から新`ServerRouteDataSource`へ差替え。新`ServerRouteDataSource`は (a) `RouteResult`/`RouteDetail`を組み、(b) **`ExtNavRouteRegistry`へ`ExtNavRoutePayload`（server由来`RouteGuidance`＋sapaDetails）を登録**する（tracker/voiceが消費するため）。feature/repository/manager/tracker/voiceの型・呼び出しは無改修だが、作業は「binding＋registry payload充足」を要する（§18.1注記）。rerouteは既存どおり`searchRoutes`再呼び出しで成立（§19.0）。
7. 案内画像gateway（`ExtNavGuideImageGateway`）の**内部fetch先を新serverへ向け替える**（型・signatureは不変、major/minor surrogate維持）。中立interface化を選ぶ場合のみ`MapViewModel`の依存差し替えが入る（TODO-19）。junction asset prefetchは既存image cache境界をそのまま使う。
8. reroute requestのcurrent/remaining points生成（既存rerouteフロー内）。**traffic refresh専用配線はS1〜S3では追加しない**。S4で入れる場合は§19.0の選択に従い消費層追加（TODO-20）。
9. **複数候補の要求リストを`ServerRouteDataSource`内部で固定**する。現行`ExtNavRouteDataSource`は`priorities = {Recommended, AvoidCongestion, Express, Free}`をhardcodeしており、`searchRoutes`境界にpriority引数は無い。`ServerRouteDataSource`も同じ既定`requestedPriorities`（`RouteOptions`へ`[RECOMMENDED, AVOID_CONGESTION, EXPRESS, FREE]`）を必ず組み、複数候補を返す。設定で可変にする場合もdefaultはこの4種とし、**binding差し替え後に候補UIが1本化しない**ことを受入基準（§21.3）で確認する。`RouteOptions.requestedPriorities`のAPI default（`[RECOMMENDED]`）は外部APIの素の既定であって、OneNaviの`ServerRouteDataSource`はそれに依存せず明示指定する。

### 18.3 DI

検出されたDI方式: **Koin**。既存の`NavigationModule`は`single<RouteDataSource> { ExtNavRouteDataSource(...) }`を束ねている。差分はこの**束ね先の差し替え＋新実装がregistry payloadを満たすこと**であり、新しいbinding型をfeatureへ足すことではない。

```kotlin
// nav-core内部（RouteDataSource実装の内側）。featureからは見えない。
val navCoreModule = module {
    single<GuidanceApiClient> { HttpGuidanceApiClient(get(), get()) }
    single<GuidanceDataSource> { ServerGuidanceDataSource(get()) }
}

// OneNavi側。RouteDataSourceの実装を差し替える。registryは既存のものを再利用。
val routeDataModule = module {
    single<RouteDataSource> {
        ServerRouteDataSource(            // ExtNavRouteDataSourceと並ぶ新実装
            guidanceDataSource = get(),   // 内部でGuidanceDataSource(=server)を呼ぶ
            registry = get(),             // ExtNavRouteRegistry（tracker/voiceが参照）
            // searchRoutesで:
            //  1) server Guidance を取得
            //  2) 各 RouteGuidance → RouteResult/RouteDetail を組む
            //  3) registry.put(ExtNavRoutePayload(id=RouteDetail.id,
            //       routeGuidance=server RouteGuidance, sapaDetailsByName=...)) を登録
            //  4) requestedPriorities は現行互換の4種を明示指定（§18.2-9）
        )
    }
}
```

feature・`RouteRepository`・`NewRouteManager`が依存するのは**従来どおり`RouteDataSource`**（実体は`RouteRepository`経由）であり、`GuidanceDataSource`はnav-core/server向けの内部interfaceとして`ServerRouteDataSource`の内側に閉じる。build flavorやdebug menuでfixture/replay/現行backendを切替える場合も、`single<RouteDataSource>`の束ね先（またはnav-core内のselector/config）だけを変える。消費featureに2種類のinterfaceを意識させない。

### 18.4 network adapter

検出された通信方式: **Ktor client（OkHttp engine） + kotlinx.serialization**

```kotlin
class HttpGuidanceApiClient(
    private val httpClient: HttpClient,
    private val config: RouteApiConfig,
) : GuidanceApiClient {
    override suspend fun route(request: RoutePlanRequest): Guidance
    override suspend fun reroute(request: RerouteRequest): Guidance
    override suspend fun refreshTraffic(
        request: TrafficRefreshRequest,
    ): Guidance
    override suspend fun fetchJunctionAsset(assetId: String): ByteArray
}

data class RouteApiConfig(
    val baseUrl: String,
    val fixedSecret: String?,
    val requestTimeoutMillis: Long,
)
```

`HttpClient`はDIで一度だけ生成し、Androidでは`OkHttp` engine、`ContentNegotiation { json(...) }`、`HttpTimeout`、固定secret header interceptorをinstallする。共有modelはkotlinx.serializationで直接decodeし、別のservice interface／converter層は追加しない。`HttpGuidanceApiClient`はこの`HttpClient`を薄く包み、URL組立・HTTP status mapping・serializer呼出だけを担当する。

### 18.5 認証stub

```kotlin
class StubAuthClient : AuthClient {
    override suspend fun restore(): AuthState = AuthState.Available
    override suspend fun signIn(): AuthState = AuthState.Available
    override suspend fun signOut() = Unit
}
```

実際の`AuthClient/AuthState`宣言にmethod/variant差がある場合、既存interfaceを変えず最小stubを実装する。navigation可否をuser tokenに依存させない。

### 18.6 実ソース候補

| 役割 | 検出した候補ファイル | 変更方針 |
|---|---|---|

| 通信アダプタ | `HANDOFF-PLAN.md`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/di/NavigationModule.kt`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavRouteDataSource.kt`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/RouteDistanceContextFactory.kt`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/newguidance/NewRouteManager.kt`<br>`onenavi-consumption/core-navigation-androidUnitTest/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavRouteDataSourceTest.kt`<br>`nav-core/src/main/kotlin/me/matsumo/navcore/guidance/internal/GuideProtoMapper.kt` | base URL、request、DIだけを変更。既存mapperを温存。 |

| 認証スタブ | `HANDOFF-PLAN.md`<br>`nav-core/src/main/kotlin/me/matsumo/navcore/NavCoreClient.kt`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavAuthGateway.kt`<br>`nav-core/src/main/kotlin/me/matsumo/navcore/auth/AuthClient.kt`<br>`nav-core/src/test/kotlin/me/matsumo/navcore/auth/AuthClientLiveTest.kt`<br>`nav-core/src/test/kotlin/me/matsumo/navcore/auth/AuthClientTest.kt`<br>`nav-core/src/test/kotlin/me/matsumo/navcore/guidance/RouteAccidentLiveTest.kt`<br>`nav-core/src/test/kotlin/me/matsumo/navcore/guidance/RouteBinaryCongestionLiveTest.kt`<br>`nav-core/src/test/kotlin/me/matsumo/navcore/guidance/RouteEmbeddedCongestionLiveTest.kt`<br>`nav-core/src/main/kotlin/me/matsumo/navcore/auth/domain/Credentials.kt` | no-op/fixed-secret stubへ縮退。 |

| 認証状態 | `HANDOFF-PLAN.md`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavAuthGateway.kt`<br>`onenavi-consumption/core-navigation-androidUnitTest/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavAuthGatewayTest.kt`<br>`nav-core/src/main/kotlin/me/matsumo/navcore/auth/AuthClient.kt`<br>`nav-core/src/test/kotlin/me/matsumo/navcore/auth/AuthClientLiveTest.kt`<br>`nav-core/src/test/kotlin/me/matsumo/navcore/auth/AuthClientTest.kt`<br>`nav-core/src/main/kotlin/me/matsumo/navcore/auth/domain/AuthState.kt`<br>`nav-core/src/main/kotlin/me/matsumo/navcore/auth/internal/AuthMapper.kt` | no-op/fixed-secret stubへ縮退。 |

| RouteDetail変換 | `HANDOFF-PLAN.md`<br>`README.md`<br>`onenavi-consumption/core-model/RouteItem.kt`<br>`onenavi-consumption/feature-map/MapEffect.kt`<br>`onenavi-consumption/feature-map/components/bottomsheet/MapRoutePreviewSheet.kt`<br>`onenavi-consumption/feature-map/components/callout/MapRoutePreviewCallOutMarkerEffect.kt`<br>`onenavi-consumption/feature-map/components/navigation/MapNavigationAlternativesCard.kt`<br>`onenavi-consumption/feature-map/components/navigation/MapNavigationManeuverPanel.kt`<br>`onenavi-consumption/feature-map/components/navigation/MapNavigationManeuverPanelSection.kt`<br>`onenavi-consumption/feature-map/components/navigation/MapNavigationSelectedWaypointCard.kt`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavGuidanceTracker.kt`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavRerouteDetector.kt` | 原則無改修。contract/replay testの確認対象。 |

| ルート描画 | `HANDOFF-PLAN.md`<br>`onenavi-consumption/feature-map/MapEffect.kt`<br>`onenavi-consumption/feature-map/components/MapPolyline.kt` | 原則無改修。contract/replay testの確認対象。 |

| Google Maps連携 | `onenavi-consumption/feature-map/MapEffect.kt`<br>`onenavi-consumption/feature-map/MapItem.kt`<br>`onenavi-consumption/feature-map/components/MapBookmarkMarker.kt`<br>`onenavi-consumption/feature-map/components/MapGuidanceManeuverArrow.kt`<br>`onenavi-consumption/feature-map/components/MapMarker.kt`<br>`onenavi-consumption/feature-map/components/MapMarkerClickDispatcher.kt`<br>`onenavi-consumption/feature-map/components/MapPolyline.kt`<br>`onenavi-consumption/feature-map/components/MapRoutePointEventMarker.kt`<br>`onenavi-consumption/feature-map/components/MapVehiclePuck.kt`<br>`onenavi-consumption/feature-map/components/callout/MapCallOutMarkerEffect.kt`<br>`onenavi-consumption/feature-map/components/callout/MapComposeBitmapDescriptor.kt`<br>`onenavi-consumption/feature-map/components/callout/MapGuidanceManeuverCallOutMarkerEffect.kt` | 原則無改修。contract/replay testの確認対象。 |

| TTS/SSML | `HANDOFF-PLAN.md`<br>`README.md`<br>`golden-sample-shakuji-tsukuba/SAMPLE-README.md`<br>`research/research-ExtApi-tts-classification.md`<br>`research/research-here-tos-selfvoice.md`<br>`research/research-here-voice-custom.md`<br>`research/research-here-yomi.md`<br>`golden-sample-shakuji-tsukuba/protobuf/README.md`<br>`golden-sample-shakuji-tsukuba/extracted/guide/guide-announcements.json`<br>`golden-sample-shakuji-tsukuba/extracted/guide/guide-summary.json`<br>`golden-sample-shakuji-tsukuba/extracted/guide/guide.json`<br>`onenavi-consumption/core-navigation-androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavAnnouncement.kt` | 原則無改修。contract/replay testの確認対象。 |

## 19. reroute・traffic更新・ローカル駆動

### 19.0 Android境界とserver endpointの対応（到達性）

現行OneNaviの公開境界は`RouteDataSource.searchRoutes(origin/destination/via/heading)`の1本のみ。**`previousRouteId`も「これはrerouteだ」という信号も渡らない**（`RouteDataSource.kt`、`NewGuidanceManager`のrerouteも素の`searchRoutes`を呼ぶだけ）。よって「`/reroutes`へ`previousRouteId`を渡す」設計は現行境界では成立しない。`ServerRouteDataSource`が状態保持で「今のは reroute だ」と推測する案は、通常検索・案内中alternatives・経由地編集検索と混ざるため**採らない**。

| server endpoint | S1〜S3 | S4（任意拡張） |
|---|---|---|
| `/routes` | 全searchをここへ写像（通常検索もrerouteも同じ`/routes`。serverはstateless、現在地始点でtraffic-aware再探索） | 同左 |
| `/reroutes` | **使わない**（previous routeを渡す境界が無いため） | 採るなら下記の境界追加が前提 |
| `/traffic-refreshes` | **使わない**（Android到達口なし） | 採るなら下記の境界追加が前提 |

**決定: S1〜S3は`/routes`のみで成立させる。** rerouteは現行どおり「現在地・進行方向・残経由地での再`searchRoutes`」＝serverには新規`/routes`として届き、§19.2のatomic swapで貼り替わる。previous routeを意識しないので消費層無改修。

**S4で`/reroutes`・`/traffic-refreshes`を使う場合**は「binding差し替えのみ」を超える**明示的な消費層追加**として扱う（§18.2、TODO-20）。最小実装:
  1. `RouteDataSource`に`reroute(previousRouteId, ...)` / `refreshTraffic(...)`を**additiveに追加**＋`RouteRepository` passthrough＋`NewGuidanceManager`/`NewRouteManager`から previousRouteId・trigger を渡す。
  2. もしくはreroute/refreshも`/routes`へ畳み、`routeCandidateId`（§8.3.1）で「同一形なら維持」を効かせて差分最適化はserver内部に閉じる（境界追加ゼロ）。
- どちらを採るにせよ、`previousRouteId`/`refreshTraffic`が**到達可能な実装場所を確定してからS4着手**。未配線のまま設計に残さない。

### 19.1 off-route責務

AndroidはGPS時系列、accuracy、route projection、heading、継続時間を使ってoff-routeを確定する。serverはoff-route判定APIを持たない。

```text
onLocation(location):
    if location.accuracy is unacceptable:
        return KEEP_CURRENT_ROUTE

    p = routeMeasureIndex.project(location.point)
    threshold = max(config.minLateralMeters,
                    location.accuracy * config.accuracyMultiplier)

    suspected = p.lateralDistance > threshold
                and headingMismatchPersists(location, p)
                and not insideGraceZoneNearComplexJunction(p)

    state = offRouteStateMachine.update(suspected, location.timestamp)

    if state == CONFIRMED:
        send RerouteRequest(
            currentPosition=location.point,
            currentHeadingDegrees=location.bearing,
            remainingViaPoints=notPassedViaPoints,
            destination=destination
        )
```

既存実装の閾値・state machineを優先し、本移行だけを理由に変更しない。実機検証でparallel road誤判定が確認された場合のみ、heading/topology/grace zoneを調整する。

### 19.2 route packageの原子的置換

- response decodeとmodel validationが成功するまで現routeを維持。
- geometry、guidance points、blocks、traffic、assets manifestを1 packageとしてswap。
- TTS queueは旧routeの未再生blockを破棄し、新routeの現在measure以後を再選択。
- map polylineとpanelを同じpackage versionで更新する。
- reroute失敗時は既存routeを保持し、再試行UI/一般案内へfallback。

### 19.3 traffic refresh trigger

- 一定時間間隔、ユーザ操作、重大渋滞通知等の既存triggerに限定。
- 案内中の毎位置更新で通信しない。
- 同時route/reroute/refreshはsingle-flightにし、新しいrerouteを優先して古いrefreshをcancelする。

## 20. 非機能・運用設計

### 20.1 resilience

- providerごとにcircuit breakerを分離。
- DB pool枯渇をKtor coroutine滞留で増幅させない。
- route buildはsingle request scoped coroutine。
- optional callはbulkheadで同時数制限。
- retryはidempotent GET/route calculationのprovider規約範囲だけ。
- external 429をAndroidへそのまま露出せず、server retryabilityへ変換。

### 20.2 observability

必須structured log field:

```text
traceId, routeId, endpoint, requestHashPrefix,
providerContractVersion, modelVersion, templateVersion,
datasetRevisionSet, originGrid, destinationGrid,
latencyByStage, candidateCountsByKind, acceptedCountsByKind,
degradedReasons, unknownEnumCounts, responseBytes
```

精密座標・読み上げ全文・secret・provider raw bodyは通常ログへ出さない。必要なdebugは明示的な短期samplingとアクセス制御を使う。

必須metric:

- route success/failure/latency
- provider error/429/timeout
- enrichment degradation rate
- intersection/IC/JCT snap acceptance rate
- wrong-carriageway rejection count
- reading hit rate by layer
- SSML validation fallback rate
- toll availability/partial rate
- junction image hit/cache rate
- unknown enum count
- response payload percentile

### 20.3 security

- TLS必須。本番相当で固定secretを使う場合はrotation可能にする。
- HERE keyをenvironment/secret fileから注入し、docker imageへ焼かない。
- SQL parameter bindingを必須化。GeoJSON/WKTを文字列連結しない。
- request size、via count、corridor length、asset response sizeを制限。
- internal endpointは外部公開しない。
- reading dictionaryのbulk取得endpointを作らない。
- dependency/SBOM/secret scanをCIに入れる。
- **HERE課金API保護（固定secretはAPK抽出可能で保護にならない前提）**: per-IP/per-device rate limit、route呼び出しの日次上限、HERE quotaへのcost cap/circuit breaker、異常トラフィック検知。public/共有配布する場合はこれらを必須とし、private専用なら配布範囲・ネットワーク制限で代替する（§6.2、TODO-18）。

### 20.4 docker-compose構成

```yaml
services:
  api:
    build: ./server
    depends_on:
      db:
        condition: service_healthy
    environment:
      JDBC_URL: jdbc:postgresql://db:5432/onenavi
      HERE_API_KEY_FILE: /run/secrets/here_api_key
  db:
    image: postgis/postgis:<pinned-version>
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U onenavi"]
  migration:
    build: ./server
    command: ["./gradlew", "flywayMigrate"]
  importer:
    build: ./data-pipeline
    profiles: ["import"]
secrets:
  here_api_key:
    file: ./.secrets/here_api_key
```

versionは実装開始時にpinし、`latest`を使わない。migrationとdataset importは別processにする。

### 20.5 契約・ライセンスgate

- HERE由来route/junction/toll情報をGoogle Maps上で表示する利用形態。
- HERE response/assetのcache期間とproxy可否。
- 各日本データの帰属表示、内部利用、派生データ、再配布条件。
- Wikipedia由来読みの採用・帰属方法。
- Google Cloud TTSへ送るtext/SSMLのデータ取扱い。

本書は技術構造を確定するが、上記は本番release前の契約確認TODOとして残す。

## 21. test・受入設計

### 21.1 test pyramid

| 層 | 対象 | 主なassertion |
|---|---|---|
| unit | HERE mapper | enum全値、offset、span、単位、notice |
| unit | measure/map-match | 投影、方向差、順序、反対車線、重複 |
| unit | speech/reading | tier、prefix、escape、辞書優先、suffix |
| repository | PostGIS | revision filter、GiST query、距離、partition |
| contract | Ktor JSON | schema、null/empty、enum、field order非依存 |
| replay | 保存provider fixture | deterministic Guidance生成 |
| golden | 石神井→つくばsample | block/ann/prefix/SSML/panel/passages |
| Android integration | data source→RouteDetail | mapper、TTS、MapPolyline無改修 |
| device | driving/simulation | timing、reroute、画像、音声自然さ |

### 21.2 golden sample comparator

```text
normalize(response):
    remove volatile routeId/timestamp/trace fields
    canonicalize JSON object key order
    preserve array order
    normalize insignificant SSML whitespace only
    canonicalize XML attributes without changing text

align expected/actual decision points by:
    maneuver semantic + route order + measure tolerance

assert:
    required distance tiers exist
    ann/prefix semantics and order match
    every SSML fragment is well-formed
    required proper nouns have expected reading
    panel and passage point ordering match
    geometry is WGS84 and drawable
```

単純な全JSON byte一致だけにしない。provider route形状差があり得るfieldはsemantic alignmentを使う一方、block内phrase順、prefix、SSML textは厳密比較する。

### 21.3 受入基準

- 全decision pointに最低`まもなく`/直前相当blockがある。
- 適用可能なdecision pointで500m/300mの多段案内が生成される。
- 短間隔maneuverで順序逆転・過剰発話がない。
- prefix単独再生でも操作対象が曖昧にならない。
- 高速系固有名詞（IC/JCT/SA/PA/料金所）はgolden corridorの対象母集合に対し、正しい読みで固有名詞を発話できた率が95%以上。残りは一般表現へ落とし、誤った読みを発話した件数は0とする。
- 一般道交差点名は、golden corridorで誤読・誤割当が0。名称を安全に割り当てられない点ではD11が作動し、「次の信号」「○○通り」「右左折一般文」のいずれかで操作が一意に伝わり、曖昧な固有名詞発話を出さない。
- decision pointへのOSM/N06名称割当は、人手正解付きの都市・郊外・高速の層別標本でprecision 98%以上。recallは初回承認値をbaselineとして固定し、同一標本で2 percentage pointを超えて退行しない。
- IC/JCT/SA/PAおよび一般道passage pointについて、反対車線施設・route順序逆転・同一logical facilityの重複がgolden corridorで0。
- speed limit/congestion区間がgeometry measureと単調整合する。
- toll不明を0と表示しない。
- junction image欠損で案内が停止しない。
- Androidの`RouteDetail`、TTS、Google Maps描画の既存testが通る。
- **候補ルートUIが1本化しない**: `ServerRouteDataSource`が既定priority群（推奨/渋滞回避/高速優先/一般道優先）で複数候補を返し、候補選択UIが従来どおり並ぶ。
- **registry payload充足**: `ExtNavRouteRegistry.get(RouteDetail.id)`がserver由来`ExtNavRoutePayload`を返し、tracker/voiceが無改修で動く。
- **画像解決**: `GuideImageRef.minor`でserverから画像が引け、既存minor単位cacheが効く。
- **ID健全性**: 同一形・traffic違いのrouteで`routeCandidateId`が一致し`routePackageId`が異なる。cache PKに`routeCandidateId`を使っていない。`groupId`は束ねキーのままで音声選抜が崩れない。
- HERE keyがAPK/通信response/logに存在しない。
- すべてのwire座標がWGS84範囲内。

### 21.4 performance test

- 都市部高密度corridor、長距離高速route、多経由地、JARTIC大量snapshotを別scenario化。
- p50/p95/p99、DB candidate count、response size、GC、pool待ちを測る。
- spatial queryは`EXPLAIN (ANALYZE, BUFFERS)`を保存し、sequential scan退行をCI/定期testで検知する。

## 22. gap register・要実機検証・未確定事項

### 22.1 gapの扱い

- gapは隠さず、`DESIGN-CLOSED`、`IMPLEMENTATION-TODO`、`DEVICE-VALIDATION`、`DATA-VALIDATION`、`CONTRACT-VALIDATION`のいずれか、または必要な複合状態へ分類する。`OPEN`を付した項目は、設計方針があっても実データ・実機の閉じ条件を満たすまで未解決と扱う。
- 外部coverage不足を推測データで埋めない。
- 実機検証項目をunit testだけで閉じない。
- `HANDOFF-PLAN.md`のgap/要検証が本節の一般TODOと競合する場合、同文書の個別項目を優先する。

### 22.2 gap状態と閉じ条件

| ID | gap | 設計上の閉じ方 | 状態 | 閉じ条件・残作業 |
|---|---|---|---|---|
| GAP-D01 | 通過交差点名がHEREだけで不足 | OSM corridor snap + topology score | `DESIGN-CLOSED` / `DATA-VALIDATION` | §21.3の名称割当precision/recall gateを満たす |
| GAP-D02 | IC/JCT/SA/PA補完 | N06母集合 + OSM位置/topology + 読み統合 | `IMPLEMENTATION-TODO` | N06属性mappingと反対車線除外testを実装 |
| GAP-D03 | 信号基準案内 | OSM signal + route順序count + D11 fallback | `DATA-VALIDATION (OPEN)` | 都市・郊外別のsignal付与率とdecision point割当精度を実測し、欠落時にD11へ確実に落ちることを確認するまで閉じない |
| GAP-D04 | 名称欠損時発話 | D11の4段fallback | `DESIGN-CLOSED` | golden fixture追加 |
| GAP-D05 | 固有名詞読み | layered dictionary + suffix + morphology + manual exact override | `DEVICE/DATA-VALIDATION (OPEN)` | 最大リスク。TODO-05b/05cと§21.3の読みgateを満たすまで閉じない |
| GAP-D06 | traffic統合 | HERE優先、欠損区間だけJARTIC | `IMPLEMENTATION-TODO` / `DATA-VALIDATION` | conflation精度検証 |
| GAP-D07 | toll失敗 | UNKNOWN/partial、route継続 | `DESIGN-CLOSED` / `CONTRACT-VALIDATION` | 日本料金coverage検証 |
| GAP-D08 | junction image欠損 | null + imageなし案内 | `DESIGN-CLOSED` / `DEVICE-VALIDATION` | asset契約/端末cache検証 |
| GAP-D09 | model二重管理 | nav-core-model単一所有 | `IMPLEMENTATION-TODO` | module分割・serializer移動 |
| GAP-D10 | provider切替 | GuidanceDataSource + DI | `IMPLEMENTATION-TODO` | 実source binding変更 |
| GAP-D11 | 複数名前付き候補ルート（推奨/高速優先/一般道優先等）の再現 | `requestedPriorities`（list） → 種別別HERE呼び出し → `RouteGuidance.priority`ラベル付け（§11.3.1） | `DATA-VALIDATION (OPEN)` | TODO-17。HEREに明示モードが無い種別の候補分化を実測するまで閉じない |

### 22.3 要実機・契約検証TODO

| TODO | 種別 | 完了条件 |
|---|---|---|
| TODO-01 | device | Google Cloud TTSでsample SSMLの読み、pause、数字、IC/JCT接尾辞が期待通り |
| TODO-02 | device | 500/300/まもなく/直前の発話時刻が速度・GPS遅延下で適切 |
| TODO-03 | device | parallel road、立体交差、トンネル出口でoff-route誤判定が許容範囲 |
| TODO-04 | device | reroute package swap時に旧panel/旧音声が残らない |
| TODO-05 | device | junction imageのprefetch/cache/回転/欠損時UIが正常 |
| TODO-05b | data | golden corridorの固有名詞を母集合化し、辞書hit率・読み正答率・一般表現fallback率を標本評価する。高速IC/JCT/SA/PAは辞書hit率90%以上を実装中間gate、最終合否は§21.3の95%以上 + 誤読発話0とする。一般道交差点名は別集計とする。 |
| TODO-05c | device/operations | `lexicon.reading_entry`の`MANUAL` exact overrideをissue→review→revision取込→TTS回帰→active化→rollbackでき、誤読語を次releaseで確実に上書きできる。 |
| TODO-06 | device | 長距離route response sizeとメモリ使用が端末許容内 |
| TODO-07 | data | 都市/郊外/高速でintersection name・signal・IC/JCT snapのprecision/recallと付与率を人手正解標本で評価し、§21.3 baselineを固定する。 |
| TODO-08 | data | JARTIC link方向とroute corridorのconflation誤り率を評価 |
| TODO-09 | provider | Toll Costの日本車種、ETC、割引、明細coverageを契約環境で確認 |
| TODO-10 | provider | Junction Viewの取得方式、coverage、asset保持条件を確認 |
| TODO-11 | contract | HERE情報とGoogle Maps表示の併用、cache/proxy/attributionを確認 |
| TODO-12 | license | 全読み辞書・N06・JARTIC・OSMの帰属文言と内部利用条件を承認 |
| TODO-13 | compatibility | 既存Android serializerがserver JSONを無変換でdecodeし全adapter test通過 |
| TODO-14 | operations | dataset revision activation/rollbackと古いJARTIC partition cleanupを演習 |
| TODO-15 | security | APK、container image、logs、tracesにHERE key/secretがないことをscan |
| TODO-16 | provider/device | HERE traffic応答にVICS snapshotと同義の観測時刻が存在するか契約環境で確認する。存在しなければ`Guidance.vicsTime=null`を維持し、OneNaviの時刻表示を撤去または非表示にする。 |
| TODO-17 | provider/data | `RoutePreference`各種別（推奨/渋滞回避/高速優先/一般道優先/距離優先）が§11.3.1の写像で**実際に分化した候補ルート**を生むか実機検証する。HEREに明示モードが無い`EXPRESS`/`AVOID_CONGESTION`が`RECOMMENDED`と同一geometryに潰れる頻度を計測し、UIの候補タブが成立する程度に分化するか、avoid/出発時刻/area avoidance等の誘導が要るかを確定する。 |
| TODO-18 | security/operations | HERE課金API保護の方式を確定する。固定secretはAPK抽出可能で保護にならない前提で、(a) private専用（配布範囲・IP/network制限）に留めるか、(b) public/共有配布ならrate limit・日次上限・HERE cost cap/circuit breaker・異常検知を実装するか、を決め完成条件に含める（§6.2、§20.3）。 |
| TODO-19 | compatibility | 案内画像を「既存`ExtNavGuideImageGateway`の内部fetch先付け替え＋major/minor surrogate維持」で無改修通すか、中立interface`NavGuideImageGateway`へ切るかを確定する。`MapViewModel`が具象gateway＋`GuideImageKey(major,minor)`に依存している点をどちらで満たすか決める（§7.4、§18.2）。 |
| TODO-20 | compatibility | S4のtraffic refresh / rerouteをAndroidから到達可能にする実装場所を確定する（`RouteDataSource`にreroute/refresh追加か、`/routes`畳み込みか）。`previousRouteId`/`refreshTraffic`が未配線のまま残らないようにする（§19.0）。 |
| TODO-21 | compatibility | 案内実行系の payload を旧`ExtNavRoutePayload`（nav-core `RouteGuidance`）互換で維持するか、中立 payload/registry へ切るかを確定する。S1-S3はserver応答`RouteGuidance`をpayloadへ詰めて互換維持、将来中立化する場合の移行を設計する（§18.1）。 |

### 22.4 HANDOFF-PLAN §4 gap register（名称中立化済み）

#### HANDOFF-PLAN §4 Gap register（HERE+副データでも埋まりにくい＝劣化許容/要検証）

- 一般道交差点名(存在・読みとも最弱)→ D11代替表現で吸収。
- 通過IC/JCTの完全網羅・名称表記 → N06補完、要実機検証。
- SA/PA内設備の「同一SA配下」束ね → HERE非保証、NEXCO/OSMで補完、要検証。
- 信号の郊外カバレッジ(OSM) → 都市部良/郊外疎、要計測。
- HERE junction view 日本提供/品質 → 要実機検証(無ければD7でfallback)。
- HERE signpost/toward(方面)の一般道カバレッジ → 要検証。
- ExtApi固有の付加価値(事故多発/オービス/ねずみ取り/冠水)→ 別データ未調達、対象外 or 将来。

### 22.5 HANDOFF-PLAN §5 要検証（名称中立化済み）

#### HANDOFF-PLAN §5 要実機検証（設計後／実装時に解消）

1. HERE `spans` enum 本番実値(`/openapi`取得で固定)
2. HERE junction view 日本提供・品質
3. HERE Places 日本SA/PA網羅率・設備束ね
4. 通過IC/JCT の corridor列挙 vs N06補完精度
5. HERE signpost/toward(方面)・IC/JCT名の構造化提供範囲
6. 読み辞書のIC/JCT/SA/PAカバレッジ実測
7. OSM交差点名/信号の付与率・沿線スナップ精度
8. HERE traffic 日本精度
9. HERE traffic応答のsnapshot観測時刻の有無と意味。VICSと同義でなければ`vicsTime`へ流用しない

## 23. 完成条件に対する作業分解とincremental移行戦略

### 23.1 最終完成定義と依存関係

本設計の最終形と§21/§22の完成条件は変更しない。以下は機能削減版や別仕様ではなく、同じ最終形へ到達する依存関係である。

```text
shared model/schema ─┬─ Android contract test
                     └─ Ktor endpoint skeleton

Routing adapter → canonical route/measure ─┬─ geometry/panel
                                          ├─ corridor snap
                                          ├─ traffic/speed
                                          └─ toll/junction/browse

PostGIS DDL → import pipelines → active revisions → corridor repository

DecisionPointSemantic → reading resolver → speech planner → golden comparator

all components → route build orchestration → Android integration → device/contract gates
```

完成の定義は「endpointが応答する」ではなく、§21の全受入基準と§22の未確定TODOが、実装完了または明示承認された状態である。

### 23.2 稼働到達順序 S1-S4

リスクを直列化しないため、稼働到達は次の順で刻む。各段は同じ`RouteDataSource`境界（その内側でnav-coreの`GuidanceDataSource`/server backendを切替える）と共有wire model上で実現し、feature/UI/TTS/Google Maps消費層に段階判定を持ち込まない。

| 段階 | 新backendへ移す範囲 | 検証の中心 | rollback |
|---|---|---|---|
| S1 route基盤 | 1 tripのgeometry・案内点・発話を**同一lineageに固定**したまま、route/geometry/duration/tollの算出をHEREベースへ移す。HERE採用tripはmaneuver案内もHERE actions由来の簡易案内（depart/turn/keep/arrive）で出し、現行backendの発話を新geometryへ重ねない。リッチな通過点・固有名詞発話はS2/S3で追加 | `RouteDetail`主要field、WGS84描画、ETA、料金UNKNOWN規則、**地図ルートと発話の同一lineage**、maneuver整合 | trip単位で現行sourceへ戻す（geometryと発話を一括で） |
| S2 沿線semantic | intersection、IC/JCT/SA/PA、signal、panel/passage pointをOSM/N06/PostGIS snapで追加。発話は現行または安全な簡易文 | §9のprecision/recall、反対車線除外、順序・重複、D11 fallback | enrichmentを無効化しS1へ |
| S3 発話・読み | speech planner、block/ann/prefix、SSML、ReadingResolverを新backendへ移す | golden comparator、§21.3読みgate。manual override機構を先に有効化し、高速→一般道の順で対象語を投入 | speech sourceだけ現行へ |
| S4 最終形 | reroute、traffic refresh、junction asset、全degradation/operationsを本設計どおり有効化。traffic refreshのAndroid配線（§19.0 / TODO-20）と画像gatewayの最終形（TODO-19）をここで確定 | 実機、長距離、failure injection、運用rollback、refresh到達性 | route単位で直前stageまたは現行sourceへ |

**異なるroute lineageのgeometryと発話・案内点を1 trip内で混在させてはならない。** 地図に描くルートと、その上で読み上げる発話・パネルは常に同じ生成元（HERE採用tripはHERE、現行tripは現行）から作る。これがS1の最優先不変条件。

geometryだけ先行検証したい場合は、served-with-old-voiceではなく**shadow比較**（現行を表示・発話しつつHERE結果を裏で算出してログ比較）で行う。やむを得ず合成・切替の同等性を判定する場合の`RouteAlignmentGate`は、geometry近接だけでは不十分なので次を全て要求する:

- origin/destination/via順一致、総距離差3%以内。
- 既存案内点の95%以上が新geometryの30m以内へ単調投影できる。
- **maneuver等価性**: decision pointのturn列（個数・走行順）、各decision pointのturn direction、分岐/合流/高速出入口の選択、（取得できる範囲で）lane選択が一致する。1つでも食い違えば不成立。

不成立時は、そのtripのroute package全体（geometry＋発話＋パネル）を同一lineageへfallbackする。geometryだけ差し替えて発話を残す部分適用は禁止。

### 23.3 provider選択と即時rollback

```kotlin
enum class GuidanceMigrationStage { S1_ROUTE, S2_ENRICHMENT, S3_SPEECH, S4_FINAL }

data class GuidanceProviderConfig(
    val stage: GuidanceMigrationStage,
    val forceExistingSource: Boolean = false,
)

interface GuidanceSourceSelector {
    fun select(config: GuidanceProviderConfig): GuidanceDataSource
}
```

provider選択はDF9どおりbuild flavorまたは署名付きruntime configで行い、DI binding/selectorだけを変更する。responseにはinternal traceとしてstage、route source、speech source、fallback reasonを記録するが、feature層へprovider分岐を露出しない。各段で現行sourceへ即時rollback可能にし、rollback演習をrelease gateへ含める。

## 付録A. server主要クラスのsignature

```kotlin
class DefaultRouteApplicationService(
    private val revisionRepository: DatasetRevisionRepository,
    private val routeEngine: RouteEngineGateway,
    private val browseGateway: AlongRouteSearchGateway,
    private val junctionGateway: JunctionViewGateway,
    private val tollGateway: TollCostGateway,
    private val corridorRepository: CorridorFeatureRepository,
    private val trafficRepository: TrafficRepository,
    private val routeNormalizer: RouteNormalizer,
    private val matcher: AlongRouteMatcher,
    private val guidanceAssembler: GuidanceAssembler,
    private val speechPlanner: GuidanceSpeechPlanner,
    private val validator: GuidanceContractValidator,
    private val cache: RouteCache,
) : RouteApplicationService

interface DatasetRevisionRepository {
    suspend fun currentSet(): DatasetRevisionSet
}

interface RouteNormalizer {
    fun normalize(route: ProviderRoute): CanonicalRoute
}

interface AlongRouteMatcher {
    fun match(
        route: CanonicalRoute,
        candidates: List<CorridorCandidate>,
    ): AlongRouteEnrichment
}

interface GuidanceContractValidator {
    fun validate(guidance: Guidance): List<ContractViolation>
}

interface RouteCache {
    suspend fun find(requestKey: RouteRequestKey): Guidance?
    suspend fun put(
        requestKey: RouteRequestKey,
        routeId: String,
        guidance: Guidance,
        expiresAt: Instant,
    )
}

interface JunctionAssetService {
    suspend fun prefetch(
        routeId: String,
        assets: List<JunctionViewAsset>,
    ): List<JunctionAssetReference>
}
```

責務境界:

- `DefaultRouteApplicationService`: 順序・deadline・degradationだけ。
- `RouteNormalizer`: provider response解釈。
- `AlongRouteMatcher`: 空間候補採点・割当。
- `GuidanceAssembler`: semantic object→wire model。
- `GuidanceSpeechPlanner`: 発話構造。
- `ReadingResolver`: 読み。
- `GuidanceContractValidator`: 互換条件。修復処理はしない。

## 付録B. repository query contract

```kotlin
data class RouteCorridor(
    val routeGeometry: List<GeoPoint>,
    val chunks: List<RouteCorridorChunk>,
)

data class RouteCorridorChunk(
    val index: Int,
    val geometryGeoJson: String,
    val startMeasure: RouteMeasureMeters,
    val endMeasure: RouteMeasureMeters,
    val metricSrid: Int,
)

data class CorridorCandidate(
    val source: DataProvenance,
    val sourceObjectId: String,
    val kind: CorridorFeatureKind,
    val position: GeoPoint,
    val sourceDirectionDegrees: Double?,
    val roadClass: RoadClass?,
    val name: String?,
    val reading: String?,
    val attributes: Map<String, String>,
)

data class DataProvenance(
    val sourceKind: DatasetSourceKind,
    val revisionId: Long,
)
```

repositoryはwire modelを返さない。SQL row→`CorridorCandidate`までがrepository責務で、routeへの採用はmatcher責務である。

## 付録C. config key

```yaml
routeApi:
  modelVersion: "guidance-model-<pinned>"
  templateVersion: "ja-guidance-<pinned>"
  overallTimeoutMs: 10000
  fixedSecretFile: null

here:
  apiKeyFile: "/run/secrets/here_api_key"
  routingContractVersion: "<pinned>"
  routingTimeoutMs: 6000
  tollTimeoutMs: 3000
  browseTimeoutMs: 2500
  junctionTimeoutMs: 2500

corridor:
  maxChunkMeters: 50000
  chunkOverlapMeters: 250
  candidateLimitPerChunkAndKind: 2000
  speechConfidenceThreshold: 0.75
  displayConfidenceThreshold: 0.60

traffic:
  jarticMaxAgeSeconds: 300
  refreshSingleFlight: true

cache:
  routeTtlSeconds: 300
  junctionAssetTtlSeconds: "<contract-dependent>"
```

secret値、契約依存値、provider上限をrepositoryへhard-codeしない。

## 付録D. HANDOFF-PLANの既定・判断候補trace

### D.1 §2 既定（名称中立化済み）

#### HANDOFF-PLAN §2 既定（明示質問せず合理的に確定。必要なら上書き可）

- DF1 HERE products: Routing v8(traffic-aware) + Geocoding&Search `/browse`(along-route corridor) + junction view + spans。
- DF2 spans取得: `names,routeNumbers,speedLimit,streetAttributes,functionalClass,tollSystems,railwayCrossings,gates,routingZones,noThroughRestrictions,incidents,length,duration`。
- DF3 制限速度源 = HERE `spans=speedLimit`(m/s正規化、field27罠なし)。規制案内(category)=JARTIC+HERE incidents。
- DF4 SA/PA: HERE `/browse` route corridor + category `400-4300-0202`/`400-4300-0000` で位置、設備は NEXCO公開/OSM amenities で束ねて補完。
- DF5 通過IC/JCT列挙: HERE actions(降りる分) + 国土数値情報N06(現況版・通過分)を座標スナップ。
- DF6 信号: OSM `highway=traffic_signals` + JARTIC(98) を沿線スナップ。「N個目の信号」はサーバでカウント生成。
- DF7 座標 = WGS84 度に統一(Coordはdegrees)。ExtApiミリ秒/Tokyo datumは旧backend内に隔離。
- DF8 wire format = kotlinx.serialization JSON of 共有ドメインモデル。
- DF9 provider選択 = OneNaviのDI/設定(build flavor or runtime設定)。ExtApi backendはコードとして残す。
- DF10 沿線スナップ = HERE polyline(+segmentId/offset)に対し PostGIS GiST 最近傍/corridor内検索で副データを付与。
- DF11 前処理パイプライン込み(OSM extract→PostGIS, N06 import, JARTIC取得, 読み辞書ビルド)。
- DF12 読み辞書: 住所=郵便番号データ(PD)/駅=駅データ.jp/地名施設=Wikidata(CC0)+JMnedict(CC BY-SA)+Geolonia(CC BY)/IC-JCT-SA-PA=Wikidata+Wikipedia+N06母集合/未ヒット=Sudachi(Apache)・UniDic(BSD選択)。辞書は再配布せずサーバ内部利用=帰属表示のみ。
- DF13 読み接尾辞ルール層: ＩＣ→インター/ＪＣＴ→ジャンクション/ＳＡ→サービスエリア/ＰＡ→パーキングエリア/号線→ごうせん 等を機械処理。
- DF14 料金表示 = HERE Toll Cost(車種別)。日本ETC割引/対距離/車種区分の実精度は要検証(§5に追加)、ずれるなら将来自前NEXCO補正。RouteSummary.fareTable/fareSegmentsへマップ。
- DF15 複数ルート候補 = HERE alternatives + ルーティングモード(推奨/有料回避/距離優先)。既存 RouteSearchCriteria.priorities にマップ。

### D.2 §6 設計判断候補（名称中立化済み）

#### HANDOFF-PLAN §6 設計詳細の委任範囲

- 沿線スナップのmap-matchアルゴリズム(最近傍/HMM/区間オフセット)
- リルート/経路逸脱判定(OneNaviは既にDead Reckoning Phase2設計あり=連携)
- Ktorエンドポイント設計・DBスキーマ・前処理スクリプト
- キャッシュ/エラーハンドリング/レート制御
- provider選択の具体機構

## 付録E. source traceability

| 設計項目 | 根拠 |
|---|---|
| wire model、既存adapter維持 | `nav-core/`、`onenavi-consumption/extnav/` |
| `RouteDetail.geometry`とmap | `core-model/RouteItem.kt`、`feature-map/` |
| block/ann/prefix/SSML | golden sample JSON/SSML/SAMPLE-README |
| field source mapping | HANDOFF-PLAN §3 |
| gap/TODO | HANDOFF-PLAN §4/§5 |
| provider selection/map-match/reroute/API | HANDOFF-PLAN §6 + 本書D01〜D20 |
| 名称中立 | `naming-policy-AGENTS.md` + 最終機械scan |
| data/license | `research/`と各dataset manifest |
