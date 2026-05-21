# 15. Fake GPS Dev Tool

## 概要

OneNavi のナビゲーション機能をデバッグするための開発者向けツール。PC 上の Web アプリから Android Emulator に対して Fake GPS を送信し、実際に走行しなくてもナビゲーションの動作確認を行えるようにする。

位置情報は Emulator の `adb emu geo` 経由で **OS レベルに注入** するため、アプリ側に Mock 用のコードを一切組み込む必要がない（旧方式の実機向けアプリ内 HTTP サーバーは廃止）。

## アーキテクチャ

```
┌─────────────────────────┐                          ┌──────────────────────────┐
│   Web App (PC)          │                          │   Android Emulator       │
│   Vite dev server :5173 │                          │                          │
│                         │  POST /location          │  OS 位置情報サブシステム │
│  Google Maps JS API     │  {lat,lng,bearing,       │  (adb emu geo nmea で    │
│  ├ 地図表示             │   speed,accuracy,        │   GPS 受信機を擬似)      │
│  ├ ルート描画           │   altitude}              │        │                 │
│  ├ 地点指定 (click)     │                          │        ▼                 │
│  ├ 地点検索 (Places)    │  ┌────────────────────┐  │  FusedLocationProvider   │
│  └ Roads API (snap)     │  │ Vite middleware    │  │  / LocationManager       │
│                         │  │ (emulatorGeoBridge)│  │        │                 │
│  Simulation Engine      │──│  NMEA 生成         │──│        ▼                 │
│  ├ ルート再生           │  │  adb emu geo nmea  │  │  ナビ SDK                │
│  ├ GPX 再生             │  └────────────────────┘  │  ├ Enhanced Location     │
│  ├ 矢印キー操作         │   adb (同一 PC 上)       │  ├ Map Matching          │
│  ├ 倍速制御             │                          │  └ Auto Reroute          │
│  └ 一時停止/再開        │                          │                          │
└─────────────────────────┘                          └──────────────────────────┘
```

ブラウザは `adb` のようなシェルコマンドを直接実行できない。そのため Vite dev server に
HTTP ブリッジ（`vite.config.ts` の `emulatorGeoBridge` プラグイン）を同居させ、Web アプリ
からの `POST /location` を NMEA 文へ変換し `adb emu geo nmea` で Emulator に注入する。
ブリッジは dev server と同一オリジンなので CORS も別プロセスも不要。

## Web アプリ

### 技術スタック

- **フレームワーク**: Vite + TypeScript (単一ページ)
- **地図**: Google Maps JavaScript API
- **ルート探索**: Google Directions API (DirectionsService)
- **道路スナップ**: Google Roads API (snapToRoads)
- **地点検索**: Google Places Autocomplete
- **GPS 注入ブリッジ**: Vite dev server middleware (`adb emu geo nmea`)
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

Status の `● Connected` は、ブリッジが `adb devices` で Emulator を検出できている時のみ点灯する。

### 地点指定方法

1. **地図クリック**: 地図上をクリックしてピンを配置、ドラッグで微調整
2. **検索ボックス**: Google Places Autocomplete で住所・施設名検索

### 操作モード

#### 1. ルート再生モード

- 出発地・経由地・目的地を指定してルート探索
- Google Directions API でルート polyline を取得
- polyline 上の点を 1Hz で順次送信
- 倍速ボタンで速度調整 (0.5x / 1x / 2x / 5x / 10x / 20x)
- ▶ (再生) / ⏸ (一時停止) / ⏹ (停止) で制御
- **再生中に矢印キーを押すと即座に手動モードに切り替わる**
- 再開ボタンで自動再生に復帰（最寄りのルート地点から再開）

#### 2. 手動操作モード (矢印キー)

- 矢印キーで自由に移動
- **方向基準**: 現在の heading (↑=前進、↓=後退、←→=左右旋回)
- **移動単位**: 速度ベース (30km/h 相当 ≈ 8m/秒)、倍速設定が適用される
- **道路スナップ**: Google Roads API で最寄りの道路にスナップ
- **ルートから外れることが可能** → リルート機能テスト用
  - 例: 直進のところを矢印キーで右折 → ルート逸脱 → リルート発火
- キー押しっぱなしで連続移動

#### 3. GPX 再生モード

- `.gpx` ファイルをドラッグ＆ドロップまたはファイル選択でインポート
- `<trk>/<trkseg>/<trkpt>` をパースして座標列として読み込み
- ルート再生モードと同じ操作 (再生/一時停止/倍速) が使える
- GPX 内のタイムスタンプがあればそれに基づいた速度で再生、なければ等間隔

### Bearing・Speed の計算と注入

- Web 側の Simulation Engine が前回座標と現在座標から bearing (方位角) と speed を算出
- `POST /location` で `lat, lng, bearing, speed, accuracy, altitude` をまとめて送信
- ブリッジは受け取った値から NMEA 文を生成して注入する:
  - **`$GPGGA`**: 位置・高度・衛星数で位置を fix させる
  - **`$GPRMC`**: 速度 (knot) と進行方向 (track angle) を与える
- Emulator の `adb emu geo fix` は bearing を渡せないため、方位まで再現するには
  NMEA (`geo nmea`) を使う必要がある。これが `$GPRMC` を併用する理由。

### 位置更新頻度

- **1Hz (1秒間隔)** を基本とする
- 倍速時も更新頻度は 1Hz のまま、1回あたりの移動距離を増やす

## Emulator 側 (アプリコード不要)

Emulator では位置情報を OS レベルで注入できるため、**アプリ側に Mock 用のコードや
パーミッションは不要**。`adb emu geo` で注入された位置は FusedLocationProvider・
LocationManager の両系統に自動的に反映され、ナビ SDK までそのまま流れる。

旧方式（実機向け）で必要だった以下はすべて廃止された:

- アプリ内 HTTP サーバー (`FakeGpsServer` / Ktor)
- `DevTools` による起動処理
- `ACCESS_MOCK_LOCATION` パーミッション (debug マニフェスト)
- 開発者オプションの「仮の現在地情報アプリ」設定
- `adb forward` によるポート転送

### GPS 注入コマンド

ブリッジは内部で次を実行する（`<serial>` は対象 Emulator）:

```
adb -s <serial> emu geo nmea $GPGGA,...    # 位置を fix
adb -s <serial> emu geo nmea $GPRMC,...    # 速度・進行方向
```

- `adb emu` は telnet と異なり auth token が不要
- 対象 Emulator は `EMU_SERIAL` 環境変数で明示指定可能。未指定時は `adb devices` の
  先頭 `emulator-*` を自動選択する
- Emulator は最後に注入された位置を保持し続けるため、`POST /stop` は「ブリッジ側の
  最終位置を忘れる」だけで Emulator の位置はリセットされない

## 通信フロー

### セットアップ

```
1. Android Emulator を起動 (Android Studio の Device Manager 等)
2. cd dev-tools/fake-gps && npm install   (初回のみ)
3. npm run dev
   → Vite dev server を起動 (GPS ブリッジ middleware を内包)
4. ブラウザで http://localhost:5173 にアクセス
5. Web アプリの Status が ● Connected になれば準備完了
```

`adb forward` や端末側の Mock Location 設定は不要。

### 位置送信シーケンス

```
Web App                          Vite middleware            Emulator
  │                                  │                          │
  │  POST /location                  │                          │
  │  {"lat":35.68,"lng":139.76,      │                          │
  │   "bearing":45.0,"speed":8.3,    │                          │
  │   "accuracy":5.0,"altitude":0}   │                          │
  │ ────────────────────────────────→ │                          │
  │                                  │ NMEA 生成 (GGA + RMC)    │
  │                                  │ adb emu geo nmea $GPGGA  │
  │                                  │ ────────────────────────→ │
  │                                  │ adb emu geo nmea $GPRMC  │
  │                                  │ ────────────────────────→ │
  │                                  │                          │ OS GPS 更新
  │                                  │                          │     │
  │                                  │                          │     ▼
  │                                  │                          │ Fused / LocationManager
  │                                  │                          │     │
  │                                  │                          │     ▼
  │                                  │                          │ ナビ SDK (Camera / Reroute)
  │  200 OK {"success": true}        │                          │
  │ ←──────────────────────────────── │                          │
  │                                  │                          │
  │  [1秒後] POST /location (next)    │                          │
  │ ────────────────────────────────→ │       ...                │
```

## npm scripts

```json
{
  "scripts": {
    "dev": "vite",
    "start": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview"
  }
}
```

GPS ブリッジは Vite dev server の middleware として動くため、`vite` を起動するだけで
有効になる（別プロセスや事前セットアップは不要）。

## ディレクトリ構成

```
dev-tools/fake-gps/
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts          ← Vite 設定 + emulatorGeoBridge middleware (adb emu geo)
├── .env                    ← VITE_GOOGLE_API_KEY (gitignore)
├── .env.example            ← テンプレート
├── .gitignore
└── src/
    ├── main.ts              ← エントリーポイント
    ├── map.ts               ← Google Maps 初期化・ルート描画
    ├── simulation.ts        ← シミュレーションエンジン
    ├── connection.ts        ← ブリッジとの HTTP 通信 (同一オリジン)
    ├── nmea.ts              ← NMEA 0183 ($GPGGA / $GPRMC) 生成
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
- Android Emulator が起動済み (`adb devices` に `emulator-*` が表示される状態)

## 制限事項

- Android Emulator 専用 (実機は対象外)
- 複数 Emulator 起動時は先頭を自動選択。明示指定は `EMU_SERIAL` 環境変数を使用
- Android のみ対応 (iOS は対象外)
- NMEA 注入のため、Emulator の GPS が `geo nmea` を受理するイメージ/バージョンが前提
  (近年の標準 Emulator では問題なし)
