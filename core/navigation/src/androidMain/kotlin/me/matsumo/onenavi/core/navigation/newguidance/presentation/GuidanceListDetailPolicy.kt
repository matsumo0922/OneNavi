package me.matsumo.onenavi.core.navigation.newguidance.presentation

import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepFacility

/**
 * フルリスト行の単一 detail 枠に何を出すかを優先順位で 1 度だけ決める policy (L3)。
 *
 * 複数の補足 (レーン / 料金 / 設備 / 境界 / 看板) を持つイベントでも、表示できる枠は 1 つ。
 * 優先順位は **料金 (料金所) > SA/PA 設備 > レーン > 境界 > 看板** の固定順とし、domain ではなく
 * この policy に閉じる。料金所では (レーンを持っていても) 料金を優先し、設備が取れている SA/PA は
 * 設備の判別を優先する。文脈ごとの調整が要るときは domain に触れずここだけ変える。
 * レーン判定で使う [LanePresentation] は位置依存 (矢印の向き) のため呼び出し側が構築して渡す。
 * 状態を持たない。
 *
 * 料金は per-gate 値ではなくルート合計 (`tollTotalYen`) のみを持つため、料金所行に合計を出す。
 */
internal class GuidanceListDetailPolicy {

    /**
     * イベントの detail 枠に出す補助表示を 1 つ選ぶ。出すものが無ければ null。
     *
     * @param event 対象イベント
     * @param lanePresentation 構築済みの表示用レーン (描画できない場合は null)
     * @param tollTotalYen ルート合計料金。無ければ null
     * @return detail 枠に出す補助表示。無ければ null
     */
    fun selectDetail(
        event: GuidanceEvent,
        lanePresentation: LanePresentation?,
        tollTotalYen: Int?,
    ): GuidanceListDetail? {
        val tollDetail = tollDetailOrNull(facility = event.details.facility, tollTotalYen = tollTotalYen)
        if (tollDetail != null) return tollDetail

        val facilityServices = event.details.facility?.services
            ?.takeIf { services -> services.isNotEmpty() }
        if (facilityServices != null) return GuidanceListDetail.FacilityServices(services = facilityServices)

        if (lanePresentation != null) return GuidanceListDetail.Lanes(lane = lanePresentation)

        val boundary = event.details.boundary
        if (boundary != null) return GuidanceListDetail.Boundary(kind = boundary)

        val signpostText = event.details.signpost?.primary
        if (signpostText != null) return GuidanceListDetail.Signpost(text = signpostText)

        return null
    }

    /**
     * 料金所行にルート合計料金を出す。料金所でない、または合計が無ければ null。
     *
     * @param facility 行の施設。無ければ null
     * @param tollTotalYen ルート合計料金。無ければ null
     * @return 料金 detail。対象でなければ null
     */
    private fun tollDetailOrNull(
        facility: StepFacility?,
        tollTotalYen: Int?,
    ): GuidanceListDetail? {
        if (facility?.kind != FacilityKind.TOLL_GATE) return null
        val amountYen = tollTotalYen ?: return null
        return GuidanceListDetail.Toll(amountYen = amountYen)
    }
}
