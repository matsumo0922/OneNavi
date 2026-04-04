# 03. Technology Evaluation & Selection

## Overview

本ドキュメントでは、OneNavi の各機能に必要な外部サービス・API を評価し、採用/不採用の判断とその理由を記録する。

---

## 1. Map Display (地図表示)

### Evaluated Options

#### Mapbox Maps SDK for Android — **ADOPTED**
- ベクタータイルベースの高品質地図
- Mapbox Studio でスタイル完全カスタマイズ（ダークモード・ナビモード自作可能）
- 3D 建物表示、滑らかなズーム・回転・チルト
- **`Surface` に直接描画可能** — Android Auto の `NavigationTemplate` で必須
- オフラインマップダウンロード対応
- 無料枠: 25,000 MAU/月
- 日本の地図データは Zenrin 提携で高品質

#### Google Maps SDK for Android — **REJECTED**
- 地図表示のみなら高品質だが、**Google Navigation SDK が承認制パートナー限定**
- Maps SDK 単体ではターンバイターン UI なし
- Surface 描画は可能だが、Navigation SDK なしではナビアプリとしての価値が薄い

#### HERE Maps SDK (Navigate Edition) — **NOT SELECTED (alternative)**
- ターンバイターン内蔵、オフライン対応、Junction View あり
- 日本の車線案内データが優秀（Increment P 提携）
- 商用ライセンスが不透明、個人 OSS には向かない可能性

#### osmdroid — **REJECTED**
- 2024年11月にアーカイブ済み（メンテナンス終了）

### Decision
**Mapbox Maps SDK を採用。** カスタマイズ性、Android Auto Surface 対応、Navigation SDK との統合、無料枠の十分さが決め手。

---

## 2. Routing & Navigation (経路探索・ナビゲーション)

### Evaluated Options

#### Mapbox Navigation SDK for Android — **ADOPTED**
- Directions API (`driving-traffic` プロファイル) によるルーティング
- リアルタイム交通情報考慮
- 自動リルート、代替ルート提案（ナビ中も自動探索 + 手動リクエスト可能）
- **ナビセッション中の Directions API 呼び出しは無料**（MAU 課金のみ）
- 車線案内データ (`intersections[].lanes`) あり
- **信号機・一時停止・踏切フラグ** (`intersections[].traffic_signal/stop_sign/railway_crossing`) あり
- バナー案内 (`bannerInstructions`) で JCT/IC 名、方面名、路線番号シールド取得可能
- 音声案内テキスト取得可能（ただし自前生成に差し替える）
- **合流マニューバ** (`merge` type) 対応
- マップマッチング API あり
- **ナビ中の経由地追加**が可能（`setNavigationRoutes()` でセッション再起動不要）
- 車両パラメータ (`max_height`, `max_width`, `max_weight`) 対応
- 時間帯制限付きルーティング (`depart_at` パラメータ) 対応
- 無料枠: 100 MAU / 1,000 トリップ/月

#### NAVITIME Route API (RapidAPI) — **REJECTED**
- ルーティングデータ自体は極めて高品質（VICS 渋滞情報、日本の交通規制完全対応、高速パネル向け SA/PA データ、料金計算）
- **しかし RapidAPI 版の利用規約（第5条）でカーナビ用途が明確に禁止:**
  - 「誘導案内又は音声案内をする機能において利用すること」→ 禁止
  - 「GPS等の位置測位による自動追従に応じて表示された地図が自動でスクロールされる機能において利用すること」→ 禁止
  - 「カーメーカー又はカーナビゲーションメーカーに対して提供されるサービス等において利用すること」→ 禁止
  - 「リアルタイムにユーザーの保有する端末に対して安全運転支援又は注意喚起を行う機能において利用すること」→ 禁止
- 規約 URL: https://api-sdk.navitime.co.jp/api/specs/description/rapid_tou.html
- 直接契約（法人向け）ならナビ用途のライセンスがある可能性はあるが、個人 OSS では不可

#### Valhalla (self-hosted) — **NOT SELECTED (future candidate)**
- OSS (MIT)、オフラインルーティング可能
- motorcycle プロファイルが定義可能（将来の二輪車対応に有用）
- 日本の OSM データ品質が課題

#### GraphHopper — **NOT SELECTED (alternative)**
- Java ベース、motorcycle プロファイルあり
- Valhalla と同様、OSM データ品質が課題

### Decision
**Mapbox Navigation SDK を採用。** Maps SDK との統合、セッション中の無料 API 呼び出し、車線・バナー・信号機データの品質が決め手。NAVITIME は規約違反のため不採用。

---

## 3. Voice Guidance (音声案内)

### テキスト生成と TTS を分離

音声案内は 2 層構造とする:
1. **案内テキスト生成** — 自前（OneNavi のコア差別化要素）
2. **TTS エンジン** — 外部サービス

### 3a. 案内テキスト生成 — **CUSTOM (自前)**

Mapbox の route data から生データを取得し、以下を自前で組み立てる:

- **通称辞書**: 正式名称→通称のマッピング（「東京都道311号環状八号線」→「環八」）
- **信号機案内**: Mapbox `intersections[].traffic_signal` フラグを使い「2つ目の信号を左です」を生成
- **自然な日本語テンプレート**: 「この信号を右折です」「まもなく左方向、○○方面です」
- **合流案内**: `maneuver.type === "merge"` を検出して「合流です。ご注意ください」を生成
- **速度連動タイミング**: GPS 速度に応じて案内距離を調整（30km/h→250m手前、100km/h→800m手前）
- **距離のメートル表記**: Mapbox デフォルトがマイル表記になる問題を回避

Mapbox から取得できるデータ:
| フィールド | 内容 | 用途 |
|---|---|---|
| `maneuver.type` | `turn`, `fork`, `on ramp`, `off ramp`, `merge`, `arrive` | 行動の種類判定 |
| `maneuver.modifier` | `left`, `right`, `slight left`, `sharp right` | 方向判定 |
| `maneuver.bearing_before/after` | 進行角度 | 角度差から右左折の種類を細分化 |
| `bannerInstructions.primary.text` | 交差点名 / JCT 名 / IC 名 | 案内テキストに使用 |
| `bannerInstructions.primary.components` | 方面名、路線番号、シールド情報 | 方面看板テキスト生成 |
| `bannerInstructions.sub.components` | 車線データ（type=lane） | 車線案内テキスト |
| `intersections[].lanes` | 各車線の方向・推奨フラグ | 車線案内 |
| `intersections[].traffic_signal` | 信号機の有無 (Boolean) | 信号機案内の生成 |
| `intersections[].stop_sign` | 一時停止標識の有無 (Boolean) | 一時停止案内 |
| `intersections[].railway_crossing` | 踏切の有無 (Boolean) | 踏切案内 |
| `name` | 道路名 | 通称辞書で変換して使用 |
| `distance` / `duration` | 距離(m) / 時間(s) | 「300メートル先」等 |
| `voiceInstructions[].distanceAlongGeometry` | 案内地点までの距離 | 案内タイミング制御（自前の速度連動ロジックで上書き） |

### 3b. TTS Engine

#### Google Cloud TTS (Chirp 3: HD) — **ADOPTED (primary)**
- 28 種の日本語音声（男 14 / 女 14）、採用ボイス: **Laomedeia**
- ストリーミング API で ~200ms のレイテンシ（ナビ用途に十分）
- 無料枠: **1M 文字/月**（Chirp 3: HD）
- Google Places API と同じ Google Cloud プロジェクト・API キーを共用可能 → OSS ユーザーの設定負担が軽い
- `asia-northeast1`（東京）リージョン対応
- ストリーミング時は SSML 非対応 → プレーンテキスト + pause マークアップで制御

#### Azure Cognitive Services TTS (Dragon HD) — **NOT SELECTED**
- 日本語音声品質は業界最高水準だが、別途 Azure アカウント・API キーが必要
- OSS ユーザーに Google Cloud + Azure の二重セットアップを要求するのは負担が大きい
- 無料枠: 500K 文字/月（Chirp 3: HD の半分）

#### Amazon Polly — **NOT SELECTED**
- 日本語音声の選択肢が少ない（Takumi のみ Neural 対応）

#### Gemini TTS — **NOT SELECTED**
- レイテンシが高すぎリアルタイムナビには不向き（Flash ~200ms / Pro ~450ms + ネットワーク遅延）
- Google 自身がリアルタイム用途には Live API を推奨

#### Android 端末内蔵 TTS — **FALLBACK**
- オフライン時やコスト削減のフォールバック
- 品質は劣るが、遅延なしで動作

### Decision
**Google Cloud TTS (Chirp 3: HD, Laomedeia) を採用し、Android 内蔵 TTS をフォールバックにする。** Google Places API と API キーを共用できるため OSS ユーザーの設定が簡素化される。案内テキストは自前生成し、通称辞書・信号機案内・合流案内・速度連動タイミングをコアの差別化とする。

---

## 4. Traffic Information (渋滞情報)

### Evaluated Options

#### Mapbox Traffic — **ADOPTED**
- `mapbox-traffic-v1` タイルセットで地図上にリアルタイム渋滞を色分け表示
- 約 8 分間隔で更新
- `driving-traffic` プロファイルでルーティングにも反映
- `annotation.congestion` でセグメントごとの渋滞レベル取得（テスト結果: 87% カバー）
- ルートライン上にも渋滞色を反映可能
- Maps SDK に追加するだけで利用可能

#### VICS (Vehicle Information and Communication System) — **NOT AVAILABLE**
- FM 多重放送、5.8GHz ビーコン、光ビーコンなど物理メディアで配信
- **インターネット API なし** — 専用ハードウェア受信機が必要
- ソフトウェアのみの Android アプリからはアクセス不可能

#### Google Traffic / HERE Traffic — **NOT SELECTED**
- Mapbox Traffic で十分なカバレッジが確認された

### Decision
**Mapbox Traffic を採用。** Maps SDK/Navigation SDK との統合が自然で、追加コストなし。

---

## 5. Lane Guidance (車線案内)

### Current State (テスト結果に基づく)

石神井公園駅→権現堂公園のテストルート（56.8km）において:
- 車線データが含まれる交差点: **75 箇所**
- 各車線に `indications` (方向)、`valid` (有効)、`active` (推奨) フラグ
- 右左折分岐で active/non-active が適切に分かれるケースあり
- 高速道路の分岐では車線データ取得可能
- 一般道では `["straight"]` のみのケースが多い

### 車線減少案内

Mapbox に専用の「車線が減ります」マニューバはないが、連続する intersection 間で `lanes` 配列の要素数が減少していることを検出し、自前で案内を生成する方針。

### Decision
**Mapbox Navigation SDK の車線データをメインで使用。** 完全なカバレッジは商用データに依存しており個人 OSS では不可能だが、主要道路・高速道路での車線案内は実現可能。車線減少案内は自前ロジックで対応。

---

## 6. Traffic Control Devices (信号機・一時停止・踏切)

### 発見: Mapbox Directions API は信号機フラグを提供している

`StepIntersection` オブジェクトに以下の Boolean フィールドがある:

| フィールド | 意味 |
|---|---|
| `traffic_signal` | 信号機あり |
| `stop_sign` | 一時停止標識あり |
| `yield_sign` | 譲れ標識あり |
| `railway_crossing` | 踏切あり |

参考: [mapbox/mapbox-navigation-ios#3843](https://github.com/mapbox/mapbox-navigation-ios/issues/3843) — v2.9 で実装・マージ済み。

### テスト結果

| 項目 | 値 |
|---|---|
| 全 intersection 数 | 282 |
| `traffic_signal: true` | **16** (5.7%) |
| `traffic_signal: null` | 266 |
| `traffic_signal: false` | 0 |

`null` は「データなし（不明）」、`true` は「信号あり確認済み」。`false` は出現せず、信号がない交差点を明示的にマークしているわけではない。

### カバレッジの評価

テストルートでは 282 交差点中 16 箇所（5.7%）で信号機フラグが `true`。実際の信号機密度と比べると不足している可能性があるが、以下の理由から実用的:

- **マニューバ地点（右左折する交差点）での信号機フラグが重要**であり、通過するだけの交差点は信号機案内の対象外
- テストデータでは主要交差点で `traffic_signal: true` が確認されている
- Mapbox は AI ベースの道路属性検出を拡充中（Mapbox Blog: "300% More Lane Guidance"）で、今後カバレッジ向上が期待できる

### Decision

**Mapbox の `traffic_signal` フラグをプライマリソースとして採用。OSM Overpass API は信号機取得には使用しない。**

信号機案内の生成ロジック:
1. 次のマニューバまでの intersection を走査
2. `traffic_signal === true` の数をカウント
3. マニューバ地点自体が `traffic_signal === true` であれば「{N}つ目の信号を{方向}です」を生成
4. カバレッジ不足の場合（信号機フラグがない交差点）は従来型の「{距離}先、{方向}です」にフォールバック

---

## 7. Highway Panel (高速パネル)

### Data Requirements

高速パネルには以下のデータが必要:
- IC/JCT/SA/PA の名前、位置、通過順序
- 各地点の推定通過時刻
- 各地点までの距離
- SA/PA の施設情報（ガソリンスタンド、コンビニ、トイレ等）

### Data Sources

**IC/JCT — Mapbox Directions API（十分）:**
- `maneuver.type` が `on ramp`, `off ramp`, `fork` のステップから取得
- `bannerInstructions` で JCT/IC 名、方面名、路線番号シールドが取得可能
- 各 step の `distance` / `duration` の累積で通過時刻・距離を算出

**SA/PA — アプリ同梱の静的データ（OSM から事前抽出）:**
- Mapbox のルートレスポンスには SA/PA がマニューバとして出現しない（通過するだけのため）
- 全国の SA/PA 位置データ（約 900 箇所）を OSM から Overpass API で事前抽出し、JSON としてリポジトリに同梱（約 200KB）
- ルート計算後、ルート形状（GeoJSON）と SA/PA 座標を突合し、経路上の SA/PA を特定
- SA/PA の施設情報（GS、トイレ、コンビニ、レストラン、EV 充電等）も同梱データに含める
- **Overpass API はビルドパイプライン（GitHub Actions）で使用し、アプリ内には搭載しない**

**更新フロー:**
- GitHub Actions で月 1 回 Overpass API を実行し、SA/PA データを更新
- 差分があれば自動 PR を作成
- マージされた更新は次回アプリビルド時に反映

### Android Auto でのパネル表示

**制約: Android Auto のリスト上限は 6 アイテム（ハードリミット、回避不可）。**

| 方法 | 可否 |
|---|---|
| 6 アイテム超のリスト | 不可（ホスト側で強制、超過でクラッシュ） |
| スクロール | 不可 |
| Surface にカスタム UI 描画 | 描画可能だがタッチ不可（インタラクティブなリスト不可） |
| ページネーション（「もっと見る」で次画面遷移） | **可能** |
| TabTemplate（4タブ × 6アイテム = 24） | **可能**（API Level 6+） |

**戦略:**
- Phone 側: 制限なし。全 IC/JCT/SA/PA をフルリスト表示
- Auto 側: 直近 5 件 + 「もっと見る」で次ページに遷移（ページネーション）
- Auto 実行中でもスマホ側でフルパネルが閲覧可能（OneNavi の Auto/Phone 独立動作の利点）

### Decision
**IC/JCT は Mapbox データで十分。SA/PA はリポジトリ同梱の静的 JSON で対応。** Overpass API はビルドパイプライン専用で、アプリのランタイム依存には含めない。

---

## 8. Traffic Regulations (交通規制)

### Mapbox の交通規制対応状況

| 規制の種類 | Mapbox 対応 | ソース | 備考 |
|---|---|---|---|
| 一方通行 | 概ね正確 | OSM + Zenrin | |
| 右左折禁止（常時） | 概ね正確 | OSM | |
| 時間帯制限（朝 7-9 時の左折禁止等） | **対応するがデータ不完全** | OSM `restriction:conditional` | `depart_at` 指定で考慮されるが、日本の住宅地のカバレッジは低い可能性 |
| 速度制限 | テストで 81% カバー | OSM + Zenrin | |
| 車高・車幅・車重制限 | `max_height/width/weight` パラメータで指定可 | OSM | |

### 車両情報の加味

| パラメータ | 対応 | 備考 |
|---|---|---|
| 車高 (`max_height`) | **対応** | 0-10m 指定可能 |
| 車幅 (`max_width`) | **対応** | 0-10m 指定可能 |
| 車重 (`max_weight`) | **対応** | 0-100t 指定可能 |
| バイク排気量 | **非対応** | Mapbox に motorcycle プロファイルなし |
| 二輪車通行禁止 | **非対応** | `exclude=motorway` で高速除外は可能だが、排気量別の制限は不可 |

### Decision
**Mapbox の `depart_at` + 車両パラメータで対応可能な範囲の交通規制は反映する。** 時間帯制限の OSM カバレッジ不足は既知のリスクとして受容し、OSM コミュニティへの貢献で段階的に改善する。二輪車の排気量別ルーティングは Mapbox 単体では不可能であり、将来的に Valhalla 自前ホストで対応を検討する。

---

## 9. Junction View / Intersection Images (交差点拡大図)

### Industry Analysis

商用カーナビの交差点拡大図は主に **事前レンダリング画像データベース** から取得:
- Pioneer/Carrozzeria: Increment P の SiNDY システムで交差点ごとに誘導情報オブジェクトを保持。「データが収録されていない交差点では交差点拡大図は表示しません」と明記
- Zenrin: 方面看板画像・3D オブジェクトをナビデータ生産パイプラインで作成
- HERE SDK: SVG 形式で Junction View を配信（日本の高速道路 JCT 対応あり）
- NDS (Navigation Data Standard): 「Junction View Building Block」で事前画像格納が標準

### Available Options for Individual Developer

| ソース | 日本対応 | コスト | 判定 |
|---|---|---|---|
| Mapbox Junction View | **不明**（「近日公開」状態、アカウント承認必要） | 無料枠内？ | 要問い合わせ |
| Mapbox Signboards | **不明**（SVG 形式、アカウント承認必要） | 無料枠内？ | 要問い合わせ |
| HERE Junction View | 高速 JCT は対応 | 商用ライセンス | 代替候補 |
| プロシージャル生成 | 自前で構築 | 無料 | **ADOPTED (Phase 2)** |

### Decision
**Phase 1 では Mapbox 標準の車線インジケーターのみ。Phase 2 で高速 JCT のプロシージャル生成を検討。** 既存 OSS で使えるレンダラーはないため自前構築が必要。Mapbox Junction View の日本対応が確認できればそちらを優先。

---

## 10. Toll Calculation (料金計算)

### Mapbox — **NOT AVAILABLE**
Mapbox Directions API には高速料金データなし。

### Google Routes API — **ADOPTED (supplement)**
- 日本の高速道路料金計算に対応
- ETC 割引情報あり

### Decision
**Google Routes API で料金計算のみ補完。**

---

## 11. Geocoding / Search (検索)

### Google Places API — **ADOPTED**
- 日本語の検索精度が高い
- autocomplete / place details / text search が充実
- Mapbox Geocoding API より日本の POI カバレッジが優秀

### Mapbox Geocoding API — **NOT SELECTED**
- Mapbox エコシステム統一の観点では理想的
- しかし日本語検索精度で Google Places に劣る
- 地図タップ時の POI identity が Mapbox Maps SDK と一致しない問題は、座標ベースのフォールバックで許容する

### Decision
**Google Places API を採用。** 日本語検索精度を最優先する設計判断。
Mapbox ベストプラクティスの Search SDK 統一からは外れるが、カーナビアプリとして検索品質を優先する。

---

## 12. Android Auto

### NavigationTemplate の能力

- `Surface` に Mapbox 地図を自前描画
- ターンバイターン（現在の step + 次の step）
- 車線案内画像（`Step.setLanesImage(CarIcon)`, 推奨 500x74dp）
- Junction View 画像（`RoutingInfo` に `CarIcon`, 最大 200dp 高さ）
- リスト表示は**最大 6 アイテム**（ハードリミット、ページネーションで対応）
- `NavigationManager.updateTrip()` でトリップ更新

### Key Requirement
**Auto 実行中もスマホ側アプリは独立動作する。** Car App Library の仕様上これは可能（Auto 側は `CarAppService` で動作し、Phone 側の Activity とは独立）。

---

## 13. OSM Data (静的データ)

### 利用方針

**Overpass API はアプリのランタイム依存には含めない。** ビルドパイプライン（GitHub Actions）で OSM データを事前抽出し、JSON としてリポジトリに同梱する。

### 同梱データ一覧

| データ | 用途 | OSM タグ | サイズ見積もり | 更新頻度 |
|---|---|---|---|---|
| SA/PA 位置 + 施設情報（全国） | 高速パネル表示、施設アイコン | `highway=services`, `highway=rest_area` + 子 amenity | ~200KB (gzip) | 月 1 回 |
| 道路通称辞書 | 音声案内の通称変換 | `alt_name`, `short_name`, `name:ja:alias` | ~50KB | コミュニティ PR で随時 |

### 更新フロー

```
GitHub Actions (月1回 cron)
  └─ Overpass API で全国 SA/PA データ取得
      └─ JSON 生成
          └─ 差分があれば自動 PR 作成
              └─ レビュー & マージ
                  └─ 次回アプリビルドに反映
```

通称辞書は OSM `alt_name` からの自動抽出 + コミュニティからの PR ベースで拡充。
