# 21. 外部ナビ API GUIDE proto 拡張とマニューバ / 音声発話設計

> **作成日:** 2026-04-23
> **ステータス:** Phase A (proto 拡張 + 調査) 完了 / Phase B (実装) 着手予定
> **対象:** 外部ナビ API ライブラリの GUIDE protobuf から取得できる情報を最大限活用し、OneNavi 側の turn-by-turn UI / TTS 発話スケジュールを改善する
> **関連:** `16_turn_by_turn_navigation_flow.md`, `18_external_nav_api_migration_plan.md`, `19_drive_supporter_api_integration_plan.md`, `20_navigationview_external_route_bridge_investigation.md`

---

## 0. この仕様書の読み方

本書は 2 つの段階に分かれる。

- **Phase A (完了)**: 外部ナビ API ライブラリの GUIDE proto を OneNavi の Kotlin Wire schema
  に回収し、合流左右・推奨レーン・交差点方向の encoding を実データから特定したところまで
- **Phase B (次に実装)**: Phase A の成果を OneNavi の UI / 発話スケジューラに反映する

コンテキストが切れた場合でも、本書と実コード (`../drive-supporter-api/drive-supporter-api/src/main/proto/.../guide.proto`) を読めば作業を再開できるように記述している。

---

## 1. 背景

### 1.1 前提

- ルート探索と turn-by-turn 案内は **外部ナビ API ライブラリ** (別管理のプライベート
  リポジトリに存在。OneNavi ルート直下に git submodule として配置) に委譲している
- ライブラリは外部ナビ API の `mocha/route`, `mocha/mapdealer?submit=dsr` 応答を
  decode し、`Guidance` / `GuidancePoint` / `Intersection` domain model として公開する
- OneNavi (公開 OSS) 側は `Guidance` を受け取って UI (マニューバアイコン / 距離) と
  TTS 発話 (Google Cloud TTS Chirp 3 HD) を生成する

### 1.2 発覚していた問題

`feat/ext-nav-api-integration` ブランチで外部ナビ案内を走らせたところ、以下の不具合が判明。

| # | 症状 | Phase A での解明度 |
|---|---|---|
| 1 | マニューバアイコンが常に「直進」扱い | ✅ encoding 特定、Phase B で実装 |
| 2 | 音声が 500m / 250m / 200m / 100m / 直前 で **5 回**読み上げる | ✅ 原因特定 (native の velocity-tuning が無効化されている分岐をそのまま再生してしまっている)、Phase B で抑制ロジック追加 |
| 3 | 分岐 / 合流 / 推奨レーンの案内アイコンが出ない | ✅ proto から取れる情報を特定、Phase B で `MergeSide` 露出 |

---

## 2. 外部ナビ API が返す案内データの正体

### 2.1 GUIDE バイナリの構造 (v9 guidance format)

`/mocha/mapdealer?submit=dsr` が返す DSR ZIP には `GUIDE` エントリが含まれ、
これが protobuf バイナリ。トップレベル構造:

```
GuideFile
├─ map_data_version
└─ Body
   ├─ RouteInfo            ← GuidePoint[] (物理案内点の列)
   │  ├─ poi_type_labels
   │  └─ GuidePoint[]      ← 本書の主役
   └─ GuideBody            ← 発話案内
      ├─ GuideTemplate[]   ← 案内種別辞書 (39 件)
      ├─ GuideBlock[]      ← 案内ブロック (物理点×前振り距離)
      └─ milestones        ← 距離テンプレ (未使用 bytes)
```

### 2.2 GuidePoint と GuideBlock の関係

- `GuidePoint` は **物理的な案内点** (交差点・分岐・料金所・合流点など)。1 ルートで 100〜800 件。
- `GuideBlock` は **発話ブロック**。API は 1 物理点あたり **複数ブロック** (500m 前振り /
  250m / 200m / 100m / 直前 など) を返す。1 ルートで 200〜1000 ブロック。
- `GuideBlock.range.pos_mid_b` = 物理案内点の位置 (distance-from-destination)。同じ物理点を
  指すブロックは同じ `pos_mid_b` を共有する。
- `pos_mid_b → GuidePoint` の解決は **累積距離の近傍一致** で行う。詳細 §5。

### 2.3 発話 vs 物理点

- **音声フレーズ**は `GuideBlock.announcements[]` に入っており、1 ブロック = 1 発話
  (e.g. "ポーン / まもなく / 右方向です。 / その先 / およそ100mで / 左方向です。")。
- **マニューバ UI 情報**は `GuidePoint` 側に入っている (`GuidePointAttr.angle_in/out`、
  `GuidePointFlags.f3`、`GuidePoint.lane_markers` 等)。
- つまり **「この GP の左右/アイコン」は block ではなく GuidePoint 側から引く** のが正。
  OneNavi 側は block (= `GuidancePoint`) から pos_mid_b で GuidePoint を解決し、
  そこから `ManeuverHint` を合成する。

---

## 3. Phase A 作業内容 (完了済み)

### 3.1 Kotlin Wire proto の拡張

`../drive-supporter-api/drive-supporter-api/src/main/proto/.../guide.proto` を更新し、
以下のフィールドを構造化 or 新規追加した。

#### 追加した構造化フィールド

| 追加した proto field | 用途 | 意味 |
|---|---|---|
| `GuidePoint.flags = 2` | `bytes` → `GuidePointFlags` | IC/JCT/合流などのカテゴリフラグ 4 種 |
| `GuidePoint.toll = 6` | `bytes` → `TollInfo` | 現金普通車料金 (円) |
| `GuidePoint.flags_group = 9` | `bytes` → `FlagsGroup` | 3-tuple 配列。`NTLaneChangeInfo` 系の source 候補 |
| `GuidePoint.special_node = 11` | `bytes` → `SpecialNode` | 橋・フェリー乗場・トンネル通過マーカー |
| `GuidePoint.speed_limit = 27` | 新規 `SpeedLimitInfo` | 制限速度 (4 値) |
| `GuidePoint.lane_markers = 30` | 新規 `LaneMarkers` | 推奨レーン (`NTLaneInfo.isRecommendLane[]` の source) |
| `GuidePoint.vics_info = 34` | 新規 `VicsInfo` | VICS 系 (3 値) |
| `GuidePoint.props_15 = 15` | 新規 `PropertySparse15` | `flag=1, values=[0,0,0]` 定型 |
| `GuidePointAttr.angle_in = 102` | renamed from `unknown_102` | 進入方位 (0-359°) |
| `GuidePointAttr.angle_out = 103` | renamed from `unknown_103` | 退出方位 (0-359°) |
| `GuidePointAttr.unknown_104..108` | 新規 | 未解明 (受信のみ) |
| `GuidePointLabel.road_name_primary = 101` | 新規 `DirectionSign` | 正式路線名 ("首都都心環状線" 等) |
| `GuidePointLabel.road_name_secondary = 102` | 新規 | 予備 (大抵空) |
| `GuidePointLabel.road_number_sign = 103` | 新規 | 路線記号 ("C1"/"E1" 等) |

#### 受信のみ (bytes 維持) のフィールド

構造解析が不十分 or サンプル間で揺れが大きいため bytes として確保だけする:
`coord_alt_1/2/3`, `jartic`, `props_22/23/35/38/40/41`, `sub_path_samples`, `fuel_refs`,
`poi_landmark_a/b/c/14/17`。

### 3.2 domain model 拡張

`../drive-supporter-api/drive-supporter-api/src/main/kotlin/.../guidance/domain/`:

| 新規 / 変更 | 型 | 意味 |
|---|---|---|
| 新規 `ManeuverDirection` | enum (9 値) | 8 方向 + Unknown。`fromAngles(in, out)` で計算 |
| 新規 `LaneInfo` / `LaneMarker` | | 推奨レーン配列 |
| 新規 `SpecialNode` | | 橋・フェリー・トンネル通過マーカー |
| 新規 `SpeedLimit` | | 制限速度 |
| 新規 `FlagsGroupEntry` | | 汎用フラグ配列 (生値) |
| 新規 `ManeuverHint` | | 上記を束ねた「案内地点の補助情報」 |
| 変更 `Intersection` | `approachAngle` 削除 → `angleIn`, `angleOut`, `direction` 追加。`roadNameOfficial`, `roadNumberSign` 追加 | |
| 変更 `GuidancePoint` | `maneuver: ManeuverHint?` 追加 | |

### 3.3 mapper 拡張

`GuideProtoMapper.kt` に `PointPositionLookup` クラスを追加:

- `points[i].attr.cum_distance_m` の累積和で各 `GuidePoint` の **ルート先頭からの m 座標**
  を事前計算
- block.pos_mid_b → `distFromStart = totalMetres - pos_mid_b` → 累積 m の **±10m 許容範囲で
  最近傍** の GuidePoint を引く
- 当該 GuidePoint から `ManeuverHint` を合成

---

## 4. 決定的に特定した encoding (Phase A 成果の正本)

### 4.1 マニューバ方向 (8 値 enum)

**field:** `GuidePointAttr.angle_in` / `angle_out` (どちらも 0-359° 整数)

**導出式:**
```
signedDelta = ((angle_out - angle_in) + 540) % 360 - 180
direction = bucket(abs(signedDelta), sign(signedDelta))
```

**バケット境界 (実データ検証済み、推定値):**

| |signedDelta| | 正 (右折) | 負 (左折) |
|---|---|---|
| < 20° | Straight | Straight |
| 20..45° | SlantRight | SlantLeft |
| 45..135° | Right | Left |
| 135..170° | ThisSideRight | ThisSideLeft |
| > 170° | UTurn | UTurn |

**検証データ (shakuji-tsukuba サンプル):**

| GP index | angle_in → out | signedDelta | 発話 | direction |
|---|---|---|---|---|
| 0 | 16° → 71° | +55° | "右方向です。" (block 0) | Right ✓ |
| 4 | 94° → 2° | −92° | "三原台一丁目 左方向です。" (block 14, anchor coord 完全一致) | Left ✓ |
| 2 | 352° → 92° | +100° | (右系) | Right |
| 3 | 93° → 94° | +1° | (直進) | Straight |
| 7 | 3° → 300° | −63° | (左系) | Left |

**native 側の一致性:**
- `NTNvGuidePointReader.getAngleIn(gpIndex)` / `getAngleOut(gpIndex)` が `angle_in` / `angle_out` と 1:1
- `NTNvGuidePointReader.getGuideDirection(gpIndex)` が native 内で上記の bucket 計算を行い 0-7 enum を返す
- 閾値は native closed-source のため不明だが、実観測で上表がすべて一致

### 4.2 合流の左右 (**Phase B で実装するため最重要**)

**field:** `GuidePointFlags.f3` (message `FlagsInner { a, b, c }`)

**パターン:**

| 発話 | f3.a | f3.b | f3.c | f4 |
|---|---|---|---|---|
| 左からの合流が有ります | 7 | 0 | **1** | **0** |
| 右からの合流が有ります | 7 | 0 | **2** | **1** |

**検証データ:** tokyo-gotemba (1 右 + 14 左) と tokyo-nagoya-hiroshima (3 右 + 21 左)
合計 **39 サンプル**で 100% 一致。

**推定判別ルール (実装方針):**

```kotlin
enum class MergeSide { LEFT, RIGHT }

fun GuidePointFlags?.mergeSide(): MergeSide? {
    val f3 = this?.f3 ?: return null
    // f3.a == 7 が merge-type のマーカー。c で side を決める。
    if (f3.a != 7) return null
    return when (f3.c) {
        1 -> MergeSide.LEFT
        2 -> MergeSide.RIGHT
        else -> null
    }
}
```

`f3.a != 7` のときは合流 GP ではない (他のフラグ用途に流用されている)、と解釈する。
`f4 == 0 / 1` も side と一致するが、`f3.c` のほうが意味論的に明確なので優先。

**注意:** 本判定の出力は「合流系イベントでの左右」を返すものであり、block 側の
発話 category (`GuidanceCategory.Merge` / `MergeAttention`) と合わせて使う。
合流 GP でない GP に対してこの関数を呼んでも null を返す設計。

### 4.3 推奨レーン (**Phase B で実装**)

**field:** `GuidePoint.lane_markers` (message `LaneMarkers { repeated LaneMarker markers, uint32 kind }`)
および `LaneMarker { a, b }`

**検証データ (tokyo-gotemba):**

| GP index | kind | markers (a, b) | 発話コンテキスト |
|---|---|---|---|
| 18 | 3 | [(1,0), (2,0)] | 料金所 2 ゲート (ETC + 現金?) |
| 44 | 1 | [(1,0),(2,0),(2,0),(1,0),(1,0),(2,0),(2,0)] | 東京料金所 7 ゲート |
| 106 | 1 | [(1,0),(2,0),(2,0),(1,0),(1,0)] | 都心環状 5 車線セクション |

**解釈:**
- `LaneMarker.a == 1` → 推奨 / 通過可
- `LaneMarker.a == 2` → 非推奨 / 通過不可 (料金所なら別種別レーン)
- `LaneMarker.b` は常に 0 観測、用途不明
- `LaneMarkers.kind` は 1 (高速分岐) か 3 (料金所) の 2 値観測。高速本線系 vs 料金所で区別している可能性

**native 側との対応:** `NTLaneInfo.isRecommendLane[i] == (marker[i].a == 1)`。

**出現頻度:** 全 GP の 2-3% と非常に疎。**料金所 / 料金所直前分岐** での使用が中心。高速本線上の
「右側 3 車線を」のような発話ブロックでは `lane_markers` は **該当 GP に存在しない**
(= block 側の category = HighwayRecommendedLane で検出し、別経路で処理する必要あり)。

### 4.4 音声発話の native 選択ロジック

**結論:** 外部ナビ API ライブラリは **「全候補ブロックを常に返す」** ライブラリで、速度ベースの
間引きは native closed-source 側で行われる。そのため **OneNavi 側で等価なヒューリスティクス
を自前実装するしかない**。

**根拠 (外部ナビ API 参照実装の逆解析結果):**

1. native `NTNvGuidanceManager.createGuidePhrase()` は **GPS マッチ 1 tick ごと** に呼ばれ、
   戻り値は「今発話すべきフレーズだけ」に絞られている
2. `setVelocityTuningGuidanceEnable(boolean)` という native setter が存在し、これが velocity-tuning
   を on/off する (中身は閉じている)
3. Java 側の `v()` (案内データ再生) は native 戻り値を **無条件 enqueue**。抑制・debounce は native に委譲
4. `velocity * 0.36` で `cm/s → km/h` 換算して native に渡す経路は存在するが、用途は reroute 用
5. `j.java` (TTS プリキャッシュ) は選択ロジックに無関与

**Phase B で実装する抑制ルール:**

各物理 GP (= `GuidancePoint` index) に以下の 2 スロット state を持たせる:

```
state per gp.index:
  prealarmFired: boolean   // 500m / 250m / 200m / 100m のうち 1 つ発話済み?
  immediateFired: boolean  // 直前 (< 60m) 発話済み?
```

**選択規則:**

| 現在の distToGp | 挙動 |
|---|---|
| `> 500m` | 何もしない |
| `≤ 500m` かつ prealarmFired=false | 最も距離の近い **prealarm** phrase を 1 つ発話、`prealarmFired=true` |
| prealarmFired=true | 新しい prealarm は全て抑制 |
| `≤ 60m` かつ immediateFired=false | **immediate** phrase を発話、`immediateFired=true` |

**速度調整 (任意):** LocationSource の `speed` を参照し、

- 速度 > 60 km/h のとき `500m` triggerを優先採用 (高速で「まもなく 500m先」が適切)
- 速度 < 40 km/h のとき `500m` をスキップして `250m` / `200m` から選択 (市街地で 500m 前から読むのは早すぎる)

速度ソースがない / 信頼できない場合は上記スロット state の単純版でフォールバック。

---

## 5. Phase B の実装タスク (これを次にやる)

### Task 1: `MergeSide` 露出

**目的:** 4.2 で特定した `f3.c` を UI 層まで流す。

- `../drive-supporter-api/drive-supporter-api/src/main/kotlin/.../guidance/domain/MergeSide.kt` (新規)
  ```kotlin
  @Immutable
  enum class MergeSide { LEFT, RIGHT }
  ```
- `ManeuverHint` に `mergeSide: MergeSide?` フィールド追加
- `GuideProtoMapper.toManeuverHint()` で `point.flags?.f3` から抽出 (§4.2 の擬似コード参照)
- OneNavi 側 `GuidanceSessionManager.toManeuverInfo` で `guidancePoint.maneuver?.mergeSide` を
  使って `ManeuverModifier` (LEFT / RIGHT) を決定:
  - `Merge` / `MergeAttention` category のとき mergeSide から modifier を設定
  - 他の category では従来通り (intersection の direction から決定)

### Task 2: 交差点方向アイコンの実装

**目的:** `ManeuverDirection` を UI の `ManeuverModifier` に変換してアイコン表示。

- `core/navigation/src/androidMain/kotlin/.../GuidanceSessionManager.kt` の `toManeuverInfo`:
  ```kotlin
  val modifier = guidancePoint.maneuver?.direction?.toManeuverModifier()
      ?: intersection?.direction?.toManeuverModifier()
  ```
- `ManeuverDirection → ManeuverModifier` 変換拡張関数 (core/navigation 内に private 配置):
  | ManeuverDirection | ManeuverModifier |
  |---|---|
  | Straight | STRAIGHT |
  | UTurn | UTURN |
  | Left | LEFT |
  | SlantLeft | SLIGHT_LEFT |
  | ThisSideLeft | SHARP_LEFT |
  | Right | RIGHT |
  | SlantRight | SLIGHT_RIGHT |
  | ThisSideRight | SHARP_RIGHT |
  | Unknown | null (従来どおり「直進」フォールバック) |
- 合流時は Task 1 の `mergeSide` を優先 (merge アイコンの側を決める)

### Task 3: 音声発話の GP-slot 抑制

**目的:** §4.4 の抑制ロジックを実装して 5 連発を止める。

- `core/navigation/src/androidMain/kotlin/.../extnav/ExtNavAnnouncementScheduler.kt` を改修:
  - `SpokenKey` (現行: gpIndex × category × distanceMetres で dedupe) を **GP 単位の 2 スロット
    state** に置き換える
  - `ExtNavProgressSnapshot` から `vehicleSpeedMps` (or `kmh`) を取れるようにする
    (必要なら `ExtNavGuidanceTracker` 側にも速度を持たせる)
  - 選択は §4.4 の「prealarm / immediate」2 スロット方式
  - CRITICAL (WrongWayDriving / WrongEntry / Zone30) は従来通り FLUSH + 即発話、本抑制の対象外

### Task 4: テスト追加

- `GuideProtoMapperTest` に tokyo-gotemba サンプルでの merge side / lane info 検証を追加
  - 既存の `MergeEncodingInvestigation.kt` は disposable 調査スクリプトなので、本番テストでは
    小さな sanity check に絞る
- `ExtNavAnnouncementSchedulerTest` (新規) で「同一 GP に対して prealarm 1 回 + immediate 1 回」
  のみ発火することを検証

---

## 6. 次ラウンドで解決すべき未解明事項

| # | 項目 | 現状 | 次のアプローチ |
|---|---|---|---|
| Q-1 | 「左側/右側の車線が減ります」の proto encoding | `f3` が揺れる (`(31,0,0)` or `(1,2,0)`)。決定的 field 未特定 | 同一 GP で lane_reduction category を持つものを全て洗い出し、flags_group / props_* の差分を比較 |
| Q-2 | 「N 側の車線に お進みください」(推奨レーン誘導) の side 決定 | `lane_markers` は存在するが side 情報は markers 配列の位置から導出 (左寄り多数=左側推奨) | markers の 1/2 分布を端からスキャンして左寄り/中央/右寄りを判定するヒューリスティクスを実装 + サンプル検証 |
| Q-3 | `GuidePointFlags.f3.a` が合流以外で何を示すか | サンプルで `f3.a ∈ {7, 1, 31}` を観測 | 各値が出る GP の共通点 (category / 属性) を分析 |
| Q-4 | `GuidePointAttr.unknown_108` の意味 | ほぼ 0、推奨レーン GP で 3 観測 | 推奨レーン判定の補助 field 候補。lane_markers との相関を調査 |
| Q-5 | 速度ソース | LocationSource.speed が信頼できるか | 実機ログで GPS speed と車速が合うか検証 |

---

## 7. 参照コード / ファイル地図

Phase B 着手時に最初に読むべき順:

1. **proto 正本**: `../drive-supporter-api/drive-supporter-api/src/main/proto/me/matsumo/drive/supporter/api/guidance/guide.proto`
2. **domain model**: `../drive-supporter-api/drive-supporter-api/src/main/kotlin/me/matsumo/drive/supporter/api/guidance/domain/` 配下
   - `ManeuverDirection.kt` (§4.1 の実装)
   - `ManeuverHint.kt` (GuidancePoint が持つ補助情報)
   - `Intersection.kt` (angleIn/out 済み)
   - `GuidancePoint.kt` (maneuver: ManeuverHint? 済み)
3. **mapper**: `../drive-supporter-api/drive-supporter-api/src/main/kotlin/me/matsumo/drive/supporter/api/guidance/internal/GuideProtoMapper.kt`
   - `toIntersection` / `toManeuverHint` / `PointPositionLookup` が抽出ロジックの正本
4. **OneNavi 側 UI / 発話**:
   - `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/GuidanceSessionManager.kt` (`toManeuverInfo` で UI 向け `ManeuverInfo` を組む)
   - `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavAnnouncementScheduler.kt` (発話スケジューラ)
   - `core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/extnav/ExtNavGuidanceTracker.kt` (進捗 snapshot)
   - `core/model/src/commonMain/kotlin/me/matsumo/onenavi/core/model/ManeuverModifier.kt` (UI 側 enum)
   - `core/ui/src/commonMain/kotlin/me/matsumo/onenavi/core/ui/navigation/ManeuverIcon.kt` (アイコンマッピング)
5. **調査スクリプト (disposable)**: `../drive-supporter-api/drive-supporter-api/src/test/kotlin/me/matsumo/drive/supporter/api/guidance/MergeEncodingInvestigation.kt`
   - `@Ignore` を外して `./gradlew :drive-supporter-api:drive-supporter-api:testDebugUnitTest --tests "*MergeEncodingInvestigation*"` で実行
   - 出力は `build/test-results/testDebugUnitTest/TEST-*MergeEncodingInvestigation.xml` の `system-out`

---

## 8. 付録: 実データ抜粋

### 8.1 shakuji-tsukuba 先頭 5 GP の attr

```
GP0: kind=1  u101=4 angle_in=16  angle_out=71  cum_distance_m=99   → Right turn (+55°)
GP1: kind=2  u101=3 angle_in=71  angle_out=350 cum_distance_m=53   → Left turn (-81°)
GP2: kind=4  u101=4 angle_in=352 angle_out=92  cum_distance_m=145  → Right turn (+100°)
GP3: kind=7  u101=3 angle_in=93  angle_out=94  cum_distance_m=107  → Straight (+1°)
GP4: kind=14 u101=4 angle_in=94  angle_out=2   cum_distance_m=242  → Left turn (-92°)  ← 三原台一丁目
```

### 8.2 合流 GP の flags 実データ (tokyo-nagoya-hiroshima 抜粋)

```
# 左合流 (21 サンプル)
block=70  side=LEFT  f3=(a=7,b=0,c=1) f4=0
block=81  side=LEFT  f3=(a=7,b=0,c=1) f4=0
block=87  side=LEFT  f3=(a=7,b=0,c=1) f4=0
...

# 右合流 (3 サンプル)
block=82  side=RIGHT f3=(a=7,b=0,c=2) f4=1
block=345 side=RIGHT f3=(a=7,b=0,c=2) f4=1
block=346 side=RIGHT f3=(a=7,b=0,c=2) f4=1
```

### 8.3 推奨レーン GP の lane_markers (tokyo-gotemba)

```
GP 18 (pos=2634m):   kind=3 markers=[(1,0),(2,0)]                          ← 料金所 2 ゲート
GP 44 (pos=22068m):  kind=1 markers=[(1,0),(2,0),(2,0),(1,0),(1,0),(2,0),(2,0)]  ← 東京料金所 7 ゲート
GP 106 (pos=99064m): kind=1 markers=[(1,0),(2,0),(2,0),(1,0),(1,0)]        ← 都心環状分岐
```

### 8.4 音声 5 連発のログ (Phase A 前)

```
gp=0 cat=1 trigger=500m distToGp=249m "ポーン 右方向です。"
gp=0 cat=1 trigger=250m distToGp=249m "ポーン まもなく 右方向です。 その先 およそ200mで 左方向です。 ..."
gp=0 cat=2 trigger=200m distToGp=208m "ポーン およそ200m先 右方向です。 その先 ..."
gp=0 cat=2 trigger=100m distToGp=113m "ポーン およそ100m先 右方向です。 その先 ..."
gp=0 cat=1 trigger=59m  distToGp=62m  "右方向です。"
```

Phase B の Task 3 実装後は 2 発話に減る想定:
- `distToGp=249m` で prealarm (250m trigger)
- `distToGp=62m` で immediate (59m trigger)
- 500m / 200m / 100m は抑制

---

## 9. 決定ログ

- **D-2101 (2026-04-23):** proto 拡張を優先し、未知 encoding 領域も `bytes` として受信だけは
  しておく。bytes のままでも Wire は unknown fields として保持するため、後からスキーマを
  structured 化しても wire 互換。
- **D-2102 (2026-04-23):** `Intersection.approachAngle` (実体は `GuidePointAttr.kind` = GP 連番) は
  誤用だったため削除。`angleIn` / `angleOut` / `direction` に置き換える破壊的変更を実施。
  既存参照は `ExtNavGuidanceTrackerTest` のみだったため修正コスト小。
- **D-2103 (2026-04-23):** 合流の左右は `GuidePointFlags.f3.c` を正本とする。`f4` も一致するが、
  `f3.c` のほうが `(a=7)` + `(c=1|2)` で merge-type かどうかを同時に判定できるため採用。
- **D-2104 (2026-04-23):** TTS 発話抑制は OneNavi 側で実装。native 相当の
  `setVelocityTuningGuidanceEnable` は再実装せず、GP 単位の 2 スロット (prealarm / immediate)
  で近似する。CRITICAL 系 (WrongWayDriving 等) は本抑制の対象外を維持。
- **D-2105 (2026-04-23):** 「左側/右側の車線が減ります」や「N 側の車線に」の encoding は
  Phase A では確定しなかったため **Phase B では保留** (発話テキストと category でのみ対応し、
  アイコンの左右は出さない)。Phase C で追加調査。
