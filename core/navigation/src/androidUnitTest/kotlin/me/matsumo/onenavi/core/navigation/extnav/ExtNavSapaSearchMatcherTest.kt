package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.sapa.domain.SapaId
import me.matsumo.drive.supporter.api.sapa.domain.SapaSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SA/PA 近傍検索結果とルート案内上の施設名の照合テスト。
 */
class ExtNavSapaSearchMatcherTest {

    @Test
    fun `対象名に一致する候補だけを採用する`() {
        val expectedResult = buildSearchResult("00000000228", "テストPA")
        val actualResult = persistentListOf(
            buildSearchResult("00000000111", "別のSA"),
            expectedResult,
        ).bestSapaSearchMatchFor("テストＰＡ")

        assertEquals(expectedResult, actualResult)
    }

    @Test
    fun `対象名に一致しない候補だけなら null を返す`() {
        val actualResult = persistentListOf(
            buildSearchResult("00000000111", "別のSA"),
            buildSearchResult("00000000222", "隣のPA"),
        ).bestSapaSearchMatchFor("テストPA")

        assertNull(actualResult)
    }

    private fun buildSearchResult(id: String, name: String): SapaSearchResult =
        SapaSearchResult(
            id = SapaId(id),
            spotCode = "02007-$id",
            name = name,
            coord = Coord(
                latMsec = 127_553_040,
                lonMsec = 501_822_000,
            ),
            distanceMeters = 120,
        )
}
