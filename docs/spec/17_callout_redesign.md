# 17. CallOut 配置設計

## 背景

OneNavi に実装していた古い CallOut は削除済み。以後の設計では、削除済み実装の API、型、配置方式を前提にしない。

Google Maps / Navigation SDK の逆解析結果は `docs/research/google_navigation_callout_placement.md` を参照する。この文書では、OneNavi 側で再実装する場合の要求仕様だけを整理する。

## 基本方針

CallOut は 1 種類の汎用配置問題として扱わない。SDK 側の構造に合わせて、入力モデルを次の 2 タイプに分ける。

| タイプ | 用途 | tail 先端 | body 位置 | tail 左右選択 |
|---|---|---|---|---|
| Polyline movable | ルート候補ごとの所要時間 / 差分表示 | ルート polyline 上で移動可能 | 候補点ごとに移動可能 | 可能 |
| Point fixed | 案内地点 / 事故地点 / waypoint / destination など | 指定地点に固定 | tail 左右に応じて相対位置だけ変わる | 可能 |

重要なのは、どちらも「左下 tail / 右下 tail を選べる」が、動かせる対象が違うこと。

- Polyline movable: tail 先端そのものを polyline 上の別地点へ移せる
- Point fixed: tail 先端は指定地点から動かせない。body の出方だけを変える

Google Maps アプリ観察では tail は左下 / 右下のみが使われる。SDK 内部には上側 anchor も存在するが、OneNavi の初期仕様では下側 2 方向だけを正式対応にする。上側方向は、スクショ比較で必要と分かった場合の fallback として扱う。

## 配置優先順位

ユーザー観察と SDK 解析を合わせると、次の要求が中核になる。

1. 他の CallOut と重ならない
2. ルート polyline と重ならない
3. 画面内、または許容 viewport 内に入る
4. polyline 上で表示する場合は、他の CallOut と散らばりを保つ
5. 前回配置から無意味にジャンプしない

SDK はこれを単純な first-fit ではなく、hard penalty / soft penalty / reward を持つ scoring として処理している。完全一致を狙う場合、OneNavi 側も scorer 方式に寄せる。

## Polyline Movable CallOut

対象はルート候補ごとの所要時間や差分表示。CallOut の tail 先端は、表示中のルート polyline 上で候補点を試せる。

### 入力

- stable id
- route screen polyline
- measured body size
- supported tail sides: `BottomLeft`, `BottomRight`
- previous placement: tip、tail side、zoom
- priority: selected route / alternative route など
- exclusion rects: 上部バー、下部操作領域、FAB、案内パネルなど

### 候補生成

1. route polyline を現在 viewport に clip する
2. visible segment を距離ベースで最大 10 点程度に sample する
3. 画面中央寄りから外側へ試す順序に並べる
4. 前回 tip がまだ有効なら最初に試す
5. 各 tip で左下 / 右下 tail を評価する

候補数の初期上限は `10 tips x 2 sides = 20 attempts` とする。SDK 内部は上側 anchor も含めて最大 40 attempts を持つため、上側 fallback を有効化する場合は `10 tips x 4 sides = 40 attempts` に揃える。

### 評価

Polyline movable では、次の scorer を使う。

| Scorer | 目的 |
|---|---|
| Bounds | tip + tail side + body size から rect が作れるか |
| Viewport | rect が viewport / exclusion に収まるか |
| CallOut overlap | 既に配置済みの CallOut と重ならないか |
| Polyline coverage | rect が route polyline を隠しすぎないか |
| Dispersion | 他の CallOut と近すぎないか |
| Position stickiness | 前回 tip と同じ、または近い位置を優遇する |
| Tail side stickiness | 前回 tail side と同じ向きを優遇する |

前回配置の score が十分高い場合は、そのまま維持する。初期値は SDK 解析に合わせて `0.8` を早期採用の目安にする。

## Point Fixed CallOut

対象はナビゲーション中の案内地点、事故地点、注意喚起地点など。CallOut の tail 先端は、入力された screen point に固定される。

### 入力

- stable id
- fixed screen point
- measured body size
- supported tail sides: `BottomLeft`, `BottomRight`
- previous tail side
- use case: maneuver / incident / destination / waypoint など
- priority
- exclusion rects
- important points

### 候補生成

候補 tip は 1 点だけ。polyline 上を探索しない。

```text
tips = [fixedPoint]
sides = rotate([previousSide, otherSide])
attempts = tips x sides
```

つまり、Point fixed は「どこに出すか」ではなく「指定地点に対して左下 / 右下どちらで出すか」を選ぶ問題になる。

### 評価

Point fixed では、次の scorer を使う。

| Scorer | 目的 |
|---|---|
| Bounds | fixed point + tail side + body size から rect が作れるか |
| Viewport | rect が viewport / exclusion に収まるか |
| CallOut overlap | 既に配置済みの CallOut と重ならないか |
| Important point coverage | 重要地点や案内点を body が隠さないか |
| Maneuver direction | 進行方向 / 分岐方向と body の出方が不自然でないか |
| Tail side stickiness | 前回 tail side と同じ向きを優遇する |

すべての候補が悪い場合の fallback は use case ごとに決める。

| use case | fallback |
|---|---|
| maneuver | 重なりを許容してでも表示 |
| incident | priority が低いものは非表示可 |
| destination / waypoint | marker と競合する場合は非表示可 |

## 共通データモデル

再実装時は、CallOut 入力を 1 つの「anchor」だけで表現しない。移動可否を型で分ける。

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

placement result は最低限これを持つ。

```text
CallOutPlacement
  id
  visible
  tip
  tailSide
  bodyBounds
  score
```

`tip` は tail 先端。Polyline movable では route polyline 上の採用点、Point fixed では入力 fixed point と必ず一致する。

## Engine の流れ

```text
placeCallOuts(requests, viewport, exclusions, previousState):
  sorted = sortByUseCaseAndPriority(requests)
  placed = []

  for request in sorted:
    positioner = when request.target:
      PolylineTarget -> polylineMovablePositioner
      FixedPointTarget -> pointFixedPositioner

    result = positioner.place(request, viewport, exclusions, placed, previousState)

    if result.visible:
      placed += result.bodyBounds
      save result for next frame
```

この分岐を engine 内部に閉じ込め、UI 側は placement result だけを受け取って描画する。

## 描画仕様

- body は角丸矩形
- tail は左下または右下から出る
- tail 先端は placement result の `tip` に一致させる
- body bounds は scorer が評価した bounds と一致させる
- shadow / padding / corner radius / tail length はスクショ比較で調整する

完全一致を狙う場合、tail side ごとに content rect や bounds が変わる可能性も許容する。計測安定性のために全方向で同じ余白を固定する方式は実装しやすいが、SDK の geometry と一致しない可能性がある。

## 再配置タイミング

| 状況 | 挙動 |
|---|---|
| route result 更新 | 即時再配置 |
| selected route 変更 | priority / z-order / style のみ変更。geometry が同じなら再配置不要 |
| camera idle | screen point / polyline を更新して再配置 |
| user gesture 中 | 表示を抑制するか、前回配置を維持する。完全一致検証で決める |
| navigation location 更新 | use case priority に従って再配置 |

古い実装で採用していた固定インターバルや簡易 first-fit は、完全一致を狙う段階では前提にしない。

## 検証

SDK 解析だけでは Google Maps アプリの見た目との完全一致は確定しない。次のスクショ比較を終了条件にする。

| ケース | 見るもの |
|---|---|
| ルート 1 本 | Polyline movable の基準位置 |
| ルート 2-3 本 | CallOut 同士の衝突回避 |
| ルートが交差 | polyline coverage |
| 短いルート | sample 数が少ない場合の fallback |
| 画面端 | viewport / exclusion |
| maneuver 密集 | Point fixed の tail side 選択 |
| incident + maneuver | priority と非表示判断 |
| zoom / pan | 前回配置維持とジャンプ抑制 |

合格条件の初期値:

- tail side が主要ケースで一致
- tail tip が 2 px 以内
- body bounds が 5 px 以内
- 表示 / 非表示の判断が主要ケースで一致
- pan / zoom 後のジャンプ回数が大きく違わない

## 実装順

1. 入力モデルを Polyline movable / Point fixed に分ける
2. 共通 scorer と placement result を定義する
3. Point fixed positioner を先に実装する
4. Polyline movable positioner を実装する
5. CallOut 形状と geometry をスクショ比較で調整する
6. route preview と navigation overlay のそれぞれに接続する
7. golden screenshot fixture を作り、差分を詰める

## 非目標

- 削除済み CallOut 実装の復元
- 古い first-fit 配置方式の踏襲
- SDK 内部 class 名に依存した公開 API の設計
- Google Maps / Navigation SDK の非公開コード片の転載
