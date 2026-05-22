# 29. UI Playground Dev Tool — UI デザイン案をブラウザで確認する mini app

## 0. このドキュメントの目的

`dev-tools/ui-playground/` に作った Vite + TypeScript のデザイン確認用 mini app
について、**エージェント（Claude Code / Codex 等）が UI デザイン案を作るときの
正本となる手順**を記録する。

このツールはインタラクティブ動作の検証用ではなく、**見た目を人間がブラウザで
確認する**ためのもの。本番モジュール（`composeApp`, `core/*`, `feature/*`）には
一切統合されない、完全に独立した dev-tool。

> **エージェントへ:** UI を新規に提案・作成する依頼を受けたら、Compose のコードを
> 書く前に、まずここにデザイン案を HTML モックとして追加して人間が確認できるように
> する運用を基本とする。確認 OK の案を Compose に落とし込む。

---

## 1. 何ができるか

- **一覧画面**: 登録済みデザインをカードで表示する。各カードはタイトル・説明・
  サムネイル（phone-portrait）・どのビューポートを実装済みかのバッジを持つ。
- **詳細画面**（`#/<design-id>`）: 1 デザインの 4 ビューポートを並べて表示する。
  `Fit ⇄ 100%` のズーム切替ボタン付き。未実装のビューポートは「未実装」の空枠で
  表示される。

スマホのベゼル・枠線等の装飾は描画しない（純粋にコンテンツのみ）。

---

## 2. 起動

リポジトリルートから:

```bash
make ui-playground          # npm install + vite 起動（http://localhost:5175）
make ui-playground-setup    # npm install のみ
make ui-playground-dev      # vite 起動のみ
```

API キー等の `.env` は不要（外部リソースに依存しない）。

---

## 3. デザインの追加方法（エージェント向け・最重要）

`designs/` 直下に **1 デザイン = 1 フォルダ** を作るだけ。`src/main.ts` が
`import.meta.glob` でフォルダを自動走査するので、**一覧への登録作業やインデックス
編集は一切不要**。フォルダを置けば一覧に出現する。

```
dev-tools/ui-playground/designs/
  <design-id>/            # kebab-case のフォルダ名がそのまま id（URL ハッシュに使われる）
    meta.json             # 必須: { "title": "...", "description": "..." }
    phone-portrait.html   #  412 ×  915
    phone-landscape.html  #  915 ×  412
    tablet-portrait.html  #  800 × 1280
    tablet-landscape.html # 1280 ×  800
```

### 3.1 ルール

1. **`meta.json` は必須**。`title`（短い画面名）と `description`（何の画面かの説明）
   を JSON で書く。これが無いとそのフォルダはデザインとして認識されない。
2. **4 つの HTML のファイル名は固定**（上表のとおり）。リネーム不可。
3. 各 HTML は**完全に自己完結した 1 枚の HTML ドキュメント**にする。CSS は
   `<style>` かインラインで書き、**外部リソース（CDN・画像 URL 等）に依存しない**。
4. 各 HTML は **§3.2 の実寸（px）で破綻なく表示される前提**で作る。プレイグラウンド
   が iframe にその実寸を与え、表示時に `transform: scale()` で自動縮小する。
   つまり「その画面サイズで見せたい完成形」をそのまま書けばよい。
5. **未実装のビューポートはファイルを置かなければよい** → 詳細画面に「未実装」の
   空枠が出る。ただし**原則 4 つすべてを実装する**こと（依頼で省略指示がない限り）。
6. スマホ枠・ベゼル等の装飾は書かない。

### 3.2 ビューポート実寸

| ファイル | 幅 × 高さ (px) | 用途 |
|---|---|---|
| `phone-portrait.html`   |  412 ×  915 | スマホ縦 |
| `phone-landscape.html`  |  915 ×  412 | スマホ横 |
| `tablet-portrait.html`  |  800 × 1280 | タブレット縦 |
| `tablet-landscape.html` | 1280 ×  800 | タブレット横 |

### 3.3 最小テンプレート

```html
<!DOCTYPE html>
<html lang="ja">
  <head>
    <meta charset="UTF-8" />
    <style>
      * { box-sizing: border-box; }
      html, body { margin: 0; height: 100%; }
      body { font-family: system-ui, "Hiragino Sans", "Noto Sans JP", sans-serif; }
      /* ここに画面のスタイルを書く */
    </style>
  </head>
  <body>
    <!-- ここに画面のマークアップを書く -->
  </body>
</html>
```

### 3.4 デザイン作成時の推奨

- 本アプリは **Material3 / カーナビ（OneNavi）** なので、モックも M3 寄りの配色・
  余白・角丸に寄せると Compose 実装への橋渡しがしやすい（必須ではない）。
- 各ビューポートで**レイアウトを作り分ける**こと。縦は縦積み、横/タブレットは
  二分割など、実機の画面比率に合った構成にする。単純な拡大縮小で済ませない。

---

## 4. 同梱サンプル

| design-id | 内容 |
|---|---|
| `sample-home`    | 4 ビューポートすべて実装した例（ホーム/目的地検索画面） |
| `sample-partial` | 2 つだけ実装し、残り 2 つが「未実装」空枠で出る例 |

新規作成時は `sample-home/` をひな型としてコピーするのが早い。

---

## 5. 仕組み（メモ）

ファイル構成:

```
dev-tools/ui-playground/
├── package.json / tsconfig.json / vite.config.ts / .gitignore
├── index.html
├── README.md
├── src/
│   ├── main.ts        # 一覧/詳細のルーティング・描画・glob 走査・iframe 縮小
│   ├── style.css      # プレイグラウンド自体の UI スタイル
│   └── vite-env.d.ts
└── designs/<id>/      # デザイン本体（§3）
```

- `src/main.ts` が `import.meta.glob("../designs/*/meta.json", { eager: true })` と
  `import.meta.glob("../designs/*/*.html", { eager: true, query: "?url" })` で
  フォルダを走査し、`<id>` ごとに束ねる。
- HTML は `?url` で iframe の `src` に渡し、実寸で描画してから `transform: scale()`
  で枠（fit モード）に縮小する。`100%` モードは実寸表示（スクロール）。
- ビューポート定義（key とファイル名・実寸の対応）は `src/main.ts` の `VIEWPORTS`
  定数が単一の正本。寸法を変えたいときはここを編集する。

`Makefile` ターゲット（`ui-playground-setup` / `ui-playground-dev` / `ui-playground`）は
追加済み。

---

## 6. 参考

- `dev-tools/ui-playground/README.md` — ツール内のクイックリファレンス（本ドキュメントの要約）
- `docs/spec/15_fake_gps_dev_tool.md` — 別の dev-tool（Fake GPS）
- `docs/spec/23_route_compare_dev_tool.md` — 別の dev-tool（Route Compare）
