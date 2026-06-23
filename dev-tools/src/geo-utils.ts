/** 地球の半径 (メートル) */
const EARTH_RADIUS = 6_371_000;

/** 度 → ラジアン */
export function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

/** ラジアン → 度 */
export function toDeg(rad: number): number {
  return (rad * 180) / Math.PI;
}

export interface LatLng {
  lat: number;
  lng: number;
}

/**
 * 2 点間の距離をメートルで返す (Haversine)。
 */
export function distanceMeters(from: LatLng, to: LatLng): number {
  const dLat = toRad(to.lat - from.lat);
  const dLng = toRad(to.lng - from.lng);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(from.lat)) * Math.cos(toRad(to.lat)) * Math.sin(dLng / 2) ** 2;
  return EARTH_RADIUS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * from → to の方位角 (bearing) を度 [0, 360) で返す。
 */
export function bearingDeg(from: LatLng, to: LatLng): number {
  const dLng = toRad(to.lng - from.lng);
  const y = Math.sin(dLng) * Math.cos(toRad(to.lat));
  const x =
    Math.cos(toRad(from.lat)) * Math.sin(toRad(to.lat)) -
    Math.sin(toRad(from.lat)) * Math.cos(toRad(to.lat)) * Math.cos(dLng);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

/**
 * 起点から指定の方位角・距離だけ移動した座標を返す。
 */
export function destinationPoint(origin: LatLng, bearingDegrees: number, distanceM: number): LatLng {
  const d = distanceM / EARTH_RADIUS;
  const brng = toRad(bearingDegrees);
  const lat1 = toRad(origin.lat);
  const lng1 = toRad(origin.lng);

  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brng),
  );
  const lng2 =
    lng1 +
    Math.atan2(Math.sin(brng) * Math.sin(d) * Math.cos(lat1), Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));

  return { lat: toDeg(lat2), lng: toDeg(lng2) };
}

/**
 * polyline 上を指定距離だけ進んだ地点の座標と、そこまでのセグメントインデックスを返す。
 */
export function interpolateAlongPath(
  path: LatLng[],
  distanceM: number,
): { point: LatLng; segmentIndex: number; bearing: number } | null {
  if (path.length < 2) return null;

  let remaining = distanceM;
  for (let index = 0; index < path.length - 1; index++) {
    const segLen = distanceMeters(path[index], path[index + 1]);
    if (remaining <= segLen) {
      const fraction = remaining / segLen;
      const lat = path[index].lat + (path[index + 1].lat - path[index].lat) * fraction;
      const lng = path[index].lng + (path[index + 1].lng - path[index].lng) * fraction;
      const bearing = bearingDeg(path[index], path[index + 1]);
      return { point: { lat, lng }, segmentIndex: index, bearing };
    }
    remaining -= segLen;
  }
  // 末端を超えた
  const last = path[path.length - 1];
  const bearing = bearingDeg(path[path.length - 2], last);
  return { point: last, segmentIndex: path.length - 2, bearing };
}

/**
 * polyline の総距離 (メートル) を返す。
 */
export function pathTotalDistance(path: LatLng[]): number {
  let total = 0;
  for (let index = 0; index < path.length - 1; index++) {
    total += distanceMeters(path[index], path[index + 1]);
  }
  return total;
}

/**
 * polyline 上で指定座標に最も近い点のインデックスと距離 (始点からの累積) を返す。
 */
export function nearestPointOnPath(
  path: LatLng[],
  point: LatLng,
): { index: number; distance: number } {
  let minDist = Infinity;
  let bestIndex = 0;

  for (let index = 0; index < path.length; index++) {
    const d = distanceMeters(point, path[index]);
    if (d < minDist) {
      minDist = d;
      bestIndex = index;
    }
  }

  let distance = 0;
  for (let index = 0; index < bestIndex; index++) {
    distance += distanceMeters(path[index], path[index + 1]);
  }

  return { index: bestIndex, distance };
}

/**
 * 0-360 の bearing を正規化する。
 */
export function normalizeBearing(deg: number): number {
  return ((deg % 360) + 360) % 360;
}
