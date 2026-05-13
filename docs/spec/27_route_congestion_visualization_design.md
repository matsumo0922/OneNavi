# 27. ルート渋滞情報の UI / 音声 設計

> **作成日:** 2026-05-13
> **ステータス:** 設計（実装未着手 — UC1/UC2 は P1 完了後すぐ着手可、UC3/UC4 はナビ機能本体に依存）
> **対象:** spec 26 で取得口を作った `CongestionSegment` を使って、4 つの想定ユースケース (UC1〜UC4) を満たす UI / 音声を実装する。
> **前提:** spec 26 が定義した `core/model.CongestionSegment` が `RouteItem.congestionSegments` / `RouteDetail.congestionSegments` から得られている状態。
> **関連:** `26_route_congestion_design.md`（取得口）、`13_navigation_screen_design.md`（ナビ画面側、UC3/4 の足回り）

---

## 0. スコープと前提

### 0.1 本書がやること

`CongestionSegment` 1 セットから 4 ユースケース全部の見た目 / 音声を作る統一設計を出す:

| # | ユースケース | 描画 / 出力先 | 依存 |
|---|---|---|---|
| UC1 | ルートプレビューの polyline 渋滞色分け | feature/home の RoutePreview Mapbox | spec 26 P1 完了 |
| UC2 | 渋滞 1 直線ストリップ UI（横帯） | feature/home の RoutePreview 下部パネル | spec 26 P1 完了 |
| UC3 | ナビ中の地図 polyline 渋滞色分け | feature/navigation の地図 | UC1 + ナビ機能本体 |
| UC4 | 渋滞の音声案内 | TTS（Cloud TTS Chirp 3 HD or Android TTS） | ナビ機能本体 |

すべて同じ `ImmutableList<CongestionSegment>` から各 UI が必要なフィールドだけ射影する。**新しいモデルは作らない**。spec 26 §2.2 で確定済の `CongestionSegment` がそのまま 4 UC を賄う。

### 0.2 本書がやらないこと

- ナビ中の渋滞情報リフレッシュ戦略（再検索間隔、サーバプッシュ受信、現在地リンク解決） → 別設計（spec 13 / 16 に追記する想定）。本書は静的な `CongestionSegment` を絵 / 音にするところまで。
- 音声案内のスケジューラ全体（他の TBT 案内との優先制御、被り回避） → spec 13 / 24 で扱う既存の発話 queue に乗せる前提。本書では「渋滞音声を生成して queue に投げる」までを定義。
- TTS エンジンの選定・差し替え → 既存の TTS 抽象に従う。
- 「前方 N 区間インジケータ」UI（dashboard 風の小型表示）→ 将来。`forward_status: IntArray` が proto に取り込まれてから別設計。

---

## 1. 共通設計

### 1.1 severity → 色テーブル

`core/ui` に `internal object CongestionPalette` を置き、4 UC が共有する。Material3 既定色を基準に微調整:

```kotlin
@Immutable
internal object CongestionPalette {
    val Free: Color = Color(0xFF4CAF50)        // 緑（流れている）
    val Crowded: Color = Color(0xFFFF9800)     // オレンジ（混雑）
    val Jam: Color = Color(0xFFF44336)         // 赤（渋滞）
    val Unknown: Color = Color(0xFF9E9E9E)     // グレー（情報なし）

    fun colorFor(severity: CongestionSeverity): Color = when (severity) {
        CongestionSeverity.NORMAL -> Free
        CongestionSeverity.SLOW -> Crowded
        CongestionSeverity.TRAFFIC_JAM -> Jam
        CongestionSeverity.UNKNOWN -> Unknown
    }
}
```

注意: spec 26 §2.2 のとおり、`congestionSegments` には `NORMAL` 区間は基本含まれない（ライブラリ側で `Smooth` 区間を除外）。`Free` 色は将来「全ルート流れ表示」を入れたくなったとき用に温存。

### 1.2 渋滞区間の polyline 切り出しヘルパ

UC1 / UC3 が共通で使う純関数。`CongestionSegment` の累積距離からルート全長に対する比率を作り、Mapbox の `line-gradient` 式の stop 列に変換する:

```kotlin
@Immutable
internal data class CongestionGradientStop(
    val position: Float,    // 0f..1f, ルート始点からの正規化位置
    val color: Color,
)

internal fun buildCongestionGradientStops(
    segments: ImmutableList<CongestionSegment>,
    totalDistanceMeters: Double,
    baseColor: Color,
): ImmutableList<CongestionGradientStop> {
    if (segments.isEmpty() || totalDistanceMeters <= 0.0) {
        return persistentListOf(
            CongestionGradientStop(0f, baseColor),
            CongestionGradientStop(1f, baseColor),
        )
    }
    val sorted = segments.sortedBy { it.startDistanceMeters }
    return buildList {
        add(CongestionGradientStop(0f, baseColor))
        for (segment in sorted) {
            val start = (segment.startDistanceMeters / totalDistanceMeters)
                .coerceIn(0.0, 1.0).toFloat()
            val end = (segment.endDistanceMeters / totalDistanceMeters)
                .coerceIn(start.toDouble(), 1.0).toFloat()
            add(CongestionGradientStop(start, CongestionPalette.colorFor(segment.severity)))
            add(CongestionGradientStop(end, baseColor))
        }
        add(CongestionGradientStop(1f, baseColor))
    }.toImmutableList()
}
```

置き場: `core/ui/src/.../congestion/CongestionGradient.kt`。純関数なので unit test しやすい（fixture: `RouteItem` の `congestionSegments` + `geometryDistanceMeters` → 期待 stop 列）。

### 1.3 「ルート全長」をどこから取るか

`buildCongestionGradientStops` には `totalDistanceMeters` が要る。OneNavi の `RouteItem` / `RouteDetail` には既に距離 (`distanceMeters`) フィールドがある（`feat: Add MapPolylineStyle ...` の前提）。これを渡す。

> ライブラリ側 `RouteGuidance.totalDistanceMeters` が無ければ Phase 1 で同時露出する。

### 1.4 Mapbox の line-gradient 式に必要な前提

- `LineLayer` の `lineMetrics = true` を立てる（既定 false。立てないと `lineGradient` が機能しない）。
- 既存の `MapPolylineStyle` を拡張して「渋滞色分け mode」を追加する。
- 1 ルート 1 LineLayer のままで色分けできる（追加レイヤー不要 → 描画コスト無し）。

---

## 2. UC1 — ルートプレビューの polyline 渋滞色分け

### 2.1 範囲

`feature/home` の RoutePreview 画面で、地図上に表示中の各候補ルート polyline に渋滞色を被せる。

### 2.2 実装方針

1. `MapPolylineStyle` に `congestionStops: ImmutableList<CongestionGradientStop>?` を追加。
2. `home/.../map/MapEffect`（or 等価）で `RouteItem.congestionSegments` から `buildCongestionGradientStops` を呼んで `MapPolylineStyle.congestionStops` に詰める。
3. `MapEffect` 内の `LineLayer` 構築で `lineMetrics(true)` + `lineGradient(<expression>)` を組み立てる。stop 列は `interpolate(linear, lineProgress, [stops])` 形式。

### 2.3 擬似コード（Mapbox style 構築）

```kotlin
val gradient = interpolate {
    linear()
    lineProgress()
    stops.forEach { stop ->
        stop(stop.position.toDouble()) { color(stop.color.toArgb().toLong()) }
    }
}
val lineLayer = lineLayer("congestion-line", "route-source") {
    lineMetrics(true)
    lineGradient(gradient)
    lineWidth(routeLineWidthDp.value.toDouble())
    lineCap(LineCap.ROUND)
}
```

### 2.4 候補ルート 4 本同時表示の扱い

ユーザー画像のように複数候補を同時表示するときは、各ルートの LineLayer に別の gradient を設定する。色分けは推奨ルートのみ ON、他候補は disabled-style（既存 `MapPolylineStyle.opacity` で薄くする）も検討。**詳細は実装時に意匠と相談**。本書はデータの渡し方だけ確定する。

### 2.5 断続渋滞表示

`isIntermittent = true` の区間は **base-color polyline + 上に dasharray の細線**を別レイヤーで重ねる（line-gradient だけでは点線は表現できない）。優先度低、初版は単色帯で OK。

---

## 3. UC2 — 渋滞 1 直線ストリップ UI

### 3.1 範囲

RoutePreview の下部、各候補ルートカードの中に「ルート始点 → ゴール」を 1 本の横帯で示し、渋滞区間に色をつける。N 社のナビアプリの「混雑バー」相当。

### 3.2 実装方針

`core/ui` に共通 Composable を置く。Compose Canvas 1 個で完結:

```kotlin
@Composable
internal fun CongestionStripBar(
    segments: ImmutableList<CongestionSegment>,
    totalDistanceMeters: Double,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Canvas(modifier = modifier) {
        // 背景
        drawRect(color = baseColor)
        // 渋滞 segments
        if (totalDistanceMeters <= 0.0) return@Canvas
        segments.forEach { segment ->
            val startX = (segment.startDistanceMeters / totalDistanceMeters * size.width)
                .toFloat().coerceIn(0f, size.width)
            val endX = (segment.endDistanceMeters / totalDistanceMeters * size.width)
                .toFloat().coerceIn(startX, size.width)
            drawRect(
                color = CongestionPalette.colorFor(segment.severity),
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, size.height),
            )
        }
    }
}
```

### 3.3 ラベル付きバージョン

start / goal の地点名 + 渋滞 head/tail の地点名を上下に置きたい場合は、`Layout` で位置を計算して `Text` を散らす。例:

```
[出発]      [印西]   [原木]                          [銚子]
└─────────────███████████─────────────────────────┘
```

地点名は `headPointName` / `tailPointName` から、フォールバックで「渋滞」とだけ表示。

### 3.4 アクセシビリティ

`CongestionStripBar` の親 `Modifier` に `semantics { contentDescription = "渋滞 X 件、合計 Y km" }` を付ける。色覚に依存しない情報を残す。

---

## 4. UC3 — ナビ中の地図 polyline 渋滞色分け

### 4.1 範囲

ナビゲーション画面で、現在地→ゴールまでの残ルートに UC1 と同じ渋滞色を被せる。ナビ機能本体（spec 13 / 16 / 22 / 24）に依存。

### 4.2 UC1 との差分

データは UC1 と完全同一 (`CongestionSegment[]`)。違いは:

1. **現在地より後ろを clip する**: 現在地の累積距離 `currentDistanceMeters` を計算し、`segment.endDistanceMeters > currentDistanceMeters` の segment のみ採用。`segment.startDistanceMeters < currentDistanceMeters` の segment は `startDistanceMeters = currentDistanceMeters` に再正規化。
2. **stops の正規化を残ルート長で再計算**: `buildCongestionGradientStops` の `totalDistanceMeters` に「残距離」を渡す。あるいは別 helper を切る。

### 4.3 実装方針

```kotlin
internal fun buildCongestionGradientStopsForNavigation(
    segments: ImmutableList<CongestionSegment>,
    currentDistanceMeters: Double,
    totalDistanceMeters: Double,
    baseColor: Color,
): ImmutableList<CongestionGradientStop> {
    val remaining = (totalDistanceMeters - currentDistanceMeters).coerceAtLeast(0.0)
    val trimmed = segments
        .filter { it.endDistanceMeters > currentDistanceMeters }
        .map { segment ->
            // segment の start を currentDistanceMeters でクリップした扱いで使う
            val effectiveStart = (segment.startDistanceMeters - currentDistanceMeters).coerceAtLeast(0.0)
            val effectiveEnd = (segment.endDistanceMeters - currentDistanceMeters).coerceAtLeast(effectiveStart)
            // CongestionGradientStop 構築のため再びコピー
            segment.copy(
                startDistanceMeters = effectiveStart,
                endDistanceMeters = effectiveEnd,
            )
        }
        .toImmutableList()
    return buildCongestionGradientStops(trimmed, remaining, baseColor)
}
```

ナビ機能実装時に既存の MapPolylineStyle / LineLayer 構築フローへ差し込む。

### 4.4 ナビ中の渋滞情報リフレッシュ

route バイナリ埋め込みデータはルート計算時点のスナップショット。ナビ中の更新戦略は本書のスコープ外（spec 13 / 16 に追記）。最低限「定期再検索」で対応する想定。

---

## 5. UC4 — 渋滞の音声案内

### 5.1 範囲

ナビ中、前方の渋滞 segment に近づいたら 1 度だけ「この先、◯◯ＩＣから△△ＳＡまで約Xkm渋滞しています。所要時間はおよそY分です。」と発話する。

### 5.2 必要なデータ

`CongestionSegment` 1 件から完結:

| 部分 | ソース |
|---|---|
| `この先` | 固定文言 |
| `◯◯ＩＣ` | `headPointName` (null なら「この先」で代用) |
| `△△ＳＡ` | `tailPointName` (null なら「先方」で代用) |
| `約Xkm` | `congestionDistanceMeters / 1000`（小数点 1 位、四捨五入） |
| `Y分` | `transitMinutes`（null なら所要時間なしフレーズ） |

路線番号 (`headRoadNumbering` 等) は当面使わない（音声で「E14 京葉道路」等を言うと冗長）。将来「先頭の路線番号 → 路線名辞書」を整備したら追加。

### 5.3 発話トリガ

```kotlin
internal class CongestionVoiceTrigger(
    private val tts: TtsClient,
) {
    private companion object {
        const val ANNOUNCE_DISTANCE_MIN_M = 800
        const val ANNOUNCE_DISTANCE_MAX_M = 1500
    }

    private val announcedKeys: MutableSet<String> = mutableSetOf()

    suspend fun onProgress(
        currentDistanceMeters: Double,
        segments: ImmutableList<CongestionSegment>,
    ) {
        for (segment in segments) {
            val distanceToStart = segment.startDistanceMeters - currentDistanceMeters
            if (distanceToStart !in ANNOUNCE_DISTANCE_MIN_M.toDouble()..ANNOUNCE_DISTANCE_MAX_M.toDouble()) {
                continue
            }
            val key = announcementKey(segment)
            if (key in announcedKeys) continue
            tts.speak(buildScript(segment))
            announcedKeys += key
        }
    }

    private fun announcementKey(segment: CongestionSegment): String =
        "${segment.startPolylinePointIndex}-${segment.endPolylinePointIndex}-${segment.severity.name}"

    private fun buildScript(segment: CongestionSegment): String {
        val head = segment.headPointName ?: "この先"
        val tail = segment.tailPointName ?: "先方"
        val km = "%.1f".format(segment.congestionDistanceMeters / 1000)
        val minutes = segment.transitMinutes
        return if (minutes == null) {
            "この先、${head}から${tail}まで約${km}km渋滞しています。"
        } else {
            "この先、${head}から${tail}まで約${km}km渋滞しています。所要時間はおよそ${minutes}分です。"
        }
    }
}
```

### 5.4 既存 TBT 発話との調停

ナビ機能本体の発話 queue（spec 13 / 24 の `AnnouncementScheduler` 想定）に渋滞案内も乗せる。優先度は通常案内（交差点 N m 前）より低めに設定。連続再発話を避けるため `announcedKeys` で 1 segment 1 回までに dedupe。

ルート再検索が走ったら `announcedKeys` をリセット（新しい `CongestionSegment[]` は新しい polyline index を持つので key 衝突しないが、安全側で明示クリア）。

### 5.5 渋滞傾向 / 断続渋滞の発話への反映（将来）

`segment.trend == INCREASING` のとき「現在も渋滞は伸びています」を append、`isIntermittent = true` のとき「断続的に流れています」を append、等。proto に `trend_scale` / `is_intermittent` が入ってから対応。

---

## 6. テスト観点

| 観点 | 場所 |
|---|---|
| `CongestionPalette.colorFor` の網羅 | `core/ui` unit test |
| `buildCongestionGradientStops` の境界値（segments 空 / 1件 / 重複 / 範囲外 / totalDistance=0） | `core/ui` unit test |
| `buildCongestionGradientStopsForNavigation` の trim 正しさ（current が segment 内 / 後ろ / 前） | `core/ui` unit test |
| `CongestionStripBar` の preview レンダー | Compose preview / screenshot test |
| `CongestionVoiceTrigger.buildScript` の文言（head/tail null 補完、minutes null フレーズ、km フォーマット） | `feature/navigation` unit test（実装時） |
| `CongestionVoiceTrigger.onProgress` の dedupe（同 segment が複数 tick で trigger されないか） | 同上 |

---

## 7. 実装順序

```
spec 26 P1 完了
    ↓
T-1 (UC共通): core/ui に CongestionPalette + buildCongestionGradientStops 追加 (純関数 + unit test)
    ↓
T-2 (UC1): MapPolylineStyle 拡張 + RoutePreview の MapEffect で line-gradient 適用
T-3 (UC2): CongestionStripBar Composable + RoutePreview のカードに組み込み
    ↓
（ナビ機能本体実装）
    ↓
T-4 (UC3): buildCongestionGradientStopsForNavigation + ナビ画面の MapPolylineStyle 適用
T-5 (UC4): CongestionVoiceTrigger + ナビ機能の発話 queue に統合
```

T-1 / T-2 / T-3 は spec 26 P1 完了直後に着手可能。T-4 / T-5 はナビ機能本体待ち（spec 13 / 16 と同期）。

---

## 8. 関連ファイル（実装時に触るもの）

**T-1 / T-2 / T-3（UC1, UC2）**

- `core/ui/src/.../congestion/CongestionPalette.kt`（新設）
- `core/ui/src/.../congestion/CongestionGradient.kt`（新設、純関数）
- `core/ui/src/.../congestion/CongestionStripBar.kt`（新設）
- `feature/home/src/.../map/MapPolylineStyle.kt`（拡張: `congestionStops` 追加）
- `feature/home/src/.../map/MapEffect.kt`（gradient 構築・適用）
- `feature/home/src/.../route/RouteCardContent.kt` 等（StripBar 組み込み）

**T-4 / T-5（UC3, UC4）**

- `core/ui/src/.../congestion/CongestionGradient.kt`（`...ForNavigation` 追加）
- `feature/navigation/...`（未着手のナビ機能本体）
- 発話 queue / TTS クライアント（spec 24 系の既存抽象）
