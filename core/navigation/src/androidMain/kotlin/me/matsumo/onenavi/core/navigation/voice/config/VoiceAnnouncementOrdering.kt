package me.matsumo.onenavi.core.navigation.voice.config

/**
 * 緊急度ランクが同値のときに発話順を決める tie-break ポリシー。
 */
internal enum class VoiceAnnouncementOrdering {

    /** payload / source block の出現順を優先する (N 社の参照実装の配列順に準拠)。 */
    RouteOrder,
}
