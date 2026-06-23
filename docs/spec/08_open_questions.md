# 08. Open Questions & Decision Log

## Decision Log

### D-100: 外部API ライブラリを案内の primary source にする

- **Date:** 2026-04-22
- **Decision:** ルート検索、turn-by-turn 案内、交通情報、案内画像は外部API ライブラリを primary source とする。
- **Rationale:** OneNavi 側で日本語案内品質を過剰に再構築せず、provider 固有処理を private 実装へ閉じ込められる。

### D-101: 地図表示は Google Maps SDK に寄せる

- **Date:** 2026-04-22
- **Decision:** OneNavi の map feature は Google Maps SDK を前提にする。
- **Rationale:** 現在の実装と dev tool が Google Maps 前提で進んでおり、旧地図/ナビ provider へ戻す計画はない。

### D-102: 旧地図/ナビ provider の fallback を残さない

- **Date:** 2026-06-05
- **Decision:** 旧 provider の SDK、token、MCP、skill、設計ドキュメントは OneNavi の判断材料から外す。
- **Rationale:** Coding Agent が旧 provider の best practice に引っ張られ、現行方針と矛盾する提案を出すため。

### D-103: provider 実名は公開 repo に出さない

- **Date:** 2026-04-22
- **Decision:** 外部API の事業者・製品名は 外部API 提供元表記に統一する。
- **Rationale:** OneNavi は public OSS であり、private 実装の具体名や認証情報を公開側へ漏らさない。

## Open Questions

### Q-001: Android Auto の map surface 戦略

- **Status:** OPEN
- **Question:** Google Maps と Android for Cars の公開 API で、スマホ版と同等の地図・案内 UI をどこまで再現できるか。
- **Priority:** HIGH

### Q-002: 外部ルートと Google Maps overlay の形状一致

- **Status:** IN PROGRESS
- **Question:** 外部API ライブラリの route geometry を Google Routes API / Google Maps overlay とどの粒度で一致させるか。
- **Reference:** `docs/logs/7_waypoint_polyline_alignment_investigation.md`
- **Priority:** HIGH

### Q-003: Cloud TTS と Android TTS の切替条件

- **Status:** OPEN
- **Question:** 遅延、ネットワーク、課金、ユーザー設定をどう評価して fallback するか。
- **Priority:** MEDIUM

### Q-004: OSS ライセンス選定

- **Status:** OPEN
- **Question:** OneNavi はどのライセンスで公開するか。
- **Priority:** LOW
