import type { LatLng } from "../geo-utils";

/** ルートプロバイダの識別子。 */
export type ProviderId = "google" | "here";

/** ルート探索1件の結果。シミュレーション engine は coords のみ消費する。 */
export interface ProviderRouteResult {
  /** 描画・再生に使う dense polyline。 */
  coords: LatLng[];
  /** 距離 (m)。取れない場合は null。 */
  distanceMeters: number | null;
  /** 所要時間 (s)。取れない場合は null。 */
  durationSeconds: number | null;
  /** プロバイダ生レスポンス（API bench / デバッグ表示用）。 */
  raw: unknown;
}

/** ルートプロバイダの共通契約。Google / HERE を同一 interface で切り替える。 */
export interface RouteProvider {
  readonly id: ProviderId;
  /** 表示名。 */
  readonly label: string;
  /** ルート線・UI に使うアクセントカラー（HEX）。 */
  readonly accent: string;
  /** waypoints (>=2) からルートを探索する。 */
  computeRoute(waypoints: LatLng[]): Promise<ProviderRouteResult>;
}
