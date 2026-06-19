# 30. 外部API GUIDE 車線案内 encoding 調査

> **作成日:** 2026-05-27
> **ステータス:** 調査完了 / 実装前
> **対象:** 外部API ライブラリの GUIDE protobuf から、一般道交差点・高速入口の車線案内を復元する
> **関連:** `18_external_api_migration_plan.md`, `21_ext_api_guide_proto_and_announcement.md`

---

## 0. このドキュメントの目的

外部API のレスポンス ZIP には `ROUTE` と `GUIDE` の 2 種類の protobuf が含まれる。
このうち、一般道交差点や高速入口で表示される「直進 / 直進 / 右折」のような
**車線図**は、`ROUTE` ではなく `GUIDE` 側の `GuidePoint.flags_group` から復元できる。

本書は、石神井 → 筑波大学サンプルで確認した次の 2 ケースを正本として記録する。

- 大泉IC入口: `直進 / 直進 / 右折(target)`
- 学園の森: `左折+直進 / 直進 / 右折(target)`

その後、保存済み 12 GUIDE payload を横断スキャンし、`flags_group` を持つ 404 GP、
`lane_markers` を持つ 41 GP、`props_22` を持つ 251 GP を確認した。
本書にはその追加結果も反映している。

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

- 外部API の参照実装アプリの表示では、レーン画像の強調に相当するのは
  `target lane` 側の概念。
- `isRecommendLane` 相当の情報は Java/Kotlin UI 層では主表示にほぼ使われず、
  実際の強調表示は `isTargetLane` 相当で行われている。
- 横断スキャンでは `field_2 == 1` が複数レーンに立つ GP が多数ある。
  これは「唯一の推奨レーン」ではなく「走行対象として強調するレーン群」と読むのが自然。
- OneNavi 側では「推奨」と UI 表記してもよいが、domain model のフィールド名は
  `isTarget` にしておくほうが誤読が少ない。

### 1.3 `field_1`

`field_1 == 1` は付加レーン / 分岐レーン / 側方レーンを示す `append lane` フラグと見る。
推奨判定には使わない。

```text
entry.field_1 == 1 -> append / side lane hint
entry.field_1 == 0 or absent -> normal lane
```

観測上、右左折側の外側レーンで立ちやすい。ただし `field_2 == 1` と常に一致する
わけではない。横断スキャンでは `a=1,b=0` が 221 件、`a=1,b=1` が 31 件あり、
`isTarget` とは別プロパティとして扱う必要がある。

---

## 2. レーン方向 encoding

### 2.1 compact lane code

`entry.field_3` は compact lane direction code。1 レーンが複数方向を持つ場合、
同じ field number `3` が複数回出る。

横断スキャンと既知の lane direction 定数を突き合わせると、次の変換で説明できる。

```text
laneDirectionBit = 1 shl (field_3 - 1)
```

| `field_3` | bit 値 | 復元方向 | 備考 |
|---:|---:|---|---|
| `1` | `1` | SlantLeft | 未観測だが変換式上は斜め左 |
| `2` | `2` | Left | 左折 |
| `3` | `4` | ThisSideLeft | 手前左。2 件観測 |
| `4` | `8` | Straight | 直進 |
| `5` | `16` | SlantRight | 斜め右。4 件観測 |
| `6` | `32` | Right | 右折 |
| `7` | `64` | ThisSideRight | 手前右。1 件観測 |
| `8` | `128` | UTurn | 未観測だが変換式上は U ターン |

1 レーンが複数方向を持つ場合は、複数の compact code を bit OR した値が通常の
lane direction 定数に相当する。

| `field_3` | bit OR | 復元方向 |
|---|---:|---|
| `[2, 4]` | `10` | Left + Straight |
| `[4, 6]` | `40` | Straight + Right |
| `[2, 6]` | `34` | Left + Right |
| `[4, 5]` | `24` | Straight + SlantRight |

つまり `flags_group` 内では直進が `4` として現れるが、既存の lane icon mapper へ
渡す場合は `1 shl (4 - 1) = 8` へ変換する。

### 2.2 proto 定義上の注意

現在の `FlagsGroupEntry.c` が `uint32` 単数の場合、`[2, 4]` のような複数方向を
正しく保持できない可能性がある。実装時は field `3` を repeated として受ける。

```proto
message FlagsGroupEntry {
  uint32 a = 1; // append / side lane hint
  uint32 b = 2; // target lane hint
  repeated uint32 compact_directions = 3 [packed = false];
}
```

wire 上は同じ field number `3` の unpacked varint 列なので、既存バイナリとは互換。
Kotlin 側では `compactDirections: List<Int>` として扱い、domain へ写す段階で
`1 shl (compact - 1)` または `LaneDirection` enum に変換する。

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
- 横断スキャンでは `props_22.field_1.field_1` が `20/30/40/50/60/70/80/100/120`
  に分布し、速度制限値として自然な値だけを取った。
- `props_22` と `flags_group` は 18 GP で同時に存在したため、相互排他ではない。
  ただし内容は独立しており、車線方向や target lane の復元には使わない。

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

横断スキャンでも、`flags_group` を持つ 404 GP と `lane_markers` を持つ 41 GP の
重複は 0 件だった。実装上は次の 2 系統を分ける。

| source | 主な用途 | domain |
|---|---|---|
| `flags_group.entries[]` | 一般道交差点 / 高速入口の lane direction + target | `LaneGuidance` |
| `lane_markers.markers[]` | 料金所・高速上のゲート/レーン情報 | `TollGateLaneInfo` または別型 |

既存 `LaneInfo` が `lane_markers` 専用の意味を持っている場合、`flags_group` 由来の
車線図を同じ型に混ぜると意味が曖昧になる。別 domain type を追加し、UI 描画層で
共通の render model へ寄せる。

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
    val laneDirectionMask: Int,
    val isTarget: Boolean,
    val isAppend: Boolean,
)

enum class LaneDirection {
    SlantLeft,
    Left,
    ThisSideLeft,
    Straight,
    SlantRight,
    Right,
    ThisSideRight,
    UTurn,
    Unknown,
}
```

`directions` は複数方向を表すため `Set` にする。既存の lane icon mapper が通常の
lane direction bit 値を要求する場合に備え、`laneDirectionMask` も保持する。
表示時は `Left + Straight` / `Straight + Right` の組み合わせを専用アイコンに写像する。

### 6.2 mapper 案

```kotlin
private fun FlagsGroupEntry.toLaneGuidanceLane(): LaneGuidanceLane =
    LaneGuidanceLane(
        directions = compactDirections
            .map { raw -> raw.toLaneDirection() }
            .toImmutableSet(),
        laneDirectionMask = compactDirections
            .map { raw -> raw.toLaneDirectionBit() }
            .fold(0) { acc, bit -> acc or bit },
        isTarget = b == 1,
        isAppend = a == 1,
    )

private fun Int.toLaneDirection(): LaneDirection =
    when (this) {
        1 -> LaneDirection.SlantLeft
        2 -> LaneDirection.Left
        3 -> LaneDirection.ThisSideLeft
        4 -> LaneDirection.Straight
        5 -> LaneDirection.SlantRight
        6 -> LaneDirection.Right
        7 -> LaneDirection.ThisSideRight
        8 -> LaneDirection.UTurn
        else -> LaneDirection.Unknown
    }

private fun Int.toLaneDirectionBit(): Int =
    if (this in 1..8) {
        1 shl (this - 1)
    } else {
        0
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

### 7.5 compact code 3 / 5 / 7

fixture: 保存済み GUIDE payload 横断

期待値:

```text
field_3=3 -> ThisSideLeft, laneDirectionMask=4
field_3=5 -> SlantRight, laneDirectionMask=16
field_3=7 -> ThisSideRight, laneDirectionMask=64
field_3=[4,5] -> {Straight, SlantRight}, laneDirectionMask=24
```

確認済みの代表例:

| sample | point | raw | 復元 |
|---|---:|---|---|
| keiyo-funabashi | 6 | `3A / 4 / 6TA` | `ThisSideLeft(append) / Straight / Right(target, append)` |
| oizumi-choshi | 160 | `2+4T / 4T / 5A` | `Left+Straight(target) / Straight(target) / SlantRight(append)` |
| tokyo-gotemba multi route3 | 312 | `4+5A / 6T` | `Straight+SlantRight(append) / Right(target)` |
| tokyo-nagoya-hiroshima | 318 | `4T / 4 / 6 / 7` | `Straight(target) / Straight / Right / ThisSideRight` |

---

## 8. 横断調査で解消した点

保存済み 12 GUIDE payload を対象に、`flags_group` を持つ 404 GP を集計した。

| 項目 | 結論 |
|---|---|
| `field_3 = 5` | `SlantRight`。`1 shl (5 - 1) = 16` |
| `field_3 = 7` | `ThisSideRight`。`1 shl (7 - 1) = 64` |
| `field_3 = 3` | 追加で観測。`ThisSideLeft`。`1 shl (3 - 1) = 4` |
| `field_1 == 1` | `append lane` として扱う。target 判定とは独立 |
| `field_2 == 1` | `target lane` として扱う。複数レーンに立つ場合がある |
| `lane_markers` との統合 | domain では分ける。`flags_group` 404 GP と `lane_markers` 41 GP の重複は 0 件 |
| `props_22` | 速度区間情報。`20/30/40/50/60/70/80/100/120` に分布し、車線復元には使わない |

残る観測ギャップは `field_3 = 1` と `8` が今回の保存済みサンプルでは未出現なこと。
ただし変換式上はそれぞれ `SlantLeft` と `UTurn` に対応するため、実装では先に対応しておく。

---

## 9. 次アクション

1. `FlagsGroupEntry.c` を repeated compact directions として受けられるよう proto を修正する。
2. `LaneGuidance` / `LaneGuidanceLane` / `LaneDirection` を domain に追加する。
3. `GuideProtoMapper.toManeuverHint()` で `flags_group` 由来の `laneGuidance` を詰める。
4. §7 の fixture tests を追加する。
5. Navigation UI で `laneGuidance` を表示する。`isTarget` を強調、`isAppend` を補助スタイルに使う。
