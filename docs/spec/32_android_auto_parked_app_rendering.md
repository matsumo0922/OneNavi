# 32. Android Auto parked-app 経路による車載リッチ描画

## ⚠️ 位置づけと前提

- 本 spec は **作者個人端末での自己責任の改造**を記録する。
- アプリ側(NATIVE_APP 宣言 + 専用 CarActivity)は**本体に統合する**(D22。hook が無ければ無害)。
- 走行ゲートを解除する **gearhead 改変(LSPosed モジュール)は本 repo に公開で置く(D23)が、有効化は個人の自己責任に限る**。
- 走行制限(運転者の注意散漫を防ぐ安全ガード)を意図的に無効化する内容を含む。**他者への配布・推奨はしない。**
- root + LSPosed 前提。リバースエンジニアリングは相互運用目的の個人利用に限る。
- 外部ナビ API 提供事業者とは無関係(本件は Android Auto 本体 = gearhead の話)。

関連: `docs/spec/14_android_auto_google_maps_investigation.md` / `docs/logs/8,9,10,11` / `../../APK/GoogleMap/cc_analysis/REPORT_*`(git 管理外の解析成果物)。

---

## 0. 意思決定ログ

| # | 決定 | 内容 | 日付 |
|---|---|---|---|
| D1 | AA 立ち位置 | **純 NATIVE_APP**。parked-app 一本。AA のナビ統合(クラスタ/ナビ通知/Assistant)は持たない | 2026-06-06 |
| D2 | 速度 hook 範囲 | **常時グローバル**。`ldw.a()`/`lek.a()`→0f を無条件。Maps 含む AA 全体が常時「停車中」扱いになることを許容 | 2026-06-06 |
| D3 | 既存経路 | **即時削除**。`OneNaviCarMapRenderer`(VirtualDisplay+Presentation)と androidx.car.app 経路を撤去し parked-app に一本化。D4 リスクは許容(賭ける) | 2026-06-06 |
| D4 | 実車 HU 対応 | 実態は「HU 非対応」ではなく **Phenotype `compatible_car_list` 未収載**(phone-side allowlist)。root で強制有効化を狙う。DFM 入手が前提条件(D20)| 2026-06-06 |
| D5 | hook 方式 | **LSPosed + 文字列アンカー自動解決**。`"Abort phone activity launch..."` / `CAR_MOVING` / `NATIVE_APP` 等の anchor から難読化名を動的解決し AA 更新に耐性を持たせる | 2026-06-06 |
| D6 | 端末 / root | **Pixel 10 / Magisk + LSPosed**(解析機と同一) | 2026-06-06 |
| D7 | スマホ画面 | car 表示中はスマホ側を**簡易表示/ロック**(運転中はスマホ操作させない)。具体形は D12/D28(メッセージのみのロック画面) | 2026-06-06 |
| D8 | CarActivity 構成 | **専用 CarActivity 新規**。車用の独立 Activity で `OneNaviApp()` をホスト。スマホ MainActivity と疎結合・多 display 同時表示 | 2026-06-06 |
| D9 | eligibility 手段 | **`allow_unknown_sources` 設定を優先**。実測で足りなければ同 LSPosed モジュールで `irr.y()` を OneNavi 限定 true。`H()` は使わない | 2026-06-06 |
| D10 | kill-switch | **LSPosed モジュール ON/OFF のみ**(追加実装なし)。「使う時だけ有効化」運用 | 2026-06-06 |
| D11 | gearhead 更新 | **Play 自動更新 OFF で固定運用**。検証済みバージョンに固定し、anchor 解決と二重で安全化。手動更新時に再検証 | 2026-06-06 |
| D12 | スマホロック実装 | **AA 接続検知で専用ドライブロック画面**をスマホに表示(誤操作防止) | 2026-06-06 |
| D13 | 車 UI レイアウト | **スマホ UI をそのまま**車 display に出す。車画面比での視認性調整は残課題(§6) | 2026-06-06 |
| D14 | 音声ルーティング | **車スピーカー経由**(AA 音声フォーカス/Bluetooth) | 2026-06-06 |
| D15 | 車起動方法 | **AA ランチャから手動タップ**(parked-app 標準挙動) | 2026-06-06 |
| D16 | AA 接続検知 | **専用 CarActivity の起動を合図**にする。同一プロセスのフラグで MainActivity に伝えロック表示。androidx.car.app は完全撤去のまま | 2026-06-06 |
| D17 | AA 接続形態 | **有線 USB**(安定・低遅延)。開発・実車とも USB 中心 | 2026-06-06 |
| D18 | 失敗時挙動 | hook 未有効/起動失敗時は**スマホに状態・原因を表示**(可視化してデバッグ容易に) | 2026-06-06 |
| D19 | 走行中 UI 制限 | **制限しない(全操作可)**。自己責任の個人利用前提 | 2026-06-06 |
| D20 | 検証順序 | 当初「DFM 入手可否」を最優先としたが追調査で **DFM 鶏卵問題は解消(コードは base に在りフラグゲートのみ)**。改め最優先は **実機で `ltz.b()`→true / Phenotype 上書きにより parked-app が起動するか**の実証。次に STEP1 アプリ実装 | 2026-06-06 |
| D21 | D3 再確認 | 🔴 発見後も **D3 即時削除を維持**(ユーザ再確認)。revert は git 履歴で担保 | 2026-06-06 |
| D22 | コード配置 | NATIVE_APP 宣言 + 専用 CarActivity を**本体(メインアプリ)に入れる**(専用バリアントに隔離しない)。hook 無しでは無害 | 2026-06-06 |
| D23 | LSPosed モジュール | **OneNavi repo 内に公開で置く**。走行ゲート解除を含むため⚠️警告を明記して運用 | 2026-06-06 |
| D24 | spec 置き場 | **公開 `docs/spec/32` のまま**(冒頭警告 + 個人利用明記でカバー) | 2026-06-06 |
| D25 | 昼夜テーマ | 車画面は **AA の day/night シグナルに追従** | 2026-06-06 |
| D26 | 接続時セッション | AA 接続時、**同一ナビセッションを車に引き継ぐ**(単一プロセス・状態共有。MainActivity と CarActivity が同じ nav 状態を参照) | 2026-06-06 |
| D27 | 切断時セッション | AA 切断時は**ドライブロック解除しスマホでナビ継続**(セッションは止めない) | 2026-06-06 |
| D28 | ドライブロック内容 | **メッセージのみ**(「車画面で操作中」)。操作ボタンは置かない | 2026-06-06 |
| D29 | 音声フォーカス | TTS 案内時は**ダッキング**(他音声を一時的に小音量) | 2026-06-06 |

### ⚠️ D3 × D4 のリスク(許容済)

D3(即時削除)+ D4(HU 未確認)により、**実車 HU が parked-app 非対応だった場合 OneNavi は AA で一切表示できなくなる**。
ユーザ判断で**このリスクを取る(parked-app に賭ける)**。ただし保険として:
- 削除は **git 履歴を残す形**で行い、非対応判明時に revert 可能にする。
- **STEP0(`ltz.b()`→true で parked-app が出るか)を最優先**で行い、早期に「parked-app 経路が成立するか」を確認する(D20)。

### D2 の安全注記(常時グローバル)

速度0偽装が有効な間、**Maps を含む全 AA アプリの走行制限が無効化される**(キーボード入力・動画等)。
このため LSPosed モジュールの有効/無効が事実上の kill-switch(D10)。運用時は「使う時だけ有効化」を徹底する。

### 🔴 重大な発見(2026-06-06)— parked-app 可用性は HU ではなく Phenotype フラグで決まる

実車(投影型 HU、AAOS ではない)で parked-app が出ない件を解析した結果:

- parked-app の可用性判定は **`GH.ParkedNativeAppCheck`(`ltz.b()`)** が行う。判定材料:
  1. `hasNativeAppsModule`(`cradle_features` car module の存在 ＝ スマホ側 DFM)
  2. `Build.VERSION.SDK_INT >= 最低版`
  3. 車接続 + `CarInfo != null`
  4. **`kdh.i(CarInfo, List)`** ＝ CarInfo を **`CradleFeature__compatible_car_list`** と照合(allowlist/filter mode)
  5. **`nlc.e()` = `rji.d()`** ＝ HU がタッチ対応か(`"Has touch: %b"`)
- 関連 Phenotype フラグ(`CradleFeature__*`、サーバ配信): `compatible_car_list` / `app_package_list` / `app_launcher_package_list` / `all_app_launcher_enabled` / `allow_video_apps` / `allowed_activities_list` / `extended_toolbar_enabled_cars`。

**結論: 実車で出ないのは HU の技術的非対応ではなく、Google が `compatible_car_list` で対応車種を絞っているから**(描画はスマホ側 gearhead で完結し、HU は映像+タッチ端末)。
→ これは **phone-side・フラグ駆動**であり、root(D6)で **Phenotype 上書き or `ltz`/`kdh.i` hook** により**強制有効化できる見込み**。

#### D3 / D4 の見直し(結論)

- D4 を更新: 「実車 HU は parked-app 非対応」ではなく「**`compatible_car_list` に未収載(phone-side allowlist)**」が実態。**強制有効化の余地あり。**
- D3(即時削除)は発見後も**ユーザ再確認により維持(D21)**。強制有効化には (a) `cradle_features` DFM の存在、(b) Phenotype 上書きの実効性、(c) 実機での投影成功 の3点が未実証だが、**削除は git 履歴を残す形で行い revert 可能とする**ことでリスクを担保する。

#### 追加 hook / 上書きターゲット

```
Phenotype 上書き(AA-Phenotype-Patcher / GMS-Phixit / フラグ read hook):
  CradleFeature__compatible_car_list       → 自車 CarInfo を追加
  CradleFeature__app_package_list          → me.matsumo.onenavi を追加
  CradleFeature__app_launcher_package_list → 同上
or LSPosed hook:
  ltz.b()/ltz.a()  → true(ParkedNativeAppCheck を素通し)
  kdh.i(CarInfo,List) → true(車フィルタ素通し)
  nlc.e()          → true(タッチ HU でなければ。OneNavi はタッチ必須なので通常は不要)
```

#### DFM 鶏卵問題の解消(2026-06-06 追調査)

当初「`cradle_features` DFM が対応車でしか落ちてこない鶏卵問題」を最大リスクとしたが、追調査で**ほぼ解消**:

- **`iij`(NativeAppCarActivityManager)は base gearhead APK にフル実装済**(2794 行 / 24 メソッド)。parked-app 中核コードは既に端末にある。
- `hasNativeAppsModule` の実体は `ltz` → `kcj.a(waq("cradle_features"))` → `kbf.d(...)` = **gearhead のフィーチャーフラグ評価(Phenotype バックエンド)**。**別途 DL が必要な split APK ではない。**
- ∴ **「DFM が入らないと詰む」鶏卵問題は存在しない**。ゲートはフラグのみ。

→ 残るゲートは全て phone-side でフラグ/hook で開けられる:
- `ltz.b()`→true(ParkedNativeAppCheck 素通し)、または `CradleFeature__*` Phenotype 上書き(`compatible_car_list` / `app_package_list` 等)。
- `kdh.i(CarInfo,List)`→true(車フィルタ)、`nlc.e()`(タッチ HU 判定。OneNavi はタッチ必須なので通常問題なし)。

#### 残る検証ポイント(リスクは低下)
- `kbf.d` が参照する Phenotype 値の **root 上書きの実効性**(AA-Phenotype-Patcher 系 or フラグ read hook)。最悪 `ltz.b()` 直接 hook で代替可。
- フラグ強制後、実機 HU で **実際に投影・タッチが通るか**(描画は phone-side のため通る見込みだが要実証)。
- **STEP1(実機)で `ltz.b()`→true を入れて parked-app が起動するか**を最優先で確認(D20)。

---

## 1. 背景 / 解決したい問題

OneNavi の Android Auto 地図描画は現状 **VirtualDisplay + Presentation + ComposeView** で実装している
(`composeApp/src/androidMain/.../car/OneNaviCarMapRenderer.kt`)。これは:

- フレームがラグい(producer/consumer fps 不一致・Choreographer vsync 問題)
- タッチが semantic callback のみ → 合成 MotionEvent を自前転送するハックが必要

Google Maps の Android Auto はこれらの問題が無い。その理由を APK 実物解析で突き止め、第三者
(OneNavi)が同等の「本物の Window + 本物のタッチ + リッチ UI」を得る経路を確定するのが目的。

---

## 2. 調査で確定した事実(実物 APK 解析ベース)

### 2.1 車載に「自前 Activity を出す」経路は2本しかない

gearhead(`com.google.android.projection.gearhead` 16.8.661854)の `setLaunchDisplayId` 呼び出し
13箇所を全分類した結果:

| 経路 | 第三者の任意 Activity を projection display に出せるか | 実体 |
|---|---|---|
| **NATIVE_APP / parked-app** | ✅ 第三者が現実に使える唯一の路 | `iij`(NativeAppCarActivityManager `CAR.CAM.NATIVE`)+ `lzt`(`GH.PhoneActivityLaunchr`) |
| **gms.car PROJECTION** | ✅ ただし first-party 署名 + 閉じた SDK 必須 → 実質不可 | `iiz`(GhostActivityManager) |
| その他11箇所 | ❌ | display 0(スマホ側) or gearhead 自前固定 Activity |

- projection display は **gearhead 所有の private VirtualDisplay**(legacy `nmg`: `createVirtualDisplay(flags=0x0)`、API34+ は `VirtualDeviceManager` 所有 display)。
  → **第三者が自力で `setLaunchDisplayId` して車画面を直接狙う道は OS が弾く。必ず gearhead に launch させる必要がある。**

### 2.2 PROJECTION(Maps 式)が第三者に不可な理由

- `GhostActivity`(`com.google.android.apps.auto.client.activity.ghost.GhostActivity`)は**透明な lifecycle の殻**。
  `onCreate` は通常時 `ghost_blank_layout`(透明)を出して `return` するだけ。地図は描いていない。
- 車に映るピクセルの実体は **`com.google.android.gms.car.DrawingSpec.d:Landroid/view/Surface;`**(protocol で配られる Surface)。
  これを受けて描くのは `CarActivityService`(閉じた gms.car SDK)。
- 第三者がこの経路に乗るには「閉じた SDK の projection client 再実装 + first-party 署名」が必要 → 非現実的。
- ∴ **「GhostActivity の onCreate を override して描く」は不成立**(描画先が Activity の window ではなく別 Surface のため)。

### 2.3 parked-app は「自分の通常 Activity」をそのまま描ける

- gearhead が `startActivity(自分のActivity, ActivityOptions.setLaunchDisplayId(車のdisplay))` で起動。
- **自分の Activity の window が車 display に直接レンダされる** → `OneNaviApp()` をそのまま描画でき、本物の MotionEvent が届く。
- SDK 実装も override も不要。これが目的に最も合う。

### 2.4 parked-app には独立した3枚のゲートがある

| ゲート | 判定 | 場所(smali) |
|---|---|---|
| A parked-app 可用性(この車で使えるか) | `GH.ParkedNativeAppCheck`(`ltz.b()`): `hasNativeAppsModule`(フラグ)+ SDK + `kdh.i(CarInfo, compatible_car_list)` + `nlc.e()`(touch) | `ltz`/`kdh`/`nlc` |
| B eligibility(このアプリを許可) | `irr.y()` の許可判定。`I()`=`allow_unknown_sources` + category∈`m`(NATIVE_APP を含む)。+ `app_package_list` | `smali/irr.smali` |
| C driving(走行中も出すか) | `lzt` が `kwr.c()→lei.c()→ldx.d()` を見て **`CAR_MOVING` なら起動中止**("Abort phone activity launch (user is driving)") | `lzt`/`lei`/`ldx` |

- A/B/C は**別レイヤー**。`irr.H()`(dev機判定)は C に一切効かない。突破手段は §3.2〜3.4、詳細な発見は §0 参照。
- **A** は Phenotype `CradleFeature__*` 駆動の phone-side allowlist(実車で出ない実態の原因)。
- **C** の `CAR_MOVING/CAR_PARKED` は **速度のみ**で決まる(`ldx.g()`: `abs(speed) > 閾値`)。dev/DHU/carInfo を参照しない。
  - 速度ソース: `ldw.a()F → Lackg.b()D`(系統A) / `lek.a()F → Lackg.c()D`(系統B)。
  - `Lldp.a=CAR_MOVING` / `Lldp.b=CAR_PARKED` / `Lldp.c=UNKNOWN`(UNKNOWN は abort されない)。
- **DHU が常に動くのは「dev だから免除」ではなく「速度0しか送らない=常に CAR_PARKED」だから**(ただしゲートA は DHU でも別途要確認)。

---

## 3. 方針(D1〜D25 反映)

### 3.1 全体像 — ゲートは3層

```
目的: 走行中も OneNavi の通常 Activity を車 display に出し、本物タッチ+リッチ UI を得る(D1 純 NATIVE_APP)

ゲートA parked-app 可用性 : この車で parked-app 機能が使えるか(ltz / Phenotype compatible_car_list)
ゲートB app eligibility   : OneNavi が parked-app として許可・提供されるか(allow_unknown_sources / app_package_list)
ゲートC driving           : 走行中も起動・維持できるか(速度を0偽装)
```

A/B は「出せるか」、C は「走行中も出せるか」。**A は 🔴 発見で判明した parked-app 固有のゲート**(従来 §2.4 の `irr.y()` だけでは不足)。

### 3.2 ゲートA: parked-app 可用性(`ltz` / Phenotype)

`GH.ParkedNativeAppCheck`(`ltz.b()`)が判定。突破手段(いずれか):

```
[本命] ltz.b()/ltz.a() → true        （ParkedNativeAppCheck を素通し。1点で全条件無視）
[代替] Phenotype 上書き:
   CradleFeature__compatible_car_list       → 自車 CarInfo を追加（kdh.i の照合を通す）
   CradleFeature__app_package_list          → me.matsumo.onenavi を追加
   CradleFeature__app_launcher_package_list → 同上
[補助] kdh.i(CarInfo,List) → true    （車フィルタ。ltz 直 hook なら不要）
       nlc.e() → true                （HU タッチ判定。タッチ HU なら不要）
```

DFM 鶏卵問題は無し(コードは base APK に在りフラグゲートのみ。§0 参照)。

### 3.3 ゲートB: app eligibility(D9)

- OneNavi を **NATIVE_APP 宣言**(§3.6) + **`app_package_list`/`app_launcher_package_list` に追加**(Phenotype 上書き、§3.2 と同じ枠)。
- 加えて **`allow_unknown_sources` を ON**(AA 開発者設定。NATIVE_APP は許可集合 `m` に在るので署名不要)。設定で足りなければ `irr.y()` を OneNavi 限定 true。
- **D9: `irr.H()` は使わない**(AA 全体の dev 化を避ける)。

### 3.4 ゲートC: driving(D2 常時グローバル)

最下流の速度を 0 にすると `ldx.g()` が常に `CAR_PARKED` を算出し、**起動ゲート(`lzt`)も実行中の走行リスナ(dialpad 等が登録)も両方** parked を見る(走り出してからの撤去も防げる)。

```
hook: ldw.a()F → return 0.0f
hook: lek.a()F → return 0.0f
```

- **D2: スコープは常時グローバル**(OneNavi 前面判定はしない)。Maps 含む全 AA が停車扱いになることを許容。
- `ldx.d()→CAR_PARKED` を叩く案より、速度ソース(`ldw`/`lek`)を叩く方が listener push 経路も含めて完全。

### 3.5 hook 運用(D5/D10/D11/D18)

- **D5**: LSPosed + 文字列アンカー自動解決。難読化名を anchor から動的解決:
  `"Abort phone activity launch (user is driving)"` / `CAR_MOVING` / `NATIVE_APP` / `"Has touch: %b"` / `GH.ParkedNativeAppCheck` 等。
- **D10**: kill-switch = LSPosed モジュール ON/OFF のみ。「使う時だけ有効化」を徹底。
- **D11**: gearhead は Play 自動更新 OFF で固定。手動更新時に anchor を再検証。
- **D18**: hook 解決失敗 / 起動失敗時は、OneNavi スマホ側に状態・原因を表示(自己診断)。

### 3.6 OneNavi アプリ側(hook と独立。D22 本体に統合)

1. **専用 CarActivity 新規**(D8): 車 display 用の独立 Activity で `OneNaviApp()` をホスト。**D13: スマホ UI をそのまま**出す(車画面比の視認性は §6 残課題)。**D25: AA の day/night に追従**。
2. **NATIVE_APP 宣言**(manifest):
   ```xml
   <meta-data android:name="com.google.android.gms.car.application"
              android:resource="@xml/onenavi_car_app_desc"/>
   ```
   `onenavi_car_app_desc.xml` に `<uses name="native_app"/>`(qkj `NATIVE_APP` と ignoreCase 照合)。※綴りは実測で確定(§6)。
3. **AA 接続検知**(D16): 専用 CarActivity の起動を合図に、同一プロセスのフラグで MainActivity に伝える(androidx.car.app は使わない)。
4. **ドライブロック**(D7/D12/D28): AA 接続検知でスマホに専用ロック画面を表示(**メッセージのみ「車画面で操作中」**、操作ボタンなし)。切断時は解除。
5. **セッション継続**(D26/D27): 単一プロセス・状態共有。**接続時は進行中ナビをそのまま車に引き継ぎ**(MainActivity と CarActivity が同じ nav 状態を参照)、**切断時はロック解除しスマホでナビ継続**(セッションは止めない)。
6. **音声**(D14/D29): TTS は車スピーカー経由(AA 音声フォーカス / Bluetooth)。案内時は**ダッキング**(他音声を一時小音量)。
7. **接続形態**(D17): 有線 USB 中心(開発・実車とも)。
8. **走行中 UI 制限なし**(D19。自己責任)。

---

## 4. 検証ラダー(STEP0 → 実車)

> ⚠️ 🔴 発見により、当初の「DHU は hook 無しで開く」前提は**要再検証**。parked-app 可用性(ゲートA)は DHU でも `ltz`(`compatible_car_list` / `hasNativeAppsModule` フラグ / touch)に依存し、**DHU が allowlist に含まれる保証はない**。よって最初に「ゲートA を開けば parked-app が出るか」を実機 root で確認する。

```
[STEP0] 実機 root で最優先実証(D20)
  - ltz.b()→true(or Phenotype 上書き)で parked-app が DHU/実車に出るか
  - = ゲートA を開ければ parked-app 経路が成立するかの可否判定(計画の生死)

[STEP1] アプリ実装 + 表示・タッチ実証
  - 専用 CarActivity + NATIVE_APP 宣言(§3.6)
  - ゲートA(ltz/compatible_car_list)+ ゲートB(allow_unknown_sources/app_package_list)を開けて起動確認
  - 描画の滑らかさ・本物タッチ・ラグ解消を確認(目的達成可否がここで判明)

[STEP2] 実車・走行実証
  - ゲートC(速度 hook: ldw/lek→0f)を追加
  - 走行中も出っぱなしを実証
```

---

## 5. 確定 hook / 改変一覧

**LSPosed module**(target: `com.google.android.projection.gearhead` / D5 anchor 解決 / D6 Pixel10+Magisk):

```
ゲートA parked-app 可用性:
  ltz.b()/ltz.a() → true                  （本命: ParkedNativeAppCheck 素通し）
  or Phenotype 上書き: CradleFeature__compatible_car_list / app_package_list / app_launcher_package_list
  kdh.i(CarInfo,List) → true              （ltz 直 hook なら不要）
  nlc.e() → true                          （タッチ HU なら不要）

ゲートB eligibility:
  allow_unknown_sources 設定 ON、足りねば irr.y() を OneNavi 限定 true    （H() は不使用 D9）

ゲートC driving（D2 常時グローバル）:
  ldw.a()F → 0.0f
  lek.a()F → 0.0f

運用: D10 LSPosed ON/OFF が kill-switch / D11 gearhead 自動更新 OFF / D18 失敗時スマホ表示
```

**OneNavi app**(D22 本体に統合):

```
- manifest: com.google.android.gms.car.application → onenavi_car_app_desc.xml(<uses name="native_app"/>)
- 専用 CarActivity（OneNaviApp() ホスト, D8 / UIそのまま D13 / day-night D25）
- AA 接続検知 = CarActivity 起動フラグ（D16）→ MainActivity ドライブロック（D7/D12）
- TTS 車スピーカー（D14） / hook 未有効時の状態表示（D18）
```

---

## 6. 残課題 / 実測で確定すべき点

- **[最優先 D20] 実機で `ltz.b()`→true / Phenotype 上書きにより parked-app が起動するか**(DHU が `compatible_car_list` に含まれるかも未知)。計画の生死を分ける。
- Phenotype 値の **root 上書きの実効性**(AA-Phenotype-Patcher 系 / フラグ read hook)。最悪 `ltz.b()` 直 hook で代替。
- `<uses name>` の正確な綴り(`native_app` か別表記か) → 実機実測。
- `irr.y()` のシグネチャと package 引数が hook で拾えるか → 拾えれば設定だけで済む。
- API34+ の `VirtualDeviceManager` 経路で NATIVE_APP の activity policy 追加ゲートが無いか。
- 難読化名(`ltz`/`kdh`/`nlc`/`irr`/`lzt`/`ldx`/`ldw`/`lek`)の anchor 解決の堅牢性と更新追従。
- セカンダリ display での Compose の density / inset / IME 挙動、Google Maps SDK のライフサイクル、Koin DI の多 display 挙動。
- **D13「スマホ UI そのまま」**の車画面比(横長/正方/縦)での視認性 → 後調整余地。
- 速度0グローバルが **OneNavi 自身のナビ(独自 FusedLocation, 別プロセス)に干渉しない**こと → 原理上無干渉だが要実証。
- (実装メモ)切断 / 車 off 時の CarActivity teardown → ドライブロック解除 → スマホ継続(D27)の遷移を確実にハンドリング。

---

## 7. 出典(git 管理外の解析成果物 = `../../APK/GoogleMap/cc_analysis/`)

- `REPORT_google_maps_android_auto.md` — Maps の描画方式(GhostActivity 透明殻 / DrawingSpec Surface)。
- `REPORT_gearhead_projection_gating.md` — eligibility 多層 enforce、`H()`/`I()`。
- `REPORT_escape_hatches.md` — `irr.y()` 判定ロジック、category 集合 `m`。
- gearhead smali: `irr.smali`(`y()`/`L()`/`H()`)、`lzt.smali`(走行ゲート)、`ldp/lds/lei/ldx/ldw/lek.smali`(走行状態・速度)、`iij`/`iiz`/`nmg`(launch・display 生成)。
- 主要文字列: `"Abort phone activity launch (user is driving)"` / `CAR_MOVING` / `CAR_PARKED` / `NATIVE_APP` / `com.google.android.gms.car.application` / `com.google.android.gms.car.DrawingSpec`。
- OSS 裏取り: AAMirror / Screen2Auto / aa-proxy-rs / AA-Phenotype-Patcher 等(`docs/logs/10` 参照)。
