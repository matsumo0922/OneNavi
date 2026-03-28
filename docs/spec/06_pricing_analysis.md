# 06. Pricing Analysis

## Premise

OneNavi は OSS として公開し、各ユーザーが自分の API キーを設定する前提。よって、各ユーザーが個別に無料枠を消費する。本分析は「1人のユーザーが個人利用する場合」の試算。

## Service-by-Service Analysis

### 1. Mapbox Maps SDK

| Item | Value |
|---|---|
| Free Tier | **25,000 MAU/month** |
| Billing Unit | Monthly Active User |
| Individual Usage | **1 MAU** |
| Verdict | **完全に無料枠内** |

MAU は「月に 1 回でもアプリを起動したデバイス」= 1。個人利用なら 1 MAU で固定。

### 2. Mapbox Navigation SDK

| Item | Value |
|---|---|
| Free Tier | **100 MAU / 1,000 trips per month** |
| Billing Unit | MAU + Trips |
| Individual Usage | 1 MAU, 月 30-60 trips（毎日 1-2 回ナビ利用として） |
| Verdict | **完全に無料枠内** |

**重要: ナビセッション中の Directions API 呼び出し（リルート等）は無料。** セッション外の Directions API 呼び出しのみ別カウント。

### 3. Mapbox Directions API (セッション外)

| Item | Value |
|---|---|
| Free Tier | **100,000 requests/month** |
| Billing Unit | Request |
| Individual Usage | 月 100-200 requests（ルートプレビュー、検索時のルート計算） |
| Verdict | **完全に無料枠内** |

### 4. Mapbox Geocoding API

| Item | Value |
|---|---|
| Free Tier | **100,000 requests/month** |
| Billing Unit | Request |
| Individual Usage | 月 50-100 requests（intent share がメインなので検索頻度は低い） |
| Verdict | **完全に無料枠内** |

### 5. Azure Cognitive Services TTS

| Item | Value |
|---|---|
| Free Tier | **500,000 characters/month** (Neural) |
| Billing Unit | Characters |
| Individual Usage Estimate | 下記参照 |

**使用量試算:**
- 1 回のナビで平均 20 回の音声案内
- 1 回の案内テキスト: 平均 30 文字
- 1 回のナビ: 20 × 30 = 600 文字
- 月 60 回ナビ: 60 × 600 = **36,000 文字/月**
- 無料枠の **7.2%** しか使わない

| Verdict | **完全に無料枠内** |

**ただし注意:** Azure TTS はリアルタイム API 呼び出しのため、ネットワーク遅延が発生する。対策:
- よく使う定型フレーズ（「右折です」「直進です」等）はキャッシュ
- ネットワーク不通時は Android 内蔵 TTS にフォールバック

### 6. Google Routes API (料金計算用)

| Item | Value |
|---|---|
| Free Tier | **$200 credit/month** (約 40,000 route requests 相当) |
| Billing Unit | Request |
| Individual Usage | 月 30-60 requests（ナビ開始時に 1 回料金取得） |
| Verdict | **完全に無料枠内** |

### 7. OSM Data / MLIT Data

| Item | Value |
|---|---|
| Cost | **完全無料** |
| License | ODbL (OSM), CC BY 4.0 (MLIT) |
| Verdict | ライセンス表記のみ必要 |

## Total Cost Summary (Individual User)

| Service | Monthly Cost |
|---|---|
| Mapbox Maps SDK | **$0** |
| Mapbox Navigation SDK | **$0** |
| Mapbox Directions API | **$0** |
| Mapbox Geocoding API | **$0** |
| Azure TTS | **$0** |
| Google Routes API | **$0** |
| OSM / MLIT Data | **$0** |
| **Total** | **$0/month** |

**結論: 個人利用であれば全サービスが無料枠内に収まる。**

## Scaling Considerations (OSS として多数ユーザーが使う場合)

各ユーザーが自分の API キーを使うため、ユーザー数が増えてもプロジェクト側のコストは増えない。各ユーザーの無料枠内で個人利用は賄える。

ただし、以下の場合はユーザー側で有料プランが必要になる可能性:
- Mapbox Navigation SDK: 月 1,000 trips を超えるヘビーユーザー（配送業者等）
- Azure TTS: 月 500K 文字を超える長距離トラックドライバー等（通常は到達しない）

## Cost Optimization Strategies

1. **TTS キャッシュ**: 定型フレーズをローカルにキャッシュして API 呼び出し削減
2. **Android 内蔵 TTS フォールバック**: Azure TTS 枠を節約したいユーザー向けオプション
3. **Directions API 呼び出し最適化**: セッション外のプレビュー呼び出しを最小限に
4. **Google Routes API は料金計算時のみ**: 不要な呼び出しを避ける
