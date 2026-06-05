# 25. カメラ Fly-To アニメーション — van Wijk–Nuij "Smooth and efficient zooming and panning" の調査と適用方針

## 0. このドキュメントの目的

OneNavi の地図カメラ移動 (`MapCameraState.animateCameraTo`) を、GoogleMap の `animateCamera`
が見せる「遠い地点へ移動するときは一旦ズームアウトしてから寄り直す / 近い地点では引きなし」
というシネマティックな挙動に寄せるための、アルゴリズム調査と OneNavi への適用設計をまとめる。

context を失った状態からでも本ドキュメント単体で実装着手できる粒度で書く。

### 0.0 改訂履歴

- **v1**: 初版。現状の `animateCameraTo` は「中心・bearing・tilt を直線 lerp、zoom を別 lerp」
  という単純補間で、遠距離移動でズームアウトが挟まらない。本ドキュメントで van Wijk–Nuij
  アルゴリズムを調査し、OneNavi への移植方針を定める。
- **v2**: §5 段階 ① を実装。`feature/map` に `camera/WebMercatorProjection.kt`（lat/lng ⇔
  ズーム 0 ワールドピクセル）と `camera/VanWijkZoomPath.kt`（d3-interpolate `interpolateZoom` 移植の
  純関数）+ `androidUnitTest` を追加。
- **v3 (現行)**: §5 段階 ②③ を実装。`MapCameraState` に `flyCameraTo`（van Wijk 経路で移動、bearing/tilt は
  別チャンネル lerp、ビューポート未確定時は `animateCameraTo` フォールバック）を追加し、`moveTo` /
  `showRouteOverview` を `flyCameraTo` 経由に切り替え。`MapItem` から `onSizeChanged` でビュー幅、
  `rememberMapCameraState` から `LocalDensity` で密度を `MapCameraState` に渡すよう配線。`zoomIn` /
  `zoomOut` / `changeZoom` は退化ケースのため従来の `animateCameraTo` のまま据え置き。companion に
  `CAMERA_FLY_TO_RHO`（1.42）/ `CAMERA_FLY_TO_SPEED_SCALE`（1.0）/ `MIN_FLY_TO_DURATION_MS`（250）/
  `MAX_FLY_TO_DURATION_MS`（3000）を追加。残るは ④ パラメータ調整（実機での体感合わせ）。

### 0.1 現状の実装 (`feature/map/.../state/MapCameraState.kt`)

- `animateCameraTo(target, panDurationMs?, zoomDurationMs?, onFinished)`
  - 1 本の `ValueAnimator` を `max(panDuration, zoomDuration)` の長さで線形に回す
  - 各フレームで pan 用 fraction / zoom 用 fraction を別々に算出し、それぞれ `DecelerateInterpolator` を個別適用
  - 中心 lat/lng・bearing・tilt は pan fraction、zoom は zoom fraction で `lerp`
- 問題: 中心を**測地座標で直線**に動かし、zoom を独立に動かすだけなので、
  - 遠距離: 画面内の見かけ移動が高速な「すべり」になる。GoogleMap のように引いて寄り直さない
  - 「現在 zoom と目標 zoom の関係」「2 点間の距離」「ビューポートの大きさ」を考慮していない

## 1. GoogleMap `animateCamera` の観察された挙動

公式ドキュメント・ソースとも非公開だが、`GoogleMap.animateCamera(CameraUpdate, durationMs, callback)`
は以下のように振る舞う:

1. start と target の地上距離が、現在のズームでのビューポートに対して**大きい**ほど、
   経路の途中でズームアウト → パン → ズームインの「弧」を描く
2. 2 点が近い (≒ 同じ画面に収まる) 場合はズームアウトが消え、ただのイージングになる
3. ズームアウトの深さは「現在 zoom」「target zoom」の両方に依存しているように見える
4. `durationMs` は所要時間の**ヒント**で、実際の経路はこのシネマティック方式で決まる

これは「画面内の見かけ移動速度 (optical flow) をできるだけ一定にする」と自然に出てくる挙動で、
情報可視化の世界では **van Wijk & Nuij のアルゴリズム** として知られる。

## 2. van Wijk–Nuij "Smooth and efficient zooming and panning"

- 論文: Jarke J. van Wijk, Wim A.A. Nuij. *"Smooth and efficient zooming and panning."*
  IEEE Symposium on Information Visualization (InfoVis) 2003. PDF: <https://vanwijk.win.tue.nl/zoompan.pdf>
- 改訂版: *"A model for smooth viewing and navigation of large 2D information spaces."*
  IEEE TVCG 2004. PDF: <https://vanwijk.win.tue.nl/zptvcg.pdf>

### 2.1 アイデア

- カメラを「対象平面の上空を高さ \(w\) で漂う点」とモデル化する (視野角 ≈ 53°)。
  \(u\) が水平位置、\(w\) が高さ。\(w\) が大きい = ズームアウトしている。
- 「ズーム + パンの同時操作で知覚される速度」を見積もる距離尺度を定義し、その尺度のもとで
  start \((u_0, w_0)\) → end \((u_1, w_1)\) を結ぶ**測地線**（最短経路）を解析的に求める。
- 自由パラメータは 2 つだけ:
  - \(\rho\)（曲率 / curvature）: ズームとパンのトレードオフ。大きいほど大胆に引く。論文の推奨は \(\rho \approx 1.42\)（\(\rho^2 \approx 2\)）。
  - \(V\)（速度）: アニメーション全体の速さ。
- 経路の弧長 \(S\) が解析的に出るので、これを時間にマッピングすれば等速っぽい滑らかな遷移になる。
- start と end が（ほぼ）同じ位置のときは退化し、純粋な指数ズーム（パンなし）になる
  → 「近い地点では引きが消える」がここから自然に出る。

### 2.2 数式（d3-interpolate `interpolateZoom` の実装をそのまま引用）

d3 の `interpolateZoom` は van Wijk–Nuij をほぼ素のまま実装したもの。2D 化（\(u\) を \((u_x, u_y)\)）してある。
\(p = [u_x, u_y, w]\) で、\(u_x, u_y\) は「ズーム 0 のワールドピクセル座標」、\(w\) は「ビューポート幅をズーム 0 ワールドピクセルで測った値」。

```javascript
var epsilon2 = 1e-12;

function cosh(x) { return ((x = Math.exp(x)) + 1 / x) / 2; }
function sinh(x) { return ((x = Math.exp(x)) - 1 / x) / 2; }
function tanh(x) { return ((x = Math.exp(2 * x)) - 1) / (x + 1); }

// rho = Math.SQRT2, rho2 = 2, rho4 = 4
function zoom(p0, p1) {
  var ux0 = p0[0], uy0 = p0[1], w0 = p0[2],
      ux1 = p1[0], uy1 = p1[1], w1 = p1[2],
      dx = ux1 - ux0,
      dy = uy1 - uy0,
      d2 = dx * dx + dy * dy,
      i, S;

  // 退化ケース: u0 ≅ u1 → 純粋な指数ズーム（パンなし）
  if (d2 < epsilon2) {
    S = Math.log(w1 / w0) / rho;
    i = function (t) {
      return [ ux0 + t * dx, uy0 + t * dy, w0 * Math.exp(rho * t * S) ];
    };
  }
  // 一般ケース
  else {
    var d1 = Math.sqrt(d2),
        b0 = (w1 * w1 - w0 * w0 + rho4 * d2) / (2 * w0 * rho2 * d1),
        b1 = (w1 * w1 - w0 * w0 - rho4 * d2) / (2 * w1 * rho2 * d1),
        r0 = Math.log(Math.sqrt(b0 * b0 + 1) - b0),
        r1 = Math.log(Math.sqrt(b1 * b1 + 1) - b1);
    S = (r1 - r0) / rho;
    i = function (t) {
      var s = t * S,
          coshr0 = cosh(r0),
          u = w0 / (rho2 * d1) * (coshr0 * tanh(rho * s + r0) - sinh(r0));
      return [
        ux0 + u * dx,
        uy0 + u * dy,
        w0 * coshr0 / cosh(rho * s + r0),
      ];
    };
  }

  // S は ρ-radian 弧長。これを ms に換算（d3 のデフォルト）
  i.duration = S * 1000 * rho / Math.SQRT2;
  return i;
}
```

- `t ∈ [0, 1]` を渡すと `[u_x(t), u_y(t), w(t)]` が返る。`t=0` で start、`t=1` で end。
- `i.duration` が「自然な所要時間 (ms)」。d3 は \(S \cdot 1000 \cdot \rho / \sqrt 2\) を使う。
  一部の地図 SDK は代わりに `speed`（screenfuls/sec）から `duration = 1000 \cdot S / speed`（概念上）を出す。
- 一般ケースでは \(w(t)\) が途中で \(w_0, w_1\) より大きくなり得る = ズームアウトの弧。`d2` が小さければ
  弧はほぼ平らになり、引きが消える。`w1 - w0`（ズーム差）も `b0, b1` に効くので「現在/目標ズーム両方を見る」も満たす。

### 2.3 派生・パラメータの実例

| 実装 | アルゴリズム | \(\rho\)（curve） | 速度・所要時間 |
|---|---|---|---|
| d3-interpolate `interpolateZoom` | van Wijk–Nuij そのまま | \(\sqrt 2 \approx 1.414\)（`.rho()` で変更可） | `S * 1000 * ρ / √2` ms |
| MapLibre GL JS / maplibre-native `flyTo` | van Wijk–Nuij 系 | 1.42 | `speed` 等で上限制御 |
| deck.gl `FlyToInterpolator` | 同上 | 1.5（`curve` 引数） | `speed` 引数 |

## 3. 「Google が本当に van Wijk を使っているか」の調査結果

**確証なし。** 状況証拠のみ:

- Google Maps SDK / Maps JavaScript API のドキュメント・リリースノートに van Wijk への言及は**ない**。
  `animateCamera` の経路アルゴリズムは非公開。
- 一方、`animateCamera` の観察挙動（§1）は van Wijk–Nuij の出力の性質と**完全に一致**する
  （遠距離 = 引いてから寄る / 近距離 = 引きなし / 現在 zoom と目標 zoom 両方に依存）。
- 「画面内の見かけ速度を一定に保つ」という発想自体は van Wijk が初出ではなく、Furnas & Bederson
  の "space-scale diagrams" (1995) などにも近い議論がある。van Wijk–Nuij はその**解析解を閉形式で**
  与えた点が価値で、以後 d3 / MapLibre / deck.gl などがこの系統の実装を採用している。
- Google の 3D 系 API（Photorealistic 3D Maps の `flyCameraTo` / `flyCameraAround`）は別物（3D 軌道）で、
  本件（2D `animateCamera`）とは無関係。

**結論**: Google が van Wijk **そのもの**かは断言できない。が、van Wijk–Nuij を実装すれば §1 の
観察された性質はすべて再現できる。完全一致は保証できないものの、パラメータ \(\rho\)（引きの大胆さ）
と所要時間スケールを調整すれば GoogleMap の体感にかなり寄せられる。「論文準拠だから同じ」ではなく
「同じ性質を持つ標準アルゴリズムを自前で持つ」という立て付けで進める。

## 4. OneNavi (`MapCameraState`) への適用方針

### 4.1 座標変換（GoogleMap ⇄ van Wijk の \(u, w\)）

van Wijk の \(u_x, u_y, w\) は「ズーム 0 のワールドピクセル空間」で表すと GoogleMap と素直に対応する。
GoogleMap は Web Mercator・タイル 256px なので:

```
fun lngToWorldX(lng: Double): Double = (lng + 180.0) / 360.0 * 256.0

fun latToWorldY(lat: Double): Double {
    val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
    val mercatorY = ln(tan(latRad) + 1.0 / cos(latRad))
    return (1.0 - mercatorY / Math.PI) / 2.0 * 256.0
}

// 逆変換（worldX, worldY → lat, lng）も用意する
```

- ビューポート幅をズーム 0 ワールドピクセルで測った値: `w = viewportWidthWorldPx0 = (mapViewWidthPx / density) / 2.0.pow(zoom)`
  - `density = context.resources.displayMetrics.density`。GoogleMap の zoom は ~256dp タイル基準なので
    px ではなく dp に直してから割るのが正確。ここを px のまま割ると弧の形が少しずれるが、\(\rho\) で吸収できる範囲。
  - `mapViewWidthPx` は `MapItem` の `AndroidView`（= `NavigationView`）の `width`。0 のとき（レイアウト前）は
    fallback として現状の単純 lerp に落とす。
- start: `p0 = [lngToWorldX(start.target.lng), latToWorldY(start.target.lat), w(start.zoom)]`
- end:   `p1 = [lngToWorldX(target.target.lng), latToWorldY(target.target.lat), w(target.zoom)]`
- 各 `t` で `[ux, uy, wt]` を得たら:
  - `center = LatLng(worldYToLat(uy), worldXToLng(ux))`
  - `zoom = log2((mapViewWidthPx / density) / wt)`

### 4.2 bearing / tilt

van Wijk は回転・傾きを扱わない。bearing/tilt は今まで通り**別チャンネルで線形 lerp**（`lerpAngle` / `lerp`）し、
fly-to の全体所要時間に合わせて動かす（必要なら `DecelerateInterpolator` を個別適用）。

### 4.3 所要時間

- `S`（弧長）が出るので、`durationMs = (S * 1000 * ρ / √2) * SPEED_SCALE` を基本にする。
  - `SPEED_SCALE` を companion 定数にして体感調整できるようにする。
- 上限 `MAX_FLY_TO_DURATION_MS` でクランプ（地球の裏側へ飛ぶときに何秒も待たされないように）。
- 既存の `panDurationMs` / `zoomDurationMs` 個別指定とは設計が変わる（fly-to は 1 本の経路）。
  `showRouteOverview` のような「明示 duration」は `durationMs` 指定で `S` ベースを上書きできるようにする。

### 4.4 退化ケース

- `d2 < EPSILON`（中心がほぼ同じ。例: `zoomIn` / `zoomOut` / `changeZoom`）→ d3 と同じく純粋な指数ズーム。
  実質「中心固定でズームだけイージング」になり、現状の `changeZoom` 相当。引きは出ない。
- start と target が完全一致（zoom も同じ）→ 何もしない。

### 4.5 イージング

- van Wijk の `t → [u, w]` は「弧長に対して」滑らか。`t` 自体を時間に対してどうマッピングするかは別問題。
  - シンプルには `t = elapsed / duration`（線形）。van Wijk 自身も基本は等速を想定。
  - GoogleMap っぽい「終端でぬるっと止まる」感を足したいなら `t = decelerate(elapsed / duration)` で最後だけ
    減速をかける（`CAMERA_DECELERATE_FACTOR` 流用）。両方試して決める。

### 4.6 実装スケッチ

```kotlin
// MapCameraState 内（擬似コード）
private fun flyCameraTo(
    target: CameraPosition,
    durationMs: Long? = null,
    onFinished: () -> Unit = {},
) {
    val map = googleMap ?: return
    val viewportWidthDp = (mapViewWidthPx / density)
    if (viewportWidthDp <= 0f) { animateCameraTo(target); return } // fallback

    val start = map.cameraPosition
    val p0 = doubleArrayOf(lngToWorldX(start.target.longitude), latToWorldY(start.target.latitude), viewportWidthDp / 2.0.pow(start.zoom.toDouble()))
    val p1 = doubleArrayOf(lngToWorldX(target.target.longitude), latToWorldY(target.target.latitude), viewportWidthDp / 2.0.pow(target.zoom.toDouble()))

    val path = vanWijkZoomPath(p0, p1, rho = CAMERA_FLY_TO_RHO)        // §2.2 の移植
    val totalMs = durationMs ?: (path.arcLengthSeconds * 1000 * CAMERA_FLY_TO_SPEED_SCALE).toLong().coerceAtMost(MAX_FLY_TO_DURATION_MS)
    val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

    cameraState = cameraState.copy(isFollowingMyLocation = false)
    cameraAnimator?.cancel()
    cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = totalMs
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            val t = easing.getInterpolation(anim.animatedValue as Float)   // §4.5 で線形 or 減速を選ぶ
            val (ux, uy, wt) = path.at(t)
            val center = LatLng(worldYToLat(uy), worldXToLng(ux))
            val zoom = (ln(viewportWidthDp / wt) / ln(2.0)).toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)
            val bearing = lerpAngle(start.bearing, target.bearing, t.toFloat())
            val tilt = lerp(start.tilt, target.tilt, t.toFloat())
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(center).zoom(zoom).bearing(bearing).tilt(tilt).build(),
                ),
            )
        }
        doOnEnd { onFinished() }
        start()
    }
}
```

`mapViewWidthPx` は `MapItem` から `MapCameraState` に渡すか（`attachMap` のタイミングで `view.width` を保持し、
レイアウト変更で更新）、`map.projection.visibleRegion` から推定する。`density` は `rememberMapCameraState` で
`LocalDensity` / `context` から取って渡す。

### 4.7 パラメータ（companion 定数の追加候補）

| 定数 | 意味 | 初期値の目安 |
|---|---|---|
| `CAMERA_FLY_TO_RHO` | \(\rho\)。引きの大胆さ。小さい = 引かない、大きい = 大胆に引く | `1.42` |
| `CAMERA_FLY_TO_SPEED_SCALE` | `S` 由来の所要時間に掛ける係数。大きい = ゆっくり | `1.0` 付近 |
| `MAX_FLY_TO_DURATION_MS` | 所要時間の上限 | `2000`〜`3000` |
| `CAMERA_FLY_TO_MIN_DISTANCE_PX` | これ未満の `d`（zoom0 worldpx）は退化ケース扱い | 小さい正の値 |

GoogleMap の体感に寄せる手順: まず `CAMERA_FLY_TO_RHO = 1.42` / `SPEED_SCALE = 1.0` で出して、
近距離・中距離・遠距離（同都市内 / 隣県 / 国をまたぐ）でそれぞれ GoogleMap の `animateCamera` と並べて見比べ、
\(\rho\) と `SPEED_SCALE` を詰める。

## 5. 影響範囲・段階導入

- `moveTo`（地点へ寄る）→ `flyCameraTo` に置換。近ければ自動で引きなしになる。
- `showRouteOverview`（bounds フィット）→ 算出した `target` を `flyCameraTo` に渡すだけ。
  ルート全体表示は普通「現在地 → 引いて全体」なので fly-to と相性が良い。
- `zoomIn` / `zoomOut` / `changeZoom` → 退化ケースに落ちるので実質現状維持。そのまま `flyCameraTo` 経由でも、
  既存 `animateCameraTo`（個別 duration）を残してもよい。
- 既存 `animateCameraTo`（pan/zoom 別 duration の単純 lerp）はレイアウト前の fallback 用に残す。

段階: ①van Wijk 経路計算の純関数 + 座標変換 + ユニットテスト【**実装済み** — `feature/map/.../camera/`】
→ ②`flyCameraTo` を `MapCameraState` に追加【**実装済み**】 → ③`moveTo` / `showRouteOverview` を切り替え【**実装済み**】 → ④パラメータ調整【未着手 — 実機での体感合わせ】。

## 6. 参考

- van Wijk, Nuij. *Smooth and efficient zooming and panning.* IEEE InfoVis 2003 — <https://vanwijk.win.tue.nl/zoompan.pdf>
- van Wijk, Nuij. *A model for smooth viewing and navigation of large 2D information spaces.* IEEE TVCG 2004 — <https://vanwijk.win.tue.nl/zptvcg.pdf>
- Mike Bostock, "Van Wijk & Nuij Zooming" (gist) — <https://gist.github.com/mbostock/600164>
- d3-interpolate `interpolateZoom` 実装 — <https://github.com/d3/d3-interpolate/blob/main/src/zoom.js>
- MapLibre GL JS `flyTo` — <https://maplibre.org/maplibre-gl-js/docs/API/classes/Map/#flyto>
