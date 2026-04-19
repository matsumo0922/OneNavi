# 独自ボイスガイダンス実装計画

- 作成日: 2026-04-20
- 対象モジュール: `core:navigation`, `core:model`, `core:resource`
- 対象範囲: 日本語のみ（多言語化は対象外）
- 参照: `docs/note/tts-required-features.md` / `docs/note/drive-supporter-tts-guide.md`

---

## 1. 目的と現状整理

### 1.1 現状の問題

`GuidanceSessionManager.kt:207-210` で `currentStep.instruction`（Google Navigation SDK の `StepInfo.fullInstructionText`）をそのまま TTS に流している。結果:

- SDK が返す文字列に引きずられ、ドライブサポーター相当の整った言い回し（「右手前方向です。」「左側の車線を、お進みください。」）にならない。
- 距離段階（2km / 500m / 300m / 100m / 50m）の段階的予告が一切無い。ステップ遷移の瞬間に 1 回だけ喋るため、実用上ナビとして機能しない。
- 「その先」連続案内、道なり案内、経由地/目的地タイプ別フレーズ、ルート外れ/リルート発話 が未実装。

### 1.2 本計画のスコープ

ドキュメントに挙がっている TTS 機能カテゴリのうち、**Google Navigation SDK / Google Routes API から安定して取れる情報で実現可能な範囲**のみを対象とする。

| カテゴリ | 実装対象 | 理由 |
|---|---|---|
| #1 基本ターンバイターン案内 | ○ | 本計画の主眼 |
| #2 高速道路案内（IC/JCT/SA/料金所・合流・分岐・ランプ） | ○ | `RouteStepInfo.highwayInfo` + Maneuver から抽出可能 |
| #3 レーン案内 | ○ | `NavigationLaneSnapshot` + `isRecommended` から推定 |
| #5 代替ルート（オフルート / リルート） | ○ | `isOffRoute` + `NavState.REROUTING` + `routeChangedListener` |
| #15 道なり案内 | ○ | 次ガイドポイントまでの距離で判定 |
| #16 経由地管理 | ○ | `ArrivalEvent.isFinalDestination` + `RoutePoint.type` |
| #17 ナビセッション管理 | ○ | 既存の `NavigationState` 遷移に発話を紐づけるだけ |
| #4 渋滞情報 | × | 渋滞データソース未整備のため |
| #6 交通規制・事故・障害 | × | 外部 VICS 相当のデータ源なし |
| #7 気象災害・#8 天気 | × | 気象 API 未統合 |
| #9 速度警告・#10 踏切・#11 一時停止・#12 カーブ・#13 ゾーン30・#14 景観 | × | 静的安全 DB / 地形データ未整備 |
| #18 ボイスコントロール | × | 音声認識は独立した別軸 |

**ユーザー方針で除外**: 気象災害、天気予報、速度、踏切、カーブ、ゾーン30、景観、ボイスコントロール。

### 1.3 採用するアーキテクチャ方針

1. SDK テキストの転送をやめ、**構造化スナップショット（`NavigationFeedSnapshot`）から独自にイベントを組み立てる**。
2. フレーズ本文は `core:resource` の `strings.xml` に集約し、`TtsPhraseId` enum がリソース ID との関連を持つ。
3. 距離段階は離散バケット（`DistanceBucket`）で扱い、同一ステップ × 同一バケットは一度しか発話しない。
4. 「その先」連続案内・「道なり」判定・「目的地タイプ別差し替え」など、ドライブサポーターで見られる組み立てルールはイベント→フレーズ列変換の責務に閉じ込める。
5. 既存の `SpeechOrchestrator` / `FallbackTtsEngine` は変更しない。優先度（`GuidancePriority`）とキューモード（`FLUSH` / `ADD`）で割り込みを制御する。

---

## 2. 用語と概念

### 2.1 データ源の再確認

| 種別 | 取得元 | 主な用途 |
|---|---|---|
| `NavigationFeedSnapshot` | `TurnByTurnUpdateBus` ← `NavInfo` | 現在ステップ・次ステップ・残距離（現ステップまで）・レーン |
| `NavigationTripProgressSnapshot` | `Navigator.RemainingTimeOrDistanceChangedListener` | ルート全体の残距離・残時間 |
| `GoogleRoute.steps: ImmutableList<RouteStepInfo>` | `RouteManager.routes` | IC/JCT/料金所（`HighwayInfo`）、事前静的情報 |
| `isOffRoute: StateFlow<Boolean>` | `NavigationSdkManager` | オフルート検出 |
| `NavigationSdkManager.arrivalEvents: SharedFlow<NavigationArrivalSnapshot>` | `ArrivalListener` | 経由地・最終目的地到着 |
| `NavigationState` | `GuidanceSessionManager` 内部 | セッション開始/終了遷移 |

### 2.2 距離バケット（`DistanceBucket`）

ドキュメント §1「基本ターンバイターン案内」で列挙されている離散距離から、**一般道と高速道路に応じて発話タイミングを切り替える**運用。

| バケット | 一般道で使うか | 高速で使うか | 備考 |
|---|---|---|---|
| `AT_2KM` (〜2000m) | × | ○ | 高速の予告。Nav ドキュメント §2「およそ2km先、サービスエリア入口です」 |
| `AT_1KM` (〜1000m) | × | ○ | 高速の主予告 |
| `AT_500M` (〜500m) | ○ | ○ | 一般道の予告。ドキュメント例「およそ500m先、料金所です」 |
| `AT_300M` (〜300m) | ○ | × | 一般道の主予告 |
| `AT_100M` (〜100m) | ○ | ○ | 直前予告。「まもなく」タイミング修飾と組合せ |
| `AT_50M` (〜50m) | ○ | ○ | 最終予告 |

バケット決定ロジック:

- 現ステップが高速道路系マニューバ（`ON_RAMP` / `OFF_RAMP` / `MERGE` / `FORK`）または現在地が `RouteStepInfo.highwayInfo != null` の区間を走行中 → 高速側。
- それ以外 → 一般道側。

各バケット閾値の `lowerBound`/`upperBound` は `DistanceBucket` enum の定数として持つ（§4.2 参照）。「距離が閾値を**下抜け**した瞬間」を発話トリガとして使う。

### 2.3 タイミング修飾（`TimingModifier`）

ドキュメント §1 の #76-79 に対応する文頭修飾。

| enum 値 | フレーズ | 発話条件 |
|---|---|---|
| `IMMINENT` | 「まもなく、」 | 距離バケットが `AT_100M` / `AT_50M` |
| `UPCOMING` | 「この先、」 | 距離バケットが `AT_2KM`〜`AT_300M` かつ「連続案内」でない |
| `NEXT` | 「その先、」 | 直前の発話が連続する案内の先頭（読点止め）だった場合の後続 |
| `FURTHER` | 「更に、」 | 直前の発話が既に `NEXT` だった場合のさらに後続 |
| `NONE` | （無し） | 距離付きフレーズ（「およそ300m先、」）を冠に置く場合 |

### 2.4 フレーズ列モデル

ドキュメント 5章の「フレーズ組み立ての基本ルール」をそのまま採用する:

> 1つの `PhraseData` 内の `onlineTtsPhraseData[]` が **1つの案内文** を構成する。配列の各要素が1つのフレーズ断片で、インデックス順に連結再生される。

本アプリでは `GuidancePhrase = List<PhraseSegment>` として扱い、発話時には `segments.joinToString(separator = "")` して `SpeechOrchestrator.enqueue()` に渡す。

### 2.5 優先度

`GuidancePriority` を定義し、`SpeechOrchestrator` のキューモード切り替えに利用。

| 優先度 | 用途 | キューモード |
|---|---|---|
| `CRITICAL` | ナビ開始/終了、オフルート、リルート完了 | `FLUSH`（進行中発話を中断） |
| `HIGH` | 50m / 100m の直前案内、到着案内 | `FLUSH` |
| `NORMAL` | 300m / 500m / 1km / 2km 予告、レーン案内 | `ADD` |
| `LOW` | 道なり通知、道路種別切替通知 | `ADD` |

---

## 3. 発話する案内のカタログ

ドキュメントのフレーズ ID を引用しつつ、OneNavi で実装するフレーズの「言い回し」と組み立てパターンを確定させる。以降の `#NNN` はドライブサポーター TTS DB の ID。

### 3.1 セッション制御（#17）

| 案内 | タイミング | フレーズ構成（ID） | 出力例 |
|---|---|---|---|
| ナビ開始 | `startSession()` 直後 | #48 + #49 | 「音声案内を開始します。実際の交通規制に従って走行してください。」 |
| 目的地接近 | `arrivalEvents` with `isFinalDestination=true` & `RoutePoint.type` | `#51` or タイプ別（§3.2） | 「目的地付近です。」 |
| ナビ終了 | 到着確定時 | #53 | 「お疲れ様でした。」 |

タイプ別差し替え（目的地 waypoint の属性）:

| `RoutePoint.type`（新設または既存 enum） | フレーズ ID |
|---|---|
| `GENERIC` | #51 「目的地付近です。」 |
| `STATION` | 「駅入口付近です。」 |
| `BUS_STOP` | 「バス停付近です。」 |
| `AIRPORT` | 「空港付近です。」 |
| `FERRY_TERMINAL` | 「フェリー乗り場付近です。」 |

※ 現状 `RoutePoint` にタイプがあるかはコード調査で未確認。存在しない場合は本計画では **`GENERIC` のみ対応**とし、タイプ情報の型追加は後続フェーズへ。実装時に現物を確認して判断する。

### 3.2 経由地管理（#16）

| 案内 | タイミング | フレーズ構成 | 出力例 |
|---|---|---|---|
| 経由地接近 | `arrivalEvents` with `isFinalDestination=false` | #50 | 「経由地付近です。」 |

`NavigationSdkManager.continueToNextDestination()` は既存のまま。**現状フレーズは発話していない**ので追加する。

### 3.3 代替ルート（#5）

| 案内 | タイミング | フレーズ構成 | 出力例 |
|---|---|---|---|
| オフルート | `isOffRoute` false→true | #54 | 「ルートから外れました。」 |
| オンルート復帰 | `isOffRoute` true→false | #55 | 「ルートに戻りました。」 |
| リルート完了 | `routeChangedListener` でルート差し替え | #56 + #57 | 「新しいルートが見つかりました。新しいルートで案内します。」 |

### 3.4 基本ターンバイターン（#1）

**8 方向のフレーズ**（ドキュメント §1, #32-47, #689）:

| `ManeuverModifier` | 読点止め（連結用） | 句点止め（文末用） |
|---|---|---|
| `STRAIGHT` | 「直進方向、」(#32) | 「直進です。」(#40) / 「直進方向です。」(#689) |
| `SLIGHT_RIGHT` | 「斜め右方向、」(#33) | 「斜め右方向です。」(#41) |
| `RIGHT` | 「右方向、」(#34) | 「右方向です。」(#42) |
| `SHARP_RIGHT` | 「右手前方向、」(#35) | 「右手前方向です。」(#43) |
| `UTURN` | 「戻る方向、」(#36) | 「戻る方向です。」(#44) |
| `SHARP_LEFT` | 「左手前方向、」(#37) | 「左手前方向です。」(#45) |
| `LEFT` | 「左方向、」(#38) | 「左方向です。」(#46) |
| `SLIGHT_LEFT` | 「斜め左方向、」(#39) | 「斜め左方向です。」(#47) |

※ ドライブサポーター基準で `SHARP_RIGHT`/`SHARP_LEFT` を「右手前」「左手前」に割り当てる（急な折り返し＝手前方向のニュアンス）。

**距離フレーズ（#3-18）**: 50/100/200/300/400/500/600/700/800/900/1000m、2/3/4/5/10km。

**組み立てパターン**:

```
[基本] <距離フレーズ> + <方向(文末)>
例: 「およそ300m先、右方向です。」 (#6 + #42)

[タイミング付] <タイミング修飾> + <方向(文末)>
例: 「まもなく、左方向です。」 (#78 + #46)

[連続案内] <距離> + <方向(連結)> → <その先 or 更に> + <次の方向(文末)>
例: 「およそ300m先、右方向、その先、分岐です。」
```

**距離バケット別の組み立てルール**:

| バケット | タイミング修飾 | 距離フレーズ | 方向 |
|---|---|---|---|
| `AT_2KM` | 無し | 「およそ2km先、」 | 文末 |
| `AT_1KM` | 無し | 「およそ1km先、」 | 文末 |
| `AT_500M` | 無し | 「およそ500m先、」 | 文末 |
| `AT_300M` | 無し | 「およそ300m先、」 | 文末 |
| `AT_100M` | 「まもなく、」 | 無し | 文末 |
| `AT_50M` | 「まもなく、」 | 無し | 文末 |

`AT_50M` は直前 100m で発話済みの場合は抑制（`DistanceBucket` ごとの重複抑制で自動的に処理）。

### 3.5 高速道路案内（#2）

現ステップまたは次ステップが高速関連のとき、§3.4 の「方向」の代わりに以下を差し込む:

| 条件 | フレーズ ID | 例 |
|---|---|---|
| `ManeuverType.ON_RAMP` & `HighwayPointType.INTERCHANGE` | #67「高速入口です。」 | 「およそ1km先、高速入口です。」 |
| `ManeuverType.OFF_RAMP` & `HighwayPointType.INTERCHANGE` | #68「高速出口です。」 | |
| `HighwayPointType.TOLL_GATE` | #69「料金所です。」 | 「およそ500m先、料金所です。」 |
| `ManeuverType.FORK`（高速区間） | #59「分岐です。」 | |
| 連続分岐（次ステップも FORK で距離が閾値以内） | #60 追加 | 「まもなく、分岐、さらに分岐が続きます。」 |
| `ManeuverType.MERGE` & driving side = RIGHT | #62「右からの合流が有ります。」 | |
| `ManeuverType.MERGE` & driving side = LEFT | #63「左からの合流が有ります。」 | |
| `ManeuverType.MERGE`（side 不明） | #64「合流が有ります。」 | |
| Entry to toll road / auto-only road（`HighwayInfo` に入る遷移） | #767 / #768 | 「有料区間に入ります。」「自動車専用道に入ります。」 |

IC/JCT/料金所の「名前」（`HighwayInfo.name`）は初期実装では**発話しない**。TTS 合成でキャッシュヒットさせるため固定フレーズで運用する。名前読み上げは後続フェーズ（カスタム TTS が必要）。

### 3.6 レーン案内（#3）

`NavigationLaneSnapshot.isRecommended` を使って推奨車線を特定する。ドキュメント §3 の 6 種パターンのうち、Google Navigation SDK から得られる情報で表現できるのは次の 3 パターンに限定する。

#### 表現可能なパターン

| パターン | 条件 | フレーズ ID |
|---|---|---|
| **右側/左側の車線を** | 推奨車線が右端寄り / 左端寄り | #488 「右側の車線を」/ #489 「左側の車線を」 |
| **右から/左から N 番目の車線を** | 推奨車線のインデックスが左右端から 2 以上内側 | #502-511 |
| **中央の車線を** | 全車線数 3 以上で推奨が中央のみ | #512「中央の車線を」 |

**「を」→「に」の使い分け**（ドキュメント §3）:

- `お進みください。` (#487) → 現レーンで OK のとき（既に推奨レーンに乗っている or 方向が一致）
- `お入りください。` (#525) → 車線変更を促すとき（推奨レーンが現在と異なる）

Google SDK からは「現在どの車線を走っているか」は取得できないため、**初期実装では常に「お進みください」で統一**し、「お入りください」は後続フェーズに回す。ドキュメントにも「（ユーザー方針で）言い回しはドキュメント通り」と明記されているが、条件判定に必要な入力が足りないので安全側に倒す。

#### レーン案内の発話タイミング

- 現ステップがターン系マニューバ（`TURN`/`FORK`/`MERGE`/`ON_RAMP`/`OFF_RAMP`）で `lanes` が非空のとき。
- 距離バケットが `AT_500M`（高速）または `AT_300M`（一般道）になったタイミング一度のみ。
- `AT_50M`/`AT_100M` の直前予告とは別イベントとして発火し、**基本ターンバイターン案内と異なる発話**になる。

フレーズ構成例:

```
[タイミング] + [車線指示] + [お進みください]
例: 「この先、左側の車線を、お進みください。」 (#76 + #489 + #487)
例: 「およそ500m先、右から2番目の車線を、お進みください。」 (#8相当 + #540 + #487)
```

**合流後/分岐後のレーン案内（#563-564）**も対応する:

- 現ステップの完了直後（= ステップ遷移イベント）、次ステップの推奨レーンが確定していて条件を満たせば発火。
- 「合流後、」(#563) / 「分岐後、」(#564) + 車線指示 + #487。

### 3.7 道なり案内（#15）

| 条件 | フレーズ |
|---|---|
| 次ガイドポイントまで 1000m ≤ dist < 5000m | 「しばらく道なりです。」(#30) |
| 次ガイドポイントまで 5000m ≤ dist | 「5km以上道なりです。」(#31) |

**重複抑制**: 同一ステップで 1 度のみ発話。ステップ開始直後に次ステップまでの距離を見て判定。

### 3.8 ナビセッション状態変化（#17 補助）

| 遷移 | フレーズ |
|---|---|
| 一般道 → 高速道 | 「高速道のルートに切り替わりました。」(#570) |
| 高速道 → 一般道 | 「一般道のルートに切り替わりました。」(#569) |

**判定**: 現ステップの `highwayInfo` の有無変化。ただし `RouteStepInfo` 側にしか `highwayInfo` が無く、現在走行中のステップを特定する必要があるため、`NavigationFeedSnapshot.currentStep.maneuver` の分類と合わせて判断する。初期実装はこの 2 フレーズを**Phase 2** に回し、Phase 1 では対応しない。

---

## 4. 主要クラス設計

### 4.1 パッケージ構成

```
core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/
  guidance/                                ← 新規
    GuidanceEvent.kt                      ← sealed interface
    GuidancePriority.kt                   ← enum
    DistanceBucket.kt                     ← enum
    TimingModifier.kt                     ← enum
    TtsPhraseId.kt                        ← enum (strings.xml との対応)
    PhraseBuilder.kt                      ← TtsPhraseId/param → 文字列
    GuidancePlanner.kt                    ← Snapshot → GuidanceEvent[]
    GuidanceEventDispatcher.kt            ← Event → SpeechOrchestrator
    RoadClassClassifier.kt                ← 高速/一般道判定
    SpokenGuideKey.kt                     ← 重複抑制キー
  GuidanceSessionManager.kt               ← 既存、Planner/Dispatcher を組み込み
```

### 4.2 型定義のスケッチ

```kotlin
// core:model に配置（UI と共有するため commonMain）
sealed interface GuidanceEvent {
    val priority: GuidancePriority
    val phrase: GuidancePhrase

    data class Maneuver(
        val stepId: String,
        val bucket: DistanceBucket,
        val maneuverType: ManeuverType,
        val modifier: ManeuverModifier?,
        val roadClass: RoadClass,
        val continuesTo: ManeuverType?,                  // 連続案内で「その先」に使う
        override val priority: GuidancePriority,
        override val phrase: GuidancePhrase,
    ) : GuidanceEvent

    data class Lane(...) : GuidanceEvent
    data class HighwayFacility(...) : GuidanceEvent      // IC/JCT/料金所/SA
    data class Straightforward(...) : GuidanceEvent      // しばらく道なり
    data class OffRoute(...) : GuidanceEvent
    data class OnRouteRecovered(...) : GuidanceEvent
    data class Rerouted(...) : GuidanceEvent
    data class ViaWaypointApproach(...) : GuidanceEvent
    data class DestinationApproach(...) : GuidanceEvent
    data class SessionStarted(...) : GuidanceEvent
    data class SessionFinished(...) : GuidanceEvent
}

data class GuidancePhrase(val segments: ImmutableList<PhraseSegment>)

sealed interface PhraseSegment {
    data class Fixed(val phraseId: TtsPhraseId) : PhraseSegment
    data class Distance(val bucket: DistanceBucket) : PhraseSegment  // 「およそ300m先、」
    // 将来、動的な名称（IC 名など）を入れるならここに追加
}
```

`DistanceBucket` の例:

```kotlin
enum class DistanceBucket(
    val phraseId: TtsPhraseId,
    val thresholdMeters: Int,
) {
    AT_2KM(TtsPhraseId.DISTANCE_2KM, 2000),
    AT_1KM(TtsPhraseId.DISTANCE_1KM, 1000),
    AT_500M(TtsPhraseId.DISTANCE_500M, 500),
    AT_300M(TtsPhraseId.DISTANCE_300M, 300),
    AT_100M(TtsPhraseId.DISTANCE_100M, 100),
    AT_50M(TtsPhraseId.DISTANCE_50M, 50),
}
```

### 4.3 `GuidancePlanner`

責務: `GuidanceSessionUpdate`（既存 `GuidanceUpdate` を拡張）を入力に、このティックで発話すべき `GuidanceEvent` 群を返す。

```kotlin
internal class GuidancePlanner(
    private val spokenKeys: SpokenGuideKeyStore,   // 重複抑制用
    private val classifier: RoadClassClassifier,
) {
    fun plan(
        snapshot: NavigationFeedSnapshot,
        tripProgress: NavigationTripProgressSnapshot?,
        isOffRoute: Boolean,
        route: GoogleRoute,
    ): List<GuidanceEvent> { ... }
}
```

内部ロジック:

1. `currentStep` と `nextStep` から道路種別を決定。
2. `distanceToCurrentStepMeters` と閾値から、このティックで下抜けしたバケットを算出。
3. そのバケットについて未発話なら `Maneuver` イベントを生成。連続案内（`distanceFromPreviousMeters(nextStep)` が 200m 未満など）であれば `continuesTo` を埋める。
4. 高速施設イベント（IC/JCT/料金所/SA）が該当すれば `HighwayFacility` を追加。
5. レーン条件を満たせば `Lane` を追加。
6. 道なり条件を満たせば `Straightforward`。
7. `isOffRoute` 変化、`sessionState` 変化はこのメソッドの外でイベント化（後述）。

### 4.4 `GuidanceEventDispatcher`

役割: `GuidancePlanner` から得た `GuidanceEvent[]` を、`PhraseBuilder` で文字列化し `SpeechOrchestrator` に投入する。優先度でキューモードを選択。

```kotlin
internal class GuidanceEventDispatcher(
    private val context: Context,
    private val orchestrator: SpeechOrchestrator,
    private val phraseBuilder: PhraseBuilder,
) {
    fun dispatch(event: GuidanceEvent) {
        val text = phraseBuilder.build(event.phrase)
        val mode = when (event.priority) {
            GuidancePriority.CRITICAL, GuidancePriority.HIGH -> SpeechQueueMode.FLUSH
            else -> SpeechQueueMode.ADD
        }
        orchestrator.enqueue(text = text, flush = (mode == SpeechQueueMode.FLUSH))
    }
}
```

### 4.5 `PhraseBuilder`

責務: `GuidancePhrase` の各 `PhraseSegment` を `strings.xml` から解決して連結する。

`core:navigation` は Android 専用モジュールなので、解決方法は次のいずれか。

- **案 A**: `Context.getString(R.string.xxx)` を使う。
  - `core:navigation` に Android `R.string.*` を持たせるか、`core:resource` の Android `res` 側に TTS 用 strings を追加する。
- **案 B**: Compose Resources の `getString()` を使う（`org.jetbrains.compose.resources.getString`）。`core:resource` の `composeResources/values/strings.xml` にそのまま追加できる。
  - 非 Composable からの呼び出しは `suspend fun getString(resource: StringResource)` になる。
  - `SpeechOrchestrator` は `Main` ディスパッチャで動いているため suspend で問題なし。

**採用方針**: **案 B（Compose Resources）**。理由:

1. 既存の strings は全て `core:resource` に集約されており、スタイル統一できる。
2. iOS に TTS を広げる余地（現計画では対象外だが、将来の可能性）を残す。
3. Android `R.string` を増やすと `core:resource` と `core:navigation` の二重管理になる。

`PhraseBuilder` の実装例:

```kotlin
internal class PhraseBuilder {
    // suspend: Compose Resources の getString() が suspend のため
    suspend fun build(phrase: GuidancePhrase): String = buildString {
        phrase.segments.forEach { segment ->
            when (segment) {
                is PhraseSegment.Fixed -> append(getString(segment.phraseId.resource))
                is PhraseSegment.Distance -> append(getString(segment.bucket.phraseId.resource))
            }
        }
    }
}
```

`TtsPhraseId` と `StringResource` の関連は enum に持たせる:

```kotlin
enum class TtsPhraseId(val resource: StringResource) {
    NAVIGATION_STARTED(Res.string.tts_navigation_started),
    FOLLOW_TRAFFIC_RULES(Res.string.tts_follow_traffic_rules),
    DISTANCE_50M(Res.string.tts_distance_50m),
    DISTANCE_100M(Res.string.tts_distance_100m),
    DISTANCE_300M(Res.string.tts_distance_300m),
    DISTANCE_500M(Res.string.tts_distance_500m),
    DISTANCE_1KM(Res.string.tts_distance_1km),
    DISTANCE_2KM(Res.string.tts_distance_2km),
    TIMING_IMMINENT(Res.string.tts_timing_imminent),
    TIMING_UPCOMING(Res.string.tts_timing_upcoming),
    TIMING_NEXT(Res.string.tts_timing_next),
    TIMING_FURTHER(Res.string.tts_timing_further),
    DIR_STRAIGHT_MID(Res.string.tts_direction_straight_mid),       // 「直進方向、」
    DIR_STRAIGHT_END(Res.string.tts_direction_straight_end),       // 「直進方向です。」
    DIR_RIGHT_MID(Res.string.tts_direction_right_mid),             // 「右方向、」
    DIR_RIGHT_END(Res.string.tts_direction_right_end),             // 「右方向です。」
    DIR_SHARP_RIGHT_MID(Res.string.tts_direction_sharp_right_mid), // 「右手前方向、」
    DIR_SHARP_RIGHT_END(Res.string.tts_direction_sharp_right_end),
    ... // 全 8 方向 × 2
    LANE_RIGHT_SIDE(Res.string.tts_lane_right_side),
    LANE_LEFT_SIDE(Res.string.tts_lane_left_side),
    LANE_CENTER(Res.string.tts_lane_center),
    LANE_PROCEED(Res.string.tts_lane_proceed),                     // 「お進みください。」
    LANE_ENTER(Res.string.tts_lane_enter),                         // 「お入りください。」
    FACILITY_HIGHWAY_ENTRANCE(Res.string.tts_facility_highway_entrance),
    FACILITY_HIGHWAY_EXIT(Res.string.tts_facility_highway_exit),
    FACILITY_TOLL_GATE(Res.string.tts_facility_toll_gate),
    FACILITY_MERGE_RIGHT(Res.string.tts_facility_merge_right),
    FACILITY_MERGE_LEFT(Res.string.tts_facility_merge_left),
    FACILITY_MERGE(Res.string.tts_facility_merge),
    FACILITY_FORK_MID(Res.string.tts_facility_fork_mid),
    FACILITY_FORK_END(Res.string.tts_facility_fork_end),
    FACILITY_FORK_CONTINUES(Res.string.tts_facility_fork_continues),
    STRAIGHT_SHORT(Res.string.tts_straightforward_short),          // 「しばらく道なりです。」
    STRAIGHT_LONG(Res.string.tts_straightforward_long),            // 「5km以上道なりです。」
    OFF_ROUTE(Res.string.tts_off_route),
    ON_ROUTE_RECOVERED(Res.string.tts_on_route_recovered),
    REROUTED_FOUND(Res.string.tts_rerouted_found),
    REROUTED_START(Res.string.tts_rerouted_start),
    WAYPOINT_APPROACH(Res.string.tts_waypoint_approach),
    DESTINATION_APPROACH(Res.string.tts_destination_approach),
    NAVIGATION_FINISHED(Res.string.tts_navigation_finished),
    ;
}
```

### 4.6 重複抑制（`SpokenGuideKeyStore`）

```kotlin
internal class SpokenGuideKeyStore {
    private val spoken = mutableSetOf<SpokenGuideKey>()

    fun markSpoken(key: SpokenGuideKey): Boolean = spoken.add(key)
    fun reset() { spoken.clear() }
    fun forgetStep(stepId: String) { spoken.removeAll { it.stepId == stepId } }
}

data class SpokenGuideKey(
    val stepId: String,
    val category: Category,
    val bucket: DistanceBucket?,
) {
    enum class Category { MANEUVER, LANE, HIGHWAY_FACILITY, STRAIGHTFORWARD }
}
```

`stepId` は `NavigationStepSnapshot` に無いので、新規にハッシュベースで算出する（`maneuver` + `roadName` + 累積距離の合成キー）。もしくは `NavigationFeedSnapshot.currentStep` の参照 ID を SDK 内部から得る手段があればそれを使う。

### 4.7 `GuidanceSessionManager` への組み込み

現状の `applyGuidanceUpdate()` (L161-211) を以下のように再編する:

```kotlin
private fun applyGuidanceUpdate(
    routeId: String,
    update: GuidanceUpdate,
) {
    if (activeRoute?.id != routeId) return

    val navInfo = update.navInfo
    val tripProgress = update.tripProgress
    val navState = navInfo?.navState
    val isOffRoute = update.isOffRouteRaw || navState == NavState.REROUTING

    // UI state 更新（従来通り）
    updateGuidanceUiState(navInfo, tripProgress, isOffRoute)

    // イベント生成 → 発話
    if (navInfo != null && navState == NavState.ENROUTE) {
        val events = guidancePlanner.plan(
            snapshot = navInfo,
            tripProgress = tripProgress,
            isOffRoute = isOffRoute,
            route = activeRoute ?: return,
        )
        events.forEach { event ->
            scope.launch { dispatcher.dispatch(event) }
        }
    }

    // オフルート/復帰検出は前回値との比較で
    handleOffRouteTransition(isOffRoute)
}
```

- `isOffRouteTransition` 検出で `OffRoute` / `OnRouteRecovered` イベントを生成。
- `routeChangedListener`（`NavigationSdkManager` 側）で `Rerouted` イベントを `_guidanceEvents` に流す。
- セッション開始/終了は既存の `startSession()`/`stopSession()` 冒頭でイベント発火。
- 到着は `arrivalEvents.collect` に追加するだけ。

### 4.8 UI との共有（後続余地）

`GuidanceEvent` は `core:model` に配置することで、将来 UI（バナー表示、振動、音のみの通知）にも流せる。現計画では TTS 発火のみを実装する。

---

## 5. `strings.xml` 追加内容

`core/resource/src/commonMain/composeResources/values/strings.xml` に追加するキーを以下に列挙する。**プレフィックスは `tts_`** で統一する。

```xml
<!-- TTS Navigation Session -->
<string name="tts_navigation_started">音声案内を開始します。</string>
<string name="tts_follow_traffic_rules">実際の交通規制に従って走行してください。</string>
<string name="tts_navigation_finished">お疲れ様でした。</string>

<!-- TTS Distance -->
<string name="tts_distance_50m">およそ50m先、</string>
<string name="tts_distance_100m">およそ100m先、</string>
<string name="tts_distance_300m">およそ300m先、</string>
<string name="tts_distance_500m">およそ500m先、</string>
<string name="tts_distance_1km">およそ1km先、</string>
<string name="tts_distance_2km">およそ2km先、</string>

<!-- TTS Timing -->
<string name="tts_timing_imminent">まもなく、</string>
<string name="tts_timing_upcoming">この先、</string>
<string name="tts_timing_next">その先、</string>
<string name="tts_timing_further">更に、</string>

<!-- TTS Direction (mid = 読点止め, end = 句点止め) -->
<string name="tts_direction_straight_mid">直進方向、</string>
<string name="tts_direction_straight_end">直進方向です。</string>
<string name="tts_direction_slight_right_mid">斜め右方向、</string>
<string name="tts_direction_slight_right_end">斜め右方向です。</string>
<string name="tts_direction_right_mid">右方向、</string>
<string name="tts_direction_right_end">右方向です。</string>
<string name="tts_direction_sharp_right_mid">右手前方向、</string>
<string name="tts_direction_sharp_right_end">右手前方向です。</string>
<string name="tts_direction_uturn_mid">戻る方向、</string>
<string name="tts_direction_uturn_end">戻る方向です。</string>
<string name="tts_direction_sharp_left_mid">左手前方向、</string>
<string name="tts_direction_sharp_left_end">左手前方向です。</string>
<string name="tts_direction_left_mid">左方向、</string>
<string name="tts_direction_left_end">左方向です。</string>
<string name="tts_direction_slight_left_mid">斜め左方向、</string>
<string name="tts_direction_slight_left_end">斜め左方向です。</string>

<!-- TTS Highway Facility -->
<string name="tts_facility_highway_entrance">高速入口です。</string>
<string name="tts_facility_highway_exit">高速出口です。</string>
<string name="tts_facility_toll_gate">料金所です。</string>
<string name="tts_facility_service_area">サービスエリア入口です。</string>
<string name="tts_facility_merge_right">右からの合流が有ります。</string>
<string name="tts_facility_merge_left">左からの合流が有ります。</string>
<string name="tts_facility_merge">合流が有ります。</string>
<string name="tts_facility_fork_mid">分岐、</string>
<string name="tts_facility_fork_end">分岐です。</string>
<string name="tts_facility_fork_continues">さらに分岐が続きます。</string>

<!-- TTS Lane -->
<string name="tts_lane_right_side">右側の車線を</string>
<string name="tts_lane_left_side">左側の車線を</string>
<string name="tts_lane_center">中央の車線を</string>
<string name="tts_lane_first_right">1番右の車線を</string>
<string name="tts_lane_first_left">1番左の車線を</string>
<string name="tts_lane_nth_from_right_2">右から2番目の車線を</string>
<string name="tts_lane_nth_from_right_3">右から3番目の車線を</string>
<string name="tts_lane_nth_from_left_2">左から2番目の車線を</string>
<string name="tts_lane_nth_from_left_3">左から3番目の車線を</string>
<string name="tts_lane_proceed">お進みください。</string>
<string name="tts_lane_enter">お入りください。</string>
<string name="tts_lane_after_merge">合流後、</string>
<string name="tts_lane_after_fork">分岐後、</string>

<!-- TTS Straightforward -->
<string name="tts_straightforward_short">しばらく道なりです。</string>
<string name="tts_straightforward_long">5km以上道なりです。</string>

<!-- TTS Off Route / Reroute -->
<string name="tts_off_route">ルートから外れました。</string>
<string name="tts_on_route_recovered">ルートに戻りました。</string>
<string name="tts_rerouted_found">新しいルートが見つかりました。</string>
<string name="tts_rerouted_start">新しいルートで案内します。</string>

<!-- TTS Waypoint / Destination -->
<string name="tts_waypoint_approach">経由地付近です。</string>
<string name="tts_destination_approach">目的地付近です。</string>
```

合計約 55 件。ドキュメントの 768 件のうち、本計画の対象範囲に絞っての最小セット。

---

## 6. 実装フェーズ

時間をかけて緻密に、という指示に従い小さく分割する。各フェーズは独立してビルド可能・単体で PR 化できる粒度。

### Phase 0 — 基盤整備（動作変化なし）

1. `core:resource` の `strings.xml` に §5 の全キーを追加。
2. `core:model` に以下を追加:
   - `GuidanceEvent` sealed interface
   - `GuidancePriority` enum
   - `DistanceBucket` enum
   - `TimingModifier` enum
   - `RoadClass` enum (`LOCAL` / `HIGHWAY`)
   - `GuidancePhrase` / `PhraseSegment`
3. `core:navigation/androidMain` に以下の骨組みを追加:
   - `guidance/TtsPhraseId.kt`
   - `guidance/PhraseBuilder.kt`（まだ `GuidanceSessionManager` からは呼ばない）
   - `guidance/SpokenGuideKeyStore.kt`
4. ビルド通過確認 + detekt パス。

### Phase 1 — セッション/オフルート/リルートの発話を独自化

現状 SDK テキストをそのまま喋っているロジックは**残したまま**、追加イベントだけ差し込む。既存発話と重ならないように、Phase 1 の間は SDK テキスト直接発話は維持し、並行動作を確認する。

1. `GuidanceEventDispatcher` 実装。
2. `GuidanceSessionManager.startSession()` 冒頭で `SessionStarted` イベント発火（#48 + #49）。
3. `onFinalDestinationArrival()` で `DestinationApproach` + `SessionFinished` を発火。
4. `arrivalEvents` で `isFinalDestination=false` のとき `ViaWaypointApproach` を発火。
5. `isOffRoute` 遷移検出。`OffRoute` / `OnRouteRecovered` を発火。
6. `routeChangedListener` で `Rerouted` を発火。

検証: 実機で起動→到着まで、SDK テキストと独自フレーズが両方喋られる状態を許容しつつ、独自フレーズが意図通りに発火することを確認。

### Phase 2 — 基本ターンバイターンの発話を独自化（SDK テキスト廃止）

**このフェーズで `currentStep.instruction` をそのまま speak する行を削除する**。

1. `RoadClassClassifier` 実装（現ステップの maneuver + `activeRoute.steps` の `highwayInfo` から `RoadClass` を返す）。
2. `GuidancePlanner` の `Maneuver` イベント生成ロジック実装。
   - 距離バケットの下抜け検出（前回の `distanceToCurrentStepMeters` と今回値を比較）。
   - `DistanceBucket.fromDistance(distance, roadClass)`。
   - 連続案内判定（`remainingSteps[0].distanceFromPreviousMeters` が 200m 以下で `continuesTo` を埋める）。
3. `PhraseBuilder` で §3.4 の組み立てパターンを実装。
4. `GuidanceSessionManager.applyGuidanceUpdate()` の `currentStep.instruction` 発話を削除し、`dispatcher.dispatch(event)` に置換。
5. `SpokenGuideKey` による重複抑制をステップ遷移時にリセット。

検証: 実機で一般道・高速道・IC 乗降を含むルートで、距離段階ごとに発話されること。発話内容が §3.4 のパターンに一致すること。

### Phase 3 — 高速道路案内と道なり案内

1. `RouteStepInfo.highwayInfo` の `HighwayPointType` を元に `HighwayFacility` イベントを生成。
2. 合流（`MERGE`）/分岐（`FORK`）で左右判定（`drivingSide` + `maneuverModifier`）。
3. 連続分岐（`#60 さらに分岐が続きます`）判定: 次ステップも `FORK` かつ距離 200m 以内なら追加。
4. 道なり判定: `currentStep.distanceFromPreviousMeters` ではなく、現在地から `currentStep` までの残距離が大きく、かつ直前ステップ通過直後に発話。
5. SA 入口（#726）は `HighwayPointType` に `SERVICE_AREA` が現状無いため、後続フェーズに回す（メモとして issue 化）。

### Phase 4 — レーン案内

1. `NavigationLaneSnapshot` の配列から推奨車線インデックスを算出。
2. §3.6 の 3 パターン（右側/左側・N 番目・中央）を `Lane` イベントとして生成。
3. 「合流後」「分岐後」バリエーションは現ステップ完了直後（ステップ遷移）に発火。
4. 「お入りください」は現状データ不足で非対応と明記し、イベント生成側で常に `LANE_PROCEED` を選ぶ。

### Phase 5 — テストと微調整

1. `GuidancePlanner` の単体テスト（JVM test）:
   - 各距離バケット下抜けで期待イベントが 1 回だけ生成される。
   - 連続案内で `continuesTo` が埋まる。
   - オフルート→オンルート遷移でイベントが発火する。
2. `PhraseBuilder` の単体テスト:
   - 各 `GuidanceEvent` から期待フレーズ文字列が得られる。
3. 実機リグレッションテストのチェックリスト作成（`docs/logs/` に追加）。

---

## 7. ケーススタディ（発話例）

### 7.1 一般道で右折 300m 手前

入力: `currentStep` が `TURN_RIGHT`、`distanceToCurrentStepMeters=305`、`roadClass=LOCAL`、次ティックで `distance=295`。

1. 前回 > 300 → 今回 ≤ 300 で `AT_300M` を下抜け。
2. `GuidancePlanner.plan()` が `Maneuver(bucket=AT_300M, modifier=RIGHT, continuesTo=null)` を返す。
3. `PhraseBuilder.build()`:
   - `PhraseSegment.Distance(AT_300M)` → 「およそ300m先、」
   - `PhraseSegment.Fixed(DIR_RIGHT_END)` → 「右方向です。」
4. 最終: **「およそ300m先、右方向です。」**
5. その後 100m で「まもなく、右方向です。」、50m でさらに「まもなく、右方向です。」（ただし `AT_50M` は `AT_100M` から 10 秒以内なら抑制する運用も検討可）。

### 7.2 高速入口 1km 手前

1. `AT_1KM` を下抜け。`ManeuverType=ON_RAMP`、`HighwayInfo.type=INTERCHANGE`。
2. イベント: `HighwayFacility(bucket=AT_1KM, facility=HIGHWAY_ENTRANCE)`。
3. フレーズ: **「およそ1km先、高速入口です。」**

### 7.3 連続案内（右折 → 分岐）

1. 現ステップ `TURN_RIGHT`、次ステップ `FORK_LEFT`、距離差 180m。
2. `continuesTo=FORK` が埋まる。
3. フレーズ: **「およそ300m先、右方向、その先、分岐です。」**
   - segments = [`Distance(AT_300M)`, `Fixed(DIR_RIGHT_MID)`, `Fixed(TIMING_NEXT)`, `Fixed(FACILITY_FORK_END)`]

### 7.4 ルート外れて復帰

1. `isOffRoute` が false→true → `OffRoute`: **「ルートから外れました。」** (`CRITICAL`, FLUSH)
2. `routeChangedListener` 発火 → `Rerouted`: **「新しいルートが見つかりました。新しいルートで案内します。」** (`CRITICAL`, FLUSH)

---

## 8. リスクと未解決事項

| リスク | 影響 | 対応方針 |
|---|---|---|
| `distanceToCurrentStepMeters` の更新粒度（`PROGRESS_DISTANCE_THRESHOLD_METERS=50m`）が粗い | 距離バケット下抜けが取りこぼされる可能性 | `registerServiceForNavUpdates` の NavInfo は別経路で頻度が高い。実機で更新頻度を実測し、不足なら `RemainingTimeOrDistanceChangedListener` の閾値を下げる |
| `NavigationStepSnapshot` に固有 ID が無い | ステップ識別が曖昧 | `(maneuver, roadName, 累積距離)` の合成キーで代替 |
| `isRecommended` が不安定 / 空のことがある | レーン案内が出ない | レーン案内は欠落許容。欠落しても主経路は壊れない設計 |
| Compose Resources の `getString()` 呼び出しが `suspend` | `GuidancePlanner` の副作用が増える | `PhraseBuilder` のみ suspend、`Planner` は純粋関数に保つ |
| SDK テキスト廃止後に発話が重複 | 「右方向です」が 2 回など | Phase 2 の置換と同時に `currentStep.instruction` 発話を削除するコミットを 1 単位で行う |
| `RoutePoint` にタイプ（駅/空港など）情報が無い | §3.1 タイプ別差し替え不可 | 初期実装は `GENERIC` のみ。型追加は後続フェーズ |
| 高速/一般道の切替検出が不完全 | #569/#570 が誤発話 | Phase 1-4 ではこの 2 フレーズを発話しない |

---

## 9. このあとの進め方

1. この計画書のレビュー（お兄ちゃんの確認）。
2. Phase 0 着手 → PR 化。
3. Phase 1 → Phase 2 の順に進行。Phase 2 完了時点で「ナビとして実用的な独自発話」が揃う。
4. Phase 3-5 は体験の質向上。
5. 将来対象外領域（渋滞・気象・安全 DB）は別計画書で立ち上げ。
