/**
 * Map 描画ロジック。external polyline / Google Routes split polyline / waypoint
 * marker の 3 レイヤーを保持し、それぞれ独立に表示/非表示を切り替えられる。
 */

import type {LatLng} from "./samples";

const DEFAULT_CENTER = { lat: 35.6812, lng: 139.7671 };
const DEFAULT_ZOOM = 11;

let map: google.maps.Map;

let externalPolyline: google.maps.Polyline | null = null;

interface ChunkLine {
  polyline: google.maps.Polyline;
  marker: google.maps.marker.AdvancedMarkerElement;
}
let chunkLines: ChunkLine[] = [];

let waypointMarkers: google.maps.marker.AdvancedMarkerElement[] = [];
let sentWaypointMarkers: google.maps.marker.AdvancedMarkerElement[] = [];

export async function initMap(): Promise<void> {
  const { Map } = (await google.maps.importLibrary("maps")) as google.maps.MapsLibrary;
  await google.maps.importLibrary("marker");

  map = new Map(document.getElementById("map")!, {
    center: DEFAULT_CENTER,
    zoom: DEFAULT_ZOOM,
    mapId: "route-compare-map",
    gestureHandling: "greedy",
    mapTypeControl: false,
    streetViewControl: false,
    fullscreenControl: false,
  });
}

export function getMap(): google.maps.Map {
  return map;
}

export function setExternalPolyline(path: LatLng[] | null): void {
  externalPolyline?.setMap(null);
  externalPolyline = null;
  if (!path || path.length === 0) return;

  externalPolyline = new google.maps.Polyline({
    map,
    path,
    strokeColor: "#1e88e5",
    strokeWeight: 5,
    strokeOpacity: 0.85,
    zIndex: 1,
  });
}

/**
 * Routes API の chunk ごとに polyline と chunk 番号バッジを描く。
 * chunk ごとに微妙に色相をずらして連結が視認できるようにする。
 */
export function setGooglePolylines(chunks: { polyline: LatLng[]; origin: LatLng }[]): void {
  for (const line of chunkLines) {
    line.polyline.setMap(null);
    line.marker.map = null;
  }
  chunkLines = [];

  for (let chunkIndex = 0; chunkIndex < chunks.length; chunkIndex++) {
    const chunk = chunks[chunkIndex];
    const hue = 0 + (chunkIndex * 18) % 60; // 0 (red) ~ ややオレンジ寄りの範囲
    const color = `hsl(${hue}, 80%, 50%)`;

    const polyline = new google.maps.Polyline({
      map,
      path: chunk.polyline,
      strokeColor: color,
      strokeWeight: 4,
      strokeOpacity: 0.85,
      zIndex: 2,
    });

    const badge = document.createElement("div");
    badge.style.cssText = `
      background: ${color}; color: white; font-size: 11px; font-weight: 700;
      padding: 2px 6px; border-radius: 8px; border: 1.5px solid white;
      box-shadow: 0 1px 4px rgba(0,0,0,0.4);
    `;
    badge.textContent = `#${chunkIndex}`;

    const marker = new google.maps.marker.AdvancedMarkerElement({
      map,
      position: chunk.origin,
      content: badge,
      zIndex: 100 + chunkIndex,
    });

    chunkLines.push({ polyline, marker });
  }
}

export function setWaypointMarkers(waypoints: LatLng[], visible: boolean): void {
  for (const marker of waypointMarkers) {
    marker.map = null;
  }
  waypointMarkers = [];
  if (!visible) return;

  for (let index = 0; index < waypoints.length; index++) {
    const point = waypoints[index];
    const dot = document.createElement("div");
    dot.style.cssText = `
      width: 8px; height: 8px; border-radius: 50%;
      background: #fb8c00; border: 1px solid white;
    `;
    dot.title = `idx=${index}`;

    const marker = new google.maps.marker.AdvancedMarkerElement({
      map,
      position: point,
      content: dot,
      zIndex: 50,
    });
    waypointMarkers.push(marker);
  }
}

/**
 * Routes API に実際に送ったウェイポイントを順序番号付きで描く。
 *
 * - 0 = origin (緑)
 * - last = destination (赤)
 * - chunk 境界 (chunkBoundaryIndices) は太枠で強調
 * - その他 = 紫 (中間 stopover)
 */
export function setSentWaypointMarkers(
  waypoints: LatLng[],
  chunkBoundaryIndices: Set<number>,
  visible: boolean,
): void {
  for (const marker of sentWaypointMarkers) {
    marker.map = null;
  }
  sentWaypointMarkers = [];
  if (!visible) return;

  for (let index = 0; index < waypoints.length; index++) {
    const point = waypoints[index];
    const isStart = index === 0;
    const isEnd = index === waypoints.length - 1;
    const isBoundary = chunkBoundaryIndices.has(index);

    const color = isStart ? "#2e7d32" : isEnd ? "#c62828" : "#7b1fa2";
    const borderColor = isBoundary ? "#000" : "white";
    const borderWidth = isBoundary ? "2.5px" : "1.5px";

    const badge = document.createElement("div");
    badge.style.cssText = `
      background: ${color}; color: white; font-size: 10px; font-weight: 700;
      min-width: 22px; height: 22px; padding: 0 5px;
      border-radius: 11px; border: ${borderWidth} solid ${borderColor};
      display: flex; align-items: center; justify-content: center;
      box-shadow: 0 1px 3px rgba(0,0,0,0.4);
    `;
    badge.textContent = `${index}`;
    badge.title = `Sent waypoint ${index}${isStart ? " (origin)" : isEnd ? " (destination)" : ""}${isBoundary ? " [chunk boundary]" : ""}`;

    const marker = new google.maps.marker.AdvancedMarkerElement({
      map,
      position: point,
      content: badge,
      zIndex: 200 + index,
    });
    sentWaypointMarkers.push(marker);
  }
}

export function setLayerVisibility(
  layer: "external" | "google" | "waypoints" | "sent",
  visible: boolean,
): void {
  switch (layer) {
    case "external":
      externalPolyline?.setMap(visible ? map : null);
      break;
    case "google":
      for (const line of chunkLines) {
        line.polyline.setMap(visible ? map : null);
        line.marker.map = visible ? map : null;
      }
      break;
    case "waypoints":
      for (const marker of waypointMarkers) {
        marker.map = visible ? map : null;
      }
      break;
    case "sent":
      for (const marker of sentWaypointMarkers) {
        marker.map = visible ? map : null;
      }
      break;
  }
}

/** 現在描画されている全ポイントに収まるよう zoom する。 */
export function fitBoundsTo(points: LatLng[]): void {
  if (points.length === 0) return;
  const bounds = new google.maps.LatLngBounds();
  for (const point of points) {
    bounds.extend(point);
  }
  map.fitBounds(bounds, 40);
}
