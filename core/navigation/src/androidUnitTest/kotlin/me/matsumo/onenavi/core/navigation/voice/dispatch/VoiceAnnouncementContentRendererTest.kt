package me.matsumo.onenavi.core.navigation.voice.dispatch

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [VoiceAnnouncementContentRenderer] の category gate フィルタ / SSML 結合 (素片間の読点ポーズ) のテスト。
 */
class VoiceAnnouncementContentRendererTest {

    @Test
    fun `有効な素片を読点で繋いだ SSML を作る`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "300m先 ", category = GuidanceCategory.IntersectionGuide),
            pieceOf(text = "右方向です", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertEquals("<speak>300m先 、右方向です</speak>", content?.ssml)
    }

    @Test
    fun `直前が句読点で終わる素片には読点を重ねない`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "右方向、", category = GuidanceCategory.IntersectionGuide),
            pieceOf(text = "入口です。", category = GuidanceCategory.IntersectionGuide),
            pieceOf(text = "その先", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        // 「右方向、」の後と「入口です。」の後は既に句読点があるので読点を足さない。
        assertEquals("<speak>右方向、入口です。その先</speak>", content?.ssml)
    }

    @Test
    fun `OFF にした category の素片は除外する`() {
        val gate = VoiceAnnouncementCategoryGate.of(GuidanceCategory.Curve to false)
        val renderer = VoiceAnnouncementContentRenderer(gate)
        val stage = stageOf(
            pieceOf(text = "右方向です", category = GuidanceCategory.IntersectionGuide),
            pieceOf(text = "急カーブ注意", category = GuidanceCategory.Curve),
        )

        val content = renderer.render(stage)

        assertEquals("<speak>右方向です</speak>", content?.ssml)
    }

    @Test
    fun `全素片が OFF なら null を返す`() {
        val gate = VoiceAnnouncementCategoryGate.of(GuidanceCategory.Curve to false)
        val renderer = VoiceAnnouncementContentRenderer(gate)
        val stage = stageOf(
            pieceOf(text = "急カーブ注意", category = GuidanceCategory.Curve),
        )

        val content = renderer.render(stage)

        assertNull(content)
    }

    @Test
    fun `SSML を持つ素片はそのまま speak で囲む`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "右です", ssml = "右です", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertEquals("<speak>右です</speak>", content?.ssml)
    }

    @Test
    fun `SSML と plain text が混在しても plain 素片を escape して取り込む`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "300m先 ", ssml = null, category = GuidanceCategory.IntersectionGuide),
            pieceOf(text = "右です", ssml = "右です", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        // plain 素片 (300m先) が SSML から欠落せず、ssml 素片と読点で繋がる。
        assertEquals("<speak>300m先 、右です</speak>", content?.ssml)
    }

    @Test
    fun `SSML 経路に取り込む plain text の XML 特殊文字を escape する`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "A&B<C> ", ssml = null, category = GuidanceCategory.IntersectionGuide),
            pieceOf(text = "右です", ssml = "右です", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertEquals("<speak>A&amp;B&lt;C&gt; 、右です</speak>", content?.ssml)
    }

    @Test
    fun `SSML を持たない素片だけでも SSML として読み上げる`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "右です", ssml = null, category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertEquals("<speak>右です</speak>", content?.ssml)
    }

    @Test
    fun `ポーンを TTS に読ませずチャイム cue にする`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "ポーン右方向です", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertEquals(VoiceAnnouncementCue.CHIME, content?.cue)
        assertEquals("<speak>右方向です</speak>", content?.ssml)
    }

    @Test
    fun `ポーンだけの素片はチャイムのみの内容にする`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "ポーン", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertEquals(VoiceAnnouncementCue.CHIME, content?.cue)
        assertNull(content?.ssml)
    }

    @Test
    fun `OFF にした category のポーンはチャイムも鳴らさない`() {
        val gate = VoiceAnnouncementCategoryGate.of(GuidanceCategory.Curve to false)
        val renderer = VoiceAnnouncementContentRenderer(gate)
        val stage = stageOf(
            pieceOf(text = "ポーン", category = GuidanceCategory.Curve),
        )

        val content = renderer.render(stage)

        assertNull(content)
    }

    private fun pieceOf(
        text: String,
        ssml: String? = null,
        category: GuidanceCategory,
    ): GuideAnnouncementPiece = GuideAnnouncementPiece(
        text = text,
        ssml = ssml,
        templateRef = null,
        category = category,
    )

    private fun stageOf(vararg pieces: GuideAnnouncementPiece): AnnouncementStage = AnnouncementStage(
        id = VoiceAnnouncementId("stage"),
        groupKey = VoiceAnnouncementId("group"),
        kind = AnnouncementStageKind.MIDDLE,
        triggerSourceMeters = 0.0,
        triggerGeometryMeters = 0.0,
        middleWindow = null,
        isGeneric = false,
        pieces = pieces.toList().toImmutableList(),
        categories = persistentSetOf(),
    )
}
