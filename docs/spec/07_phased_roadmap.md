# 07. Phased Development Roadmap

## Phase 1: MVP — Basic Navigation

**Goal:** Google Maps から intent share で起動し、Mapbox ベースでターンバイターンナビゲーションができる状態。

### Scope

- Mapbox Maps SDK 統合（地図表示、渋滞レイヤー、ルートライン描画）
- Mapbox Navigation SDK 統合（ルーティング、リルート、トリップ進捗）
- Intent share 受信 → ルート案内開始
- 基本的な Phone UI（地図画面、ルート概要、ナビ画面）
- ターンバイターン表示（交差点名、方向、距離）
- Mapbox 標準の車線インジケーター表示
- 代替ルートの半透明表示 + タップ切替
- 基本的な音声案内（Android 内蔵 TTS + 自前テキスト生成の初期版）
  - メートル系表記
  - 基本的な日本語テンプレート（「○○を右折です」「○○メートル先、左折です」）
  - 信号機案内（Mapbox `traffic_signal` フラグベース、カバレッジ内のみ）
  - 合流案内（`merge` マニューバ検出）
  - 速度連動の案内タイミング
- 最低限の検索機能（Mapbox Geocoding）
- ナビ中の経由地追加（地図タップ → 確認 → ルート更新）
- 基本的な高速パネル表示（Mapbox steps データから IC/JCT 一覧）
- 設定画面（API キー入力、基本設定）

### Non-Goals for Phase 1
- Android Auto 対応
- 通称辞書
- SA/PA データ・施設情報
- 交差点拡大図
- 料金表示
- 二輪車対応
- Google Cloud TTS (Chirp 3: HD) ストリーミング統合

---

## Phase 2: Enhanced Guidance — Voice & Highway Panel

**Goal:** 音声案内の品質をナビタイムレベルに引き上げ、高速パネルを完全実装。

### Scope

- Google Cloud TTS (Chirp 3: HD, Laomedeia) ストリーミング統合
- 通称辞書の構築と統合
  - 初期データ: 主要都市の幹線道路 300-500 件
  - OSM `alt_name` タグからの自動抽出パイプライン（GitHub Actions）
- 日本語案内テンプレートの充実
  - 「この信号を右折です」
  - 「まもなく○○方面、左車線へ」
  - 高速道路固有（「○○JCT を左方向です、○○方面」）
  - 車線減少案内
- SA/PA データの同梱（GitHub Actions で OSM から月次自動抽出）
  - 位置・名前・施設情報（GS, トイレ, コンビニ, レストラン, EV 充電等）のアイコン表示
- 高速パネル完全版
  - IC/JCT/SA/PA 一覧 + 推定通過時刻 + 距離
  - 一般道でも右左折リスト表示
- 料金表示（Google Routes API 統合）
- 高速 JCT のプロシージャル Junction View（初期版）
  - Mapbox Junction View の日本対応が確認できればそちらを優先
- 方面看板表示（Mapbox banner components から方面名・路線番号を表示）

---

## Phase 3: Android Auto & Production Quality

**Goal:** Android Auto 完全対応、プロダクション品質の UI/UX。

### Scope

- Android Auto 対応
  - `NavigationTemplate` で地図・ターンバイターン・車線案内表示
  - Surface に Mapbox 地図描画
  - 高速パネルのページネーション対応（6 アイテム制限）
  - Phone / Auto 独立動作
- UI/UX 仕上げ
  - ダークモード / ナビモードのカスタム地図スタイル
  - スムーズなアニメーション
  - 運転中の視認性最適化
- 速度制限警告（Mapbox maxspeed データ活用）
- パフォーマンス最適化

---

## Phase 4: Advanced Features

**Goal:** 二輪車対応、オフライン、コミュニティ拡充。

### Scope

- 二輪車ルーティング（Valhalla 自前ホスト検討、排気量別制限対応）
- オフラインナビ（Mapbox オフラインマップ + Valhalla）
- 通称辞書のコミュニティ拡充
- PLATEAU LOD3 データ統合（都市部の精密 Junction View）
- 燃費計算機能

---

## Risk Register

| Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|
| Mapbox の車線データカバレッジ不足 | 車線案内の品質低下 | Medium | カバレッジ外では案内をスキップ |
| Mapbox Junction View が日本非対応 | 交差点拡大図なし | High | プロシージャル生成で代替 |
| Google Cloud TTS のレイテンシ | 音声案内の遅延 | Low | ストリーミング API (~200ms) + 定型フレーズキャッシュ + Android TTS フォールバック |
| Mapbox の料金体系変更 | コスト増 | Low | 個人利用なら影響小 |
| 信号機フラグのカバレッジ不足 | 信号機案内の品質低下 | Medium | カバレッジ外では距離ベース案内にフォールバック |
| Android Auto の制約 | パネル表示等の機能制限 | High（確定） | Phone 側でフル機能提供、Auto はページネーションで対応 |
| 二輪車ルーティングの実現困難 | 車種切替機能の遅延 | High | Phase 4 に先送り |
| 時間帯交通規制の OSM カバレッジ不足 | ルート品質低下 | Medium | OSM コミュニティ貢献で段階的改善 |
