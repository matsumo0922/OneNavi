# AGENTS.md

本ファイルは Codex / 他の AI エージェント向けの運用指示。Claude Code 利用時は
`CLAUDE.md` の内容を優先で読むこと（両ファイルの規約は一致させる）。

---

## ⚠️ 厳命: 外部ナビ API 提供事業者名の非露出

本リポジトリ（OneNavi）は **パブリック OSS**。ルート検索・turn-by-turn 案内は別管理の
プライベートリポジトリに閉じ込めた「外部ナビ API ライブラリ」に委譲している。
**当該事業者・製品の実名を本リポジトリ側に一切露出させないこと。**

### 対象範囲

- コミットメッセージ（subject / body）
- コードコメント / KDoc / TODO
- クラス名・変数名・関数名・定数名・ファイル名・ブランチ名・PR タイトル / 本文
- 新規追加する公開ドキュメント（**本ファイル AGENTS.md / CLAUDE.md 自身も含む**）

### 代替表記（これのみ使用）

- 事業者: **「N 社」**
- 製品 / アプリ: **「N 社のナビアプリ」** または **「外部ナビ API の参照実装アプリ」**
- ライブラリ: **「外部ナビ API ライブラリ」**（別管理のプライベートリポジトリに存在）
- クラス prefix: **`ExtNav`**（External Nav の略）
- 環境変数 / BuildKonfig: **`EXT_NAV_*`**

### 禁止語の具体リスト

具体的にどの語が禁止かを本 repo に書くと、その列挙自体が漏洩になる。ゆえに
**禁止語の具体リストは repo 外（エージェントのローカルメモリ / `~/.claude/` 配下）に
閉じ込めてある**。Claude Code 利用時は自動でロードされる。他のエージェントは
初回作業前に利用者から禁止語リストを受け取ること。

原則として「別管理のプライベートリポジトリ側のリポジトリ名・APK パッケージ名・公式
ドメイン・アプリの製品名（日本語 / 英語 / カタカナ / ローマ字表記すべて）」が禁止に
含まれる、と理解しておけば新規追記時に迷うことは少ない。

### 例外（既存記述のみ据え置き）

- `docs/spec/*.md` の既存言及（競合分析 / 歴史的決定記録。新規追記では使わない）
- `docs/logs/*.md` の既存言及（同上）

上記以外で既存ファイルに禁止語が含まれていたら、必ず修正対象として報告すること。

### コミット前の事前チェック

差分に禁止語が混入していないかは、ローカルの **git-ignored** な pattern file を
用意して grep することで確認できる（pattern file 自体も repo にコミットしてはならない）。
例:

```bash
# .claude/forbidden.txt は .gitignore で除外済みであることを事前に確認
git diff --cached | grep -iEf .claude/forbidden.txt
```

ヒットした場合は修正してから再 stage する。

### 背景

非公開 API を第三者実装で扱う以上、権利侵害・BAN・法的リスクを公開側で極小化するため。
2026-04-22 に `docs/note/` 配下の逆解析ドキュメントを履歴ごと filter-repo で削除し、
force push で origin/main 及び全ブランチを rewrite した経緯あり。
詳細は `docs/spec/18_external_nav_api_migration_plan.md` §D-108 参照。

---

## その他のプロジェクト規約

コード規約・アーキテクチャ・ビルド手順は `CLAUDE.md` と `~/.claude/CLAUDE.md`（利用者の
グローバル設定）に従う。主要ポイント:

- Kotlin: trailing comma 必須、`data class` には KDoc + `@Stable` / `@Immutable`
- Compose: `modifier: Modifier = Modifier` 必須、`Spacer` に dp 直指定禁止
- Material3 を使用（`androidx.compose.material` は不可）
- ビルド: `./gradlew assembleDebug --no-configuration-cache`
- Lint: `make detekt`
- コミット prefix: `feat:` / `fix:` / `refactor:` / `test:` / `docs:` / `chore:` / `ci:` / `build:`
- コミットメッセージは英語、PR title は英語、PR description は日本語
