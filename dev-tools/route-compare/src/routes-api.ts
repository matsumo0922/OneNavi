/**
 * Google Routes API v2 (computeRoutes) を 25 中間 waypoint chunk で叩く。
 *
 * 1 chunk = 1 origin + 最大 25 stopover + 1 destination。
 * 隣接 chunk は前 chunk の末尾を次 chunk の origin として接続する (重複なし)。
 */

import {decodePolyline} from "./polyline";
import type {LatLng} from "./samples";

const ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes";
const FIELD_MASK = "routes.polyline.encodedPolyline,routes.distanceMeters,routes.duration";

/**
 * Routes API へ送る 1 waypoint。緯度経度に加えて任意で進行方向 (heading) を持つ。
 * heading は 0-359 の compass bearing で、高架/側道のような同位置・同方向の
 * 道路間で snap 先を絞るための補助情報。
 */
export interface WaypointInput {
  lat: number;
  lng: number;
  heading?: number;
}

export interface ChunkOptions {
  chunkSize: number;
  /** intermediates に via:true を立てて pass-through 扱いにする (legs を作らない)。 */
  useVia: boolean;
}

export interface ChunkResult {
  chunkIndex: number;
  origin: LatLng;
  destination: LatLng;
  intermediateCount: number;
  distanceMeters: number;
  polyline: LatLng[];
}

/**
 * 与えられた waypoints を chunkSize で分割した時の chunk 境界 index を返す。
 * 例: 60 点 / chunkSize=25 → stepSize=26、boundary = {0, 26, 52, 59}。
 * (各 chunk の origin index と最終 destination index の集合)
 */
export function computeChunkBoundaryIndices(totalCount: number, chunkSize: number): Set<number> {
  if (totalCount < 2) return new Set([0]);
  const intermediateMax = Math.max(1, Math.min(25, chunkSize));
  const stepSize = intermediateMax + 1;
  const boundaries = new Set<number>();
  for (let index = 0; index < totalCount - 1; index += stepSize) {
    boundaries.add(index);
  }
  boundaries.add(totalCount - 1);
  return boundaries;
}

/**
 * waypoints (>=2) を chunk 分割し、各 chunk を Routes API に投げてポリラインを集める。
 *
 * @param waypoints 全ウェイポイント (順序済み)。先頭=出発地、末尾=目的地。
 * @param options.chunkSize 1 chunk あたりの中間 waypoint 数 (1〜25)。
 * @param options.useVia    true なら intermediates を via 扱いにする。
 */
export async function computeChunkedRoute(
  waypoints: WaypointInput[],
  options: ChunkOptions,
  apiKey: string,
): Promise<ChunkResult[]> {
  if (waypoints.length < 2) {
    throw new Error("waypoints must contain at least 2 points (origin & destination)");
  }
  const intermediateMax = Math.max(1, Math.min(25, options.chunkSize));

  const chunks = sliceIntoChunks(waypoints, intermediateMax);
  const results: ChunkResult[] = [];

  for (let chunkIndex = 0; chunkIndex < chunks.length; chunkIndex++) {
    const chunk = chunks[chunkIndex];
    const result = await callComputeRoutes(chunk, options.useVia, apiKey);
    const origin = chunk[0];
    const destination = chunk[chunk.length - 1];
    results.push({
      chunkIndex,
      origin: { lat: origin.lat, lng: origin.lng },
      destination: { lat: destination.lat, lng: destination.lng },
      intermediateCount: chunk.length - 2,
      distanceMeters: result.distanceMeters,
      polyline: result.polyline,
    });
  }
  return results;
}

/**
 * waypoints を chunk に分割する。
 * 例: 60 点を chunkSize=25 で割ると、
 *   - chunk0: idx [0..26] (origin + 25 中間 + dest=idx26)
 *   - chunk1: idx [26..52]
 *   - chunk2: idx [52..59] (短い末尾 chunk)
 */
function sliceIntoChunks(waypoints: WaypointInput[], intermediateMax: number): WaypointInput[][] {
  const chunks: WaypointInput[][] = [];
  const stepSize = intermediateMax + 1; // origin -> next-origin 間で進む index 数

  let start = 0;
  while (start < waypoints.length - 1) {
    const end = Math.min(start + stepSize, waypoints.length - 1);
    chunks.push(waypoints.slice(start, end + 1));
    if (end === waypoints.length - 1) break;
    start = end;
  }
  return chunks;
}

async function callComputeRoutes(
  chunk: WaypointInput[],
  useVia: boolean,
  apiKey: string,
): Promise<{ distanceMeters: number; polyline: LatLng[] }> {
  const origin = chunk[0];
  const destination = chunk[chunk.length - 1];
  const intermediates = chunk.slice(1, -1).map((point) => toWaypoint(point, useVia));

  const body = {
    origin: toWaypoint(origin, false),
    destination: toWaypoint(destination, false),
    intermediates,
    travelMode: "DRIVE",
    routingPreference: "TRAFFIC_UNAWARE",
    polylineQuality: "HIGH_QUALITY",
  };

  const response = await fetch(ENDPOINT, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": apiKey,
      "X-Goog-FieldMask": FIELD_MASK,
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Routes API ${response.status}: ${text}`);
  }

  const json = (await response.json()) as {
    routes?: Array<{
      polyline?: { encodedPolyline?: string };
      distanceMeters?: number;
    }>;
  };

  const route = json.routes?.[0];
  const encoded = route?.polyline?.encodedPolyline;
  if (!encoded) {
    throw new Error(`Routes API response had no polyline: ${JSON.stringify(json)}`);
  }

  return {
    distanceMeters: route?.distanceMeters ?? 0,
    polyline: decodePolyline(encoded),
  };
}

interface ApiWaypoint {
  location: {
    latLng: { latitude: number; longitude: number };
    heading?: number;
  };
  via?: boolean;
}

function toWaypoint(point: WaypointInput, via: boolean): ApiWaypoint {
  const location: ApiWaypoint["location"] = {
    latLng: { latitude: point.lat, longitude: point.lng },
  };
  if (typeof point.heading === "number") {
    // Routes API は 0-359 の整数を受け付ける
    location.heading = Math.round(((point.heading % 360) + 360) % 360);
  }
  const result: ApiWaypoint = { location };
  if (via) result.via = true;
  return result;
}
