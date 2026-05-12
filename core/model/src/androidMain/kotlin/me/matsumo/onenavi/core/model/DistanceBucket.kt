package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * マニューバ予告の距離バケット。
 *
 * `distanceToCurrentStepMeters` が閾値を跨いだ瞬間に該当バケットが「下抜け」したとみなし、
 * そのバケット用フレーズで案内を発話する。
 *
 * @property thresholdMeters 下抜け判定に用いる距離（メートル）。
 * @property phraseId 通常の文頭フレーズ。
 * @property standalonePhraseId `AT_50M` 単独発話時（`AT_100M` 未発話のまま 50m 以下に入ったケース）の文頭フレーズ。
 *   他のバケットでは [phraseId] と同一で問題ない。
 */
@Immutable
enum class DistanceBucket(
    val thresholdMeters: Int,
    val phraseId: TtsPhraseId,
    val standalonePhraseId: TtsPhraseId = phraseId,
) {
    /** およそ2km先。 */
    AT_2KM(
        thresholdMeters = 2000,
        phraseId = TtsPhraseId.DISTANCE_2KM,
    ),

    /** およそ500m先。 */
    AT_500M(
        thresholdMeters = 500,
        phraseId = TtsPhraseId.DISTANCE_500M,
    ),

    /** まもなく（100m）。 */
    AT_100M(
        thresholdMeters = 100,
        phraseId = TtsPhraseId.TIMING_IMMINENT,
    ),

    /** 直前（50m）。`AT_100M` 未発話の単独発話では「この先すぐ、」に切り替わる。 */
    AT_50M(
        thresholdMeters = 50,
        phraseId = TtsPhraseId.TIMING_IMMINENT,
        standalonePhraseId = TtsPhraseId.TIMING_VERY_IMMINENT,
    ),
}
