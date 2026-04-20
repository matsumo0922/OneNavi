package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * 音声案内の発話単位（セグメントの並び）。
 *
 * `PhraseComposer.compose()` が `GuidanceEvent` から組み立てた segments を持ち、
 * `PhraseComposer.resolve()` で strings.xml を参照した最終的な発話文字列へ変換される。
 */
@Immutable
data class GuidancePhrase(
    val segments: ImmutableList<PhraseSegment>,
)

/**
 * 発話セグメント。
 *
 * 固定文言（[Fixed]）と距離バケット由来の文頭フレーズ（[Distance]）の 2 種類を持つ。
 * Distance は `AT_50M` 単独発話モードでは `standalonePhraseId` が使われるため、
 * 直接 [TtsPhraseId] を持たず [DistanceBucket] を保持して `PhraseComposer` 側で解決する。
 */
@Immutable
sealed interface PhraseSegment {
    /** strings.xml の固定フレーズ。 */
    @Immutable
    data class Fixed(val phraseId: TtsPhraseId) : PhraseSegment

    /**
     * 距離バケット由来のフレーズ。
     *
     * @property bucket 対象バケット。
     * @property isStandalone `AT_50M` 単独発話モードか。true のとき `standalonePhraseId` を使用する。
     */
    @Immutable
    data class Distance(
        val bucket: DistanceBucket,
        val isStandalone: Boolean = false,
    ) : PhraseSegment
}
