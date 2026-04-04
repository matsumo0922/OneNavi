# 15. Fake GPS Dev Tool

## 概要

OneNavi のナビゲーション機能をデバッグするための開発者向けツール。PC 上の Web アプリから Android 端末に対して Fake GPS を送信し、実際に走行しなくてもナビゲーションの動作確認を行えるようにする。

## アーキテクチャ

```
┌─────────────────────────┐      ADB Forward       ┌──────────────────────────┐
│   Web App (PC)          │     tcp:5556 ←→ 5556   │   Android (Debug Build)  │
│                         │                         │                          │
│  Google Maps JS API     │   POST /location        │  FakeGpsService          │
│  ├ 地図表示             │   {lat,lng,bearing,     │  ├ Ktor Server (CIO)     │
│  ├ ルート描画           │    speed,accuracy}      │  ├ LocationManager       │
│  ├ 地点指定 (click)     │ ──────────────────────→ │  │  .addTestProvider()    │
│  ├ 地点検索 (Places)    │                         │  │  .setTestProviderLoc() │
│  └ Roads API (snap)     │   GET /status           │  └ Mock Location 注入    │
│                         │ ←────────────────────── │                          │
│  Simulation Engine      │                         │  Mapbox Navigation SDK   │
│  ├ ルート再生           │                         │  ├ Enhanced Location     │
│  ├ GPX 再生             │                         │  ├ Map Matching          │
│  ├ 矢印キー操作         │                         │  └ Auto Reroute          │
│  ├ 倍速制御             │                         │                          │
│  └ 一時停止/再開        │                         │                          │
└─────────────────────────┘                         └──────────────────────────┘
```

## Web アプリ

### 技術スタック

- **フレームワーク**: Vite + TypeScript (単一ページ)
- **地図**: Google Maps JavaScript API
- **ルート探索**: Google Directions API (DirectionsService)
- **道路スナップ**: Google Roads API (snapToRoads)
- **地点検索**: Google Places Autocomplete
- **配置**: `dev-tools/fake-gps/`

### API キー管理

- `dev-tools/fake-gps/.env` に `VITE_GOOGLE_API_KEY` を設定
- プロジェクトの `local.properties` から手動コピー
- `.env` は `.gitignore` に追加

### UI レイアウト

全画面地図 + フローティングコントロールパネル。

```
┌──────────────────────────────────────────┐
│  [🔍 Search Box (Places Autocomplete)]   │
│┌──────────────────┐                      │
││ Waypoints        │                      │
││  📍 Start: ---   │                      │
││  📍 Via 1: ---   │  Google Map          │
││  📍 End:   ---   │                      │
││  [+ Add Via]     │  [ルートライン]      │
││──────────────────│  [現在地マーカー]    │
││ Controls         │  [ピン(ドラッグ可)]  │
││  ▶ ⏸ ⏹          │                      │
││  0.5x 1x 2x 5x  │                      │
││  10x             │                      │
││──────────────────│                      │
││ Status           │                      │
││  ● Connected     │                      │
││  📍 35.68, 139.76│                      │
││  🧭 Bearing: 45° │                      │
││  🚗 Speed: 30km/h│                      │
││──────────────────│                      │
││ Import           │                      │
││  [📂 Load GPX]   │                      │
│└──────────────────┘                      │
│                                          │
│              Arrow Keys: Move            │
│              Space: Pause/Resume         │
└──────────────────────────────────────────┘
```

### 地点指定方法

1. **地図クリック**: 地図上をクリックしてピンを配置、ドラッグで微調整
2. **検索ボックス**: Google Places Autocomplete で住所・施設名検索

### 操作モード

#### 1. ルート再生モード

- 出発地・経由地・目的地を指定してルート探索
- Google Directions API でルート polyline を取得
- polyline 上の点を 1Hz で順次送信
- 倍速ボタンで速度調整 (0.5x / 1x / 2x / 5x / 10x)
- ▶ (再生) / ⏸ (一時停止) / ⏹ (停止) で制御
- **再生中に矢印キーを押すと即座に手動モードに切り替わる**
- 再開ボタンで自動再生に復帰（最寄りのルート地点から再開）

#### 2. 手動操作モード (矢印キー)

- 矢印キーで自由に移動
- **方向基準**: 現在の heading (↑=前進、↓=後退、←→=左右旋回)
- **移動単位**: 速度ベース (30km/h 相当 ≈ 8m/秒)、倍速設定が適用される
- **道路スナップ**: Google Roads API で最寄りの道路にスナップ
- **ルートから外れることが可能** → Mapbox のリルート機能テスト用
  - 例: 直進のところを矢印キーで右折 → ルート逸脱 → リルート発火
- キー押しっぱなしで連続移動

#### 3. GPX 再生モード

- `.gpx` ファイルをドラッグ＆ドロップまたはファイル選択でインポート
- `<trk>/<trkseg>/<trkpt>` をパースして座標列として読み込み
- ルート再生モードと同じ操作 (再生/一時停止/倍速) が使える
- GPX 内のタイムスタンプがあればそれに基づいた速度で再生、なければ等間隔

### Bearing・Speed の計算

- Web 側で前回座標と現在座標から bearing (方位角) を算出
- speed は移動距離と経過時間から算出
- `POST /location` で `lat, lng, bearing, speed, accuracy` をまとめて送信

### 位置更新頻度

- **1Hz (1秒間隔)** を基本とする
- 倍速時も更新頻度は 1Hz のまま、1回あたりの移動距離を増やす

## Android 側

### 配置

- `composeApp/src/androidDebug/` に debug ビルド専用コードを配置
- release / billing ビルドには一切含まれない

### ファイル構成

```
composeApp/src/
├── androidDebug/
│   ├── AndroidManifest.xml              ← ACCESS_MOCK_LOCATION パーミッション追加
│   └── kotlin/me/matsumo/onenavi/debug/
│       ├── DevTools.kt                  ← FakeGpsServer を起動する実装
│       └── FakeGpsServer.kt             ← HTTP サーバー + Mock Location Provider
├── androidRelease/
│   └── kotlin/me/matsumo/onenavi/debug/
│       └── DevTools.kt                  ← no-op (何もしない)
└── androidBilling/
    └── kotlin/me/matsumo/onenavi/debug/
        └── DevTools.kt                  ← no-op (何もしない)
```

`DevTools.initialize(context)` は `OneNaviApplication.onCreate()` から全ビルドタイプ共通で呼ばれる。
ビルドタイプごとのソースセットで実装を切り替えるため、リフレクション不要。

### AndroidManifest.xml (debug)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.ACCESS_MOCK_LOCATION" />
</manifest>
```

debug マニフェストは自動的にメインマニフェストにマージされる。

### FakeGpsService

- **起動タイミング**: Application.onCreate (debug ビルドのみ)
- **HTTP サーバー**: Ktor Server (CIO エンジン)、ポート 5556
- **エンドポイント**:

| Method | Path | Body | 説明 |
|--------|------|------|------|
| POST | `/location` | `{"lat": 35.68, "lng": 139.76, "bearing": 45.0, "speed": 8.3, "accuracy": 5.0}` | Mock Location を設定 |
| GET | `/status` | - | サービスの状態を返す (`{"active": true, "lastLocation": {...}}`) |
| POST | `/stop` | - | Mock Provider を解除 |

- **Mock Location 設定処理**:
  1. `LocationManager.addTestProvider("gps", ...)` でテストプロバイダーを登録
  2. `LocationManager.setTestProviderEnabled("gps", true)` で有効化
  3. `LocationManager.setTestProviderLocation("gps", location)` で位置を設定
  4. `Location` オブジェクトに `lat, lng, bearing, speed, accuracy, time, elapsedRealtimeNanos` を設定

### Ktor Server 依存関係

`composeApp/build.gradle.kts` の `android { dependencies { } }` ブロックに debug 限定で追加:

```kotlin
android {
    dependencies {
        debugImplementation(libs.ktor.server.core)
        debugImplementation(libs.ktor.server.cio)
        debugImplementation(libs.ktor.server.content.negotiation)
        debugImplementation(libs.ktor.serialization.kotlinx.json)
    }
}
```

KMP モジュールでも `android {}` ブロック内の `dependencies` で Android 固有の依存を追加可能。`sourceSets` の `androidMain.dependencies` とは別経路だが、debug バリアント限定にできるメリットがある。

## 通信フロー

### セットアップ

```
1. Android 端末を USB 接続
2. 開発者オプション > 仮の現在地情報アプリ > OneNavi Debug を選択 (初回のみ)
3. cd dev-tools/fake-gps && npm run start
   → adb forward tcp:5556 tcp:5556 を自動実行
   → Vite dev server を起動
4. ブラウザで http://localhost:5173 にアクセス
5. Web アプリの Status が ● Connected になれば準備完了
```

### 位置送信シーケンス

```
Web App                              Android
  │                                    │
  │  POST /location                    │
  │  {"lat":35.68,"lng":139.76,        │
  │   "bearing":45.0,"speed":8.3,      │
  │   "accuracy":5.0}                  │
  │ ──────────────────────────────────→ │
  │                                    │ LocationManager.setTestProviderLocation()
  │                                    │        │
  │                                    │        ▼
  │                                    │ Mapbox NavigationLocationProvider
  │                                    │        │
  │                                    │        ▼
  │                                    │ Map Camera / Trip Progress / Reroute
  │                                    │
  │  200 OK {"success": true}          │
  │ ←────────────────────────────────── │
  │                                    │
  │  [1秒後]                           │
  │  POST /location (next point)       │
  │ ──────────────────────────────────→ │
  │         ...                        │
```

## npm scripts

```json
{
  "scripts": {
    "dev": "vite",
    "start": "node scripts/setup-adb.js && vite",
    "build": "tsc && vite build"
  }
}
```

`scripts/setup-adb.js`:
- `adb forward tcp:5556 tcp:5556` を実行
- デバイス未接続時はエラーメッセージを表示

## ディレクトリ構成

```
dev-tools/fake-gps/
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
├── .env                    ← VITE_GOOGLE_API_KEY (gitignore)
├── .env.example            ← テンプレート
├── .gitignore
├── scripts/
│   └── setup-adb.js        ← adb forward 自動化
└── src/
    ├── main.ts              ← エントリーポイント
    ├── map.ts               ← Google Maps 初期化・ルート描画
    ├── simulation.ts        ← シミュレーションエンジン
    ├── connection.ts        ← Android との HTTP 通信
    ├── gpx-parser.ts        ← GPX ファイルパーサー
    ├── controls.ts          ← UI コントロール (再生/停止/倍速)
    ├── keyboard.ts          ← 矢印キー操作
    ├── snap.ts              ← Roads API 道路スナップ
    ├── geo-utils.ts         ← bearing/distance 計算ユーティリティ
    └── style.css            ← スタイル
```

## 必要な Google API

既存の `GOOGLE_API_KEY` で以下の API が有効になっている必要がある:

| API | 用途 | 確認方法 |
|-----|------|----------|
| Maps JavaScript API | 地図表示 | 必須 |
| Directions API | ルート探索 | Web アプリでルート検索時に使用 |
| Roads API | 道路スナップ | 矢印キー操作時に使用 |
| Places API | 地点検索 | 検索ボックスで使用 |

Google Cloud Console で有効化が必要な場合がある。

## 前提条件

- Node.js 18+
- ADB がパスに通っている
- Android 端末が USB デバッグ有効
- Android 端末の開発者オプションで「仮の現在地情報アプリ」に OneNavi Debug を設定済み

## 制限事項

- USB 接続が必須 (ADB forward のため)
- 1台の端末のみ対応 (複数端末は未考慮)
- Android のみ対応 (iOS は対象外)
- Mock Location は GPS プロバイダーのみ偽装 (Network プロバイダーは対象外)
- Mapbox Navigation SDK の Enhanced Location が Mock Location を正しく認識するかは実装時に検証が必要
