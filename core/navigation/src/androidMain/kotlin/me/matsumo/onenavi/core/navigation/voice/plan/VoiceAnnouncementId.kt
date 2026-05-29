package me.matsumo.onenavi.core.navigation.voice.plan

/**
 * 発話 stage を route 寿命内で一意に識別する安定キー。
 *
 * 既発話判定 (二重発話の抑止) や barge-in の対象指定に使う。文字列の組み立て規約は
 * [VoiceAnnouncementPlanBuilder] が持つ。
 *
 * @property value `<routeId>#gp<index>#<blockId>` 形式の安定キー
 */
@JvmInline
internal value class VoiceAnnouncementId(val value: String)
