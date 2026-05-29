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
import kotlin.test.assertTrue

/**
 * [VoiceAnnouncementContentRenderer] の category gate フィルタ / text・SSML 結合のテスト。
 */
class VoiceAnnouncementContentRendererTest {

    @Test
    fun `有効な素片の text を順に結合する`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "300m先 ", category = GuidanceCategory.IntersectionGuide),
            pieceOf(text = "右方向です", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertEquals("300m先 右方向です", content?.text)
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

        assertEquals("右方向です", content?.text)
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
    fun `SSML を持つ素片があれば speak で囲んだ SSML を作る`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "右です", ssml = "右です", category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertEquals("<speak>右です</speak>", content?.ssml)
    }

    @Test
    fun `SSML を持つ素片が無ければ ssml は null`() {
        val renderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn)
        val stage = stageOf(
            pieceOf(text = "右です", ssml = null, category = GuidanceCategory.IntersectionGuide),
        )

        val content = renderer.render(stage)

        assertTrue(content?.text == "右です" && content.ssml == null)
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
        kind = AnnouncementStageKind.MIDDLE,
        triggerSourceMeters = 0.0,
        triggerGeometryMeters = 0.0,
        pieces = pieces.toList().toImmutableList(),
        categories = persistentSetOf(),
    )
}
