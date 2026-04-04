import type { LatLng } from "./geo-utils";

/** デフォルトの初期位置 (東京駅) */
const DEFAULT_CENTER = { lat: 35.6812, lng: 139.7671 };
const DEFAULT_ZOOM = 14;

let map: google.maps.Map;
let currentMarker: google.maps.marker.AdvancedMarkerElement | null = null;
let routeRenderer: google.maps.DirectionsRenderer | null = null;
let directionsService: google.maps.DirectionsService;
let waypointMarkers: google.maps.marker.AdvancedMarkerElement[] = [];
let autocomplete: google.maps.places.Autocomplete | null = null;

/** 地図クリック時のコールバック */
let onMapClickCallback: ((latLng: LatLng) => void) | null = null;

/**
 * Google Maps を初期化する。
 */
export async function initMap(): Promise<void> {
  const { Map } = await google.maps.importLibrary("maps") as google.maps.MapsLibrary;
  await google.maps.importLibrary("marker");
  await google.maps.importLibrary("places");
  await google.maps.importLibrary("routes");

  map = new Map(document.getElementById("map")!, {
    center: DEFAULT_CENTER,
    zoom: DEFAULT_ZOOM,
    mapId: "fake-gps-map",
    gestureHandling: "greedy",
    disableDefaultUI: false,
    zoomControl: true,
    mapTypeControl: false,
    streetViewControl: false,
    fullscreenControl: false,
  });

  directionsService = new google.maps.DirectionsService();
  routeRenderer = new google.maps.DirectionsRenderer({
    map,
    suppressMarkers: false,
    polylineOptions: {
      strokeColor: "#4285f4",
      strokeWeight: 5,
      strokeOpacity: 0.8,
    },
  });

  map.addListener("click", (event: google.maps.MapMouseEvent) => {
    if (event.latLng && onMapClickCallback) {
      onMapClickCallback({ lat: event.latLng.lat(), lng: event.latLng.lng() });
    }
  });

  // Places Autocomplete
  const searchInput = document.getElementById("search-input") as HTMLInputElement;
  autocomplete = new google.maps.places.Autocomplete(searchInput, {
    fields: ["geometry", "name"],
  });
  autocomplete.bindTo("bounds", map);
  autocomplete.addListener("place_changed", () => {
    const place = autocomplete!.getPlace();
    if (place.geometry?.location && onMapClickCallback) {
      const loc = place.geometry.location;
      map.panTo(loc);
      map.setZoom(16);
      onMapClickCallback({ lat: loc.lat(), lng: loc.lng() });
    }
    searchInput.value = "";
  });
}

export function onMapClick(callback: (latLng: LatLng) => void): void {
  onMapClickCallback = callback;
}

/**
 * 現在位置マーカーを更新する。
 */
export function updateCurrentPosition(position: LatLng, bearing: number): void {
  if (!map) return;

  if (!currentMarker) {
    const el = document.createElement("div");
    el.className = "current-marker";
    el.innerHTML = `<svg width="24" height="24" viewBox="0 0 24 24">
      <circle cx="12" cy="12" r="10" fill="#4285f4" stroke="white" stroke-width="2"/>
      <polygon points="12,2 16,12 12,9 8,12" fill="white" opacity="0.9"/>
    </svg>`;

    currentMarker = new google.maps.marker.AdvancedMarkerElement({
      map,
      position,
      content: el,
      zIndex: 1000,
    });
  } else {
    currentMarker.position = position;
  }

  // bearing に合わせてマーカーを回転
  const el = currentMarker.content as HTMLElement;
  if (el) {
    el.style.transform = `rotate(${bearing}deg)`;
  }

  // カメラを追従
  map.panTo(position);
}

/**
 * ウェイポイントマーカーを表示する。
 */
export function updateWaypointMarkers(waypoints: LatLng[]): void {
  // 既存マーカーを削除
  for (const marker of waypointMarkers) {
    marker.map = null;
  }
  waypointMarkers = [];

  for (let index = 0; index < waypoints.length; index++) {
    const wp = waypoints[index];
    const label = index === 0 ? "S" : index === waypoints.length - 1 ? "E" : `${index}`;
    const color = index === 0 ? "#4caf50" : index === waypoints.length - 1 ? "#f44336" : "#ff9800";

    const el = document.createElement("div");
    el.style.cssText = `
      width: 28px; height: 28px; border-radius: 50%;
      background: ${color}; color: white; font-size: 12px; font-weight: bold;
      display: flex; align-items: center; justify-content: center;
      border: 2px solid white; box-shadow: 0 1px 4px rgba(0,0,0,0.3);
    `;
    el.textContent = label;

    const marker = new google.maps.marker.AdvancedMarkerElement({
      map,
      position: wp,
      content: el,
      gmpDraggable: true,
    });

    waypointMarkers.push(marker);
  }
}

/**
 * Google Directions API でルートを検索し、ポリライン座標を返す。
 */
export async function findRoute(waypoints: LatLng[]): Promise<LatLng[]> {
  if (waypoints.length < 2) return [];

  const origin = waypoints[0];
  const destination = waypoints[waypoints.length - 1];
  const vias = waypoints.slice(1, -1).map((wp) => ({
    location: new google.maps.LatLng(wp.lat, wp.lng),
    stopover: true,
  }));

  const result = await directionsService.route({
    origin: new google.maps.LatLng(origin.lat, origin.lng),
    destination: new google.maps.LatLng(destination.lat, destination.lng),
    waypoints: vias,
    travelMode: google.maps.TravelMode.DRIVING,
  });

  if (routeRenderer) {
    routeRenderer.setDirections(result);
  }

  // ルートの polyline 座標を抽出
  const path: LatLng[] = [];
  const legs = result.routes[0]?.legs ?? [];
  for (const leg of legs) {
    for (const step of leg.steps ?? []) {
      const decodedPath = step.path ?? [];
      for (const point of decodedPath) {
        path.push({ lat: point.lat(), lng: point.lng() });
      }
    }
  }

  return path;
}

/**
 * ルート表示をクリアする。
 */
export function clearRoute(): void {
  if (routeRenderer) {
    routeRenderer.setDirections({ routes: [] } as unknown as google.maps.DirectionsResult);
  }
}

/**
 * GPX パスを地図上にポリラインとして表示する。
 */
export function showGpxPath(path: LatLng[]): void {
  clearRoute();
  if (path.length === 0) return;

  new google.maps.Polyline({
    map,
    path: path.map((p) => ({ lat: p.lat, lng: p.lng })),
    strokeColor: "#9c27b0",
    strokeWeight: 4,
    strokeOpacity: 0.8,
  });

  const bounds = new google.maps.LatLngBounds();
  for (const point of path) {
    bounds.extend(point);
  }
  map.fitBounds(bounds);
}

export function getMap(): google.maps.Map {
  return map;
}
