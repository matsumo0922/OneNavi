# AGENTS.md

本ファイルは Codex / 他の AI エージェント向けの運用指示。Claude Code 利用時は
`CLAUDE.md` の内容を優先で読むこと（両ファイルの規約は一致させる）。

---

## ⚠️ 厳命: 外部API 提供事業者名の非露出

本リポジトリ（OneNavi）は **パブリック OSS**。ルート検索・turn-by-turn 案内は別管理の
プライベートリポジトリに閉じ込めた「外部API ライブラリ」に委譲している。
**当該事業者・製品の実名を本リポジトリ側に一切露出させないこと。**

### 対象範囲

- コミットメッセージ（subject / body）
- コードコメント / KDoc / TODO
- クラス名・変数名・関数名・定数名・ファイル名・ブランチ名・PR タイトル / 本文
- 新規追加する公開ドキュメント（**本ファイル AGENTS.md / CLAUDE.md 自身も含む**）

### 代替表記（これのみ使用）

- 事業者: **「外部API 提供元」**
- 製品 / アプリ: **「外部API の参照実装アプリ」**
- ライブラリ: **「外部API ライブラリ」**（別管理のプライベートリポジトリに存在）
- 新規のクラス prefix: **`ExtApi`**（External API の略）
- 新規の環境変数 / BuildKonfig: **`EXT_API_*`**

既存コードのクラス名・メソッド名・import などに残る固有の識別子は、明示的に移行対象と
された場合のみ安全にリネームする。公開ドキュメント、運用手順、コメント、PR / Issue
文面では上記の代替表記に統一する。

### 禁止語の具体リスト

具体的にどの語が禁止かを本 repo に書くと、その列挙自体が漏洩になる。ゆえに
**禁止語の具体リストは repo 外（エージェントのローカルメモリ / `~/.claude/` 配下）に
閉じ込めてある**。Claude Code 利用時は自動でロードされる。他のエージェントは
初回作業前に利用者から禁止語リストを受け取ること。

原則として「別管理のプライベートリポジトリ側のリポジトリ名・APK パッケージ名・公式
ドメイン・アプリの製品名（日本語 / 英語 / カタカナ / ローマ字表記すべて）」が禁止に
含まれる、と理解しておけば新規追記時に迷うことは少ない。

### 例外

- Maven local repository に publish する依存 artifact 名は、上流ライブラリの制約により
  既存のまま扱う。

上記以外で既存ファイルに禁止語が含まれていたら、必ず修正対象として報告すること。

### 背景

非公開 API を第三者実装で扱う以上、権利侵害・BAN・法的リスクを公開側で極小化するため。
2026-04-22 に `docs/note/` 配下の逆解析ドキュメントを履歴ごと filter-repo で削除し、
force push で origin/main 及び全ブランチを rewrite した経緯あり。
詳細は `docs/spec/18_external_api_migration_plan.md` §D-108 参照。

---

## その他のプロジェクト規約

コード規約・アーキテクチャ・ビルド手順は `CLAUDE.md` と `~/.claude/CLAUDE.md`（利用者の
グローバル設定）に従う。主要ポイント:

- Kotlin: trailing comma 必須、`data class` には KDoc + `@Stable` / `@Immutable`
- Compose: `modifier: Modifier = Modifier` 必須、`Spacer` に dp 直指定禁止
- Material3 を使用（`androidx.compose.material` は不可）
- ビルド: `./gradlew assembleDebug --no-configuration-cache`
- Lint: `make detekt`
- 外部API ライブラリは submodule ではなくローカル file Maven repository の AAR として
  解決する。初回セットアップ / 再 publish は `make ext-api-setup` を使う。
- 外部API ライブラリの checkout が既定位置に無い場合のみ `EXT_API_GIT_URL` または
  `EXT_API_PATH` を指定する。private URL を tracked file に書かないこと。
- 外部API ライブラリ自体を編集しながらビルドする場合のみ `extApiPath` /
  `EXT_API_PATH` で opt-in composite build を有効化する。通常の IDE import の既定にしない。
- 外部API ライブラリを submodule として再追加しないこと。運用手順の正本は
  `docs/spec/33_external_api_local_dependency.md`。
- コミット prefix: `feat:` / `fix:` / `refactor:` / `test:` / `docs:` / `chore:` / `ci:` / `build:`
- コミットメッセージは英語、PR title は英語、PR description は日本語

## Worktree 運用

実装を行う場合は、必ず worktree を作成し、デフォルトディレクトリを汚さない。read-only の調査やビルド・テストの実行はこの限りでない。

```bash
git worktree add ../OneNavi-<task-slug> -b <branch-name>
cp -p local.properties ../OneNavi-<task-slug>/local.properties
cd ../OneNavi-<task-slug>
```

- `local.properties` は git 管理外なので、`git worktree add` ではコピーされない。Android SDK
  の場所、API キー、外部API の credential / ローカル依存設定を BuildKonfig や Gradle
  が参照するため、worktree 作成直後に必ず元 checkout からコピーする。
- `local.properties` の内容は tracked file、コミットメッセージ、PR 本文、issue コメントへ
  転記しない。worktree ごとに必要な差分がある場合も、各 worktree 内の `local.properties`
  だけを編集する。
- 外部API ライブラリは submodule ではなくローカル file Maven repository の AAR として
  解決する。worktree で初回ビルドに失敗した場合は `local.properties` のコピー漏れを確認し、
  必要に応じて `make ext-api-setup` を実行する。

## Dev Tools (`dev-tools/`)

UI 確認・デバッグ用の独立した Vite ベース mini app 群（本番モジュールには非統合）。

- `dev-tools/fake-gps`（`docs/spec/15_fake_gps_dev_tool.md`）
- `dev-tools/route-compare`（`docs/spec/23_route_compare_dev_tool.md`）
