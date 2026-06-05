# 8. Android Auto 地図サーフェス描画方式の徹底調査

## 目的

Android Auto 上で地図 + Compose 製コントロールを描画する現行構成
（host から受け取った生 `Surface` → `VirtualDisplay` → `Presentation` → `MapView` + `ComposeView`）
について、以下を曖昧な推測ではなく実物確認で切り分ける。

- `VirtualDisplay + Presentation` 以外に、view/Compose を生 Surface へ載せる「抜け道」が存在するのか。
- 現行構成で観測される「リッチさが出ない」「タップ反応に約 1 秒のラグ」の原因は描画方式そのものか、別要因か。
- Google のナビアプリが滑らかな理由は何で、それは公開側で再現可能か。

個人利用前提のため、hidden / reflection / internal API を使う前提でも調査範囲に含めた。

## 調査方針

- ローカルの実物を確認する：`androidx.car.app` AAR・sources、Compose / KMP Compose の sources、`android-36/android.jar`、DHU の config。
- web で各代替手法（SurfaceControlViewHost / HardwareRenderer / ViewRootImpl / ComposeScene / lockHardwareCanvas）と他社ナビ実装、VirtualDisplay の frame pacing を調査する。
- 確認できた事実、棄却した仮説、未確定事項を分けて記録する。

---

## 結論サマリ

1. **生 Surface へ view/Compose を載せる現実的な抜け道は無い。** 主要な代替手法はすべて dead-end か劣化。
2. **`VirtualDisplay + Presentation` は逃げ道ではなく「最良の公開手法」。** 第三者ベンチで他手法を圧倒し、Google 公式のナビ導線（Navigation SDK の `NavigationViewForAuto`）も内部で同一構成を採用している。
3. **観測されたラグ/カクつきは描画方式の問題ではなく frame pacing の問題。** 主因は (a) DHU の 30fps 固定上限と (b) producer(60) / consumer(30) の差による buffer stuffing。アーキ変更なしの調整で改善余地がある。
4. **Google が滑らかなのは技術ではなく first-party 特権。** 自前 C++ エンジンが生 Surface へ直接 GL 描画しており、Maps **SDK** を使う公開側では構造的に同等にはできない。

---

## 確認済みの事実

### 代替描画手法の評価（実物 + web）

| 手法 | API | 判定 | 根拠 |
|---|---|---|---|
| **VirtualDisplay + Presentation**（現行） | 21+ | ✅ 最良 | MapLibre 公式ベンチで **34.66fps / acceptable frames 87.8%**。実装 ~71 行・公開 API のみ |
| HardwareRenderer + RenderNode | 29+ | △ 部分的 | 公開 API で生 Surface へ直接描ける。が、Compose の lifecycle/layout 駆動に結局ヘッドレス VirtualDisplay が要る。`view.draw(renderNode)` 取り込み + 手動 Choreographer pump が必要で二重管理 |
| lockHardwareCanvas + View.draw | 23+ | △ 劣化 | 毎フレーム GPU→CPU readback。MapLibre ベンチで **8.26fps / 62.9%**。さらに一度 `lockCanvas` すると当該 Surface は GLES/Vulkan と併用不可になる |
| SurfaceControlViewHost | 30+ | ❌ dead-end | 出力は `SurfacePackage` で、消費側に `SurfaceView.setChildSurfacePackage()` が必須。host がくれるのは生 `Surface` のため reparent 先が無い。reflection で `SurfaceControl` を取り出す経路は未検証かつ脆い |
| 手動 ViewRootImpl / WindowManagerGlobal | hidden | ❌ dead-end | `ViewRootImpl.setView()` は WMS への IPC + window token 必須。生 Surface を注入する穴は無く、アーキ上 WindowManager と密結合。Android 9+ の hidden API 規制も追加で阻む |
| ComposeScene / Skiko 直描画 | — | ❌ dead-end | `ComposeScene` は `skikoMain` 限定で **Android 版 Compose には存在しない**（cache 実物で確認、Android artifact に `scene` クラス 0 件）。Android は HardwareRenderer/RenderNode 経路で、Skiko-android artifact も未配置 |

直接 GL を生 Surface に描く方式（MapLibre の `SurfaceMapRenderer`）はベンチで 10.93fps / 84.7% だが、2,238 行の自前 renderer が必要で地図ライブラリ内部に密結合する。

### `SurfaceContainer` が露出する情報（実物確認: `androidx.car.app:app:1.7.0`）

- `getSurface(): android.view.Surface` / `getWidth()` / `getHeight()` / `getDpi()` のみ。
- **`SurfaceControl` も hidden handle も無い。** host（ヘッドユニット）が SurfaceFlinger の BufferQueue を生成し、`SurfaceContainer` を `Bundleable` で Binder シリアライズして app 側へ渡すだけ。
- 受け取った各 `Surface` は使用後に `Surface.release()` する契約（javadoc 明記）。
- 描画方法は任意（`Canvas` via `lockHardwareCanvas` / `HardwareRenderer.setSurface()` / EGL）。Surface 配送に privileged な仕掛けは無い。
- manifest に `androidx.car.app.ACCESS_SURFACE` が必要。`NavigationTemplate`（および MapController を使う Map 系テンプレート）でのみ surface がゲートされる。

### 入力経路（実物確認: `SurfaceCallback` / `ISurfaceCallback`）

- 通常モードでは host が全タッチを横取りする。`onScroll` / `onFling` / `onScale`（CarApi 2+）と `onClick`（CarApi 5+）は **`Action.PAN` を持ち pan モードに入っているときだけ** Binder 経由で配送される。
- つまりコントロールのタップは「host が semantic callback を Binder で送る → app が合成 `MotionEvent` を Compose へ転送」という 2 段経路を通る。入力自体に IPC レイテンシが内在する。

### Google が滑らかな理由 / Maps SDK の制約

- Google Maps（first-party）は自前のクロスプラットフォーム C++ レンダリングエンジンが生 Surface へ直接 GL 描画。template でも Compose でも View でもない。リッチさの源泉は framework ではなくエンジンの作り込み。
- Maps **SDK**（`com.google.android.gms.maps.MapView`）は内部で `Activity` context を要求する GMS binding を行う。`CarContext` は `Activity` ではないため、**生 Surface へ直接は描けず Presentation（実体は Dialog backed の real window）を仲介する必要がある**。これが現行構成の必然性。
- Google 公式の AA 地図導線は **Navigation SDK の `NavigationViewForAuto`**（承認制 + Maps Platform ライセンス）で、その実装は `createVirtualDisplay → Presentation(carContext, vd.display) → setContentView(NavigationViewForAuto)` という **現行と同一構成**。
- `MapView` を任意の `SurfaceTexture` / `TextureView` へリダイレクトする公開 API は無い。`liteMode` は静的 bitmap で非対話。`GoogleMap.snapshot()` は main thread での GPU readback（20–100ms）で毎フレーム用途は不可。
- このため第三者ナビアプリの多くは生 Surface へ GL を直接出せる **MapLibre / Mapbox** へ移行している。

### DHU の実測仕様（実物確認: `~/Library/Android/sdk/extras/google/auto/`、DHU 2.0）

- すべての config（`default.ini` / `*_720p.ini` / `*_1080p.ini` 等）で **`framerate = 30` 固定**。
- DHU は app に直接 Surface を渡さない。phone 側 AA host が Surface を生成し、合成出力を USB/WiFi 経由で DHU へストリームする sink。**60fps で描いても DHU 側は 30fps でしか消費しない。**
- したがって DHU 上の体感ラグには DHU 自身のストリーム遅延 + 30fps 天井が混入する。実機ヘッドユニットでは挙動が変わり得る。

### VirtualDisplay の frame pacing（web）

- 旧 6 引数 `createVirtualDisplay` は refresh rate を表現できない。`VirtualDisplayConfig.Builder.setRequestedRefreshRate(float)`（API 34+）で指定可。0 なら物理ディスプレイ追従、任意値は物理 rate の約数へ切り上げ。
- 推奨 flag は `PRESENTATION | OWN_CONTENT_ONLY`（**現行コードは既にこの組み合わせ**）。`AUTO_MIRROR` は `OWN_CONTENT_ONLY` と排他。
- AOSP の既知制約 b/116025192：app 側 Choreographer の vsync は内部（primary）ディスプレイ追従で、`physicalDisplayId` は実質無視。VirtualDisplay 宣言 refresh rate では駆動されない。View 階層なら手動 vsync pump は不要（system 駆動で足りる）。
- producer が consumer より速いと **buffer stuffing**：見た目は滑らかだがキュー滞留分だけ入力反応が遅延する。これが「滑らかなのにラグい」の典型症状。

---

## ラグ/カクつきの切り分け結論

観測症状は描画方式起因ではなく、独立した 2 要因に分解できる。

- **(a) FPS 上限 = DHU の 30fps 固定。** 60 で描いても無駄。体感カクつきの一部は DHU の仕様。
- **(b) タップ→反応 約 1 秒 = buffer stuffing。** 60fps producer を 30fps consumer に流すとキューが滞留し、`onClick` の Binder IPC + 合成 MotionEvent 経路の遅延が上乗せされる。

いずれもアーキ変更なしの frame pacing 調整で改善余地がある（後述）。

## 棄却した仮説

- 「`VirtualDisplay + Presentation` は妥協の産物で、より良い描画方式に乗り換えるべき」→ 棄却。第三者ベンチ・Google 公式実装とも同一構成で、これが最良の公開手法。
- 「Compose を生 Surface へ直接載せる隠し API / reflection 経路がある」→ 棄却。SurfaceControlViewHost・ViewRootImpl・ComposeScene/Skiko はいずれも本ケースで不成立。
- 「ラグの主因は描画方式（Presentation 経由のオーバーヘッド）」→ 棄却。主因は DHU 30fps 上限 + buffer stuffing。
- 「Maps SDK を生 Surface へ直接描けば滑らかになる」→ 棄却。Maps SDK は `Activity` context 前提で生 Surface へ直接描画できない。Google の滑らかさは自前エンジン + first-party 特権由来で再現不可。

## 未確定事項 / 次アクション候補

frame pacing 調整（アーキ変更不要）を実装し DHU で再検証する。逆説的だが producer を consumer(30) に合わせると stuffing が減りラグが縮む想定。

1. `Surface.setFrameRate(30f, FRAME_RATE_COMPATIBILITY_DEFAULT)`（API 30+、要 version gate）で生成側を 30 に明示。
2. `VirtualDisplayConfig.Builder.setRequestedRefreshRate(30f)`（API 34+、要 version gate）で VD 自体も 30 に。
3. `map.isTrafficEnabled` / `isBuildingsEnabled` を一時的に切り、描画負荷と体感ラグの相関を切り分け。
4. 改善が頭打ちなら **HardwareRenderer 経路**を実験的に試す（上限ありだが計測価値あり）。
5. DHU 由来の遅延と切り分けるため、可能なら**実機ヘッドユニット**で体感を確認する。

## 参考

- 実物: `androidx.car.app:app:1.7.0`（`SurfaceContainer` / `SurfaceCallback` / `AppManager` / `ISurfaceCallback`）、`android-36/android.jar`（`HardwareRenderer` / `SurfaceControlViewHost` / `SurfaceControl` 等の存在確認）、Compose `ui-android` sources（`AbstractComposeView` / `WindowRecomposerPolicy` / `setParentCompositionContext`）、DHU 2.0 config。
- web: MapLibre on Android Auto ベンチ（helw.net, 2025-11）、Android for Cars draw-maps、Navigation SDK Android Auto、AOSP Choreographer / b/116025192、VirtualDisplayConfig（AOSP）。
- 関連: 本リポジトリ `docs/spec/14_android_auto_google_maps_investigation.md`。
