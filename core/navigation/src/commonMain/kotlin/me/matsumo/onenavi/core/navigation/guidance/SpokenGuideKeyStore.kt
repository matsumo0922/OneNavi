package me.matsumo.onenavi.core.navigation.guidance

/**
 * [SpokenGuideKey] の可変セットを保持するストア。
 *
 * Planner に渡す際はスナップショット（読み取り専用 Set）を取り、
 * 更新は Coordinator 側から行う。古いステップの鍵を `forgetBefore()` で掃除してメモリを節約する。
 */
internal class SpokenGuideKeyStore {

    private val keys: MutableSet<SpokenGuideKey> = mutableSetOf()

    val size: Int get() = keys.size

    fun snapshot(): Set<SpokenGuideKey> = keys.toSet()

    fun contains(key: SpokenGuideKey): Boolean = key in keys

    fun add(key: SpokenGuideKey) {
        keys.add(key)
    }

    fun clear() {
        keys.clear()
    }

    /**
     * `stepCounter < threshold` のキーを破棄する。
     *
     * ステップが進むたびに呼び、過去ステップ分のキーを掃除する。
     */
    fun forgetBefore(threshold: Int) {
        keys.removeAll { key -> key.stepCounter < threshold }
    }
}
