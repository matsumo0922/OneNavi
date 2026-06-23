import type { LatLng } from "./geo-utils";

/** デフォルトの初期位置 (東京駅) */
const DEFAULT_CENTER = { lat: 35.6812, lng: 139.7671 };
const DEFAULT_ZOOM = 14;

let map: google.maps.Map;
let currentMarker: google.maps.marker.AdvancedMarkerElement | null = null;
let markerHeadingEl: HTMLElement | null = null;
let waypointMarkers: google.maps.marker.AdvancedMarkerElement[] = [];
let directionsService: google.maps.DirectionsService;
let directionsRenderer: google.maps.DirectionsRenderer;
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

  map = new Map(document.getElementById("map")!, {
    center: DEFAULT_CENTER,
    zoom: DEFAULT_ZOOM,
    mapId: "fake-gps-map",
    gestureHandling: "greedy",
    disableDefaultUI: false,
    zoomControl: true,
    keyboardShortcuts: false,
    mapTypeControl: false,
    streetViewControl: false,
    fullscreenControl: false,
  });

  directionsService = new google.maps.DirectionsService();
  directionsRenderer = new google.maps.DirectionsRenderer({
    map,
    suppressMarkers: true,
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

  // 初期位置に現在地マーカーを表示
  updateCurrentPosition(DEFAULT_CENTER, 0);

  initPlaceAutocomplete();
}

/**
 * PlaceAutocompleteElement を初期化する。
 */
function initPlaceAutocomplete(): void {
  const searchContainer = document.getElementById("panel-search")!;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const PlaceAutocompleteElement = (google.maps.places as any).PlaceAutocompleteElement;
  const autocompleteEl = new PlaceAutocompleteElement({}) as HTMLElement;
  autocompleteEl.id = "search-input";
  autocompleteEl.style.width = "100%";
  protectAutocompleteKeyboardInput(autocompleteEl);
  searchContainer.appendChild(autocompleteEl);

  map.addListener("bounds_changed", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (autocompleteEl as any).locationBias = map.getBounds();
  });

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

function protectAutocompleteKeyboardInput(autocompleteEl: HTMLElement): void {
  autocompleteEl.addEventListener("keydown", stopKeyboardEventPropagation);
  autocompleteEl.addEventListener("keyup", stopKeyboardEventPropagation);
}

function stopKeyboardEventPropagation(event: KeyboardEvent): void {
  event.stopPropagation();
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
    el.innerHTML = `<svg width="32" height="32" viewBox="0 0 32 32">
      <circle cx="16" cy="16" r="12" fill="#4285f4" stroke="white" stroke-width="3"/>
      <polygon points="16,2 21,14 16,10 11,14" fill="white" opacity="0.9"/>
    </svg>`;
    markerHeadingEl = el;

    currentMarker = new google.maps.marker.AdvancedMarkerElement({
      map,
      position,
      content: el,
      zIndex: 1000,
    });
  } else {
    currentMarker.position = position;
  }

  if (markerHeadingEl) {
    markerHeadingEl.style.transform = `rotate(${bearing}deg)`;
  }

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
 * DirectionsService でルートを検索し、ポリライン座標を返す。
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

  directionsRenderer.setDirections(result);

  // polyline 座標を抽出
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

  // ルート全体をフィット
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
  directionsRenderer?.setDirections({
    routes: [],
  } as unknown as google.maps.DirectionsResult);
  clearGpxPolyline();
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
