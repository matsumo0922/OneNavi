# 10. Android Auto projection 経路の排他制御と抜け道調査

## 目的

`docs/logs/8` で「Google Maps は本物の Activity を gms.car projection 経路で投影しており、第三者用 Car App Library（生 Surface + semantic 入力）とは別物」と APK 実物で確認した。本調査はその先 ──「**第三者がその projection 経路（本物の Window + 本物のタッチ）に到達する抜け道は無いのか**」を、Android Auto 本体（gearhead）の実物解析で根本から検証する。

## 調査方針

- gearhead（`com.google.android.projection.gearhead`）を実機から取得し、projection アプリの選定・検証ロジックを decompile して確認する。
- 排他制御の各層（permission / 署名照合 / フィーチャーフラグ / dev 設定）を特定する。
- 確認できた事実、未確定事項を分けて記録する。
- 抜け道の有無は別途継続調査（rooted 端末での動的検証を含む）。

## 解析対象

- Google Maps: `com.google.android.apps.maps` `base.apk`（73.8MB, 10 dex）。
- gearhead: `com.google.android.projection.gearhead` `base.apk`（34MB, 4 dex）。Pixel 10 実機から `adb pull`。version 16.8.661854-release（versionCode 168661854, targetSdk 37）。
- 手法: `apktool d -s`（manifest）、`jadx --single-class`、`d2j-dex2jar` + `strings`/`grep`。
- 成果物（リポジトリ外）: `APK/GoogleMap/cc_analysis/`（`REPORT_google_maps_android_auto.md` / `REPORT_gearhead_projection_gating.md` / `apktool_out/` / `gearhead/` / `jadx/`）。

---

## 確認済みの事実

### projection 経路の構造（Maps 側）

- Maps は `androidx.car.app.CarAppService` を宣言せず、`ACCESS_SURFACE` / `NAVIGATION_TEMPLATES` permission も持たない（第三者用 Car App Library を使っていない）。
- `GmmCarProjectionService`（+ 制限/補助の計5 service）が `com.google.android.gms.car.category.CATEGORY_PROJECTION_NAVIGATION` を intent-filter に持ち、meta-data `GHOST_ACTIVITY` で `GhostActivity` を指す。
- `GhostActivity extends FragmentActivity`（本物の Window）。API 33+ 必須、`displayId==0` なら finish（projection 用セカンダリ Display 専用）。起動 Intent extra `CarActivityServiceComponentName` で carActivityService を host から指定される。
- carActivityService 実体 = `GmmCarProjectionService extends bijb extends Service`（bound Service）。
- gms.car SDK が生の入力/描画を配送: `TouchEventCompleteData` / `KeyEventCompleteData` / `InputFocusChangedEvent` / `DrawingSpec` / `CarWindowManagerLayoutParams` / `CarDisplay`。
- 車載 UI 部品: `com.google.android.apps.auto.sdk.ui.*`（`CarRecyclerView` 等の旧 Vanagon ツールキット）。Maps 固有の描画は難読化で読めず。

### projection の排他制御（gearhead 側）

gearhead は projection アプリ可否を多層で enforce している。

1. **`START_PROJECTED_ACTIVITY` = `signature` permission。** GhostActivity は本 permission で保護され、起動できるのは gearhead と同じ署名のアプリ（＝ gearhead 自身）のみ。第三者用 `androidx.car.app.ACCESS_SURFACE` / `NAVIGATION_TEMPLATES` / `MAP_TEMPLATES` は全て `normal`（誰でも可）。
2. **`GoogleSignatureVerifier` による署名照合。** projection アプリ解決クラス `irr` の検証 `L(...)` に `if (l.contains(pkg) && ((sgf)…).c(pkg))` があり、`sgf.c(pkg)` は `GoogleSignatureVerifier`（`sgb`）へ委譲し `packageInfo.signatures`（取得 flag `134217792`）を Google 既知署名と照合する。失敗時ログ `Package is not a first party package [%s]`。関連: `isFirstParty` / `com.google.android.gms.car.CarFirstPartyManager` / `CAR_FIRST_PARTY_API`。
3. **Phenotype フィーチャーフラグ `ControlSupportedNavApps__allowlisted_nav_packages`。** サーバ配信でサポート対象 nav package の allowlist を制御。署名照合の上に乗る追加制御。
4. **first-party skip list `l`。** `l = new yhr("com.google.android.gms")`（gms 等）。meta-data チェックを免除するパッケージ集合。
5. **dev 抜け道。** `irr.I()` = `getSharedPreferences("carservice",0).getBoolean("allow_unknown_sources", false)`。`irr.H()` = ヘッドユニット判定（`carInfo` が `"Desktop Head Unit"` / `"Emulator"` / `"tangorpro [AAR]"` / `"Seahawk [AAR]"` 等）。dump に `unknownSourcesEnabled=` 出力。

### 署名証明書（参考）

- Maps base.apk: SDK33+ `7ce83c1b…`、SDK24-32 `f0fd6c5b…`（key rotation）。
- gearhead base.apk: `1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295`。
- Maps と gearhead の cert は不一致。ただし permission の向き（gearhead が GhostActivity を起動する側）から、Maps 自身が gearhead 署名を持つ必要は無く矛盾しない。

---

## 現時点の結論

- 実機ヘッドユニットでは projection 経路は **Google 署名照合 + Phenotype allowlist** の二段で締められ、署名鍵を持たない第三者は到達不能。第三者は `androidx.car.app`（生 Surface・semantic 入力）に限定される。
- **DHU / Emulator では `allow_unknown_sources` という dev 設定が存在**する（緩和の余地あり）。ただし利用にはアプリ側が閉じた gms.car projection SDK を実装する必要がある。

## 判定ロジック `isPackageAllowed3p`（`irr.y()`）の smali 読解結果

1213 命令で jadx 不可だったため `apktool d` の smali（`irr.smali` の `y()`, file line 7517〜）で制御フローを読解。**逐次評価で、ゲートを通過しつつ allow 条件のいずれかに当たれば `return 1`**。正確な構造は以下（行番号は `irr.smali`）:

### 先頭の DENY ゲート（false で即拒否）

- null package（7556）/ package disabled（7754）/ block list（7814）/ meta-data uses 未定義（8085）/ package not found。

### NATIVE_APP 経路（PROJECTION とは無関係）

- 7833 の equals → 7861 `return`（allow）、7972 `"Not allowed native app"` は **qkj.j = NATIVE_APP 専用**の native app category 判定。PROJECTION の抜け道ではない。

### HU 互換ゲート `x(...)`（allow ではない・通過条件）

- 8124 `H()` → true なら 8130 で **無条件 ALLOW**（`H()` = `carInfo`/`carUiInfo` が `"Desktop Head Unit"`/`"Emulator"`/`"tangorpro [AAR]"`/`"Seahawk [AAR]"` 等の dev/DHU 判定）。
- 8133〜 `x(...)`（HU compatibility: head-unit-whitelist / car-info / car-ui-info を見る）→ **8148 `if-eqz v0, :cond_38`**。`x()` が **false なら `cond_38`（8937）→ `"Should not run on HU"` で DENY**。`x()` が true でも allow ではなく、後段（署名・installer・Play・allowlist）へ進むだけの **互換ゲート**。

### allow 条件（通過後、いずれかで `return 1`）

- 8166 `sgf.c(pkg)` = `GoogleSignatureVerifier`（**first-party 署名照合**）合致 → 8172 ALLOW。
- 8174〜 の `com.google.android.projection.gearhead` equals は **単独 allow ではなく**、first-party 失敗時のログ分岐（`"Package is not a first party package"`）の一部。
- 8309〜8433 **Play Store インストール + google 署名**（`isPackageInstalledByPlayCheck` / `Finsky.IsValid`）→ 8682 ALLOW。
- 8687 `I()` = `allow_unknown_sources`（SharedPreferences "carservice"）が true **かつ category ∈ `m`** → 8734 ALLOW。
- 8717 いずれも不成立 → DENY（`Package DENIED; failed all other checks`）。

### フラグ駆動の追加 allow 経路（実装上存在・dex 文字列で確認）

`AppValidation__*`（Phenotype フラグ）による経路が実装されている:

- `AppValidation__should_bypass_validation` / `AppValidation__dhu_bypass_validation`（検証スキップ）
- `AppValidation__allowed_package_list`（+ signature hash 照合と見られる allowlist）
- `AppValidation__allowed_3p_installers`（許可 installer 経由）
- `AppValidation__swallow_play_api_exception` / `AppValidation__blocked_packages_by_installer`

これらは **retail 端末の通常 sideload アプリが普通に使える経路ではなく**、サーバ配信フラグ / allowlist で制御される。

### `allow_unknown_sources` で許可される category 集合 `m`

```java
m = {MEDIA, NAVIGATION, NOTIFICATION, OEM, NATIVE_APP, SERVICE, SMS}
// 除外: PROJECTION, TEMPLATE, MESSAGING
```

（qkj enum: OEM / NOTIFICATION / MEDIA / NAVIGATION / SERVICE / **PROJECTION** / SMS / TEMPLATE / MESSAGING / **NATIVE_APP**）

## 確定した抜け道の有無

- **PROJECTION カテゴリ（Google Maps 式 real Activity 投影）の allow 経路**（smali 上）: ① first-party 署名（`sgf.c`）② `H()`（dev/DHU。`x()` ゲート通過が前提）③ Play/Finsky 有効 ④ フラグ駆動経路（`should_bypass_validation` / `allowed_package_list`+hash / `allowed_3p_installers`）。**ただし ③④ は Google 署名・サーバ allowlist 等に紐づき、retail 端末の通常 sideload では開かない。** 実務上、第三者が現実に狙えるのは ②（carInfo/DHU spoof）か、root で ①④ の判定を潰す方法。
- **`allow_unknown_sources` 単独では PROJECTION を解放しない**（`m` から除外）。解放されるのは NATIVE_APP / NAVIGATION 等。NATIVE_APP は「駐車中 native app（parked apps）」枠で、運転中の自由投影ではない。
- **重要な区別**: `y()` を通る ≠ PROJECTION 経路が貰える。real Activity 投影は、gearhead が signature permission 付き `GhostActivity` を projection display へ `setLaunchDisplayId` で起動し、その Activity 内でアプリの UI/renderer を動かす構造（後述）。
- 既存 OSS（AAMirror / Screen2Auto / Fermata 等）が「任意 rich UI + タッチ on AA」を実現しているのは、**真の GhostActivity 投影ではなく、旧 CarActivity（CATEGORY_PROJECTION）の car Surface に MediaProjection の VirtualDisplay をミラー**し、タッチを input injection で転送する方式。

## Maps の描画方式（正確な理解）

Maps は「単なる Surface に全部自前で描いている」のではなく、**gearhead が signature permission（`START_PROJECTED_ACTIVITY`）付きの `GhostActivity`（本物の FragmentActivity）を projection display に `setLaunchDisplayId` で起動し、その Activity 内で Maps の通常 UI/renderer を動かしている**、と見るのが正確。第三者向けの公式経路は `CarAppService` + `NavigationTemplate` + `SurfaceCallback` で、入力も `onClick` / `onScroll` / `onFling` / `onScale` の抽象イベントのみ。

通常アプリ単体での `ActivityOptions#setLaunchDisplayId` 直起動は、private/system/owned display 制限により AA の display へ任意 Activity を投げる経路にはならない（Android 公式 multi-display activity-launch policy）。

## 抜け道の評価（rooted Pixel 10 前提・要点）

- **DHU / Emulator**: `H()` 成立で署名・Play 不要・全カテゴリ（PROJECTION 含む）通る。PoC はこれで十分。
- **carInfo 詐称**（自作レシーバ / aa-proxy-rs MITM / AAWireless dev mode で `model="Desktop Head Unit"`）: 実機 HU でも `H()` 成立。
- **Phenotype 書換**（`app_white_list` / `allowlisted_nav_packages`、AA-Phenotype-Patcher / GMS-Phixit）: ランチャー可視性のみ（署名層は別）。
- **Play インストール詐称**（`pm install -i com.android.vending` / `getInstallerPackageName` フック、AAAD / XLauncher Unlocked）: Play チェックのみ突破。
- **GoogleSignatureVerifier を Frida/LSPosed で hook**: first-party 判定突破 → 真の PROJECTION。難度最高（GmsCore 密結合）。

詳細・OSS 一覧は `APK/GoogleMap/cc_analysis/REPORT_escape_hatches.md`。

## 未確定事項（動的検証待ち）

- DHU 上で第三者の gms.car projection（CarActivity）実装アプリが実際に real Activity 投影されるかの動作確認。
- `I()`（allow_unknown_sources）が `H()`/dev 設定に連動する gearhead バージョン差の有無。
- Frida での GmsCore first-party hook の実効性（anti-tamper の有無）。

## 参考

- 実物解析: `com.google.android.apps.maps` / `com.google.android.projection.gearhead` の各 `base.apk`。成果物は `APK/GoogleMap/cc_analysis/`。
  - `cc_analysis/gearhead/jadx/sources/defpackage/irr.java`（`m` 集合定義, L() 判定）
  - `cc_analysis/gearhead/apktool_full/smali/irr.smali`（`y()` = `isPackageAllowed3p` 制御フロー, 8124〜）
  - `x()` の実体は `irr.smali:5492` の `irr.x(Optional, Optional, String, CarInfo, CarUiInfo)Z`（`CarInfo`/`CarUiInfo` を引数に取る HU 互換ゲート。`iiz.smali` ではない）
  - `cc_analysis/apktool_out/AndroidManifest.xml`（`GhostActivity` 宣言, 1565）
  - aa-proxy-rs `src/mitm.rs`（`developer_mode` で `make`/`model`/`head_unit_make`/`head_unit_model` を DHU 値に書換 → `H()` 成立に対応）
- Android 公式: [Draw maps](https://developer.android.com/training/cars/apps/library/draw-maps)、[multi-display activity launch policy](https://source.android.com/docs/core/display/multi_display/activity-launch)、[Parked apps](https://developer.android.com/training/cars/parked/auto)。
- 関連: `docs/logs/8_android_auto_surface_rendering_investigation.md`、`docs/spec/14_android_auto_google_maps_investigation.md`。
