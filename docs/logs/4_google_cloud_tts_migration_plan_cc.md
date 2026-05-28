# Google Cloud TTS 移行計画書

## 1. 目的

OneNavi の音声案内エンジンを、現状の Android 標準 TTS (`android.speech.tts.TextToSpeech`) から **Google Cloud Text-to-Speech (Chirp 3 HD / ja-JP-Chirp3-HD-Despina)** に載せ替える。

2026-05-28 時点の voice 選定は `ja-JP-Chirp3-HD-Despina` とする。Chirp 3 HD の自然な会話調を活かしつつ、ナビアプリでは Google アシスタント風の短文案内として聞き取りやすい声質を優先する。

ただし以下を条件とする:

- **最小変更で** Android 標準 TTS にフォールバックできる仕組みを持たせる (ネットワーク不通・API 障害・API キー未設定時の安全網)
- **自前実装を極力避ける**。既存の依存 (Ktor 3.3 + kotlinx.serialization + Napier) を最大限流用する
- 既存の `TtsEngine` interface 抽象化を活かし、`SpeechOrchestrator` / `GuidanceSessionManager` の契約は極力変えない

## 2. 現状分析

### 2.1 既存構造

`core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/tts/` 配下:

| ファイル | 役割 |
|---|---|
| `TtsEngine.kt` | 差し替えポイントの interface。`speak()` / `stop()` / `shutdown()` / `isReady: StateFlow` / `onUtteranceCompleted` コールバック |
| `AndroidTtsEngine.kt` | `android.speech.tts.TextToSpeech` を使った唯一の実装 |
| `SpeechOrchestrator.kt` | 発話キューと完了コールバック管理。`TtsEngine` のみに依存 |
| `SpeechQueueMode.kt` | `FLUSH` / `ADD` の enum |
| `AudioFocusManager.kt` | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` の取得/解放 |

### 2.2 差し替えを阻害している唯一のポイント

`GuidanceSessionManager.kt` L82 で `AndroidTtsEngine` を**直接 `new` している**。

```kotlin
val engine = AndroidTtsEngine(
    context = context,
    audioFocusManager = AudioFocusManager(context),
).also { createdEngine ->
    createdEngine.onReadyChanged = { ready -> ... }
}
ttsEngine = engine
```

これを**ファクトリ経由**に変えれば、DI で Google / Android / Fallback を切り替えられる。

### 2.3 `TtsEngine` interface の契約

```kotlin
interface TtsEngine {
    val isReady: StateFlow<Boolean>
    var onUtteranceCompleted: ((String) -> Unit)?
    fun speak(text: String, utteranceId: String, queueMode: SpeechQueueMode): Boolean
    fun stop()
    fun shutdown()
}
```

この契約のまま Google Cloud TTS を実装可能。**interface 変更なし**が最小変更の鍵。

## 3. 技術選定

### 3.1 Google Cloud TTS の呼び出し方式

| 方式 | 評価 | 採否 |
|---|---|---|
| `com.google.cloud:google-cloud-texttospeech` (gRPC SDK) | APK 肥大化 (+数 MB)、Service Account JSON を APK 同梱が必要で鍵漏洩リスク、R8 対応も面倒。Android 非対応明記 | ❌ |
| `changemyminds/Google-Cloud-TTS-Android` (非公式) | 2021/2 から更新停止、OkHttp + Gson 依存 (スタック不整合)、Chirp 3 HD 非対応 | ❌ |
| **REST API + Ktor (既存依存流用)** | API キー 1 本で認証、既存 Ktor/kotlinx.serialization 流用、Chirp 3 HD 指定可能、追加依存ほぼゼロ | ✅ |

### 3.2 音声再生方式

| 方式 | レイテンシ | 追加依存 | 備考 | 採否 |
|---|---|---|---|---|
| `MediaPlayer` + MP3 | 100-300ms (prepare) | なし | base64 → 一時ファイル IO、`FLUSH` が遅い | ❌ |
| **`AudioTrack` + LINEAR16 PCM** | 即時 (デコード不要) | なし | WAV ヘッダ 44 byte スキップのみ、`FLUSH` が 1ms 以内、インスタンス使い回し可能 | ✅ |
| `SoundPool` | - | なし | 短クリップ `load` 前提で動的生成に不向き | ❌ |
| ExoPlayer / Media3 | 中 | +数 MB | オーバーキル | ❌ |
| `MediaCodec` + AudioTrack | 速 | なし | 自前デコードは不要にコストを上げる | ❌ |

**採用: Ktor REST + AudioTrack (LINEAR16/24kHz/mono/16bit PCM)**

### 3.3 Google Cloud TTS リクエスト設計

エンドポイント: `POST https://texttospeech.googleapis.com/v1/text:synthesize?key={API_KEY}`

リクエスト JSON:

```json
{
  "input": { "text": "この先、右方向です" },
  "voice": {
    "languageCode": "ja-JP",
    "name": "ja-JP-Chirp3-HD-Despina"
  },
  "audioConfig": {
    "audioEncoding": "LINEAR16",
    "sampleRateHertz": 24000,
    "speakingRate": 1.0,
    "pitch": 0.0
  }
}
```

レスポンス: `{ "audioContent": "<base64 PCM with 44-byte WAV header>" }`

### 3.4 API キーのセキュリティ

**Google の Android 制限は `?key=` 付きの URL クエリだけでは発動しない**。REST 呼び出し時に以下のヘッダを必ず同送する必要がある (Google 公式ドキュメント)。

- **`x-goog-api-key: {API_KEY}`** — API キー本体 (URL クエリでなくヘッダに載せる)
- **`X-Android-Package: me.matsumo.onenavi(.debug)`** — 実行中パッケージ名
- **`X-Android-Cert: {SHA-1 fingerprint}`** — APK 署名の SHA-1 (`:` 区切り大文字)

さらに前提:

- `local.properties` + `BuildKonfig` で API キーを注入 (詳細は Section 8)
- Google Cloud Console で以下を設定:
  - **アプリケーション制限**: Android アプリ (パッケージ名 + SHA-1)
  - **API 制限**: Cloud Text-to-Speech API のみ
- リリース時は productFlavor / 署名ごとに別鍵を発行 (debug / release / billing)
- **予算アラート必須**: Cloud Billing で月額上限と 50%/80%/100% アラート設定
- **使用量モニタリング**: Cloud Monitoring で `serviceruntime.googleapis.com/api/request_count` を観測、異常増加を検知
- **Google 自身が「Android 制限は回避容易」と警告している**ことを前提に、鍵漏洩時は即ローテ・1 日上限 quota を低めに設定して被害最小化

> 参考: [Google API keys authentication](https://docs.cloud.google.com/docs/authentication/api-keys) / [API keys for REST](https://docs.cloud.google.com/docs/authentication/api-keys-use)

## 4. アーキテクチャ

### 4.1 クラス構成

```
TtsEngine (既存 interface, 変更なし)
├── AndroidTtsEngine (既存, 変更なし)
├── GoogleCloudTtsEngine (新規)
│   ├── GoogleCloudTtsApi (新規: Ktor 経由の薄い API クライアント)
│   ├── GoogleCloudTtsDto (新規: リクエスト/レスポンス DTO)
│   └── PcmAudioPlayer (新規: AudioTrack ラッパー)
└── FallbackTtsEngine (新規: primary = Google, fallback = Android)

SpeechOrchestrator (既存, 変更なし)
GuidanceSessionManager (変更: TtsEngine ファクトリ注入)
```

### 4.2 `FallbackTtsEngine` の振る舞い

```
speak(text, utteranceId, queueMode):
    if primary.isReady.value:
        spoken = primary.speak(text, utteranceId, queueMode)
        if spoken: return true
    return fallback.speak(text, utteranceId, queueMode)

isReady: primary.isReady or fallback.isReady (combine)

onUtteranceCompleted: primary と fallback 両方のコールバックを集約して上位へ転送
```

**切り替えトリガー**:

1. `primary.isReady.value == false` (API キー未設定・初期化失敗)
2. `primary.speak()` が false を返した (即時エラー)
3. `GoogleCloudTtsEngine` 内部でネットワーク/API エラーが発生した場合、**次回の `speak()` から** isReady を false に降格させる (連続失敗カウンタ + 一定時間のクールダウン)

### 4.3 `GoogleCloudTtsEngine` の内部構造

**重要**: `SpeechOrchestrator.stop()` は即時停止を契約する (既存 `AndroidTtsEngine.stop()` は `textToSpeech.stop()` 一発で即時) 。合成+再生を直列 `Channel` で回すと、`api.synthesize()` / 再生完了待ちに worker が塞がれて `FLUSH` / `stop()` が後ろに並ぶ。
**→ キュー管理と再生 job を分離し、`stop()` / `FLUSH` は現在走っている job を同期 cancel できる構造にする。**

```
state (init 時に確保):
    requestChannel: Channel<Utterance>        // 発話予約
    activeJob: Job?                           // 現在 "合成中 or 再生中" の単一 job
    jobMutex: Mutex                           // activeJob 差し替えの排他
    consecutiveFailures: Int
    cooldownUntilMillis: Long
    sessionDisabled: Bool                     // 401/403 でセッション中は primary を落とす
    isReady: StateFlow<Boolean>

init:
    isReady = (apiKey.isNotBlank() && !sessionDisabled && now >= cooldownUntilMillis)
    worker を 1 本起動

speak(text, utteranceId, queueMode):
    if !isReady: return false
    if queueMode == FLUSH:
        flushInternal()   // 同期で activeJob.cancelAndJoin + channel drain + audioPlayer.flush
    requestChannel.trySend(Utterance(text, utteranceId))
    return true

worker coroutine:
    for utterance in requestChannel:
        val job = scope.launch {
            audioFocusManager.request()
            runCatching {
                val audio = api.synthesize(utterance.text)     // Ktor (cancellable)
                audioPlayer.playAndAwait(audio)                // AudioTrack per-utterance (cancellable)
            }.onSuccess {
                onUtteranceCompleted(utterance.id)
                resetFailureCount()
            }.onFailure { error ->
                handleFailure(error)                            // 401/403 は sessionDisabled=true
                onUtteranceCompleted(utterance.id)              // 完了通知は必ず発火 (orchestrator の callback を詰まらせない)
            }
            if (requestChannel.isEmpty) audioFocusManager.abandon()
        }
        jobMutex.withLock { activeJob = job }
        job.join()
        jobMutex.withLock { if (activeJob == job) activeJob = null }

flushInternal():  // 同期で即時停止
    jobMutex.withLock {
        activeJob?.cancel()
    }
    while (requestChannel.tryReceive().isSuccess) { /* drain */ }
    audioPlayer.flush()                                  // AudioTrack を release
    audioFocusManager.abandon()

stop():
    flushInternal()

shutdown():
    flushInternal()
    worker.cancel()
    audioPlayer.release()
    httpClient.close()
```

**ポイント**:
- `FLUSH` は worker のキューに載せず `speak()` の即時呼び出しで処理する。これで待ち行列後ろに並ぶ問題を回避
- `activeJob.cancel()` で合成中の Ktor HTTP も `awaitPlayback()` もまとめてキャンセル可能 (構造化並行性)
- 完了通知は成功/失敗どちらでも必ず発火し、`SpeechOrchestrator.completionCallbacks` を詰まらせない

### 4.4 `PcmAudioPlayer` の内部構造

**方針変更**: 単一 `AudioTrack` を使い回して `setNotificationMarkerPosition` で完了検知する案は、以下の理由で採用しない:

- marker は stream 全体の累積 frame 基準で 1 個だけ、`flush()` してもリセットされない
- `writtenFrames` / `playbackHead` を自前管理する複雑さがバグを招く
- per-utterance で作り直しても `AudioTrack` 生成コストは小さく (数 ms) 、ナビ文短さでは許容

**採用: 発話ごとに `AudioTrack` を新規作成 + `MODE_STATIC` で完了を同期 await**

```kotlin
suspend fun playAndAwait(audio: ByteArray) {
    val pcm = audio.copyOfRange(WAV_HEADER_BYTES, audio.size)   // 44 byte スキップ
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setBufferSizeInBytes(pcm.size)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    try {
        track.write(pcm, 0, pcm.size)
        val totalFrames = pcm.size / 2    // 16bit mono
        track.setNotificationMarkerPosition(totalFrames)
        val done = CompletableDeferred<Unit>()
        track.setPlaybackPositionUpdateListener(object : OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) { done.complete(Unit) }
            override fun onPeriodicNotification(t: AudioTrack?) = Unit
        })
        track.play()
        try {
            done.await()       // coroutine cancel で catch → finally で release
        } finally {
            track.release()    // キャンセル時も必ず release
        }
    } catch (cancel: CancellationException) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        track.release()
        throw cancel
    }
}

fun flushCurrent() {  // Engine の flushInternal() から呼ばれる
    // activeJob.cancel() により playAndAwait の CancellationException 経由で
    // track.release() まで到達するため、このメソッド本体は no-op に近い
}
```

**ポイント**:
- per-utterance で作るので marker 位置は常に `totalFrames` 固定 (wrap を気にしない)
- coroutine cancel で `finally` / `catch(CancellationException)` から `release()` に確実に到達
- `MODE_STATIC` により write 完了後 `play()` 1 回で全体再生、完了検知が明快
- 発話が長くバッファ上限超過の懸念がある場合は将来 `MODE_STREAM` + ring buffer に切替、ただしナビフレーズ (最長 10 秒 ~ 480KB) なら `MODE_STATIC` で十分

### 4.5 AudioFocus のライフサイクル仕様

既存 `AndroidTtsEngine` は `onDone` / `onError` で必ず `abandon()` している (L92-101)。新実装でも**解放漏れで他アプリ音声を延々 duck させない**ため、以下のすべての経路で `abandon()` を保証する。

| トリガ | 箇所 | 動作 |
|---|---|---|
| 発話開始 | worker coroutine の冒頭 (Section 4.3) | `request()` |
| 発話成功完了 | worker で `onUtteranceCompleted` 発火後、`requestChannel.isEmpty` なら | `abandon()` |
| 発話失敗 (HTTP / 再生例外) | `handleFailure` の finally | `abandon()` (次の発話がまだキューにあれば次 worker ループで再取得) |
| `stop()` / `FLUSH` | `flushInternal()` 末尾 | `abandon()` |
| `shutdown()` | `flushInternal()` 経由で | `abandon()` |
| Coroutine cancel (jobMutex 経由) | `playAndAwait()` finally で `track.release()` 後、worker の finally で | `abandon()` |
| `GoogleCloudTtsEngine` → `FallbackTtsEngine` 降格中 | `FallbackTtsEngine` 側で primary の `stop()` を呼び、その経路で | `abandon()` |

**原則**: `audioFocusManager.request()` と `abandon()` はペアで 1:1 になるよう、worker coroutine の `try/finally` で挟む。`Channel` が空になった時のみ `abandon()` し、次の発話がキューに残っているなら focus を握り続ける (duck 状態の無用な往復を避ける)。

## 5. 変更ファイル一覧

### 5.1 新規作成

| パス | 概要 | 想定行数 |
|---|---|---|
| `core/navigation/src/androidMain/.../tts/GoogleCloudTtsEngine.kt` | TtsEngine 実装、発話キュー、エラー時の isReady 降格ロジック | ~150 |
| `core/navigation/src/androidMain/.../tts/PcmAudioPlayer.kt` | AudioTrack ラッパー、WAV ヘッダスキップ、マーカーによる完了通知 | ~100 |
| `core/navigation/src/androidMain/.../tts/GoogleCloudTtsApi.kt` | Ktor `HttpClient` 経由の薄い API クライアント | ~50 |
| `core/navigation/src/androidMain/.../tts/GoogleCloudTtsDto.kt` | `@Serializable` な DTO (SynthesizeRequest/Response) | ~50 |
| `core/navigation/src/androidMain/.../tts/FallbackTtsEngine.kt` | primary/fallback 切り替えロジック | ~80 |
| `core/navigation/src/androidMain/.../tts/TtsAudioCache.kt` | LRU キャッシュ (`LruCache<String, ByteArray>`) | ~40 |

### 5.2 変更

| パス | 変更概要 |
|---|---|
| `core/navigation/src/androidMain/.../GuidanceSessionManager.kt` | `AndroidTtsEngine` の直接 `new` をやめ、コンストラクタで受け取る `ttsEngineFactory: () -> TtsEngine` を使う。`ttsEngine: AndroidTtsEngine?` → `ttsEngine: TtsEngine?` に型変更。`onReadyChanged` コールバックをやめ `isReady.collect` で UI State 更新。**collector の寿命管理を明示** (下記 5.2.1 参照) |
| `core/navigation/src/androidMain/.../di/NavigationModule.kt` | `TtsEngine` の named factory 登録 (`google` / `android` / デフォルトは `FallbackTtsEngine`)、`HttpClient`/`GoogleCloudTtsApi` 登録、`GuidanceSessionManager` に `ttsEngineFactory` を渡す |
| `core/navigation/build.gradle.kts` | `androidMain` に Ktor (`ktor-client-okhttp`) + kotlinx.serialization を追加 (BuildKonfig は持たず、API キーは `AppConfig` 経由で DI 注入) |
| `core/model/src/commonMain/.../AppConfig.kt` | `googleCloudTtsApiKey: String` フィールド追加 |
| `composeApp/build.gradle.kts` (既存 BuildKonfig 設定) | `GOOGLE_CLOUD_TTS_API_KEY` を `local.properties` から読み込み、`AppConfig` 構築時に詰める (既存 `googleApiKey` と同じ方式) |
| `local.properties.sample` (あれば) | 追加の環境変数を記載 |

### 5.2.1 `GuidanceSessionManager` での collector 寿命管理

`AndroidTtsEngine.onReadyChanged` コールバックを `TtsEngine.isReady` (StateFlow) の `collect` に置き換える際、**`startSession()` で起動した collector は `stopSession()` で必ず cancel する**。さもないと start/stop を跨いで stale engine の flow が `isTtsAvailable` を揺らし続ける。

```kotlin
private var guidanceJob: Job? = null
private var arrivalJob: Job? = null
private var ttsReadyJob: Job? = null        // 新規

fun startSession() {
    // ... engine 生成
    ttsReadyJob?.cancel()
    ttsReadyJob = scope.launch {
        engine.isReady.collect { ready ->
            _guidanceUiState.value = _guidanceUiState.value.copy(isTtsAvailable = ready)
        }
    }
    // ... 他
}

fun stopSession() {
    // ... 既存
    ttsReadyJob?.cancel()
    ttsReadyJob = null
    speechOrchestrator?.shutdown()   // 既存: engine.shutdown() も含む
    speechOrchestrator = null
    ttsEngine = null
    // ...
}
```

### 5.3 変更なし

- `TtsEngine.kt` (interface そのまま)
- `AndroidTtsEngine.kt` (既存動作そのまま)
- `SpeechOrchestrator.kt`
- `SpeechQueueMode.kt`
- `AudioFocusManager.kt`

## 6. 実装ステップ

### Step 0: 実 API スパイク (マージ対象外)

以降の実装が事故らないよう、まず **1 本だけ実 API を叩いて前提を確認する**。捨てブランチまたは scratch 用ファイルで OK。

- [ ] API キー発行 + Cloud Console で Android 制限設定 (パッケージ名 + SHA-1)
- [ ] `curl` or 簡単な Kotlin テストコードで `text:synthesize` に POST
  - `x-goog-api-key` / `X-Android-Package` / `X-Android-Cert` ヘッダの必要性を実機レベルで確認
  - voice name `ja-JP-Chirp3-HD-Despina` が実在・利用可能か確認
  - `audioEncoding = LINEAR16` + `sampleRateHertz = 24000` のレスポンスが WAV ヘッダ 44 byte + PCM であることをバイナリダンプで確認
- [ ] 成功したら Step 1 へ。認証で詰まったら Cloud Console 設定を先に正す

### Step 1: DI 抽象化 (先行マージ可能)

- `GuidanceSessionManager` を `ttsEngineFactory` 経由に変更
- `NavigationModule` で `AndroidTtsEngine` を `TtsEngine` として登録
- **この時点で動作は現状維持**。既存テスト・ビルドが通ることを確認

### Step 2: `PcmAudioPlayer` の実装

- `AudioTrack` のラッパーを単体で書く
- 任意の PCM バイト列を流して鳴ることをユニット確認 (手動で pre-generated WAV ファイルを流して OK)

### Step 3: `GoogleCloudTtsApi` + DTO

- Ktor `HttpClient` で `text:synthesize` を叩き、base64 デコードまで
- API キーを `local.properties` → `BuildKonfig` 経由で渡す

### Step 4: `GoogleCloudTtsEngine` の組み立て

- Step 2 + Step 3 を結合
- `Channel`-based worker + 分離した `activeJob` で発話キュー制御 (Section 4.3 参照)
- `FLUSH` / `ADD` の挙動を実機確認 (連続 FLUSH で音が被らないこと、即時切り替わること)
- `TtsAudioCache` (Section 9.1) を `api.synthesize` の前段に差し込む
- エラー分類 (恒久 vs 一時) の分岐と `sessionDisabled` / 連続失敗カウンタの実装

### Step 5: `FallbackTtsEngine`

- primary + fallback の切り替えロジック
- 連続失敗時の降格 + クールダウン

### Step 6: DI 差し替え

- `NavigationModule` のデフォルト `TtsEngine` を `FallbackTtsEngine` に
- 実機で Google/Android 両経路の動作確認 (API キー無効にして Android フォールバックに落ちるか等)

### Step 7: ビルド確認 + detekt

```bash
./gradlew assembleDebug --no-configuration-cache
make detekt
```

## 7. エラーハンドリング方針

**恒久エラー** と **一時エラー** を明確に区別する。恒久エラーに無駄にリトライしない。

| 事象 | 分類 | 対応 |
|---|---|---|
| API キー未設定 / 空文字 | 恒久 | 起動時に `isReady = false`、`FallbackTtsEngine` が Android に委譲。以後一切 Google を試さない |
| `AudioTrack` 初期化失敗 | 恒久 | `isReady = false`、fallback 一択 |
| **HTTP 401 / 403** (認証・制限エラー) | 恒久 | **`sessionDisabled = true` に設定し、以後このアプリプロセスが生きている間は primary を一切試さない** (クールダウン再試行しない)。Napier で `warn` を 1 度だけ出力、telemetry 送信。次回アプリ再起動時にリトライ |
| HTTP 400 (リクエスト不正) | 恒久 | 同上 (設計バグなので再挑戦しても直らない) |
| HTTP 404 (voice name 廃止等) | 恒久 | 同上 |
| ネットワークタイムアウト (connect 3s / read 5s) | 一時 | そのフレーズは握り潰し、連続失敗カウンタ++。3 回連続で失敗したら 60 秒クールダウン |
| HTTP 429 (rate limit) | 一時 | 指数バックオフ (500ms, 1s) で 2 回までリトライ、ダメなら連続失敗カウンタ++。`Retry-After` ヘッダがあればその値でクールダウン |
| HTTP 5xx | 一時 | リトライ 1 回 (指数バックオフ 500ms)、ダメなら連続失敗カウンタ++ |
| ネット復帰 | - | `sessionDisabled == false` なら、クールダウン明けの次回 `speak()` で自動復帰 |

**連続失敗カウンタ**:
- 一時エラーで 3 回連続失敗 → `cooldownUntilMillis = now + 60s`
- クールダウン中は `isReady = false` を返し fallback に委譲
- 1 回でも成功したらカウンタを 0 にリセット

**telemetry**:
- 401/403/400/404 発生時は必ず Napier で記録し、Crashlytics 等が入ったら非致命イベントとして送る
- サイレントに fallback に落ちると鍵ミス / 制限ミスに気付けないため、最初の 1 回だけは `error` レベル

## 8. 設定値・環境変数

### 8.1 モジュール境界

`core/navigation` から `composeApp` の `BuildKonfig` は直接参照できない (依存方向が逆)。既存の `core/model/AppConfig.kt` にフィールドを追加し、DI で渡す方針に統一する。

```kotlin
// core/model/src/commonMain/.../AppConfig.kt
data class AppConfig(
    // ... 既存フィールド
    val googleApiKey: String,                // 既存 (Routes API 用)
    val googleCloudTtsApiKey: String,        // 新規追加
    // ...
)
```

- `composeApp` の `BuildKonfig` で `GOOGLE_CLOUD_TTS_API_KEY` を読み込み、`AppConfig` 構築時に詰める (既存の `googleApiKey` と同じ方式)
- `NavigationModule` (android) で `get<AppConfig>().googleCloudTtsApiKey` を取得して `GoogleCloudTtsApi` / `GoogleCloudTtsEngine` に渡す

### 8.2 環境変数一覧

| キー | 格納先 | 経路 | 用途 |
|---|---|---|---|
| `GOOGLE_CLOUD_TTS_API_KEY` | `local.properties` → `composeApp` の BuildKonfig → `AppConfig.googleCloudTtsApiKey` | DI で `core/navigation` に注入 | TTS API 認証 |

実装デフォルト (コード内):

- `voice.languageCode = "ja-JP"`
- `voice.name = "ja-JP-Chirp3-HD-Despina"`
- `audioConfig.audioEncoding = "LINEAR16"`
- `audioConfig.sampleRateHertz = 24000`
- `audioConfig.speakingRate = 1.0`
- `audioConfig.pitch = 0.0`
- HTTP タイムアウト: connect 3s / read 5s
- 連続失敗閾値: 3 回
- クールダウン: 60 秒 (通常) / 10 分 (認証エラー時)

## 9. 非対応とする項目 (今回スコープ外)

- iOS 対応 (現状 Android のみ稼働なので commonMain 昇格しない)
- Service Account / OAuth2 認証 (API キーで充分)
- SSML サポート (`input.ssml`) — 必要になったら `speak()` のオーバーロードで追加
- 音量・速度・ピッチのランタイム調整 — 設定画面から制御する機能は別タスク

### 9.1 今回スコープに含めるか要検討: LRU キャッシュ

Codex レビュー指摘: LINEAR16 24kHz mono 16bit は 48KB/s raw、REST は base64 でさらに膨らむ。ナビフレーズには同一文言の繰り返し (「まもなく右方向です」等) が多く、**小さい LRU キャッシュで通信量・レイテンシ両方が大幅改善する**。

| 判断軸 | 今回スコープに含める場合 | 後続 PR に回す場合 |
|---|---|---|
| 実装コスト | `LruCache<String, ByteArray>` 1 クラス追加 ~30 行 | - |
| 通信量削減 | 繰り返しフレーズ多数の用途で 50%+ 削減見込み | MVP では過剰課金リスク |
| レイテンシ | キャッシュヒット時は HTTP 往復ゼロで即再生 | - |
| 複雑性 | `GoogleCloudTtsEngine.speak()` で key (text) 合致時は api を skip | - |

**推奨**: 今回スコープに含める。voice 固定前提なら key は text のみで、LRU 容量は 2-4MB (50 フレーズ程度) で充分。`GoogleCloudTtsApi` の前段に置くだけで済むので影響範囲も限定的。

## 10. 検証項目 (PR 時)

### 機能確認
- [ ] Android 実機で Google TTS が再生される (Chirp 3 HD の音質で発話)
- [ ] API キーを空にすると Android 標準 TTS にフォールバックする
- [ ] 機内モードで Google TTS が失敗 → Android にフォールバック
- [ ] 不正な API キーで 401/403 → `sessionDisabled` になり、そのプロセス中は再試行されない (ログに `error` が 1 回)

### 動作品質
- [ ] ルート案内中に高頻度で `FLUSH` が走っても音が被らない / 即切り替わる (100ms 以内)
- [ ] 発話中に `stop()` → 次の `speak()` で即座に新しい発話が始まる
- [ ] 連続発話時 (ADD モード) で途切れず順に読まれる
- [ ] 同一文言 2 回目以降がキャッシュヒットで即再生される (LRU)

### リソース管理
- [ ] バックグラウンド遷移で AudioFocus が適切に解放される
- [ ] `startSession()` / `stopSession()` を 10 回繰り返しても `AudioTrack` / `ttsReadyJob` / `HttpClient` がリークしない (Android Studio Profiler 確認)
- [ ] `shutdown()` 後に `speak()` を呼んでもクラッシュしない

### ビルド
- [ ] `./gradlew assembleDebug --no-configuration-cache` が通る
- [ ] `make detekt` が通る

## 11. リスクと軽減策

| リスク | 影響 | 軽減策 |
|---|---|---|
| Chirp 3 HD (Despina) が将来名前変更 / 廃止 | 音声が出なくなる | voice name を設定可能にし、`FallbackTtsEngine` で救済 |
| API キー漏洩 | Google Cloud アカウント悪用 | Console 側で Android パッケージ + SHA-1 制限 + API 制限 |
| PCM 転送量の増加 (MP3 比 ~3 倍 + base64 でさらに約 1.33 倍 ≒ 実 wire は MP3 比 4 倍前後) | モバイル通信量・Cloud TTS 課金 | 同一文言を繰り返す用途なので Section 9.1 の LRU キャッシュを今回スコープに含めて軽減 |
| Cloud TTS の従量課金 | コスト増 | Chirp 3 HD は従来の Standard/Neural より単価が高い。`docs/spec/06_pricing_analysis.md` と突合のうえ月間予算のアラート設定 |
| 連続失敗時のフォールバック遅延 | 最初の 1 フレーズが無音になる可能性 | `speak()` の戻り値判定で即 fallback に回す (非同期失敗は次回以降降格) |

## 12. Codex レビューでの主な変更点 (2026-04-19)

1. **FLUSH 即時化のため Channel と活動 job を分離** (§4.3) — Channel だけで回すと合成・再生完了待ちで FLUSH が後ろに並ぶ致命問題を修正
2. **API キーを `?key=` ではなく `x-goog-api-key` + `X-Android-Package` + `X-Android-Cert` ヘッダに** (§3.4) — Google の Android 制限が発動するヘッダ形式に変更、予算アラート・使用量監視を前提化
3. **`PcmAudioPlayer` を per-utterance `AudioTrack` に変更** (§4.4) — 単一使い回し + marker は状態管理が複雑で破綻しやすいため `MODE_STATIC` で発話ごと生成に変更
4. **AudioFocus の解放パスを全経路で明文化** (§4.5 新設) — `request()` と `abandon()` を 1:1 ペアに保証
5. **401/403 は恒久エラー扱いでセッション中 primary 停止** (§7) — 無駄な再試行をやめ、`sessionDisabled` フラグ + telemetry で通知
6. **`BuildKonfig` は `AppConfig` 経由で DI 注入** (§8.1 新設) — `core/navigation` から `composeApp` BuildKonfig を直接触れないモジュール境界を明示
7. **`ttsReadyJob` で collector 寿命を管理** (§5.2.1 新設) — start/stop 跨ぎで stale engine が UI 状態を揺らさないように
8. **Step 0: 実 API スパイクを追加** (§6) — 認証ヘッダ・voice 名・encoding・sample rate を実機で確認してから本実装に入る
9. **LRU キャッシュを今回スコープに格上げ** (§9.1 新設) — 同一文言が多いナビ用途で通信量・レイテンシ両方が大幅改善するため
