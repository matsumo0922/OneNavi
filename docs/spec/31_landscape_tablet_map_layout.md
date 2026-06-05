# 31. 横画面 / タブレット向け MapScreen 分割レイアウト設計

## 0. このドキュメントの位置づけ

`MapScreen` は現状 **縦画面（狭幅）専用**で組まれており、端末を横に向けただけで UI が破綻する。
本設計では **専用 UI / 専用画面を新規追加せず、既存 UI の「配置」だけを変える**ことで横画面・
タブレットを成立させる。

中心アイデア：

> 広い画面では端（既定は右）に幅 **X** の「UI 帯（panel strip）」を作り、UI（BottomSheet / 各種
> カード / Maneuver パネル / TopAppBar）を全部そこへ寄せる。地図は全面に敷いたまま、**自車を残りの
> 反対側領域の中心**へ置く。

参照イメージ: `docs/idea/android_auto/navigation/img_14.png`（右上 Maneuver、右下 ETA、自車は中央やや左下）。

> 本設計は Claude / Codex の2系統による設計レビューを反映済み（反映ログは §13）。

---

## 1. 用語と座標定義

| 記号 | 意味 |
|---|---|
| `W` / `H` | 地図ビュー（= 画面）の実幅 / 実高さ |
| `X` | UI 帯（panel strip）の幅 |
| 帯 (panel strip) | UI を寄せる領域。既定は右端の `[W - X, W]` |
| 地図領域 | 帯に覆われない側。既定は左の `[0, W - X]` |
| 分割モード | `X > 0`（= Expanded）で帯が有効な状態 |
| `panelSide` | 帯を置く物理側（`RIGHT` 既定 / `LEFT`）。§7.3 |

- **自車センターの目標**: 地図領域の中心。既定（右帯）では x ≒ `(W - X)/2`（左右 inset 補正込み）。
- 帯は UI で覆われるが、地図領域は縦方向に UI で覆われないため**縦をフルに使える**。

---

## 2. 発動条件とブレークポイント（2段階）

`MapScreen` ルートに `BoxWithConstraints` を置き、`maxWidth`（dp）で 2 段階判定する
（`material3-adaptive` 依存は追加しない）。**Medium 帯（300〜420dp の中途半端な X）は採用しない**。

| 幅クラス | 条件（maxWidth） | レイアウト | X | 地図/scaffold 構造 |
|---|---|---|---|---|
| Compact | `< 840.dp` | 従来フル幅（**現行維持**） | `0.dp` | 地図は scaffold body 内（現行のまま） |
| Expanded | `>= 840.dp` | 分割 | `400.dp`（固定） | 地図を body から外し背景 sibling 化（§3.2） |

### 2.1 ⚠️ 挙動上の注意（合意済み）

- 幅基準のため、**多くのスマートフォンは横画面で Expanded** になる（縦 412dp 級 → 横 ~900dp ≥ 840dp）。
  → スマホ横は X=400dp 固定。地図領域は ~500dp、ほぼ正方形になる。これは許容（合意済み）。
- スマホ横は**高さが低い**（≈360〜412dp）。帯に Maneuver（上）＋ ETA（下）を縦に積むと密になるが、
  **「まず試す」方針で許容**。右帯が Maneuver と ETA で縦に埋まっても問題なしとする（合意済み・§4.1）。
- `smallestScreenWidthDp` / `WindowHeightSizeClass` ベースへの切替は将来余地として残すのみ（本設計では採らない）。

### 2.2 タブレット縦

`maxWidth >= 840.dp` を満たせばタブレット縦でも分割になる（縦でも自車は地図領域中心＝常時寄せ §4.2）。

---

## 3. レイアウト構成（Before / After）

### 3.1 Compact（< 840dp）= 現行構造を完全維持

```
MapScreen (Box, fillMaxSize)
└─ BottomSheetScaffold (fillMaxSize)
    ├─ sheetContent: 各種 Sheet（フル幅・下端）
    └─ body (Box, fillMaxSize)
        ├─ MapItem / MapEffect   （地図・オーバーレイ, fillMaxSize）
        ├─ MapScreenContent      （TopAppBar/Maneuver/ETA, fillMaxSize）
        └─ MapControls           （右下）
```

→ Compact は地図を body 内に残す。**タッチ pass-through の回帰リスクをゼロにする**（両レビュー P1）。

### 3.2 Expanded（>= 840dp）= 帯分割構造

**地図を `BottomSheetScaffold` の body から外し、全面背景の sibling にする**。scaffold は「UI
オーバーレイ + bottom sheet のコンテナ」に純化し、幅 `X`・帯側へ制約する。

```
MapScreen (BoxWithConstraints, fillMaxSize)         ← ここで幅クラス / X / panelSide を算出
│   panelLayout = MapPanelLayout(...)
│
├─ [背景] 地図レイヤー (Box, fillMaxSize)
│       ├─ MapItem    (地図, fillMaxSize)
│       └─ MapEffect  (マーカー/ポリライン)
│
├─ [中景] MapControls
│       Modifier.fillMaxSize().padding(end = X)（panelSide=RIGHT）, align BottomEnd → 地図領域の右下
│
├─ [前景] BottomSheetScaffold
│       Modifier.width(X).align(panelAlign) ＋ body 親に背景透過（§9.2）
│       ├─ sheetContent: 各種 Sheet（帯内・ドラッグ peek/expand 維持）
│       └─ body: MapScreenContent (TopAppBar/Maneuver/ETA カード)
│
└─ [最前] MapWaypointSearchScreen (全画面オーバーレイ・分割対象外)
```

**なぜ地図を body から外すのか**: `BottomSheetScaffold` の bottom sheet 位置は scaffold 内部で決まり、
`sheetMaxWidth` を指定しても**中央寄せ**にしかできず帯側へ寄せられない。scaffold 自体を幅 `X`・帯側へ
制約すれば、シートも body のカードも自動的に帯へ収まり、**ドラッグ挙動を保ったまま帯寄せ**が成立する。
Expanded では帯幅 X 以外の地図領域は scaffold の外なので、Compact で懸念したタッチ pass-through 問題が
そもそも起きない。

---

## 4. コンポーネント別の配置仕様（Expanded）

### 4.1 MapScreenContent（TopAppBar / Maneuver / 各種カード）

- body が幅 `X` に制約されるため、内部の `fillMaxWidth` / `TopCenter` / `BottomCenter` は**そのまま帯内に
  収まる**（実質コード変更不要）。
- **Maneuver（上）＋ ETA（下）が帯の縦をほぼ埋めてよい**（合意済み）。低身長横でも分割は適用し、
  元の崩れを確実に解消することを優先する。
- Rerouting パネル（`MapNavigationReroutingPanel`）は Maneuver と同じ**上部スロット**に出るため帯上部へ
  自動追従（専用高さ不要）。AddWaypoint / Alternatives / 経由地編集の各 overlay カードは**下部スロット**で
  `(帯高/2, 240dp)` ルールを共有する（OD-7）。`Arrived` は帯に専用 UI を持たない（§10）。
- Browsing の `MapTopAppBar`（畳んだ検索バー＋設定）も帯内（右上）に入る（仕様文どおり）。
  **タップ展開は `ExpandedFullScreenSearchBar` の全画面のまま**（§4.6・Codex Finding 7）。
- overlay カード高さ `bottomFloatingCardHeight`（`MapNavigationContent` L112）は分割時
  **`(帯高さ / 2).coerceAtLeast(240.dp)`** とする（横画面で代替ルート一覧等が読める高さを確保。
  両レビュー指摘）。Compact は現状 `maxHeight / 3f` のまま。
  - 「帯高さ」は `MapNavigationContent` の `BoxWithConstraints.maxHeight`（= body の制約高さ）を使う。
    `BottomSheetScaffold` の内部レイアウト次第で peek 分が body から引かれ `maxHeight` が想定より小さくなる
    可能性があるため、`coerceAtLeast(240.dp)` の下限で吸収する（前回 新 F）。

### 4.2 自車センタリング（常時寄せ）

- 分割モードでは **Browsing 含め常時** 自車を地図領域中心へ置く（合意済み）。
- 右に UI が無い Browsing でも寄せ固定。モード遷移・回転でカメラが横滑りしないことを優先。

### 4.3 BottomSheet

- `BottomSheetScaffold` を**継続使用**し、ドラッグ peek/expand を維持。
- 分割では scaffold を `width(X)` + 帯側 align に制約 → シートは帯下端に寄って出る。
- body 親に背景透過を明示（地図 sibling を透かす）。シート本体（drag handle / surface 色）は維持。
  Expanded まで開いた時に body が白塗りにならないか実機確認（§9.2）。

### 4.4 MapControls（ズーム / コンパス / 現在地 FAB）

- **地図領域の右下**（合意済み）。`MapScreenContent` の外の sibling。
- panelSide=RIGHT 時 `Modifier.fillMaxSize().padding(end = X)`（**この Modifier 順序必須**。
  逆順だと画面外に消える・Claude 7-B）→ `align(BottomEnd)`。
- bottom padding（`controlsBottomPadding`）は分割時**帯のカード/シート高さに追従しない**。
  `navigationBars` インセットのみ。Compact は現状の `animateDpAsState` を維持。
- a11y: compass / zoom / 現在地 FAB に `contentDescription` を付与（現状 null。§9.5）。

### 4.5 MapWaypointSearchScreen

- **全画面のまま**（合意済み）。分割対象外。実装変更なし。

### 4.6 Browsing 検索の展開状態

- 畳んだ検索バー: 帯内（右上、幅 X）。
- 展開（`ExpandedFullScreenSearchBar`）: **全画面**（WaypointSearch の全画面方針と整合）。帯幅へ
  押し込む改造はしない。

---

## 5. ASCII レイアウトイメージ（Expanded / 案内中）

```
┌───────────────────────────────────────────────┬───────────────┐
│                                                │  Maneuver      │ ← 右帯 (X=400dp)
│            地図領域 (W - X)                       │  パネル         │
│            地図のみ                              │                │
│                                                │  (地図透過)     │
│                  ▲ 自車                         │                │
│            x≒(W-X)/2, 下から25%                  ├───────────────┤
│                                          ┌──┐  │  ETA カード     │
│                                          │＋│  │  到着 / 距離     │ ← Maneuver と ETA で
│                                          │－│  │  [×][代替][+]   │   帯が縦に埋まってよい
│                                  MapCtrl │◎│  │               │
└──────────────────────────────────────────┴──┴──┴───────────────┘
                                            ↑ 地図領域の右下
```

---

## 6. カメラ padding 再設計

GoogleMap は `setPadding(start, top, end, bottom)` の **padding を除いた可視矩形の中心（画面 pixel）**へ
camera target を置く。自車追従では `target = 自車の緯度経度そのもの`（`VehicleCameraPositionFactory`
`centeredVehicleCameraPosition` / projection オフセットなし）なので、**自車の画面位置は完全に setPadding で
決まる**。

### 6.0 分割モードの padding 規則（状態非依存に一本化）

分割モードでは **UI は全部帯にある**ため、地図領域側の縦 padding は UI 要素高さに依存しない。
これにより Browsing / Navigating / 各 overlay を個別に書かずに済む（両レビュー：overlay 分岐爆発の解消）。

| 軸 | 値（panelSide=RIGHT） |
|---|---|
| 水平基底 `base` | `max(24dp, 左の水平 safe inset, 右の水平 safe inset)`（**左右対称化**・§7.4） |
| start | `base` |
| end | `base + X`（→ 可視中心 x = `(W - X)/2` を厳密に保つ。新 C） |
| top | 非案内: `statusBars` インセット / 案内中: §6.3 で上書き（UI 高さは足さない） |
| bottom | `navigationBars` インセット（案内中の下部アンカーは top 側で表現・§6.3） |

> `start`/`end` の基底を左右 inset の**最大値で対称化**することで、cutout / navbar が左右非対称な
> 端末でも自車中心が `(W - X)/2` からずれない（前回レビュー 新 C への対応）。

**panelSide=LEFT の場合**（将来の car surface / 運転席側対応・§7.3）は水平を反転し `start = base + X` /
`end = base` とする（自車中心は `(W + X)/2` ＝ 右側地図領域の中心）。top/bottom は panelSide に依らず共通。

### 6.1 水平方向（地図領域中心へ寄せる）

可視中心 x = `(start + (W - end))/2`。`end = start + X` とすると中心 = `(W - X)/2`（start 値に依らない）。
`start`/`end` の基底を左右 inset の最大値で**対称化**するため（§6.0）、非対称 cutout 端末でも中心は
厳密に `(W - X)/2` に保たれる。

> **tilt（3D, 45°）でも成立**: 自車追従中は camera target = 自車の緯度経度であり、target は
> tilt / bearing に関係なく padded 矩形の**画面中心ピクセル**へ投影される（foreshortening は target
> 以外の点に効く）。よって水平位置は tilt でも `(W - X)/2`。
> なお `moveTo()` 経由（Browsing の地点表示・place details 等）は target を当該地点へ明示的に置くが、
> 同じ setPadding が効くため当該地点が `(W - X)/2`（地図領域中心）に出る ＝ 地点を地図領域、詳細を帯、
> という意図どおりの配置になる。追従 / 非追従いずれも実機確認を受け入れ条件に含める（§9.4・前回 2-A）。

### 6.2 垂直方向・非案内

`top = statusBars`, `bottom = navigationBars` → 自車は地図領域の概ね縦中央。Browsing はこれで足りる
（案内ではないため前方視野の確保は不要）。

### 6.3 垂直方向・案内中（下から25%アンカー）

分割案内では自車を**地図領域の下から `f`=25%（= 上から 75%）**に置く。`GuidanceCameraPadding` に
アンカー率 `f` を渡し、padded 中心が `(1 - f) * H` になる top を返す（bottom は実 obstruction の navBar の
まま）：

**目標**: 自車（= camera target）を**画面下端から `d` px 上**（target_y = `H - d`）に置く。GoogleMap は
target を padded 矩形の縦中心へ置くので、`bottom` は実 obstruction の navBar（= `rawBottom`）のまま、`top`
側で目標を表現する：

```
target_y = padded中心_y = (top + (H - rawBottom)) / 2 = H - d
⇒ top = H - 2d + rawBottom        (rawBottom = navigationBars インセット)
```

> **二重算入ではない（前回 3R Claude の確認点）**: `rawBottom` は setPadding の `bottom` にも渡る実値なので
> 中心計算で打ち消し合い、最終的に target_y = `H - d`（= 画面下端から `d`）に一致する。`d` は **画面下端
> からの距離**であって navBar からの距離ではない。

`d` は通常 `0.25H`（下から25%）。ただし低身長横 + 3-button nav で puck が navBar に被らないよう**下限**を
設ける（前回 Codex 2R）：

```
d   = max(0.25 * H, rawBottom + puckRadiusPx + gapPx)
top = (H - 2 * d + rawBottom).coerceAtLeast(0)
```

`puckRadiusPx` ≒ 32dp、`gapPx` ≒ 8dp。下限が効く短い画面では自車は **`0.75H` より上へ動く**（これが意図。
puck を navBar から離すため、クランプ時は 0.75H と一致しないのが正しい）。`d=0.25H` の通常時は
`top = 0.5H + rawBottom` → 中心 `0.75H`、H≈412dp で自車は下端から ≈103dp（前回 Codex Finding 5）。

**API シグネチャ（前回レビュー 新 A への対応）** — `GuidanceCameraPadding.resolveTopPaddingPx` に
`anchorFractionFromBottom: Float?` を追加し、Compact（カード相対）と分割（率＋下限）を 1 メソッドで共存：

```kotlin
fun resolveTopPaddingPx(
    isGuidanceFollowActive: Boolean,
    mapViewHeightPx: Int,           // = H
    rawTopPaddingPx: Int,
    rawBottomPaddingPx: Int,        // = rawBottom (= navBar インセット)
    density: Float,
    anchorFractionFromBottom: Float? = null,   // null=Compact / 0.25f=分割
): Int
// active && fraction != null :
//     d   = max(fraction * H, rawBottom + puckRadiusPx + gapPx)
//     top = (H - 2 * d + rawBottom).coerceAtLeast(0)
// active && fraction == null : top = H - rawBottom - 2 * margin   （現行 Compact・カード相対）
// !active                    : top = rawTopPaddingPx
```

`margin = VEHICLE_ANCHOR_MARGIN_FROM_BOTTOM_DP = 32dp`、`puckRadiusPx/gapPx` は density 換算定数。
`MapCameraEffect` は分割案内時のみ `0.25f` を渡す。

### 6.4 ⚠️ viewport サイズ変化時の再適用（既存バグ修正）

現状 `MapCameraState.updateViewportSize`（L114–118）は幅/高さを保存するだけで **`applyCameraPadding()`
を呼ばない**。案内中 top padding は `mapViewHeightPx` 依存なので、分割画面/マルチウィンドウで**高さだけ**
変わると下部アンカーが古い高さのまま残る（Codex Finding 4・既存バグ）。

**対応**: `updateViewportSize` の末尾で `applyCameraPadding()` ＋ `moveFollowCameraIfNeeded()` を呼ぶ。
カメラ padding 再計算トリガに「viewport サイズ変化」も含める。

> **連打ガード（前回レビュー 新 D）**: `updateViewportSize` は `onSizeChanged` から呼ばれ、分割画面の
> リサイズ中は連続発火しうる。Maneuver フォーカスのアニメーション中に padding が差し込まれて跳ねるのを
> 避けるため、**前回と同じ width/height なら early return** するガードを先頭に入れる（サイズ不変時は
> 副作用を起こさない）。

### 6.5 遷移時のカメラ挙動

幅/高さクラス変化（回転・分割画面・マルチウィンドウ）時は **即時スナップ**（padding 即適用）。回転は
config 変更で画面が作り直されるため違和感は小さい（合意済み）。アニメーション補間はしない。

> **進行中アニメの扱い（前回 Codex 2R / Finding 3）**: padding スナップ時に fly-to / follow animator が
> 走っていれば**キャンセル**してから snap する。ただし `updatePadding` は **peek 高さ変化など通常の
> padding 更新でも呼ばれる**ため、そこに cancel を入れてはいけない（通常更新中の追従アニメを壊す・前回 3R
> Codex）。**config / 幅クラス変化専用の経路**として `MapCameraState.onPanelLayoutChanged()` を新設し、
> こちらでのみ `cameraAnimator.cancel()` ＋ padding 即適用を行う。呼び出しは `MapPanelLayout`（幅クラス /
> X / panelSide）変化を key にした専用 `LaunchedEffect`（§6.6 の viewport key と同経路）から行う。
> なお configChanges 未宣言（§9.6）のため、回転では Activity ごと再生成され animator も消える。

### 6.6 Maneuver フォーカス / ルート全体表示

- Maneuver フォーカス（top-down, tilt=0）も §6.1 の水平 padding（`end = start + X`）を**継続**し、自車を
  地図領域中心の真上拡大で見せる（合意・将来オプションとして中央化の余地は残す）。
- `showRouteOverview` は現在の setPadding を尊重するため、ルートは自動的に地図領域へ収まる。追加 padding
  `ROUTE_OVERVIEW_PADDING_PX = 64px` は分割でも**共通値**を使う（必要なら実機で X 比補正を検討・Claude 2-C）。
- route overview / padding 系 `LaunchedEffect`（`MapCameraEffect` L120, L210）の key に **X / 幅クラス /
  viewport サイズ**を追加し、取りこぼしで古い padding が残らないようにする（Claude 4-B）。
  - **viewport サイズの key 取得経路（前回 Codex 第2ラウンド）**: `MapCameraState` の `mapViewWidthPx/HeightPx`
    は private で observable でない。effect key には **ルートの `BoxWithConstraints` が既に持つ `maxWidth/
    maxHeight`**（§7.1 で算出に使うもの）を px 換算して `MapCameraEffect` に渡し、それを key にする。
    `MapCameraState` 内部の px は従来どおり `updateViewportSize`（`onSizeChanged`）で更新する（二経路だが、
    片方は state の内部計算用、片方は effect の再実行トリガ用と役割が分かれる）。
    - **前提（前回 3R Claude）**: この方式は `MapItem` が常に `fillMaxSize` で、制約 dp の px 換算と
      `onSizeChanged` の px が等価であることに依存する。MapItem のサイズが制約と乖離する変更を将来入れる
      場合は、effect key の経路（observable な実描画サイズの持ち回し）を見直すこと。

---

## 7. 状態・アーキテクチャ変更

### 7.1 レイアウト記述子 `MapPanelLayout`

```kotlin
/** 地図画面の幅クラスと UI 帯（panel strip）の寸法・配置側。 */
@Immutable
internal data class MapPanelLayout(
    val widthSizeClass: MapWidthSizeClass,
    val panelWidth: Dp,           // X（Compact では 0.dp）
    val panelSide: MapPanelSide,  // 既定 RIGHT
) {
    /** 分割（帯）レイアウトが有効か。 */
    val isSplit: Boolean get() = panelWidth > 0.dp
}

/** 地図画面のレイアウト分岐に使う幅クラス（2段階）。 */
internal enum class MapWidthSizeClass { COMPACT, EXPANDED }

/** UI 帯を置く物理側。RTL に依らず物理位置で扱う（§7.3）。 */
internal enum class MapPanelSide { LEFT, RIGHT }
```

- `MapScreen` ルートの `BoxWithConstraints.maxWidth` から算出（§2）。
- `panelWidth`（X）/ `panelSide` を `MapCameraEffect` / `MapControls` / `BottomSheetScaffold` /
  `MapScreenContent` へ渡し、各自で幅制約・align・padding を分岐。

### 7.2 データフロー

```
BoxWithConstraints.maxWidth ─▶ MapPanelLayout ─┬─▶ Scaffold modifier (width(X)+align, 透過)
                                               ├─▶ MapControls modifier (padding(side=X))
                                               └─▶ MapCameraEffect (X, panelSide, isSplit)
                                                     ├─▶ MapCameraState.updatePadding(start, top, end, bottom,
                                                     │        guidanceAnchorFraction: Float?)   // 分割案内=0.25f
                                                     │        └─▶ GuidanceCameraPadding（案内中 top を率＋下限で上書き）
                                                     └─▶ MapCameraState.onPanelLayoutChanged()  // 幅クラス/X 変化専用
                                                              └─▶ cameraAnimator.cancel() ＋ padding 即適用（§6.5）
```

- `MapCameraState` の公開 API 増分は最小：`updatePadding` に `guidanceAnchorFraction: Float? = null` を追加
  （格納して `resolveTopPaddingPx` へ中継）、`updateViewportSize` の再適用（§6.4）、config 変化専用の
  `onPanelLayoutChanged()`（§6.5）の3点のみ。
- カメラ状態機械（fly-to / Maneuver フォーカス / 追従）の**挙動は変えない**。

### 7.3 RTL の扱い

`panelSide` は **物理 Left/Right** で持つ。`Alignment.End` / `padding(end=...)` は RTL で反転するため、
帯まわりの配置・padding 算出は `panelSide` を直接見て決める（RTL 反転に依存しない）。本設計は LTR 前提だが、
panelSide 抽象により将来の car surface / 運転席側設定や RTL へ拡張しやすくする（Claude 7-C）。

### 7.4 横画面の水平 safe inset

横画面では navigation bar / display cutout が**左右**に出る端末がある。カメラの水平基底 `base`（§6.0）は
**左右の水平 safe inset（`WindowInsets.safeDrawing` 等）の最大値**を取り、`start=base` / `end=base+X` と
**対称化**して自車中心を `(W-X)/2` に固定する（前回 新 C）。

帯（panelSide 側）の幅 X は**画面端まで**の見かけ幅とし、帯内コンテンツ（カード / シート / 検索バー）は
既存の `statusBarsPadding()` / `navigationBarsPadding()` 等で**自前に inset 回避**する（現行カードがすでに
そうしている）。これにより「X が safe 領域の内か外か」を別途定義せずに済む（Codex Finding 10 / OD-6）。

---

## 8. ファイル別変更一覧

| ファイル | 変更概要 |
|---|---|
| `MapScreen.kt` | ルートを `BoxWithConstraints` 化し `MapPanelLayout` 算出。**Expanded のみ**地図レイヤーを scaffold body の外（背景 sibling）へ。Scaffold に `width(X)+align`（分割）/ `fillMaxSize`（Compact）。body 親背景を透過。`MapControls` に `padding(side=X)`。`controlsBottomPadding` を分割で navBar インセットのみに分岐。Compact は現行コードパスを維持。 |
| `state/MapPanelLayout.kt`（新規） | `MapPanelLayout` / `MapWidthSizeClass` / `MapPanelSide` と `maxWidth → 算出` ヘルパ。 |
| `MapCameraEffect.kt` | padding 計算 LaunchedEffect（**L120–143**）を `isSplit` / `panelSide` で分岐（§6.0 規則）。水平 `end=base+X`、垂直は分割時インセットのみ／案内中は率 `0.25f` を `GuidanceCameraPadding` へ渡す。**この L120 LaunchedEffect の key に幅クラス・X・viewport サイズを必ず追加**（前回 新 B：Compact→Expanded 遷移時に古い `bottomSheetPeekHeight/navigationCardHeight` 由来の padding が残らないように）。route overview 用 LaunchedEffect（L210）の key にも同様に追加。引数に `panelLayout` 追加。 |
| `state/MapCameraState.kt` | `updatePadding` に `guidanceAnchorFraction: Float? = null` を追加し格納＋`resolveTopPaddingPx` へ中継。`updateViewportSize` 先頭に**同値なら early return** ガード（新 D）、末尾で `applyCameraPadding()`＋`moveFollowCameraIfNeeded()`（§6.4）。config/幅クラス変化専用の `onPanelLayoutChanged()` を新設（`cameraAnimator.cancel()`＋padding 即適用・§6.5）。 |
| `state/GuidanceCameraPadding.kt` | `resolveTopPaddingPx` に `anchorFractionFromBottom: Float?` を追加（§6.3 の新シグネチャ）。Compact=null（カード相対・現行 `H-rawBottom-2margin`）/ 分割=`0.25f`（`d=max(0.25H, rawBottom+puckRadius+gap)`, `top=H-2d+rawBottom`・clamp 込み）。 |
| `components/MapControls.kt` | `MapPanelLayout` を受け、分割時 `padding(side=X)`（順序固定）。`contentDescription` 付与。本体ロジック不変。 |
| `components/content/MapNavigationContent.kt` | `bottomFloatingCardHeight` を分割時 `(帯高/2).coerceAtLeast(240dp)` に分岐。他は body 幅制約で自動追従。 |
| `components/content/MapBrowsingContent.kt` / `MapRoutePreviewContent.kt` | 幅制約で自動追従。検索の展開は全画面のまま（§4.6）。 |
| `MapItem.kt` | Expanded で背景 sibling 化に伴う配置変更のみ。`updateViewportSize` は従来どおり全画面サイズを通知（再適用は MapCameraState 側で）。 |

---

## 9. 実装上の検証事項・受け入れ条件

1. **タッチ pass-through**: Expanded で地図領域（scaffold 外）の tap / long-tap / pinch / pan が地図へ届く。
   `NavigationWaypointEditor` の経由地マーカードラッグ等の地図ジェスチャも機能する（前回 6-D）。
   Compact は現行構造維持により回帰なしを確認（両レビュー P1）。
2. **`body` 背景透過 + Sheet Expanded 時の白塗り**: 帯を全展開してもカード背後に地図が見え、白塗りに
   ならない（Claude 1-B）。**合格基準**＝帯領域でカード/シート以外の余白から背景地図が透けて見えること。
   **検証失敗時の代替**＝`BottomSheetScaffold(containerColor=Transparent)` に加え、body 直下の `Box` へ
   `Modifier.background(Color.Transparent)` を明示付与（場合により body を描画させず overlay 専用にする）。
3. **検索バー / 展開**: 畳んだ検索バーが帯幅で成立し、設定アイコンと共存。展開は全画面で破綻しない（§4.6）。
   なおサジェスト候補リストは **展開（全画面）側**に出るため、帯幅 anchor からはみ出す DropdownMenu 問題は
   生じない（前回 7-A は §4.6 全画面方針により無効化）。
4. **tilt 時の自車水平位置**: 案内（tilt=45）/ Maneuver フォーカス（tilt=0）で自車が地図領域中心から
   許容外に水平ずれしないことを実機計測（§6.1・Claude 2-A）。
5. **a11y**: compass / zoom / 現在地 FAB の TalkBack ラベル。最小タップ 48dp（充足済み）。
6. **回転・分割画面・マルチウィンドウ**: viewport サイズ変化で padding が即再適用され（§6.4）、案内中の
   下部アンカーが古い高さで残らない。MapView ライフサイクル（`remember(context)` 再生成）に競合がない。
   - **MapView 再生成（前回 Codex 第2ラウンド）**: `MainActivity` は `android:configChanges` を**宣言して
     いない**ため、回転・分割画面リサイズ・折りたたみ等の config 変更で **Activity ごと再生成**される。
     よって Compact↔Expanded の構造差（地図が body 内↔sibling）に伴う MapView 再生成は**追加コストにならない**
     （元々再生成される）。将来 `configChanges` を宣言して回転を滑らかにする場合は、MapItem を両モードで
     **安定した位置**に保つ設計へ見直すこと（その時は Compact のタッチ pass-through を別途解決する）。
7. **haze**: consumer が未配線で `hazeSource` だけが残っていたため削除済み。将来 blur を再導入する場合は
   SurfaceView 越えの可否と target 側の配線を別途確認（Codex Finding 2 / Claude 1-C）。
8. **Google ロゴ / 帰属表示（ToS 必須）**: setPadding 移動後もロゴ・帰属が地図領域内に見え、MapControls /
   カードと競合しない（合意：SDK 既定位置のまま検証）。
9. **低身長横の密度**: Maneuver＋ETA が帯縦を埋めても可読・操作可能（合意：まず試す）。overlay カードは
   `(帯高/2, 最低240dp)` で一覧が読める。

---

## 10. 非対象（Non-goals）

- 横画面・タブレット**専用の新規 UI / 画面**は作らない（配置変更のみ）。
- `Arrived` 状態は現状どおり帯内に専用 UI を持たない（`Unit`）。分割でも帯に何も出ない（Claude 6-B）。
- `MapWaypointSearchScreen` / Browsing 展開検索は全画面のまま（帯化しない）。
- iOS 対象外（map 実装は androidMain のみ）。
- RTL は LTR 前提（panelSide 抽象で将来余地のみ確保）。
- `smallestScreenWidthDp` / `WindowHeightSizeClass` 判定への切替（将来余地として記載のみ）。
- カメラ状態機械（fly-to / Maneuver フォーカス / 追従ロジック）の挙動変更。

---

## 11. 決定事項ログ

| # | 論点 | 決定 |
|---|---|---|
| 1 | 発動条件 / X | 2段階。`<840dp`=Compact（現行フル幅）/ `>=840dp`=Expanded（X=`400dp` 固定）。Medium 廃止 |
| 2 | Compact 構造 | 現行維持（地図は scaffold body 内）。分割は Expanded のみ別構造 |
| 3 | 左寄せタイミング | 分割では**常時** 地図領域中心へ |
| 4 | 案内中の縦位置 | 地図領域の**下から 25%**（率指定アンカー） |
| 5 | 非案内の縦位置 | インセットのみ（概ね縦中央） |
| 6 | overlay カード高 | 分割時 `(帯高/2).coerceAtLeast(240dp)` |
| 7 | BottomSheet | ドラッグ挙動維持・幅 X・帯寄せ（地図を body 外へ・Expanded のみ） |
| 8 | MapControls | 地図領域の右下（`padding(side=X)`・順序固定） |
| 9 | Browsing 検索 | 畳んだバーは帯内 / 展開は全画面 |
| 10 | WaypointSearch | 全画面のまま |
| 11 | panelSide | パラメータ化（物理 L/R・既定 R・LTR 固定） |
| 12 | 水平 safe inset | start/end・帯幅に算入。基底は左右 inset の**最大値で対称化**し自車中心を `(W-X)/2` に固定 |
| 13 | 遷移時カメラ | 即時スナップ |
| 14 | viewport 再適用 | `updateViewportSize` で padding 再適用（既存バグ修正） |

---

## 12. 低身長横（スマホ横）に関する既知トレードオフ

両レビューが HIGH で指摘した「スマホ横は Expanded=400dp 固定 + 低身長で帯が密」点は、**まず現方針で
試す**と決定（§2.1）。将来 UX 上の問題が顕在化した場合の打ち手を記録しておく：

- `WindowHeightSizeClass` を併用し、低身長横は帯を「単一スクロール列（Maneuver→ETA 縦積み）」に切替。
- `X` をスマホ横では `min(400dp, maxWidth * 0.45f)` 等にクランプ。
- 低身長横のみ従来フル幅へフォールバック（横画面の崩れは別途解消）。

いずれも本設計のスコープ外。`MapPanelLayout` に幅クラスを持たせてあるため、後から `heightSizeClass` を
追加して分岐を足せる構造にしてある。

---

## 13. 設計レビュー反映ログ

Claude / Codex の2系統レビュー（HIGH/MED 中心）と本設計での処置：

| 指摘（要旨） | 重大度 | 処置 |
|---|---|---|
| Compact の map sibling 化でタッチ pass-through 回帰 | HIGH×2 | Compact は現行構造維持（§3.1） |
| 案内中 `rawBottom=navBar` だと自車が最下部に寄り過ぎ | HIGH/MED | 下から25%率アンカーへ（§6.3） |
| `updateViewportSize` が padding を再適用しない（既存バグ） | HIGH | `applyCameraPadding` 追加（§6.4） |
| スマホ横 Expanded=400dp で低身長・カード過短 | HIGH×2 | まず試す＋overlay カード `(帯高/2,240dp)`／将来余地（§12） |
| overlay 状態ごとの padding 分岐爆発 | HIGH | 分割 padding 規則を状態非依存に一本化（§6.0） |
| tilt 時の水平ずれ懸念 | HIGH | target=自車で pixel 中心固定、非問題と整理＋実機確認（§6.1） |
| Browsing 検索は全画面 ExpandedSearchBar | MED | 展開は全画面のまま（§4.6） |
| 横画面の左右 navbar / cutout inset 未定義 | MED | 水平 safe inset を算入（§7.4） |
| viewport / 幅クラス変化の effect key 漏れ | MED | key に X・幅クラス・viewport 追加（§6.6） |
| 遷移時のカメラ瞬間移動の方針未定義 | MED | 即時スナップと決定（§6.5） |
| haze の未使用配線 | MED | `hazeSource` だけの無効な配線は削除済み（§9.7） |
| `containerColor` 透過 / Sheet 白塗り | MED | 検証項目化（§9.2） |
| Modifier 順序 / a11y ラベル / RTL | LOW | 順序固定・ラベル付与・panelSide 物理側（§4.4, §7.3） |

**第2ラウンド（改訂版の再検証で追加された指摘）:**

| 指摘（要旨） | 重大度 | 処置 |
|---|---|---|
| §6.3 一般化後の `GuidanceCameraPadding` API 未定義（新 A） | P1 | 新シグネチャ `anchorFractionFromBottom: Float?` を明記。式を率指定へ変更（**最終形は §6.3 の clamp 込み `d=max(0.25H, rawBottom+puckRadius+gap)` / `top=H-2d+rawBottom`** が正・3R で統合） |
| §6.0 一本化と L120 既存 LaunchedEffect の key 漏れ（新 B） | P1 | §8 に「L120 padding LaunchedEffect へ幅クラス/X/viewport を key 追加」を明示 |
| §6.0 `end` 定義が非対称 inset で中心ずれ（新 C） | P1 | 水平基底を左右 inset の**最大値で対称化**し中心を `(W-X)/2` に固定（§6.0/§6.1） |
| `updateViewportSize` 連打でフォーカス中カメラ跳ね（新 D） | P2 | 同値 early return ガードを追加（§6.4/§8） |
| §9.2 受け入れ基準・代替が不明（新 E） | P2 | 合格基準＋失敗時代替（body 透過）を追記（§9.2） |
| `(帯高/2)` の帯高取得方法未定義（新 F） | P2 | `BoxWithConstraints.maxHeight` 使用・peek 引かれ得る点を下限で吸収（§4.1） |
| WaypointEditor マーカードラッグ（6-D） | P2 | §9 項目1 のタッチ検証に包含 |
| 検索 DropdownMenu はみ出し（7-A） | LOW | 展開＝全画面のため無効化（§4.6） |

**第2ラウンド（Codex の追加検証）:**

| 指摘（要旨） | 重大度 | 処置 |
|---|---|---|
| 水平 safe inset と X の物理 L/R 式が実装者解釈依存 | HIGH | base を左右 inset 最大で対称化・帯コンテンツは自前 inset 回避（§6.0/§7.4） |
| 25% アンカーに低身長時の下限なし | MED | `d=max(0.25H, navBar+puckRadius+gap)` の下限クランプ（§6.3） |
| `GuidanceCameraPadding` fraction API が抽象的 | MED | `anchorFractionFromBottom: Float?` の具体シグネチャ明記（§6.3/§8） |
| viewport size が effect key 用に observable でない | MED | ルート `BoxWithConstraints.maxWidth/Height` を key 経路に使う（§6.6） |
| Compact↔Expanded 遷移で MapView 再生成 | MED | `configChanges` 未宣言で Activity 毎回再生成＝追加コストなしと確認（§9.6） |
| padding snap 中の fly-to cancel/継続が未定義 | MED | config/幅クラス変化経路で animator を cancel（§6.5） |
| Rerouting / 各 overlay の高さ方針が一部のみ | MED（OD-7） | Rerouting=上部スロット自動追従・overlay=下部スロット共通ルール（§4.1） |

**第3ラウンド（最終確認）:**

| 指摘（要旨） | 重大度 | 処置 |
|---|---|---|
| §6.3 25% アンカー式の二重算入懸念 | MED | False positive（`rawBottom` は中心計算で打ち消す）。式の意図を明記し2ブロックを統合（§6.3） |
| §8 の clamp 式と未 clamp 式の不一致 | MED | §8 GuidanceCameraPadding 行を clamped 形へ統一（§6.3 参照） |
| `anchorFractionFromBottom` の `MapCameraState` 受け渡し API 未具体化 | MED | `updatePadding(..., guidanceAnchorFraction: Float?)` を明記（§7.2/§8） |
| animator cancel が通常 padding 更新と区別不能 | MED | config/幅クラス変化専用メソッド `onPanelLayoutChanged()` を分離（§6.5/§8） |
| viewport key の `fillMaxSize` 前提が暗黙 | LOW | 前提を注記（§6.6） |
| `MapPanelSide.LEFT` の padding 式未明記 | LOW | LEFT=`start=base+X`/`end=base` を明記（§6.0） |
| §13 末尾の余分なコードフェンス | LOW | 削除 |
