# 05. System Architecture

## High-Level Architecture

```
┌─────────────────────────────────────────────────────┐
│                     OneNavi                           │
├──────────────┬──────────────┬───────────────────────┤
│   Phone UI   │   Auto UI    │     Shared Logic      │
│   (Compose)  │   (CarApp)   │                       │
│              │              │  ┌─────────────────┐  │
│              │              │  │ Voice Guidance   │  │
│              │              │  │  ├ 通称辞書      │  │
│              │              │  │  ├ 信号機案内    │  │
│              │              │  │  ├ 合流案内      │  │
│              │              │  │  ├ 速度連動      │  │
│              │              │  │  └ 日本語テンプレ │  │
│              │              │  ├─────────────────┤  │
│              │              │  │ Highway Panel    │  │
│              │              │  │  └ SA/PA 同梱DB  │  │
│              │              │  ├─────────────────┤  │
│              │              │  │ TTS Engine       │  │
│              │              │  │  ├ GCP TTS      │  │
│              │              │  │  └ Android TTS   │  │
│              │              │  └─────────────────┘  │
├──────────────┴──────────────┤                       │
│  Mapbox Maps SDK            │                       │
│  ├ ベクタータイル地図       │                       │
│  ├ 渋滞レイヤー            │                       │
│  ├ ルートライン描画        │                       │
│  └ 代替ルート半透明表示    │                       │
│                             │                       │
│  Mapbox Navigation SDK      │                       │
│  ├ ルーティング            │                       │
│  ├ 自動リルート            │                       │
│  ├ 車線案内データ          │                       │
│  ├ 信号機/一時停止/踏切    │                       │
│  ├ バナー（方面名）        │                       │
│  ├ 合流マニューバ          │                       │
│  ├ マップマッチング        │                       │
│  └ 経由地動的追加          │                       │
│                             │  Google Routes API    │
│  Mapbox Geocoding API       │  (料金計算のみ)       │
│  (検索)                     │                       │
└─────────────────────────────┴───────────────────────┘
```

## Data Flow

### 1. Route Calculation

```
User Input (intent share or search)
  │
  ▼
Mapbox Directions API
  request: driving-traffic, steps, banner, voice, annotations, geojson, depart_at
  │
  ▼
Route Response
  ├─ Map: GeoJSON route line overlay
  ├─ Steps: ターンバイターンデータ
  ├─ Lanes: 車線案内データ
  ├─ Traffic signals: 信号機/一時停止/踏切フラグ
  ├─ Banners: JCT/IC 名、方面名、シールド
  ├─ Annotations: 渋滞、制限速度、速度
  │
  ├─ [同梱 JSON 参照] SA/PA 位置データとルート形状を突合
  │     → 経路上の SA/PA を特定、通過時刻・距離を算出
  │
  ├─ [同梱 JSON 参照] 通称辞書で道路名を変換
  │
  └─ [Google Routes API] 料金計算（並行リクエスト）
  │
  ▼
UI: ルート概要、高速パネル、料金表示
```

### 2. Active Navigation

```
GPS Location Update
  │
  ▼
Mapbox Navigation SDK (trip progress)
  ├─ Map: カメラ位置・方向更新
  ├─ Route progress: 次のマニューバまでの距離/時間
  ├─ Banner update: 接近中の交差点データ
  ├─ Lane update: 推奨車線
  ├─ Traffic signal flags: 経路上の信号機
  │
  ▼
Voice Guidance (自前)
  1. 通称辞書で道路名変換
  2. 信号機フラグをカウントして信号機案内生成
  3. 合流マニューバ検出
  4. GPS 速度に応じた案内タイミング算出
  5. 自然な日本語テンプレートでテキスト生成
  │
  ▼
TTS Engine → 音声再生
```

### 3. ナビ中の経由地追加

```
地図上のタップ / 長押し
  │
  ▼
逆ジオコーディング（Mapbox Geocoding API）
  → 地点名取得
  │
  ▼
「経由地に追加しますか？」ダイアログ
  │ Yes
  ▼
新しい RouteOptions 構築（現在地 + 経由地 + 目的地）
  │
  ▼
setNavigationRoutes() でセッション継続のまま新ルートに切替
```

## External Dependencies

| Service | Usage | Free Tier | Billing Unit |
|---|---|---|---|
| Mapbox Maps SDK | 地図表示、渋滞レイヤー | 25,000 MAU/month | MAU |
| Mapbox Navigation SDK | ルーティング、リルート、トリップ進捗 | 100 MAU, 1,000 trips/month | MAU + trips |
| Mapbox Directions API | ルート計算 | (Nav SDK セッション中は無料) | Requests (session 外のみ) |
| Mapbox Geocoding API | 検索 | 100,000 requests/month | Requests |
| Google Cloud TTS (Chirp 3: HD) | 音声合成 | 1M chars/month | Characters |
| Google Routes API | 料金計算のみ | $200 credit/month | Requests |

## Static Data (リポジトリ同梱)

| Data | Source | File | Size | Update |
|---|---|---|---|---|
| SA/PA 位置 + 施設情報 | OSM (Overpass API で事前抽出) | `assets/sapa.json` | ~200KB | GitHub Actions 月 1 回 |
| 道路通称辞書 | OSM `alt_name` 抽出 + コミュニティ PR | `assets/nickname_dict.json` | ~50KB | 随時 |

**Overpass API はアプリのランタイムでは使用しない。** ビルドパイプライン（GitHub Actions）でのデータ抽出専用。

## Key Design Decisions

### 1. 音声案内テキスト生成がコアの差別化

通称辞書、信号機案内、合流案内、速度連動タイミング、自然な日本語テンプレートを自前で構築する。Mapbox のデフォルト音声テキストは使用しない。

### 2. 信号機データは Mapbox から取得

Mapbox Directions API の `intersections[].traffic_signal` フラグを使用。OSM Overpass API はアプリ内では使わない。カバレッジ不足時は距離ベースの案内にフォールバック。

### 3. 代替ルートのリアルタイム表示

Mapbox Navigation SDK が自動で代替ルートを探索（デフォルト 5 分間隔）。`MapboxRouteLineView` で代替ルートを半透明ラインとして地図上に表示。タップで切替可能。

### 4. SA/PA データはアプリ同梱

OSM から事前抽出した静的 JSON をリポジトリに含め、アプリにバンドルする。ランタイムでの外部 API 呼び出しを最小限にし、オフライン耐性とプライバシーを確保する。

### 5. Android Auto のパネル制限への対処

Auto のリスト上限 6 件はハード制約。Phone 側でフルパネルを提供し、Auto 側はページネーションで対応する。Auto 実行中でもスマホ側アプリが独立動作するため、ユーザーはスマホでフルパネルを確認可能。
