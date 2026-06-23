# 03. Technology Evaluation & Selection

## Overview

OneNavi の現行方針は、地図表示を Google Maps SDK、経路・案内情報を外部API ライブラリ、補助的な経路再現や料金取得を Google Routes API に分担させる構成である。

過去に検討・実装した旧地図/ナビ provider へ戻す方針はない。新規設計、実装、レビューでは旧 provider の SDK、MCP、skill、公式ドキュメントを参照しない。

---

## 1. Map Display

### Decision

**Google Maps SDK for Android を採用する。**

### Rationale

- OneNavi の現在の地図 feature は Google Maps 前提で実装されている。
- Android 標準の位置情報・権限・Compose 連携と合わせやすい。
- 外部API ライブラリから得た route geometry、渋滞区間、案内地点を overlay として表現できる。

---

## 2. Routing & Navigation Data

### Decision

**ルート検索、turn-by-turn 案内、交差点画像、交通情報は外部API ライブラリを primary source とする。**

### Rationale

- 案内品質と日本語表現を OneNavi 側で無理に再構築しない。
- 公開 repo には provider 実名・製品名・認証情報を露出させない。
- OneNavi 側は外部API ライブラリの抽象 model と Google Maps overlay に責務を限定する。

---

## 3. Google Routes API

### Decision

**Google Routes API は補助用途で利用する。**

### Scope

- 料金表示
- 外部ルート polyline を Google Maps 上で安定表示するための検証・再現
- dev-tools による手動検証

Google Routes API を turn-by-turn 案内の primary source にはしない。

---

## 4. Voice Guidance

### Decision

**案内テキストは外部API ライブラリ由来の情報を優先し、読み上げは Google Cloud TTS と Android 内蔵 TTS の二段構えにする。**

### Rationale

- Cloud TTS は高品質な日本語音声を提供できる。
- Android 内蔵 TTS を fallback にすることでオフライン・低コスト構成でも最低限動作する。
- OneNavi 側で過剰な案内テンプレート分岐を抱えない。

---

## 5. Traffic And Lane Guidance

### Decision

**渋滞、車線、方面看板、交差点画像は外部API ライブラリのデータを primary source とする。**

Google Maps には描画 layer と camera control の責務だけを持たせる。

---

## 6. Android Auto

### Decision

Android Auto は Google Maps / Android for Cars の公開 API で成立する構成に再設計する。

現行 head では不完全な Auto 公開を避けるため、必要な地図 surface、案内 template、通知、foreground service の責務が整理できるまで production 導線には出さない。
