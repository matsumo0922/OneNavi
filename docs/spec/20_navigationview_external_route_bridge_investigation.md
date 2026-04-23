# 20. NavigationView 外部ルート橋渡し調査

> **作成日:** 2026-04-23
> **ステータス:** ドラフト（実装・POC 併走）
> **対象:** Google Navigation SDK `NavigationView` を、Google 側でルート探索を行わず、**外部ナビ API** のルート/案内データだけで駆動できるかの技術調査
> **関連:** `16_turn_by_turn_navigation_flow.md`, `17_callout_redesign.md`, `18_external_nav_api_migration_plan.md`

> **注意:** 本書はローカル環境で取得した Navigation SDK 6.0.0 runtime/javadoc の調査結果を要約したものであり、decompile したソースの転載は行わない。公開 repo に残すのは、実装判断に必要な観測事実と設計方針のみとする。

---

## 0. この調査の結論

結論から書く。

- **Google 側で route search / reroute / alternative route search を走らせない**という条件は維持できる
- その代わり、`NavigationView` に対して公開 API を使うのではなく、**内部 state を synthetic に組み立てて反射で流し込む**必要がある
- 最も有力な seam は `NavigationView` 内部の `vd.f -> te.d -> tn.o` に **synthetic `to.a` (navigation UI state)** を渡す経路
- route line と alternative route 表示はかなり現実的
- 交差点自動拡大と案内地点 CallOut も狙えるが、**step の位置合わせ精度**が成否を分ける
- `bb.e.a(bv.h)` は lower-level overlay seam として有効だが、**これ単独では immediate camera fit までは届かない可能性が高い**
- `we.et.e(br.az)` のような一見使えそうな hidden method は見つかったが、これは**任意 route 注入ではなく既存 loaded route の選択切替**に過ぎず、本件の主経路にはならない

つまり、本件は「Google の navigator をだます」のではなく、**Google の map/navigation UI controller が期待する内部モデルを外部ナビ API から再構築する**問題だと整理できる。

---

## 1. 背景と前提

今回の前提は明確である。

- ルート探索は外部ナビ API が行う
- リルートも外部ナビ API が行う
- alternative routes 取得も外部ナビ API が行う
- Google 側は `NavigationView` を使って地図と navigation UI を描画するだけに留める

この要件に対して、現状の OneNavi は次の状態にある。

### 1.1 現状のアプリ実装

- `GuidanceSessionManager` は意図的に `Navigator.setDestinations()` / `startGuidance()` を呼んでいない
- 代わりに `RoadSnappedLocationProvider` から map-matched 位置を受け、`ExtNavGuidanceTracker` で進捗・次 maneuver・off-route 判定を自前計算している
- `ExtNavRouteDataSource` は外部ナビ API で得たルートを `GoogleRoute` に浅く射影しているが、`routeToken` は `null`、`steps` は空
- route line は現状の `NavigationView` 標準機能ではなく自前描画に依存している

この構成では、`NavigationView` に built-in で入っている機能群は基本的に動かない。

- 交差点自動拡大
- 案内地点 CallOut
- 標準 route line / alternative route UI
- 純正 header / ETA / trip progress と同期した内部状態遷移

理由は単純で、`NavigationView` 側にとって「今どの route を走っていて、今どの step にいて、どの route 群が loaded されているか」という内部 guidance state が存在していないためである。

---

## 2. 今回の調査範囲

今回確認したのは次の 4 系統である。

1. 公開 API / javadoc に、外部 route や外部 nav info を注入する正式な入口があるか
2. `NavigationView` が内部的にどの class に依存して route line / CallOut / camera を更新しているか
3. hidden / package-private / reflection 対象として使えそうな seam があるか
4. 外部ナビ API の `Guidance` / `GuidancePoint` / `Intersection` / dense polyline を、その内部 model に写像できるか

追加で、synthetic object graph をホスト JVM 上で組み立てる scratch probe も作成し、**コンパイルは通る**ところまで確認した。ただし runtime は Android 依存の `libcore.io.Memory` に到達してホスト JVM では停止したため、最終検証は Android process 内 POC が必要である。

---

## 3. 公開 API だけでは足りない

### 3.1 外部 route / nav info 注入の公開 API は見つからなかった

公開 API と javadoc を確認した限り、次のような API は存在しなかった。

- 任意 polyline を `NavigationView` 標準 route line として登録する API
- 外部 maneuver/step を `NavigationView` に inject する API
- 外部 `NavInfo` を `TurnByTurnManager` など経由で `NavigationView` に渡す API

`TurnByTurnManager` は service/bundle 経由の update 読み書き補助であり、`NavigationView` の入力 seam ではなかった。

### 3.2 custom `Navigator` 差し替えも不可

`NavigationView` は `onCreate` 経路で受け取った `Navigator` を、内部 concrete class に cast して保持している。  
このため、`Navigator` interface を自前実装して差し替える方針は成立しない。

結論:

- **公開 API のみ**で外部ナビ API のルートと step を標準 UI に流し込むことはできない
- **custom Navigator 実装**で回避することもできない

---

## 4. 調査で見つかった実際の seam

### 4.1 seam A: `vd.f -> te.d -> tn.o` の UI state 注入経路

`NavigationView` は内部で `te.d` を組み立て、その前段に `vd.f` を置いている。  
`vd.f` は 2 つの重要な役割を持つ。

1. `a()` で内部 controller 群を「navigation started」状態へ遷移させる
2. `i(newState, prevState)` で `to.a` を `te.d` に流し込む

その先の `tn.o` は、受け取った `to.a` から route 群・current step・destination list・display mode を取り出し、`bv.h` を合成して map/navigation controller を更新する。

この経路の意味は大きい。

- `NavigationView` の標準 route line
- route overview
- alternative route 表示
- current step を基準にした CallOut
- current step / route state に連動する camera 挙動

が、最終的にこの UI state 経路に依存していると見てよい。

**評価:** 最有力。  
本件の主戦場はここ。

### 4.2 seam B: `bb.e.a(bv.h)` の lower-level route display 注入経路

`NavigationView` は別途 `bb.e` 実装も内部に持っており、実体は `bo.m`。  
`bb.e.a(bv.h)` を呼ぶと、そのまま `bo.ac.q(...)` に入り、callback で overlay と camera target を更新する。

加えて、`bb.e` interface 自体は次の 4 メソッドしか持たない。

- `a(bv.h)`
- `b()`
- `c(bc)`
- `d()`

つまり **lower seam 側には「camera を明示的に動かす public-ish な入口」が無い**。

この経路は次に向いている。

- route line 表示
- route 群の loaded 状態の反映
- 一部 camera / overlay 更新

追加調査で分かったこと:

- callback 側 (`bo.k`) には `bs.a.b` の camera target が渡っている
- ただし実際に `bo.m.e(as, ...)` で camera を動かすのは internal flag `bo.m.m` が `true` のときだけ
- `bb.e.a(bv.h)` の実装本体である `bo.m.a(...)` は、`bo.ac.q(...)` に入る直前に必ず `m=false` を書き込む
- decompile/source search で確認できた `bo.m.m` への代入は、少なくとも `bo.m.a(...)` と `bo.m.e(...)` の `false` 書き込みだけだった
- 現時点で確認できた `bo.m.m` の参照/代入は `bo.m` 自身と `bo.k` だけであり、`bb.e.a(...)` の direct call だけで immediate camera fit まで到達する経路は見えていない

したがって seam B 単独で期待できることは次のように整理する。

- route line / route overlay: 確度が高い
- camera target の計算: 行われる
- camera fit の即時発火: 低確度
- current-step CallOut の full fidelity: seam A より不利

ただし、`to.a` 丸ごとの遷移よりは coverage が狭い。  
したがって **「標準 UI を広く使いたい」なら seam A、route 表示だけ早く出したいなら seam B** という整理になる。

#### 4.2.a `tn.o` から `bb.e` への実際の呼び分け

`seam A` を使う場合でも、最終的に route overlay 側へは `tn.o` から `bb.e` が呼ばれる。

追加調査で確定した呼び分けは次のとおり。

- inactive -> active guidance へ入る遷移では、まず `bb.e.d()` が呼ばれる
- 通常の navigation UI state 更新では、`tn.o` が `bv.h` を組み立てて `bb.e.a(bv.h)` を呼ぶ
- current progress が `null` のときは `bb.e.b()` で overlay を落とす
- `sm.h.h == true` の分岐では `bb.e.a(...)` を通らず、別の destination/pin 更新経路へ入る

つまり、`vd.f.i(to.a, prev)` は広い意味では primary seam だが、**route line を実際に map へ出す最後の段は `bb.e`** である。

#### 4.2.b `bo.ac.q(...)` の生成パイプライン

`bo.ac.q(...)` 自体は decompile しきれない巨大 method だったが、bytecode 追跡で次のパイプラインまでは確定できた。

1. `bo.ac.q(bv.h, ..., bo.k)` は `bo.v` callback を作る
2. 内部 executor 上で `bo.aj` が走り、`bv.b` から `bs.a` を組み立てる
3. `bo.aj` は route 数と texture type 数の一致を前提に route overlay 用の入力を作る
4. `bo.aj` は `ac.I = bs.a` を保持したうえで `bo.z.a(bs.a)` を非同期実行する
5. `bo.v.a(bs.a)` は still-current callback であることを確認した後、`bq.ag` 群、optional `bq.z`、optional `bq.o` を作る
6. それらを `bo.aa(bq.e, routeOverlays, bq.z, ...)` に包み、`ac.U` へ差し替える
7. `bo.w` が old/new `bo.aa` の swap を行い、最後に `bo.k.a(bv.h, bs.a)` が map 側の route overlay / camera target 反映を行う

この順序の意味は重要で、**`bb.e.a(bv.h)` は単なる「線を引く」 API ではなく、内部では overlay session を丸ごと作り直す**。

補足として、ここでいう texture type は `bp.e` で、実体は次の 4 種だった。

- `SELECTED_WITH_TRAFFIC`
- `UNSELECTED_WITH_TRAFFIC`
- `SELECTED_UNIFORM`
- `UNSELECTED_UNIFORM`

したがって seam B を direct に叩く最小 POC でも、`bv.h.b()` 側には **route 本数ぶんの `bp.e` list** を揃える必要がある。

#### 4.2.c `bq.o` は `kg.j` 系の別キュー

`bq.e` の optional field `c` には `bq.o` が入ることがある。これは `bo.v` 内で `az.d.ca.a.kg.j` 由来の list から構築されていた。

ここで確定した事実:

- `bq.o` は `acn.ej` list を距離順に sort して保持する
- 実際に消費しているのは `ej.b == 31` の entry で、payload は `acn.eh`
- `ct.b` は `eh.b.b` を route 上の距離として `az.K(distance)` に投影し、`eh.c` の enum を decoration type として使う
- 初期化時 (`bq.t`) に先頭 3 件までを preload する
- live progress 更新時 (`bq.e.e(br.bg)`) に `bq.o.c` へ現在距離が入り、`bq.q` が threshold を見て queue を進める
- queue 内の要素は `ob.k` / `ns.i` などの map decoration object として生成・破棄される

これは `bl` の text cue とは別系統であり、**交差点 preview / 画像付き案内 / 先読みデコレーション系の内部パイプライン**である可能性が高い。  
ただし exact semantics は未確定なので、現時点では「`kg.j` を埋めると `bq.o` 系が有効になる可能性がある」という整理に留める。  
少なくとも **image ref だけでは足りず、距離 along route と decoration kind の対応付けが別途必要**である。

#### 4.2.d `kg.j` は route-relative payload と見てよい

追加調査で、route 切り詰め/再構成側の `rl.g` が `kg.j` を明示的に rebasing していることが分かった。

- selected trip を clone して先頭 path を落とす際、`kg.j` 内の `ej.b == 31` entry を走査する
- 各 entry の `eh.b.b` から「落とした path の先頭 offset」を減算する
- 再計算後の距離が `<= 0` の entry は落とし、`> 0` のものだけ新しい `kg.j` に積み直す

この挙動から、`kg.j` は単なる画像 ID の箱ではなく、**route 上の距離基準で管理される preview/decor payload** とみてよい。

実装上の含意:

- `kg.j` を synthetic に作るなら authority は lat/lng ではなく **distance along route**
- reroute / route trim 時は `kg.j` を差分でいじるより、external route から再構築する方が安全
- 同じ `rl.g` は `ig` や `kg.y` も route-relative に補正しており、preview/decor まわりは複数の index/distance 系 metadata が相互に整合している可能性が高い

補足:

- top-level proto `hl` にも `g: bz<ej>` が存在し、`br.ac` はこれを `bb.g.c(ej)` へ包んでいる
- ただし現時点で preview/decor 実経路として確定しているのは `az.d.ca.a.kg.j -> bq.o`
- よって **初期 POC では `kg.j` を主に埋め、`hl.g` の追加整合が必要かは Android process 観測で判断**するのがよい

### 4.3 `we.et.e(br.az)` は使えそうで使えない

一見、`we.et.e(br.az)` は route を差し替える hidden method に見える。  
しかし実際には、内部 route list に既に存在する route の中から **polyline が一致する route を選択するだけ**の処理だった。

これは次の用途には使える可能性がある。

- すでに internal route list に積んである alternative route 群の selected route 切替

一方で、これは次の用途には使えない。

- 外部ナビ API から来た brand-new route を後から 1 本だけ注入する
- arbitrary route object を route list なしで押し込む

したがって、本件の primary seam にはならない。

---

## 5. synthetic route/state graph は作れそうか

結論として、**かなり作れそう**である。

### 5.1 route 本体: `br.aw -> br.az`

内部 route object は `br.aw` を埋めて `br.az` を構築する形になっている。  
`br.aw` 側には次のような field がある。

- travel mode
- waypoints / destination list
- step array
- polyline
- route summary 相当
- internal proto root (`ij`)

重要なのは、ここが完全な builder DSL ではなく **public field を埋める形**である点で、reflection なしでも組み立てやすい。

### 5.2 step 本体: `br.bk -> br.bl`

step は `br.bk` を埋めて `br.bl` を作る構成で、必要な項目が比較的読みやすい。

- maneuver type
- side
- severity
- step location
- step number
- polyline vertex offset
- primary text
- cue list

これは外部ナビ API の案内点から写像しやすい。

### 5.3 route list / alternative route: `br.be`

`br.be.f(selectedIndex, routes)` で route 群と selected route を表現できる。  
よって external API 側が複数ルートを返せるなら、alternative routes も internal model 上で再現できる。

### 5.4 current progress / guidance state: `rf.a/b -> sm.* -> to.a`

current route 上の progress は `rf.a/b` で表現される。  
それを `sm.l/m/g/h` で包み、最後に `to.a$a` builder で `to.a` にする。

ここで重要なのは以下。

- `rf.b` が current route, current step, remaining distance/time を持てる
- `sm.h` が route list 全体 + current guidance state を持てる
- `to.a` が `NavigationView` 側の標準 UI 更新の入力になる

つまり、**外部 API の route と current progress を internal model に再構成できれば、標準 UI への橋渡し自体は理屈上可能**である。

### 5.5 proto builder surface は思ったより使える

追加調査で、generated proto 群は obfuscated されていても共通の builder surface を持つことが分かった。

- message は共通基底 `adg.bi`
- builder は共通基底 `adg.bb`
- `bi.t()` で空 builder、`bi.u(existing)` で merge 済み builder を得られる
- builder の `b` は mutable message instance で、個々の proto field は public なものが多い

このため、`ee` / `ef` / `bk` / `ke` のように typed setter がほぼ無い builder でも、次のパターンで最低限は組める。

1. default instance から builder を取る
2. 必要なら `s()` で mutable copy にする
3. `builder.b` を具体型へ cast して field と bitmask を直接更新する
4. build 済み message を取り出して親 proto へ埋める

含意:

- `eh` / `eg` / `bm` / `kf` の minimal synthetic 構築は reflection なしでも届く可能性が高い
- 実装上の難所は「builder API の不在」より、**どの field/bitmask をどの整合条件で埋めるか** に寄っている
- `kg.j` 系 POC も、builder class が痩せていること自体は blocker になりにくい

---

## 6. 目的機能ごとの実現性評価

### 6.1 route line

**実現性: 高**

必要なもの:

- route polyline を `az.j` に入れる
- route list を `be` に積む
- `to.a` または `bb.e.a(bv.h)` で controller に流す

外部ナビ API 側には dense polyline が既にあるため、最も実現しやすい。

### 6.2 alternative route 表示 / 切替

**実現性: 高**

必要なもの:

- 外部ナビ API から複数ルートを取得する
- 各 route を個別に synthetic `az` へ変換する
- `be.f(selectedIndex, routes)` で route list を組む

現状の `ExtNavRouteDataSource` は 1 本固定だが、internal 側の model 自体は複数ルート対応になっている。

### 6.3 交差点自動拡大

**実現性: 中〜高**

ポイントは `current step` の位置精度である。  
`NavigationView` 側は current step と route geometry から camera phase を判断していると見てよい。

必要なもの:

- `bl.k` の polyline vertex offset を正しく置く
- `rf.a.b` に current step を正しく入れる
- location update ごとに `rf.b` / `sm.h` / `to.a` を更新する

既存の `ExtNavGuidanceTracker` は dense polyline 上への投影と進捗 m の算出ができているため、このロジックを step anchor 計算にも再利用できる。

### 6.4 案内地点 CallOut

**実現性: 中**

CallOut 自体は current step と cue 情報に依存している。  
最低限、step text だけでも何らかの CallOut は出せる可能性が高いが、標準に近い見た目を得るには cue の質が重要。

追加調査で、CallOut/案内表現には少なくとも 2 系統あることが見えている。

- `br.bl` + cue 群を `tv.g` / `tv.a` が整形する **text/header 系**
- `az.d.ca.a.kg.j -> bq.o` から生成される **preview/decor 系**

さらに `cr.as` constructor は `bl` だけで完結しておらず、`az.P()` が返す route-level geo list から `cr.ao` を組み立てている。  
したがって `aw.f` / `az.m` を dummy の空 list で済ませると、text は出ても **標準 CallOut の viewport/layout 側が痩せる**可能性がある。

このため、OneNavi 側でまず狙うべき順序は次のとおり。

1. `bl` / cue 群だけで text CallOut を成立させる
2. その後で必要なら `kg.j` 系を synthetic に構築し、preview 系の fidelity を上げる

必要なもの:

- `br.bl` の primary text
- 可能なら `br.bm` / `acn.bh` 相当の cue を組み立てる
- 方面看板・道路名・交差点名を step cue に適切に配分する

外部ナビ API 側には次がある。

- 交差点名
- 路線番号
- 方面看板 A/B
- 画像参照

したがって材料はあるが、**どの文言をどの cue type に入れると標準 UI が最もそれらしく出るか**は POC で詰める必要がある。

### 6.5 レーン案内

**実現性: 低〜中**

理由:

- SDK 内部には lane 表現がある
- しかし外部ナビ API の現在の public domain model では lane marker が表に出ていない
- raw guide には lane 相当情報が残っているため、ライブラリ側で domain へ surfacing すれば改善余地はある

今回の主目的は route line / 交差点自動拡大 / CallOut なので、lane は second priority とするのが妥当。

---

## 7. 外部ナビ API のどの情報をどこへ写すか

| 外部データ | internal target | 主用途 | 確度 |
|---|---|---|---|
| dense polyline | `aw.i` / `az.j` | route line, step anchor, camera | 高 |
| route 群 | `be` | alternative route 表示/切替 | 高 |
| 案内点距離 | `bk.g`, `bk.h`, `rf.a` 各種 distance | current step 切替, progress, CallOut | 中〜高 |
| maneuver category | `bk.a`, `bk.b`, `bk.c` | maneuver icon / step semantics | 高 |
| 交差点名 | `bk.i` と cue 群 | header/CallOut text | 中 |
| 方面看板 A/B | cue 群 (`br.bm` 相当) | CallOut richness | 中 |
| route 残距離 / 残時間 | `rf.a` / `rf.b` | trip progress, arrival timing | 高 |
| origin/destination/waypoints | `aw.f`, `aw.g` (`ce` list) | overview, route endpoints | 中 |
| lane marker | 未 surfacing | lane guidance | 低 |
| image refs | custom overlay か後続の internal popup 調査対象 | image CallOut | 低 |

別系統の可能性として、`az.d.ca.a.kg.j` を埋めると `bq.o` キューが有効になり、`bl` の text cue だけでは出せない preview/decor 系 UI を再利用できる余地がある。  
これはまだ **推定** だが、image refs / intersection image を標準寄りに見せる上では最も有望な hidden path である。  
一方で `bq.o` が実際に要求しているのは「画像 ID」そのものより **距離 along route + decoration kind** に近く、外部ナビ API の `imageRefs` をそのまま 1:1 で写せる保証はない。

### 7.1 一番重要な変換は `step -> polyline vertex offset`

step が route 上のどこにあるかを internal model に伝えるため、`GuidancePoint` や `Intersection` を dense polyline 上に投影して、**最寄りの vertex index** あるいは segment 上の代表 index を求める必要がある。

ここが雑だと次の不具合が出る。

- current step の切替が早すぎる / 遅すぎる
- 交差点拡大のタイミングがズレる
- CallOut の出る地点がズレる
- alternative route 選択後の current step が不安定になる

幸い、現行の `ExtNavGuidanceTracker` は polyline 射影を既に持っているため、新たに geometry engine を起こす必要はない。

### 7.2 `aw.f` / `az.P()` は CallOut 側でも使われる

`az.P()` は route object が保持する route-level point list を返し、`cr.as` constructor や `bq.e` の overlay composition で `cr.ao(...)` 生成に使われる。

このため synthetic `az` では、`aw.f` / `az.m` を単に validator 通過用のダミーにせず、少なくとも次を満たす方が安全である。

- 空でないこと
- route endpoints / overview 候補と幾何的に矛盾しないこと
- external route の origin/destination/waypoints と概ね対応していること

初期 POC では「最小の waypoint list」で済ませてよいが、CallOut/camera を詰める段階では `az.P()` の中身も観測対象に入れるべきである。

### 7.3 `destination step` は optional ではない

追加調査で分かった重要点として、internal route model は **destination step を route 終端の基準点として使う**。

- `az.j()` / `az.l()` は destination step を探す
- `az.x()` は destination step の `k` を route 終端 vertex とみなす
- `az.w()` は `az.x()` までの累積長を route 全長として返す
- `az.p()` は destination step 群を境界に route segment を切る

したがって、synthetic `az` では少なくとも次を満たす必要がある。

- `DESTINATION` step を 1 つ以上持つ
- その `bl.k` が route polyline 上の妥当な vertex offset になっている
- destination step が current route の `bl[]` に実在する

これが欠けると、route line 自体は出ても以下が壊れやすい。

- route 全長計算
- destination pin / route segment 切り出し
- overview / camera の終点基準
- alternative route 切替後の終端判定

POC の最小 route でも、**current maneuver step より先に destination step を正しく置く**ことが必須になる。

### 7.4 CallOut の text/cue は `bl` 1 本の文字列ではない

`bl` は単なる `step text` ではなく、複数カテゴリの cue を内部に持つ。

- `indirectCues`
- `followCues`
- `intersectionCues`
- `stepGuidances`
- `laneGuidances`
- `navigationPopups`
- `spokenText`

`bk.q` に入れた raw cue 群は `bl` constructor 内で type ごとに再分類される。  
その後 `tv.g.f(...)` が cue を選別し、`tv.a.l(tv.g, bl)` が最終的な CallOut/header 用 text を作る。

ここで分かったこと:

- `tv.a.l(...)` は `bl.u/v/w/x/y` などの cue 群を使って text を組む
- road name / exit number / intersection cue は dedupe や優先度付きで扱われる
- `bl.p` に cue span が入っていれば `tv.a.o(...)` 経由で icon 付き text も作れる

したがって CallOut を標準寄りにしたい場合、最低でも次の 2 段階で考える必要がある。

1. **最低限の可動条件**
   - `bk.i` に primary text を入れる
   - `bk.q` に road / intersection / exit 系の raw cue を 1 つ以上入れる
2. **見た目の fidelity 向上**
   - `br.bm` / `acn.bh` 相当で cue type を適切に分ける
   - 方面看板、路線番号、交差点名をどの cue type に置くかを調整する
   - 必要なら `bl.p` の span まで作って icon 表現を有効化する

つまり、CallOut は「`bk.i` に文字列を入れれば終わり」ではない。

### 7.5 CallOut 対象 maneuver には SDK 側のフィルタがある

`cr.as.c(bl)` は全 step を対象にしていない。  
obfuscated enum field を ordinal 順へ引き直すと、少なくとも次の maneuver 群が CallOut 候補に含まれる可能性が高い。

- `TURN`
- `NAME_CHANGE`
- `UTURN`
- `ON_RAMP`
- `OFF_RAMP`
- `FORK`
- `FERRY`
- `FERRY_TRAIN`
- `ROUNDABOUT_EXIT`
- `ROUNDABOUT_ENTER_AND_EXIT`

これは decompile 結果からの**推定**であり、最終的には Android process POC で確認が必要。  
ただし少なくとも「外部 API の案内点を全部 step 化すれば全部標準 CallOut が出る」とは考えない方がよい。

---

## 8. 今回の調査で分かった「採らない方がいい案」

### 8.1 Google 側で shadow guidance を走らせる案

これは以前の調査で最有力に見えたが、今回の要件では不採用。

理由:

- Google 側で route search を行うことになり、要件違反
- reroute も Google 側の判断が混ざる
- external route と Google route のズレが前提になる

今回の要件は「Google は表示だけ」なので、この案は外す。

### 8.2 custom `Navigator` 実装差し替え案

`NavigationView` が concrete class cast を行うため不採用。

### 8.3 `we.et.e(br.az)` だけで解決する案

これは loaded route の selected route 切替 seam であって、external route 注入 seam ではない。  
補助用途はありうるが、主案にはならない。

---

## 9. 推奨アーキテクチャ

推奨は次の 3 レイヤ構成である。

### 9.1 `ExtNavInternalRouteMapper`

責務:

- 外部ナビ API の route/guidance を synthetic `az` 群へ変換する
- step (`bl`) の anchor を dense polyline 上へ投影して埋める
- alternative route 群を `be` にまとめる

入出力のイメージ:

```text
ExtNav route/guidance list
  -> synthetic az list
  -> be(selectedIndex, routes)
```

### 9.2 `NavigationViewReflectionBridge`

責務:

- `NavigationView` から private field `m`, `n`, `p` を取得する
- seam A を使う場合:
  - `vd.f.a()` で internal navigation controller を started にする
  - `vd.f.i(newState, prevState)` で `to.a` を注入する
- seam B を使う場合:
  - `bb.e.a(bv.h)` を lower-level で叩く

この class に SDK version 依存を隔離する。

### 9.3 `ExtNavNavigationUiBridge`

責務:

- `ExtNavProgressSnapshot` から current step / remaining distance/time を更新する
- `rf.b -> sm.h -> to.a` を都度再構築する
- reroute / route selection 変更時に internal route list を再生成する

これにより `GuidanceSessionManager` は「外部 guidance の authority」を持ち続けつつ、`NavigationView` へは synthetic internal state を流すだけで済む。

### 9.4 state builder 側は自前で埋めすぎない

追加調査で、`to.a$a` と `sm.m` には明確な整合条件があることが分かった。

`sm.m` の不変条件:

- route count == route guidance state count
- selected route == guided route
- betterRouteIndex は route state 配列の範囲内

`to.a$a` の挙動:

- `sm.h` を渡すと `headerStep` を再評価する
- 通常は `h.f().c` の current step を採用する
- `headerStep` が current route 上に無い場合は warning 寄りの扱いになる
- current step が進んだときは builder 側で `headerStep` を追随させる

実装上の含意:

- POC 初期段階では `headerStep` を無理に手動固定しない
- `rf.b.c` の current step と selected route の整合だけ正しく保つ
- `to.a$a` に最終補正を任せる方が安全

特に `prevState/newState` を差分更新するとき、manual で `headerStep` を持ち回すと stale な step を掴みやすい。

---

## 10. 次にやること

ここからは「調査だけを継続する」フェーズではなく、**実装しながら未確定点を観測で潰す**フェーズとして扱う。

- 静的調査で残っている論点は、`交差点自動拡大` の最終起動条件と `kg.j` / `hl.g` の実運用上の最小整合に絞られている
- これ以上の広い逆解析より、Android process 内 POC の方が情報価値が高い
- したがって以後は `NavigationViewReflectionBridge` と synthetic route/state mapper を実装し、その結果を見ながら本書を随時更新する
- 失敗した箇所だけを追加で狭く逆解析する

ここからは調査ではなく実装計画である。  
優先順位順に書く。

### 10.1 Step 1: current 手描き実装を kill switch 付きで残す

まず、既存の manual route line / maneuver UI をすぐには消さない。

理由:

- reflection bridge は SDK version 依存が強い
- POC 中に route line すら出ない可能性がある
- fallback が無いとナビ機能全体を止めるリスクがある

やること:

- feature flag を追加
- `NavigationView` bridge が有効なときだけ synthetic injection を使う
- 失敗時は現行 custom 描画へフォールバックする

### 10.2 Step 2: external route を複数本取れるようにする

今の `ExtNavRouteDataSource` は 1 本固定で `steps` も空。  
bridge を本気でやるなら、最初に route list の責務を整理する必要がある。

やること:

- external route search で複数候補を取得
- guidance とのペアリング方法を定義
- registry を「1 route payload」ではなく「route set payload」に広げる

完了条件:

- alternative route 候補を UI 上で選択できる前提データが揃う

### 10.3 Step 3: `ExtNavInternalRouteMapper` を作る

最初の POC では、最低限の synthetic route を組む。

最小スコープ:

- polyline
- origin / destination
- current route 1 本
- step array
- destination step
- current step
- remaining distance/time

この段階では、cue の見た目は粗くてもよい。  
まずは `NavigationView` が route line と current step を受け付けることを確認する。

### 10.4 Step 4: Android process 内の reflection POC

これは最重要。

ホスト JVM では Android 依存で止まるため、次は Android instrumentation か debug-only screen で実行する。

POC 手順:

1. `NavigationView` を表示する debug screen を作る
2. `NavigationViewReflectionBridge` で type ベースに `vd.f`, `bb.e` を取得する
3. まず `bb.e.a(bv.h)` で minimal route 表示が出るか確認する
4. その際、`bb.e.a(...)` 単独では camera fit が動かない前提で結果を評価する
5. 次に `vd.f.a()` + `vd.f.i(to.a, prev)` で full UI state を注入する
6. `bl` cue だけで text CallOut が出るかを確認する
7. 余力があれば `kg.j` 系を minimal に埋めた synthetic route でもう一度試す
8. synthetic current step を動かして CallOut と camera が反応するか確認する
9. `aw.f` / `az.P()` を空にした場合と埋めた場合で CallOut / viewport の差分を見る
10. `kg.j` だけで preview/decor が足りるか、追加で `hl.g` 整合が必要かを観測する

ここで確認したい観測点:

- route line が built-in で描画されるか
- `showRouteOverview()` が synthetic route に対して効くか
- current step 更新で CallOut が出るか
- 交差点接近で camera が切り替わるか
- `bb.e.a(...)` 単独と `vd.f.i(...)` 経由で挙動差が出るか
- `kg.j` 未設定時と設定時で preview/decor 系 UI が変わるか
- `az.P()` の有無で CallOut / overview の見え方が変わるか
- `hl.g` を触らなくても `kg.j` だけで preview/decor が成立するか

### 10.5 Step 5: `GuidanceSessionManager` と live 接続する

POC が通ったら live update へ進む。

やること:

- `ExtNavProgressSnapshot` を bridge へ渡す
- location update ごとに `rf.b` / `sm.h` / `to.a` を再構築
- off-route / reroute 時は external guidance authority のまま route set を入れ替える

この段階で初めて、現在の manual route line 実装と差し替えられる。

### 10.6 Step 6: CallOut の fidelity を上げる

最後に text/cue を詰める。

やること:

- 交差点名、方面看板、路線番号をどの cue type に入れると標準 CallOut が最も自然か調整
- 必要なら external library 側で lane marker を domain へ surfacing
- image refs の扱いを別途検討

---

## 11. 実装タスクの具体案

### 11.1 追加候補 class

| class | 役割 |
|---|---|
| `ExtNavInternalRouteMapper` | 外部 route/guidance -> synthetic `az` / `be` 変換 |
| `ExtNavInternalStepMapper` | `GuidancePoint` / `Intersection` -> `bk` / `bl` 変換 |
| `NavigationViewReflectionBridge` | `NavigationView` の private seam 呼び出しを隔離 |
| `ExtNavUiStateMapper` | `ExtNavProgressSnapshot` -> `rf.b` / `sm.h` / `to.a` 変換 |
| `ExtNavBridgeSession` | `NavigationView` 1 インスタンスに対する old/new state 管理 |

### 11.2 既存 class の変更候補

| class | 変更内容 |
|---|---|
| `ExtNavRouteDataSource` | 複数 route 取得、route set registry 化 |
| `GuidanceSessionManager` | manual UI 更新と bridge 注入の二重化、後に置換 |
| `RouteManager` | selected route の切替時に bridge 再構築 |
| `HomeMapsMapEffectContent` | built-in route line 採用後の custom polyline 削減 |

---

## 12. リスクと blocker

### 12.1 SDK version 依存

今回の seam は public API ではない。  
よって SDK version update に弱い。

対策:

- Navigation SDK version を固定する
- reflection target の class/field/method 名を 1 箇所へ隔離する
- 起動時に bridge health check を行い、失敗時は manual rendering へ落とす

### 12.2 `ce` / proto root の最小構築

`az` には internal proto root (`ij`) と waypoint list (`ce`) が必要で、ここはまだ一部未確定要素がある。  
ただし constructor と field の形は把握できており、Android process POC で詰める段階まで来ている。

追加で確定した不変条件:

- `aw.a` (`ca`) は non-null 必須
- `aw.f` / `aw.g` の waypoint list は non-null 必須
- `aw.C` (route metadata list) は non-null 必須
- `aw.D` (`ij`) は non-null かつ route root validation を通す必要がある
- `ca.e() > 0` の場合、`waypoint count == leg count + 1` を満たす必要がある

補足:

- polyline が空でも constructor は `(0,0)` fallback を入れて落ちない
- ただしこれは crash 回避に過ぎず、route line / camera / destination 判定を正しく出すには不十分
- `aw.f` / `az.m` は validator 通過だけでなく、`az.P()` 経由で CallOut/viewport 側にも使われる

要するに、**`az` の最小構成は「polyline さえあればよい」ではない**。  
POC でも route leg 数と waypoint 数の整合を守る synthetic route を作る必要がある。

### 12.3 external route と guidance の整合

alternative routes を本格対応するには、各 route に対して対応する guidance が必要になる。  
API 側が route list と guidance list をどこまで安定して返せるかは、ライブラリ側の責務整理も必要。

### 12.4 lane 情報の surfacing 不足

lane guidance まで標準化したいなら、external library 側で raw lane marker を domain に出す作業が必要になる可能性が高い。

---

## 13. この調査で更新された判断

本調査を踏まえ、以後の実装判断は次のとおりとする。

1. Google 側では route search / reroute / alternative route search を行わない
2. `NavigationView` の built-in navigation feature は、synthetic internal model を反射で注入して再利用する
3. primary seam は `vd.f.i(to.a, prev)` とする
4. `bb.e.a(bv.h)` は route 表示系の補助 seam として保持する
5. `we.et.e(br.az)` は selected route 切替用途以外には期待しない
6. 実装は POC -> live integration -> fidelity 向上の順で進める

---

## 14. 直近の着手順

迷わないように、直近の順番を固定する。

1. `docs/spec/20` を正本として、この方針で進める
2. `ExtNavRouteDataSource` を複数 route 対応へ広げる
3. `ExtNavInternalRouteMapper` / `NavigationViewReflectionBridge` を debug-only で実装する
4. Android process POC で route line / overview / CallOut / camera の 4 点を確認する
5. 成功した機能から `GuidanceSessionManager` へ統合する

この順序を崩すと、POC 前に mapper を作り込みすぎるか、逆に route list が無いまま alternative route を考えることになり、遠回りになる。

---

## 15. 補足

今回の調査で一番重要だったのは、「公開 API で無理だから諦める」でも「Google に route search をさせてごまかす」でもなく、**`NavigationView` の内部 state machine に external guidance を橋渡しする設計へ視点を切り替えたこと**である。

現時点では未確定要素も残るが、少なくとも次は「実現可能性を探る調査」ではなく、**Android process 内 POC を書いて route line / CallOut / camera が本当に反応するかを確認する実装フェーズ**に入れる。
