package me.matsumo.onenavi.core.model

import kotlinx.serialization.Serializable

/**
 * 開発者向けオプションで個別に有効化できる機能。
 */
@Serializable
enum class DeveloperFeature {

    /** Plus 権限を開発者向けに強制付与する。 */
    FORCE_PLUS_PRIVILEGE,

    /** Plus 加入済みでも Paywall セクションを表示する。 */
    SHOW_PAYWALL_SECTION,

    /** アプリバージョン表記に開発者向けバッジを表示する。 */
    SHOW_DEVELOPER_BADGE,

    /** 端末の実位置を使わず、開発用の固定位置を返す。 */
    FAKE_GPS,

    /** 地図の描画密度や viewport の診断ログを有効にする。 */
    MAP_DIAGNOSTICS,

    /** Android Auto Virtual Display の診断オーバーレイを表示する。 */
    CAR_VD_DEBUG_OVERLAY,

    /** ナビゲーション中に TTS 発話予定のデバッグカードを表示する。 */
    TTS_SCHEDULE_DEBUG_CARD,

    /** Android Auto host から取得した車両ハードウェア値の診断一覧を表示する。 */
    CAR_HARDWARE_DIAGNOSTICS,
}
