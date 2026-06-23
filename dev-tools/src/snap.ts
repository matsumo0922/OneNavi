import type { LatLng } from "./geo-utils";

const GOOGLE_API_KEY = import.meta.env.VITE_GOOGLE_API_KEY as string;

/**
 * Google Roads API (snapToRoads) で座標を最寄りの道路にスナップする。
 * API が利用不可の場合は元の座標をそのまま返す。
 */
export async function snapToRoad(point: LatLng): Promise<LatLng> {
  try {
    const url = new URL("https://roads.googleapis.com/v1/snapToRoads");
    url.searchParams.set("path", `${point.lat},${point.lng}`);
    url.searchParams.set("key", GOOGLE_API_KEY);

    const response = await fetch(url.toString());
    if (!response.ok) return point;

    const data = (await response.json()) as SnapToRoadsResponse;
    const snapped = data.snappedPoints?.[0]?.location;
    if (!snapped) return point;

    return { lat: snapped.latitude, lng: snapped.longitude };
  } catch {
    return point;
  }
}

interface SnapToRoadsResponse {
  snappedPoints?: {
    location: {
      latitude: number;
      longitude: number;
    };
    originalIndex?: number;
    placeId?: string;
  }[];
}
