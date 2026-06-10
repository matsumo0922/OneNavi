# CLAUDE.md

## ⚠️ 厳命: 外部ナビ API 提供事業者名の非露出

本リポジトリ（OneNavi）は **パブリック OSS**。ルート検索・turn-by-turn 案内は別管理の
プライベートリポジトリに閉じ込めた「外部ナビ API ライブラリ」に委譲している。
**当該事業者・製品の実名を本リポジトリ側に一切露出させないこと。**

### 対象範囲

- コミットメッセージ（subject / body）
- コードコメント / KDoc / TODO
- クラス名・変数名・関数名・定数名・ファイル名・ブランチ名・PR タイトル / 本文
- 新規追加する公開ドキュメント（**本ファイル CLAUDE.md / AGENTS.md 自身も含む**）

### 代替表記（これのみ使用）

- 事業者: **「N 社」**
- 製品 / アプリ: **「N 社のナビアプリ」** または **「外部ナビ API の参照実装アプリ」**
- ライブラリ: **「外部ナビ API ライブラリ」**（別管理のプライベートリポジトリに存在）
- クラス prefix: **`ExtNav`**（External Nav の略）
- 環境変数 / BuildKonfig: **`EXT_NAV_*`**

### 禁止語の具体リスト

具体的にどの語が禁止かは、**git 管理外** のローカルメモリに格納されている（本 repo
内に列挙すると列挙そのものが漏洩になるため）。エージェント利用者はセッション開始時に
自動ロードされるメモリを参照すること。人間作業者は `~/.claude/projects/` 配下の本
プロジェクト用 memory ディレクトリ内 `feedback_external_nav_naming.md` を参照。

原則として「別管理のプライベートリポジトリ側のリポジトリ名・APK パッケージ名・公式
ドメイン・アプリの製品名（日本語 / 英語 / カタカナ / ローマ字表記すべて）」が禁止に
含まれる、と理解しておけば新規追記時に迷うことは少ない。

### 例外（既存記述のみ据え置き）

- `docs/spec/*.md` の既存言及（競合分析 / 歴史的決定記録。新規追記では使わない）
- `docs/logs/*.md` の既存言及（同上）

上記以外で既存ファイルに禁止語が含まれていたら、必ず修正対象として報告すること。

### コミット前の確認

差分に禁止語が混入していないかは、ローカルの git-ignored な pattern file を使って
grep することで確認できる（pattern file を repo にコミットしてはならない）。

### 背景

非公開 API を第三者実装で扱う以上、権利侵害・BAN・法的リスクを公開側で極小化するため。
詳細は `docs/spec/18_external_nav_api_migration_plan.md` §D-108 参照。

---

## Project Overview

OneNavi は Android 向けカーナビアプリ。Google Maps 等から intent share で起動し、経路案内に特化する。OSS として公開、収益化なし。

詳細な仕様は `docs/spec/` 以下を参照:
- `01_project_overview.md` — コンセプト・目標
- `02_requirements.md` — 機能/非機能要件
- `03_technology_evaluation.md` — 技術選定・判断理由
- `05_architecture.md` — システムアーキテクチャ
- `06_pricing_analysis.md` — コスト分析
- `07_phased_roadmap.md` — フェーズ別ロードマップ
- `08_open_questions.md` — 未解決事項・意思決定ログ
- `09_etc_card_detection.md` — ETC カード検出 API 調査
- `18_external_nav_api_migration_plan.md` — 外部ナビ API 移行計画
- `23_route_compare_dev_tool.md` — ルート比較 dev tool
- `28_navigating_state_and_guidance_progress_design.md` — ナビ中状態・案内進捗設計
- `33_external_nav_api_local_dependency.md` — 外部ナビ API ライブラリのローカル依存化

## Tech Stack

- **Language**: Kotlin 2.3 (KMP)
- **UI**: Jetpack Compose (Compose Multiplatform 1.10)
- **DI**: Koin 4.1
- **Network**: Ktor 3.3
- **Serialization**: kotlinx.serialization
- **Image**: Coil 3
- **Navigation**: Navigation3 (androidx.navigation3)
- **Build**: Gradle 9.4 wrapper + Android Gradle Plugin 8.13 + build-logic (Convention Plugins)
- **Lint**: detekt + Twitter Compose Rules
- **Map/Navigation**: Google Maps SDK + Google Routes API + 外部ナビ API ライブラリ
- **TTS**: Google Cloud TTS Chirp 3 HD + ローカル効果音 / AudioFocus 制御（Android 内蔵 TTS fallback は未実装）
- **Toll**: 外部ナビ API ライブラリ由来の route summary（Google Routes API は dev tool / 補助検証用途）

## Module Structure

```
composeApp/           — Android/iOS アプリエントリポイント (me.matsumo.onenavi)
core/
  common/             — 共通ユーティリティ
  model/              — ドメインモデル
  datasource/         — データソース層
  repository/         — リポジトリ層
  ui/                 — 共通 UI コンポーネント
  resource/           — リソース (strings, drawables 等)
  billing/            — 課金ロジック
feature/
  home/               — ホーム画面
  setting/            — 設定画面
  billing/            — 課金画面
build-logic/          — Convention Plugins (primitive.*)
config/detekt/        — detekt 設定
docs/spec/            — 仕様書
docs/idea/            — UI リファレンス画像
docs/design/          — Pencil デザインファイル (.pen)
```

## Dev Tools (`dev-tools/`)

UI 確認・デバッグ用の独立した Vite ベース mini app 群。本番モジュールには統合されない。
`make <name>` で起動する。

| ツール | 用途 | ドキュメント |
|---|---|---|
| `dev-tools/ui-playground` | UI デザイン案をブラウザで一覧/詳細プレビュー | `docs/spec/29_ui_playground_dev_tool.md` |
| `dev-tools/fake-gps` | エミュレータへ GPS を流す | `docs/spec/15_fake_gps_dev_tool.md` |
| `dev-tools/route-compare` | 外部ルートと Routes API polyline の比較 | `docs/spec/23_route_compare_dev_tool.md` |

**UI を新規に提案・作成するときは、Compose を書く前に `ui-playground` にデザイン案を
HTML モックとして追加し、人間がブラウザで確認できるようにすること**（手順は
`docs/spec/29_ui_playground_dev_tool.md` 参照）。`make ui-playground` で起動。

## Build & Run

実装終了後は必ずビルドが通ることを確認すること。
現状 iOS の実装は行っていないため、Android のビルドが通ることを確認すれば良い。
なお、CLI (`./gradlew`) は Configuration Cache の問題が発生する場合があるため注意すること。。

```bash
./gradlew assembleDebug --no-configuration-cache

# detekt (auto-correct)
make detekt
# or
./gradlew detekt --auto-correct --continue
```

## External Nav API Local Dependency

外部ナビ API ライブラリは submodule ではなく、ローカル file Maven repository に publish した
AAR として解決する。通常の IDE import では外部ライブラリのソースを読み込まない。

```bash
make ext-nav-api-setup
./gradlew assembleDebug --no-configuration-cache
```

- 既定では OneNavi と同じ親ディレクトリにある checkout を使う。checkout が無い初回だけ
  `EXT_NAV_API_GIT_URL=<private-repository-url> make ext-nav-api-setup` で clone + publish する。
- checkout の場所を変える場合は `EXT_NAV_API_PATH`、publish 先を変える場合は
  `EXT_NAV_API_REPOSITORY_PATH`、依存 version を固定する場合は `EXT_NAV_API_VERSION` を使う。
- 外部ナビ API ライブラリ自体を編集しながらビルドする場合のみ、`extNavApiPath` /
  `EXT_NAV_API_PATH` で opt-in composite build を有効化する。通常の IDE import の既定にしない。
- 外部ナビ API ライブラリを submodule として再追加しないこと。詳細は
  `docs/spec/33_external_nav_api_local_dependency.md` を参照。

## Convention Plugins (build-logic)

| Plugin ID | 用途 |
|---|---|
| `matsumo.primitive.android.application` | Android Application モジュール |
| `matsumo.primitive.android.library` | Android Library モジュール |
| `matsumo.primitive.kmp.common` | KMP 共通設定 |
| `matsumo.primitive.kmp.android` | KMP Android ターゲット |
| `matsumo.primitive.kmp.compose` | KMP Compose 設定 |
| `matsumo.primitive.kmp.ios` | KMP iOS ターゲット |
| `matsumo.primitive.detekt` | detekt 設定 |

## Architecture

- **レイヤー構成**: feature → core (repository → datasource → model)
- **DI**: Koin。各モジュールが `xxxModule` を公開し、`composeApp` の `applyModules()` で統合
- **BuildKonfig**: `local.properties` または環境変数から API キーを読み込み

## Android Configuration

- **minSdk**: 26
- **targetSdk / compileSdk**: 36
- **Java**: 17
- **Package**: `me.matsumo.onenavi`
- **Build Types**: `debug` (suffix `.debug`), `release` (minify + ProGuard), `billing`

## Key Dependencies

- `kotlinx-collections-immutable` — Compose の安定性のために必須
- `kotlinx-datetime` — 日時操作
- `napier` — ロギング
- `calf` — KMP 向け UI/Permission/FilePicker
- `kolor` — Material You カラー

## Code Conventions

`~/.claude/CLAUDE.md` のグローバル設定に従う。特にこのプロジェクトで重要なもの:

- **trailing comma は全箇所で必須** (detekt で強制)
- **Composable のデフォルト可視性は `internal`**
- **Composable の引数に `modifier: Modifier = Modifier` を必ず含める**
- **`Spacer` に具体的な dp 値を指定しない** — `Arrangement.spacedBy` + `Modifier.padding` で調整
- **Composable は名前付き引数 + 改行で呼び出す**（引数1つなら省略可）
- **`data class` には `@Stable` or `@Immutable` を付与**
- **Material3 を使用**（Material ではない）
