---
title: 22. route token / Custom Navigator 路線の最終評価
status: 結論確定 (2026-04-30)
related: 20_navigationview_external_route_bridge_investigation.md
---

# 22. route token / Custom Navigator 路線の最終評価

> **作成日:** 2026-04-30
> **対象:** spec/20 で「不可」とした custom Navigator 路線の再検討。
> 「任意ルートに対応する route token を発行できれば、Google の Navigator に任意ルートを案内させられるのではないか」という仮説の検証。
> **結論:** 公式 API・公開 OSS bridge・SDK 7.5.0 decompile の三系統で確認した結果、本路線は **不成立**。
> ただし副産物として、spec/20 の反射注入路線とは別系統の **native JNI seam** が新たに見つかった。
> 本書はその記録と、今後の方針判断の根拠として残す。

---

## 0. TL;DR

- `routeToken` は **Google サーバ発行の opaque な server-signed binary** であり、クライアントは中身を解釈しない。
- Navigator は `setDestinations(waypoints, CustomRoutesOptions)` で token を受け取った場合でも、**waypoints と一緒に gRPC で `mobilemaps-pa.googleapis.com:443` にリクエストを送る**。サーバが token を再展開してクライアントに route proto を返す前提。
- 端末側で token を偽造することは、サーバ側の署名検証を突破する必要があり実質不可能。
- 結果として「**任意ルート (= 外部API が計算した polyline) に対応する token を発行する**」という仮説の前提が、公開実装・decompile の双方で破綻した。
- 公式 API レベルで `Navigator` interface 差し替え機構や任意 polyline を直接注入する API は **存在しない** (spec/20 §3.2 の結論を decompile 側からも追認)。
- ただし decompile 中に、`LocationIntegratorJni.nativeReplaceRoutesFromProto(long handle, long, byte[])` という **native JNI 直叩きの seam** が見つかった。proto schema は未確定だが、spec/20 §4.1 の `vd.f -> te.d -> tn.o` 反射注入よりさらに 1 レイヤー下の入口として将来検証する価値がある。

したがって今後の方針は変わらず spec/20 の **synthetic state を反射で `vd.f` / `bb.e` に注入する路線** を本線とし、`nativeReplaceRoutesFromProto` は **代替案 B** として保留する。

---

## 1. 仮説と検証範囲

### 1.1 検証した仮説

> Custom Navigator 路線は spec/20 §3.2 で「`NavigationView` が concrete class に cast するため不可」と結論したが、
> もし任意ルートに対応する `routeToken` を端末側で発行できるなら、標準 `Navigator` の `setDestinations(..., CustomRoutesOptions)` API
> 経由で外部API のルートをそのまま案内させることができる。

つまり仮説の核は「route token をクライアント側で自由に発行できるか」である。

### 1.2 三系統の調査

並行して次の 3 系統を走らせ、結論を統合した。

1. **公式ドキュメント精査** — Routes API / Navigation SDK の `routeToken` / `CustomRoutesOptions` / 旧 `setDestinations(List, String)` の挙動と廃止履歴を確認した。
2. **`googlemaps/react-native-navigation-sdk` の公開 OSS bridge を読解** — 公開コードで token がどう扱われているかを末端まで追った。
3. **Navigation SDK 7.5.0 AAR の decompile** — `CustomRoutesOptions.Builder.build()` 以降のチェーンを bytecode レベルで追跡した。

---

## 2. 公式ドキュメントから分かった事実

### 2.1 token 発行の制約

- 発行元: `routes.googleapis.com/directions/v2:computeRoutes` (Routes API v2)。
- 必須 FieldMask: `routes.routeToken`。
- `travelMode = DRIVE` または `TWO_WHEELER` のみサポート。
- `routingPreference = TRAFFIC_AWARE` か `TRAFFIC_AWARE_OPTIMAL` のみ。
- via waypoint は禁止。
- token は origin / destination / time / route objectives に紐付き、有効期限あり。
- 「dynamic な交通変化や車両逸脱でリルートが起きうる」と明記され、**Navigator が token のルートに必ずは従わない**。

### 2.2 SDK 側の API 履歴

- 旧 API `Navigator.setDestinations(List<Waypoint>, String routeToken)` は **v5.0.0 (2023-07) で削除済み**。
- 現行 API は `Navigator.setDestinations(List<Waypoint>, CustomRoutesOptions)` のみ。
- `CustomRoutesOptions.Builder` のフィールドは **`setRouteToken(String)` と `setTravelMode(TravelMode)` のみ**。polyline 直接渡し用フィールドは存在しない。
- `Navigator` interface には `setCustomNavigationCalculator` 系の hook は存在しない。

この時点で「token は公式に Routes API 発行物として運用される」ことが明確で、polyline 直接注入の公式 path は無いと確定する。

---

## 3. react-native-navigation-sdk の精査結果

### 3.1 サーバ呼び出しは JS 側でハードコード

`example/src/helpers/routesApi.ts`:

- endpoint: `https://routes.googleapis.com/directions/v2:computeRoutes`
- header: `X-Goog-FieldMask: routes.routeToken`
- 取得した token は `data.routes[0].routeToken` の文字列を JS から bridge 越しに渡すだけ。

つまり SDK 側は token 発行に**一切関与せず**、外部 (Routes API) の応答をそのまま素通しする実装。

### 3.2 bridge 上の token 流路

```
TS  setDestinationsImpl(waypoints, options)
  └─ NavModule.setDestinations(waypoints, routingOptions, displayOptions, routeTokenOptions)
         [RN TurboModule bridge]
Android  NavModule.java#setDestinations
  └─ ObjectTranslationUtil.getCustomRoutesOptionsFromMap(map)
       └─ CustomRoutesOptions.builder().setRouteToken(token).setTravelMode(mode).build()
       └─ navigator.setDestinations(waypoints, customRoutesOptions, [displayOptions])
iOS  NavModule.mm#setDestinations:routeToken:callback:
```

`routingOptions` と `routeTokenOptions` は **JS 側で排他**としてチェック (`throw new Error(...)`)。
専用 API `navigateToRoute()` は存在せず、エントリは `setDestinations` 一本。
**token 使用時も waypoints は必ず一緒に渡している。**

### 3.3 polyline 直接渡し / Custom Navigator の痕跡

**存在しない。**

- `CustomRoutesOptions` の bridge mapping にも `routeToken` と `travelMode` 以外のキー無し。
- Simulator 系 API `simulateLocationsAlongExistingRoute()` は「設定済みルートを simulate するだけ」で外部 polyline を Navigator に食わせる API ではない。
- Navigator implementation を差し替える hook は無い。

参照ファイル (`/tmp/rnnav-sdk` 配下):
- `src/navigation/navigation/types.ts`
- `src/navigation/navigation/useNavigationController.ts` (`setDestinationsImpl`)
- `android/src/main/java/com/google/android/react/navsdk/NavModule.java` (`setDestinations` L590–675)
- `android/src/main/java/com/google/android/react/navsdk/ObjectTranslationUtil.java` (`getCustomRoutesOptionsFromMap` L236–249)
- `ios/react-native-navigation-sdk/NavModule.mm` (`setDestinations:routeToken:callback:` L518–524)
- `example/src/helpers/routesApi.ts`
- `example/src/screens/RouteTokenScreen.tsx`

---

## 4. Navigation SDK 7.5.0 decompile からの確定情報

### 4.1 token は base64url の opaque blob

- `CustomRoutesOptions.Builder.build()` の bytecode で `adn.g.e.j(token)` を呼んでいる。
- `adn.g.e` の static initializer は `c("base64Url()", "ABCDE...XYZabc...xyz0123456789-_", '=')` — **base64url decoder**。
- decode に失敗すると `IllegalArgumentException` が投げられ、上位で `ApiIllegalStateException("The route token passed in the builder is malformed", ...)` に変換される。
- **クライアント側に署名検証もスキーマ検証も存在しない。** 唯一の検証は base64url としてデコードできるか否か。

### 4.2 token は proto sub-message として gRPC payload に詰まる

- decode した byte[] は `aki.x.v(byte[])` で protobuf `ByteString` に変換される。
- `ajl.gj.b` (type `ByteString`) にセットされ、上位 proto `ml.d` に sub-message として埋め込まれる。
- 同時に bit flag `ml.c = 64` が立つ。
- **waypoints (`bk.b.e`) は token あり時も常に serialize される**。token は waypoints を**置き換えない**。

### 4.3 送信先

- gRPC endpoint: `mobilemaps-pa.googleapis.com:443`
- service: `google.internal.mothership.maps.mobilemaps.v1.MobileMapsService`
- 送信は `au.v.d(mt, ...)` (DirectionsFetcherImpl#loadDirectionsInternal) から行われる。

つまり **token あり/なしのどちらでも同じ endpoint に gRPC を投げている**。token は `ml.d` の sub-message + bit flag で表現されているだけで、ローカルに route を復元する path は無い。

### 4.4 token なし vs token あり の wire 差分

| field | token なし | token あり |
|---|---|---|
| `ml.d` (token sub-msg) | `null` | `gj{ b: ByteString(decoded_token) }` |
| `ml.c` (flags) | `0` | `64` |
| `bk.b.e` (waypoints) | 含む | 含む (変わらず) |

`ml.c = 64` が「token あり」のビットフラグというのは bytecode (`bipush 64; putfield`) の定数値からの推定であり、proto schema 外からの観測。

---

## 5. お兄ちゃんの仮説に対する結論

> 「任意ルートに対応する route token を発行できれば、任意ルートを Navigator に案内させられる」

**この仮説は不成立。**

### 5.1 token を端末側で発行することは不可能

- token は base64url の opaque payload であり、サーバ側で署名 + proto encoding されている。
- クライアントには decode するだけの実装しかなく、署名鍵は当然ながら持っていない。
- 偽 token は base64url が valid なら build() を通過するが、サーバ側で `mobilemaps-pa.googleapis.com` が拒否し `NO_ROUTE_FOUND` / `NETWORK_ERROR` 相当を返す。

### 5.2 token を Routes API で「実際に」発行しても外部ルートは反映できない

- Routes API は Google のルート計算結果に対してしか token を発行しない。
- 「外部API が計算した polyline をそのまま token 化する」公式手段は無い。
- 仮に Routes API で waypoints を投げて token を取っても、それは Google 側のルートであって 外部API 提供元のルートではない。

### 5.3 token を渡しても Navigator はサーバ往復する

- token あり時のリクエストにも waypoints が同梱される。
- gRPC で `mobilemaps-pa.googleapis.com` に投げ、サーバから route proto を再取得する。
- リルート時は token 内の objectives を参照してサーバが**再計算**する。
- つまり token は「サーバ側に持たせている route plan の参照キー」であって、polyline 本体を encoding したものではない。

以上から、route token 経由で外部ルートを案内する路線は **完全に閉じた**。

---

## 6. 副産物 — `nativeReplaceRoutesFromProto`

decompile 中に下記の JNI メソッドが見つかった:

```text
LocationIntegratorJni.nativeReplaceRoutesFromProto(long handle, long, byte[])
```

- `handle` は `nativeCreateRouteLocationIntegrator()` で取得する必要がある。
- `byte[]` は何らかの proto バイナリ。
- proto schema は今回の調査範囲では未確定 (`libgmm-jni.so` 内のネイティブコードに踏み込まないと分からない)。

これが意味する可能性:

- spec/20 §4.1 (`vd.f -> te.d -> tn.o` の反射 UI state 注入) よりさらに 1 レイヤー下、**native 側の location integrator に直接 route proto を流し込む** seam が存在する。
- 外部ルートの polyline / step を proto 化して `nativeReplaceRoutesFromProto` に流せれば、Navigator が「自分が計算したルート」として扱う可能性がある。

ただし問題は山積み:

- proto schema 解析には `libgmm-jni.so` のネイティブリバースが必要。
- ABI 安定性が不明 (SDK version で field tag が変わるリスク)。
- handle のライフサイクル管理が public class に出ていない。
- public Java method からは到達経路が無く、JNI 直叩きの reflection になる。

評価:

- **本線 (spec/20 反射路線) を覆すほどの確度はまだ無い。** 反射注入路線が `to.a` / `bb.e` の Java/Kotlin obfuscated レイヤーで完結するのに対し、こちらは native binary レイヤーまで降りる必要がある。
- ただし反射路線が POC で詰まった場合の **バックアップ B** として記録しておく価値はある。
- 着手するなら proto schema を `.proto` レベルで復元できる目処が立った後。

---

## 7. 今後の方針

### 7.1 本線

spec/20 §10 のロードマップを変更しない。

- `ExtApiInternalRouteMapper` で synthetic `az` / `be` を構築
- `NavigationViewReflectionBridge` で `vd.f.i(to.a, prev)` + `bb.e.a(bv.h)` を呼ぶ
- POC は Android process 内で実機検証する

route token 経路の検討で時間を使うことはこれ以上しない。

### 7.2 代替案 B (バックアップ)

`nativeReplaceRoutesFromProto` 経由の native 注入は、本線 POC が成立しなかったときの代替候補として保持する。
ただし着手は次の 2 条件を満たした後:

- 本線が `to.a` / `bb.e` レイヤーで「route line すら出ない」レベルで完全失敗していること
- `libgmm-jni.so` の proto schema が静的解析または symbol 名から特定できる目処が立つこと

### 7.3 公式 token 経路

「外部API のルートをそのまま案内する」目的では使えない。
ただし「Google Routes API で計算したルートを Navigator に案内させる」用途には使える (= デバッグ/比較目的)。
本プロジェクトでは現時点でこの用途は無い。

---

## 8. 決定ログ

- **D-2201 (2026-04-30):** route token 路線は不成立と確定。token は server-signed opaque blob で偽造不可、token あり時も Navigator はサーバ gRPC を実行して route proto を再取得する。
- **D-2202 (2026-04-30):** Custom Navigator 差し替え路線は spec/20 §3.2 を decompile 側からも追認 — 公式に hook なし、`Navigator` は concrete class cast 前提。
- **D-2203 (2026-04-30):** 本線は spec/20 の synthetic state 反射注入のままとする。route token 関連の追加調査・実装は行わない。
- **D-2204 (2026-04-30):** `LocationIntegratorJni.nativeReplaceRoutesFromProto` を **代替案 B** として記録。本線 POC 失敗時のバックアップとし、現時点では着手しない。

---

## 11. 実機 curl 検証 (2026-04-30)

10.x 系の机上判断を確定させるため、Google API Key (テスト用 unrestricted) で実際に Routes API を叩いて検証した。

### 11.1 Routes API v2 の hard cap 確認

- 26 intermediates 投げると `400 INVALID_ARGUMENT: "Too many intermediate waypoints in the request (26). The maximum allowed intermediate waypoints for this request is 25."`
- **25 hard cap はサーバ側で確定**。

### 11.2 Routes Preferred API のアクセス試行

- `routespreferred.googleapis.com/v1:computeCustomRoutes` を叩くと `403 PERMISSION_DENIED` で `"Routes Preferred API has not been used in project ... before or it is disabled. Enable it by visiting [console URL]"`。
- 公式 doc の "select customers only" は誇張で、**console から self-service enable を試みれる**ことが分かった (実際に enable できるかは未検証)。
- 個人開発者でも有効化できる可能性があり、企業契約 hard lock ではない。

### 11.3 案内点フィルタリング案 (お兄ちゃん発案 + Phase A データ検証)

外部 API の guidance points には音声 only の情報案内 (合流地点 / レーン減少 / 交差点接近など) が混ざっており、これを waypoints として渡す必要はない。サンプル DSR データ (ext-api/analysis/sample/) で実測した結果:

| ルート | 全 GP | 物理マニューバ (template 100/104/105/314) | フィルタ後 chunk 数 (25 ずつ) |
|---|---|---|---|
| shakuji-tsukuba (74km) | 146 | **24 (16.4%)** | **1 chunk** |
| tokyo-gotemba (100km) | 116 | **25 (21.6%)** | **1 chunk** |
| tokyo-nagoya-hiroshima (824km) | 770 | **77 (10%)** | **3 chunks** |
| hiroshima-ferry-beppu (370km) | 619 | **48 (7.8%)** | **2 chunks** |

判定方法: `GuideBlock.range.field_3` を `dist_from_destination` として GP に紐付け、各 GP の announcement.priority (= template_id) を集計。template 100 (交差点案内) / 104 (交差点案内まもなく) / 105 (速度に応じたガイダンス) / 314 (自動車専用道路入口案内) のいずれかを持つ GP を物理マニューバと分類。

**含意**: 全 GP を waypoints とする素朴な発想 (146/26 = 6 chunks) ではなく、**物理マニューバのみフィルタすれば最長 824km ルートでも 3 chunks 以内に収まる**。chunk 数の問題は実用範囲。

### 11.4 形状追従精度の実測 (致命的な発見)

shakuji-tsukuba (74km, 24 物理マニューバ) で実 routeToken を発行し、Google が返した polyline と外部 API の polyline を比較した結果:

| 指標 | 値 |
|---|---|
| Google 計算距離 | **110.5 km** |
| 外部 API 実距離 | 74.4 km |
| **距離比 (Google / External)** | **1.486x** (49% 長い) |
| Google→Ext 偏差 mean | 557m |
| Google→Ext 偏差 max | 4224m |
| Ext→Google 偏差 mean | 320m |
| Ext→Google 偏差 max | 680m |

tokyo-gotemba (100km mostly highway, 25 物理マニューバ) でも同様:

| 指標 | 値 |
|---|---|
| Google 計算距離 | 147.4 km |
| 外部 GP 連結距離 (proxy) | 97.9 km |
| **距離比** | **1.505x** |
| Google→Ext mean / max | 933m / **6973m** |
| Ext→Google mean / max | 458m / 5944m |

両ルートとも **約 50% の距離膨張** が再現性高く発生。これは waypoint フィルタの精度を上げても改善しない構造的問題と判断する。

### 11.5 形状膨張の根本原因 — stopover-only の構造的限界

routeToken は via=true waypoint と非互換 (公式制約) のため、すべての waypoint を stopover として渡す必要がある。

stopover の意味は「物理的に停止する地点」であり、Google Routes は各 stopover で**立ち寄り処理**を行う:
- 料金所手前で本線から脇に寄る
- JCT で出口に出てから再進入する代替経路を探す
- 一方通行や中央分離帯のため U ターンを挿入する

ところが 外部API 提供元の guidance points に含まれる物理マニューバには次のような **本線通過 / pass-through ノード** が混ざる:
- **JCT** (本線を曲げずにそのまま別高速に乗り換えるだけ、停止しない)
- **料金所** (ETC 通過なら本線を維持、停止扱いしない)
- **IC 分岐**(本線から分岐するだけで停止しない)

stopover 制約下では、これら pass-through 点でも Google は「立ち寄り」処理を強制し、**結果として大きな detour を挟んでしまう**。これが 1.5 倍距離膨張の根本原因。

### 11.6 結論 — お兄ちゃんの 25+25 分割路線の最終判断

| 評価軸 | 結果 |
|---|---|
| chunk 数の現実性 | ✅ 物理マニューバフィルタで最長ルートでも 3 chunks 以内 |
| Routes API 25 hard cap | ✅ サーバ側 reject、回避不可 |
| **routeToken 発行 (chunk ごと)** | ✅ 実証済み (chunk 1 で routeToken 2742 chars 発行成功) |
| **Google 計算ルートが 外部API 提供元ルートを忠実再現できるか** | ❌ 距離 50% 膨張、max 6.9km の経路逸脱 |
| 反射 audio/UI 制御 (`setAudioGuidance(SILENT)` 等) | ✅ 公式 API で設定可能 |
| ReroutingListener 奪取 | ✅ 公式に存在 (`addReroutingListener`) |

つまり「**25+25 分割アーキテクチャは技術的には組めるが、Google が返すルートが外部ルートと 50% 違う**」ため、route line / camera が外部 API のルートとずれた状態で表示される。これは本路線の致命的欠点。

例: 外部 API は関越→外環→常磐の最短経路を計算しているが、Google routeToken の polyline は 美女木ＪＣＴ で「立ち寄り detour」を入れるため、地図上に違う線が描かれる。ユーザは「この道のはずなのに案内表示が違う」状態になる。

これは spec/20 の反射注入路線が回避できる問題 (= 自前で 外部API 提供元の polyline をそのまま `bb.e.a(bv.h)` に流せる) のため、**形状忠実性の観点では反射注入路線が依然優位**。

### 11.7 結論の更新

- **D-2209 (2026-04-30):** routeToken + chunk 分割アーキテクチャは、(a) 物理マニューバフィルタによる waypoint 削減で chunk 数は実用範囲、(b) 公式 API での UI/audio 抑制と reroute 奪取は可能、にもかかわらず (c) Google の stopover 制約により実ルートと **50% の距離膨張** が発生し、地図描画レイヤーの主目的 (= 外部API 提供元ルートの忠実描画) を達成できない。本路線は採用不可と確定する。
- **D-2210 (2026-04-30):** Routes Preferred API は self-service enable 可能性あり (要 console 確認)。ただし Routes Preferred を使っても stopover 制約は同じため、98 waypoint で密度を上げても 50% 膨張問題は解決しない見込み。Routes Preferred 投資は不要。
- **D-2211 (2026-04-30):** spec/20 の反射注入路線 (`vd.f.i(to.a)` + `bb.e.a(bv.h)`) は、外部 polyline をそのまま Google 内部に流せる唯一の手段として優位を維持。本線継続。

---

## 10. 補遺: 「25 waypoint 制限を超えて外部ルートを近似する」案の再評価

### 10.1 仮説の再定式化

> Routes API は 25 waypoint 制限がボトルネックで外部ルートを近似できない、と当初は判断した。
> しかし「25 を超える方法がある」というブログ記述があり、これを再評価する。
> もし 25 を超えて waypoint を渡せれば、外部API 提供元の polyline を細かく辿る Google ルートを計算させ、
> その route token で Navigator を駆動できるのではないか?

この仮説は **再評価の結果も不成立**。複数の独立した壁で閉じている。

### 10.2 公式上限の整理

- 現行 **Routes API v2** (`routes.googleapis.com/v2:computeRoutes`) の `intermediates` 配列上限は **25** で 2026-04 時点も維持。
- 一方 **Routes Preferred API** (`routespreferred.googleapis.com/v1:computeCustomRoutes`) は今も active で、**98 waypoint (lat/lng のみ)** または **25 waypoint (place ID 含む)** をサポート。
  - 25 超え時の追加制約: 累積直線距離 < 1000 km、`travel_mode = DRIVE / TWO_WHEELER`、**全て stopover 必須**。
  - 公式記述: "available only to select customers" — 個別契約 (旧プレミアム / 企業向け枠) が必要で、通常の Google Maps Platform セルフサービス契約からは叩けない。
- **重要**: Routes Preferred の `computeCustomRoutes` は **routeToken を返す**。公式記述: 「Responses from ComputeCustomRoutes include route distance, duration, and a route token for navigation. You can pass the route token to the Navigation SDK using the Navigator.setDestinations method, specifying the same destination waypoints that you used when creating the route token.」
- つまり「98 waypoint で形状近似 → routeToken 取得 → Navigation SDK で案内」という路線は **技術的には成立する**。
- ただし採用にあたっては (a) Routes Preferred の契約要件、(b) stopover-only による arrival announcement 連発、(c) Google 道路ネットワーク非依存ルートの近似限界、(d) リルート時の objectives 再計算による乖離、という複数の壁が残る (10.4〜10.7 で詳述)。

### 10.3 ブログ記事 (afi.io / dev.to) の手法は「単一 routeToken」を生まない

検証した 2 記事の手法:

- **afi.io: GMPRO TSP solver** — Google が出した別 API (Route Optimization)。TSP / 巡回路最適化向けで、turn-by-turn 案内用 token 発行とは目的が違う。25 制限の「回避策」ではなく「別商品の紹介」。
- **dev.to: 25 waypoint Limit を超える** — 10 〜 25 waypoint 単位で複数 `computeRoutes` を投げて返ってきた polyline を**地図描画上で連結**するだけの方式。各 leg はそれぞれ独立した route response で、**それぞれ別の routeToken** が返る。これを `Navigator.setDestinations` に渡しても 1 つの token しか受け付けないため、**単一 navigation セッションには統合できない**。

つまりブログ記事の手法は「描画用の polyline 連結」止まりで、Navigation SDK との接続は元々想定されていない。

### 10.4 route token 側の追加制約

公式 `computeRoutes` REST reference に明記された制約:

> **Route.route_token is not supported for requests that have Via waypoints.**

加えて 4.2 で確認済みの token 発行条件:

- `travelMode = DRIVE` または `TWO_WHEELER`
- `routingPreference = TRAFFIC_AWARE` 系
- via waypoint 禁止 = **stopover only**

これにより、25 個までの waypoint も全て stopover として渡すことになる。

### 10.5 stopover only の致命的な UX 影響

stopover は意味的に「停止する地点」であり、Navigation SDK では各 stopover に到達するたびに次の挙動が起きる:

- 「目的地に到着しました」相当の arrival announcement
- waypoint advance イベント (`Navigator.ArrivalListener`)
- camera / header の destination 更新

つまり 25 個 stopover で外部ルート形状を近似しようとすると、**走行中に約 25 回「目的地に到着しました」と言われる**。これは UX として navigation が成立していない。

公式 API には arrival announcement を選択的に抑制する経路は提供されていない (CustomRoutesOptions / RoutingOptions / Navigator どこにも該当 setter なし — spec/22 §4.1, react-native bridge 確認済み)。

### 10.6 multi-leg を逐次 setDestinations で繋ぐ案

「外部ルートを N 区間に分割し、区間ごとに 25 waypoint で route token を取り、`Navigator.setDestinations` を leg ごとに切り替える」運用は**理論上は可能**。ただし:

- leg 境界で navigation session が一旦終了 (`stopGuidance`) → 新 token で再開 (`startGuidance`)
- 境界で camera / header / route line が一瞬リセットされる
- リルートが leg 境界をまたぐと挙動が破綻する (現 leg の token は古い、次 leg の token もまだ無効)
- 外部API 提供元 polyline 全長 (例 100 km) を 25 stopover ずつ刻むと leg 数が爆発し、毎区間 token 取得 = Routes API 課金の山
- そもそも **leg 内でも 25 stopover ぶんの arrival announcement が連発**する (10.5 と同じ問題)

UX / コスト / 実装複雑度のいずれの軸でも採用に耐えない。

### 10.7 25 waypoint 以内で「形状近似」する案の限界

stopover の数 (= arrival announcement 連発) を諦めて、**25 stopover を慎重に選んで 外部API 提供元 polyline の主要屈曲点だけ並べる**案も検討に値する。しかし:

- 外部API 提供元 polyline が Google の道路ネットワークに存在しない裏道や接続を選んでいる場合、25 stopover で誘導しても Google は **道路ネットワーク制約に従って別経路を返す**。形状近似が成立しない。
- 外部API 提供元の代表的優位点 (信号回避抜け道, 動的トラフィック判断) はそもそも Google ルートエンジンの挙動と異なるため、stopover 経由でも忠実再現できない。
- 仮に幾何が似たとしても、リルート時に Navigator は token 内 objectives で **再計算** するため、走行中に 外部API 提供元ルートから乖離していく。

### 10.8 結論 (10.1 の仮説に対する最終判断 — 訂正版)

| 評価軸 | 結果 |
|---|---|
| Routes API v2 単一リクエストで 25 超 | **不可** (上限 25 で確定) |
| **Routes Preferred API で 98 waypoint + 単一 routeToken 発行** | **技術的には成立** (公式に Navigation SDK 互換と明記) |
| Routes Preferred API へのアクセス | **個別契約必須** ("select customers only")。OneNavi (個人 OSS / 収益化なし) ではセルフサービスで取得不可 |
| dev.to 風 multi-leg 分割で単一 routeToken | **不可** (各 leg で別 token、SDK は 1 token のみ受け付け) |
| via 多数で形状制約 | **不可** (token は via と非互換、Routes Preferred も同じ) |
| 25 / 98 waypoint で stopover 形状近似の運用上の壁 | (a) 中間 stopover で arrival announcement が出るかの未検証懸念、(b) Google 道路網非依存ルートは近似不能、(c) リルート時の objectives 再計算で乖離 |

したがって「外部API のルートを Google routeToken 発行で近似して Navigator に走らせる」路線は:
- **技術的には成立する** (Routes Preferred 経由で 98 waypoint まで形状近似可能、token も Navigation SDK 互換)
- **OneNavi の現状 (個人 OSS) では Routes Preferred 個別契約が取れず採用できない**
- **仮に契約が取れても、stopover 連発の挙動が UX を壊さないかは実機 POC でしか確かめられない**

これにより、D-2201 の判断 (本プロジェクトでは route token 路線を採用しない) は維持するが、**理由を訂正する**:
- 旧説明: 「技術的に不可能」(誤り — 98 waypoint route token は技術的に成立)
- 新説明: **契約上の制約 (Routes Preferred 個別契約) と運用上の懸念 (stopover 挙動) の組み合わせで採用しない**

将来 OneNavi が法人化・商用化されて Routes Preferred 契約が取れる可能性が出てきた場合、検証する価値があるのは「中間 stopover の挙動」。検証ポイント:
- 中間 stopover で arrival announcement / 音声 / カードが出るか出ないか
- `Navigator.ArrivalListener` を実装しないと UI に何も出ないのか、SDK 標準 UI が表示されるのか
- waypoint 間で navigation が分断されるのか継続するのか
- これらは何らかの隠し setter で制御可能か (`CustomRoutesOptions` 周辺の reflection 探索が必要)

これらが「中間 waypoint は静かに通過する」挙動なら、Routes Preferred 路線は反射注入路線より圧倒的に保守性が高くなる (= SDK バージョンに対する脆弱性が無い)。逆に「全 stopover で連発」なら採用不可。

### 10.9 決定ログ追加 (訂正含む)

- **D-2205 (2026-04-30, 訂正済):** Routes API v2 の `intermediates` 上限は 25 で確定。一方 **Routes Preferred API は今も active** で、`computeCustomRoutes` は **98 waypoint (lat/lng)** をサポートし routeToken を返す。Navigation SDK との互換性は公式に明記。前回 spec の「98 waypoint は廃止 API の残骸」という記述は誤り。お兄ちゃんの指摘で訂正。
- **D-2206 (2026-04-30):** dev.to 流の multi-leg 分割は単一 routeToken を生成しないため Navigation SDK との接続点を持たない。Routes Preferred を使わずに 98 を超える別の道を作る案にはならない。
- **D-2207 (2026-04-30, 訂正済):** Routes Preferred 路線は **技術的に成立** するが、(a) "select customers only" の個別契約ハードル (OneNavi 個人 OSS では取れない)、(b) 全 waypoint stopover による中間 arrival announcement 挙動の未検証懸念、(c) Google 道路網依存と objectives 再計算による 外部API 提供元ルートからの乖離、で OneNavi 現状では採用しない。本線は spec/20 の反射注入のまま。
- **D-2208 (2026-04-30):** 将来 Routes Preferred 契約が取得できた場合に再検討する価値があるのは「中間 stopover の挙動」「stopover 抑制の hidden option があるか」。この検証は契約取得後の実機 POC でしか行えない。

---

## 9. 参考文献

- [Plan a route | Navigation SDK for Android](https://developers.google.com/maps/documentation/navigation/android-sdk/customize-route)
- [Get a route token | Routes API](https://developers.google.com/maps/documentation/routes/route_token)
- [CustomRoutesOptions.Builder reference](https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/libraries/navigation/CustomRoutesOptions.Builder)
- [Navigator reference](https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/libraries/navigation/Navigator)
- [Navigation SDK for Android release notes](https://developers.google.com/maps/documentation/navigation/android-sdk/release-notes)
- [googlemaps/react-native-navigation-sdk (公開 OSS)](https://github.com/googlemaps/react-native-navigation-sdk)
- [Set intermediate waypoints | Routes API](https://developers.google.com/maps/documentation/routes/intermed_waypoints)
- [Method: computeRoutes REST reference](https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes)
- [GMPRO TSP solver: Google Maps with more than 25 waypoints (afi.io)](https://blog.afi.io/blog/gmpro-tsp-solver-google-maps-with-more-than-25-waypoints/)
- [Google Maps Routes API: Passing the 25 Waypoint Limit (dev.to)](https://dev.to/dannyhodge/google-maps-routes-api-passing-the-25-waypoint-limit-3m0)
- [Optimize your route waypoints | Routes Preferred API](https://developers.google.com/maps/documentation/routes_preferred/waypoint_optimization_proxy_api) — 98 waypoint の根拠 (lat/lng のみ、stopover 必須、travelMode=DRIVE/TWO_WHEELER、累積直線距離 < 1000 km)
- [Method: computeCustomRoutes | Routes Preferred API](https://developers.google.com/maps/documentation/routes_preferred/reference/rest/v1/TopLevel/computeCustomRoutes) — routeToken 発行と Navigation SDK 互換性
