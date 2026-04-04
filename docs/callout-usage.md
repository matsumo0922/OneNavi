# 吹き出し配置システム使用ガイド

## 概要

`MapCalloutPositioner` は地図上の吹き出し（CallOut）を **ビューポート追従 + 重なり回避** 付きで配置するための汎用ユーティリティ。ルートプレビューの所要時間表示だけでなく、ナビゲーション中の交差点案内吹き出しなど、任意の座標列に紐づく吹き出しに利用できる。

## 基本フロー

```
1. 座標列を用意する（ルートの geometry、交差点の位置リスト等）
2. CalloutSize を定義する（吹き出しの幅・高さ px）
3. MapCalloutPositioner.computePositions() を呼ぶ
4. 返却された List<Point?> を ViewAnnotation に渡す
```

## API

### `MapCalloutPositioner.computePositions()`

```kotlin
fun computePositions(
    mapboxMap: MapboxMap,
    geometries: List<List<RoutePoint>>,  // 吹き出しごとの座標列
    calloutSize: CalloutSize,            // 吹き出しの推定サイズ (px)
    marginPx: Double = 16.0,            // 矩形間のマージン (px)
    offsetCandidates: DoubleArray = ..., // 可視区間上の試行位置
): List<Point?>
```

- **入力**: 座標列のリスト。各リストが1つの吹き出しに対応
- **出力**: `Point?` のリスト。ビューポート外の場合は `null`
- **内部動作**:
  1. カメラ状態からビューポート境界を取得
  2. 各座標列の可視区間を抽出
  3. `offsetCandidates` の順に配置候補を生成
  4. `ScreenRect` の矩形交差判定で重なりを検査
  5. 重ならない位置が見つかればそこに配置、全て重なれば中央にフォールバック

### `RouteCalloutStyle`

```kotlin
@Immutable
data class RouteCalloutStyle(
    val backgroundColor: Int,  // ARGB
    val textColor: Int,        // ARGB
    val shadowColor: Int,      // ARGB
    val textSizeSp: Float,
)
```

ファクトリメソッドで用途別スタイルを生成:
- `RouteCalloutStyle.forRoute(isPrimary: Boolean)` — ルートプレビュー用

## 使用例 1: ルートプレビュー（既存実装）

```kotlin
// 1. サイズ定義
private val ROUTE_CALLOUT_SIZE = CalloutSize(
    widthPx = 180.0,
    heightPx = 80.0,
)

// 2. カメラ変更時にリアルタイム再計算
val cameraChangeCancelable = view.mapboxMap.subscribeCameraChanged {
    calloutPoints = MapCalloutPositioner.computePositions(
        mapboxMap = view.mapboxMap,
        geometries = routeResults.map { it.item.geometry },
        calloutSize = ROUTE_CALLOUT_SIZE,
    )
}

// 3. スタイル定義
val primaryStyle = remember { RouteCalloutStyle.forRoute(isPrimary = true) }
val secondaryStyle = remember { RouteCalloutStyle.forRoute(isPrimary = false) }

// 4. 描画（primary を最後に描画して z-order 最上位に）
routeResults.forEachIndexed { index, result ->
    if (index != selectedRouteIndex) {
        calloutPoints.getOrNull(index)?.let { point ->
            HomeMapRouteCallout(
                point = point,
                routeResult = result,
                isPrimary = false,
                style = secondaryStyle,
                onClick = { onRouteSelected(index) },
            )
        }
    }
}
calloutPoints.getOrNull(selectedRouteIndex)?.let { point ->
    HomeMapRouteCallout(
        point = point,
        routeResult = routeResults[selectedRouteIndex],
        isPrimary = true,
        style = primaryStyle,
        onClick = { },
    )
}
```

## 使用例 2: ナビゲーション交差点吹き出し（実装イメージ）

```kotlin
// 1. 交差点座標をリストに変換（各交差点に1座標 → 1要素のリスト）
val turnGeometries = turnInstructions.map { turn ->
    listOf(RoutePoint(latitude = turn.latitude, longitude = turn.longitude))
}

// 2. 小さめの吹き出しサイズ
val TURN_CALLOUT_SIZE = CalloutSize(
    widthPx = 120.0,
    heightPx = 48.0,
)

// 3. 配置位置を計算（重なり回避はルートと同じロジック）
val turnCalloutPoints = MapCalloutPositioner.computePositions(
    mapboxMap = mapboxMap,
    geometries = turnGeometries,
    calloutSize = TURN_CALLOUT_SIZE,
)

// 4. 独自スタイルを定義
val turnStyle = RouteCalloutStyle(
    backgroundColor = 0xFF1B5E20.toInt(),  // Dark green
    textColor = 0xFFFFFFFF.toInt(),
    shadowColor = 0x40000000,
    textSizeSp = 12f,
)

// 5. ViewAnnotation で描画
turnCalloutPoints.forEachIndexed { index, point ->
    point?.let {
        // 独自の交差点吹き出し Composable を使用
        NavTurnCallout(
            point = it,
            instruction = turnInstructions[index],
            style = turnStyle,
        )
    }
}
```

## ScreenRect を使った独自の重なり検査

`ScreenRect` は汎用的な矩形型として、吹き出し以外の重なり検査にも使える:

```kotlin
val rectA = ScreenRect(left = 0.0, top = 0.0, right = 100.0, bottom = 50.0)
val rectB = ScreenRect(left = 80.0, top = 30.0, right = 200.0, bottom = 80.0)

if (rectA.overlaps(rectB)) {
    // 重なっている
}
```

## ファイル構成

```
feature/home/map/
├── util/
│   └── MapCalloutPositioner.kt   # 汎用配置計算 + CalloutSize + ScreenRect
├── components/
│   ├── HomeMapRouteCallout.kt    # ルート吹き出し Composable
│   └── RouteCalloutStyle.kt      # 吹き出しスタイル定義
├── HomeMapNavigationManager.kt   # reorderedRoutes() でルート並び替え一元化
└── HomeMapsMapEffectContent.kt   # 配置ロジックは Positioner に委譲
```
