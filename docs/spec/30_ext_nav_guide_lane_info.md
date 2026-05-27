# 30. 外部ナビ API GUIDE 車線案内 encoding 調査

> **作成日:** 2026-05-27
> **ステータス:** 調査完了 / 実装前
> **対象:** 外部ナビ API ライブラリの GUIDE protobuf から、一般道交差点・高速入口の車線案内を復元する
> **関連:** `18_external_nav_api_migration_plan.md`, `21_ext_nav_guide_proto_and_announcement.md`

---

## 0. このドキュメントの目的

外部ナビ API のレスポンス ZIP には `ROUTE` と `GUIDE` の 2 種類の protobuf が含まれる。
このうち、一般道交差点や高速入口で表示される「直進 / 直進 / 右折」のような
**車線図**は、`ROUTE` ではなく `GUIDE` 側の `GuidePoint.flags_group` から復元できる。

本書は、石神井 → 筑波大学サンプルで確認した次の 2 ケースを正本として記録する。

- 大泉IC入口: `直進 / 直進 / 右折(target)`
- 学園の森: `左折+直進 / 直進 / 右折(target)`

また、調査初期に候補と見ていた `props_22 = {40, 560, 0}` は車線ではなく
速度区間情報と判断したため、その理由も明記する。

---

## 1. 結論

### 1.1 車線図の本体

一般道交差点 / 高速入口の車線図は以下の field に入る。

```text
GuideFile.body.route_info.points[].flags_group.entries[]

protobuf path:
101.1.200.9.1
```

analysis JSON 上では以下の形で見える。

```json
"flags_group": {
  "field_1": [
    { "field_1": 0, "field_3": 4 },
    { "field_1": 0, "field_3": 4 },
    { "field_1": 1, "field_2": 1, "field_3": 6 }
  ]
}
```

`flags_group.field_1[]` の 1 要素が **左から順に 1 レーン**に対応する。

### 1.2 推奨 / 強調レーン

推奨またはターゲットとして強調されるレーンは、各 entry の `field_2 == 1` で判定する。

```text
entry.field_2 == 1 -> target lane
entry.field_2 absent or 0 -> normal lane
```

命名は `recommended` より `target` / `highlighted` を優先する。

理由:

- 外部ナビ API の参照実装アプリの表示では、レーン画像の強調に相当するのは
  `target lane` 側の概念。
- `isRecommendLane` 相当の情報は Java/Kotlin UI 層では主表示にほぼ使われず、
  実際の強調表示は `isTargetLane` 相当で行われている。
- OneNavi 側では「推奨」と UI 表記してもよいが、domain model のフィールド名は
  `isTarget` にしておくほうが誤読が少ない。

### 1.3 `field_1`

`field_1 == 1` は付加レーン / 分岐レーン / 側方レーンの補助フラグと見られる。
推奨判定には使わない。

```text
entry.field_1 == 1 -> append / side lane hint
entry.field_1 == 0 or absent -> normal lane
```

観測上、右左折側の外側レーンで立ちやすい。ただし `field_2 == 1` と常に一致する
わけではないため、`isTarget` とは別プロパティとして扱う。

---

## 2. レーン方向 encoding

### 2.1 compact lane code

`entry.field_3` は compact lane direction code。1 レーンが複数方向を持つ場合、
同じ field number `3` が複数回出る。

| `field_3` | 復元方向 | 備考 |
|---:|---|---|
| `2` | Left | 左折 |
| `4` | Straight | 直進 |
| `6` | Right | 右折 |
| `[2, 4]` | Left + Straight | 左折+直進 |
| `[4, 6]` | Straight + Right | 直進+右折 |
| `[2, 6]` | Left + Right | 左折+右折。サンプル数少 |
| `5`, `7` | Unknown | 極少観測。現時点では unknown 扱い |

これは既知の lane direction 定数とは別 encoding である。
例えば通常の車線方向定数では `STRAIGHT = 8`, `RIGHT = 32`, `STRAIGHT_RIGHT = 40`
に相当する組み合わせでも、`flags_group` 内では直進が `4`、右折が `6` になる。

### 2.2 proto 定義上の注意

現在の `FlagsGroupEntry.c` が `uint32` 単数の場合、`[2, 4]` のような複数方向を
正しく保持できない可能性がある。実装時は field `3` を repeated として受ける。

```proto
message FlagsGroupEntry {
  uint32 a = 1; // append / side lane hint
  uint32 b = 2; // target lane hint
  repeated uint32 directions = 3 [packed = false];
}
```

wire 上は同じ field number `3` の unpacked varint 列なので、既存バイナリとは互換。
Kotlin 側では `directions: List<Int>` として扱う。

---

## 3. 検証ケース

### 3.1 大泉IC入口

画面上の案内は `大泉IC` だが、車線図は `GuidePoint[13]` の `flags_group` にある。
直後の `GuidePoint[14]` は `大泉インター入口` で `props_22` を持つが、これは車線図ではない。

```text
GuidePoint[13]
name      = 大泉ＩＣ
road      = 都道２４号線
angle_in  = 289
angle_out = 7
```

raw:

```json
[
  { "field_1": 0, "field_3": 4 },
  { "field_1": 0, "field_3": 4 },
  { "field_1": 1, "field_2": 1, "field_3": 6 }
]
```

復元:

| lane index | direction | isTarget | isAppend |
|---:|---|---:|---:|
| 0 | Straight | false | false |
| 1 | Straight | false | false |
| 2 | Right | true | true |

表示:

```text
直進 / 直進 / 右折(target)
```

### 3.2 学園の森

石神井 → 筑波大学サンプルは `学園の森` 交差点を通過する。

```text
GuidePoint[138]
name      = 学園の森
road      = 県道１９号線
angle_in  = 16
angle_out = 105
```

raw:

```json
[
  { "field_1": 0, "field_3": [2, 4] },
  { "field_1": 0, "field_3": 4 },
  { "field_1": 1, "field_2": 1, "field_3": 6 }
]
```

復元:

| lane index | direction | isTarget | isAppend |
|---:|---|---:|---:|
| 0 | Left + Straight | false | false |
| 1 | Straight | false | false |
| 2 | Right | true | true |

表示:

```text
左折+直進 / 直進 / 右折(target)
```

該当発話 block も `GuidePoint[138]` に一致する。

| block | trigger | 主な発話 |
|---:|---:|---|
| 159 | 2000m | `学園の森` / `右方向です。` |
| 161 | 1000m | `学園の森` / `右方向です。` |
| 162 | 500m | `学園の森` / `右方向です。` / `県道２４号線に入ります。` |
| 163-167 | 250m..100m | `右方向です。` |
| 168-171 | 110m..80m | `この信号を` / `右方向です。` |

### 3.3 近傍: 学園の森中央

近傍の `学園の森中央` は別交差点として存在する。ここも車線図を持つ。

```text
GuidePoint[137]
name = 学園の森中央
```

raw:

```json
[
  { "field_1": 0, "field_3": [2, 4] },
  { "field_1": 0, "field_2": 1, "field_3": 4 },
  { "field_1": 1, "field_3": 6 }
]
```

復元:

```text
左折+直進 / 直進(target) / 右折(append)
```

`field_1 == 1` と `field_2 == 1` が独立していることを示す良い反例。
したがって、推奨判定に `field_1` を使ってはいけない。

---

## 4. `props_22 = {40, 560, 0}` は何か

大泉IC入口の直後 `GuidePoint[14]` には以下の `props_22` がある。

```json
"props_22": {
  "field_1": {
    "field_1": 40,
    "field_2": 560,
    "field_3": 0
  }
}
```

調査初期は `40` が `STRAIGHT_RIGHT` 相当、`560` が lane payload の可能性を疑った。
しかし、他サンプルを含めて分布を見ると、これは車線情報ではなく速度区間情報と見るほうが自然。

推定:

```text
field_1 = speed limit km/h
field_2 = section length metres
field_3 = auxiliary value
```

根拠:

- `field_1` は `30/40/50/60/70/80/100/120` のような速度値として自然な値を取る。
- `field_2` は当該 GP からの区間長らしい値を取る。
- `field_1=40, field_2=560` は `40km/h 区間が 560m` と読める。
- 車線図を持つ GP でも `props_22` が無いケースが多い。
- `学園の森` の車線図は `props_22` なしで `flags_group` だけから復元できる。

したがって、`props_22` は車線実装の入力に使わない。

---

## 5. `lane_markers` との関係

既存の `GuidePoint.lane_markers` (`field 30`) は、料金所・高速道路上の一部 GP で
観測される別系統の車線情報。

例:

- 大泉料金所
- 三郷料金所
- 御殿場料金所

一方、大泉IC入口や学園の森のような一般道交差点 / 高速入口の車線図は
`lane_markers` ではなく `flags_group` に入る。

実装上は次の 2 系統を分ける。

| source | 主な用途 | domain |
|---|---|---|
| `flags_group.entries[]` | 一般道交差点 / 高速入口の lane direction + target | `LaneGuidance` |
| `lane_markers.markers[]` | 料金所・高速上のゲート/レーン情報 | `TollGateLaneInfo` または別型 |

既存 `LaneInfo` が `lane_markers` 専用の意味を持っている場合、`flags_group` 由来の
車線図を同じ型に混ぜると意味が曖昧になる。別 domain type を追加するほうが安全。

---

## 6. 実装方針

### 6.1 domain model 案

```kotlin
@Immutable
data class LaneGuidance(
    val lanes: ImmutableList<LaneGuidanceLane>,
)

@Immutable
data class LaneGuidanceLane(
    val directions: ImmutableSet<LaneDirection>,
    val isTarget: Boolean,
    val isAppend: Boolean,
)

enum class LaneDirection {
    Left,
    Straight,
    Right,
    Unknown,
}
```

`directions` は複数方向を表すため `Set` にする。
表示時は `Left + Straight` / `Straight + Right` の組み合わせを専用アイコンに写像する。

### 6.2 mapper 案

```kotlin
private fun FlagsGroupEntry.toLaneGuidanceLane(): LaneGuidanceLane =
    LaneGuidanceLane(
        directions = directions
            .map { raw -> raw.toLaneDirection() }
            .toImmutableSet(),
        isTarget = b == 1,
        isAppend = a == 1,
    )

private fun Int.toLaneDirection(): LaneDirection =
    when (this) {
        2 -> LaneDirection.Left
        4 -> LaneDirection.Straight
        6 -> LaneDirection.Right
        else -> LaneDirection.Unknown
    }
```

`flags_group` は車線以外の用途にも使われる可能性があるため、最低限の guard を置く。

```kotlin
private fun FlagsGroup.toLaneGuidanceOrNull(): LaneGuidance? {
    val lanes = entries
        .map { it.toLaneGuidanceLane() }
        .filter { it.directions.isNotEmpty() }

    if (lanes.size < 2) return null
    if (lanes.all { LaneDirection.Unknown in it.directions }) return null

    return LaneGuidance(lanes.toImmutableList())
}
```

### 6.3 推奨判定

```kotlin
val isTarget = entry.b == 1
```

`entry.a == 1` は `isAppend` にのみ使う。

### 6.4 `GuidePoint` と `GuideBlock` の紐付け

車線図は `GuidePoint` 側にあるため、既存の `PointPositionLookup` で
`GuideBlock.range.pos_mid_b` から対象 `GuidePoint` を解決し、`ManeuverHint` に
`laneGuidance` を追加する。

```text
GuideBlock.range.pos_mid_b
  -> distanceFromStart = totalMetres - pos_mid_b
  -> nearest GuidePoint by cumulative distance
  -> GuidePoint.flags_group
  -> LaneGuidance
```

---

## 7. 受け入れテスト候補

### 7.1 大泉IC

fixture: 石神井 → 筑波大学

期待値:

```text
GuidePoint[13].laneGuidance.lanes.size == 3
lanes[0].directions == {Straight}
lanes[1].directions == {Straight}
lanes[2].directions == {Right}
lanes[2].isTarget == true
lanes[2].isAppend == true
```

### 7.2 学園の森

fixture: 石神井 → 筑波大学

期待値:

```text
GuidePoint[138].laneGuidance.lanes.size == 3
lanes[0].directions == {Left, Straight}
lanes[1].directions == {Straight}
lanes[2].directions == {Right}
lanes[2].isTarget == true
lanes[2].isAppend == true
```

### 7.3 学園の森中央

`field_1` と `field_2` を混同しないことを検証する。

期待値:

```text
GuidePoint[137].laneGuidance.lanes.size == 3
lanes[0].directions == {Left, Straight}
lanes[1].directions == {Straight}
lanes[1].isTarget == true
lanes[1].isAppend == false
lanes[2].directions == {Right}
lanes[2].isTarget == false
lanes[2].isAppend == true
```

### 7.4 `props_22` を車線に使わない

期待値:

```text
GuidePoint[14].props_22 == {40, 560, 0}
GuidePoint[14].laneGuidance == null
```

これにより、`40,560` を lane payload と誤認しないことを固定する。

---

## 8. 未解決点

- `field_3 = 5` / `7` の方向意味。観測数が少ないため `Unknown` 扱い。
- `field_1 == 1` の正確な内部名。現時点では `append` / `side` 系の補助フラグと推定。
- `field_2 == 1` が実装内部の `target lane` と完全一致するか。
  外部ナビ API の参照実装アプリの表示とは一致しているが、全サンプルでの直接比較は未実施。
- `lane_markers` と `flags_group` を最終的に同一 UI type へ統合するかどうか。
  domain では分け、UI で共通描画に寄せる方針が安全。

---

## 9. 次アクション

1. `FlagsGroupEntry.c` を repeated directions として受けられるよう proto を修正する。
2. `LaneGuidance` / `LaneGuidanceLane` / `LaneDirection` を domain に追加する。
3. `GuideProtoMapper.toManeuverHint()` で `flags_group` 由来の `laneGuidance` を詰める。
4. §7 の fixture tests を追加する。
5. Navigation UI で `laneGuidance` を表示する。`isTarget` を強調、`isAppend` を補助スタイルに使う。
