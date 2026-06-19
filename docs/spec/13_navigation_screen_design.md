# 13. Navigation Screen Design

## Overview

本ドキュメントは、Google Maps surface と外部API ライブラリを前提にしたナビ画面設計の正本である。

旧地図/ナビ provider の observer、route line、trip session、通知、SDK built-in UI は参照しない。

## UI Scope

- route preview
- maneuver panel
- trip progress card
- lane guidance
- signboard / guide image
- congestion-aware route line
- reroute notification
- arrival screen
- Cloud TTS / Android TTS 状態表示

## State Model

```text
Browsing
  -> Search
  -> RoutePreview
  -> Navigating
  -> Arrival
  -> Browsing
```

状態固有データは `StateFlow` で分離し、毎秒更新される progress が画面全体を再 compose しないようにする。

## Architecture

```text
feature/map
  ├─ Google Maps rendering
  ├─ camera control
  └─ overlay rendering

core/navigation
  ├─ route candidate model
  ├─ navigating state
  ├─ guidance progress
  ├─ reroute coordination
  └─ TTS scheduling

external api integration
  ├─ route search
  ├─ guidance feed
  ├─ congestion
  └─ guide images
```

## Rendering Responsibilities

| Area | Responsibility |
|---|---|
| Google Maps | map surface、camera、marker、polyline overlay |
| OneNavi UI | maneuver、lane、signboard、trip progress、controls |
| 外部API ライブラリ | route/guidance/congestion/source data |

## Foreground And Notification

OneNavi 側で foreground service と notification を管理する。SDK built-in の trip notification には依存しない。

## Android Auto

Android Auto は Phase 4 で再設計する。スマホ版の状態 model を再利用しつつ、Auto 側は公開 API の template と map surface の制約に合わせる。
