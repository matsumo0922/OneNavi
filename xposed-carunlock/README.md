# xposed-carunlock

> ⚠️ **個人利用・自己責任**。本モジュールは Android Auto の**走行安全ガード(注意散漫防止)に関わる挙動を改変**する内容を含む。
> 他者への配布・推奨はしない。公開 OSS に同梱しているのは作者の個人検証目的(`docs/spec/32` の D23/D24 参照)。

OneNavi を **Android Auto の parked-app(NATIVE_APP)として車 display に出す**ための Xposed/Vector モジュール。
設計の正本は [`docs/spec/32_android_auto_parked_app_rendering.md`](../docs/spec/32_android_auto_parked_app_rendering.md)。

## 何をするか

Android Auto 本体(`com.google.android.projection.gearhead`)に hook を当て、5 ゲートのうち A/C/E を開ける:

- **Gate A**: `ltz`(GH.ParkedNativeAppCheck)の boolean 判定(`a()`/`b()`)を `true` 固定 → parked-app 機能を有効化。
- **Gate C**: framework API `VirtualDeviceParams.Builder.setAllowedActivities(Set)` を hook し、OneNavi の `CarActivity` component を許可集合に注入 → VDM の activity policy(デフォルト block)で起動が弾かれないようにする。
- **Gate E(走行維持)**: `GH.ParkedAppMgr`(`lxl`)の reactive observer `lxl.eJ()` を no-op 化 → 走行開始(`isParked=false`)時の parked-app 蹴り出し(dashboard / 代替 Activity への置換)を止め、走行中も OneNavi を車画面に維持する。
- **Gate B** は hook 不要(AA 開発者設定「不明なソース」で代替)。

> ⚠️ Gate E は走行安全ガード(注意散漫防止)を意図的に無効化する。**個人利用・自己責任のみ**。Gate E 入口の trace 詳細は `docs/spec/32` の「🎯 Gate E 入口特定」/ D34 参照。実車走行での維持実証(STEP2)は未完。

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
4. gearhead を強制停止(または再起動)して hook を反映。

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
2. logcat の `OneNaviCarUnlock` で `hooked ltz... -> true` / `hooked setAllowedActivities` / `hooked lxl.eJ... -> no-op` / `injected OneNavi...` を確認。
3. AA のアプリ一覧に **OneNavi** が出るか(出なければ Gate A / opt-in 宣言を疑う)。
4. OneNavi をタップ → 車画面に **CarActivity 診断画面**が出るか。
   - 出ない + `SecurityException`/`not allowed on virtual device` → Gate C(注入)を疑う。
   - `iij ... with launchDisplayId: N` が出るか、`adb shell dumpsys activity displays` で確認。
5. 車画面でタップ/ドラッグ → 箱が追従 / `OneNaviCar` ログに座標が出る = **本物 MotionEvent 到達**。

### 走行維持(Gate E = STEP2)

> ⚠️ **公道での走行検証は同乗者が操作するか、安全な私有地/クローズドコースで。運転者は操作しない。**

1. STEP1a で OneNavi を車画面に出した状態から、D レンジに入れて発進。
2. `lxl.eJ` 未 hook 時: 数百 ms 後に OneNavi が dashboard / 別 Activity に置換され撤去される(`GH.ParkedAppMgr` ログに `Stopping ... Showing dashboard instead` / `Starting ... instead`)。
3. hook 有効時: `OneNaviCarUnlock` に `SkipDrivingEviction: eJ -> no-op` が出て、走行中も OneNavi が車画面に維持されれば成功。
4. もし「落ちないが操作不可 / 画面暗転」が残る場合は UXR 系(`iet`/`ieu`)の別制限 → `docs/spec/32` §3.6 の未 trace 項目。

## ログタグ

| タグ | 意味 |
|---|---|
| `OneNaviCarUnlock` | gearhead 内 hook の成否(Gate A/C/E) |
| `OneNaviCar` | CarActivity の `displayId` / タップ座標 |
