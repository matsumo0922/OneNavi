/**
 * Google encoded polyline algorithm のデコーダ。
 * Routes API v2 は polyline.encodedPolyline 文字列を返すのでここで LatLng[] に戻す。
 *
 * 参考: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */

import type {LatLng} from "./samples";

export function decodePolyline(encoded: string): LatLng[] {
  const coords: LatLng[] = [];
  let cursor = 0;
  let lat = 0;
  let lng = 0;

  while (cursor < encoded.length) {
    const latDelta = decodeNext(encoded, cursor);
    cursor = latDelta.next;
    lat += latDelta.value;

    const lngDelta = decodeNext(encoded, cursor);
    cursor = lngDelta.next;
    lng += lngDelta.value;

    coords.push({ lat: lat / 1e5, lng: lng / 1e5 });
  }
  return coords;
}

function decodeNext(encoded: string, start: number): { value: number; next: number } {
  let result = 0;
  let shift = 0;
  let cursor = start;
  let byte: number;
  do {
    byte = encoded.charCodeAt(cursor++) - 63;
    result |= (byte & 0x1f) << shift;
    shift += 5;
  } while (byte >= 0x20);

  const value = (result & 1) !== 0 ? ~(result >> 1) : result >> 1;
  return { value, next: cursor };
}
