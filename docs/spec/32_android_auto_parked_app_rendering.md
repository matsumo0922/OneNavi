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
| D2 | 速度 hook 範囲 | ⚠️**要改訂(保留)**。当初「`ldw`/`lek`→0f 常時グローバル」としたが、Codex 検証で **これは lzt(スマホ)系で車 parked-app の走行ゲートE に効かない**と判明。E(`iet`/`qhi`)の hook 点を §6-1 で trace 後に改訂 | 2026-06-06 |
| D3 | 既存経路 | **削除実行済**。`OneNaviCarMapRenderer`/`OneNaviCarAppService`/`OneNaviCarSession`/`OneNaviCarMapScreen` と androidx.car.app 経路を撤去。**template 経路が native-app 分類を妨げていたため撤去は必須だった**(STEP1a で判明) | 2026-06-06 |
| D4 | 実車 HU 対応 | 出ない主因は **Phenotype `compatible_car_list` 未収載**(phone-side allowlist)で root 強制有効化を狙う。ただし **HU の touch/capability/投影ネゴで弾かれる余地は残る**(ltz は phone-side gate だが HU 依存条件あり) | 2026-06-06 |
| D5 | hook 方式 | **LSPosed + 文字列アンカー自動解決**。anchor は **車 parked-app 系**(`GH.ParkedNativeAppCheck` / `"removing active app for the region"` / `"with launchDisplayId"` / `NATIVE_APP` / `"Has touch: %b"`)を主役にする。`"Abort phone activity launch..."` / `CAR_MOVING` は **スマホ側 lzt 系**で本計画の対象外(混同しない) | 2026-06-06 |
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
| D30 | 検証の山場 | 実機検証はまず **C+D(停車中に OneNavi を車画面へ起動・本物タッチ)** を最優先で実証。E(走行維持)は次段 | 2026-06-06 |
| D31 | D3 再々確認 | Codex 指摘(5ゲート化で確度低下)後も **即時削除を維持**(ユーザ再確認)。git revert で担保 | 2026-06-06 |
| D32 | Xposed ランタイム/API | device ランタイム = **Vector**(JingMatrix、Zygisk)。モジュールは **legacy `de.robv.android.xposed:api:82`(`api.xposed.info` maven、compileOnly)**でビルド(Vector 互換)。modern libxposed API は public maven 非公開のため不採用。モジュール = `xposed-carunlock`(repo 直下、D23) | 2026-06-06 |
| D33 | Gate C hook 方式 | 難読化 `bwn` ではなく **framework API `VirtualDeviceParams$Builder.setAllowedActivities(Set)` を hook** し OneNavi component を注入(AA 更新に強い)。Gate A は `ltz` の boolean a()/b() を true 固定 | 2026-06-06 |
| D34 | Gate E 入口 | **走行 stop の実行元 = `GH.ParkedAppMgr`(`lxn`)の reactive 購読 `lxl.eJ()`**(trace で特定)。`iet`/`qno`(UXR/センサ)でも `lzt`/速度系でもない。hook 本命 = **`lxl.eJ` を no-op**。`qhi` は後段 cleanup で hook 点ではないと確定 | 2026-06-07 |
| D35 | Gate E 第2経路 | E-1(`lxl.eJ`)だけでは不足。トースト `parked_manager_close_app` を逆引きし、`GH.IntentInterceptor` "ParkedApp" = **`lxi.b(Intent)`** が走行中の起動/継続をブロック+閉じる第2経路と判明。**`lxi.b` を OneNavi 限定 false**(E-2)で対応。`mjo` 他サブクラス `lhz`=Media は無関係 | 2026-06-07 |

### ⚠️ D3 × D4 のリスク(許容済)

D3(即時削除)+ D4(HU 未確認)により、**実車 HU が parked-app 非対応だった場合 OneNavi は AA で一切表示できなくなる**。
ユーザ判断で**このリスクを取る(parked-app に賭ける)**。ただし保険として:
- 削除は **git 履歴を残す形**で行い、非対応判明時に revert 可能にする。
- **STEP0(`ltz.b()`→true で parked-app が出るか)を最優先**で行い、早期に「parked-app 経路が成立するか」を確認する(D20)。

### D2 の安全注記(常時グローバル)

※当初の「速度0グローバル hook」は Codex 検証で**車 parked-app の走行ゲートE に効かない**と判明し保留(D2)。よって「Maps 含む全 AA が停車扱い」という旧説明は現計画に当てはまらない。Gate E の正確な hook が決まり次第、その影響範囲(対象 callback / グローバル可否)を再評価する。
いずれにせよ LSPosed モジュールの有効/無効が kill-switch(D10)。運用時は「使う時だけ有効化」を徹底する。

### 🔴 重大な発見(2026-06-06)— parked-app 可用性は HU ではなく Phenotype フラグで決まる

実車(投影型 HU、AAOS ではない)で parked-app が出ない件を解析した結果:

- parked-app の可用性判定は **`GH.ParkedNativeAppCheck`(`ltz.b()`)** が行う。判定材料:
  1. `hasNativeAppsModule`(`cradle_features` car module の存在 ＝ スマホ側 DFM)
  2. `Build.VERSION.SDK_INT >= 最低版`
  3. 車接続 + `CarInfo != null`
  4. **`kdh.i(CarInfo, List)`** ＝ CarInfo を **`CradleFeature__compatible_car_list`** と照合(allowlist/filter mode)
  5. **`nlc.e()` = `rji.d()`** ＝ HU がタッチ対応か(`"Has touch: %b"`)
- 関連 Phenotype フラグ(`CradleFeature__*`、サーバ配信): `compatible_car_list` / `app_package_list` / `app_launcher_package_list` / `all_app_launcher_enabled` / `allow_video_apps` / `allowed_activities_list` / `extended_toolbar_enabled_cars`。

**結論: 実車で出ない主因は Google が `compatible_car_list`(phone-side allowlist)で対応車種を絞っているから**(描画はスマホ側 gearhead で完結し、HU は主に映像+入力端末)。**ただし `ltz` は HU の touch/capability を見るため、非収載 HU が無条件で通るとは限らない**(投影ネゴや touch 要件で弾かれる余地は残る。Codex 指摘)。
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

### 🔵 実機検証(2026-06-06)— Xperia / Phenotype 未配信

- 実機: Xperia XQ-FS44、**root 取得済(Magisk)**、gearhead **16.8.661854(versionCode 168661854)= 解析した版と完全一致**。
- gearhead の Phenotype は**丸ごと未配信**(`files/phenotype/shared/...gearhead.pb` が 6 bytes、更新 2026-03-28)。中央 GMS `phenotype.db` にも `CradleFeature*` は **0 件**。
- ∴ 実機の `compatible_car_list` = **コンパイルデフォルト `{mode:4, リスト空}`**(`aclg.d()` の default `"CAQ"`=protobuf `{1:4}`)。サーバ配信の車リストは存在しない。
- 含意: **Phenotype 上書きより `ltz.b()`→true 直 hook が確実**(`hasNativeAppsModule` は `kcj.a(waq("cradle_features"))` の car module feature チェックで、純フラグでないため Phenotype 単独で満たせない可能性)。

### 🟠 Codex レビュー反映(2026-06-06)— 重大な誤り訂正

Codex レビューの全指摘を smali で再検証し、**全て正しいと確認**。spec を §2.4/§3/§4/§5/§6 で訂正済:

- **`lzt` は parked-app の走行ゲートではない**(`lpf.c()`=`setLaunchDisplayId(0)`=スマホ画面)。"Abort phone activity launch (user is driving)" はスマホ activity 用。車 parked-app の走行制限は別系統 **ゲートE(`iet`/`qhi`/`qno`=CAR.SENSOR)**。
- 車 launch 本命は **`iij`**(`setLaunchDisplayId(carDisplayId)`、`iij:1348`)。
- **VDM activity policy(`setAllowedActivities`/`addActivityPolicyExemption`)が中心ゲート**(ゲートC)。OneNavi の Activity を許可集合に入れる必要。
- SDK 条件は **Android 15 / API 35**(`ltz:150` `0x23`)。「API34+」は誤り。
- ゲートを **A〜E の5段**に再構成(§2.4 / §3)。
- 最大破綻点(Codex): **ゲートE(走行維持)**と **ゲートC(VDM policy)**。「ランチャに出たのに起動しない / 停車中だけ出て走り出すと撤去」が最も起きやすい失敗。

#### 再判断結果(Codex 指摘 → ユーザ確認済)

- **D3(即時削除)**: Codex は保留を推奨したが、**ユーザは即時削除を維持(D31)**。git revert で担保。
- **検証順序(D30)**: まず **C+D(停車中に車画面へ起動・本物タッチ)** を実証 → 成立後に E(走行維持)。
- **D2(速度0)** は要更新(未確定): hook 対象 `ldw`/`lek` は **lzt(スマホ)系で車 parked-app の走行ゲートE に効かない**。E は `iet`/`qhi` の抑止が必要。**E の正確な hook 点を §6-1 で trace してから D2 を改訂**(それまで「速度0」記述は無効扱い)。

---

### 🟢 Codex 再レビュー反映(2026-06-06 第2回)— PoC まで条件付き合意

2回目レビューの指摘を全て smali で再検証し反映:

- (#1) D4 の「DFM 入手が前提条件」削除(DFM 鶏卵問題は解消済で矛盾していた)。
- (#2) D2 安全注記の「速度0=全 AA 停車扱い」は旧前提なので無効化を明記。
- (#3) hook anchor を **車 parked-app 系**(`GH.ParkedNativeAppCheck` 等)主役に。`"Abort phone activity launch"`/`CAR_MOVING` は **スマホ lzt 系で対象外**と分離。
- (#4) 「HU 技術的非対応ではない」を緩和(HU touch/capability/投影ネゴで弾かれる余地を明記)。
- (#5) **`qhi` は走行起点ではなく、Activity stopped 後の registry cleanup(結果観測点)**と訂正(`qhi:594`/`qpy:811`)。Gate E の入口=「走行開始時に誰が active app を stop させるか」は**未特定 → 要 trace**。

**合意ステータス**:
- ✅ **STEP1a(ゲート A/B/C/D)= PoC は合意**。停車中に OneNavi Activity が車 display に出て本物 MotionEvent が届くかを実機で積む。
- ⏸ **STEP2(ゲート E / 走行維持)= 未合意**。`iet`/`qno` から走行 stop の実呼び出し元を trace し入口を掴むまで「成立見込み」止まり。`qhi` 決め打ち禁止。

---

### 🎉 STEP1a 実証成功(2026-06-06 / DHU)

ゲート **A/B/C/D を実機(DHU)で突破し、OneNavi の CarActivity が projection display に起動・本物タッチを確認**。

- `displayId = 20`(projection display)で CarActivity 描画。赤い箱を**ドラッグできた = 本物 MotionEvent 到達**(合成不要)。
- 突破に使った hook / 設定(全て phone-side):
  - Gate A: `ltz` の boolean a()/b() を true 固定(`ForceTrue` 発火確認)。
  - Gate B: AA「不明なソース」ON + `kgj.e(app_package_list, pkg)` を OneNavi に true 固定(opt-in。Phenotype 未配信で空のため hook 必須)。
  - Gate C: `VirtualDeviceParams.Builder.setAllowedActivities` に OneNavi 注入。
  - Gate D: `iij` が車 display に起動。
- **実装で判明した重要点**:
  1. **旧 template 経路(`OneNaviCarAppService` + `<uses name="template"/>`)が native-app 分類を妨げる** → 撤去必須(D3 実行済)。残すと gearhead が template app と見なし `No opted-in native apps found` になる。
  2. opt-in は package list(`app_package_list` 等、Phenotype 駆動)で gate され、未配信端末では空 → `kgj.e` hook で突破。
- **未達**: Gate E(走行維持)。DHU は速度0で非発火。実車走行での維持は `iet`/`qno` trace 後(STEP2)。

---

### 🎯 Gate E 入口特定(2026-06-07)— `GH.ParkedAppMgr`(`lxn` / `lxl.eJ`)

実車で D レンジに入れた瞬間に OneNavi が撤去された事象を起点に、走行 stop の**実呼び出し元を trace で特定**(spec が長く「未特定・要 trace」としていた点)。デコンパイル(jadx)+ smali + 主要文字列で裏取り済。**結論: 走行 stop は `iet`/`qno`(UXR/センサ)でも `lzt`/速度系でもなく、`GH.ParkedAppMgr`(難読化 `lxn`)の reactive 購読 `lxl.eJ()` が実行している。**

**配線**(`lxn.fz()` = 接続時ライフサイクル):

```
this.i = swv.e( kcx.a(),       // carToken
                mjx.b().h,     // 起動中アプリ Map (mjw)
                ldv.b().a(),   // isParked（★ldv は 500ms debounce: Duration.ofMillis(500)）
                lxm.a )        // combinator → lxk(=ParkedAppState) を生成
this.i.fy(kwr.d(), this.j)     // this.j = lxl を lcj(接続)スコープで購読
```

`lxm` が組む `lxk` の `toString()` = `ParkedAppState(carToken, appsByRegion, isParked)`(自白)。`lxk.c` = **isParked**、`mkw.c` = **isParkedOnly**(`mkw.toString()` = `ProjectionApp(appKey, appCategory, isParkedOnly, isImmersiveApp, isNativeApp)` で確認)。

**蹴り出し本体**(`lxl.eJ(lxk)`):

```java
if (lxkVar.a == null || lxkVar.c) return;        // token無 or 停車中(isParked)は素通り
for (各リージョンの起動中アプリ mkwVar) {
    if (mkwVar.c && mgg.G().b(carRegionId)) {     // isParkedOnly かつ display 有効
        if (メイン display + dashboard 有) {
            log "Stopping %s. Showing dashboard instead"
            nln.b().a().i();                       // dashboard が画面を奪う
        } else {
            log "Stopping %s. Starting %s instead"
            mjy.a().k(replacementIntent, qlm);     // 代替 Activity を起動して押し出す
        }
    }
}
```

- `if (... || lxkVar.c) return` のため **停車中は何もせず、走り出した瞬間(isParked=false)だけ発火**。これが Gate E の本体。debounce 約 500ms 遅延。
- stop は `finish()` 直呼びではなく **dashboard / 代替 Activity で画面を奪って押し出す**方式。結果 OneNavi の Activity が onStop → `qpy.g()` → `qhi` case 16(`"removing active app for the region"`)。**∴ `qhi` は後段 cleanup(結果観測点)で確定**(Codex の「決め打ち禁止」が正しかった)。
- **`ldw`/`lek`→0(lzt/速度系)が効かない予想も裏付け**。走行 stop は速度系ではなく `GH.ParkedAppMgr` の reactive 購読経由。

**hook 候補**(Gate E):

| 案 | hook 点 | 効果 | 評価 |
|---|---|---|---|
| **本命** | `lxl.eJ(Object)` を no-op(即 return) | parked-only アプリを走行中に一切蹴らない | 一点・確実。自端末は parked-app が OneNavi のみのため全停止で実害なし(D19) |
| 外科的 | `lxl.eJ` の `beforeHookedMethod` で `lxk.b`(Map)から OneNavi の region を除外して通す | OneNavi だけ免除、他アプリは安全挙動維持 | 正確だが `lxk` 再構築 + package 判定が要りもろい |
| 配線断 | `lxn.fz()` で `this.i.fy(...)` 購読をスキップ | 購読自体させない | fz が executor 等も初期化、副作用リスク |

`ldv.b().a()`(isParked グローバル)直叩きは D2 の「速度0」同様に影響範囲過大で非推奨。

**バージョン追従アンカー**(D5):クラス = `"GH.ParkedAppMgr"`(`lxn`)、蹴り出し = `"Stopping %s. Showing dashboard instead"` / `"Stopping %s. Starting %s instead"`。

#### 第2経路 `lxi`(2026-06-07 追加特定)— Gate E は2経路

`lxl.eJ`(E-1)を no-op 化しただけでは**実車で "運転中は使用できません" トーストが出てアプリが閉じた**。原因をトースト文字列から逆引きして判明:

- トースト = string `parked_manager_close_app`(id `0x7f150790`、"Not available while driving" / 日本語版「運転中は使用できません」)。これを出すのは `lxn.b(nmf)`(`context.getText(0x7f150790)`)。
- `lxn.b()` の呼び出し元は **`lxl.eJ`(line 376)だけでなく `lxi` もある**(`grep Llxn;->b(` で2箇所)。
- **`lxi`** = `GH.IntentInterceptor`(`mjo`)の "ParkedApp" 実装。`lxi.b(Intent)` が「走行中この起動/継続 intent を parked-only としてブロックすべきか」を判定(`mkw.c`=isParkedOnly かつ走行中で true)、true なら `lxi.a(Intent)` が `lxn.b()` でトースト+閉じ。constructor で `ldv.b().a()`(isParked)を購読。

∴ **Gate E は2経路**:

| 経路 | 役割 | hook |
|---|---|---|
| **E-1** `lxl.eJ`(GH.ParkedAppMgr) | 既に**表示中**のを走行開始で dashboard/代替 Activity へ置換 | `eJ` を no-op |
| **E-2** `lxi`(GH.IntentInterceptor "ParkedApp") | 走行中の**起動/継続 intent をブロック**しトースト+閉じ | `lxi.b(Intent)` を OneNavi 限定 false(他 package 素通し) |

E-1 を no-op にしても継続 intent が E-2(`lxi`)で再ブロックされるため、**両方塞いで初めて走行維持が成立**。`mjo` のもう一つのサブクラス `lhz` は "Media"(メディアアプリのリダイレクト)で走行制限とは無関係 → 漏れなし。IntentInterceptor を回す実行点は `mjy`。

**残る未確認**: UXR 系(`iet`/`ieu` の "always restricted")は別系統のコンテンツ制限(完全 stop でなく UI ブランク/操作制限)の可能性。E-1/E-2 hook 後も「落ちないが操作不可/暗転」が残れば `iet` 側を別途 trace する(未着手)。

> ⚠️ 注: 本 trace は jadx デコンパイル + smali で `lxn`/`lxl`/`lxk`/`mkw`/`ldv`/`lxm`/`qpy`/`qhi` を直接確認済(gearhead 16.8.661854)。難読化名はこの版前提(D11 で版固定)。

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
| **NATIVE_APP / parked-app** | ✅ 第三者が現実に使える唯一の路 | `iij`(NativeAppCarActivityManager `CAR.CAM.NATIVE`、`setLaunchDisplayId(carDisplayId)`)。※`lzt`=`GH.PhoneActivityLaunchr` は **display 0=スマホ向けで別物**(§2.4 訂正参照) |
| **gms.car PROJECTION** | ✅ ただし first-party 署名 + 閉じた SDK 必須 → 実質不可 | `iiz`(GhostActivityManager) |
| その他11箇所 | ❌ | display 0(スマホ側) or gearhead 自前固定 Activity |

- projection display は **gearhead 所有の private VirtualDisplay**(legacy `nmg`: `createVirtualDisplay(flags=0x0)`、API34+ は `VirtualDeviceManager` 所有 display)。
  → **第三者が自力で `setLaunchDisplayId` して車画面を直接狙う道は OS が弾く。必ず gearhead に launch させる必要がある。**

### 2.2 PROJECTION(Maps 式)が第三者に不可な理由

- `GhostActivity`(`com.google.android.apps.auto.client.activity.ghost.GhostActivity`)は**透明な lifecycle の殻**。
  `onCreate` は通常時 `ghost_blank_layout`(透明)を出して `return` するだけ。地図は描いていない。
- 車に映るピクセルの実体は private projection contract の Surface。**旧経路 = `com.google.android.gms.car.DrawingSpec.d:Surface` → `VirtualDisplay` → `Presentation`、新経路 = `InputTransferToken` → `SurfaceControlViewHost`(SCVH)**(Codex codex_analysis/reports/01:156)。地図自体はその中で native/GL renderer。
- これを受けて描くのは `CarActivityService`(閉じた gms.car SDK)。第三者がこの経路に乗るには「閉じた SDK の projection client 再実装 + first-party 署名」が必要 → 非現実的。
- ∴ **「GhostActivity の onCreate を override して描く」は不成立**(描画先が Activity の window ではなく別 Surface/SCVH のため)。
- ただし **parked-app 経路(§2.4 ゲートD `iij`)は別**で、第三者の普通の Activity を車 display に直接 launch する(SDK 不要)。OneNavi はこちらを使う。

### 2.3 parked-app は「自分の通常 Activity」をそのまま描ける

- gearhead が `startActivity(自分のActivity, ActivityOptions.setLaunchDisplayId(車のdisplay))` で起動。
- **自分の Activity の window が車 display に直接レンダされる** → `OneNaviApp()` をそのまま描画でき、本物の MotionEvent が届く。
- SDK 実装も override も不要。これが目的に最も合う。

### 2.4 parked-app には独立した5枚のゲートがある(Codex レビューで再構成・全 smali 検証済)

| ゲート | 判定 | 場所(smali) |
|---|---|---|
| **A** parked-app 可用性 | `GH.ParkedNativeAppCheck`(`ltz.b()`): **`SDK_INT>=35`(`0x23`)** + `hasNativeAppsModule`(`kcj.a(waq("cradle_features"))`= car module feature チェック)+ `kdh.i(CarInfo, compatible_car_list)`(filter mode 付き)+ `nlc.e()`(touch) | `ltz:150`/`kdh`/`nlc` |
| **B** app eligibility | `irr.y()`: `I()`=`allow_unknown_sources` + category∈`m`(NATIVE_APP 含む)。+ `app_package_list` | `irr:8687`/`qkj:236` |
| **C** VDM activity policy | VirtualDevice 構築時に `setAllowedActivities(Set)` / `addActivityPolicyExemption()`。**第三者 Activity(OneNavi)を許可集合に入れないと launch が落ちる** | `bwn:1294`/`ijf:4254` |
| **D** car launch | `iij`(NativeAppCarActivityManager): `startActivity(intent, setLaunchDisplayId(carDisplayId))` で**普通の Activity を車 display に起動**(iij 自身に走行チェックは無い) | `iij:1348` |
| **E** runtime driving / UXR | car parked-app の走行制限系。**走行 stop の実行元 = `GH.ParkedAppMgr`(`lxn`)の reactive 購読 `lxl.eJ()`**(2026-06-07 特定)。`isParked=false` 発火時に isParkedOnly アプリを dashboard / 代替 Activity で押し出す。`qhi`("removing active app for the region")は押し出し後の registry cleanup(結果観測点、`qpy.g` 起点)。`iet`("No driving status, always restricted")/`qno`(`CAR.SENSOR`)は別系統の UXR コンテンツ制限 | `lxn`/`lxl`/`lxk`/`mkw`、`iet:266`/`qno`/`qhi:594`/`qpy:811` |

#### ⚠️ 重要訂正(旧版の誤り)

- **`lzt`(`GH.PhoneActivityLaunchr`)は parked-app の走行ゲートではない。** `lpf.c()`=`setLaunchDisplayId(0)` ＝ **スマホのデフォルト display 向け**起動で、`"Abort phone activity launch (user is driving)"` も**スマホ activity 用**(`ldx`/`ldw`/`lek` の速度状態系もこの系統)。**車 parked-app とは別経路。**
- 車 parked-app の走行制限は **ゲートE**(評価=`iet`、センサ源=`qno`=CAR.SENSOR。`qhi` は stopped 後の cleanup で起点ではない)。**走行開始時の stop 実行元は未特定**で、**`ldw.a()`/`lek.a()`→0(スマホ lzt 系)を叩いても E には効かない見込み**(要 trace)。
- SDK 条件は **Android 15 / API 35**(`ltz:150` の `0x23`)。旧版「API34+」は誤り。
- **ゲートC(VDM activity policy)は中心ゲート**。旧版で残課題扱いだったが、第三者 Activity 起動の本丸。

---

## 3. 方針(D1〜D29 + Codex レビュー反映)

### 3.1 全体像 — ゲートは5段(A〜E)

```
目的: 走行中も OneNavi の通常 Activity を車 display に出し、本物タッチ+リッチ UI を得る(D1 純 NATIVE_APP)

A parked-app 可用性   : この車で parked-app 機能が使えるか(ltz: SDK35 + module + CarInfo filter + touch)
B app eligibility     : OneNavi が許可・提供されるか(irr.y / allow_unknown_sources / app_package_list)
C VDM activity policy : 第三者 Activity を VirtualDevice の許可集合に入れる(setAllowedActivities / exemption)
D car launch          : iij が setLaunchDisplayId(carDisplayId) で OneNavi Activity を起動
E runtime driving     : 走行中も維持(走行開始時の active app stop を回避。実行元は要 trace)— 本物の「parked-only」
```

A〜D が「車画面に出せるか」、E が「走行中も維持できるか」。**旧版の「lzt 走行ゲート」「速度0で維持」は誤り**で、E は別系統(§2.4 訂正)。

### 3.2 ゲートA: parked-app 可用性(`ltz.b()`)

```
[本命] ltz.b()/ltz.a() → true   （SDK35 / module / CarInfo filter / touch を一括素通し）
[代替] Phenotype 上書き: CradleFeature__compatible_car_list（自車 CarInfo）/ filter mode
[補助] kdh.i→true / nlc.e()→true
```

⚠️ 実機検証(§0)では Xperia の gearhead Phenotype が**丸ごと未配信**(compatible_car_list = デフォルト mode4/空)。よって **Phenotype 上書きより `ltz.b()`→true 直 hook が確実**(`hasNativeAppsModule` は純フラグでなく car module feature チェックのため Phenotype 単独では満たせない可能性)。

### 3.3 ゲートB: app eligibility(D9)

- **NATIVE_APP 宣言**(§3.8) + **`app_package_list` 追加** + **`allow_unknown_sources` ON**、足りねば `irr.y()` を OneNavi 限定 true。
- **D9**: `irr.H()` は本線では使わない。ただし **DHU/検証短縮用の carInfo spoof は手元に残す**(Codex 助言)。

### 3.4 ゲートC: VDM activity policy(trace 確定 2026-06-06)

gearhead は VirtualDevice を **デフォルト block・許可リストのみ**の policy で構築する(`bwn`):

```
許可集合 v1 = { "com.google.android.apps.auto.carservice.car.activity.virtualdevice.CarVirtualDeviceActivity"(固定) }
             ∪ map( acle.f() )                      # acle.f() → aclg.e() = CradleFeature__allowed_activities_list
log "Allowing %s"
VirtualDeviceParams.Builder.setAllowedActivities(v1)   # bwn:1294。これ以外の Activity は VDM が起動を弾く
```

- **OneNavi の CarActivity が v1 に居ないと、A/B/D が揃っても VDM policy で起動が弾かれる**(失敗の最有力原因)。
- 実機は `CradleFeature__allowed_activities_list` も**未配信=空**(§0 実機検証)。よって OneNavi を入れる手段:

| 手段 | 内容 |
|---|---|
| **本命** | `bwn` を hook し、`setAllowedActivities(v1)` の**直前に OneNavi の CarActivity component を v1 に add**(anchor: `"Allowing %s"`)。Phenotype 非依存で確実 |
| 代替1 | `CradleFeature__allowed_activities_list` を上書きして OneNavi を追加(実機は Phenotype 未配信のため override 生成が必要) |
| 代替2 | `addActivityPolicyExemption()`(`ijf:4254`、`CradleFeature__add_activity_policy_exemption_baklava_kill_switch` 関連=Android16 系)で runtime 例外追加 |

- 出典(検証済): `bwn:1294`(`setAllowedActivities`)、`acle.f()`→`aclg.smali:285`(`allowed_activities_list`)、`ijf:4254`(`addActivityPolicyExemption`)。

### 3.5 ゲートD: car launch(`iij`)

- `iij`(NativeAppCarActivityManager)が `startActivity(intent, setLaunchDisplayId(carDisplayId))`(`iij:1348`)。iij 自身に走行チェックは無い。A/B/C が通れば OneNavi Activity が車 display に出る。

### 3.6 ゲートE: runtime driving / UXR(走行中維持の本丸)— 入口特定済(2026-06-07)

- **走行 stop の実行元 = `GH.ParkedAppMgr`(難読化 `lxn`)の reactive 購読 `lxl.eJ()`**(冒頭「🎯 Gate E 入口特定」ブロックに詳細)。`lxn.fz()` で `(carToken, 起動中アプリ Map, isParked[500ms debounce])` の合成ストリームに `lxl` を購読。`isParked=false`(走行)発火時に、isParkedOnly(`mkw.c`)アプリを **dashboard(`nln.b().a().i()`)/ 代替 Activity(`mjy.a().k(...)`)で押し出す**。
- `qhi`("removing active app for the region")は押し出し後 cleanup(結果観測点、`qpy.g` の lifecycle stopped 通知が起点)。`iet`("No driving status, always restricted")/`qno`=CAR.SENSOR は別系統の UXR コンテンツ制限(完全 stop ではない可能性、未 trace)。
- **`ldw`/`lek`→0(=lzt/スマホ系)では効かない**ことが裏付けられた(走行 stop は速度系でなく ParkedAppMgr の reactive 購読経由)。∴ D2 の「速度0グローバル」は E に無効で確定。
- **Gate E は2経路**(冒頭「第2経路 `lxi`」ブロック参照): E-1 = `lxl.eJ` を no-op、E-2 = `lxi.b(Intent)`(`GH.IntentInterceptor` "ParkedApp")を OneNavi 限定 false。E-1 単独では継続 intent が `lxi` で再ブロックされ閉じるため両方必須。

### 3.7 hook 運用(D5/D10/D11/D18)

- **D5**: LSPosed + 文字列アンカー自動解決。anchor 例: `"No driving status, always restricted"` / `"removing active app for the region"` / `"with launchDisplayId"` / `NATIVE_APP` / `"Has touch: %b"` / `GH.ParkedNativeAppCheck`。
- **D10**: kill-switch = LSPosed モジュール ON/OFF のみ。「使う時だけ有効化」。
- **D11**: gearhead は Play 自動更新 OFF で固定。手動更新時に anchor 再検証。
- **D18**: hook 解決失敗 / 起動失敗時は OneNavi スマホ側に状態・原因を表示(自己診断)。

### 3.8 OneNavi アプリ側(hook と独立。D22 本体に統合)

1. **専用 CarActivity 新規**(D8): 車 display 用の独立 Activity で `OneNaviApp()` をホスト。**D13: スマホ UI をそのまま**出す(車画面比の視認性は §6 残課題)。**D25: AA の day/night に追従**。
   - **`CAR_LAUNCHER` intent-filter** を付ける(gearhead `lxp`=GH.ParkedNativeApps は `android.intent.category.LAUNCHER` と **`android.intent.category.CAR_LAUNCHER`** で native app を発見 → opt-in でフィルタ):
     ```xml
     <activity android:name=".car.CarActivity" android:exported="true">
       <intent-filter>
         <action android:name="android.intent.action.MAIN"/>
         <category android:name="android.intent.category.CAR_LAUNCHER"/>
       </intent-filter>
     </activity>
     ```
2. **NATIVE_APP opt-in 宣言**(manifest):
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

> ⚠️ ゲートは A〜E の5段。STEP を分け、各段を計測しながら積む。観測手段: `adb shell dumpsys display`、`dumpsys activity displays`(どの display に何が起動したか)、OneNavi CarActivity の `onTouchEvent`/Compose pointerInput ログ(本物 MotionEvent 到達確認)、logcat の `GH.ParkedNativeAppCheck`/`iet`/`qhi`/iij の `with launchDisplayId`。

```
[STEP0] 実機 root で「parked-app ランチャ露出」可否(D20)
  - ltz.b()→true(or Phenotype 上書き)で AA に parked-app セクション/アイコンが出るか
  - ⚠️ ランチャ露出 ≠ 起動成功。次段で起動を別途確認

[STEP1a] OneNavi を車 display に起動(ゲートB+C+D)
  - 専用 CarActivity + NATIVE_APP 宣言(§3.8) + app_package_list/allow_unknown_sources(B)
  - VDM allowed activities / addActivityPolicyExemption に OneNavi 追加(C)  ← Codex 指摘の山場
  - iij が launchDisplayId(car) で起動するか dumpsys/logcat で確認(D)
  - 出たら 本物 MotionEvent 到達・描画の滑らかさ・ラグ解消を確認(目的達成可否)

[STEP1b] DHU 可否の確認
  - DHU が ltz の CarInfo filter / touch を満たすか(満たさなければ実車のみで検証)

[STEP2] 実車・走行維持(ゲートE) ※STEP1a 成立後・hook 点 trace 後
  - 先に「走行開始時に active app を stop させる実行元」を trace で特定(iet/qno から辿る。qhi は決め打ち禁止)
  - 特定した入口を抑止する hook を入れ、走り出しても撤去されないか実証
  - ※ ldw/lek→0 だけでは E に効かない見込み
```

---

## 5. 確定 hook / 改変一覧(5ゲート)

**LSPosed module**(target: `com.google.android.projection.gearhead` / D5 anchor 解決 / D6 Pixel10+Magisk):

```
A parked-app 可用性:
  ltz.b()/ltz.a() → true                  （本命。SDK35/module/CarInfo filter/touch を一括突破）
  （代替: CradleFeature__compatible_car_list 上書き。ただし実機は Phenotype 未配信のため直 hook 推奨）

B eligibility:
  allow_unknown_sources 設定 ON、足りねば irr.y() を OneNavi 限定 true   （H() 不使用 D9）

C VDM activity policy（trace 確定）:
  本命: bwn を hook → setAllowedActivities(v1) 直前に OneNavi CarActivity component を v1 に add（anchor "Allowing %s"）
  代替: CradleFeature__allowed_activities_list 上書き / ijf.addActivityPolicyExemption()
  ※許可集合は { CarVirtualDeviceActivity } ∪ map(allowed_activities_list)。デフォルト block なので必須

D car launch:
  iij（hook 不要。A/B/C が通れば iij が setLaunchDisplayId(car) で起動）

E runtime driving（走行維持。入口特定済 2026-06-07。2経路）:
  E-1 = GH.ParkedAppMgr(lxn)の reactive 購読 lxl.eJ()。isParked=false で表示中の isParkedOnly アプリを dashboard/代替 Activity で押し出す
        → lxl.eJ(Object) を no-op（即 return）
  E-2 = GH.IntentInterceptor(mjo)"ParkedApp"=lxi.b(Intent)。走行中の起動/継続を parked-only としてブロックし lxn.b() でトースト+閉じ
        → lxi.b(Intent) を OneNavi 限定 false（component package が OneNavi なら false、他は素通し）。b()=false なら a()(トースト)も呼ばれない
  ※ E-1 だけでは継続 intent が E-2 で再ブロックされ閉じる。両方必須
  ※ mjo の他サブクラス lhz="Media" は走行制限と無関係（漏れなし）
  アンカー: "GH.ParkedAppMgr" / "Stopping %s. Showing dashboard instead" / "Starting %s instead" / string parked_manager_close_app(0x7f150790)
  ※ qhi は stopped 後 cleanup（結果観測点）で hook 点ではない（確定）
  ※ ldw/lek→0 / ldv の isParked グローバル直叩きは影響過大で非推奨

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

## 6. 残課題 / 実測で確定すべき点(優先度順)

1. ~~[最優先] ゲートE の入口特定~~ **完了(2026-06-07)**。実行元 = `GH.ParkedAppMgr`(`lxn`)の `lxl.eJ()`(冒頭ブロック / §3.6)。残= 実機(実車走行)で `lxl.eJ` no-op hook が走行維持に効くかの実証(STEP2)、および UXR 系(`iet`/`ieu`)のコンテンツ制限が別途残るかの確認。
2. ~~ゲートC(VDM activity policy)~~ **trace 確定済**(§3.4): `bwn` で `setAllowedActivities({CarVirtualDeviceActivity} ∪ map(allowed_activities_list))`。本命 hook = `bwn` で v1 に OneNavi CarActivity を add(anchor `"Allowing %s"`)。残= 実機でこの hook が効くか確認のみ。
3. **ゲートA の実証**: `ltz.b()`→true で parked-app が露出するか(STEP0)。**露出 ≠ 起動**なので C/D まで通して初めて成立。
4. **DHU が ltz の CarInfo filter / touch を満たすか**(満たさなければ実車のみで検証。Codex F4)。
5. `<uses name>` の正確な綴り、`irr.y()` シグネチャ。
6. 難読化名(`ltz`/`kdh`/`nlc`/`irr`/`iij`/`iet`/`qhi`/`bwn`/`ijf`)の anchor 解決の堅牢性と更新追従。
7. セカンダリ display での Compose の density/inset/IME、Google Maps SDK の多 display ライフサイクル、Koin DI、本物 MotionEvent 到達(`onTouchEvent`/pointerInput ログで確認)。
8. **D13「スマホ UI そのまま」**の車画面比での視認性 → 後調整余地。
9. 速度系 hook が **OneNavi 自身のナビ(独自 FusedLocation, 別プロセス)に干渉しない**こと(原理上無干渉だが要実証)。
10. (実装メモ)切断/車 off 時の CarActivity teardown → ロック解除 → スマホ継続(D27)。
11. Phenotype 上書きの実効性(実機は Phenotype 未配信のため直 hook 主軸。§0 実機検証)。

---

## 7. 出典

git 管理外の解析成果物 = `../../APK/GoogleMap/cc_analysis/`(別エージェント)/ `../../APK/GoogleMap/codex_analysis/`(Codex)。

- `cc_analysis/REPORT_google_maps_android_auto.md` — Maps の描画(GhostActivity 透明殻 / DrawingSpec Surface)。
- `codex_analysis/reports/01_google_maps_android_auto_rendering_findings.md` — Maps 描画(旧 DrawingSpec→VD→Presentation / 新 InputTransferToken→SCVH)。
- gearhead smali(全て検証済):
  - ゲートA: `ltz.smali:150`(SDK35/module/filter/touch)、`kdh`、`nlc`、`aclg`(`compatible_car_list` default "CAQ"={mode4,空})
  - ゲートB: `irr.smali:8687`、`qkj.smali:236`(NATIVE_APP)
  - ゲートC: `bwn.smali:1294`(`setAllowedActivities`、固定 `CarVirtualDeviceActivity` + `map(allowed_activities_list)`、anchor `"Allowing %s"`)、`acle.f()`→`aclg.smali:285`(`CradleFeature__allowed_activities_list`)、`ijf.smali:4254`(`addActivityPolicyExemption`)
  - ゲートD: `iij.smali:1348`(`setLaunchDisplayId(carDisplayId)`)
  - ゲートE(2026-06-07 特定): 走行 stop 実行元 = `GH.ParkedAppMgr`(`lxn`)の reactive 購読 `lxl.eJ()`。`lxn.fz()` で `swv.e(kcx.a(), mjx.b().h, ldv.b().a(), lxm.a)` に `lxl` を購読 → `isParked=false` で isParkedOnly(`mkw.c`)アプリを `nln.b().a().i()`(dashboard)/ `mjy.a().k(...)`(代替 Activity)で押し出す。`lxk`=`ParkedAppState(carToken, appsByRegion, isParked)`、`ldv` debounce = `Duration.ofMillis(500)`。アンカー `"GH.ParkedAppMgr"` / `"Stopping %s. Showing dashboard instead"` / `"Stopping %s. Starting %s instead"`。`qhi.smali:594` は stopped 後 cleanup(`qpy.smali:811` 起点)= 結果観測点で確定。`iet.smali:266`(評価)/`qno.smali`(CAR.SENSOR)は別系統 UXR。
  - スマホ系(別物): `lzt.smali`+`lpf.smali:73`(`setLaunchDisplayId(0)`)、`ldx/ldw/lek`(速度状態)
- 主要文字列: `"No driving status, always restricted"` / `"removing active app for the region"` / `"Abort phone activity launch (user is driving)"`(スマホ系) / `NATIVE_APP` / `com.google.android.gms.car.application`。
- OSS 裏取り: AAMirror / Screen2Auto / aa-proxy-rs / AA-Phenotype-Patcher 等(`docs/logs/10`)。
