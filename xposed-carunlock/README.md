# xposed-carunlock

> ⚠️ **個人利用・自己責任**。本モジュールは Android Auto の**走行安全ガード(注意散漫防止)に関わる挙動を改変**する内容を含む。
> 他者への配布・推奨はしない。公開 OSS に同梱しているのは作者の個人検証目的(`docs/spec/32` の D23/D24 参照)。

OneNavi を **Android Auto の parked-app(NATIVE_APP)として車 display に出す**ための Xposed/Vector モジュール。
設計の正本は [`docs/spec/32_android_auto_parked_app_rendering.md`](../docs/spec/32_android_auto_parked_app_rendering.md)。

## 何をするか

Android Auto 本体(`com.google.android.projection.gearhead`)に hook を当て、5 ゲートのうち A/C/E を開ける:

- **Gate A**: `ltz`(GH.ParkedNativeAppCheck)の boolean 判定(`a()`/`b()`)を `true` 固定 → parked-app 機能を有効化。
- **Gate C**: framework API `VirtualDeviceParams.Builder.setAllowedActivities(Set)` を hook し、OneNavi の `CarActivity` component を許可集合に注入 → VDM の activity policy(デフォルト block)で起動が弾かれないようにする。
- **Gate E(走行維持)= 2経路**:
  - **E-1**: `GH.ParkedAppMgr`(`lxl`)の reactive observer `lxl.eJ()` を no-op 化 → 走行開始(`isParked=false`)時の**表示中** parked-app 蹴り出し(dashboard / 代替 Activity への置換)を止める。
  - **E-2**: `GH.IntentInterceptor`(`mjo`)の "ParkedApp" 実装 `lxi.b(Intent)` を OneNavi 限定 false → 走行中の**起動/継続ブロック**(+「運転中は使用できません」トースト)を回避。E-1 だけでは継続 intent が `lxi` で再ブロックされ閉じるため**両方必須**。
- **Gate B** は hook 不要(AA 開発者設定「不明なソース」で代替)。

> ⚠️ Gate E は走行安全ガード(注意散漫防止)を意図的に無効化する。**個人利用・自己責任のみ**。Gate E 入口の trace 詳細は `docs/spec/32` の「🎯 Gate E 入口特定」/「第2経路 `lxi`」/ D34・D35 参照。実車走行での維持実証(STEP2)は未完。

## 前提

- root 化端末(Magisk または KernelSU)。検証機: Xperia XQ-FS44 + Magisk。
- **Vector**(JingMatrix/Vector)── Zygisk ベースの ART hooking フレームワーク。LSPosed 相当で、**legacy Xposed API 互換**。本モジュールは `de.robv.android.xposed:api:82`(`api.xposed.info`、compileOnly)でビルドし Vector 上で動かす。
- gearhead **16.8.661854**(解析版)。難読化名 `ltz` / `lxl` 等はこの版前提。版が変われば再特定が必要(`docs/spec/32` §6)。

## Vector の導入手順

1. Magisk(または KernelSU)を導入し、**Zygisk を有効化**して再起動。
2. [JingMatrix/Vector](https://github.com/JingMatrix/Vector) のリリースから Vector 本体(Magisk module zip)を入手し、Magisk の「モジュール」から flash → 再起動。
3. Vector のマネージャ(LSPosed 系 UI)が起動することを確認。

## モジュールの導入

1. ビルド: `./gradlew :xposed-carunlock:assembleDebug`
   - 出力: `xposed-carunlock/build/outputs/apk/debug/xposed-carunlock-debug.apk`
2. 端末にインストール: `adb install -r <上記APK>`
3. Vector マネージャで **`OneNavi CarUnlock` を有効化**。scope は manifest の `xposedscope`(`com.google.android.projection.gearhead`)で自動指定済。手動でも gearhead に scope を付ける。
4. **gearhead を必ず強制停止する**(`adb shell am force-stop com.google.android.projection.gearhead`)。

> ⚠️ **重要**: LSPosed/Vector の hook は「次に起動する gearhead プロセス」にしか当たらない。モジュールを
> **入れ直す/更新するたびに gearhead を force-stop が必須**。これを忘れると旧プロセスに hook が乗らず、
> 「有効化したのに走行中に閉じる」状態になる(実際に踏んだ罠)。AA は USB 再接続で gearhead が起動する。

## OneNavi 側の準備

1. `./gradlew :composeApp:installDebug`
2. Android Auto の開発者設定 → **「不明なソース」を ON**(Gate B)。
3. gearhead の **Play 自動更新を OFF**(版固定。`docs/spec/32` D11)。

## 検証(停車中 = STEP1a)

> ⚠️ **エンジン ON・P レンジ・パーキングブレーキ(停車)でのみ**。走行中の検証は対象外。

```bash
adb -s <serial> logcat -c
adb -s <serial> logcat | grep -iE "OneNaviCarUnlock|OneNaviCar|iij|VirtualDevice|SecurityException|not allowed"
```

1. USB で車 HU に接続、AA 起動。
2. logcat の `OneNaviCarUnlock` で `hooked ltz... -> true` / `hooked setAllowedActivities` / `hooked lxl.eJ... -> no-op` / `hooked lxi.b(Intent)` / `injected OneNavi...` を確認。
3. AA のアプリ一覧に **OneNavi** が出るか(出なければ Gate A / opt-in 宣言を疑う)。
4. OneNavi をタップ → 車画面に **CarActivity 診断画面**が出るか。
   - 出ない + `SecurityException`/`not allowed on virtual device` → Gate C(注入)を疑う。
   - `iij ... with launchDisplayId: N` が出るか、`adb shell dumpsys activity displays` で確認。
5. 車画面でタップ/ドラッグ → 箱が追従 / `OneNaviCar` ログに座標が出る = **本物 MotionEvent 到達**。

### 走行維持(Gate E = STEP2)

> ⚠️ **公道での走行検証は同乗者が操作するか、安全な私有地/クローズドコースで。運転者は操作しない。**

1. STEP1a で OneNavi を車画面に出した状態から、D レンジに入れて発進。
2. hook 無効時: 発進直後に「運転中は使用できません」トーストで OneNavi が閉じる。
3. hook 有効時: `OneNaviCarUnlock` に `SkipDrivingEviction: eJ -> no-op`(E-1)と `ParkedLaunchAllow: ... -> not blocked while driving`(E-2)が出て、走行中も OneNavi が車画面に維持されれば成功。
   - **DHU の "restrict all" で実証済**(2026-06-07): force-stop 後に再接続すると E-1/E-2 が発火し、トーストも teardown も発生せず維持された。
   - **まだ閉じる場合**はほぼ「force-stop 忘れ(hook が旧プロセス)」。`adb shell am force-stop com.google.android.projection.gearhead` してから再接続。それでも閉じるなら `SkipDrivingEviction`/`ParkedLaunchAllow` がログに出ているか確認(出ていなければ `lxl`/`lxi` の難読化名が版違い → 再特定)。
4. もし「落ちないが操作不可 / 画面暗転」が残る場合は UXR 系(`iet`/`ieu`)の別制限 → `docs/spec/32` §3.6 の未 trace 項目。

## ログタグ

| タグ | 意味 |
|---|---|
| `OneNaviCarUnlock` | gearhead 内 hook の成否(Gate A/C/E) |
| `OneNaviCar` | CarActivity の `displayId` / タップ座標 |
