# Google Navigation SDK Callout 配置ロジック調査メモ

## 目的

OneNavi の自前 Callout 配置を、Google Maps / Google Navigation SDK の挙動にできるだけ近づける。

本メモは `com.google.android.libraries.navigation:navigation:6.0.0` と
`com.google.android.gms:play-services-maps:17.0.0` のローカル artifact を静的解析した結果を、
再実装に使える仕様へ落としたもの。SDK 由来のコード本文は転載しない。

完全一致を狙う場合の実装対象は、主に次の 2 系統。

- ルート選択 Callout: ルート polyline 上の候補点から配置できる
- 案内地点 Callout: maneuver / incident / decoration など、指し示す地点は固定で anchor だけ選べる

## 注意

このメモは公開 SDK の挙動理解と互換 UI 実装のための調査ノート。SDK の逆コンパイル結果そのもの、
復元コード、長い逐語的な処理列は repo に入れない。

実装側では、以下だけを利用する。

- 公開 API から取得できる座標、polyline、画面投影結果
- 独自に書き直した candidate generation / scoring / selection
- スクリーンショット比較で得た観察結果

## 調査対象

| Artifact | 結論 |
|---|---|
| `play-services-maps:17.0.0` | `GoogleMap` / `Marker` / `Polyline` / `InfoWindow` の Binder wrapper が中心。ルート Callout 配置ロジックは見当たらない |
| `navigation:6.0.0` | Callout 生成、計測、配置候補生成、スコアリング、背景描画まで Java 層に存在する |

## 主要クラス対応表

| Decompiled class | 推定責務 |
|---|---|
| `internal.pe.ec` | Labeler。通常 label 配置後に `placeCallouts` を実行する |
| `internal.pe.cb` | Callout label factory。style / geometry / text をまとめて Callout label を作る |
| `internal.ps.q` | Positioner interface。候補点と anchor を選び、採用可否を返す |
| `internal.ps.n` | Scorer interface。候補配置に対して `0.0..1.0` の評価値を返す |
| `internal.ps.af` | Composite scorer。複数 scorer を threshold / weight / reward 付きで合成する |
| `internal.ps.p` | Scoring context。camera projection、既存 Callout bounds、前回位置、anchor 候補などを持つ |
| `internal.ps.o` | Positioner output。採用された地理座標と anchor を返す |
| `internal.ps.k` | Callout manager。毎フレームの配置更新で Callout を show / hide / move する |
| `internal.ps.ao` | Callout manager variant。計測済み Callout bounds を使って async に再配置する |
| `internal.nx.e` | Callout handle。`move(position, anchor)` と `hide()` 相当の操作を受ける |
| `internal.nx.i` | MeasuredCallout。Callout id と anchor 別の screen bounds を持つ |
| `internal.nx.j` / `internal.nx.d` | CalloutScreenBounds。`left/top/right/bottom` |
| `internal.acd.b` | Anchor enum。`CENTER`, `LEFT`, `RIGHT`, `TOP`, `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM`, `BOTTOM_LEFT`, `BOTTOM_RIGHT` |
| `internal.pf.a` | Callout geometry。anchor ごとの bounds / tip offset を計算する |
| `internal.pj.d` | Bitmap background renderer。角丸矩形 + tail の Path を描く |

## 全体フロー

1. Callout label を生成する
2. Callout の anchor 別 screen bounds を測る
3. 配置対象を use case / priority / identity で安定ソートする
4. 各 Callout について positioner を実行する
5. positioner が候補点と anchor を列挙する
6. composite scorer が候補を評価する
7. 閾値を超えた候補は即採用、なければ最良候補を採用する
8. 採用された bounds を「既存 Callout」として次の Callout の scoring context に追加する
9. 採用できなければ Callout を非表示にする

配置は greedy。全 Callout を同時に大域最適化しているわけではない。

## Composite Scorer の意味

`internal.ps.af` は複数 scorer を合成する。scorer の生値は `0.0..1.0` に clamp される。

内部的には scorer を 3 種類に分けられる。

| 種類 | 用途 | 挙動 |
|---|---|---|
| hard penalty | 絶対に避けたい条件 | penalty が threshold を超えたら候補全体を不採用 |
| soft penalty | できれば避けたい条件 | `1 - penalty` を weight 付きで quality に足す |
| reward | 前回位置維持など | 生値をそのまま quality に足す |

最終的な score は「quality が高いほど良い」。ルート Callout では `0.8` を超えると早期採用される。

### 代表的な scorer

| Scorer | 推定評価 |
|---|---|
| `ps.ab` | 画面内 / exclusion 領域判定。画面外や禁止領域に入ると penalty |
| `ps.r` | viewport / exclusion overlap の面積比。完全に良いほど 0、悪いほど 1 に近い |
| `ps.v` | 既に配置済みの Callout bounds との overlap 面積比 |
| `ps.w` | Callout bounds が route polyline をどの程度覆うか |
| `ps.aa` | 特定 polyline / point 近傍を隠していないか |
| `ps.z` | 前回位置または重要地点を Callout bounds が隠していないか |
| `ps.t` | 前回 anchor と同じなら reward |
| `ps.u` | 前回 position + anchor の両方が同じなら reward |
| `cr.ao` | waypoint / marker 系 Callout との衝突 |
| `cr.an` | 他 maneuver point を隠していないか |
| `cr.am` | maneuver 前後の線形方向と anchor の向きが合っているか |
| `cr.ak` | zoom 条件。低 zoom での表示抑制に使われる |

## ルート Callout の配置

対応する主な positioner は `internal.cr.ax`。

### 入力

- ルート polyline
- 画面内に見えている polyline segment
- supported anchors
- 前回配置位置
- 前回 anchor
- 既に配置済みの Callout bounds
- exclusion rects
- 他の route / decoration polyline

### 候補点生成

`internal.cr.aw` が polyline 上の候補を作る。

観察できた仕様:

- visible polyline を対象にする
- まず visible polyline を最大 10 点程度に等距離サンプリングする
- 候補順は中央寄りから外側へ広げる
- anchor は supported anchors を順番に回す
- 最大試行数は 40

概念的には次の順。

```text
visiblePoints = sampleVisiblePolyline(routePolyline, maxCount = 10)
orderedPoints = centerOut(visiblePoints)
anchors = rotateSupportedAnchors(start = previousAnchor)

for point in orderedPoints:
  for anchor in anchors:
    yield Candidate(point, anchor)
    stop after 40 candidates
```

### 採用順

`internal.cr.ax` の採用ロジックは、前回位置の維持をかなり強く見る。

1. 前回 position + 前回 anchor を評価する
2. score が `0.8` 超ならそのまま採用
3. 前回 position のまま、別 anchor を評価する
4. score が `0.8` 超なら採用
5. polyline 上の候補点 × anchor を順に評価する
6. score が `0.8` 超なら即採用
7. 即採用候補がなければ、score が正の最良候補を採用
8. score が 0 以下なら非表示

前回配置は、ズームアウト量が一定以上大きい場合は再利用しない。観察上は、前回 zoom から現在 zoom が
2 段階以上低くなった場合に再探索へ倒れる。

### ルート Callout の composite scorer

`internal.cr.ax` で構築される scorer は、おおむね次の優先度。

| 優先度 | 条件 | 実装上の扱い |
|---|---|---|
| P0 | Callout bounds が計算できる | 計算不能なら score `0.5` や不採用 |
| P0 | 画面内 / exclusion 領域に収まる | hard penalty |
| P0 | 既存 Callout と重ならない | hard penalty |
| P0 | waypoint / marker 系 Callout と衝突しない | hard penalty |
| P0 | 重要地点を隠さない | hard penalty |
| P1 | route polyline を覆いすぎない | soft penalty。weight が大きい |
| P1 | viewport overlap が少ない | soft penalty または hard threshold |
| P2 | 前回 position + anchor を維持する | reward |
| P2 | 前回 anchor を維持する | reward |

ルート Callout の route polyline 被覆 scorer は weight が高い。つまり、Callout 同士の重なりを避けた後は、
route line を隠さないことが強く効く。

## 案内地点 Callout の配置

対応する主な positioner は `internal.cr.as` / `internal.cr.al`。

ルート Callout と違い、指し示す地点は固定。候補点を polyline 上で探さない。

### 入力

- maneuver / incident / route decoration の固定位置
- supported anchors
- 前回 anchor
- 既に配置済みの Callout bounds
- route polyline
- 他 maneuver points
- exclusion rects

### 採用順

1. 固定 position を使う
2. supported anchors を評価する
3. composite scorer が最も良い anchor を選ぶ
4. 採用できなければ非表示

### 案内地点系の scorer

`internal.cr.as` で見える scorer 構成は、ルート Callout より route / maneuver 特化。

| 条件 | 意味 |
|---|---|
| 既存 Callout と重ならない | Callout 同士の overlap を避ける |
| waypoint / marker 系と衝突しない | 地点固定 Callout 同士の衝突を抑える |
| 他 maneuver point を隠さない | maneuver icon / point を潰さない |
| route polyline を覆いすぎない | 特に maneuver 周辺で route line を読めるようにする |
| anchor と走行方向の相性 | 進行方向や曲がり方向に対して自然な側へ置く |
| 画面内 / exclusion | viewport 外や禁止領域を避ける |
| 前回 position + anchor 維持 | ちらつきを抑える |

## Anchor と tail の扱い

内部 enum は 9 種類ある。

```text
CENTER, LEFT, RIGHT, TOP, TOP_LEFT, TOP_RIGHT, BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT
```

route / maneuver 系 Callout では、少なくとも次の 4 方向が supported anchors として扱われる箇所がある。

```text
BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT
```

ユーザー観察では Google Maps アプリの CallOut は左下 / 右下だけに見えるが、SDK 内部表現としては上側 anchor
も存在する。完全一致を狙う場合は、次を分けて考える。

- SDK が内部的に扱える anchor
- Google Maps アプリが特定画面で実際に許可している anchor
- OneNavi が UI 仕様として許可する tail side

OneNavi の次期実装では、まず Google Maps アプリ観察に合わせて `BottomRight`, `BottomLeft` を正式対応にする。
上側 anchor は SDK 内部には存在するが、初期実装では fallback または検証対象として扱う。

## Callout 形状

`internal.pf.a` が anchor ごとの geometry を計算し、`internal.pj.d` が bitmap 背景を描く。

観察できた形状仕様:

- 背景は角丸矩形
- anchor に応じて tail が外へ伸びる
- tail は corner / edge 近傍に出る
- anchor 切替時に Callout 全体 bounds と content rect が変わる
- shadow / stroke / fill は style から引く

OneNavi の次期実装では、計測安定性を優先して全方向で同じ bounds にする案と、SDK に寄せて anchor ごとに
bounds / content rect を変える案を比較する。完全一致を狙うなら後者も検証対象にする。

完全一致を狙う場合は、形状について次をスクショ比較で詰める。

- corner radius
- tail length
- tail base inset
- body padding
- shadow blur / offset / alpha
- stroke width
- anchor ごとの content rect offset
- bounds が anchor ごとに変わるか、全方向で固定 bounds にしてよいか

## SDK 実装から見える 2 タイプ

SDK 実装上、Callout manager は「どの positioner に任せるか」だけを見ており、最後は共通の
`position + anchor` を Callout handle へ渡す。違いは positioner が探索する candidate の自由度にある。

| タイプ | 主な positioner | 探索するもの | 固定されるもの |
|---|---|---|---|
| Polyline movable | `internal.cr.ax` / `internal.cr.aw` | polyline 上の tip 候補と anchor | route polyline |
| Point fixed | `internal.cr.as` / `internal.cr.al` | fixed point に対する anchor | tip position |

Polyline movable は、tail 先端を visible route polyline 上の別地点へ動かせる。Point fixed は、tail 先端を
maneuver / incident / decoration の固定地点から動かせない。両者とも anchor を変えることで body の出方は変えられる。

## OneNavi 次期実装への要求

古い CallOut 実装は削除済みのため、既存 API との差分ではなく、SDK 推定仕様から必要な入力と engine を再定義する。

### 1. 入力モデルを 2 タイプに分ける

1 つの anchor 型に可変 / 固定を詰め込まず、target を明示する。

```text
CallOutRequest
  id
  useCase
  priority
  supportedTailSides
  previousPlacement
  contentSize
  target:
    PolylineTarget(screenPolyline)
    FixedPointTarget(screenPoint)
```

### 2. 配置 engine は scorer 方式にする

```text
placeCallOuts(inputs):
  sort callouts by useCase, priority, stable identity
  placed = []
  for callout in sortedCallouts:
    context = PlacementContext(callout, placed, previousPlacement, viewport, routePolylines, exclusions)
    result = positionerFor(callout.target).place(context)
    if result.visible:
      placed += result.bounds
      remember result
    else:
      hide callout
```

### 3. Polyline movable の候補生成を合わせる

候補生成仕様:

```text
visiblePolyline = clipRoutePolylineToViewport(routePolyline, viewport)
sampled = sampleByDistance(visiblePolyline, maxCount = 10)
tips = centerOut(sampled)
anchors = rotate(supportedTailSides, start = previousTailSide)
maxAttempts = 20  # 下側 2 方向のみ。上側 fallback を入れるなら 40
```

### 4. Point fixed は fixed point だけで anchor を選ぶ

候補 tip は入力された fixed screen point だけ。polyline 上の探索はしない。

```text
tips = [fixedPoint]
anchors = rotate(supportedTailSides, start = previousTailSide)
```

### 5. Scorer を実装する

最低限必要な scorer:

| Scorer | 実装内容 |
|---|---|
| `BoundsComputableScorer` | tip + direction + size から bounds が作れるか |
| `ViewportScorer` | bounds が viewport / exclusion に収まるか |
| `CalloutOverlapScorer` | placed bounds との overlap 面積比 |
| `PolylineCoverageScorer` | bounds と route polyline の交差長 / bounds 対角長 |
| `ImportantPointCoverageScorer` | important point が bounds 内に入るか |
| `TailSideStickinessScorer` | 前回 tail side と同じか |
| `PositionStickinessScorer` | 前回 tip + tail side の両方が同じか |
| `ManeuverDirectionScorer` | maneuver の incoming / outgoing bearing と tail side の相性 |

### 6. 閾値を合わせる

初期値:

| 値 | 初期設定 |
|---|---|
| route early accept | `0.8` |
| max route candidates | `40` |
| visible route samples | `10` |
| route polyline clearance px | `10dp 相当` から開始 |
| route polyline overlap fallback penalty | `0.3` |
| zoom reuse disable delta | `2.0` |

weight 初期値:

| Scorer | route duration | maneuver |
|---|---:|---:|
| viewport / exclusion | hard | hard or weight 10 |
| placed callout overlap | hard | hard |
| route polyline coverage | 85 | 500 |
| maneuver point coverage | なし | 100 |
| maneuver direction | なし | 10 |
| exact previous placement reward | 10 | 1 |
| previous anchor reward | 5 | なし or 1 |

この表は実装開始値。完全一致はスクショ比較で調整する。

## スクショ検証計画

完全一致は静的解析だけでは終わらない。Google Maps アプリと SDK の挙動が同一とは限らず、style / density /
feature flag で変わるため、黒箱検証を必須にする。

### 検証ケース

| ケース | 見るもの |
|---|---|
| ルート 1 本 | primary route Callout の基準位置 |
| ルート 2-3 本 | Callout 同士の重なり回避 |
| ルートが画面中央で交差 | route polyline 被覆回避 |
| ルートが画面端を通る | viewport / fallback |
| 短いルート | sample 数が少ない場合 |
| 長いルート | center-out sampling |
| zoom in/out | 前回位置維持と zoom delta |
| pan 中 / pan 後 | temporal stability |
| maneuver が密集 | 固定地点 Callout の anchor 選択 |
| incident + maneuver | useCase priority |
| destination / waypoint 近傍 | important point coverage |
| portrait / landscape | viewport と exclusion |
| density 2.0 / 3.0 / 4.0 | geometry px 誤差 |

### 比較指標

| 指標 | 合格条件案 |
|---|---|
| visibility | Google 側と OneNavi 側で表示 / 非表示が一致 |
| tail direction | 一致 |
| tail tip | 2 px 以内 |
| body bounds | left/top/right/bottom が 3 px 以内 |
| route overlap | Google 側と同じ Callout が route を隠す / 隠さない |
| temporal stability | 同じ pan / zoom 操作でジャンプ回数が一致 |

最初の終了条件は「route preview の 90% ケースで tail direction 一致、bounds 5 px 以内」。
最終終了条件は「主要ケースで visibility / tail direction が一致し、bounds 3 px 以内」。

## 実装ロードマップ

### Phase 1. Engine 基礎

- 入力モデルを Polyline movable / Point fixed に分ける
- placement result に `visible`, `tip`, `tailSide`, `bodyBounds`, `score` を持たせる
- `supportedTailSides` と `previousPlacement` を入力できるようにする
- use case / priority / stable identity で配置順を安定化する

### Phase 2. Route duration 完全寄せ

- route polyline から visible segment を作る
- 最大 10 点の center-out candidate を作る
- anchor rotate を実装する
- score `0.8` 早期採用を入れる
- route polyline coverage scorer を追加する

### Phase 3. Fixed Callout

- fixed point + anchor scoring を実装する
- maneuver / incident / decoration の useCase を分ける
- maneuver direction scorer を実装する
- important point coverage を入れる

### Phase 4. Shape / metrics 調整

- Google Maps 風の padding / tail / radius / shadow をスクショから測る
- 全方向固定 bounds 方式で許容できるか確認する
- 必要なら anchor ごとに content rect offset を変える

### Phase 5. Golden validation

- 固定 route fixture を作る
- emulator screenshot を保存する
- bounds / direction / visibility を JSON に落とす
- 主要ケースの差分を毎回確認する

## 未確定事項

- Google Maps アプリ本体と Navigation SDK の route Callout 実装が完全に同じとは限らない
- style 値は SDK 内でも style resource / remote config に依存する可能性がある
- `internal.oi.j` 周辺の投影・viewport helper は完全には追っていない
- server / feature flag で supported anchor や threshold が変わる可能性がある
- Google Maps アプリ観察では下側 tail が多いが、SDK 内部は上側 anchor も扱う

## 現時点の結論

ユーザー観察で整理した優先順位は、SDK 内部構造とかなり一致している。

1. 他の CallOut と重ならない
2. polyline と重ならない
3. 画面内に描画される
4. polyline 上で表示する場合は散らばりを保つ

ただし SDK 内部では、これらは単純な優先順位ではなく、hard penalty / soft penalty / reward を持つ
weighted scoring として扱われている。完全一致を狙うなら、次期実装は最初から scorer 型で組むべき。
