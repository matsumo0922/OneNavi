# 31. Proto Inspector dev tool

## 目的

外部ナビ API ライブラリ（別管理のプライベートリポジトリ）が扱う protobuf バイナリ
（ROUTE / GUIDE 等）には未解明フィールドが多く残っている。`unknown_*` / `props_*` /
`flag_*` の値域を複数サンプル間で比較しながら、フィールド名・意味を人手で同定する
作業を効率化するためのブラウザ UI を提供する。

## スコープ

本ドキュメントは MVP（フェーズ 1）を対象とする。

### MVP に含むもの

- 任意の protobuf バイナリ（ファイルアップロード）を **スキーマ無し** で raw decode
  - field_number / wire_type / 値 をツリー表示
  - length-delimited は「子メッセージとして再 parse 成功すればネスト、ダメなら string/bytes」
    のヒューリスティック
  - varint は int / sint / bool / enum 候補を併記
- 各フィールドに対するアノテーション（`name` / `description` / `type_hint`）の
  ブラウザ編集と JSON 永続化
- アノテーション JSON のキーは `(rootId, path[], fieldNumber)`。`rootId` は `guide` /
  `route` 等の任意の文字列。`path` は親 message のフィールド番号列

### MVP に含まないもの（フェーズ 2 以降）

- `.proto` / `.kt` のコメント書き戻し
- 外部ナビ API ライブラリ（プライベートリポジトリ）でパースした高レベルモデルとの並置
- 複数サンプル間の値域 diff 表示

## 構成

```
dev-tools/proto-inspector/
  index.html             — UI エントリ
  vite.config.ts         — annotations API (GET/PUT /api/annotations) を内包
  src/
    main.ts              — エントリ
    decoder.ts           — raw protobuf デコーダ
    annotations.ts       — annotation API client
    tree.ts              — ツリー描画 + 詳細パネル制御
    style.css            — スタイル
  annotations/
    <rootId>.json        — 永続化されたアノテーション
```

## アノテーション JSON フォーマット

```json
{
  "root": "guide",
  "savedAt": "2026-05-28T...",
  "fields": {
    "1": {
      "name": "map_data_version",
      "description": "DSR ZIP の map_data_version と一致する。",
      "typeHint": "string"
    },
    "2.3.1": {
      "name": "cum_distance_m",
      "description": "ルート起点からの累積距離 (メートル)。",
      "typeHint": "uint32"
    }
  }
}
```

- キーは `path` を `.` 区切りでつないだ field_number 列（root 直下は `1`、ネスト先は
  `2.3.1` のように親 → 子）
- `typeHint` は省略可。受け取った値の wire_type と照合して、UI 側で値を再解釈する

## 起動

```bash
make proto-inspector
```

`http://localhost:5175/` で UI が開く。ファイル選択でバイナリをアップロードすると、
スキーマ無しでデコードしてツリーが展開される。

## 既知の制約

- 同じバイト列でも「ネストメッセージ」と「ランダムなバイト列で偶然 parse できた」が
  区別できないケースがある（PR #unknown）。ツリー表示時には信頼度（child の field
  数が多い / 末尾まで消費できた等）に基づいて警告ラベルを表示する
- `packed` repeated は wire_type=2 として中身を varint 配列として推測する。値域の
  単調性などから判定するが、明示的なヒントを `typeHint` で与えると確定する
