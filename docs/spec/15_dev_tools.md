# 15. Dev Tools

## 概要

OneNavi のナビゲーション機能をデバッグするための開発者向けツール。PC 上の Web アプリから Android Emulator に対して Fake GPS を送信し、実際に走行しなくてもナビゲーションの動作確認を行えるようにする。

位置情報は Emulator の **gRPC location API (`EmulatorController/setGps`)** 経由で OS レベルに注入するため、アプリ側に Mock 用のコードを一切組み込む必要がない（旧方式の実機向けアプリ内 HTTP サーバーは廃止）。これは Extended Controls の Location 機能と同じ注入経路であり、緯度経度に加えて **bearing（進行方向）・speed・satellites まで含めた完全な GPS state を注入できる** 点が特徴。

## アーキテクチャ

```
┌─────────────────────────┐                          ┌──────────────────────────┐
│   Web App (PC)          │                          │   Android Emulator       │
│   Vite dev server :5173 │                          │                          │
│                         │  POST /location          │  OS 位置情報サブシステム │
│  Google Maps JS API     │  {lat,lng,bearing,       │  (setGps で GPS state を │
│  ├ 地図表示             │   speed,accuracy,        │   直接セット)            │
│  ├ ルート描画           │   altitude}              │        │                 │
│  ├ 地点指定 (click)     │                          │        ▼                 │
│  ├ 地点検索 (Places)    │  ┌────────────────────┐  │  gps / fused provider    │
│  └ Roads API (snap)     │  │ Vite middleware    │  │  (bearing/speed 込み)    │
│                         │  │ (emulatorGeoBridge)│  │        │                 │
│  Simulation Engine      │──│  gRPC setGps       │──│        ▼                 │
│  ├ ルート再生           │  │  (localhost:gPort) │  │  ナビ SDK                │
│  ├ GPX 再生             │  └────────────────────┘  │  ├ Enhanced Location     │
│  ├ 矢印キー操作         │   gRPC (同一 PC 上)      │  ├ Map Matching          │
│  ├ 倍速 / 更新頻度制御  │                          │  └ Auto Reroute          │
│  └ 一時停止/再開        │                          │                          │
└─────────────────────────┘                          └──────────────────────────┘
```

ブラウザは Emulator の gRPC を直接叩けない。そのため Vite dev server に
HTTP ブリッジ（`vite.config.ts` の `emulatorGeoBridge` プラグイン）を同居させ、Web アプリ
からの `POST /location` を gRPC `EmulatorController/setGps` で Emulator に注入する。
ブリッジは dev server と同一オリジンなので CORS も別プロセスも不要。

> **なぜ `adb emu geo fix` ではなく gRPC `setGps` か**
> 当初は `adb emu geo fix` で位置を注入していたが、このコマンドは **bearing を注入できず
> 常に `bear=0.0`** になる。`fused` provider を参照するアプリ（Google Maps / OneNavi）は
> 連続位置から進行方向を自前で算出するため問題ないが、**生の `gps` provider を直接購読する
> ナビアプリ**は bearing が無いと地図マッチング／自車補間が回らず、「ごく稀に更新されて
> ジャンプする」挙動になった。Extended Controls が使う gRPC `setGps` は GpsState として
> bearing・speed・satellites を運べるため、生の `gps` provider 購読アプリでも滑らかに追従する。

## Web アプリ

### 技術スタック

- **フレームワーク**: Vite + TypeScript (単一ページ)
- **地図**: Google Maps JavaScript API（表示は常に Google Maps）
- **ルート探索**: プロバイダ切替制（**Google Directions** / **HERE Routing v8**、§ ルートプロバイダ参照）
- **道路スナップ**: Google Roads API (snapToRoads)
- **地点検索**: Google Places Autocomplete
- **API プレイグラウンド**: HERE REST を直接叩く API Bench（§ API Bench 参照）
- **GPS 注入ブリッジ**: Vite dev server middleware (gRPC `EmulatorController/setGps`)
- **配置**: `dev-tools/`

### API キー管理

- `dev-tools/.env` に `VITE_GOOGLE_API_KEY` を設定
- プロジェクトの `local.properties` の `GOOGLE_API_KEY_DEV_TOOLS` から取得
  （地図表示が web からの参照になるため、HTTP リファラー制限に `http://localhost:5173/*`
  を許可したブラウザ用キーを使うこと）
- HERE を使う場合は `dev-tools/.env` に `VITE_HERE_API_KEY`（必要なら `VITE_HERE_APP_ID`）を設定。
  `local.properties` の `HERE_API_KEY_DEV_TOOLS` / `HERE_APP_ID_DEV_TOOLS` から取得する。
  HERE Routing v8 は **apikey クエリのみ**で認証する（app_id/app_code は旧認証で v8 では不要）。
- HERE はブラウザから直叩きするため key がブラウザに露出する。dev 用 freemium key に留め、本番には使わない。
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
││  10x 20x         │                      │
││  Update rate     │                      │
││  1 2 5 10 20 Hz  │                      │
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

Status の `● Connected` は、ブリッジが discovery ini から実行中 Emulator の gRPC 接続情報を
解決できている時のみ点灯する。

### ルートプロバイダ (Google / HERE)

ルート探索は同一 interface (`RouteProvider`) で **Google Directions** と **HERE Routing v8** を
切り替える。Route カードのトグルで選択し、選択は `localStorage` に永続化する。

- **共通契約**: `computeRoute(waypoints) → { coords, distanceMeters, durationSeconds, raw }`。
  シミュレーション engine は `coords`（dense polyline）だけを消費するため、provider が変わっても
  再生・GPS 注入・スナップは無改修で動く。
- **Google** (`providers/google.ts`): `DirectionsService` を呼び、leg/step の path を coords へ展開。
- **HERE** (`providers/here.ts`): Routing v8 を `return=polyline,summary` で叩き、**flexible polyline**
  を `providers/flexpolyline.ts`（自前デコーダ、外部依存なし）で WGS84 座標へ復号。section 境界の
  重複端点は 1 点へ畳む。
- **描画**: provider に関わらず単一の `google.maps.Polyline` で描く。線の色は **アクティブな
  provider のアクセントカラー**（Google=青 `#4285f4` / HERE=ティール `#00afaa`）で、地図を見れば
  どちらの経路かが色で分かる。トグルの選択中ボタンも同じ色。
- ルート表示中に provider を切り替えると、同じ waypoints で即座に引き直す。

### API Bench

HERE の各 REST API を直接叩いて JSON を確認し、ジオメトリを地図へ重ねる開発用プレイグラウンド
（右側パネル、`bench.ts`）。自前バックエンド移行の事前検証（HERE が日本でどこまで返すか）に使う。

- **プリセット**: Routing v8 + spans / Routing v8 tolls / Browse (SA/PA) / Discover / Reverse geocode。
  チップを押すと Endpoint 欄にテンプレ URL が入る（編集可）。
- **apikey 自動付与**: `here.ts` が URL に `apikey` を付けて送る。手入力・貼り付け不要。表示 URL は
  apikey をマスクする。
- **Send**: ステータスコード・往復ミリ秒・整形 JSON をダークなターミナル面に表示。
- **Plot**: 直近レスポンスを再帰探索し、`polyline`（flexible polyline）と `{lat,lng}` 地点を地図へ
  オーバーレイ（橙）。Clear plot で消す。
- HERE は実プロバイダ名なので識別子・ドキュメントに実名表記してよい（外部API 提供元の秘匿対象外）。

### 地点指定方法

1. **地図クリック**: 地図上をクリックしてピンを配置、ドラッグで微調整
2. **検索ボックス**: Google Places Autocomplete で住所・施設名検索

### 操作モード

#### 1. ルート再生モード

- 出発地・経由地・目的地を指定してルート探索
- Google Directions API でルート polyline を取得
- polyline 上の点を一定の更新頻度で順次送信
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

### Bearing・Speed の扱い

- Web 側の Simulation Engine が前回座標と現在座標から bearing (方位角) と speed を算出し、
  `POST /location` で `lat, lng, bearing, speed, accuracy, altitude` をまとめて送信する。
- ブリッジはこれらを GpsState に詰めて `setGps` で注入する。**bearing・speed・satellites が
  そのまま OS の GPS state に反映される**ため、`fused` を参照するアプリも、生の `gps`
  provider を直接購読するアプリも、進行方向込みで滑らかに追従する。
- `setGps` は `passiveUpdate=false` で呼ぶ。これにより Extended Controls の Location UI による
  1Hz の自動上書きを止め、本ツールの更新を優先させる（dev-tool 再生中は Location UI の
  ルート再生は使わない前提）。

### 位置更新頻度

- **既定 10Hz**。UI の「Update rate」ボタンで `1 / 2 / 5 / 10 / 20Hz` を切り替え可能。
- 倍速（speed multiplier）とは独立。更新頻度を上げると 1 回あたりの移動距離が短くなり、
  Emulator 上の自車がより滑らかに動く。
- 生の `gps` provider 購読アプリでは、極端に高い頻度（1 点あたりの移動が小さすぎる）より
  数 Hz 程度の方が進行方向を算出しやすい場合があるため、挙動を見て調整する。

## Emulator 側 (アプリコード不要)

Emulator では位置情報を OS レベルで注入できるため、**アプリ側に Mock 用のコードや
パーミッションは不要**。`setGps` で注入された GpsState は `gps` / `fused` provider の
両系統に反映され、ナビ SDK までそのまま流れる。

旧方式（実機向け）で必要だった以下はすべて廃止された:

- アプリ内 HTTP サーバー (`FakeGpsServer` / Ktor)
- `DevTools` による起動処理
- `ACCESS_MOCK_LOCATION` パーミッション (debug マニフェスト)
- 開発者オプションの「仮の現在地情報アプリ」設定
- `adb forward` によるポート転送

### GPS 注入経路 (gRPC)

ブリッジは実行中 Emulator の gRPC エンドポイントに対し `EmulatorController/setGps` を呼ぶ。

- **接続情報の解決**: Emulator は起動時に discovery ini を書き出す
  （macOS では `~/Library/Caches/TemporaryItems/avd/running/pid_<pid>.ini`）。
  ブリッジはこの ini から `grpc.port`・`grpc.token`・`port.serial` を読み取る。
- **認証**: `grpc.token`（`-grpc-use-token` で生成される静的トークン）を
  `authorization: Bearer <token>` メタデータで付与する。localhost への plaintext 接続で通る。
  JWT 署名は不要。
- **対象 Emulator**: `EMU_SERIAL` 環境変数があれば `port.serial` で照合。未指定時は
  最も新しい discovery ini を採用する。
- **GpsState フィールド**: `latitude, longitude, speed, bearing, altitude, satellites` を送る。
  Emulator は最後に注入された位置を保持し続けるため、`POST /stop` は「ブリッジ側の
  最終位置を忘れる」だけで Emulator の位置はリセットされない。

```
EmulatorController/setGps(GpsState{
  passiveUpdate: false,
  latitude, longitude, speed, bearing, altitude, satellites
})
```

### 注意: GPS が更新されない場合

- **アクティブな位置リスナーが必要**: Emulator の GPS HAL は、いずれかのアプリが位置を
  要求している間だけ fix を出力する。位置取得していない画面では注入しても last location が
  更新されない。地図 / ナビ画面など位置取得中の状態で確認すること。
- **位置が固まって動かない場合**: Extended Controls の Location が位置を握っているか、
  snapshot 復元由来で GPS がスタックしていることがある。Location を一度オフ→オンする、
  もしくは Emulator を cold boot（`emulator -avd <name> -no-snapshot-load`）すると復帰する。

## 通信フロー

### セットアップ

```
1. Android Emulator を起動 (Android Studio の Device Manager 等)
2. make dev-tools-setup   (初回のみ)
3. make dev-tools-dev
   → Vite dev server を起動 (GPS ブリッジ middleware を内包)
4. ブラウザで http://localhost:5173 にアクセス
5. Web アプリの Status が ● Connected になれば準備完了
6. Emulator 側で対象アプリを地図 / ナビ画面まで進め、位置取得を開始させる
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
  │                                  │ gRPC setGps(GpsState{    │
  │                                  │   lat,lng,speed,bearing, │
  │                                  │   altitude,satellites})  │
  │                                  │ ────────────────────────→ │
  │                                  │                          │ OS GPS state 更新
  │                                  │                          │     │ (bearing/speed
  │                                  │                          │     ▼  込みで反映)
  │                                  │                          │ gps / fused provider
  │                                  │                          │     │
  │                                  │                          │     ▼
  │                                  │                          │ ナビ SDK (Camera / Reroute)
  │  200 OK {"success": true}        │                          │
  │ ←──────────────────────────────── │                          │
  │                                  │                          │
  │  [次 tick] POST /location (next)  │                          │
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
dev-tools/
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts          ← Vite 設定 + emulatorGeoBridge middleware (gRPC setGps)
├── proto/
│   └── emulator_min.proto  ← EmulatorController/setGps の最小 proto 定義
├── .env                    ← VITE_GOOGLE_API_KEY / VITE_HERE_API_KEY (gitignore)
├── .env.example            ← テンプレート
├── .gitignore
└── src/
    ├── main.ts              ← エントリーポイント
    ├── map.ts               ← Google Maps 初期化・ルート描画・bench オーバーレイ
    ├── simulation.ts        ← シミュレーションエンジン
    ├── connection.ts        ← ブリッジとの HTTP 通信 (同一オリジン)
    ├── gpx-parser.ts        ← GPX ファイルパーサー
    ├── controls.ts          ← UI コントロール (再生/停止/倍速/更新頻度/provider 切替)
    ├── keyboard.ts          ← 矢印キー操作
    ├── snap.ts              ← Roads API 道路スナップ
    ├── geo-utils.ts         ← bearing/distance 計算ユーティリティ
    ├── here.ts              ← HERE REST 共通クライアント (apikey 自動付与)
    ├── bench.ts             ← API Bench (HERE REST プレイグラウンド)
    ├── providers/
    │   ├── types.ts         ← RouteProvider interface
    │   ├── index.ts         ← provider レジストリ・アクティブ切替
    │   ├── google.ts        ← Google Directions プロバイダ
    │   ├── here.ts          ← HERE Routing v8 プロバイダ
    │   └── flexpolyline.ts  ← HERE flexible polyline デコーダ
    └── style.css            ← スタイル
```

## 必要な Google API

`GOOGLE_API_KEY_DEV_TOOLS` で以下の API が有効になっている必要がある:

| API | 用途 | 確認方法 |
|-----|------|----------|
| Maps JavaScript API | 地図表示 | 必須 |
| Directions API | ルート探索 | Web アプリでルート検索時に使用 |
| Roads API | 道路スナップ | 矢印キー操作時に使用 |
| Places API | 地点検索 | 検索ボックスで使用 |

Google Cloud Console で有効化と、`http://localhost:5173/*` の HTTP リファラー許可が必要。

## 前提条件

- Node.js 18+
- Android Emulator が起動済み (discovery ini が書き出されている状態)
- `@grpc/grpc-js` / `@grpc/proto-loader`（`npm install` で導入）

## 制限事項

- Android Emulator 専用 (実機は対象外)
- 複数 Emulator 起動時は最新の discovery ini を自動選択。明示指定は `EMU_SERIAL` 環境変数を使用
- Android のみ対応 (iOS は対象外)
- Emulator の GPS は位置リスナーが無いと fix を出力しない。位置がスタックした場合は
  Location のオフ→オン、または cold boot で復帰させる
- gRPC 接続情報は discovery ini に依存する。ini の配置は OS / Emulator バージョンで
  変わりうるため、見つからない場合は `EMU_SERIAL` と ini パスを確認する
```
