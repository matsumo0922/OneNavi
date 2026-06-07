# Android Auto 新 SCVH relay 合成への割り込みによる split 実証(経路B)

調査日: 2026-06-07。対象: `com.google.android.projection.gearhead` 16.8.661854 / 実機 Xperia(**Android 16 / API 36**・rooted・LSPosed) + DHU。
手法: 実機 spike(Xposed hook)+ `dumpsys activity` 実測 + `apktool` full smali 読解。
関連: `docs/logs/12`(Coolwalk projection feasibility) / `docs/spec/32` / `cc_analysis/gearhead`。

---

## 背景(この調査の問い)

`docs/logs/12` は「native(parked-app)のまま Coolwalk split は不可(Google 設計)、projection 化は割に合わず **native 全画面で確定**」と結論していた。しかし **split 同居がマスト要件**として再浮上したため、projection 経路(経路B)を再検討した。

問い: **gearhead の新 SCVH relay 合成ホストに自前 UI を割り込ませて、split に参加できないか。**

結論先出し: **描画・入力ともに割り込み成立を実証**。ただし現状は「Maps の projection セッションへの相乗り」であり、Maps/レール等の相手 client も新経路に乗らないと真の同居にはならない(課題)。

---

## 1. projection 合成は2世代ある

| 世代 | 合成ホスト | ピクセル/入力 | 実機(Phenotype 未配信)の現行 |
|---|---|---|---|
| 旧 | `GhostActivity`(透明殻) | `DrawingSpec.Surface` → `VirtualDisplay` + `Presentation`(or 生 MotionEvent を ICarProjection Binder) | **これが使われている**(`dumpsys` で Maps=GhostActivity) |
| 新 | `ProjectionRootActivity`(CAR.WM.ProjRootAct) | `InputTransferToken` → `SurfaceControlViewHost` → `SurfacePackage`(OS input channel) | Strato 未配信で**未起動** |

- Maps app 側の選択(`binx.l`, codex 01): `if (Build.VERSION.SDK_INT < 36 || !bimp.j(54)) biok(旧) else bioo(新 SCVH)`。**新経路は API36 かつ flag(54) 必須**。
- 実機は Android 16(API36)で SDK 条件は満たすが、Phenotype 未配信で flag が false → 旧経路に落ちていた。

## 2. 新経路の起動チェーン(gearhead smali)

```
igv(projection マネージャ) line 1888-1940:
  acsl.a()  == true   かつ   SDK_INT >= 0x24(36)
    → new jcl(...)                         # 新 relay host を生成
jcl.i(Surface, ...) line 444-592:
  Intent(ProjectionRootActivity)
  + relay_host_callback_bundle(jcm = callback + UI state flow aipk)
  + ActivityOptions.setLaunchDisplayId(projected display)
  → startActivity                          # ProjectionRootActivity を車画面に起動
ProjectionRootActivity.onCreate:
  rootLayout = FrameLayout(背景 0xFF000000)  # instance field `c`
  relay_host_callback_bundle → jcm → UI state flow を collect
  → onUiStateChanged(RelayUiState) → createOrUpdateViews(List<WindowData>)
     各 window の SurfacePackage を SurfaceView.setChildSurfacePackage で rootLayout に合成
jcj(SurfaceView attach listener):
  host token(InputTransferToken)を取得 → igp.d() で client へ dispatch
  ※ acsk.c() == false(Strato 無効)なら "Strato feature is disabled" で例外
```

### Strato flag の実体

- `acsk.c()` = `acsk.a.b().a()` = `acsm.a()`。
- `acsm.a()` = Phenotype flag **`StratoFeature__enabled`(default `false`)**(`acsm` static init で `rjh.f("StratoFeature__enabled", false)`)。
- **重要**: `igv` の分岐(line 1900)は `acsk.c()` を経由せず **`acsl.a()`(=`acsm.a()`)を直接**呼ぶ。よって hook 対象は実装本体 `acsm.a()`。`acsk.c()` を hook しても igv には効かない(spike で確認済)。

## 3. 実験(`xposed-carunlock/ProjectionSplitProbe`)

1. **Strato 解錠**: `acsm.a()` を `true` 固定(`StratoFeature__enabled` 強制有効)。
2. **rootLayout 割り込み**: `ProjectionRootActivity.onCreate` を after hook → instance field から型一致で `FrameLayout`(難読化 `c`)を取得 → 赤箱(500px, center)を `addView` + `OnTouchListener`。

## 4. 結果(2026-06-07 / DHU)

```
installed: hooked acsm.a() -> true (StratoFeature__enabled)
installed: hooked ...ProjectionRootActivity.onCreate
ProjRootAct.onCreate fired: displayId=413 name=RelayComposer   ← 新経路が起動した
probe box attached: 500px center, rootChildren=1
PROBE TOUCH: action=0 x=254.6 y=270.0                          ← 本物 MotionEvent 到達
```

- ✅ `acsm.a()=true` で `igv` が新経路を選び、**ProjectionRootActivity(display 413 "RelayComposer")が起動**。
- ✅ rootLayout に赤箱が描画され **HU に表示**(画面中央)。背景の黒は rootLayout の `setBackgroundColor(0xFF000000)`。
- ✅ 赤箱タップで **`PROBE TOUCH action=0`** = 本物タッチ到達(`action=0` のみなのは listener が `return false` で DOWN 後の後続を受けない仕様)。

→ **native では原理的に不可能だった「合成フレームへの割り込み描画+本物入力」が projection 経路で成立。経路B の P1/P2 実証。**

## 5. 課題(split 同居はまだ)

- `rootChildren=1`(赤箱のみ)。**host(gearhead)だけ Strato 強制 ON にしたが、Maps/レール(launcher)等の各 projected client が新 SCVH 経路に追従できていない** → SurfacePackage を返さず、rootLayout に相手の SurfaceView が来ない。
- Maps は新経路で描画を試みて破綻 → **「マップの読み込み中にエラーが発生しました」**(client `bioo` 不整合)。
- ∴ 現状は「Maps の projection セッションへの**相乗り**」で、Maps が死ぬと土台ごと不安定。真の split 同居には**相手 app も新経路で SurfacePackage を返す**必要がある。

## 6. 次の一手

- **Maps 同居実験**: Maps app(`com.google.android.apps.maps`)にも LSPosed スコープを広げ、Maps 内の Strato flag(`binx` の `bimp.j(54)`)を true 化 → Maps が `bioo`(SCVH)で SurfacePackage を返し、rootLayout に **Maps SurfaceView + 赤箱が並ぶ**か。並べば「新経路で2アプリ同居 = split 成立」が確定する。
- その後: 赤箱を OneNavi プロセスの `SurfaceControlViewHost` 由来 `SurfacePackage` に差し替え(P3)、bounds を split スロットに合わせて `OneNaviApp()` を同居。

## 7. バージョン追従アンカー

- Strato flag: 文字列 `"StratoFeature__enabled"` / 実装 `acsm.a()` / `acsk.c()`。
- 合成ホスト: `"CAR.WM.ProjRootAct"` / FQCN `com.google.android.apps.auto.carservice.car.window.composition.ProjectionRootActivity` / display name `"RelayComposer"`。
- 起動: `igv`(分岐 line 1888-1940) / `jcl.i`(line 444-592) / extra `"relay_host_callback_bundle"` / `"relay_host_callback"`。
- 例外文字列: `"Host token dispatched but Strato feature is disabled"`。
- ※ 難読化名は gearhead 16.8.661854 前提(`docs/spec/32` D11 で版固定)。
