# 独自ボイスガイダンス実装計画 (rev.3)

- 作成日: 2026-04-20
- 改訂日: 2026-04-20 (Codex 2 回目レビュー反映)
- 対象モジュール: `core:navigation`, `core:model`, `core:resource`
- 対象範囲: 日本語のみ（多言語化は対象外）
- 参照: `docs/note/tts-required-features.md` / `docs/note/drive-supporter-tts-guide.md`

---

## 0. 変更履歴

### rev.3（本改訂、Codex 2 回目レビュー反映）

- **[HIGH]** Phase 1/2 を**完全統合して単一の cutover** に再設計。Phase 1 で「SDK テキスト発話を残しつつ独自発話を併走させる」方針は廃止。新 Phase 1 は骨組み配線のみで発話には到達しない。新 Phase 2 で `speak(currentStep.instruction)` 削除と独自発話投入を**同一コミットで**行う。§6 全面改稿。
- **[HIGH]** `crossedBuckets()` の採用ルールを **「最近接バケット優先」** に反転。例: 505m → 95m と 2 ティックで跨いだ場合、運転上の価値が高い `AT_100M` を採用し `AT_500M` を捨てる。§4.4 書き換え。
- **[HIGH]** リルート検知を routeToken 依存から **「routeToken 優先、null 時は `GoogleRoute.id` + `geometry` サイズ比較にフォールバック」** へ。§4.9 書き換え。
- **[MEDIUM]** `StepTransitionTracker.isSameStep()` に補助条件として **`distanceToCurrentStepMeters` の急増（前回比 +300m 以上）** を加える。距離は進行方向で単調減少するはずなので急増はステップ遷移と解釈する。§4.6 書き換え。
- **[MEDIUM]** `SpeechDispatcher` の Channel を `UNLIMITED` から **`Channel.BUFFERED` (capacity 32) + 同カテゴリ coalesce** へ。連続する同カテゴリイベントは最新のみ残す。§4.7 書き換え。
- **[MEDIUM]** `AT_50M` 抑制を時間ベースから **`stepCounter` ベース** に。「同一 `stepCounter` で `AT_100M` 済みなら `AT_50M` 抑制、`AT_100M` 未発話時のみ `AT_50M` を単独発話許可」。§2.3 / §4.4 書き換え。
- **[LOW]** `PROGRESS_DISTANCE_THRESHOLD_METERS` 調整案を削除（発話ロジックが `tripProgress` を使わないため根拠が薄いという指摘反映）。§8 から該当行を削除。

### rev.2（初版 Codex レビュー反映）

- **[HIGH]** 高速道路案内のスコープを大幅縮小。`GoogleRoutesDataSource.kt:161-188` の `RouteStepInfo` 生成は `highwayInfo=null` / `roadName=""` / `roadRef=null` で固定されており、IC・JCT・料金所・SA の識別情報が一切供給されていない。`#67`「高速入口です」・`#68`「高速出口です」・`#69`「料金所です」・`#569-570`「一般/高速切替」・`#767-768`「有料区間に入ります」などは v1 非対応とし、v2 で datasource 強化とセットで扱う。
- **[HIGH]** `stepId = (maneuver, roadName, cumulativeDistance)` を廃止。`NavigationStepSnapshot` には index / position / 固有 ID に相当するフィールドが無く、`NavigationUpdatesService` 経由で受け取る `StepInfo` も現状スナップショットに sequence を乗せていない。`GoogleRoute.steps` との逆算も route token 不在時に SDK 側で別ルート計算されうる（`NavigationSdkManager.kt:222-226` のコメント参照）。v1 では **session-local な step transition counter** を採用する（詳細 §4.6）。
- **[HIGH]** `PhraseBuilder.build()` を `suspend` とし、`GuidanceEventDispatcher` を `Channel<GuidanceEvent>` + 単一コルーチンの **直列 actor** に再設計。`scope.launch { dispatch(event) }` の多重起動をやめる。
- **[HIGH]** 距離バケットを **ユーザー指定通り `2km / 500m / 100m / 50m` に固定**。`1km` / `300m` は廃止。
- **[MEDIUM]** `GuidanceEvent` は phrasing を持たない `SemanticEvent` に変更。`PhraseComposer` が別工程で segments を生成。`GuidancePlanner` は純関数に保つ。
- **[MEDIUM]** 「その先」連続案内は v1 では scope 外。
- **[MEDIUM]** `#566` / `#567` / `#568` / `#690` は v1 非対応（BG タイマー機構なし）。
- **[MEDIUM]** 目的地タイプ別フレーズ差し替えは v1 非対応（`RoutePoint` に type が無い）。
- **[MEDIUM]** レーン案内は v1 では「右側 / 左側 / 中央」の 3 パターン + 常に「お進みください」固定。
- **[LOW]** 新規 data class / sealed class に `@Immutable` を付与。
- **[LOW]** v1 は動的文字列を発話テキストに一切含めない。

---

## 1. 目的と v1 スコープ

### 1.1 現状の問題（再掲）

`GuidanceSessionManager.kt:207-210` で `currentStep.instruction`（Google Navigation SDK の `StepInfo.fullInstructionText`）をそのまま TTS に流している。結果、距離予告が無く、ドライブサポーター風の整った言い回しにもならず、連続案内・オフルート・リルート・到着系の発話も無い。

### 1.2 v1 スコープ（実装対象）

**原則**: 現状のデータ供給で**確実に**実現できるものだけを v1 に含める。曖昧なものは一切入れない。

| # | ドキュメント章 | v1 対応 | 備考 |
|---|---|---|---|
| 1 | 基本ターンバイターン案内（8 方向、距離予告） | ✅ | 信号カウント (#754-755) は除外 |
| 1 | 右折後 / 左折後 / 通過後 (#764-766) | ❌ v2 | ステップ遷移直後の即時発話は v2 |
| 2 | 高速道路案内（IC / JCT / 料金所 / SA 入口） | ❌ v2 | `highwayInfo` 未供給 |
| 2 | 分岐 (#58-60) | ⚠️ 部分 | `Maneuver.FORK_*` を「分岐です。」として発話。高速限定演出は v2 |
| 2 | 合流 (#62-64) | ⚠️ 部分 | `Maneuver.MERGE_*` + `drivingSide` で「右/左からの合流が有ります。」 |
| 2 | 一般/高速切替 (#569-570), 有料区間入 (#767-768) | ❌ v2 | 道路種別判定不可 |
| 3 | レーン案内（右側 / 左側 / 中央 + お進みください） | ⚠️ 部分 | v1 は 3 パターンのみ。N 番目/お入りください等は v2 |
| 3 | 車線減少 (#733-734), 専用レーン (#73-75) | ❌ v2 | 情報源なし |
| 4 | 渋滞 | ❌ v2 以降 | データ源未整備 |
| 5 | オフルート / オンルート復帰 / リルート完了 | ✅ | |
| 5 | プロアクティブリルート（時間差案内） | ❌ v2 以降 | |
| 6-14 | 規制・気象・速度・踏切・一時停止・カーブ・ゾーン30・景観 | ❌ ユーザー除外 | |
| 15 | 道なり案内（しばらく / 5km 以上） | ✅ | |
| 16 | 経由地接近 | ✅ | 「経由地付近です。」固定 |
| 16 | 目的地タイプ別差し替え | ❌ v2 | `RoutePoint` に type 無し |
| 17 | 開始 (#48-49), 目的地到着 (#51 + #53) | ✅ | |
| 17 | 継続 / 中断 / 停止中 (#566-568, #690) | ❌ v2 | BG タイマー機構なし |
| 18 | ボイスコントロール | ❌ ユーザー除外 | |

**v1 で追加される発話の種類: 計 6 カテゴリ**

1. セッション開始 / 終了
2. 基本ターンバイターン（8 方向 × 距離バケット 4 段）
3. 合流 / 分岐（方向付き or なし）
4. レーン誘導（右側 / 左側 / 中央）
5. 道なり（しばらく / 5km 以上）
6. オフルート / 復帰 / リルート / 経由地到着 / 目的地到着

---

## 2. 用語と概念

### 2.1 データ源（現状調査に基づく確定事実）

| 種別 | 取得元 | v1 での用途 |
|---|---|---|
| `NavigationFeedSnapshot.currentStep / remainingSteps` | `TurnByTurnUpdateBus`（SDK の NavInfo 由来、アプリ側周期制御なし） | 現ステップ/次ステップ判定、レーン、driving side |
| `NavigationFeedSnapshot.distanceToCurrentStepMeters` | 同上 | 距離バケット下抜け検出 |
| `NavigationTripProgressSnapshot` | `addRemainingTimeOrDistanceChangedListener` (5s / 50m 閾値) | UI 用のみ。発話ロジックでは**使わない**（距離粒度が粗すぎる） |
| `isOffRoute: StateFlow<Boolean>` | `NavigationSdkManager` | オフルート検出 |
| `arrivalEvents: SharedFlow<NavigationArrivalSnapshot>` | `ArrivalListener` | 経由地/最終到着 |
| `routeChangedListener` → `RouteManager.routes` 更新 | SDK | リルート検出（Flow の distinctUntilChanged で差分検知） |

### 2.2 v1 で使わないデータ

- `GoogleRoute.steps` — `roadName=""`, `roadRef=null`, `highwayInfo=null` が現状固定であり、v1 の発話ロジックでは参照しない（将来 v2 で datasource 強化時に活用）。
- `GoogleRoute.intermediateWaypoints` — 名前や種別情報が無いため、v1 では到着順序の数合わせにのみ使う。発話には反映しない。
- `NavigationArrivalSnapshot.waypointTitle` — 現状は `"経由地1"` / `"目的地"` の固定文字列。発話には使わず、`isFinalDestination` のみで分岐する。

### 2.3 距離バケット（確定版）

**ユーザー指定通り `2km / 500m / 100m / 50m` の 4 段**。道路種別による切り替えは v1 では行わない（`RoadClass` 判定基盤が未整備のため）。

| バケット | 閾値 [m] | フレーズ |
|---|---|---|
| `AT_2KM` | 2000 | 「およそ2km先、」 |
| `AT_500M` | 500 | 「およそ500m先、」 |
| `AT_100M` | 100 | 「まもなく、」 |
| `AT_50M` | 50 | 「まもなく、」 |

**発話タイミングの組み立てポリシー**:

距離バケットは「発話トリガー」と「文頭の形」を別に持つ。

| バケット | トリガー距離 | 文頭 | 例 |
|---|---|---|---|
| `AT_2KM` | 2000m を下抜け | 「およそ2km先、」 | 「およそ2km先、右方向です。」 |
| `AT_500M` | 500m を下抜け | 「およそ500m先、」 | 「およそ500m先、左方向です。」 |
| `AT_100M` | 100m を下抜け | 「まもなく、」 | 「まもなく、斜め右方向です。」 |
| `AT_50M` | 50m を下抜け | 「まもなく、」 | 「まもなく、U ターンです。」 |

- **`AT_50M` の抑制ルール（`stepCounter` ベース）**:
  - 同一 `stepCounter` で `AT_100M` を既に発話済みなら `AT_50M` は抑制（直前に同趣旨の発話をしているため）。
  - 同一 `stepCounter` で `AT_100M` 未発話の場合は `AT_50M` を単独発話する（急接近や取りこぼし時の保険）。
- 一般道/高速の切替は v2。v1 では上記 4 段を一律に使う。

### 2.4 連続案内は v1 では扱わない

「その先、分岐です」連続案内は、次ステップの距離評価と重複抑制の整合性が詰まっていないため v1 では見送り。「その先、〜」フレーズ ID (#77) は strings に登録しておくが、v1 の発話ロジックでは呼び出さない。

### 2.5 フレーズ列モデル

```
GuidancePhrase = ImmutableList<PhraseSegment>

PhraseSegment =
    | Fixed(phraseId: TtsPhraseId)     // strings.xml の固定文言
    | Distance(bucket: DistanceBucket) // 「およそ2km先、」等
```

発話時は `segments.joinToString(separator = "")` して `SpeechOrchestrator.enqueue()` に渡す。

### 2.6 優先度

| 優先度 | 用途 | キューモード |
|---|---|---|
| `CRITICAL` | セッション開始/終了、オフルート、リルート、到着 | `FLUSH` |
| `HIGH` | 50m / 100m の直前案内 | `FLUSH` |
| `NORMAL` | 500m / 2km 予告、レーン案内 | `ADD` |
| `LOW` | 道なり案内 | `ADD` |

`SpeechOrchestrator.enqueue(flush=true)` は既存の pending を破棄するので、`CRITICAL` が来たらそれまでの通常案内は中断される。ユーザー影響：オフルート中に 2km 予告中断はむしろ望ましい挙動。

---

## 3. 発話カタログ（v1 版）

以降の `#NNN` はドライブサポーター TTS DB の ID。

### 3.1 セッション制御

| イベント | 発話 | 優先度 |
|---|---|---|
| `startSession()` 成功直後 | 「音声案内を開始します。実際の交通規制に従って走行してください。」(#48 + #49) | CRITICAL |
| 最終到着 (`arrivalEvents.isFinalDestination=true`) | 「目的地付近です。お疲れ様でした。」(#51 + #53) | CRITICAL |

### 3.2 経由地

| イベント | 発話 | 優先度 |
|---|---|---|
| 中間到着 (`isFinalDestination=false`) | 「経由地付近です。」(#50) | CRITICAL |

### 3.3 代替ルート

| イベント | 発話 | 優先度 |
|---|---|---|
| `isOffRoute` false → true | 「ルートから外れました。」(#54) | CRITICAL |
| `isOffRoute` true → false | 「ルートに戻りました。」(#55) | CRITICAL |
| `routeChangedListener` 発火（routes StateFlow が変化） | 「新しいルートが見つかりました。新しいルートで案内します。」(#56 + #57) | CRITICAL |

### 3.4 基本ターンバイターン（8 方向）

`ManeuverModifier` → 文末フレーズ（句点止め、#40-47, #689）:

| `ManeuverModifier` | 文末フレーズ | 例 |
|---|---|---|
| `STRAIGHT` | 「直進方向です。」 (#689) | |
| `SLIGHT_RIGHT` | 「斜め右方向です。」 (#41) | |
| `RIGHT` | 「右方向です。」 (#42) | |
| `SHARP_RIGHT` | 「右手前方向です。」 (#43) | |
| `UTURN` | 「戻る方向です。」 (#44) | |
| `SHARP_LEFT` | 「左手前方向です。」 (#45) | |
| `LEFT` | 「左方向です。」 (#46) | |
| `SLIGHT_LEFT` | 「斜め左方向です。」 (#47) | |

**対象マニューバ (`ManeuverType`)**: `TURN` / `CONTINUE` / `DEPART` / `END_OF_ROAD` / `NAME_CHANGE`

**ManeuverType が `CONTINUE` の扱い**: 交差点名通知などで `STRAIGHT` を返す。v1 では「直進方向です」だけを発話し、交差点名・信号情報は発話しない。

**組み立て**:

```
[距離(2km/500m) or タイミング(100m/50m)] + [方向(文末)]
```

- `AT_2KM`: 「およそ2km先、」+ 方向
- `AT_500M`: 「およそ500m先、」+ 方向
- `AT_100M` / `AT_50M`: 「まもなく、」+ 方向

### 3.5 分岐・合流（v1 限定版）

`ManeuverType.FORK` の場合、方向の代わりに分岐フレーズを使う:

| 条件 | 文末フレーズ |
|---|---|
| `FORK` 全般 | 「分岐です。」 (#59) |
| `MERGE` + `drivingSide=RIGHT` | 「右からの合流が有ります。」 (#62) |
| `MERGE` + `drivingSide=LEFT` | 「左からの合流が有ります。」 (#63) |
| `MERGE` + `drivingSide` 不明 | 「合流が有ります。」 (#64) |

**発話は距離バケットにしたがう**。たとえば「およそ500m先、分岐です。」。

### 3.6 ランプ（v1 限定版）

`ManeuverType.ON_RAMP` / `OFF_RAMP` は **v1 では方向フレーズで代替**する。IC/高速入口の語彙は `highwayInfo` 未供給のため使わない。

| `ManeuverType` + `ManeuverModifier` | v1 の発話 |
|---|---|
| `ON_RAMP` + `LEFT` | 「左方向です。」（通常方向で代用） |
| `ON_RAMP` + `RIGHT` | 「右方向です。」 |
| `ON_RAMP` + `STRAIGHT` | 「直進方向です。」 |
| `OFF_RAMP` + `LEFT` | 「左方向です。」 |
| …etc | |

### 3.7 レーン案内（v1 版）

`NavigationLaneSnapshot.isRecommended=true` の車線インデックス群を `recommendedIndices`、総車線数を `totalLanes` とする。

| 条件 | 車線フレーズ |
|---|---|
| `recommendedIndices` が 0 のみ or 0〜1 の最左側集合 | 「左側の車線を」 (#489) |
| `recommendedIndices` が `totalLanes-1` のみ or 最右側集合 | 「右側の車線を」 (#488) |
| `totalLanes >= 3` かつ推奨が中央のみ（両端除く） | 「中央の車線を」 (#512) |
| 上記いずれにも当たらない | **発話しない**（v2 対応） |

**発話タイミング**:

- ターン系マニューバ（`TURN` / `FORK` / `MERGE` / `ON_RAMP` / `OFF_RAMP`）かつ `lanes` 非空のとき
- `AT_500M` を下抜けたタイミング 1 回のみ
- 組み立て: `[タイミング] + [車線指示] + 「お進みください。」(#487)`
  - 例: 「およそ500m先、左側の車線を、お進みください。」

**v1 では常に「お進みください」(#487) 固定**。「お入りください」(#525) は現在車線が取得できないため v2。

### 3.8 道なり案内

| 条件 | 発話 | タイミング |
|---|---|---|
| `distanceToCurrentStepMeters >= 5000` | 「5km以上道なりです。」(#31) | ステップ遷移直後に 1 回 |
| `1000 <= distanceToCurrentStepMeters < 5000` | 「しばらく道なりです。」(#30) | ステップ遷移直後に 1 回 |

**ステップ遷移検出**: `NavigationFeedSnapshot.currentStep` が前回と異なる値になった瞬間（§4.6 の step transition counter で検出）。

---

## 4. 主要クラス設計（rev.2）

### 4.1 パッケージ構成

```
core/navigation/src/androidMain/kotlin/me/matsumo/onenavi/core/navigation/
  guidance/                              ← 新規
    TtsPhraseId.kt                      ← enum、strings.xml との関連
    DistanceBucket.kt                   ← enum
    GuidanceEvent.kt                    ← sealed interface（semantic、phrasing を含まない）
    GuidancePriority.kt                 ← enum
    GuidancePhrase.kt                   ← data class + PhraseSegment sealed
    GuidancePlanner.kt                  ← 純関数：入力 snapshot → List<GuidanceEvent>
    PhraseComposer.kt                   ← GuidanceEvent → GuidancePhrase（suspend: strings 読み込み）
    SpeechDispatcher.kt                 ← Channel<GuidanceEvent> + 単一 actor
    SpokenGuideKeyStore.kt              ← ステップ × バケットの重複抑制
    GuidanceCoordinator.kt              ← 状態保持（前回 snapshot, off-route flag, step counter）
  GuidanceSessionManager.kt             ← 既存、上記を組み込む
```

### 4.2 型スケッチ

```kotlin
// core:model / commonMain
@Immutable
sealed interface GuidanceEvent {
    val priority: GuidancePriority

    data class SessionStarted(override val priority: GuidancePriority) : GuidanceEvent
    data class SessionFinished(override val priority: GuidancePriority) : GuidanceEvent
    data class ViaWaypointApproach(override val priority: GuidancePriority) : GuidanceEvent
    data class DestinationApproach(override val priority: GuidancePriority) : GuidanceEvent
    data class OffRoute(override val priority: GuidancePriority) : GuidanceEvent
    data class OnRouteRecovered(override val priority: GuidancePriority) : GuidanceEvent
    data class Rerouted(override val priority: GuidancePriority) : GuidanceEvent

    data class Maneuver(
        val stepCounter: Int,
        val bucket: DistanceBucket,
        val maneuverType: ManeuverType,
        val modifier: ManeuverModifier?,
        val drivingSide: DrivingSide?,
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    data class Lane(
        val stepCounter: Int,
        val bucket: DistanceBucket,
        val lanePosition: LanePosition,           // LEFT / RIGHT / CENTER
        override val priority: GuidancePriority,
    ) : GuidanceEvent

    data class Straightforward(
        val stepCounter: Int,
        val level: StraightforwardLevel,          // SHORT / LONG
        override val priority: GuidancePriority,
    ) : GuidanceEvent
}

@Immutable
enum class GuidancePriority { CRITICAL, HIGH, NORMAL, LOW }

@Immutable
enum class DistanceBucket(val thresholdMeters: Int) {
    AT_2KM(2000),
    AT_500M(500),
    AT_100M(100),
    AT_50M(50),
}
```

`GuidanceEvent` は **phrasing を一切持たない**。文字列化は `PhraseComposer` の責務。

### 4.3 `GuidancePlanner`（純関数）

```kotlin
internal class GuidancePlanner {
    fun plan(input: GuidancePlannerInput): List<GuidanceEvent>
}

internal data class GuidancePlannerInput(
    val previousSnapshot: NavigationFeedSnapshot?,
    val currentSnapshot: NavigationFeedSnapshot,
    val previousIsOffRoute: Boolean,
    val currentIsOffRoute: Boolean,
    val stepCounter: Int,             // 現ステップの識別子（§4.6）
    val stepTransitioned: Boolean,    // 今回のティックでステップが変わったか
    val spokenKeys: Set<SpokenGuideKey>,
)
```

`plan()` の責務:

1. ステップ遷移なら `Straightforward` 候補を生成（距離条件満たすとき）
2. 距離バケット下抜け検出 → `Maneuver` 候補
3. レーン条件満たすとき → `Lane` 候補
4. オフルート変化検出 → `OffRoute` / `OnRouteRecovered`

セッション開始/終了、到着、リルートは `GuidancePlanner` には入れない。`GuidanceSessionManager` が直接 `SpeechDispatcher` に流す（これらは snapshot 起点でないイベント）。

**純関数**: `plan()` は `spokenKeys` を入力で受け取り、自身では状態を持たない。呼び出し側（`GuidanceCoordinator`）が結果に応じて `spokenKeys` を更新する。

### 4.4 距離バケット下抜け検出

```kotlin
// 前回距離 previous と今回距離 current から下抜けしたバケットを返す
// ルート上は距離が単調減少するので、previous > threshold >= current となるバケットが対象
private fun crossedBuckets(previous: Int?, current: Int): List<DistanceBucket> {
    val prev = previous ?: return emptyList()
    return DistanceBucket.entries
        .filter { bucket -> prev > bucket.thresholdMeters && current <= bucket.thresholdMeters }
}
```

**複数バケット跨ぎの処置**: `crossedBuckets` が 2 つ以上返った場合、**最も近いバケット（= 最小 threshold）の未発話のみを採用**する。

理由: 運転上の価値は「直前の予告」ほど高い。たとえば 505m → 95m と 1 ティックで飛んだケースでは、`AT_500M` と `AT_100M` の両方が下抜けるが、ドライバーにとって最も重要なのは「まもなくその操作をせよ」という直前案内（`AT_100M`）であり、既に過ぎ去った 500m 予告を後から喋っても価値が無い。

```kotlin
// 採用ロジック
val speakBucket = crossedBuckets(previous, current)
    .filter { !spokenKeys.contains(SpokenGuideKey(stepCounter, MANEUVER, it)) }
    .minByOrNull { it.thresholdMeters }   // ← 最も近いバケット
```

**50m/100m 抑制**: §2.3 のルール通り、同一 `stepCounter` で `AT_100M` 発話済みなら `AT_50M` は抑制、未発話なら単独発話を許可。`spokenKeys` に `SpokenGuideKey(stepCounter, MANEUVER, AT_100M)` が存在するかで判定。

### 4.5 `PhraseComposer`

```kotlin
internal class PhraseComposer {
    suspend fun compose(event: GuidanceEvent): GuidancePhrase = when (event) {
        is GuidanceEvent.SessionStarted ->
            phrase(Fixed(TtsPhraseId.NAVIGATION_STARTED), Fixed(TtsPhraseId.FOLLOW_TRAFFIC_RULES))
        is GuidanceEvent.DestinationApproach ->
            phrase(Fixed(TtsPhraseId.DESTINATION_APPROACH), Fixed(TtsPhraseId.NAVIGATION_FINISHED))
        is GuidanceEvent.Maneuver -> composeManeuver(event)
        is GuidanceEvent.Lane -> composeLane(event)
        is GuidanceEvent.Straightforward -> composeStraightforward(event)
        // ... 他
    }

    suspend fun resolve(phrase: GuidancePhrase): String = buildString {
        phrase.segments.forEach { segment ->
            when (segment) {
                is PhraseSegment.Fixed -> append(getString(segment.phraseId.resource))
                is PhraseSegment.Distance -> append(getString(segment.bucket.phraseId.resource))
            }
        }
    }
}
```

`compose()` は `GuidanceEvent` → `GuidancePhrase`（segments 組み立て）、`resolve()` は `GuidancePhrase` → `String`（strings.xml 読み込み）。両方 `suspend`。

### 4.6 Step transition counter（セッション限定 ID）

`GoogleRoute.steps` への逆算依存をやめ、セッション中だけ有効な単純カウンタに置き換える。

```kotlin
internal class StepTransitionTracker {
    private var counter: Int = 0
    private var lastStep: NavigationStepSnapshot? = null
    private var lastDistanceToStep: Int? = null

    fun update(
        currentStep: NavigationStepSnapshot?,
        currentDistance: Int?,
    ): StepTransitionResult {
        val last = lastStep
        val transitioned = when {
            last == null && currentStep == null -> false
            last == null && currentStep != null -> {
                counter = 0
                true
            }
            last != null && currentStep == null -> false  // ENROUTE → REROUTING 等
            else -> !isSameStep(last!!, currentStep!!, currentDistance)
        }
        if (transitioned) counter++
        lastStep = currentStep
        lastDistanceToStep = currentDistance
        return StepTransitionResult(counter = counter, transitioned = transitioned)
    }

    // 2 条件のいずれかが不一致なら別ステップと判定する。
    // 1) maneuver / roadName / simpleRoadName / drivingSide / roundaboutTurnNumber のヒューリスティック一致
    // 2) distanceToCurrentStepMeters の急増（+300m 以上）→ 進行方向では距離が単調減少するはずなので、
    //    急増はステップ遷移（次のガイドポイントに切り替わった）と解釈する
    private fun isSameStep(
        a: NavigationStepSnapshot,
        b: NavigationStepSnapshot,
        currentDistance: Int?,
    ): Boolean {
        val fieldsMatch = a.maneuver == b.maneuver &&
            a.roadName == b.roadName &&
            a.simpleRoadName == b.simpleRoadName &&
            a.drivingSide == b.drivingSide &&
            a.roundaboutTurnNumber == b.roundaboutTurnNumber
        if (!fieldsMatch) return false

        val prevDistance = lastDistanceToStep ?: return true
        val curr = currentDistance ?: return true
        // 前回より 300m 以上増加したらステップが切り替わったと判定
        return curr - prevDistance < DISTANCE_SURGE_THRESHOLD_METERS
    }

    fun reset() {
        counter = 0
        lastStep = null
        lastDistanceToStep = null
    }

    companion object {
        private const val DISTANCE_SURGE_THRESHOLD_METERS = 300
    }
}
```

**誤判定時のログ**: `isSameStep()` が距離補助条件で遷移判定した場合は `Napier.d` で記録し、Phase 4 の実機チューニングで頻度を確認する。

`counter` は `GuidanceEvent.Maneuver` / `Lane` / `Straightforward` の `stepCounter` として使い、`SpokenGuideKey` のキー要素にする。

```kotlin
@Immutable
data class SpokenGuideKey(
    val stepCounter: Int,
    val category: Category,
    val bucket: DistanceBucket?,
) {
    enum class Category { MANEUVER, LANE, STRAIGHTFORWARD }
}
```

ステップが進むたびに `counter` が増えるので、過去ステップの `SpokenGuideKey` は `forgetStep(counter)` で掃除する（メモリ節約）。

### 4.7 `SpeechDispatcher`（単一 actor + bounded + coalesce）

**設計方針**: `TurnByTurnUpdateBus` はアプリ側の周期制御を持たないため、受信頻度が高くなる可能性がある。無制限 Channel は OOM とキュー肥大のリスクがあるので、以下の 2 段で制御する。

1. **bounded Channel（capacity 32）**: 実用上の発話イベントは同時に数件程度。32 は余裕を見た値。
2. **同カテゴリ coalesce**: `pending` リストを別途保持し、同じ `(stepCounter, category)` のイベントが連続して届いた場合は**最新のみを残す**（古い方を捨てる）。これは「距離バケット下抜けで Maneuver 2 件が発生したが前イベントが未消化」などのケースに対応。

```kotlin
internal class SpeechDispatcher(
    private val orchestrator: SpeechOrchestrator,
    private val composer: PhraseComposer,
    private val scope: CoroutineScope,
) {
    private val channel = Channel<GuidanceEvent>(capacity = CAPACITY)
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            for (event in channel) {
                runCatching {
                    val phrase = composer.compose(event)
                    val text = composer.resolve(phrase)
                    if (text.isNotBlank()) {
                        val flush = event.priority == GuidancePriority.CRITICAL ||
                            event.priority == GuidancePriority.HIGH
                        orchestrator.enqueue(text = text, flush = flush)
                    }
                }.onFailure { throwable ->
                    Napier.w(tag = TAG, throwable = throwable) { "Failed to dispatch guidance event: $event" }
                }
            }
        }
    }

    fun send(event: GuidanceEvent) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            // キューが満杯 → 古いイベントを捨てて新しいものを入れる（coalesce）
            Napier.w(tag = TAG) { "Speech dispatcher channel full, dropping oldest: $event" }
            channel.tryReceive()
            channel.trySend(event)
        }
    }

    fun shutdown() {
        channel.close()
        job?.cancel()
        job = null
    }

    companion object {
        private const val CAPACITY = 32
        private const val TAG = "SpeechDispatcher"
    }
}
```

**直列保証**: `Channel` + 単一 collector で順序保証。`suspend` な `compose`/`resolve` もこの actor 内で順次処理されるので、発話の順序ずれが起きない。

**優先度**: `CRITICAL` / `HIGH` のとき `flush=true` で `SpeechOrchestrator` のキューを破棄して割り込む。actor 自体は FIFO だが、`SpeechOrchestrator.enqueue(flush=true)` が実際の再生をリセットするので、割り込みは再生層で起きる。

### 4.8 `GuidanceCoordinator`

`GuidanceSessionManager` が持っていた状態（前回 snapshot / off-route / step tracker / spoken keys）を切り出す。

```kotlin
internal class GuidanceCoordinator(
    private val planner: GuidancePlanner,
    private val dispatcher: SpeechDispatcher,
) {
    private val stepTracker = StepTransitionTracker()
    private val spokenKeys = mutableSetOf<SpokenGuideKey>()
    private var previousSnapshot: NavigationFeedSnapshot? = null
    private var previousIsOffRoute = false

    fun onNavigationUpdate(snapshot: NavigationFeedSnapshot, isOffRoute: Boolean) {
        val step = snapshot.currentStep
        val transitionResult = stepTracker.update(
            currentStep = step,
            currentDistance = snapshot.distanceToCurrentStepMeters,
        )
        if (transitionResult.transitioned) {
            forgetOldSteps(transitionResult.counter)
        }

        val events = planner.plan(
            GuidancePlannerInput(
                previousSnapshot = previousSnapshot,
                currentSnapshot = snapshot,
                previousIsOffRoute = previousIsOffRoute,
                currentIsOffRoute = isOffRoute,
                stepCounter = transitionResult.counter,
                stepTransitioned = transitionResult.transitioned,
                spokenKeys = spokenKeys.toSet(),
            ),
        )
        events.forEach { event ->
            if (markSpokenIfNeeded(event)) {
                dispatcher.send(event)
            }
        }

        previousSnapshot = snapshot
        previousIsOffRoute = isOffRoute
    }

    // セッション開始・到着・リルートは snapshot 起点でないので別 API
    fun emit(event: GuidanceEvent) { dispatcher.send(event) }

    fun reset() {
        stepTracker.reset()
        spokenKeys.clear()
        previousSnapshot = null
        previousIsOffRoute = false
    }

    private fun markSpokenIfNeeded(event: GuidanceEvent): Boolean { /* ... */ }
    private fun forgetOldSteps(currentCounter: Int) { /* ... */ }
}
```

### 4.9 `GuidanceSessionManager` の再編

既存の `applyGuidanceUpdate()` を以下に置換:

```kotlin
private fun applyGuidanceUpdate(routeId: String, update: GuidanceUpdate) {
    if (activeRoute?.id != routeId) return

    val navInfo = update.navInfo
    val tripProgress = update.tripProgress
    val navState = navInfo?.navState
    val isOffRoute = update.isOffRouteRaw || navState == NavState.REROUTING

    // UI state 更新（従来通り）
    updateGuidanceUiState(navInfo, tripProgress, isOffRoute)

    if (navInfo == null || navState != NavState.ENROUTE) return
    coordinator.onNavigationUpdate(navInfo, isOffRoute)
}
```

セッション開始時:

```kotlin
fun startSession() {
    // ... 既存初期化
    dispatcher.start()
    coordinator.reset()
    coordinator.emit(GuidanceEvent.SessionStarted(priority = GuidancePriority.CRITICAL))
    // ...
}
```

到着時 (`arrivalEvents.collect`):

```kotlin
navigationSdkManager.arrivalEvents.collect { arrival ->
    if (activeRoute?.id != route.id) return@collect
    if (arrival.isFinalDestination) {
        coordinator.emit(GuidanceEvent.DestinationApproach(priority = GuidancePriority.CRITICAL))
        onFinalDestinationArrival(activeRoute)
    } else {
        coordinator.emit(GuidanceEvent.ViaWaypointApproach(priority = GuidancePriority.CRITICAL))
        navigationSdkManager.continueToNextDestination()
    }
}
```

**リルート検出**: `routeManager.routes` の変化を監視する。ただし routeToken は存在しない場合があるため、以下の優先順で差分検知する。

```kotlin
routeManager.routes
    .map { it.firstOrNull() }
    .distinctUntilChanged { old, new ->
        when {
            old == null || new == null -> old === new
            // 1) routeToken がある場合はそれで比較
            old.routeToken != null && new.routeToken != null -> old.routeToken == new.routeToken
            // 2) ない場合は id + geometry サイズで代替（座標全比較はコスト高のため geometry.size で近似）
            else -> old.id == new.id && old.geometry.size == new.geometry.size
        }
    }
    .drop(1)   // 初回 emit はリルートではない
    .onEach { coordinator.emit(GuidanceEvent.Rerouted(GuidancePriority.CRITICAL)) }
    .launchIn(scope)
```

v1 で `geometry.size` に頼るのは割り切り。完全一致判定は性能コスト（数百点の座標比較）がナビ更新ループ内で重いため。実機で偽陽性が問題になれば Phase 4 で `distanceMeters` や `durationSeconds` も合成キーに加える。

`stopSession()`:

```kotlin
fun stopSession() {
    dispatcher.shutdown()
    coordinator.reset()
    // ... 既存終了処理
}
```

**重要**: この `applyGuidanceUpdate()` の書き換え（`speak(currentStep.instruction)` 行削除 + `coordinator.onNavigationUpdate()` 追加）は**同一コミットで実施**する。SDK テキスト発話と独自発話が併走する期間は作らない（§6 Phase 2 参照）。

---

## 5. strings.xml 追加内容（v1 確定版）

`core/resource/src/commonMain/composeResources/values/strings.xml` に追加。プレフィックスは `tts_`。

```xml
<!-- TTS Session -->
<string name="tts_navigation_started">音声案内を開始します。</string>
<string name="tts_follow_traffic_rules">実際の交通規制に従って走行してください。</string>
<string name="tts_navigation_finished">お疲れ様でした。</string>

<!-- TTS Distance -->
<string name="tts_distance_2km">およそ2km先、</string>
<string name="tts_distance_500m">およそ500m先、</string>

<!-- TTS Timing -->
<string name="tts_timing_imminent">まもなく、</string>

<!-- TTS Direction (句点止め = 文末) -->
<string name="tts_direction_straight_end">直進方向です。</string>
<string name="tts_direction_slight_right_end">斜め右方向です。</string>
<string name="tts_direction_right_end">右方向です。</string>
<string name="tts_direction_sharp_right_end">右手前方向です。</string>
<string name="tts_direction_uturn_end">戻る方向です。</string>
<string name="tts_direction_sharp_left_end">左手前方向です。</string>
<string name="tts_direction_left_end">左方向です。</string>
<string name="tts_direction_slight_left_end">斜め左方向です。</string>

<!-- TTS Fork / Merge -->
<string name="tts_fork_end">分岐です。</string>
<string name="tts_merge_right">右からの合流が有ります。</string>
<string name="tts_merge_left">左からの合流が有ります。</string>
<string name="tts_merge">合流が有ります。</string>

<!-- TTS Lane -->
<string name="tts_lane_right_side">右側の車線を</string>
<string name="tts_lane_left_side">左側の車線を</string>
<string name="tts_lane_center">中央の車線を</string>
<string name="tts_lane_proceed">お進みください。</string>

<!-- TTS Straightforward -->
<string name="tts_straightforward_short">しばらく道なりです。</string>
<string name="tts_straightforward_long">5km以上道なりです。</string>

<!-- TTS Route -->
<string name="tts_off_route">ルートから外れました。</string>
<string name="tts_on_route_recovered">ルートに戻りました。</string>
<string name="tts_rerouted_found">新しいルートが見つかりました。</string>
<string name="tts_rerouted_start">新しいルートで案内します。</string>

<!-- TTS Waypoint / Destination -->
<string name="tts_waypoint_approach">経由地付近です。</string>
<string name="tts_destination_approach">目的地付近です。</string>
```

合計 **27 件**（rev.1 の 55 件から v1 スコープ絞り込みで半減）。

**`TtsPhraseId` enum**:

```kotlin
enum class TtsPhraseId(val resource: StringResource) {
    NAVIGATION_STARTED(Res.string.tts_navigation_started),
    FOLLOW_TRAFFIC_RULES(Res.string.tts_follow_traffic_rules),
    NAVIGATION_FINISHED(Res.string.tts_navigation_finished),

    DISTANCE_2KM(Res.string.tts_distance_2km),
    DISTANCE_500M(Res.string.tts_distance_500m),
    TIMING_IMMINENT(Res.string.tts_timing_imminent),

    DIR_STRAIGHT_END(Res.string.tts_direction_straight_end),
    DIR_SLIGHT_RIGHT_END(Res.string.tts_direction_slight_right_end),
    DIR_RIGHT_END(Res.string.tts_direction_right_end),
    DIR_SHARP_RIGHT_END(Res.string.tts_direction_sharp_right_end),
    DIR_UTURN_END(Res.string.tts_direction_uturn_end),
    DIR_SHARP_LEFT_END(Res.string.tts_direction_sharp_left_end),
    DIR_LEFT_END(Res.string.tts_direction_left_end),
    DIR_SLIGHT_LEFT_END(Res.string.tts_direction_slight_left_end),

    FORK_END(Res.string.tts_fork_end),
    MERGE_RIGHT(Res.string.tts_merge_right),
    MERGE_LEFT(Res.string.tts_merge_left),
    MERGE(Res.string.tts_merge),

    LANE_RIGHT_SIDE(Res.string.tts_lane_right_side),
    LANE_LEFT_SIDE(Res.string.tts_lane_left_side),
    LANE_CENTER(Res.string.tts_lane_center),
    LANE_PROCEED(Res.string.tts_lane_proceed),

    STRAIGHT_SHORT(Res.string.tts_straightforward_short),
    STRAIGHT_LONG(Res.string.tts_straightforward_long),

    OFF_ROUTE(Res.string.tts_off_route),
    ON_ROUTE_RECOVERED(Res.string.tts_on_route_recovered),
    REROUTED_FOUND(Res.string.tts_rerouted_found),
    REROUTED_START(Res.string.tts_rerouted_start),

    WAYPOINT_APPROACH(Res.string.tts_waypoint_approach),
    DESTINATION_APPROACH(Res.string.tts_destination_approach),
    ;
}
```

`DistanceBucket` から `TtsPhraseId` へのマッピングは enum で持つ:

```kotlin
enum class DistanceBucket(val thresholdMeters: Int, val phraseId: TtsPhraseId?) {
    AT_2KM(2000, TtsPhraseId.DISTANCE_2KM),
    AT_500M(500, TtsPhraseId.DISTANCE_500M),
    AT_100M(100, TtsPhraseId.TIMING_IMMINENT),
    AT_50M(50, TtsPhraseId.TIMING_IMMINENT),
}
```

---

## 6. 実装フェーズ（rev.3）

**cutover 原則**: SDK テキストの直接発話と独自発話を**併走させない**。Phase 1 は配線のみで発話には到達しない（骨組みで完結）。Phase 2 が単一の cutover コミットとなり、`speak(currentStep.instruction)` 削除 + 独自発話投入を同時に行う。

### Phase 0 — 基盤整備（動作変化なし）

目標: 型・リソース・骨組みを追加。既存発話ロジックは**一切変更しない**。

1. `strings.xml` に §5 の 27 件を追加。
2. `core:model` に追加（全て `@Immutable`）:
   - `GuidanceEvent` sealed interface
   - `GuidancePriority` enum
   - `DistanceBucket` enum
   - `LanePosition` enum
   - `StraightforwardLevel` enum
   - `GuidancePhrase` / `PhraseSegment`
3. `core:navigation/androidMain/guidance/` に骨組みを追加（実装は空でも可）:
   - `TtsPhraseId` enum
   - `PhraseComposer`（`compose()` / `resolve()` を実装）
   - `SpokenGuideKeyStore`
   - `StepTransitionTracker`
   - `SpeechDispatcher`（未起動状態で存在するだけ）
   - `GuidancePlanner`（常に `emptyList()` を返す）
   - `GuidanceCoordinator`
4. `PhraseComposer` の単体テスト（JVM test）:
   - 全 `GuidanceEvent` 型に対して期待フレーズが生成されることを検証。
5. `./gradlew assembleDebug --no-configuration-cache` / `make detekt` 通過確認。

**この Phase では `GuidanceSessionManager` に一切手を入れない**。既存の発話挙動は維持される。

**PR サイズ目安**: 400 行前後。

### Phase 1 — 配線のみ（actor 起動・イベント配管、発話は未接続）

目標: `SpeechDispatcher` / `GuidanceCoordinator` のライフサイクルを `GuidanceSessionManager` に組み込む。**この時点では独自発話を `speak()` に繋げない**（SpeechDispatcher は起動されるが、受け取ったイベントの `orchestrator.enqueue()` 呼び出しは feature flag で抑制）。

1. `GuidanceSessionManager` に以下を追加:
   - `coordinator: GuidanceCoordinator` / `dispatcher: SpeechDispatcher` を DI で注入。
   - `startSession()` で `dispatcher.start()` / `coordinator.reset()`。
   - `stopSession()` で `dispatcher.shutdown()`。
2. `SpeechDispatcher` に `enabled: Boolean = false` フラグを持たせ、false のうちは `orchestrator.enqueue()` をスキップ（ログ出力のみ）。
3. `arrivalEvents` / `isOffRoute` / `routeManager.routes` のコレクタを追加してイベント emit まで行う。
4. 動作確認: 発話は一切変化しない（既存 SDK テキスト発話のみ）。ログで `SpeechDispatcher` が期待イベントを受信していることを確認。

**この Phase の目的**: 配管を本番コードに入れた状態でイベントが意図通りに流れることを確認する。発話は次 Phase で解禁する。

**PR サイズ目安**: 200 行前後。

### Phase 2 — **単一 cutover**（SDK テキスト発話削除 + 独自発話投入）

**このコミットが本計画の核心**。以下を 1 つの PR で実施する。

1. `SpeechDispatcher.enabled = true` に切り替え（受信イベントを `orchestrator.enqueue()` に流す）。
2. `GuidanceSessionManager.applyGuidanceUpdate()` の `speak(currentStep.instruction)` 行を**削除**。
3. `GuidanceSessionManager.applyGuidanceUpdate()` 末尾に `coordinator.onNavigationUpdate(navInfo, isOffRoute)` を追加。
4. `GuidancePlanner.plan()` の実装:
   - `StepTransitionTracker` でステップ遷移判定
   - `crossedBuckets()` で距離下抜け検出
   - **最近接未発話バケットを 1 つ選択**（§4.4）
   - `SpokenGuideKey` で重複抑制
   - `AT_50M` 抑制（§2.3、stepCounter ベース）
   - `ManeuverType.FORK` / `MERGE` 判定と driving side 反映
5. `PhraseComposer.composeManeuver()` で距離 + 方向フレーズ連結。

**検証項目（実機）**:
- 右折 2km 手前で「およそ2km先、右方向です。」、500m で「およそ500m先、右方向です。」、100m で「まもなく、右方向です。」、50m は 100m 発話済みなら抑制。
- U ターン「まもなく、戻る方向です。」。
- 合流「およそ500m先、右からの合流が有ります。」。
- 分岐「およそ500m先、分岐です。」。
- セッション開始 / 経由地到着 / 最終到着 / オフルート / リルートが期待通り発話される。
- **旧 SDK テキスト発話「300m 先を右方向です。右方向に進みます」などが一切聞こえない**ことを確認。

**PR サイズ目安**: 500 行前後。

### Phase 3 — レーン誘導 + 道なり

目標: 体験の質向上。Phase 2 で cutover 済みのため、ここは機能追加のみ。

1. `GuidancePlanner` で `Lane` イベント生成（`AT_500M` のみ、ターン系マニューバのみ）。
2. 推奨車線位置判定（LEFT / RIGHT / CENTER のみ、該当しない場合はイベント非生成）。
3. `Straightforward` イベント生成（ステップ遷移時に 1 回、距離条件を満たすとき）。
4. `PhraseComposer.composeLane()` / `composeStraightforward()` 実装。

**検証項目**:
- 推奨が最左のみなら「およそ500m先、左側の車線を、お進みください。」。
- 次ステップまで 3km の直進区間で「しばらく道なりです。」。
- 次ステップまで 7km なら「5km以上道なりです。」。

### Phase 4 — 回帰テスト + 実機調整

目標: 実機での発話タイミングと抑制挙動の微調整。

1. 実機で複数ルート（市街地・郊外・高速混じり）を走破し、発話ログを取って期待テーブルと突き合わせ。
2. 距離バケット下抜けの取りこぼし実測。NavInfo 更新頻度が不足している場合は `NUM_NEXT_STEPS_TO_PREVIEW` や Service 設定を見直す。
3. `StepTransitionTracker` の誤判定ログ頻度を確認。偽陽性が多ければ `DISTANCE_SURGE_THRESHOLD_METERS` を調整、偽陰性が多ければ判定フィールド追加を検討。
4. `SpokenGuideKey` のメモリサイズを長時間ナビ（1 時間以上）で計測。`forgetOldSteps()` が効いていることを確認。
5. `SpeechDispatcher` の Channel 溢れログ頻度確認。ゼロに近いことを確認。
6. リルート検知の偽陽性/偽陰性確認（geometry.size 比較の粗さ）。

---

## 7. ケーススタディ（発話例）

### 7.1 一般道で右折 500m 手前〜 50m 手前（通常）

前提: `currentStep.maneuver=TURN_RIGHT`, `distanceToCurrentStepMeters` が段階的に減少。

| ティック | prev | curr | 下抜けバケット | 採用 | 発話 |
|---|---|---|---|---|---|
| t0 | null | 505 | - | - | - |
| t1 | 505 | 495 | `AT_500M` | `AT_500M` | 「およそ500m先、右方向です。」 |
| t2 | 495 | 105 | - | - | - |
| t3 | 105 | 95 | `AT_100M` | `AT_100M` | 「まもなく、右方向です。」 |
| t4 | 95 | 45 | `AT_50M` | 抑制 | （同一 stepCounter で `AT_100M` 発話済みのため） |

### 7.1b 距離ジャンプ（505m → 95m を 1 ティックで飛ぶ）

前提: 何らかの理由で NavInfo の更新が遅れ、1 ティックで 505m から 95m に飛んだケース。

| ティック | prev | curr | 下抜けバケット | 採用 | 発話 |
|---|---|---|---|---|---|
| t0 | null | 505 | - | - | - |
| t1 | 505 | 95 | `AT_500M`, `AT_100M` | **`AT_100M`** (最近接) | 「まもなく、右方向です。」 |
| t2 | 95 | 45 | `AT_50M` | 抑制 | （同一 stepCounter で `AT_100M` 発話済み） |

`AT_500M` は未発話のまま残るが、既に 95m 地点では価値が無い。最近接優先ポリシーにより「まもなく」と言うべき瞬間を逃さない。

### 7.1c 距離ジャンプ + 50m まで飛ぶ

| ティック | prev | curr | 下抜けバケット | 採用 | 発話 |
|---|---|---|---|---|---|
| t0 | null | 505 | - | - | - |
| t1 | 505 | 45 | `AT_500M`, `AT_100M`, `AT_50M` | **`AT_50M`** (最近接) | 「まもなく、右方向です。」 |

`AT_100M` 未発話のまま `AT_50M` を発話するのは §2.3 のルールで許可されている（`AT_100M` 未発話時は `AT_50M` 単独発話可）。

### 7.2 合流（左から）

`maneuver=MERGE_LEFT`, `drivingSide=LEFT`。`AT_500M` 到達:

発話: 「およそ500m先、左からの合流が有ります。」

### 7.3 分岐

`maneuver=FORK_RIGHT`。`AT_500M` 到達:

発話: 「およそ500m先、分岐です。」（※ v1 では分岐の左右区別はしない）

### 7.4 オフルート → リルート

1. `isOffRoute` false → true → `OffRoute` (CRITICAL, flush=true) → 「ルートから外れました。」
2. `routeChangedListener` 発火 → `routes` StateFlow 変化 → `Rerouted` (CRITICAL, flush=true) → 「新しいルートが見つかりました。新しいルートで案内します。」
3. `isOffRoute` true → false → `OnRouteRecovered` (CRITICAL, flush=true) → 「ルートに戻りました。」

発話順序は SDK の発火順に依存する。実機で確認して、不要に思える場合は `OnRouteRecovered` を `Rerouted` があった直後のみ抑制する等のチューニングを Phase 4 で行う。

### 7.5 長距離直進（道なり）

ステップ遷移直後、次ステップまで 7200m の区間:

発話: 「5km以上道なりです。」（LOW, flush=false）

---

## 8. リスクと未解決事項

| リスク | 影響 | 対応方針 |
|---|---|---|
| NavInfo の更新頻度が実機で遅いと距離バケット下抜けが複数同時発生する | 遠距離バケットの取りこぼし | 最近接優先ポリシーで「まもなく」発話を優先（§4.4）。Phase 4 で更新頻度を実測 |
| `StepTransitionTracker` の `isSameStep` 偽陽性（同一路名連続などで別ステップを同一扱い） | 重複発話、カウンタずれ | ヒューリスティック一致 + `distanceToCurrentStepMeters` 急増の 2 条件（§4.6）。距離補助条件で遷移判定した場合は Napier ログ出力。Phase 4 で誤判定頻度確認 |
| `StepTransitionTracker` の偽陰性（実際は別ステップなのに同一扱い） | 発話が次ステップまで遅れる | 実害小。優先度低。ログで頻度監視 |
| routeToken なしルートで SDK が別ルート計算 | `GoogleRoute.geometry` と実際の走行経路が乖離 | v1 では `GoogleRoute.steps`/`geometry` を発話ロジックに使わないため影響小。リルート検知は id + geometry.size で代替（§4.9） |
| リルート検知で `geometry.size` 比較が同数になり誤陰性 | リルート発話が出ない | Phase 4 で偽陰性頻度を確認。必要なら `distanceMeters` / `durationSeconds` を比較キーに追加 |
| Google Cloud TTS キャッシュヒット率低下 | コスト増・レイテンシ増 | v1 は固定フレーズのみ。動的文字列を一切含まない |
| `SpeechDispatcher` Channel のバックプレッシャ | キュー溢れ | capacity 32 + 古いもの coalesce（§4.7）。Phase 4 で溢れログ頻度確認 |
| `flush=true` で LOW/NORMAL 発話が頻繁に破棄される | 情報損失 | `CRITICAL`/`HIGH` 連発シナリオは稀（リルート直後など）。Phase 4 で体感確認 |
| Phase 1 で配線のみ入れた `SpeechDispatcher.enabled=false` 状態のまま Phase 2 を忘れる | cutover されず独自発話が出ない | Phase 2 PR のチェックリストに `enabled=true` 切替 + `speak(currentStep.instruction)` 削除を明記 |

---

## 9. v2 以降のバックログ

v1 完了後に順次実装する機能を明示。

| 項目 | 前提 |
|---|---|
| 高速道路案内（IC / JCT / 料金所 / SA 入口） | `GoogleRoutesDataSource` で `highwayInfo` / `roadName` / `roadRef` を埋める必要がある |
| 一般/高速切替通知 (#569/#570) | 同上 |
| 有料区間・自動車専用道通知 (#767/#768) | 同上 |
| 「その先」連続案内 | 連続案内の距離ポリシー確定 + 複数発話の順序保証設計 |
| 右折後 / 左折後 / 通過後 (#764-766) | ステップ遷移直後の即時発話設計 |
| 信号カウント (#754-755, #757-758) | 信号機データソース必要 |
| 車線 N 番目 / 中央右側/左側 / お入りください / 合流後/分岐後 | 現在走行車線の取得が必要 |
| 車線減少 / 専用レーン | 情報源必要 |
| 目的地タイプ別フレーズ (#51 → 駅/空港/バス停など) | `RoutePoint` に type 追加 + Places API から種別伝播 |
| 経由地/目的地スポット名読み上げ | `NavigationArrivalSnapshot.waypointTitle` にスポット名を入れる + TTS キャッシュ戦略見直し |
| 音声継続/中断 (#566-568, #690) | BG タイマー + セッション中断機構 |
| 渋滞 / 規制 / 気象 / 災害 / 天気 / 速度 / 踏切 / 一時停止 / カーブ / ゾーン30 / 景観 | 各データ源の統合 |
| ボイスコントロール | 音声認識エンジン統合 |

---

## 10. このあとの進め方

1. この計画書 (rev.3) のレビュー。
2. Phase 0 着手 → PR 化（型・strings・骨組み追加のみ、`GuidanceSessionManager` 無改修）。
3. Phase 1（配線のみ、`SpeechDispatcher.enabled=false`、動作変化なし）。
4. **Phase 2（単一 cutover）**: `enabled=true` 化 + `speak(currentStep.instruction)` 削除 + `coordinator.onNavigationUpdate()` 接続を 1 PR で実施。ここが本計画の核心。
5. Phase 3（レーン + 道なり）。
6. Phase 4（実機調整）。
7. v1 完了後、v2 として「高速道路案内」着手（`GoogleRoutesDataSource` の `highwayInfo` / `roadName` / `roadRef` 供給から）。
