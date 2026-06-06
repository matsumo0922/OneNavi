# Android Auto「Coolwalk 共存（split + レール）」を native app で得られるか、projection 化は可能か

調査日: 2026-06-06。対象: `com.google.android.projection.gearhead` 16.8.661854 / 実機 Xperia XQ-FS44（Android 15・rooted・Vector）+ DHU。
手法: 実機 spike（`am start --display` / projection service スタブ + Xposed hook）+ `dumpsys display|window|activity` の実測 + `apktool` full smali 読解。
関連: `docs/logs/8`（生 Surface）/ `docs/logs/9`（bypass）/ `docs/logs/10`（projection gating）/ `docs/logs/11`（parked-app 走行ゲート）/ `docs/spec/32`。

---

## 背景（この調査の問い）

STEP1a で **parked-app（native_app）経路は実証済み** ── OneNavi の `CarActivity` を車 display に
フルスクリーンで出し、本物の MotionEvent を取れた（`docs/spec/32`）。しかし実車/DHU で見ると:

- OneNavi 起動の瞬間、**Android Auto のレール（右の status/ランチャ/戻る）と左の media ペインが消える**。
- つまり **OneNavi が画面全体を占有**し、AA の「Coolwalk フレキシブルレイアウト」（OneNavi + YouTube Music の
  split 等）が成立しない。

問い: **native のまま Coolwalk split に参加できないか。無理なら projection 化（Maps と同じ枠）は可能か。**

結論先出し:
- **native のまま Coolwalk は原理的に不可。** これは hack の限界ではなく **Google の設計**（native/parked app は
  専用 display にフル占有が仕様。Coolwalk split/レールは projected/template アプリ専用）。
- **projection 化は「黒寄りのグレー」。** 合成は公開 API（SCVH）だが、**first-party 専用の多層ゲート**で守られ、
  さらに**閉じた gms.car 描画プロトコルの再実装**（数週間・版固定）が必要。個人利用では割に合わない。
- **採用方針: native フルスクリーンで確定。** 他アプリへは HU のホーム/アプリ切替で遷移する。

---

## 1. 「projected」と「native」はピクセル/入力の運び方が根本的に違う（実測で確定）

DHU で Coolwalk split 表示中（左=media リスト / 右=Maps / 端=レール）の `dumpsys display` mViewports:

```
displayId=58  GhostActivityDisplay{maps/GmmCarProjectionService}  1200x720 160dpi  active=true   ← projected アプリ枠
displayId=65  GhostActivityDisplay{.../ProjectionTrampolineFallbackService} 780x720           ← split 領域
displayId=68  GhostActivityDisplay{.../GhAppLauncherService}      780x720
displayId=88  NativeDisplay{me.matsumo.onenavi.debug/...CarActivity} 1280x720 209dpi          ← OneNavi(フル)
（他に MediaService / TelecomService 等の 780x720 GhostActivityDisplay、1280x80/60 のバー帯）
```

- Coolwalk は **「projection service ごとに作られた GhostActivityDisplay」を複数合成**して 1 枚の HU フレームにしてる。
- native の OneNavi だけ **専用 NativeDisplay をフル（1280x720）で貰い、単独配信**される。
- OneNavi の NativeDisplay は `dumpsys window displays` で **statusBars/navigationBars の InsetsSource を一切持たない**
  （スマホ display 0 は両方持つ）。= gearhead はレールを native display に**合成しない**。

### 決定的実験: native Activity を projected 枠（display 58）に押し込む

```
adb shell am force-stop me.matsumo.onenavi.debug
adb shell am start -n me.matsumo.onenavi.debug/me.matsumo.onenavi.car.CarActivity --display 58
```

`dumpsys activity` 上は **CarActivity が display 58 を完全占有**（`topResumedActivity` / `mCurrentFocus` /
`mObscuringWindow` / `mTopFullscreenOpaqueWindowState` = CarActivity）。**にもかかわらず HU は Maps を表示し、
かつ Maps が操作可能だった。**

→ **projected 枠は WindowManager の framebuffer を配信していない。** ピクセルも入力も
**projection IPC（gms.car）経由で登録済み projected アプリに直送**されており、WM の focus と完全に切り離されている。

| slot | ピクセル | タッチ入力 |
|---|---|---|
| projected（58/65/68 = GhostActivityDisplay） | projection Surface（gms.car IPC） | projection 入力 → 登録アプリ |
| native（88 = NativeDisplay） | display framebuffer を生配信 | display へ MotionEvent 注入（本物） |

**「native の framebuffer を chrome 付き領域に合成する」モードは存在しない。** よって native のまま Coolwalk は不可。

---

## 2. projection 合成の中身は公開 API（SCVH relay）。broker は gearhead 自身

- 合成ホスト: `com/google/android/apps/auto/carservice/car/window/composition/ProjectionRootActivity`
  （`CAR.WM.ProjRootAct`、非難読化）。Intent extra `relay_host_callback_bundle` で IBinder（`jcm`/`jcn`）と
  UI state flow を受け、`onUiStateChanged(RelayUiState)` → `createOrUpdateViews(List<WindowData>)` で
  各 window の **`SurfaceControlViewHost$SurfacePackage` を SurfaceView に attach**、`getHostTokenForView()` で
  **`InputTransferToken` を client に返す**（= 入力転送）。**全部 Android 公開 API。閉じた DrawingSpec ではない。**
- `WindowData`（`jcq`）= `{id:int, bounds:Rect, zOrder:int, alpha:float, surfacePackage}`。
- SCVH client（`SurfaceControlViewHost` を作って描き SurfacePackage を返す）= `rkc`（`CAR.PROJECTION.SCVH`）。
  SurfacePackage は AIDL（`rka`/`rvc`/`qug`/`qte.y(Bundle)`）越しに gearhead へ渡る。
- projection broker（PROJECTION_LIFETIME_SERVICE）の package は `qoy.b()` が返す
  **`com.google.android.projection.gearhead`（旧 bumblebee）** ＝ **broker は gearhead 自身。GMS ではない**
  （`rkz.a` の `sgf.c(getPackageName())` は gearhead の自己署名検証）。
- GhostActivityDisplay は gearhead（`lvi`/`ihd`/`iis`/`oss`）が **アプリの `CarProjectionService`（例:
  `maps/GmmCarProjectionService`）に bind**して作る。

→ 「broker が別プロセス(GMS)で詰み」ではない。broker は乗っ取り済の gearhead。**壁はゲートと描画プロトコル。**

---

## 3. projection アプリの eligibility ゲート（`irr.y`）

`irr`（アプリ種別判定。`docs/logs/10` の Gate B）の `y(String pkg, qkj, Z p3, …):Z` が validator。
`p3=true` で `"CarProjectionValidator#isPackageAllowed3p"`（**第三者 projection を許可する経路は存在する**）。

許可（`return v17`）に至る OR 条件の一つ（`irr.smali` 8144–8172）:

```smali
invoke-virtual/range {v1 .. v6}, Lirr;->x(...)Z          # 8144 メタデータ/型の検証
...
check-cast v0, Lsgf;
invoke-virtual {v0, v4}, Lsgf;->c(String)Z               # 8166 first-party 署名判定
if-eqz v0, :cond_1b
return v17                                                # true なら許可で即 return
```

つまり **`sgf.c(pkg)`（GoogleSignatureVerifier、`sgf.smali:1550`）が true なら projection eligibility も通る**はず、
というのが smali からの仮説だった。native が通ったのは別ブランチ（`qkj.j`=NATIVE + `kgj.e(app_package_list)`
opt-in、これは hook 済）。

---

## 4. 実機 spike と結果（白黒）

OneNavi に以下を追加（spike、後に revert）:
- `automotive_app_desc.xml`: `<uses name="projection" />`
- `OneNaviCarProjectionService`（スタブ）+ intent-filter（MAIN/LAUNCHER/APP_MAPS/DEFAULT/CATEGORY_PROJECTION
  /CATEGORY_PROJECTION_NAVIGATION）+ meta-data `GHOST_ACTIVITY = me.matsumo.onenavi.car.CarActivity`
- native の `CAR_LAUNCHER` intent-filter を一旦封印（projection 経路だけで起動できる状態に）
- Xposed: `sgf.c(String)` を OneNavi に対して true 固定する hook を追加

| 段階 | 結果 |
|---|---|
| PackageManager discovery（`cmd package query-services -c CATEGORY_PROJECTION`） | **○** OneNavi の service が Maps と並んで解決 |
| gearhead launcher の projection アプリ列挙（sgf hook **なし**） | **✗** 一覧に出ない |
| gearhead launcher（sgf hook **あり**、`hooked sgf.c -> true` をログで確認） | **✗** やはり出ない |
| 決め手 | **`SignatureForceFirstParty` 発火回数 = 0** ＝ **gearhead は OneNavi に対し `sgf.c` を一度も呼んでいない** |

→ **sgf.c に到達する手前で、より早い別ゲートが OneNavi を弾いている。** 候補は `irr.x()`
（非 first-party が `<uses name="projection">` 型を名乗ることの検証）か、launcher 独自の projection allowlist。
**さらに別の hook が要る**（whack-a-mole）。

参考: native 経路はこの spike 中も `am start --display 58` 時に `NativeDisplay{...CarActivity}` が生成され、
GHOST_ACTIVITY を CarActivity に向けてもタップ時は従来どおり **native フル**で起動した（projection ではなく）。

---

## 5. 結論と方針

projection 化は:
1. **first-party 専用の多層ゲート**（`ltz`→`kgj.e`→`setAllowedActivities`→`sgf.c`→さらに `irr.x`/launcher allowlist→…）を
   1つずつ発見して hook する必要があり、**まだ最低あと 1〜2 個未特定**。
2. それを全部抜けても、最後に **閉じた gms.car projection client protocol（`CarProjectionService` +
   DrawingSpec/Surface 受け渡し、`rkc` 相当の SCVH client）を再実装**する必要がある。数週間・obfuscated・
   gearhead 版上げで壊れる。
3. `OneNaviApp()`（Compose/ViewModel/Koin）自体は流用可能だが、**旧経路では VirtualDisplay+Presentation の
   ラグが再来**し、新 SCVH 経路は Strato flag（実機は Phenotype 未配信で恐らく無効）が前提。

得られるのは Coolwalk split のみ。**対価に全く見合わないため projection 化は不採用。**

**採用: native フルスクリーン（STEP1a）で確定。** ナビはフルスクリーン地図が本来の姿であり、
任意 Compose + 滑らか + 本物タッチという native 経路だけの武器を保持する。他アプリへは HU 側の
ホーム/アプリ切替で遷移する。

### 未解決（やるなら次の一手）
- `irr.x()` または launcher enumeration の projection allowlist の正確な位置特定（sgf.c より手前のゲート）。
- ただし上記 5-2（閉じた描画プロトコル）の壁は残るため、ゲート特定だけ進めても実用には至らない。
