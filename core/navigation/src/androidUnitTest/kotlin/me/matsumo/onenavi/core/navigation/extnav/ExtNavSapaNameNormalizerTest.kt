package me.matsumo.onenavi.core.navigation.extnav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SA/PA 名を API 間の照合 key に変換する正規化 helper のテスト。
 */
class ExtNavSapaNameNormalizerTest {

    @Test
    fun `全角 ASCII と空白差分を吸収して一致する`() {
        val matches = ExtNavSapaNameNormalizer.matches("テストPA", "テスト ＰＡ")

        assertTrue(matches)
    }

    @Test
    fun `片方が路線名や上下線を含む場合も一致する`() {
        val matches = ExtNavSapaNameNormalizer.matches("テストPA 上り", "テストPA")

        assertTrue(matches)
    }

    @Test
    fun `空白だけの名前は一致しない`() {
        val matches = ExtNavSapaNameNormalizer.matches("　", "テストPA")

        assertFalse(matches)
    }
}
