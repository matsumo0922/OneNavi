# 07. Phased Development Roadmap

## Phase 1: Google Map + Route Preview Stabilization

**Goal:** Google Maps 上で外部ナビ API ライブラリのルート候補を安定して表示し、ルート選択までの体験を固める。

### Scope

- Google Maps surface の安定化
- route preview overlay
- 複数 route candidate の表示
- 渋滞 segment の route line 反映
- route-compare dev tool による shape reproduction 検証
- provider 実名・旧 provider 参照の撤去

## Phase 2: Active Guidance

**Goal:** 外部ナビ API ライブラリの案内情報を使い、スマホ画面で turn-by-turn ナビを成立させる。

### Scope

- navigating state
- maneuver panel
- lane / signboard / guide image
- route progress
- reroute
- foreground service
- notification
- Cloud TTS + Android TTS fallback

## Phase 3: Highway Panel And Guidance Polish

**Goal:** 日本のカーナビとして必要な高速パネル、渋滞、音声案内の品質を上げる。

### Scope

- IC/JCT/SA/PA パネル
- guide image preload
- 渋滞音声案内
- route congestion strip
- toll display
- long route QA

## Phase 4: Android Auto

**Goal:** Android Auto の公開 API で安全にナビ体験を提供する。

### Scope

- map surface integration
- NavigationTemplate
- trip update
- foreground service / notification 整理
- phone / Auto の状態同期

## Risk Register

| Risk | Impact | Mitigation |
|---|---|---|
| 外部ナビ API の provider 実名露出 | 法的・運用リスク | N 社表記と公開 repo grep を徹底 |
| Google Maps overlay と外部ルート形状の不一致 | ナビ体験低下 | route-compare で waypoint 戦略を検証 |
| Cloud TTS 遅延 | 音声案内遅延 | cache と Android TTS fallback |
| Android Auto 公開 API 制約 | Auto 対応遅延 | Phase 4 に分離して検証 |
