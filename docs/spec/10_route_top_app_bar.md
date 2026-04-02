# HomeMapRouteTopAppBar 仕様書

## 概要

ルート表示中に地図上部に表示される TopAppBar。出発地・経由地・目的地をリスト表示し、確定状態と編集状態の 2 つの表示モードを持つ。Google Maps のルート表示 UI を参考にしている。

## 表示条件

- `routeResults` が空でないとき（ルート検索結果が存在するとき）に表示
- 既存の `HomeMapTopAppBar`（検索バー）と排他的に表示
- 統合先: `HomeMapScreenContent` で条件分岐して表示

## 表示状態

### 確定状態（デフォルト）

- 左端に **戻るアイコン（←）**（カード内左端に縦中央配置）→ タップで `OnDismissRoutes`
- 出発地行の右端に **設定アイコン**（⚙）→ タップで編集状態へ遷移
- 現在登録されている地点をリストで表示
- 並び替えアイコン（≡）・バツアイコン（×）は **非表示**
- 経由地なし（出発地 + 目的地のみ）の場合、目的地右側に **反転アイコン（↑↓）** を表示
  - タップで出発地と目的地を即座に入れ替え、`OnSwapOriginDestination` で ViewModel に通知
  - CurrentLocation と Place の入れ替えもそのまま許容（目的地が CurrentLocation になることも可）
- 経由地ありの場合、反転アイコンは非表示
- 各地点はタップ可能 → `OnWaypointClicked(index)` で通知（別画面 or Modal で地点名編集。入力ロジックはスキップ）

### 編集状態

- 左端に **戻るアイコン（←）**（カード内左端に縦中央配置）→ タップで変更を破棄して確定状態に戻る
- 各地点の右側に **並び替えアイコン（≡）** と **バツアイコン（×）** を表示
- DnD（ドラッグ＆ドロップ）で並び替え可能
  - 実装: `LazyColumn` + `detectDragGestures` 自前実装
  - ドラッグトリガー: **≡ アイコンのみ** でドラッグ開始（行全体ではない）
  - ドラッグ中のビジュアルフィードバック: 今回はなし（後で対応）
- 自由入力欄:
  - 常に 1 枠は自由入力欄が存在（5 地点に達したら非表示）
  - 自由入力欄の右側にはバツアイコン **なし**（削除不可）、並び替えアイコン（≡）のみ
  - タップで別画面に遷移（入力ロジックはスキップ）
  - **DnD で並び替え可能**（先頭にも来れる。プレースホルダーは位置に追従）
  - 自由入力欄が埋まったら、5 地点未満なら **自動で新しい自由入力欄を末尾に追加**
- 下部: `HorizontalDivider` + **「完了」TextButton**（右寄せ）
  - ルートが完結しない（入力済み地点が 2 つ未満）場合は完了ボタン **無効化**

### 編集状態への遷移ルール

- **5 地点未満の場合**: 現在の目的地を経由地に変換し、末尾に空の目的地枠（自由入力欄）を追加
  - 例（2 地点）: 出発地 + 目的地 → 出発地 + 経由地A(元目的地) + 目的地(空)
  - 例（3 地点）: 出発地 + 経由地A + 目的地 → 出発地 + 経由地A + 経由地B(元目的地) + 目的地(空)
  - 例（4 地点）: 出発地 + A + B + 目的地 → 出発地 + A + B + 経由地C(元目的地) + 目的地(空)
- **5 地点の場合**: 変換せずそのまま編集モードに入る（自由入力欄なし）
  - 1 つでも削除すれば空の枠が追加される

## 地点削除時の挙動

### 入力済み地点が 2 つ以上残る場合

- 削除すると上下から詰める
- 出発地削除 → 経由地 A が出発地に昇格
- 目的地削除 → 末尾の経由地が目的地に降格
- 常に「出発地（先頭）→ 経由地（中間）→ 目的地（末尾）」の順序を維持

### 入力済み地点が 1 つのみ残る場合

- **元の位置を維持する**: 目的地のみ残っていれば目的地のまま、出発地のみ残っていれば出発地のまま
- 空いたポジションに自由入力欄（null）を追加
- 例: 出発地を削除して目的地のみ → 出発地(空) + 目的地 の 2 行表示

### 共通

- 削除後、5 地点未満かつ自由入力欄がなければ、末尾に自由入力欄を自動追加
- 常に最低 2 行を表示

## 地点数制限

- 最大 **5 地点**（出発地 1 + 経由地 0〜3 + 目的地 1）
- 5 地点に達した場合、自由入力欄は非表示

## アイコン

| 位置 | 確定状態 | 編集状態 |
|------|---------|---------|
| 出発地（CurrentLocation） | 🔵 青い現在地アイコン（`MyLocation`） | 同左 |
| 出発地（Place） | ○ 空丸 | 同左 |
| 経由地（入力済み） | ○ 空丸（英字なし） | Ⓐ Ⓑ Ⓒ（丸囲み英字） |
| 目的地（入力済み） | 📍 赤い目的地アイコン（`LocationOn`、赤色） | 同左 |
| 自由入力欄（最終行＝目的地位置） | — | 📍 赤い目的地アイコン |
| 自由入力欄（中間行＝経由地位置） | — | ○ 空丸 |
| 自由入力欄（先頭行＝出発地位置） | — | ○ 空丸 |

- アイコンの実装: Material Icons + Text の組み合わせ
- 丸囲み英字: `Box` + `Text` で実装
- 経由地アイコンの色: `onSurfaceVariant`（グレー系）
- `CurrentLocation` の表示名「現在地」は UI 側でハードコード（string resource 経由）

## 区切り線

- 地点間に **縦の点線** と **水平の区切り線** を **両状態で** 表示
- 縦の点線: `Text("⋮")` で代用

## プレースホルダーテキスト

| 位置 | テキスト |
|------|---------|
| 先頭行（出発地位置）の自由入力欄 | 「出発地を入力」 |
| 中間行（経由地位置）の自由入力欄 | 「経由地を選択」 |
| 最終行（目的地位置）の自由入力欄 | 「目的地を入力」 |

※ DnD で位置が変わったら、プレースホルダーもその位置に応じて変化する

## レイアウト

- 背景: `Surface` カラー準拠の `ElevatedCard`（elevation あり、Material3 デフォルト）
- 角丸: 両状態で同じ（Material3 Shape 準拠）
- 横幅: `HomeMapTopAppBar` の `AppBarWithSearch` と同じ（呼び出し側で制御）
- カード幅: 確定状態・編集状態で **同じ幅**（内部で調整、地点名は `ellipsis` で切り詰め）
- 戻るアイコン: カード内左端に縦中央配置（両状態共通）
- 行のサイズ: コンパクトなカスタムサイズ（Material3 ListItem ではなく自前 padding）
- アニメーション: 状態切り替え時のアニメなし
- テキストスタイル: Material3 Typography 完全準拠

## 状態管理

- **編集中**: TopAppBar 内のローカル状態（`remember` / `mutableStateListOf`）
  - データ表現: `RouteWaypoint?` のリスト（`null` = 自由入力欄）
- **確定時**: ViewModel に `OnRouteWaypointsConfirmed(waypoints)` ViewEvent で地点リストを丸ごと通知
  - 通知時、自由入力欄（`null`）はリストから **除外** して渡す
  - 型: `ImmutableList<RouteWaypoint>`（non-null）
- 編集キャンセル時: ローカル状態を破棄して確定状態に戻る（確認ダイアログなし）

## データモデル

`core/model` に新設:

```kotlin
/** ルート上の地点を表す sealed interface */
@Immutable
sealed interface RouteWaypoint {
    /** 現在地 */
    @Immutable
    data class CurrentLocation(
        val latitude: Double,
        val longitude: Double,
    ) : RouteWaypoint

    /** 任意の地点 */
    @Immutable
    data class Place(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    ) : RouteWaypoint
}
```

## ViewEvent（新規追加）

| Event | 説明 |
|-------|------|
| `OnSwapOriginDestination` | 出発地と目的地を入れ替え（確定状態で反転アイコンタップ時）、即座に ViewModel に通知 |
| `OnRouteWaypointsConfirmed(waypoints: ImmutableList<RouteWaypoint>)` | 編集完了時に地点リストを丸ごと通知（null 除外済み） |
| `OnWaypointClicked(index: Int)` | 地点タップ時（別画面 / Modal への遷移、入力ロジックはスキップ） |

## コールバック設計

- `onViewEvent: (HomeMapViewEvent) -> Unit` パターンで統一（既存パターンと一貫）

## ViewModel 変更

- `waypoints: StateFlow<ImmutableList<RouteWaypoint>>` を新規追加
  - 初期値: `userLatLng` → `CurrentLocation` + `selectedResult` → `Place` から構築
  - `OnSwapOriginDestination` / `OnRouteWaypointsConfirmed` で更新

## Composable 構成

```
HomeMapRouteTopAppBar (internal fun)
  引数:
    - waypoints: ImmutableList<RouteWaypoint>
    - modifier: Modifier = Modifier
    - onViewEvent: (HomeMapViewEvent) -> Unit

├── HomeMapRouteTopAppBarConfirmed (private fun) — 確定状態
│   ├── IconButton (←) — 戻る（OnDismissRoutes）
│   ├── Column
│   │   ├── HomeMapRouteWaypointRow (private fun) — 各地点行
│   │   └── HomeMapRouteWaypointDivider (private fun) — 地点間の区切り（⋮ + 水平線）
│   └── IconButton (⚙ / ↑↓) — 設定 or 反転
│
└── HomeMapRouteTopAppBarEditing (private fun) — 編集状態
    ├── IconButton (←) — 戻る（キャンセル）
    ├── LazyColumn (DnD 対応)
    │   ├── HomeMapRouteWaypointRow — 各地点行（≡ + ×）
    │   └── HomeMapRouteWaypointDivider
    └── HorizontalDivider + TextButton("完了")
```

## HomeMapScreenContent 統合

- `routeResults.isNotEmpty()` のとき `HomeMapTopAppBar` の代わりに `HomeMapRouteTopAppBar` を表示
- `viewModel.waypoints.collectAsStateWithLifecycle()` で waypoints を取得して渡す

## 今回スキップする項目

- 地点入力画面（別画面 / Modal）の実装
- ルート再検索ロジック
- 反転時のルート再検索
- ドラッグ中のビジュアルフィードバック（影・浮き上がり等）
