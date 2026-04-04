import type { LatLng } from "./geo-utils";

/** デフォルトの初期位置 (東京駅) */
const DEFAULT_CENTER = { lat: 35.6812, lng: 139.7671 };
const DEFAULT_ZOOM = 14;

let map: google.maps.Map;
let currentMarker: google.maps.marker.AdvancedMarkerElement | null = null;
let waypointMarkers: google.maps.marker.AdvancedMarkerElement[] = [];
let routePolylines: google.maps.Polyline[] = [];
let gpxPolyline: google.maps.Polyline | null = null;

/** 地図クリック時のコールバック */
let onMapClickCallback: ((latLng: LatLng) => void) | null = null;

/**
 * Google Maps を初期化する。
 */
export async function initMap(): Promise<void> {
  const { Map } = (await google.maps.importLibrary("maps")) as google.maps.MapsLibrary;
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

  map.addListener("click", (event: google.maps.MapMouseEvent) => {
    if (event.latLng && onMapClickCallback) {
      onMapClickCallback({ lat: event.latLng.lat(), lng: event.latLng.lng() });
    }
  });

  // PlaceAutocompleteElement (新 API)
  initPlaceAutocomplete();
}

/**
 * PlaceAutocompleteElement を初期化する。
 * 旧 google.maps.places.Autocomplete の代替。
 */
function initPlaceAutocomplete(): void {
  const searchContainer = document.getElementById("panel-search")!;

  // 旧 input 要素を削除
  const oldInput = document.getElementById("search-input");
  if (oldInput) oldInput.remove();

  // PlaceAutocompleteElement を生成・挿入
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const PlaceAutocompleteElement = (google.maps.places as any).PlaceAutocompleteElement;
  const autocompleteEl = new PlaceAutocompleteElement({}) as HTMLElement;
  autocompleteEl.id = "search-input";
  autocompleteEl.style.width = "100%";
  searchContainer.appendChild(autocompleteEl);

  // 地図の bounds で結果をバイアス
  map.addListener("bounds_changed", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (autocompleteEl as any).locationBias = map.getBounds();
  });

  // 選択イベント
  autocompleteEl.addEventListener("gmp-select", async (event: Event) => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const { placePrediction } = event as any;
    if (!placePrediction) return;

    const place = placePrediction.toPlace();
    await place.fetchFields({ fields: ["location"] });

    if (place.location && onMapClickCallback) {
      map.panTo(place.location);
      map.setZoom(16);
      onMapClickCallback({
        lat: place.location.lat(),
        lng: place.location.lng(),
      });
    }
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
 * Routes API (computeRoutes) でルートを検索し、ポリライン座標を返す。
 * 旧 DirectionsService の代替。
 */
export async function findRoute(waypoints: LatLng[]): Promise<LatLng[]> {
  if (waypoints.length < 2) return [];

  const origin = waypoints[0];
  const destination = waypoints[waypoints.length - 1];
  const intermediates = waypoints.slice(1, -1).map((wp) => ({
    location: { lat: wp.lat, lng: wp.lng },
  }));

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const Route = (google.maps as any).routes.Route;
  const response = await Route.computeRoutes({
    origin: { lat: origin.lat, lng: origin.lng },
    destination: { lat: destination.lat, lng: destination.lng },
    intermediates,
    travelMode: "DRIVE",
  });

  const routes = response.routes;
  if (!routes || routes.length === 0) return [];

  const route = routes[0];

  // 旧ルートを削除して新ルートを描画
  clearRoutePolylines();
  const polylines = route.createPolylines({
    strokeColor: "#4285f4",
    strokeWeight: 5,
    strokeOpacity: 0.8,
  });
  for (const polyline of polylines) {
    polyline.setMap(map);
    routePolylines.push(polyline);
  }

  // ルートの座標を抽出
  const path: LatLng[] = [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  for (const point of route.path as any[]) {
    if (typeof point.lat === "function") {
      path.push({ lat: point.lat(), lng: point.lng() });
    } else {
      path.push({ lat: point.lat, lng: point.lng });
    }
  }

  // ルート全体が見えるようにカメラを調整
  if (path.length > 0) {
    const bounds = new google.maps.LatLngBounds();
    for (const point of path) {
      bounds.extend(point);
    }
    map.fitBounds(bounds);
  }

  return path;
}

/**
 * ルート表示をクリアする。
 */
export function clearRoute(): void {
  clearRoutePolylines();
  clearGpxPolyline();
}

function clearRoutePolylines(): void {
  for (const polyline of routePolylines) {
    polyline.setMap(null);
  }
  routePolylines = [];
}

function clearGpxPolyline(): void {
  if (gpxPolyline) {
    gpxPolyline.setMap(null);
    gpxPolyline = null;
  }
}

/**
 * GPX パスを地図上にポリラインとして表示する。
 */
export function showGpxPath(path: LatLng[]): void {
  clearRoute();
  if (path.length === 0) return;

  gpxPolyline = new google.maps.Polyline({
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
