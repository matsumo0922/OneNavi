# 08. Open Questions & Decision Log

## Decision Log

### D-001: NAVITIME API は使用しない
- **Date:** 2026-03-28
- **Context:** NAVITIME の RapidAPI 版は高品質なルーティングデータを提供するが、利用規約第 5 条でカーナビ用途が明確に禁止されている。
- **Decision:** NAVITIME API は不採用。Mapbox をメインとし、料金計算のみ Google Routes API で補完。
- **Rationale:** 規約違反のリスクは許容できない。
- **Reference:** https://api-sdk.navitime.co.jp/api/specs/description/rapid_tou.html

### D-002: Mapbox をフルスタックで採用
- **Date:** 2026-03-28
- **Decision:** Mapbox Maps SDK + Navigation SDK + Geocoding API をメインスタックとして採用。
- **Rationale:** Surface 描画対応、セッション中の無料 API 呼び出し、車線・バナー・信号機データの品質、統一スタックによる開発コスト低減、個人利用で完全に無料枠内。

### D-003: 音声案内テキストは自前生成
- **Date:** 2026-03-28
- **Decision:** Mapbox の route data から生データを取得し、案内テキストは完全に自前で生成する。
- **Rationale:** Mapbox デフォルトはマイル表記・不自然な日本語・通称なし・信号機案内なし。音声案内の品質は OneNavi の最大の差別化要素。

### D-004: TTS は Google Cloud TTS Chirp 3: HD を採用
- **Date:** 2026-04-04 (revised)
- **Decision:** Google Cloud TTS (Chirp 3: HD, ボイス: Laomedeia) を採用、Android 内蔵 TTS をフォールバックとする。
- **Rationale:** Google Places API と API キーを共用でき、OSS ユーザーの設定負担が軽減される。無料枠 1M 文字/月は Azure（500K）の 2 倍。ストリーミング API で ~200ms のレイテンシはナビ用途に十分。東京リージョン対応。

### D-005: 高速パネルは Mapbox + 同梱 SA/PA データ
- **Date:** 2026-03-28
- **Decision:** IC/JCT は Mapbox steps データから、SA/PA はリポジトリ同梱の静的 JSON で対応。
- **Rationale:** Mapbox の steps に SA/PA は出現しない。OSM から Overpass API で事前抽出し JSON 化してリポジトリに含める。アプリのランタイムに Overpass API への依存は持たない。

### D-006: Junction View は Phase 2 でプロシージャル生成
- **Date:** 2026-03-28
- **Decision:** Phase 1 では交差点拡大図なし。Phase 2 で高速 JCT のみプロシージャル生成を検討。Mapbox Junction View の日本対応が確認できればそちらを優先。
- **Rationale:** 商用ナビの交差点拡大図は事前レンダリング画像 DB 前提で、個人開発では不可能。

### D-007: 信号機案内は Mapbox の traffic_signal フラグで実現
- **Date:** 2026-03-28
- **Decision:** Mapbox Directions API の `intersections[].traffic_signal` フラグを使用して信号機案内を生成する。OSM Overpass API はこの用途ではアプリに搭載しない。
- **Rationale:** テストで 282 交差点中 16 箇所に `traffic_signal: true` を確認。カバレッジは完全ではないが、マニューバ地点での信号機案内には実用的。カバレッジ外では距離ベース案内にフォールバック。
- **Reference:** https://github.com/mapbox/mapbox-navigation-ios/issues/3843

### D-008: Overpass API はアプリに搭載しない
- **Date:** 2026-03-28
- **Decision:** Overpass API はビルドパイプライン（GitHub Actions）での SA/PA データ抽出にのみ使用。アプリのランタイム依存には含めない。
- **Rationale:** OSS プロジェクトとして自前の DB サーバーを持てない。SA/PA データは静的 JSON としてリポジトリに同梱し、信号機データは Mapbox API から取得することで、外部依存を Mapbox + Google Cloud (Routes API + TTS) のみに抑える。

---

## Open Questions

### Q-001: Mapbox Junction View / Signboards の日本対応
- **Status:** UNRESOLVED
- **Question:** Mapbox の Junction View と Signboards は日本の高速道路をカバーしているか？
- **Impact:** 対応していれば Phase 2 のプロシージャル生成が不要になる可能性
- **Action:** Mapbox サポートに問い合わせる
- **Priority:** HIGH

### Q-002: `voice_units=metric` パラメータ
- **Status:** UNRESOLVED
- **Question:** Mapbox Directions API に `voice_units=metric` パラメータが存在するか？
- **Impact:** 自前テキスト生成するなら影響は小さいが、部分的に Mapbox テキストを活用する場合は重要
- **Action:** パラメータのテスト
- **Priority:** MEDIUM

### Q-003: 二輪車ルーティングの実現方法
- **Status:** UNRESOLVED
- **Question:** Mapbox に motorcycle プロファイルがない。排気量別の道路制限にどう対応するか？
- **Options:**
  1. Valhalla を自前ホストし、motorcycle プロファイルをカスタム定義
  2. GraphHopper の motorcycle プロファイルを使用
  3. 当面は自動車プロファイルのみで Phase 4 に先送り
- **Priority:** LOW (Phase 4)

### Q-004: 通称辞書の初期データ構築
- **Status:** UNRESOLVED
- **Question:** 道路の正式名称→通称のマッピングをどう構築するか？
- **Options:**
  1. OSM の `alt_name` / `short_name` タグから自動抽出（GitHub Actions）
  2. 手動で主要道路 300-500 件を登録
  3. コミュニティに初期データ構築を呼びかけ
- **Priority:** MEDIUM

### Q-005: 信号機フラグのカバレッジ検証
- **Status:** PARTIALLY RESOLVED
- **Question:** Mapbox の `traffic_signal` フラグは日本の主要道路でどの程度カバーされているか？
- **Partial Answer:** テストルートでは 282 交差点中 16 箇所（5.7%）で `true`。主要交差点では確認。
- **Remaining:** 複数ルート・複数地域での検証が必要。
- **Priority:** MEDIUM

### Q-006: Android Auto テスト環境
- **Status:** UNRESOLVED
- **Question:** Android Auto アプリの開発・テスト環境はどう構築するか？
- **Priority:** LOW (Phase 3)

### Q-007: OSS ライセンス選定
- **Status:** UNRESOLVED
- **Question:** OneNavi はどのライセンスで公開するか？（Apache 2.0 / GPL v3 / MIT）
- **Priority:** LOW

### Q-008: Mapbox の日本における地図データ品質
- **Status:** PARTIALLY RESOLVED
- **Partial Answer:** テストルートでは妥当なルート選択、81% の制限速度カバー、87% の渋滞情報カバーが確認。
- **Remaining:** 狭い道回避、時間帯制限の正確性は未検証。

### Q-009: Mapbox / Google の利用規約に OneNavi を禁止する条項がないか
- **Status:** UNRESOLVED
- **Question:** Mapbox Maps SDK / Navigation SDK / Google Routes API の利用規約で、OSS カーナビアプリの用途が制限されていないか？
- **Priority:** HIGH

---

## Review Request

本仕様書について以下の観点でのレビューを求む:

1. **技術的妥当性:** Mapbox をフルスタックで採用する判断は妥当か？見落としているリスクはないか？
2. **フェーズ分割:** Phase 1 の MVP スコープは適切か？過大/過小でないか？
3. **代替案:** HERE SDK、TomTom、MapFan API など、検討すべきだが不足している選択肢はあるか？
4. **コスト:** 無料枠内で運用可能という試算に漏れはないか？
5. **法的リスク:** NAVITIME API の規約解釈は正しいか？Mapbox / Google の利用規約に問題はないか？
6. **二輪車対応:** Phase 4 に先送りする判断は妥当か？
7. **OSM データ依存:** SA/PA の同梱 JSON + 通称辞書で OSM に依存する戦略のリスクは？
8. **信号機案内:** Mapbox `traffic_signal` フラグのみで信号機案内を構築する判断は妥当か？カバレッジ不足のリスクは許容範囲か？
