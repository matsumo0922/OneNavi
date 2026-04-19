# 独自ボイスガイダンス実装計画 (rev.2)

- 作成日: 2026-04-20
- 改訂日: 2026-04-20 (Codex レビュー反映 + 現状コード再調査を反映)
- 対象モジュール: `core:navigation`, `core:model`, `core:resource`
- 対象範囲: 日本語のみ（多言語化は対象外）
- 参照: `docs/note/tts-required-features.md` / `docs/note/drive-supporter-tts-guide.md`

---

## 0. 変更履歴 (rev.2 で変わったポイント)

初版 (rev.1) に対する Codex レビューと現状コード再調査の結果、以下を反映した。

- **[HIGH]** 高速道路案内のスコープを大幅縮小。`GoogleRoutesDataSource.kt:161-188` の `RouteStepInfo` 生成は `highwayInfo=null` / `roadName=""` / `roadRef=null` で固定されており、IC・JCT・料金所・SA の識別情報が一切供給されていない。`#67`「高速入口です」・`#68`「高速出口です」・`#69`「料金所です」・`#569-570`「一般/高速切替」・`#767-768`「有料区間に入ります」などは v1 非対応とし、v2 で datasource 強化とセットで扱う。
- **[HIGH]** `stepId = (maneuver, roadName, cumulativeDistance)` を廃止。`NavigationStepSnapshot` には index / position / 固有 ID に相当するフィールドが無く、`NavigationUpdatesService` 経由で受け取る `StepInfo` も現状スナップショットに sequence を乗せていない。`GoogleRoute.steps` との逆算も route token 不在時に SDK 側で別ルート計算されうる（`NavigationSdkManager.kt:222-226` のコメント参照）。v1 では **session-local な step transition counter** を採用する（詳細 §4.6）。
- **[HIGH]** Phase 1/2 を統合。SDK テキスト垂れ流し発話と独自発話の併走期間を設けない。1 つの cutover で置換する。
- **[HIGH]** `PhraseBuilder.build()` を `suspend` とし、`GuidanceEventDispatcher` を `Channel<GuidanceEvent>` + 単一コルーチンの **直列 actor** に再設計。`scope.launch { dispatch(event) }` の多重起動をやめる。
- **[HIGH]** 距離バケットを **ユーザー指定通り `2km / 500m / 100m / 50m` に固定**。`1km` / `300m` は廃止。「`100m/50m` = 常に『まもなく、』」という固定ルールも廃止。距離フレーズとタイミング修飾をテーブルで独立に指定できるポリシー形にする。
- **[MEDIUM]** `GuidanceEvent` は phrasing を持たない `SemanticEvent` に変更。`PhraseComposer` が別工程で segments を生成。`GuidancePlanner` は純関数に保つ。
- **[MEDIUM]** 連続案内の距離閾値を固定値 200m から「一般道 ≤200m / 高速 ≤500m」へ。ただし v1 の時点では**「その先」連続案内を scope 外**にする（レビュー指摘の連続発話と重複抑制の整合性を次版で詰める）。
- **[MEDIUM]** バケット下抜けの複数閾値跨ぎに備え、`crossedBuckets(previousDistance, currentDistance)` 形式で複数候補を取得し、**最遠の未発話バケットのみ**を採用（発話は常にルート上で前方に向かって進行するため、近いバケットに飛ばない）。
- **[MEDIUM]** `#566`「音声案内を継続します」・`#567`「一定時間が経過したため中断」・`#568`「あと5分で中断」・`#690`「案内停止中」は背景タイムアウト機構が未実装のため v1 非対応（スコープ明記）。
- **[MEDIUM]** 目的地タイプ別フレーズ差し替えは v1 非対応（`RoutePoint` に type が無く、Places API からも種別が流れてきていない）。v1 は `#51`「目的地付近です」+ `#53`「お疲れ様でした」固定。
- **[MEDIUM]** レーン案内は v1 では **「右側 / 左側 / 中央」の 3 パターン + 常に『お進みください』固定**。「右から N 番目」「中央右側/中央左側」「お入りください」「車線減少」「専用レーン」「合流後/分岐後」は v2 へ。
- **[LOW]** 新規 data class / sealed class に `@Immutable` を付与する方針を明記。
- **[LOW]** TTS キャッシュヒット率を下げないため、v1 は動的文字列（IC 名・道路名など）を発話テキストに一切含めない。

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

- `AT_50M` は `AT_100M` 発話後 **5 秒以内なら抑制**する（重複回避）。
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

**複数バケット跨ぎの処置**: `crossedBuckets` が 2 つ以上返った場合、**最も遠いバケット（= 最大 threshold）の未発話のみを採用**する。近いバケット（= より直前の案内）は抑制。理由：距離予告は「より遠くの予告をしておく」方が運転上の価値が高く、近距離バケットは次ティックで再検出される可能性が高い。

**50m/100m の連続抑制**: `AT_100M` を発話した時刻を記録し、5 秒以内の `AT_50M` は抑制。

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

    fun update(currentStep: NavigationStepSnapshot?): StepTransitionResult {
        val last = lastStep
        val transitioned = when {
            last == null && currentStep == null -> false
            last == null && currentStep != null -> {
                counter = 0
                true
            }
            last != null && currentStep == null -> false  // ENROUTE → REROUTING 等
            else -> !isSameStep(last!!, currentStep!!)
        }
        if (transitioned) counter++
        lastStep = currentStep
        return StepTransitionResult(counter = counter, transitioned = transitioned)
    }

    private fun isSameStep(a: NavigationStepSnapshot, b: NavigationStepSnapshot): Boolean {
        // maneuver / roadName / drivingSide / roundaboutTurnNumber が全一致なら同一ステップとみなす
        // 偽陰性（=違うステップを同一扱い）のときは発話が次ステップまで遅れるが、ナビ品質は壊れない
        // 偽陽性（=同じステップを別扱い）のときは発話重複のリスクがある
        // → 安全側として少し厳しめの一致条件を使う
        return a.maneuver == b.maneuver &&
            a.roadName == b.roadName &&
            a.simpleRoadName == b.simpleRoadName &&
            a.drivingSide == b.drivingSide &&
            a.roundaboutTurnNumber == b.roundaboutTurnNumber
    }

    fun reset() {
        counter = 0
        lastStep = null
    }
}
```

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

### 4.7 `SpeechDispatcher`（単一 actor）

```kotlin
internal class SpeechDispatcher(
    private val orchestrator: SpeechOrchestrator,
    private val composer: PhraseComposer,
    private val scope: CoroutineScope,
) {
    private val channel = Channel<GuidanceEvent>(capacity = Channel.UNLIMITED)
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
        channel.trySend(event)
    }

    fun shutdown() {
        channel.close()
        job?.cancel()
        job = null
    }

    companion object { private const val TAG = "SpeechDispatcher" }
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
    private var lastImminentAtMs: Long = 0L

    fun onNavigationUpdate(snapshot: NavigationFeedSnapshot, isOffRoute: Boolean) {
        val step = snapshot.currentStep
        val transitionResult = stepTracker.update(step)
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
        lastImminentAtMs = 0L
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

リルート検出: `routeManager.routes` の `distinctUntilChanged { old, new -> old.firstOrNull()?.routeToken == new.firstOrNull()?.routeToken }` で変化検知して emit。

`stopSession()`:

```kotlin
fun stopSession() {
    dispatcher.shutdown()
    coordinator.reset()
    // ... 既存終了処理
}
```

**重要**: rev.1 で Phase 1/2 を分けていた「SDK テキスト発話残しつつ併走」は廃止。この `applyGuidanceUpdate()` の書き換えと同時に `speak(currentStep.instruction)` 行を削除する。すなわち **cutover は 1 コミットで実施**。

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

## 6. 実装フェーズ（rev.2）

### Phase 0 — 基盤整備（動作変化なし、cutover 無し）

目標: 型・リソース・ビルド通過。既存発話ロジックは**変更しない**。

1. `strings.xml` に §5 の 27 件を追加。
2. `core:model` に追加:
   - `GuidanceEvent` sealed interface（`@Immutable`）
   - `GuidancePriority` enum
   - `DistanceBucket` enum
   - `LanePosition` enum
   - `StraightforwardLevel` enum
   - `GuidancePhrase` / `PhraseSegment`（`@Immutable`）
3. `core:navigation/androidMain/guidance/` に骨組み追加（空実装で可）:
   - `TtsPhraseId`
   - `PhraseComposer`
   - `SpokenGuideKeyStore`
   - `StepTransitionTracker`
   - `SpeechDispatcher`
   - `GuidancePlanner`（`emptyList()` を返す空実装）
   - `GuidanceCoordinator`
4. `PhraseComposer` は単体テストで主要ブランチを検証（JVM test）。
5. `./gradlew assembleDebug --no-configuration-cache` / `make detekt` 通過確認。

**PR サイズ目安**: 300 行前後。

### Phase 1 — セッション系・到着・オフルート・リルートの cutover

目標: ナビ開始直後 / 到着 / オフルート / リルートの発話を独自化。ただし **この時点では基本ターンバイターンも従来の SDK テキスト発話は維持** する（ステップ単位の 1 回発話）。SDK テキスト発話と衝突するのは「起動時」「ルート逸脱直後」など一瞬のみで、`CRITICAL` の `flush=true` で割り込むため実害が小さい。

1. `GuidanceSessionManager.startSession()` に `dispatcher.start()` + `SessionStarted` emit を追加。
2. `stopSession()` に `dispatcher.shutdown()` + `reset()` を追加。
3. `arrivalEvents.collect` に `ViaWaypointApproach` / `DestinationApproach` emit を追加。
4. `isOffRoute` の変化を監視して `OffRoute` / `OnRouteRecovered` emit。
5. `routeManager.routes` の変化を監視して `Rerouted` emit。
6. `PhraseComposer` の対応イベント型を実装。

**検証項目**:
- 起動直後に「音声案内を開始します。実際の交通規制に従って走行してください。」が流れる。
- 経由地到着時に「経由地付近です。」。
- 最終到着時に「目的地付近です。お疲れ様でした。」。
- ルート逸脱時に「ルートから外れました。」、復帰時に「ルートに戻りました。」。
- 新ルート適用時に「新しいルートが見つかりました。新しいルートで案内します。」。

### Phase 2 — 基本ターンバイターン + 分岐/合流（cutover 本番）

目標: SDK テキストの直接発話を**削除**し、独自発話に置換。

1. `GuidanceSessionManager.applyGuidanceUpdate()` の `speak(currentStep.instruction)` 行を削除。
2. `GuidanceCoordinator.onNavigationUpdate()` を同ロジック末尾で呼び出し。
3. `GuidancePlanner.plan()` で `Maneuver` イベント生成を実装:
   - `StepTransitionTracker` でステップ遷移判定
   - `crossedBuckets()` で距離下抜け検出
   - 最遠未発話バケットを 1 つ選択
   - `SpokenGuideKey` で重複抑制
   - `AT_100M` / `AT_50M` の連続抑制（5 秒ウィンドウ）
4. `PhraseComposer.composeManeuver()` で距離フレーズ + 方向フレーズ連結。
5. `ManeuverType.FORK` → `FORK_END`、`MERGE` → 左右/不明の分岐。

**検証項目**:
- 右折 2km / 500m / 100m / 50m 手前で発話。
- U ターン「戻る方向です。」。
- 合流「右からの合流が有ります。」。
- 分岐「分岐です。」。
- 100m 発話直後の 50m 抑制が効く。

### Phase 3 — レーン誘導 + 道なり

目標: 体験の質向上。

1. `GuidancePlanner` で `Lane` イベント生成（`AT_500M` のみ、ターン系マニューバのみ）。
2. 推奨車線位置判定（LEFT / RIGHT / CENTER のみ、該当しない場合はイベント非生成）。
3. `Straightforward` イベント生成（ステップ遷移時に 1 回）。
4. `PhraseComposer.composeLane()` / `composeStraightforward()` 実装。

**検証項目**:
- 推奨が最左のみなら「およそ500m先、左側の車線を、お進みください。」。
- 次ステップまで 3km の直進区間で「しばらく道なりです。」。
- 次ステップまで 7km なら「5km以上道なりです。」。

### Phase 4 — 回帰テスト + 調整

目標: 実機での発話タイミングと抑制挙動の微調整。

1. 実機で複数ルート（市街地・郊外・高速混じり）を走破し、発話ログを取って期待テーブルと突き合わせ。
2. 距離バケット下抜けの取りこぼし実測。必要に応じて `PROGRESS_DISTANCE_THRESHOLD_METERS` の引き下げを検討（現 50m）。
3. `SpokenGuideKey` のメモリリーク有無確認（長時間ナビ後の `spokenKeys` サイズ）。
4. `SpeechDispatcher` の Channel バックプレッシャ確認。

---

## 7. ケーススタディ（発話例）

### 7.1 一般道で右折 500m 手前〜 50m 手前

前提: `currentStep.maneuver=TURN_RIGHT`, `distanceToCurrentStepMeters` が 505 → 95 → 45 と遷移。

| ティック | prev | curr | 下抜けバケット | 発話 |
|---|---|---|---|---|
| t0 | null | 505 | - | - |
| t1 | 505 | 495 | `AT_500M` | 「およそ500m先、右方向です。」 |
| t2 | 495 | 105 | - | - |
| t3 | 105 | 95 | `AT_100M` | 「まもなく、右方向です。」 |
| t4 | 95 | 45 | `AT_50M` | （`AT_100M` 発話から 5s 未満なら抑制） |

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
| NavInfo の更新頻度が実機で遅いと距離バケット下抜けが取りこぼされる | 500m 発話が出ない等 | Phase 4 で実測。必要なら `registerServiceForNavUpdates` の NumNextStepsToPreview / `PROGRESS_DISTANCE_THRESHOLD_METERS` を調整 |
| `StepTransitionTracker` の `isSameStep` 判定が誤検出（偽陽性） | 重複発話、カウンタずれ | 判定フィールドを厳しめに（maneuver + roadName + drivingSide + roundabout）。Phase 4 で実機確認 |
| `StepTransitionTracker` の偽陰性（実際は別ステップなのに同一扱い） | 発話が次ステップまで遅れる | 実害小。優先度低 |
| routeToken なしルートで SDK が別ルート計算 | ステップ情報と事前 `GoogleRoute.steps` の乖離 | v1 では `GoogleRoute.steps` を発話ロジックで使わないため影響小 |
| Phase 1 期間中の SDK テキスト発話との重複 | 短時間の二重発話 | `CRITICAL` の flush=true で割り込む。重複の体感が強ければ Phase 1-2 を結合して一括 cutover |
| Google Cloud TTS キャッシュヒット率低下 | コスト増・レイテンシ増 | v1 は固定フレーズのみ。動的文字列は含まない |
| `SpeechDispatcher` Channel のバックプレッシャ | キュー溢れ | `Channel.UNLIMITED` を採用。実測でメモリ懸念あれば `CONFLATED` 等に変更 |
| `flush=true` で LOW/NORMAL 発話が頻繁に破棄される | 情報損失 | `CRITICAL`/`HIGH` を連発するシナリオは稀。Phase 4 で確認 |

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

1. この計画書 (rev.2) のレビュー。
2. Phase 0 着手 → PR 化（型追加と strings 追加のみ、動作変化なし）。
3. Phase 1（セッション/到着/オフルート/リルート）。
4. Phase 2（基本 TBT + 分岐/合流の cutover）。SDK テキスト発話を削除するのはここ。
5. Phase 3（レーン + 道なり）。
6. Phase 4（実機調整）。
7. v1 完了後、v2 として「高速道路案内」着手（datasource 強化を伴う）。
