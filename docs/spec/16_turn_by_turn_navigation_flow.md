# 16. ターンバイターンナビゲーション フロー詳細

## 概要

OneNavi のターンバイターン案内は、Google Maps の地図表示と外部ナビ API ライブラリの案内情報を組み合わせて実現する。

旧地図/ナビ provider の trip session、observer、route progress、voice instruction は参照しない。

## 1. ルート選択

```text
User destination
  -> external nav API library route search
  -> route candidates
  -> route preview state
  -> Google Maps overlay
```

## 2. ナビ開始

```text
RoutePreview
  -> selected route
  -> navigating state initialization
  -> foreground service start
  -> TTS engine preparation
  -> camera following
```

## 3. 走行中

```text
Location update
  -> off-route check
  -> guidance progress update
  -> maneuver/lane/signboard state update
  -> route line consumed segment update
  -> TTS queue update
```

## 4. リルート

```text
Off route
  -> external nav API library reroute
  -> active route replacement
  -> map overlay refresh
  -> notification / TTS
```

## 5. 到着

```text
Arrival detected
  -> foreground service stop
  -> TTS stop
  -> arrival state
  -> Browsing return
```

## 6. 実装上の注意

- provider 固有型を UI state に直接流さない。
- route geometry と guidance point は OneNavi の domain model に変換してから扱う。
- TTS は Cloud TTS と Android TTS fallback の両方を維持する。
- Android Auto は別途 `docs/spec/14_android_auto_google_maps_investigation.md` と Phase 4 設計に従う。
