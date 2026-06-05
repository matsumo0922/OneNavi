# 9. Android Auto Surface 抜け道調査と Maps SDK 直接描画可否

## 目的

Android Auto の host から受け取る app-owned `Surface` に対して、現行の
`VirtualDisplay + Presentation` 以外の経路で Google Maps SDK / Navigation SDK の
`MapView` / `NavigationViewForAuto`、または Android の `View` / Compose 階層を載せられるかを
確認する。

個人利用前提のため、公開 API だけでなく hidden API、reflection、DHU、ローカル SDK、
端末から取得した Google Maps APK/APKS も調査入口に含めた。

> **作成日:** 2026-06-06
> **ステータス:** 調査ログ / 設計判断用
> **対象:** Android Auto app-owned `Surface`、Google Maps SDK、Google Navigation SDK、
> AndroidX Car App Library、DHU、Google Maps APK/APKS

---

## 結論

**Google Maps SDK / Navigation SDK の View 系 API を使う限り、
`VirtualDisplay + Presentation` 以外の現実的な抜け道は見つからなかった。**

理由は単純で、Android for Cars App Library が app に渡すのは `Surface`、幅、高さ、dpi だけであり、
`View` 階層を直接 attach するための `Window`、`Display`、`SurfaceControl`、window token は渡されないためである。
`MapView` / `NavigationViewForAuto` は通常の Android `View` として動く設計なので、
車載 host の生 `Surface` へ載せるには、結局 `VirtualDisplay` で `Display` を作り、
その上に `Presentation` の real window を立てる必要がある。

一方、生 `Surface` に自前 renderer で直接描くことは可能である。
ただし、それは Google Maps SDK の `MapView` を使う経路ではなく、
Canvas / EGL / OpenGL / Vulkan / `HardwareRenderer` などで独自に描く経路になる。
Google Maps first-party アプリの滑らかさはこの種の自前地図 renderer に由来すると見るのが自然で、
SDK 利用側が `MapView` を任意の `Surface` へ差し替える公開 API は確認できなかった。

---

## 1. Android Auto の `Surface` モデル

### 1.1 host が渡すもの

Android for Cars App Library では、ナビアプリが `AppManager.setSurfaceCallback()` を登録すると、
host から `SurfaceContainer` が渡される。

ローカルの `androidx.car.app:app:1.7.0` sources / API で確認できた `SurfaceContainer` の実体は以下のみ。

- `Surface`
- `width`
- `height`
- `dpi`

`SurfaceControl`、`SurfaceTexture`、`SurfacePackage`、window token、`ViewRootImpl` に渡せる hidden handle は無い。

つまり app 側は「描画可能な BufferQueue の producer 側」を受け取るだけであり、
通常の `View` hierarchy を host の画面ツリーに接続する能力は渡されない。

### 1.2 template は地図 View を host する API ではない

`NavigationTemplate` / `MapWithContentTemplate` は Android Auto の安全制約つき UI を host に出すための
template であり、template 自体が任意の Android `View` を host するわけではない。

`NavigationTemplate` のリファレンスでも、template 自体は描画 `Surface` を提供せず、
map の描画は `AppManager.setSurfaceCallback()` を使う前提で説明されている。

### 1.3 入力も raw touch ではなく semantic callback

`SurfaceCallback` が受ける入力は以下のような semantic event である。

- `onClick`
- `onScroll`
- `onFling`
- `onScale`

通常の Android `MotionEvent` stream がそのまま app の `ViewRoot` に流れてくるわけではない。
そのため現行構成で Compose overlay を動かす場合も、
host callback を app 側で `MotionEvent` 相当に合成して仮想 display 上の `View` へ渡す必要がある。

---

## 2. Google Maps SDK / Navigation SDK の確認結果

### 2.1 Maps SDK `MapView`

プロジェクト採用版の `play-services-maps:19.2.0` と、
ローカル Gradle cache に存在した `play-services-maps:20.0.0` AAR を確認した。

対象:

- `gradle/libs.versions.toml`: `playServicesMaps = "19.2.0"`
- `~/.gradle/caches/modules-2/files-2.1/com.google.android.gms/play-services-maps/19.2.0/.../play-services-maps-19.2.0.aar`
- `~/.gradle/caches/modules-2/files-2.1/com.google.android.gms/play-services-maps/20.0.0/.../play-services-maps-20.0.0.aar`

`javap -public` で確認した `MapView` の public API は通常の `View` lifecycle と `getMapAsync()` が中心で、
任意の `Surface` / `SurfaceTexture` / `TextureView` を注入する API は無かった。

`GoogleMap` も camera / marker / layer / snapshot などの通常操作 API はあるが、
描画先を app-owned `Surface` に差し替える API は無い。

`GoogleMapOptions.zOrderOnTop(boolean)` は内部で使われる `SurfaceView` の重なり順に関する設定であり、
任意 `Surface` への出力先差し替えではない。

### 2.2 Maps SDK delegate

`IMapViewDelegate` は `getView()`、`getMap()`、lifecycle、`getMapAsync()` を持つが、
ここにも `Surface` を受け取る API は無い。

`IGoogleMapDelegate` も map 操作と snapshot が中心であり、描画 target の外部注入は確認できない。

このため、reflection で public wrapper の奥を掘っても、
少なくとも SDK の stable な Binder boundary には「raw Surface へ map renderer を接続する」口は見当たらない。

### 2.3 Navigation SDK `NavigationViewForAuto`

ローカル Gradle cache の `com.google.android.libraries.navigation:navigation:6.0.0` と `7.6.1` AAR を確認した。

対象:

- `~/.gradle/caches/modules-2/files-2.1/com.google.android.libraries.navigation/navigation/6.0.0/.../navigation-6.0.0.aar`
- `~/.gradle/caches/modules-2/files-2.1/com.google.android.libraries.navigation/navigation/7.6.1/.../navigation-7.6.1.aar`

`NavigationViewForAuto` は `android.widget.FrameLayout` を継承する `View` であり、
constructor は `Context` / `AttributeSet` / `GoogleMapOptions` 系である。
public API に任意 `Surface` を渡す口は無い。

Google Navigation SDK の Android Auto 公式サンプルは、
`SurfaceContainer.getSurface()` から `DisplayManager.createVirtualDisplay(...)` を作り、
`Presentation` 上に `NavigationViewForAuto` を置く構成である。

これは OneNavi の現行構成と本質的に同じであり、
Google 公式の SDK 利用経路でも `VirtualDisplay + Presentation` が正攻法として扱われている。

---

## 3. 抜け道候補の評価

| 候補 | 判定 | 理由 |
|---|---|---|
| `VirtualDisplay + Presentation` | 採用継続 | `View` / `MapView` / `NavigationViewForAuto` を動かすために必要な `Display` と `Window` を作れる唯一の現実解 |
| raw `Surface` + Canvas / EGL / GL / Vulkan | 部分的に可 | 自前 renderer なら可能。ただし Google Maps SDK の `MapView` 再利用ではない |
| `Surface.lockHardwareCanvas()` + `View.draw(Canvas)` | PoC 価値のみ | 単純な custom view なら描ける可能性はあるが、`MapView` / `SurfaceView` / `TextureView` 子要素は期待通りに描けない |
| `HardwareRenderer + RenderNode` | PoC 価値のみ | raw `Surface` へ GPU 描画できるが、`ViewRoot` / lifecycle / input / focus を提供しない。`MapView` host にはならない |
| `SurfaceControlViewHost` | ほぼ不可 | 受け渡し単位は `SurfacePackage` で、消費側に `SurfaceView.setChildSurfacePackage()` が必要。Car App が渡す raw `Surface` だけでは親 `SurfaceControl` が無い |
| `WindowlessWindowManager` | ほぼ不可 | SDK source には存在するが public `android.jar` では露出しない。constructor に root `SurfaceControl` と host input token が必要で、`SurfaceContainer` にはどちらも無い |
| hidden `ViewRootImpl` / `WindowManagerGlobal` | ほぼ不可 | WMS に登録された window token 前提で、生 `Surface` 注入だけでは通常の `View` tree を成立させられない |
| `GoogleMap.snapshot()` 連打 | 不適 | bitmap readback 経路で latency / fps / input 追従が厳しい。ライブナビ renderer にはならない |
| Maps SDK 内部 renderer reflection | 高難度かつ不安定 | obfuscation、GMS / SDK 内部実装、version 差分、署名 / 権限差分に強く依存。stable な `Surface` injection point は未確認 |

結論として、第三者 app が Google Maps SDK の `MapView` を使い続けるなら
`VirtualDisplay + Presentation` を改善する方向が最も現実的である。
本当に raw `Surface` へ直接描くなら、Google Maps SDK ではなく raw surface 対応の地図 renderer を使う設計になる。

---

## 4. DHU の確認結果

ローカル SDK の DHU を確認した。

対象:

- `~/Library/Android/sdk/extras/google/auto/`
- `source.properties`: `Pkg.Desc=Android Auto Desktop Head Unit Emulator`、`Pkg.Revision=2.0`

代表 config:

- `default.ini`: `resolution = 800x480`、`dpi = 160`、`framerate = 30`
- `default_720p.ini`: `resolution = 1280x720`、`dpi = 160`、`framerate = 30`
- `default_1080p.ini`: `resolution = 1920x1080`、`dpi = 160`、`framerate = 30`

DHU は projection の receiver / emulator であり、app 側 Car App API の実装そのものではない。
DHU binary からは projection protocol receiver、navigation status、sensor source などの文字列は見えるが、
`androidx.car.app` の `SurfaceCallback` や `Presentation` を置き換える app-side API は見つからなかった。

したがって DHU の実装から「app が raw `Surface` 以外の低レベル handle を受け取れる」証拠は得られない。
また DHU config が 30fps 固定なので、DHU 上のカクつきや入力遅延には receiver 側の 30fps 天井も混ざる。

---

## 5. Google Maps APK/APKS の取得状況

Pixel 10 Pro XL から Google Maps の APK/APKS とメタデータを退避済み。

退避先:

- `~/dev/APK/GoogleMap/`

取得済みファイル:

- `base.apk`
- `split_config.arm64_v8a.apk`
- `split_config.ja.apk`
- `split_config.xxhdpi.apk`
- `base.dm`
- `com.google.android.apps.maps.Pixel_10_Pro_XL.apks`
- `metadata/`

端末情報:

- model: `Pixel 10 Pro XL`
- device: `mustang`
- Android: `16`
- SDK: `36`
- build fingerprint: `google/mustang/mustang:16/CP1A.260505.005/15081906:user/release-keys`

package 情報:

- package: `com.google.android.apps.maps`
- versionName: `26.22.04.920688788`
- versionCode: `1068597713`
- minSdk: `32`
- targetSdk: `37`
- update: `2026-05-31 01:32:25`

`pm path` で確認できた split:

- `base.apk`
- `split_config.arm64_v8a.apk`
- `split_config.ja.apk`
- `split_config.xxhdpi.apk`

APKS 内容:

- `base.apk`
- `split_config.arm64_v8a.apk`
- `split_config.ja.apk`
- `split_config.xxhdpi.apk`

sha256:

- `base.apk`: `82315018676177170af427c7bb702ec8bf89ed59415f582c6ebdf6de684a7bc8`
- `split_config.arm64_v8a.apk`: `1f8de9e965990d5f660a96a120e77db71663a80d1d461065e6587a2724cc3407`
- `split_config.ja.apk`: `f17bf5ef458728c12e4eb5dc20d3d31e821a4341b6ffd39d89afa4afa143a4a8`
- `split_config.xxhdpi.apk`: `cd31ec3e2f67be86d5a83c63643ee51782c410424b1193fab7247a1e56bd32b6`
- `base.dm`: `30bc7b9f3385f6aa40041c78ebdbf5eac61007f2aeab89f603c4789944e86f5f`
- `com.google.android.apps.maps.Pixel_10_Pro_XL.apks`: `317850075f1089f7585ed339bb66c9ceb102f88047d4437a605e204b62ba26c8`

`dumpsys package` の summary では、Android Auto projection navigation 系の category と
`com.google.android.car.category.NAVIGATION_PROVIDER` が見えている。
また `com.google.android.gms.permission.CAR_SPEED` が granted である。

ただし、これは Google Maps first-party / system app としての projection integration を示す材料であり、
第三者 app が AndroidX Car App Library で raw `Surface` 以外の handle を得られる証拠ではない。

APK 解析で今後確認すべきこと:

- Android Auto / projection 用 entry point の class 名と component 構造
- native library に地図 renderer / projection renderer らしき symbol や文字列が残っているか
- `Surface` / `SurfaceControl` / `VirtualDisplay` / `Presentation` / EGL 周辺の呼び出し痕跡
- Play services / GMS 側に処理が逃げていないか
- hidden API を reflection で呼ぶ痕跡があるか

ただし Maps 本体は obfuscation と native 実装の比率が高い可能性があり、
「どの描画処理をしているか」を APK だけから高精度に復元する難易度は高い。
Java/Kotlin 層の entry point と surface handoff は追える見込みがあるが、
renderer 内部の実描画ロジックは native / GMS / server driven 設定に分散している可能性が高い。

---

## 6. 非公開 API の有無について

現時点で確認できた範囲では、第三者 app が再利用できる形の
「Google Maps SDK の renderer を raw Android Auto `Surface` へ差し替える非公開 API」は見つかっていない。

ただし、Google Maps first-party アプリが Android Auto projection 向けに
private な integration point や privileged permission を使っている可能性は十分ある。
今回の `dumpsys package` でも、一般の AndroidX Car App Library とは異なる projection navigation category が多数見えている。

重要なのは、仮に first-party private API が存在しても、
それは Google Maps アプリと Google/Android Auto stack の内部契約であり、
OneNavi が SDK 経由で `MapView` を raw `Surface` に接続できることを意味しない点である。

したがって設計判断としては以下が妥当。

- Google Maps SDK を使う: `VirtualDisplay + Presentation` を前提に frame pacing / input latency を詰める。
- raw `Surface` 直描画を狙う: Google Maps SDK を諦め、raw surface 対応の地図 renderer を採用または自作する。
- APK 解析を進める: private 実装理解には有用だが、OneNavi にそのまま移植できる可能性は低い。

---

## 7. 次にやる価値がある PoC

### 7.1 現行構成の改善

まずは `VirtualDisplay + Presentation` を維持したまま、DHU 30fps に合わせて buffer stuffing を減らす。

- API 30+: `Surface.setFrameRate(30f, FRAME_RATE_COMPATIBILITY_DEFAULT)`
- API 34+: `VirtualDisplayConfig.Builder.setRequestedRefreshRate(30f)`
- DHU / 実機 head unit で input latency を比較
- map の traffic / buildings / 3D 表示を切り替え、GPU 負荷と latency の相関を見る

### 7.2 raw `Surface` 直接描画の最小 PoC

Google Maps SDK ではなく、単純な renderer で raw `Surface` の挙動を確認する。

- `Surface.lockHardwareCanvas()` で overlay と touch feedback を描く
- EGL で簡単な quad / polyline を描く
- `HardwareRenderer + RenderNode` で static / animated UI を描く

これは「Car App host の `Surface` 自体は十分な速度で描けるのか」を測るための PoC であり、
`MapView` 代替にはならない。

### 7.3 APK 解析

退避済み APKS を対象に、まずは manifest / component / string / native library の軽い棚卸しから始める。

優先順:

1. `AndroidManifest.xml` の component と car / projection 関連 metadata
2. `classes*.dex` の `Surface` / `VirtualDisplay` / `Presentation` / EGL / `SurfaceControl` 参照
3. `lib/arm64-v8a/*.so` の文字列と export symbol
4. ProGuard mapping が無い前提で、entry point 周辺だけ jadx / apktool で読む

---

## 参考 URL

- Android for Cars: Draw maps
  https://developer.android.com/training/cars/apps/library/draw-maps
- Android for Cars: Interact with maps
  https://developer.android.com/training/cars/apps/library/interact-map
- AndroidX `AppManager`
  https://developer.android.com/reference/androidx/car/app/AppManager
- AndroidX `SurfaceCallback`
  https://developer.android.com/reference/kotlin/androidx/car/app/SurfaceCallback
- AndroidX `SurfaceContainer`
  https://developer.android.com/reference/androidx/car/app/SurfaceContainer
- AndroidX `NavigationTemplate`
  https://developer.android.com/reference/androidx/car/app/navigation/model/NavigationTemplate
- AndroidX `MapWithContentTemplate`
  https://developer.android.com/reference/androidx/car/app/navigation/model/MapWithContentTemplate
- Google Maps SDK `MapView`
  https://developers.google.com/android/reference/com/google/android/gms/maps/MapView
- Google Maps SDK `GoogleMap`
  https://developers.google.com/android/reference/com/google/android/gms/maps/GoogleMap
- Google Navigation SDK: Android Auto
  https://developers.google.com/maps/documentation/navigation/android-sdk/android-auto
- Google Navigation SDK `NavigationViewForAuto`
  https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/libraries/navigation/NavigationViewForAuto
- Android `DisplayManager`
  https://developer.android.com/reference/android/hardware/display/DisplayManager
- Android `Presentation`
  https://developer.android.com/reference/android/app/Presentation
- Android `HardwareRenderer`
  https://developer.android.com/reference/android/graphics/HardwareRenderer
- Android `SurfaceControlViewHost`
  https://developer.android.com/reference/android/view/SurfaceControlViewHost
- Android graphics architecture
  https://source.android.com/docs/core/graphics/architecture
- Android graphics architecture: Surface and SurfaceHolder
  https://source.android.com/docs/core/graphics/arch-sh
- Android graphics architecture: EGL and OpenGL ES
  https://source.android.com/docs/core/graphics/arch-egl-opengl

---

## 関連ログ

- `docs/spec/14_android_auto_google_maps_investigation.md`
- `docs/logs/8_android_auto_surface_rendering_investigation.md`
