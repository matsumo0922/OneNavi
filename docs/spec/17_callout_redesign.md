# 17. Callout 再設計プラン

## 背景

OneNavi のルートプレビュー画面で、Google Maps 風の吹き出し（Callout）UI を表示している。現状実装には複数の問題があり、全面的に書き直す必要がある。

詳細な配置ロジック調査は `docs/research/google_navigation_callout_placement.md` を参照。

### 現状の問題点

- Mapbox → Google Maps 移行時に、Mapbox SDK が提供していた 9-patch 画像ベースの Callout からの置き換えが雑に行われ、簡素な代替に妥協した
- 吹き出し口が常に下中央から真下に伸びている（Google Maps は矩形の角から斜め 45° に出る）
- 吹き出し口の先端がアンカー点に正確に一致していない
- 地図をパン/ズームしても Callout が連動せず、`OnCameraIdleListener` 後にワープする
- 複数 Callout 間の衝突回避ロジックが `GoogleMapCalloutPositioner` にあるが UI サイズがハードコードで壊れやすい

### 実現したい仕様

- 吹き出し口は矩形の 4 コーナーのいずれかから 45° で外に伸びる三角形
- 吹き出し口の先端が指定アンカー点に厳密に一致
- 2 つの配置戦略:
  - `AvoidOverlap`（ルートプレビュー）: Callout 同士の重なり回避を優先、アンカーはルート上で候補点を試せる
  - `AnchorFirst`（ナビ中の交差点/事故）: アンカー固定、重なり許容
- 中身は**スロットパターン**で複数画面/用途から再利用可能
- 地図のパン/ズームに追従

---

## 設計方針（確定分）

### API 骨子

```kotlin
enum class CalloutTailDirection { TopLeft, TopRight, BottomLeft, BottomRight }

@Immutable
sealed interface CalloutAnchor {
    val id: Any
    val primaryPoint: Offset

    @Immutable
    data class Flexible(
        override val id: Any,
        override val primaryPoint: Offset,
        val candidates: ImmutableList<Offset>,
    ) : CalloutAnchor

    @Immutable
    data class Fixed(
        override val id: Any,
        override val primaryPoint: Offset,
    ) : CalloutAnchor
}

enum class CalloutPlacementStrategy { AvoidOverlap, AnchorFirst }

@Composable
fun CalloutLayer(
    anchors: ImmutableList<CalloutAnchor>,
    placementStrategy: CalloutPlacementStrategy,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, tailDirection: CalloutTailDirection) -> Unit,
)

@Composable
fun Callout(
    tailDirection: CalloutTailDirection,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit,
)
```

### 形状実装

- `GenericShape` + `Path.op(roundedRect, triangle, Union)` により、角丸矩形とコーナー三角形を 1 つの Path にマージ
- `Modifier.background(color, shape)` に渡すのみ。Canvas 手描き不使用
- Tail は 45°、対象コーナーから外側へ伸びる

### 配置アルゴリズム

`SubcomposeLayout` ベースの 2 パス:
1. Pass 1: 仮 Tail 方向で subcompose → 各 Callout の実測サイズを取得
2. Pass 2: `CalloutPlacement.compute(anchors, sizes, screenSize, strategy)` が pure function で (candidatePoint × 4 tailDirection) を列挙し、画面内収容 & 衝突回避条件で最適組を選ぶ
3. Pass 3: 確定 Tail 方向で再 subcompose し `place()`

### カメラ追従

`OnCameraMoveListener` で毎フレーム screen 座標を再計算し `mutableStateOf` に反映。`CalloutLayer` は自動で再配置される。

---

## Discoveries (Round 1)

### Assumptions Challenged

| Assumption | Finding | Impact | Decision |
|---|---|---|---|
| Pass 1/Pass 3 のサイズは Tail 方向が変わっても同一 | Tail 方向側に padding が入る設計だとサイズが方向依存で変わる → Pass 1 結果が Pass 3 で無効になる可能性 | High | **全方向に max 余白を固定確保**。方向に関係なくサイズ一定にする |
| `core/ui` は commonMain (KMP + iOS) なので、Android-only の Google Maps SDK との境界設計が必要 | LatLng→Screen 変換は androidMain 側、CalloutLayer は commonMain に置いて Offset だけ受け取る案と、全部 androidMain に閉じ込める案が対立 | High | **`core/ui/src/androidMain/` に配置**。iOS への露出を諦め、`expect/actual` の stub 量産を回避 |
| 将来の `NavigationView` 移行との互換性を考慮すべきか | `docs/logs/3_mapbox_to_google_navigation_migration_plan_codex.md` は **stale**。NavigationView 移行は行わない | Blocker for abstraction design | **`GoogleMap` + `MapView` 前提で設計を固定**。抽象化レイヤー/インターフェース化は不要 |

### Decisions Made

| Topic | Decision | Rationale | Risk Level |
|---|---|---|---|
| Tail 余白 | 全方向に max tail length 分の padding を常時確保 | Pass 1/Pass 3 のサイズ不変性を保証し、配置計算を単純化 | Low（余白分の占有面積増で衝突が起きやすくなるのは許容） |
| モジュール配置 | `core/ui/src/androidMain/` | KMP ポリシーに一部反するが、Android 専用 API 依存が閉じることを優先 | Low |
| 地図 SDK 前提 | `GoogleMap` + `MapView` 固定。`NavigationView` 非対応 | 旧移行計画は stale。抽象化は過剰 | Low |

### New Questions Surfaced（Round 2 以降で掘る）

- 配置計算コスト: Flexible で候補点が多数（ルート上 N 点）× 4 方向 × Callout 数 N 個。毎フレーム走るのは耐えられるか？
- 画面端・全方向 off-screen 時の fallback 挙動
- 選択中ルートの z-order / 配置優先度
- Tail 三角部分の hit-test 精度
- Material3 テーマ連動（現状 `Color(0xFF4285F4)` ハードコード）
- テスト戦略（Paparazzi / Roborazzi / ユニットテストの有無）

---

## Discoveries (Round 2)

### Assumptions Challenged

| Assumption | Finding | Impact | Decision |
|---|---|---|---|
| 毎フレーム配置再計算が必須 | カメラ pan/zoom 中は Callout を非表示にし、計算を止めてよい。自車移動による自動追従中は 3 秒に 1 回の再配置で十分 | High（パフォーマンス大幅改善） | **ジェスチャー中: 非表示 + フェード / 自動追従中: 3 秒インターバル**で再配置。フェード in/out 必須 |
| 画面端 fallback に複雑なロジックが必要 | 現実のユースケースでは許容でよい | Low | **Off-screen は Compose の clip 任せ**。Callout 自体が画面外にはみ出しても OK |

### Decisions Made

| Topic | Decision | Rationale | Risk Level |
|---|---|---|---|
| 配置再計算タイミング | ジェスチャー中は callout を非表示（フェードアウト）し、ジェスチャー停止で再計算→フェードイン。自動追従中は `CALLOUT_RELAYOUT_INTERVAL_MS = 3_000L` の間隔でのみ再配置。3 秒間はアンカー screen 座標を保持（地図との乖離を許容） | UX 的に途切れない、CPU 負荷激減。Google Maps 本体も同様の挙動に近い | Low |
| フェードアニメーション | 表示切り替え時は `AnimatedVisibility(enter = fadeIn, exit = fadeOut)` | ジェスチャー検出の判定ラグを視覚的にマスク | Low |
| ジェスチャー検出 | `OnCameraMoveStartedListener` で `REASON_GESTURE` を検出。既存 `viewportState.setGestureInProgress` 経路を再利用 | 既に実装済みフラグを活用 | Low |
| 画面端 fallback | Off-screen 允容。Compose が自動 clip | 複雑な shape 変形を避ける | Medium（画面端のアンカーで見切れるが UX 許容） |
| 定数化 | `private const val CALLOUT_RELAYOUT_INTERVAL_MS = 3_000L`（または `kotlin.time.Duration`）として callout モジュール内で定義 | マジックナンバー禁止、将来調整しやすく | Low |

### New Questions Surfaced（Round 3 以降）

- 選択ルート変更時の placement 再計算扱い（3 秒待たない？即座に再配置？）
- Tail 三角部分のクリック判定（`Modifier.clickable` は shape でクリップされるか？）
- ルートプレビューと自動追従中で `CALLOUT_RELAYOUT_INTERVAL_MS` は共通でよいか、プレビュー時は常時再配置か
- Material3 テーマ連動の粒度（背景色/文字色を caller が決めるか、Callout 側で `selected/unselected` 表現を持つか）
- テスト戦略（Paparazzi/Roborazzi の有無、unit test で Placement を守るか）

---

## Discoveries (Round 3)

### Assumptions Challenged

| Assumption | Finding | Impact | Decision |
|---|---|---|---|
| 選択ルート変更で placement 再計算が必要 | 画面座標は変わっていないので placement は不変。色/z-order だけが変わればよい | Medium | **選択変更で placement は再計算しない**。slot content 側で selected 状態を受けて色変え |
| Callout 全体（tail 含む）がクリック対象 | ボディ矩形だけで UX 上十分 | Low | **Tail 部分はタップ透過**。`clickable` はボディ矩形にのみ適用 |

### Decisions Made

| Topic | Decision | Rationale | Risk Level |
|---|---|---|---|
| 選択変更時 | `placementStrategy` と `anchors` が変わらない限り placement 結果を保持。`index == selectedRouteIndex` の判定はスロット内で行い、背景色/文字色/z-order を切り替え | 画面座標不変 → placement 不変。計算コストもゼロ | Low |
| クリック領域 | `Modifier.background(color, calloutShape).clickable { ... }` をボディ相当の Box に限定。Tail 三角は `drawBehind` or 別の child layer で描画（ripple 領域もボディのみ） | Tail は装飾、UX 上タップ対象になる必要なし | Low |
| Tail 描画方針（追加確定） | Tail は Callout の「ボディ Box の外側」に Canvas で別描画するのではなく、**Shape 全体（ボディ角丸 + Tail 三角）を 1 つの `GenericShape` にマージし、`Modifier.background(color, shape)` で描画**。ただしクリックは**内側の body 矩形 Box** に限定することで実現 | Shape と clickable の責務を分離。ripple は内側 Box の角丸矩形内に収まる（tail は独立した見た目要素） | Medium（layering が少し複雑、実装時に確認が必要） |

### New Questions Surfaced（Round 4 以降）

- selected callout の z-order（常に最前面？）
- placement compute の候補点優先順位（ルート中間点寄りを優先？先頭優先？）
- Material3 テーマ連動：背景色 default を MaterialTheme.colorScheme から取るか、caller 完全任せか
- テスト戦略（unit test / Paparazzi / 既存 test infra 有無）

---

## Discoveries (Round 4)

### Decisions Made

| Topic | Decision | Rationale | Risk Level |
|---|---|---|---|
| テーマ API | `backgroundColor = MaterialTheme.colorScheme.surface` / `contentColor = onSurface` をデフォルトに。caller が必要なら上書き可能 | Material3 準拠、ダークモード自動対応、caller 側の記述も最小 | Low |
| selected 表現 | Callout は selected フラグを持たない。スロット内で `index == selectedRouteIndex` を判定し、caller が backgroundColor / content を差し替え | 汎用部品にルートプレビュー専用ロジックを混入させない | Low |
| テスト戦略 | **テストコード追加なし**。実機確認のみ | 既存モジュールに test infra なし、本タスクのスコープ外 | Medium（回帰時に気付けないが許容） |

---

## 確定版 設計サマリ

### モジュール構成

```
core/ui/src/androidMain/kotlin/me/matsumo/onenavi/core/ui/callout/
├── Callout.kt              # 単一 Callout composable（slot ベース）
├── CalloutShape.kt         # GenericShape + Path.op(Union) で 4 方向の形状を生成
├── CalloutLayer.kt         # SubcomposeLayout で複数 Callout を配置
├── CalloutAnchor.kt        # sealed interface（Flexible / Fixed）
└── CalloutPlacement.kt     # pure function. 候補列挙 + 衝突回避
```

削除対象:
- `feature/home/src/androidMain/.../map/components/HomeMapRouteCallout.kt`
- `feature/home/src/androidMain/.../map/util/GoogleMapCalloutPositioner.kt`

修正対象:
- `feature/home/src/androidMain/.../map/HomeMapsMapEffectContent.kt`（旧 callout ロジック削除、`CalloutLayer` を呼び出し）

### 主要 API

```kotlin
enum class CalloutTailDirection { TopLeft, TopRight, BottomLeft, BottomRight }

@Immutable
sealed interface CalloutAnchor {
    val id: Any
    val primaryPoint: Offset

    @Immutable
    data class Flexible(
        override val id: Any,
        override val primaryPoint: Offset,
        val candidates: ImmutableList<Offset>,
    ) : CalloutAnchor

    @Immutable
    data class Fixed(
        override val id: Any,
        override val primaryPoint: Offset,
    ) : CalloutAnchor
}

enum class CalloutPlacementStrategy { AvoidOverlap, AnchorFirst }

@Composable
fun CalloutLayer(
    anchors: ImmutableList<CalloutAnchor>,
    placementStrategy: CalloutPlacementStrategy,
    isGestureInProgress: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, tailDirection: CalloutTailDirection) -> Unit,
)

@Composable
fun Callout(
    tailDirection: CalloutTailDirection,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
)
```

### 重要な定数

```kotlin
private const val CALLOUT_RELAYOUT_INTERVAL_MS = 3_000L
```

### 挙動まとめ

| 状況 | 挙動 |
|---|---|
| ジェスチャー中（`REASON_GESTURE`） | Callout を fade out で非表示。placement 計算停止 |
| ジェスチャー停止 | screen 座標再計算 → placement 再計算 → fade in で再表示 |
| 自動追従中（自車移動でカメラが動く） | `CALLOUT_RELAYOUT_INTERVAL_MS` 間隔でのみ placement 再計算。間は前回配置を保持 |
| 選択ルート変更 | placement は不変。スロット内で `selected` を判定して色/z-order を切り替え |
| 新しいルート結果到着 | anchors が新規になるので placement は自然に再計算 |
| 画面端で callout が収まらない | Compose の clip に任せる。Callout 自体は計算どおり配置、画面外は見切れる |
| Tail 部分のタップ | 透過（ボディ矩形のみ clickable） |

### Tail 形状

- 全方向に **max tail length 分の padding** を常時確保 → Tail 方向が変わってもサイズ一定（SubcomposeLayout の Pass 1/Pass 3 サイズ不変性保証）
- `GenericShape` + `Path.op(roundedRect, cornerTriangle, Union)` で 1 つの Path にマージ
- `Modifier.background(color, calloutShape)` で描画。Canvas 手描き不使用

### 配置アルゴリズム

`CalloutPlacement.compute(anchors, sizes, screenSize, strategy) -> List<Placement>`

- `AvoidOverlap`: `anchor.candidates × 4 directions` を全列挙、画面内 AND 既 placed と非重複の最初を採用
- `AnchorFirst`: `primaryPoint × 4 directions` のみ、画面内収容を優先に直線探索
- どちらも first-fit greedy。候補順は caller が geometry から渡す順（中間点優先等は caller 側の責任）

### カメラ追従

- `OnCameraMoveStartedListener` で `REASON_GESTURE` を検知 → `isGestureInProgress = true` → Callout fade out
- `OnCameraIdleListener` で `isGestureInProgress = false` → anchor screen 座標更新 → fade in
- 自動追従時は 3 秒タイマーで placement 再計算

---

## Dig Summary

### Investigation Overview

- Rounds completed: 4
- Questions asked: 9
- Assumptions challenged: 7
- Decisions made: 13

### Key Discoveries

1. **毎フレーム配置計算は不要**。ジェスチャー中は非表示（fade）、自動追従中は 3 秒間隔で十分。これで当初懸念の 20,000 回/秒計算が実質ゼロに。
2. **Tail 方向でサイズが変わる SubcomposeLayout 2-pass 設計の罠**を、全方向 max padding で根絶。size-invariant にすることで placement 計算が単純化。
3. **NavigationView 移行計画 (`docs/logs/3_mapbox_to_google_navigation_migration_plan_codex.md`) は stale**。`GoogleMap` + `MapView` 固定前提で設計してよい（抽象化不要）。
4. **選択ルート変更は再配置不要**。screen 座標は不変なので placement 結果を再利用、色/z-order のみ更新。

### All Decisions

| Topic | Decision | Rationale | Risk |
|---|---|---|---|
| Tail 余白 | 全方向に max tail 分の padding 固定 | Pass 1/Pass 3 サイズ不変性保証 | Low |
| モジュール配置 | `core/ui/src/androidMain/callout/` | Android-only API 依存を閉じる、commonMain の abstraction コスト回避 | Low |
| 地図 SDK 前提 | `GoogleMap` + `MapView` 固定、`NavigationView` 非対応 | 移行計画 stale | Low |
| 再計算タイミング | ジェスチャー中は非表示、自動追従中は 3 秒インターバル | CPU 負荷削減、UX 安定 | Low |
| フェード | `AnimatedVisibility(enter = fadeIn, exit = fadeOut)` | ジェスチャー検出ラグのマスク | Low |
| 定数化 | `CALLOUT_RELAYOUT_INTERVAL_MS = 3_000L` | マジックナンバー禁止 | Low |
| 画面端 fallback | Off-screen 允容、Compose clip 任せ | 複雑な shape 変形回避 | Medium |
| 選択変更扱い | placement は不変、色のみ差し替え | screen 座標不変 | Low |
| クリック領域 | ボディ矩形のみ、Tail は透過 | Tail は装飾 | Low |
| Shape 実装 | `GenericShape` + `Path.op(Union)` | Canvas 手描き不使用、AI 苦手領域を回避 | Low |
| テーマ | MaterialTheme.colorScheme を default、caller 上書き可 | Material3 自然連動、ダークモード自動対応 | Low |
| selected 表現 | Callout 自体は selected を持たない、スロット内で判定 | 汎用部品に用途固有ロジックを混入させない | Low |
| テスト | なし（実機確認のみ） | test infra 未整備、スコープ外 | Medium |

### Remaining Risks

- **回帰検出不可**: unit test / screenshot test なし。placement / shape のデグレは実機で人が気付くまで顕在化しない
- **3 秒インターバルの体感**: 高速道路 100km/h で走行時、3 秒 ≒ 83m 移動する。自動追従中の callout 位置が最大 83m ずれる瞬間がある（許容範囲と判断）
- **Flexible 候補点の順序は caller 責任**: `CalloutLayer` は渡された候補列を first-fit で採用するため、caller がルート先頭だけに偏った候補を渡すと複数 callout がルート前半に固まる可能性。caller は geometry を「中間→両端」の順で並べる等の配慮が必要
- **画面端で callout が見切れる**: Off-screen 允容方針のため、アンカーが画面端ギリギリにあると callout 本体の大半が見えない瞬間が発生しうる

### Recommended Next Steps

1. **新モジュール骨格作成**
   - `core/ui/src/androidMain/kotlin/me/matsumo/onenavi/core/ui/callout/` に 5 ファイル作成
   - `CalloutTailDirection` / `CalloutAnchor` / `CalloutPlacementStrategy` から着手（型定義のみ）

2. **`CalloutShape` 実装**
   - `GenericShape` で 4 方向分の Path 生成を先に確定
   - プレビュー Composable を作って 4 方向をビジュアル確認（実機 preview）

3. **`CalloutPlacement.compute` 実装**
   - pure function として純度を保つ（外部依存ゼロ）
   - `AvoidOverlap` / `AnchorFirst` の分岐を明示

4. **`CalloutLayer` 実装**
   - `SubcomposeLayout` による 2-pass measure-then-place
   - `isGestureInProgress` 受け取り、`AnimatedVisibility` でフェード
   - 自動追従時 3 秒タイマー（`LaunchedEffect` + `delay`）で anchor 座標を snapshot して placement 再計算

5. **`Callout` 実装**
   - Material3 default 色、slot content、ボディ矩形のみ `clickable`
   - `Modifier.background(color, calloutShape)` で tail 統合形状

6. **`HomeMapsMapEffectContent` 改修**
   - 旧 callout ロジック（`routeCalloutOffsets` / `computeRouteCalloutOffsets` / `GoogleMapCalloutPositioner`）を削除
   - `CalloutLayer` + `CallOut` を呼び出し、ルート geometry から `CalloutAnchor.Flexible` を生成
   - `viewportState.isGestureInProgress` を `CalloutLayer` に接続

7. **旧ファイル削除**
   - `HomeMapRouteCallout.kt` / `GoogleMapCalloutPositioner.kt`

8. **実機検証**
   - ルートプレビュー: 3 ルート表示で重ならないこと、選択切り替え反映
   - ジェスチャー: pan/zoom で fade out → 停止で fade in
   - 画面端: アンカーが端にあるときに fallback が許容範囲内か目視
   - Material3 ダーク/ライト切り替え
