# CLAUDE.md

## Project Overview

OneNavi は Android 向けカーナビアプリ。Google Maps 等から intent share で起動し、経路案内に特化する。OSS として公開、収益化なし。

詳細な仕様は `docs/spec/` 以下を参照:
- `01_project_overview.md` — コンセプト・目標
- `02_requirements.md` — 機能/非機能要件
- `03_technology_evaluation.md` — 技術選定・判断理由
- `04_api_test_results.md` — Mapbox API テスト結果
- `05_architecture.md` — システムアーキテクチャ
- `06_pricing_analysis.md` — コスト分析
- `07_phased_roadmap.md` — フェーズ別ロードマップ
- `08_open_questions.md` — 未解決事項・意思決定ログ

## Tech Stack

- **Language**: Kotlin 2.3 (KMP)
- **UI**: Jetpack Compose (Compose Multiplatform 1.10)
- **DI**: Koin 4.1
- **Network**: Ktor 3.3
- **Serialization**: kotlinx.serialization
- **Image**: Coil 3
- **Navigation**: Navigation3 (androidx.navigation3)
- **Build**: Gradle 9.4 + build-logic (Convention Plugins)
- **Lint**: detekt + Twitter Compose Rules
- **Map/Navigation**: Mapbox Maps SDK + Navigation SDK (未統合)
- **TTS**: Azure TTS Dragon HD + Android 内蔵 TTS (未統合)
- **Toll**: Google Routes API (未統合)

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
```

## Build & Run

```bash
# ビルド
./gradlew assembleDebug

# detekt (auto-correct)
make detekt
# or
./gradlew detekt --auto-correct --continue
```

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
