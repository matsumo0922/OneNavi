# OneNavi UI Playground

UI デザイン案をブラウザで確認するための dev-tool。インタラクティブ動作の検証ではなく、
**見た目の確認**が目的。デザインは Claude Code / Codex などのエージェントが作成する。

> 正本ドキュメント（エージェント向け運用・設計）は `docs/spec/29_ui_playground_dev_tool.md`。
> 本 README はその要約。

- **一覧画面**: 全デザインをカードで表示（タイトル・説明・サムネイル・実装済みビューポート）
- **詳細画面**: 1デザインの 4 ビューポートを並べて表示

## 起動

リポジトリルートから:

```bash
make ui-playground          # npm install + vite 起動（http://localhost:5175）
# 個別に
make ui-playground-setup    # npm install のみ
make ui-playground-dev      # vite 起動のみ
```

## デザインの追加方法（エージェント向け）

`designs/` 直下に **1 デザイン = 1 フォルダ** を作るだけ。フォルダはビルド時に
自動検出され一覧へ出現する。**登録ファイルの編集は不要。**

```
designs/
  <design-id>/            # kebab-case のフォルダ名がそのまま id（URL に使われる）
    meta.json             # 必須: { "title": "...", "description": "..." }
    phone-portrait.html   # 412 × 915
    phone-landscape.html  # 915 × 412
    tablet-portrait.html  # 800 × 1280
    tablet-landscape.html # 1280 × 800
```

### ルール

- **`meta.json` は必須**。`title`（短い名前）と `description`（何の画面か）を書く。
- 4 つの HTML ファイルの**ファイル名は固定**（上記のとおり）。
- 各 HTML は**完全に自己完結した1枚の HTML ドキュメント**にする。
  CSS はインラインまたは `<style>` で書く。外部リソースに依存しない。
- 各 HTML は**指定の実寸（px）で破綻なく表示される前提**で作る。
  プレイグラウンド側が iframe にその実寸を与え、表示時に自動で縮小する。
- **未実装のビューポートはファイルを置かなければよい** → 詳細画面に「未実装」の空枠が出る。
  ただし原則として 4 つすべてを実装すること。
- スマホ枠・ベゼル等の装飾は不要（プレイグラウンド側でも描画しない）。

### ビューポート実寸

| ファイル | 幅 × 高さ (px) | 用途 |
|---|---|---|
| `phone-portrait.html`   | 412 × 915  | スマホ縦 |
| `phone-landscape.html`  | 915 × 412  | スマホ横 |
| `tablet-portrait.html`  | 800 × 1280 | タブレット縦 |
| `tablet-landscape.html` | 1280 × 800 | タブレット横 |

### 最小テンプレート

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

## サンプル

- `designs/sample-home/` — 4 ビューポートすべて実装した例。
- `designs/sample-partial/` — 2 つだけ実装し、残りが「未実装」空枠で出る例。

## 仕組み（メモ）

`src/main.ts` が `import.meta.glob('../designs/*/meta.json')` と
`import.meta.glob('../designs/*/*.html', { query: '?url' })` でフォルダを走査し、
`<id>` ごとに束ねて一覧/詳細を描画する。HTML は `?url` で iframe の `src` に渡し、
`transform: scale()` で枠に合わせて縮小表示している。
