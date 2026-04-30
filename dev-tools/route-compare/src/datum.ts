/**
 * 旧日本測地系 (Tokyo Datum) → WGS84 の簡易変換 (Hosoi 式)。
 *
 * drive-supporter-api/analysis/sample/* の生データはサーバー既定の Tokyo Datum
 * (`unit.datum = "tokyo"`) で記録されている。Google Maps は WGS84 で描画するため、
 * そのまま緯度経度をプロットすると約 ~400m 北東側にずれる。
 *
 * 公式 TKY2JGD ほどの精度は不要なデバッグ用途なので、Hosoi の 1 次近似で十分。
 * 参考: 国土地理院テクニカルレポート / Hosoi 簡易式 (誤差 ~数 m 〜 十数 m)。
 */

import type {LatLng} from "./samples";

export function tokyoToWgs84(point: LatLng): LatLng {
  const { lat, lng } = point;
  return {
    lat: lat - 0.00010695 * lat + 0.000017464 * lng + 0.0046017,
    lng: lng - 0.000046038 * lat - 0.000083043 * lng + 0.010040,
  };
}

export function tokyoToWgs84Path(path: LatLng[]): LatLng[] {
  return path.map(tokyoToWgs84);
}
