# 06. Pricing Analysis

## Premise

OneNavi は OSS として公開し、Google API キーや外部API の認証情報は各ユーザーが自分で用意する前提。公開 repo は共通の認証情報を持たない。

## Service-by-Service Analysis

### 1. Google Maps SDK

| Item | Value |
|---|---|
| Usage | 地図表示、camera、overlay |
| Billing Unit | Google Cloud の Maps Platform 課金体系に従う |
| Individual Usage | 個人利用の通常起動・ナビ表示 |
| Notes | API key はユーザー側で設定する |

### 2. Google Routes API

| Item | Value |
|---|---|
| Usage | 料金取得、route-compare 検証、補助的な route reproduction |
| Billing Unit | Request |
| Individual Usage | ルート検索・ナビ開始時の必要回数だけ |
| Notes | turn-by-turn 案内の primary source にはしない |

### 3. Google Cloud TTS

| Item | Value |
|---|---|
| Usage | 高品質な日本語音声案内 |
| Billing Unit | Characters |
| Fallback | Android 内蔵 TTS |
| Notes | 定型フレーズ cache で呼び出し量を抑える |

### 4. 外部API ライブラリ

| Item | Value |
|---|---|
| Usage | ルート検索、turn-by-turn 案内、渋滞、案内画像 |
| Billing Unit | 別管理の private 実装・利用条件に従う |
| Notes | 認証情報、provider 実名、製品名は公開 repo に含めない |

## Cost Optimization Strategies

1. Cloud TTS の定型フレーズを cache する。
2. Android 内蔵 TTS fallback を常に維持する。
3. Google Routes API は料金取得や検証など必要な用途に限定する。
4. dev tool の API 呼び出しは明示操作時だけにする。
