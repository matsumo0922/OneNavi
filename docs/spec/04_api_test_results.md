# 04. Mapbox Directions API Test Results

## Test Conditions

- **Endpoint**: `GET https://api.mapbox.com/directions/v5/mapbox/driving-traffic/{coordinates}`
- **Origin**: 石神井公園駅 (Shakujii-Koen Station) — `139.6068,35.7435`
- **Destination**: 権現堂公園 (Gongendo Park) — `139.7178,36.0892`
- **Parameters**:
  - `steps=true`
  - `banner_instructions=true`
  - `voice_instructions=true`
  - `annotations=congestion,maxspeed,speed,distance,duration`
  - `overview=full`
  - `geometries=geojson`
  - `language=ja`
- **Date**: 2026-03-28

## Route Summary

| Item | Value |
|---|---|
| Distance | 56,817 m (56.8 km) |
| Duration | 3,659 s (~61 min) |
| Weight Name | auto |
| Steps | 22 |
| Road Segments (annotations) | 941 |
| Intersections with Lane Data | 75 |

## Route Path

石神井公園駅 → 補助第232号線 → 目白通り(東京都道・埼玉県道24号) → 東京外環自動車道 → 川口JCT → 東北自動車道 → 久喜白岡JCT → 圏央道 → 幸手IC → 国道4号 → 日光街道 → 権現堂公園

## Detailed Analysis

### 1. Step Instructions (ターンバイターン)

全 22 ステップ。各ステップに `maneuver` (type + modifier + instruction + location), `name` (道路名), `distance`, `duration`, `voiceInstructions`, `bannerInstructions`, `intersections` が含まれる。

主要なステップの instruction 一覧:

| # | Type | Instruction | Name |
|---|---|---|---|
| 1 | depart | 直進です。 | (unnamed) |
| 2 | turn/left | 左方向です。 | (unnamed) |
| 3 | turn/left | 左方向、補助第232号線を進みます。 | 補助第232号線 |
| 4 | turn/left | 石神井公園駅東を左方向です。 | (unnamed) |
| 5 | turn/left | 三軒寺を左方向です。 | 目白通り; 東京都道・埼玉県道24号練馬所沢線 |
| 6 | fork/left | 分岐を左方向です。 | 目白通り; 東京都道・埼玉県道24号練馬所沢線 |
| 7 | turn/right | 分岐を右方向です。 | 東京外環自動車道 |
| 8 | fork/right | 分岐を右方向です。 | 東京外環自動車道 |
| 9 | on ramp/slight left | 川口JCTを左方向です。 | (unnamed) |
| 10 | fork/left | 分岐を左方向です。 | 東北自動車道 |
| 11 | on ramp/slight left | 久喜白岡JCTを左方向です。 | 圏央道 |
| 12 | fork/right | 右方向、つくば/常磐道方面です。 | 首都圏中央連絡自動車道; 一般国道468号 |
| 13 | off ramp/left | 左方向、幸手方面、幸手IC出口です。 | (unnamed) |
| 14 | turn/left | 幸手I.Cを左方向です。 | 惣新田幸手線旧道 |
| 15 | turn/right | 中三丁目を右方向です。 | 国道4号; 一般国道4号 |
| ... | ... | ... | ... |
| 22 | arrive | 目的地は左側です。 | (unnamed) |

### 2. Voice Instructions (音声案内)

各ステップに 1〜4 個の `voiceInstructions` が含まれる。

**良い点:**
- 交差点名付き案内あり: 「石神井公園駅東を左方向です。」「三軒寺を左方向です。」
- JCT 名付き案内あり: 「川口JCTを左方向です。」「久喜白岡JCTを左方向です。」
- IC 出口の方面名あり: 「左方向、幸手方面出口です。」
- 事前案内あり: 「0.25マイル先、三軒寺を左方向です。」

**問題点:**
- **距離がマイル/フィート表示**: 「1マイル直進です」「300フィート先」「0.25マイル先」
  - `language=ja` でもメートル系にならない
  - `voice_units=metric` パラメータで修正可能か要検証
- **「左方向です」「右方向です」が基本形**: 「左折」「右折」とは言わない
- **信号機案内なし**: 「2つ目の信号を左です」のような案内はない
- **道路名が正式名称**: 「東京都道・埼玉県道24号練馬所沢線」（通称「目白通り」と言うべき）
- **SSML 付き**: `<speak><amazon:effect name="drc"><prosody rate="1.08">...</prosody></amazon:effect></speak>` — Amazon Polly 向けフォーマット

**結論: Mapbox のデフォルト音声テキストは日本のカーナビとしては品質不足。自前テキスト生成が必須。**

### 3. Banner Instructions (バナー案内)

各ステップに `bannerInstructions` が含まれ、`primary` / `secondary` / `sub` の 3 層構造。

**高速道路区間のバナーデータ（実データ）:**

```
川口JCT:
  primary: { text: "川口JCT", type: "turn", modifier: "slight left" }

久喜白岡JCT:
  primary: {
    text: "久喜白岡JCT / C4",
    components: [
      { type: "text", text: "久喜白岡JCT" },
      { type: "delimiter", text: "/" },
      { type: "icon", text: "C4", mapbox_shield: { display_ref: "C4", text_color: "black" } }
    ]
  }

圏央道分岐:
  primary: {
    text: "つくば / 常磐道",
    components: [
      { type: "text", text: "つくば" },
      { type: "text", text: "/" },
      { type: "text", text: "常磐道" }
    ]
  }

幸手IC出口:
  primary: {
    text: "幸手IC / 383",
    components: [
      { type: "text", text: "幸手IC" },
      { type: "delimiter", text: "/" },
      { type: "icon", text: "383", mapbox_shield: { display_ref: "383" } }
    ]
  }
  secondary: { text: "幸手" }
  sub (接近時): {
    components: [
      { type: "lane", active: true, directions: ["straight"], active_direction: "straight" },
      { type: "lane", active: false, directions: ["straight"] }
    ]
  }
```

**評価:**
- JCT/IC 名、方面名、路線番号シールドが取得できる → **高速パネル表示に使える**
- `sub` に車線データが入る（接近時に表示切替） → **車線案内の切替タイミング制御可能**
- `mapbox_shield` で路線番号アイコンの URL 取得可能 → **方面看板の再現に使える**
- `distanceAlongGeometry` で表示開始距離が取得可能 → **案内タイミング制御可能**

### 4. Lane Data (車線案内)

75 交差点で車線データあり。

**パターン 1: 全車線直進（最も多い）**
```json
[
  { "indications": ["straight"], "valid": true, "active": true },
  { "indications": ["straight"], "valid": true, "active": true },
  { "indications": ["straight"], "valid": true, "active": true }
]
```

**パターン 2: 直進と非推奨車線の混在**
```json
[
  { "indications": ["straight"], "valid": true, "active": true },
  { "indications": ["straight"], "valid": true, "active": true },
  { "indications": ["straight"], "valid": false, "active": false }
]
```

**パターン 3: 複数方向の分岐（右左折）**
```json
[
  { "indications": ["left", "straight"], "valid": true, "active": true },
  { "indications": ["right"], "valid": false, "active": false },
  { "indications": ["right"], "valid": false, "active": false }
]
```

**評価:**
- 高速の分岐では active/valid が適切に分かれている
- 一般道でのデータはパターン 1 が多く、複雑な交差点のデータは限定的
- 信号交差点での右折/左折レーン指示は一部の主要交差点でのみ取得可能

### 5. Congestion (渋滞情報)

941 セグメント中:

| Level | Count | Percentage |
|---|---|---|
| low | 733 | 77.9% |
| unknown | 121 | 12.9% |
| moderate | 83 | 8.8% |
| heavy | 4 | 0.4% |

**87.1% のセグメントで渋滞レベルが判明。** 十分な品質。

### 6. Max Speed (制限速度)

941 セグメント中:

| Status | Count | Percentage |
|---|---|---|
| Speed known | 763 | 81.1% |
| Unknown | 178 | 18.9% |

取得された速度値: **30, 40, 50, 60, 70, 80, 100, 120 km/h**

生活道路 (30km/h) から高速道路 (120km/h) まで幅広くカバー。

### 7. Route Geometry

`overview=full` + `geometries=geojson` で全経路の GeoJSON LineString が取得可能。地図上にルートラインを描画するのに十分。

### 8. Traffic Control Devices (信号機・一時停止・踏切)

`intersections[]` に信号機等のフラグが含まれることが判明（[mapbox/mapbox-navigation-ios#3843](https://github.com/mapbox/mapbox-navigation-ios/issues/3843) で v2.9 に実装済み）。

| 項目 | 値 |
|---|---|
| 全 intersection 数 | 282 |
| `traffic_signal: true` | **16** (5.7%) |
| `traffic_signal: null` | 266 |
| `traffic_signal: false` | 0 |

ステップ別の内訳:
- 石神井公園駅東→目白通り（一般道、59 交差点中 5 に信号フラグ）
- 国道 4 号（幹線道路、18 交差点中 5 に信号フラグ）
- 高速道路区間にも 1 件あり（料金所付近の信号?）

**評価:** カバレッジは 5.7% と低いが、`null` は「データなし」であり「信号なし」ではない。主要交差点ではフラグが確認されており、マニューバ地点（実際に曲がる交差点）での信号機案内には実用的。カバレッジ不足時は距離ベースの案内にフォールバックする設計とする。

**重要:** この発見により、信号機案内のために OSM Overpass API をアプリに搭載する必要がなくなった。

## Known Issues / TODO

1. **`voice_units=metric` パラメータのテスト**: マイル表記問題を解決できるか確認
2. **`alternatives=true` のテスト**: 代替ルートのデータ構造確認
3. **二輪車ルーティング**: Mapbox に motorcycle プロファイルがないため代替策の検討
4. **一般道の車線案内**: 主要交差点以外の車線データ不足への対応
5. **信号機フラグのカバレッジ**: より多くのルートで `traffic_signal` のカバー率を検証
