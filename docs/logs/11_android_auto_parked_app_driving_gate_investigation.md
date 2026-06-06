# Android Auto「parked app（Car Ready Mobile Apps）」経路と走行ゲートの実物解析

調査日: 2026-06-06。解析対象: `com.google.android.projection.gearhead` 16.8.661854（Pixel 10 実機 pull）。
手法: `apktool d`（full smali）で走行状態判定と parked-app 起動経路を読解。
関連: `docs/logs/8`（生 Surface 描画）/ `docs/logs/10`（projection gating）/ `cc_analysis/REPORT_*`。

---

## 背景（この調査の問い）

Android 15+ の Android Auto は「parked app（Car Ready Mobile Apps）」として、テンプレートでも
ナビ用 Surface でもない**普通のスマホ Activity を車のディスプレイ上で直接動かせる**。
ただし公式ポリシー上は **ゲーム / 動画 / ブラウザ等に限定 + 駐車中のみ**。

問い: **これを OneNavi の抜け道に使えないか。具体的には「走行中も」動かせないか。**

結論先出し: **使える。しかも PROJECTION/GhostActivity（first-party 署名必須）と違い第三者が触れる本命経路。**
壁は2つだけ ──「① そもそも car display に出せるか（カテゴリ＋`allow_unknown_sources`）」と
「② 走行中も動かせるか（走行ゲート）」。**②は gear/speed 由来の enum 1個の比較**で、
**MITM ドングルで gear/speed を詐称すれば実車でも突破できる**（root 不要、これが「実車運用」要件に最も合う）。

---

## 1. parked-app は「本物の Activity を car display に launch」する経路

- 起動本体: `Llzt;`（`GH.PhoneActivityLaunchr`、`smali/lzt.smali`）。メソッド `c(Context, Intent, ...)`。
- 中身は `Context.startActivity(Intent, Bundle)` で **普通の Activity を起動**（`lpf.c()` の launch Bundle で表示先 display を指定）。
- caller は `smali_classes2/mhd.smali`（合成 lambda、car-to-phone 起動トリガ）。
- これは **VirtualDisplay+Presentation でも生 Surface でもない**。本物の `Window` + 本物の `MotionEvent` を持つ
  通常 Activity がそのまま car display で動く ＝ お兄ちゃんが最初に欲しがってた「リッチ＋滑らか＋普通のタッチ」そのもの。
- 前回判明の **PROJECTION/GhostActivity（`docs/logs/10`）は first-party 署名必須で第三者不可**だったが、
  この parked-app 経路は **NATIVE_APP カテゴリ（`qkj.j`）= `allow_unknown_sources` の許可集合 `m` に含まれる**（`REPORT_escape_hatches.md` #40 で確認済）。
  → 署名なし第三者でも car display に Activity を出せる土俵。

---

## 2. 走行ゲートの実物（`lzt.c` 内、smali 確定）

`lzt.smali` 冒頭（line 25-36）:

```smali
invoke-static {}, Lkwr;->c()Llds;        # 走行状態モニタ取得
invoke-interface {v0}, Llds;->c()Lldp;   # 現在の走行ステータス enum
move-result-object v1
sget-object v2, Lldp;->a:Lldp;           # Lldp.a = CAR_MOVING
const/4 v3, 0x1
if-eq v1, v2, :cond_1                     # ステータス==CAR_MOVING なら abort 分岐へ
... :cond_1 ...
const-string v0, "Abort phone activity launch (user is driving)"   # line 255
```

`Lldp` enum（`smali/ldp.smali` で確定）:

| 値 | 意味 | abort するか |
|---|---|---|
| `Lldp.a` | **CAR_MOVING** | **する**（`if-eq` 一致で `:cond_1`）|
| `Lldp.b` | CAR_PARKED | しない（fall-through → `startActivity`）|
| `Lldp.c` | **UNKNOWN** | **しない**（CAR_MOVING ではないので通る）|

→ **走行ゲートは「走行状態 == CAR_MOVING」のたった1個の enum 比較**。
   CAR_PARKED はもちろん、**UNKNOWN でも起動が通る**（gear/speed 信号が欠落・不定なら parked app は動く）。
   DHU で parked app が常に動くのはこれ（DHU は gear/speed を送らない → UNKNOWN/PARKED）。

---

## 3. 走行状態 CAR_MOVING/PARKED の供給源 = 車の gear + speed

- 算出本体: `Lldl;`（`smali/ldl.smali`）。dump 文字列に `currentGear: ` と `currentParkingState (speed): `。
- すなわち **gear position と speed の2信号**から CAR_MOVING/PARKED を決める。
- これらは projection プロトコルで車から届く（dex 文字列 `gearData=` / `speed=` / `, isParked=` / `drivingStatus=` / `CAR_LOCATION_PROVIDER_HAS_SPEED` / `CAR_REPORTED_DRIVER_POSITION_*`）。
- `Llds` interface（`smali/lds.smali`）: `c()Lldp`（状態）/ `b()Location` / `f()Float`（speed）/ リスナ群。
  集約元は車載センサ（CarSensor over projection）。

→ **走行判定は端末が独自に持つ情報ではなく、車（HU）が送ってくる gear/speed に従属**している。
   ここが MITM で詐称できる根拠。

---

## 4. 走行ゲートを突破する手段（実車前提・評価順）

| 手段 | 何をする | 何が起きる | root | 実車 |
|---|---|---|---|---|
| **(a) MITM ドングルで gear/speed 詐称** | aa-proxy-rs / OpenAuto 系で projection ストリームの gear=park・speed=0 を常時書換 | `ldl` が常に **CAR_PARKED** を算出 → `lzt` の `if-eq CAR_MOVING` が成立せず abort されない → **走行中も parked app が起動** | **端末は不要**（ドングル側で完結）| ◎ 最適 |
| (b) `lds.c()`/`kwr.c()` を Frida/LSPosed で hook | gearhead プロセス内で常に CAR_PARKED を返す | 同上 | 端末 root 必須 | ◎ |
| (c) `lzt` smali パッチ + 再署名 | `if-eq ... :cond_1` を除去 | 走行判定を物理的に無効化 | root or gearhead 差し替え | ◎ |

- **(a) が「実車で運用」要件に最も合う**。DHU 止まりにならず、端末を root せずに済む（前回の carInfo 詐称 = `H()` 突破と同じ MITM 系の延長で、ついでに gear/speed も書き換えるだけ）。
- 加えて **carInfo を "Desktop Head Unit" に詐称（`H()` 成立, `docs/logs/10`）すれば `allow_unknown_sources` 相当の dev 緩和も同時に効く** → ①の土俵入りと②の走行ゲートを **1個の MITM ドングルで両方**解決できる。

---

## 5. OneNavi への含意（重要）

1. **これは OneNavi の元々の悩み（VirtualDisplay+Presentation のラグ・合成タッチ）を構造的に解消する**。
   parked-app 経路なら car display で動くのは**普通の OneNavi Activity**。`OneNaviApp()` をそのまま car display で
   描画でき、本物のタッチが届く。Presentation ハックも合成 MotionEvent も不要。
2. **①の土俵入り**: NATIVE_APP カテゴリ宣言 + `allow_unknown_sources`（AA 開発者設定 / DHU/carInfo 詐称）。
   個人 sideload なら Play 側 allowlist は迂回可。
3. **②の走行ゲート**: gear/speed 由来 enum 1個。MITM で gear=park/speed=0 を流せば実車・走行中でも通る。
4. **安全上の明確な注記**: ②の突破は「運転者の注意散漫を防ぐ安全ガードそのものを無効化する」行為。
   本件はあくまで**自己責任の個人利用**前提（ユーザ明言）。公開リリースや他者配布には乗せない。

---

## 6. 一次ソース（再現用）

- `cc_analysis/gearhead/apktool_full/smali/lzt.smali` — `GH.PhoneActivityLaunchr`。走行ゲート（line 25-36 判定、line 255 abort 文字列）。
- `cc_analysis/gearhead/apktool_full/smali/ldp.smali` — `CAR_MOVING(a)/CAR_PARKED(b)/UNKNOWN(c)` enum 定義。
- `cc_analysis/gearhead/apktool_full/smali/lds.smali` — 走行状態モニタ interface（`c()Lldp` / `f()Float` speed）。
- `cc_analysis/gearhead/apktool_full/smali/ldl.smali` — gear+speed から状態算出（`currentGear: ` / `currentParkingState (speed): `）。
- `cc_analysis/gearhead/apktool_full/smali_classes2/mhd.smali` — `lzt.c()` 呼び出し元。
- dex 文字列: `Abort phone activity launch (user is driving)` / `gearData=` / `speed=` / `isParked=` / `drivingStatus=` / `CAR_MOVING` / `CAR_PARKED`。
