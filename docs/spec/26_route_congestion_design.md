# 26. ルート上の渋滞情報 — データ取得口の設計

> **作成日:** 2026-05-13
> **更新日:** 2026-05-13（Phase 0 = 外部API ライブラリ側の RE 完了。`field 12` 確定）
> **ステータス:** 取得口の RE 完了。Phase 1 (パーサ実装) 着手前 / Phase 2 (OneNavi 配線) 完了
> **対象:** ルート検索結果に「ルートに沿った渋滞区間」の情報を載せ、`RouteItem` / `RouteDetail` から取得できるようにする。地図描画・横帯 UI・ナビ中表示・音声案内の **利用側 UI / ナビゲーションは本書のスコープ外**（→ `27_route_congestion_visualization_design.md`）。本書が決めるのは「どこから渋滞情報を取り、どんな形で公開するか」だけ。
> **関連:** `19_external_api_integration_plan.md`, `21_ext_api_guide_proto_and_announcement.md`, `27_route_congestion_visualization_design.md`（UI / 音声側）

---

## 0. スコープと前提

### 0.1 本書がやること

ルート検索（外部API ライブラリ経由）の結果に、**ルートに沿った渋滞区間の列**を含めて返せるようにする。具体的には:

- 外部API ライブラリ側: route バイナリに埋め込まれた渋滞メタデータをパースし、`RouteGuidance` のドメインモデルとして公開する。
- OneNavi 側: それを `core/model` の中立モデル（`CongestionSegment`）に詰め替え、`RouteItem` / `RouteDetail` から参照できるようにする（`ExtApiRouteDataSource` で配線する）。

これが完了すれば、将来ナビゲーションや音声案内や各種 UI を実装するときに、必要な情報はルート検索結果から取り出せる状態になっている。

### 0.2 本書がやらないこと（明示的にスコープ外）

OneNavi の現状は **RoutePreview 画面でのルート検索と polyline 表示まで**しか実装されていない。ナビゲーション / turn-by-turn / 音声案内は未実装で、過去に書かれた実装があってもそれは破棄された古いもの。したがって本書では:

- 渋滞 polyline の色分け描画（プレビュー / ナビ中とも）
- 渋滞を 1 直線で示す横帯 UI
- 前方 N 区間の渋滞インジケータ UI
- 渋滞の音声案内（発話タイミング、フレーズ組み立て、発話スケジューラとの調停、TTS エンジン対応）
- ナビ中の渋滞情報リフレッシュ戦略（再検索間隔など）

— これらは **`27_route_congestion_visualization_design.md`**（spec 27）に分離した。本書はあくまで「データ取得口」のみ。データモデルが「4 つの想定ユースケースを将来満たせる」ことの検証は §2 で確認する。

### 0.3 将来の 4 想定ユースケース（モデル設計の指針として）

データモデルが将来これらを賄えるよう、必要な情報の粒度だけ確認しておく:

| # | 想定ユースケース | モデルに必要な情報 |
|---|---|---|
| UC1 | プレビュー / ナビ中の polyline 色分け | route polyline 上の index 範囲 + 渋滞レベル |
| UC2 | 渋滞を 1 直線で示す横帯 UI | ルート始点からの累積距離範囲 + 渋滞レベル |
| UC3 | ナビ中の地図 polyline 色分け | UC1 と同じ（更新は別途。モデルとしては UC1 と同じで足りる） |
| UC4 | 渋滞の音声案内「この先、◯◯ＩＣから△△ＳＡまで約5km渋滞しています。所要時間はおよそ10分です。」 | 渋滞先頭/末尾の地点名・読み・路線番号、渋滞距離、通過所要時間、渋滞レベル、渋滞傾向、渋滞先頭までの距離 |

---

## 1. 渋滞情報の所在

### 1.1 現状

- ルート検索（`mocha/route` JSON / `mocha/mapdealer?submit=dsr` の DSR ZIP）の結果を外部API ライブラリがデコードして公開する `RouteGuidance.polyline` / OneNavi の `RouteItem.geometry` / `RouteDetail.geometry` は **lat/lng の列のみ**で、渋滞情報を持たない。
- `core/model/.../RouteCongestion.kt` に `CongestionSeverity { NORMAL, SLOW, TRAFFIC_JAM, UNKNOWN }` と `CongestionSegment(startPolylinePointIndex, endPolylinePointIndex, severity)` が**既にある**（当初は Google Routes API の `speedReadingIntervals` 用に作られた箱）。
- `RouteItem.congestionSegments: ImmutableList<CongestionSegment>` も**既にある**が、`ExtApiRouteDataSource` は空リストのまま返しており、配線されていない。
- `RouteDetail` には `roadClassSegments`（geometry を道路種別で区切ったセグメント列）という似たパターンが既にある。

### 1.2 渋滞情報は route バイナリに埋め込まれている — RE 完了（2026-05-13）

渋滞情報は **ルート計算時に route バイナリ（`mocha/mapdealer?submit=dsr` が返す DSR ZIP の `GUIDE` エントリ。protobuf）へ埋め込まれた per-guide-point の VICS 由来データ**として取得できる。実機キャプチャと proto デコード（詳細: submodule `plan/12-route-embedded-congestion.md` §実機検証）で以下が確定した。

#### 1.2.1 サーバへ「VICS 入りで返せ」と指示する 2 トークン

参照実装アプリの **「交通情報」トグル ON** 状態は、DSR の `rsp1` パラメータに以下を**両方**含めることに対応する:

```
rsp1=...trans:1.priority:17....traffictime:<yyyyMMddHHmmss JST>.trafficsearch:1
```

これを送らないとサーバは GUIDE バイナリに VICS データを一切埋めない（`vics_info` も `field 12` も空または最小形）。`RouteSearchCriteria.useTrafficInfo: Boolean = true` を追加してライブラリ側が emit する（spec 26 / plan 12 の Phase 1）。

#### 1.2.2 GUIDE バイナリの渋滞構造の所在 = 新設 `field 12`

当初の推定では `vics_info`（field 34）が渋滞構造と思われていたが、実機観察で `vics_info = (a, b, c)` は **施設種別マーカー**（`(1,1)`=IC, `(2,2)`=PA, `(4,4)`=料金所, `(0,10)`/`(0,11)`=端点）であり渋滞情報ではないと判明した。

実際の渋滞構造は **新設 `field 12`** に入る `CongestionEnvelope` で、`field 1`（`CongestionAttrEx`、head/tail 隣接 GP の補助情報）と `field 2`（`TrafficInfo`、§1.2 表の中立名フィールド一覧に対応）を排他に持つ。`TrafficInfo` の中身:

| 中立名 | proto field | 型 | 意味 | 観測例（keiyo-funabashi GP[24]=船橋IC, Jam）|
|---|---|---|---|---|
| `status` | 1 | uint32 | 渋滞レベル (1=Smooth, 2=Crowded, 3=Jam) | `3` |
| `jam_level` | 2 | uint32 | 渋滞密度 (0..14) | `14` |
| `transit_time_sec` | 3 | uint32 | 通過所要時間（秒） | `2190` (= 36分30秒) |
| `distance` | 4 | uint32 | 渋滞区間長（単位 m を想定、要確定） | `290` |
| `distance_from_gp` | 5 | uint32 | この GP から渋滞先頭までの距離 (m) | `0` |
| `head_point` | 6 | GuideNameSjis | 渋滞先頭の地点名 + 読み (SJIS) | `"印西"` |
| `tail_point` | 7 | GuideNameSjis | 渋滞末尾の地点名 + 読み | `"原木"` |
| `head_road` | 8 | GuideNameSjis | 渋滞先頭の路線番号 | `"E14"` (= 京葉道路) |
| `tail_road` | 9 | GuideNameSjis | 渋滞末尾の路線番号 | `"E14"` |
| `is_intermittent` | (未確定) | bool | 断続渋滞含む | (断続ルート未取得) |
| `trend_scale` | (未確定) | uint32 | 渋滞傾向 | (断続ルート未取得) |

これだけの情報が取れれば §0.3 の 4 ユースケースは全部賄える（地点名・読み・路線番号まで取れるのが大きい）。confirmed proto schema は submodule の `plan/12-route-embedded-congestion.md` §0-3 / 実機検証 を正本とする。

### 1.3 方針 — 参照実装アプリと同じく route バイナリ埋め込みデータを使う

route バイナリ埋め込みの渋滞メタデータを外部API ライブラリ側でパースして公開し、OneNavi はそれを使う。

理由:

- ルートとの照合が**構造的に不要**（渋滞データは最初からガイドポイント単位で route に紐づく）。座標近傍マッチや測地系変換が要らない。
- 渋滞先頭/末尾の**地点名・読みが取れる**ので UC4（「◯◯から△△まで」）が将来そのまま実現できる。
- 追加の API 呼び出しが不要（ルート検索のレスポンスに最初から入っている）。
- 1 ソースで UC1〜UC4 全部の素材が揃う。

別案として「渋滞リスト API（`mocha/traffic/list` の area / path-code 版）を叩いて座標近傍でルートに射影する」方式もあるが、地点名の質・精度・実装複雑度（エリアコード解決、測地系変換、座標マッチ）すべてで劣る。**RE が終わるまでの暫定フォールバックとしても作らない**（中途半端な実装と捨てコードになるため）。RE/パーサ（§3 Phase 0/1）が完了するまで `congestionSegments` は常に空配列で、利用側もそれを前提に作る。

注意点（リフレッシュを将来実装する人向けのメモ）: route バイナリの渋滞データは**ルート計算時点のスナップショット**。プレビュー用途では十分だが、ナビ中に陳腐化する。参照実装アプリは別途 (a) 現在地リンク解決 → 渋滞リスト API ポーリング、または (b) ルート付近の渋滞変化検知 → ルート再検索 で更新している。OneNavi で (a) は現在地リンク解決にネイティブのマップマッチが要るため当面困難。(b) 相当（定期再検索）が現実的だが、これも本書のスコープ外。

---

## 2. データモデル設計

### 2.1 外部API ライブラリ側 — 公開ドメイン（インターフェース契約）

ライブラリは以下を満たすこと。詳細実装（protobuf RE 手順、パーサ実装）は submodule 側の `plan/` で別途管理する。本書はインターフェースだけ定義する。

`ext-api/src/main/kotlin/.../guidance/domain/` に追加（命名はライブラリ既存規約に合わせる。下記は提案形）:

```kotlin
/** 渋滞レベル。VICS 準拠（平常 / 混雑 / 渋滞）。 */
enum class CongestionLevel { Smooth, Crowded, Jam, Unknown }

/** 渋滞傾向。 */
enum class CongestionTrend { Stable, Increasing, Decreasing, Intermittent, PartlyIncreasing, PartlyDecreasing, Unknown }

/**
 * route 上の連続した渋滞区間 1 件。
 * route バイナリ（GUIDE）に埋め込まれた VICS 由来データから構築する。
 * 隣接ガイドポイントの渋滞構造を集約して 1 区間にまとめる。
 */
@Immutable
data class RouteCongestionSegment(
    /** route polyline（RouteGuidance.polyline）上の区間開始 index（包含） */
    val polylineStartIndex: Int,
    /** route polyline 上の区間終了 index（包含） */
    val polylineEndIndex: Int,
    /** ルート始点から渋滞先頭までの累積距離（m） */
    val startDistanceFromRouteStartMetres: Int,
    /** ルート始点から渋滞末尾までの累積距離（m） */
    val endDistanceFromRouteStartMetres: Int,
    /** 渋滞区間長（m）。サーバ提供値（polyline から計算した値と必ずしも一致しない）。不明なら 0。 */
    val congestionDistanceMetres: Int,
    /** 渋滞区間の通過予想所要時間（秒）。不明なら null。 */
    val transitTimeSeconds: Int?,
    val level: CongestionLevel,
    val trend: CongestionTrend,
    /** 断続渋滞を含むか */
    val isIntermittent: Boolean,
    /** 渋滞先頭の地点名（例: "◯◯ＩＣ"）。取れなければ null。 */
    val headPointName: String?,
    /** 渋滞先頭の地点名の読み（半角カナまたはひらがな）。null 可。 */
    val headPointKana: String?,
    /** 渋滞先頭の路線番号（例: "C2"）。null 可。 */
    val headRoadNumbering: String?,
    val tailPointName: String?,
    val tailPointKana: String?,
    val tailRoadNumbering: String?,
)
```

`RouteGuidance`（`guidance/domain/Guidance.kt`）に追記:

```kotlin
data class RouteGuidance(
    // ... 既存フィールド ...
    val polyline: ImmutableList<Coord>,
    val congestionSegments: ImmutableList<RouteCongestionSegment> = persistentListOf(),  // 追加
)
```

挙動要件:
- 渋滞構造が無い（ルート計算時点で渋滞ゼロ）route では空配列を返す。
- パース失敗時も例外を投げず空配列（ライブラリの `ApiResult` 規約に従う）。
- **`level == Smooth`（平常流れ）の区間は `congestionSegments` に含めない**。`Crowded` / `Jam`、および「渋滞データはあるがレベルが判別できない」場合の `Unknown` のみを区間として出す。
- **断続渋滞は専用の区間に分けず、通常の渋滞区間の `isIntermittent` フラグに集約する**（参照実装に断続専用の構造体があるが、OneNavi 側はフラグで表現する）。
- `polylineStartIndex` / `polylineEndIndex` は `RouteGuidance.polyline` の index と整合させる（ライブラリ側で polyline 構築と同じ座標系・順序で計算する）。
- 累積距離はライブラリ側で polyline の Haversine 累積から計算してよい（サーバ提供値があればそちら優先）。

（任意・優先度低）`GuidancePoint` 単位で `trafficStatus: IntArray`（前方 N 小区間ステータス）も露出すると、将来「前方 N 区間インジケータ UI」が作れる。本書では必須としない。

### 2.2 OneNavi 側 — 中立モデル（`core/model/.../RouteCongestion.kt`）

既存の `CongestionSeverity` / `CongestionSegment` を**拡張**する。既存の index ベース 3 フィールド（`startPolylinePointIndex`, `endPolylinePointIndex`, `severity`）は将来の overlay 描画系がそのまま使えるよう温存し、距離ベース・地点名・所要時間・傾向を追加する。

```kotlin
/** 渋滞傾向。 */
enum class CongestionTrend { STABLE, INCREASING, DECREASING, INTERMITTENT, UNKNOWN }
// 参照実装の PartlyIncreasing/PartlyDecreasing は当面 INCREASING/DECREASING に丸めて受ける

@Immutable
data class CongestionSegment(
    // --- 既存（変更なし） ---
    val startPolylinePointIndex: Int,
    val endPolylinePointIndex: Int,
    val severity: CongestionSeverity,
    // --- 追加 ---
    /** ルート始点からの累積距離（m）。横帯 UI・音声の「この先 Xkm」用。 */
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    /** サーバ提供の渋滞区間長（m）。0 のときは利用側で endDistanceMeters - startDistanceMeters を使う。 */
    val congestionDistanceMeters: Double,
    /** 渋滞区間の通過予想（分）。null=不明。 */
    val transitMinutes: Int? = null,
    val trend: CongestionTrend = CongestionTrend.UNKNOWN,
    val isIntermittent: Boolean = false,
    /** 渋滞先頭の地点名（"◯◯ＩＣ"）。null=不明。 */
    val headPointName: String? = null,
    val headPointKana: String? = null,
    val headRoadNumbering: String? = null,
    val tailPointName: String? = null,
    val tailPointKana: String? = null,
    val tailRoadNumbering: String? = null,
)
```

- `CongestionLevel → CongestionSeverity` マッピング: `Crowded → SLOW`, `Jam → TRAFFIC_JAM`, `Unknown → UNKNOWN`。`Smooth → NORMAL` も定義上は存在するが、ライブラリ側が `Smooth` 区間を出さないため `congestionSegments` に `NORMAL` は実際には現れない（既存 enum 値は温存）。
- `RouteItem.congestionSegments` は既存。`RouteDetail` にも `congestionSegments: ImmutableList<CongestionSegment> = persistentListOf()` を追加する（`roadClassSegments` と同列。デフォルト値があるので既存の `RouteDetail` 生成箇所は無改修）。両方に持たせるのは、`RouteItem` は UI 表示単位、`RouteDetail` は将来の案内単位という現状の二系統に合わせるため。

### 2.3 配線 — `ExtApiRouteDataSource`

`core/navigation/.../extapi/ExtApiRouteDataSource.kt` で、`RouteGuidance.congestionSegments` を中立モデルに**単純コピー**して `RouteItem` / `RouteDetail` に詰める。index も累積距離もライブラリ側で計算済みのものをそのまま使う（**OneNavi 側で座標マッチや測地系変換は一切しない**）。`headPointKana` 等が空文字なら null に正規化する程度。

```kotlin
private fun RouteCongestionSegment.toModel(): CongestionSegment = CongestionSegment(
    startPolylinePointIndex = polylineStartIndex,
    endPolylinePointIndex = polylineEndIndex,
    severity = level.toSeverity(),
    startDistanceMeters = startDistanceFromRouteStartMetres.toDouble(),
    endDistanceMeters = endDistanceFromRouteStartMetres.toDouble(),
    congestionDistanceMeters = congestionDistanceMetres.toDouble(),
    transitMinutes = transitTimeSeconds?.let { (it + 30) / 60 },
    trend = trend.toModel(),
    isIntermittent = isIntermittent,
    headPointName = headPointName?.ifBlank { null },
    headPointKana = headPointKana?.ifBlank { null },
    headRoadNumbering = headRoadNumbering?.ifBlank { null },
    tailPointName = tailPointName?.ifBlank { null },
    tailPointKana = tailPointKana?.ifBlank { null },
    tailRoadNumbering = tailRoadNumbering?.ifBlank { null },
)
```

これで「取得口」は完成。`RouteItem` / `RouteDetail` を受け取った側は、必要なときに `congestionSegments` を読めばよい。

---

## 3. 実装フェーズ

| Phase | 場所 | 内容 | 状態 |
|---|---|---|---|
| **P0** | ext-api | 渋滞中ルートをキャプチャして `GUIDE` バイナリの渋滞構造を RE。`field 12 = CongestionEnvelope { CongestionAttrEx, TrafficInfo }` を確定 | **完了 (2026-05-13)** |
| **P1** | ext-api | (a) `RouteSearchCriteria.useTrafficInfo: Boolean = true` 追加 + `DsrQueryBuilder` で `traffictime` + `trafficsearch:1` を emit (b) `guide.proto` に確定スキーマ反映 (c) `GuideProtoMapper` に渋滞パース + 隣接 GP 集約実装 (d) fixture テスト (oizumi-choshi / keiyo-funabashi / tokyo-yokohama の DSR ZIP) | 着手前 |
| **P2-a** | OneNavi | `core/model` 拡張（`CongestionSegment` 拡張、`CongestionTrend` 追加、`RouteDetail.congestionSegments` 追加） | **完了** |
| **P2-b** | OneNavi | `ExtApiRouteDataSource` で `RouteGuidance.congestionSegments` を中立モデルに配線 | **完了**（P1 待ちで実データは空） |

P1 完了で OneNavi 側はそのまま実データが流れ込む（追加実装不要）。

UI / ナビゲーション / 音声は **`27_route_congestion_visualization_design.md`** を参照。本書のデータモデルがその素材を提供する。

---

## 4. リスク・未解決事項

| # | 内容 | 対応 |
|---|---|---|
| R1 | 渋滞中ルートのキャプチャが P0 の前提。「いつ・どこが渋滞しているか」依存で再現性が低い | 朝夕の首都高など渋滞確実な区間・時間帯を狙う。複数本キャプチャしてバリエーション（断続あり/なし、head のみ/両方、混雑/渋滞）を網羅 |
| R2 | `props_35` 等が用途マルチユース（GuidePoint ごとに別構造が入る）— 渋滞構造だけ確実に取り出せるか | P0 で「どの GuidePoint に渋滞構造が出るか」「タグ番号 or 内部構造で判別できるか」を実データで確認 |
| R3 | 地点名の読み（kana）が proto に入っているか不明 | P0 で確認。無ければ `headPointKana` 系は常に null（将来の音声実装側で漢字読み or 別手段にフォールバック） |
| R4 | route バイナリの渋滞データはルート計算時点のスナップショット | プレビュー用途では十分。ナビ中のリフレッシュは将来の別タスク（§1.3 のメモ参照） |
| R5 | 断続渋滞専用構造体が別にある | 当面は `isIntermittent` フラグに集約。区間を細切れに分けるのは将来 |
| R6 | `RouteDetail` に `congestionSegments` を足すと生成箇所すべてに影響 | デフォルト値 `persistentListOf()` で既存呼び出しは無改修（`roadClassSegments` の前例どおり） |
| R7 | polyline index と累積距離の整合（ライブラリ側計算） | ライブラリ側で `RouteGuidance.polyline` 構築と同じ座標系・順序で計算すること。fixture テストで index→座標→累積距離の一貫性を検証 |

---

## 5. 関連ファイル

**外部API ライブラリ側（submodule、本書では参照のみ）:**
- `../ext-api/plan/12-route-embedded-congestion.md` — P0/P1 の詳細設計（RE 手順、proto 拡張、`GuideProtoMapper` 実装、fixture 戦略）。本書とペア
- `../ext-api/src/main/proto/.../guide.proto` — P0 で `vics_info` / `props_*` の渋滞構造を追記
- `../ext-api/src/main/kotlin/.../guidance/internal/GuideProtoMapper.kt` — P1 で渋滞パース実装
- `../ext-api/src/main/kotlin/.../guidance/domain/Guidance.kt` — `RouteGuidance.congestionSegments`、`RouteCongestionSegment` / `CongestionLevel` / `CongestionTrend` 追加
- `../ext-api/analysis/` — 渋滞構造のフィールド一覧の出典（中立名は §1.2）

**OneNavi 側（本書のスコープ。P2）:**
- `core/model/src/androidMain/kotlin/me/matsumo/onenavi/core/model/RouteCongestion.kt` — `CongestionSegment` 拡張、`CongestionTrend` 追加
- `core/model/src/androidMain/kotlin/me/matsumo/onenavi/core/model/RouteItem.kt` — `RouteDetail.congestionSegments` 追加（`RouteItem.congestionSegments` は既存）
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/extapi/ExtApiRouteDataSource.kt` — `RouteGuidance.congestionSegments` を中立モデルに詰め替え

**スコープ外（将来、ナビゲーション実装時に別途設計）:** 地図 polyline の渋滞色分け、横帯ストリップ UI、前方 N 区間インジケータ、渋滞音声案内、ナビ中のリフレッシュ。
