import type { LatLng } from "../geo-utils";

// HERE Flexible Polyline デコーダ（2D のみ。3rd dimension は読み飛ばす）。
// 仕様: https://github.com/heremaps/flexible-polyline
// 外部依存を増やさないため最小実装で持つ。

const ENCODING = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

const DECODING: Record<number, number> = {};
for (let index = 0; index < ENCODING.length; index++) {
  DECODING[ENCODING.charCodeAt(index)] = index;
}

interface Decoder {
  /** 符号なし varint を 1 つ読む（header 用。zigzag しない）。 */
  nextUnsigned(): number;
  /** 符号付き varint を 1 つ読む（座標デルタ用。zigzag する）。 */
  nextSigned(): number;
  hasNext(): boolean;
}

function createDecoder(encoded: string): Decoder {
  let position = 0;

  // 32-bit シフトのオーバーフローを避けるため累積は乗算で行う
  // （高精度ポリラインでは値が 2^31 を超えるため）。
  const readVarint = (): number => {
    let result = 0;
    let multiplier = 1;
    let value: number;

    do {
      const charCode = encoded.charCodeAt(position++);
      value = DECODING[charCode];
      if (value === undefined) {
        throw new Error(`flexpolyline: invalid char '${encoded[position - 1]}'`);
      }
      result += (value & 0x1f) * multiplier;
      multiplier *= 32;
    } while (value >= 0x20);

    return result;
  };

  return {
    hasNext(): boolean {
      return position < encoded.length;
    },
    nextUnsigned(): number {
      return readVarint();
    },
    nextSigned(): number {
      const result = readVarint();

      // ZigZag decode（ビット演算を使わず 2^53 までの値を扱う）
      return result % 2 === 1 ? -(result + 1) / 2 : result / 2;
    },
  };
}

/** HERE flexible polyline 文字列を WGS84 座標列へデコードする。 */
export function decodeFlexiblePolyline(encoded: string): LatLng[] {
  if (!encoded) return [];

  const decoder = createDecoder(encoded);

  // header (version + content) は符号なし varint
  const version = decoder.nextUnsigned();
  if (version !== 1) {
    throw new Error(`flexpolyline: unsupported version ${version}`);
  }
  const headerContent = decoder.nextUnsigned();
  const precision = headerContent & 0x0f;
  const thirdDim = (headerContent >> 4) & 0x07;

  const latLngFactor = 10 ** precision;
  const coordinates: LatLng[] = [];

  let lat = 0;
  let lng = 0;

  while (decoder.hasNext()) {
    lat += decoder.nextSigned();
    lng += decoder.nextSigned();
    if (thirdDim) decoder.nextSigned(); // 3rd dimension は捨てる

    coordinates.push({ lat: lat / latLngFactor, lng: lng / latLngFactor });
  }

  return coordinates;
}
