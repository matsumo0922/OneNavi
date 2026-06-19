# 05. System Architecture

## High-Level Architecture

```text
OneNavi
├─ Phone UI / Compose
│  ├─ Google Maps surface
│  ├─ route preview
│  ├─ navigating UI
│  └─ settings / billing
├─ Navigation Domain
│  ├─ route selection state
│  ├─ navigating state
│  ├─ guidance progress
│  ├─ congestion segments
│  └─ maneuver / lane / signboard models
├─ External API Integration
│  ├─ route search
│  ├─ turn-by-turn guidance
│  ├─ reroute
│  ├─ traffic information
│  └─ guide images
├─ Google Platform
│  ├─ Maps SDK
│  ├─ Routes API
│  └─ Cloud TTS
└─ Android Platform
   ├─ location
   ├─ foreground service
   ├─ notification
   └─ Android TTS fallback
```

## Data Flow

### 1. Route Search

```text
User input
  -> search / shared destination
  -> external API library
  -> route candidates
  -> Google Maps route overlay
  -> route preview UI
```

### 2. Active Guidance

```text
Location update
  -> guidance tracker
  -> external API guidance feed / route progress
  -> navigating state
  -> maneuver panel / route line / camera
  -> TTS queue
```

### 3. Reroute

```text
Off-route detection
  -> external API reroute
  -> route candidates update
  -> map overlay refresh
  -> user notification and TTS
```

## External Dependencies

| Service | Usage |
|---|---|
| Google Maps SDK | 地図表示、camera、route overlay |
| Google Routes API | 料金取得、route-compare 検証、補助的な polyline 再現 |
| Google Cloud TTS | 高品質な日本語音声 |
| 外部API ライブラリ | ルート検索、案内、交通情報、案内画像 |

## Key Design Decisions

### 1. 旧 provider へ戻さない

旧地図/ナビ provider の SDK、token、MCP、skill、設計ドキュメントを OneNavi の判断材料にしない。

### 2. 公開 repo に provider 実名を露出しない

外部API の事業者・製品名は 外部API 提供元表記に統一し、公開 repo には認証情報や具体名を置かない。

### 3. 地図 SDK と案内 provider を分離する

Google Maps は描画、外部API ライブラリは案内 source。UI model は provider 固有型を直接持たない。

### 4. Android Auto は再設計する

不完全な Auto 導線を公開せず、公開 API で地図 surface と案内 template が成立する形に整理してから復帰させる。
