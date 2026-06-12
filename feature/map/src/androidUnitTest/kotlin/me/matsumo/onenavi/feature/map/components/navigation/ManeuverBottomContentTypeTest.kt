package me.matsumo.onenavi.feature.map.components.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Maneuver カード下段コンテンツの優先度解決テスト。
 */
class ManeuverBottomContentTypeTest {

    @Test
    fun 案内画像はパネルより優先される() {
        val contentType = resolveManeuverBottomContentType(
            hasVisibleGuideImage = true,
            hasPanelItems = true,
            showPanel = true,
            shouldPreferFollowupHint = true,
            hasLaneCells = true,
            hasFollowupCallout = true,
        )

        assertEquals(ManeuverBottomContentType.GuideImage, contentType)
    }

    @Test
    fun パネル展開中はレーンよりパネルが優先される() {
        val contentType = resolveManeuverBottomContentType(
            hasVisibleGuideImage = false,
            hasPanelItems = true,
            showPanel = true,
            shouldPreferFollowupHint = false,
            hasLaneCells = true,
            hasFollowupCallout = true,
        )

        assertEquals(ManeuverBottomContentType.Panel, contentType)
    }

    @Test
    fun 案内画像距離外のフォローアップはレーンより優先される() {
        val contentType = resolveManeuverBottomContentType(
            hasVisibleGuideImage = false,
            hasPanelItems = false,
            showPanel = false,
            shouldPreferFollowupHint = true,
            hasLaneCells = true,
            hasFollowupCallout = true,
        )

        assertEquals(ManeuverBottomContentType.Followup, contentType)
    }

    @Test
    fun パネル非展開ではレーンが通常フォローアップより優先される() {
        val contentType = resolveManeuverBottomContentType(
            hasVisibleGuideImage = false,
            hasPanelItems = true,
            showPanel = false,
            shouldPreferFollowupHint = false,
            hasLaneCells = true,
            hasFollowupCallout = true,
        )

        assertEquals(ManeuverBottomContentType.Lanes, contentType)
    }

    @Test
    fun 表示候補が無い場合は下段なしになる() {
        val contentType = resolveManeuverBottomContentType(
            hasVisibleGuideImage = false,
            hasPanelItems = false,
            showPanel = false,
            shouldPreferFollowupHint = false,
            hasLaneCells = false,
            hasFollowupCallout = false,
        )

        assertEquals(ManeuverBottomContentType.None, contentType)
    }
}
