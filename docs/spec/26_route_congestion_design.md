# 26. ルート上の渋滞情報 — データ取得口の設計

> **作成日:** 2026-05-13
> **ステータス:** 設計（実装未着手）
> **対象:** ルート検索結果に「ルートに沿った渋滞区間」の情報を載せ、`RouteItem` / `RouteDetail` から取得できるようにする。地図描画・横帯 UI・ナビ中表示・音声案内といった **利用側 UI / ナビゲーションは本書のスコープ外**。本書が決めるのは「どこから渋滞情報を取り、どんな形で公開するか」だけ。
> **関連:** `19_drive_supporter_api_integration_plan.md`, `21_ext_nav_guide_proto_and_announcement.md`

---

## 0. スコープと前提

### 0.1 本書がやること

ルート検索（外部ナビ API ライブラリ経由）の結果に、**ルートに沿った渋滞区間の列**を含めて返せるようにする。具体的には:

- 外部ナビ API ライブラリ側: route バイナリに埋め込まれた渋滞メタデータをパースし、`RouteGuidance` のドメインモデルとして公開する。
- OneNavi 側: それを `core/model` の中立モデル（`CongestionSegment`）に詰め替え、`RouteItem` / `RouteDetail` から参照できるようにする（`ExtNavRouteDataSource` で配線する）。

これが完了すれば、将来ナビゲーションや音声案内や各種 UI を実装するときに、必要な情報はルート検索結果から取り出せる状態になっている。

### 0.2 本書がやらないこと（明示的にスコープ外）

OneNavi の現状は **RoutePreview 画面でのルート検索と polyline 表示まで**しか実装されていない。ナビゲーション / turn-by-turn / 音声案内は未実装で、過去に書かれた実装があってもそれは破棄された古いもの。したがって本書では:

- 渋滞 polyline の色分け描画（プレビュー / ナビ中とも）
- 渋滞を 1 直線で示す横帯 UI
- 前方 N 区間の渋滞インジケータ UI
- 渋滞の音声案内（発話タイミング、フレーズ組み立て、発話スケジューラとの調停、TTS エンジン対応）
- ナビ中の渋滞情報リフレッシュ戦略（再検索間隔など）

— これらの**仕様・アーキテクチャは決めない**。データモデルだけは「4 つの想定ユースケースを将来満たせる」ことを満たすよう設計する（§2）が、それらをいつ・どう実装するかは別途。**UI への変更は本書では行わない。**

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

- ルート検索（`mocha/route` JSON / `mocha/mapdealer?submit=dsr` の DSR ZIP）の結果を外部ナビ API ライブラリがデコードして公開する `RouteGuidance.polyline` / OneNavi の `RouteItem.geometry` / `RouteDetail.geometry` は **lat/lng の列のみ**で、渋滞情報を持たない。
- `core/model/.../RouteCongestion.kt` に `CongestionSeverity { NORMAL, SLOW, TRAFFIC_JAM, UNKNOWN }` と `CongestionSegment(startPolylinePointIndex, endPolylinePointIndex, severity)` が**既にある**（当初は Google Routes API の `speedReadingIntervals` 用に作られた箱）。
- `RouteItem.congestionSegments: ImmutableList<CongestionSegment>` も**既にある**が、`ExtNavRouteDataSource` は空リストのまま返しており、配線されていない。
- `RouteDetail` には `roadClassSegments`（geometry を道路種別で区切ったセグメント列）という似たパターンが既にある。

### 1.2 渋滞情報は route バイナリに埋め込まれている

外部ナビ API の参照実装アプリ（N 社のナビアプリ）を逆解析した結果、渋滞情報は **ルート計算時に route バイナリ（`mocha/mapdealer?submit=dsr` が返す DSR ZIP の `GUIDE` エントリ。protobuf）へ埋め込まれた per-guide-point の VICS 由来データ**として取得できることが判明している。

参照実装アプリの SDK が route バイナリを読んだ後の in-memory 表現には、ガイドポイント単位で次の渋滞メタデータが含まれる（逆解析で確認したフィールドを中立名に置き換えたもの。出典: 外部ナビ API ライブラリ submodule の `analysis/` 配下）:

| 中立名 | 型 | 意味 |
|---|---|---|
| `trafficStatus` | `int[]`（前方 N 区間ぶん） | ガイドポイント前方の各小区間の渋滞ステータス |
| `congestionLevel` | `int` | 渋滞レベル（平常 / 混雑 / 渋滞） |
| `transitTimeSeconds` | `int` | 渋滞区間の通過所要時間 |
| `congestionDistanceMetres` | `int` | 渋滞区間の長さ |
| `distanceFromGuidePointMetres` | `int` | このガイドポイントから渋滞先頭までの距離 |
| `congestionTrendScale` | `int` | 渋滞傾向（増加 / 減少 / 断続 / 一部増加 / 一部減少） |
| `headPointName` / `headPointKana` | name 構造（漢字 + 読み） | 渋滞先頭の地点名「◯◯ＩＣ」 |
| `headRoadNumbering` | name 構造 | 渋滞先頭の路線番号 |
| `tailPointName` / `tailPointKana` | name 構造 | 渋滞末尾の地点名「△△ＳＡ」 |
| `tailRoadNumbering` | name 構造 | 渋滞末尾の路線番号 |
| `isIntermittent` | `bool` | 断続渋滞を含むか |

加えて「断続渋滞」専用の構造体（同じく head/tail 地点名を持つ）が別に存在する。これだけの情報が取れれば §0.3 の 4 ユースケースは全部賄える（地点名・読みまで取れるのが大きい）。

GUIDE proto の現状の RE 定義（submodule の `analysis/sample/.../protobuf/guide.proto`）には `GuidePoint` に `vics_info`（field 34）と `props_35 / props_38 / props_40 / props_41`（汎用 PropertyBag）があるが、解析に使ったサンプルルートが渋滞中に取られていないため、渋滞構造は最小形しか判明していない。**渋滞中のルートを実機で 1 本以上キャプチャして、これらのフィールドのどれが渋滞構造かを確定する**作業が要る（§3 Phase 0）。

### 1.3 方針 — 参照実装アプリと同じく route バイナリ埋め込みデータを使う

route バイナリ埋め込みの渋滞メタデータを外部ナビ API ライブラリ側でパースして公開し、OneNavi はそれを使う。

理由:

- ルートとの照合が**構造的に不要**（渋滞データは最初からガイドポイント単位で route に紐づく）。座標近傍マッチや測地系変換が要らない。
- 渋滞先頭/末尾の**地点名・読みが取れる**ので UC4（「◯◯から△△まで」）が将来そのまま実現できる。
- 追加の API 呼び出しが不要（ルート検索のレスポンスに最初から入っている）。
- 1 ソースで UC1〜UC4 全部の素材が揃う。

別案として「渋滞リスト API（`mocha/traffic/list` の area / path-code 版）を叩いて座標近傍でルートに射影する」方式もあるが、地点名の質・精度・実装複雑度（エリアコード解決、測地系変換、座標マッチ）すべてで劣る。**RE が終わるまでの暫定フォールバックとしても作らない**（中途半端な実装と捨てコードになるため）。RE/パーサ（§3 Phase 0/1）が完了するまで `congestionSegments` は常に空配列で、利用側もそれを前提に作る。

注意点（リフレッシュを将来実装する人向けのメモ）: route バイナリの渋滞データは**ルート計算時点のスナップショット**。プレビュー用途では十分だが、ナビ中に陳腐化する。参照実装アプリは別途 (a) 現在地リンク解決 → 渋滞リスト API ポーリング、または (b) ルート付近の渋滞変化検知 → ルート再検索 で更新している。OneNavi で (a) は現在地リンク解決にネイティブのマップマッチが要るため当面困難。(b) 相当（定期再検索）が現実的だが、これも本書のスコープ外。

---

## 2. データモデル設計

### 2.1 外部ナビ API ライブラリ側 — 公開ドメイン（インターフェース契約）

ライブラリは以下を満たすこと。詳細実装（protobuf RE 手順、パーサ実装）は submodule 側の `plan/` で別途管理する。本書はインターフェースだけ定義する。

`drive-supporter-api/drive-supporter-api/src/main/kotlin/.../guidance/domain/` に追加（命名はライブラリ既存規約に合わせる。下記は提案形）:

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

### 2.3 配線 — `ExtNavRouteDataSource`

`core/navigation/.../extnav/ExtNavRouteDataSource.kt` で、`RouteGuidance.congestionSegments` を中立モデルに**単純コピー**して `RouteItem` / `RouteDetail` に詰める。index も累積距離もライブラリ側で計算済みのものをそのまま使う（**OneNavi 側で座標マッチや測地系変換は一切しない**）。`headPointKana` 等が空文字なら null に正規化する程度。

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

| Phase | 場所 | 内容 | 完了時 |
|---|---|---|---|
| **P0** | drive-supporter-api | 渋滞中のルートをライブラリの live API 経由で 1 本以上キャプチャ。`GUIDE` バイナリの `vics_info`(field 34) / `props_35/38/40/41` のどれが渋滞構造かを RE し、フィールドマッピングを確定（必要フィールドは §1.2 の中立名一覧から逆算済み）。`guide.proto` を更新。fixture 追加。詳細手順は submodule の `plan/12-route-embedded-congestion.md` | proto デコードが渋滞構造を拾える |
| **P1** | drive-supporter-api | `GuideProtoMapper` に渋滞パース実装。隣接 GuidePoint の渋滞構造を集約 → `RouteCongestionSegment[]`（polyline index / 累積距離付き、`Smooth` 区間は除外、断続はフラグ集約）。`RouteGuidance.congestionSegments` を公開。fixture テスト（渋滞中ルート ZIP） | ライブラリが渋滞区間を返す |
| **P2** | OneNavi | `core/model` 拡張（§2.2: `CongestionSegment` 拡張、`CongestionTrend` 追加、`RouteDetail.congestionSegments` 追加）。`ExtNavRouteDataSource` で配線（§2.3） | ルート検索結果に渋滞区間が載る。`RouteItem` / `RouteDetail` から取れる |

P0 がブロッカー。P0/P1 の詳細設計は submodule 側 `plan/12-route-embedded-congestion.md`。OneNavi 側は P2 の `core/model` 拡張だけなら P0 を待たず先行できる（インターフェースは §2 で確定。`ExtNavRouteDataSource` の配線部分だけ P1 完了後）。

UI / ナビゲーション / 音声は、ナビゲーション機能を実装する段になってから別途設計する（本書のデータモデルがその素材を提供する）。

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

**外部ナビ API ライブラリ側（submodule、本書では参照のみ）:**
- `../drive-supporter-api/plan/12-route-embedded-congestion.md` — P0/P1 の詳細設計（RE 手順、proto 拡張、`GuideProtoMapper` 実装、fixture 戦略）。本書とペア
- `../drive-supporter-api/drive-supporter-api/src/main/proto/.../guide.proto` — P0 で `vics_info` / `props_*` の渋滞構造を追記
- `../drive-supporter-api/drive-supporter-api/src/main/kotlin/.../guidance/internal/GuideProtoMapper.kt` — P1 で渋滞パース実装
- `../drive-supporter-api/drive-supporter-api/src/main/kotlin/.../guidance/domain/Guidance.kt` — `RouteGuidance.congestionSegments`、`RouteCongestionSegment` / `CongestionLevel` / `CongestionTrend` 追加
- `../drive-supporter-api/analysis/` — 渋滞構造のフィールド一覧の出典（中立名は §1.2）

**OneNavi 側（本書のスコープ。P2）:**
- `core/model/src/androidMain/kotlin/me/matsumo/onenavi/core/model/RouteCongestion.kt` — `CongestionSegment` 拡張、`CongestionTrend` 追加
- `core/model/src/androidMain/kotlin/me/matsumo/onenavi/core/model/RouteItem.kt` — `RouteDetail.congestionSegments` 追加（`RouteItem.congestionSegments` は既存）
- `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavRouteDataSource.kt` — `RouteGuidance.congestionSegments` を中立モデルに詰め替え

**スコープ外（将来、ナビゲーション実装時に別途設計）:** 地図 polyline の渋滞色分け、横帯ストリップ UI、前方 N 区間インジケータ、渋滞音声案内、ナビ中のリフレッシュ。
