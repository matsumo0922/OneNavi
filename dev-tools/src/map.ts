import type { LatLng } from "./geo-utils";
import { activeProvider, type ProviderRouteResult } from "./providers";

/** デフォルトの初期位置 (東京駅) */
const DEFAULT_CENTER = { lat: 35.6812, lng: 139.7671 };
const DEFAULT_ZOOM = 14;

export interface RoutePointCandidate {
  name: string;
  address: string;
  position: LatLng;
}

let map: google.maps.Map;
let currentMarker: google.maps.marker.AdvancedMarkerElement | null = null;
let markerHeadingEl: HTMLElement | null = null;
let waypointMarkers: google.maps.marker.AdvancedMarkerElement[] = [];
let geocoder: google.maps.Geocoder;
let routePolyline: google.maps.Polyline | null = null;
let gpxPolyline: google.maps.Polyline | null = null;
let benchOverlays: google.maps.Polyline[] = [];
let benchMarkers: google.maps.marker.AdvancedMarkerElement[] = [];
let routePointInfoWindow: google.maps.InfoWindow | null = null;

/** 地点候補選択時のコールバック */
let onRoutePointSelectedCallback: ((candidate: RoutePointCandidate) => void) | null = null;

/**
 * Google Maps を初期化する。
 */
export async function initMap(): Promise<void> {
  const { Map } = (await google.maps.importLibrary("maps")) as google.maps.MapsLibrary;
  const { Geocoder } = (await google.maps.importLibrary("geocoding")) as google.maps.GeocodingLibrary;
  await google.maps.importLibrary("marker");
  await google.maps.importLibrary("places");

  map = new Map(document.getElementById("map")!, {
    center: DEFAULT_CENTER,
    zoom: DEFAULT_ZOOM,
    mapId: "dev-tools-map",
    gestureHandling: "greedy",
    disableDefaultUI: false,
    zoomControl: true,
    keyboardShortcuts: false,
    mapTypeControl: false,
    streetViewControl: false,
    fullscreenControl: false,
  });

  geocoder = new Geocoder();

  map.addListener("click", (event: google.maps.MapMouseEvent) => {
    void handleMapClick(event);
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
    await place.fetchFields({ fields: ["displayName", "formattedAddress", "location"] });

    if (place.location && onRoutePointSelectedCallback) {
      const candidate = createRoutePointCandidateFromPlace(placePrediction, place);
      map.panTo(place.location);
      map.setZoom(16);
      onRoutePointSelectedCallback(candidate);
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

export function onRoutePointSelected(callback: (candidate: RoutePointCandidate) => void): void {
  onRoutePointSelectedCallback = callback;
}

export function showRoutePointCallout(
  candidate: RoutePointCandidate,
  waypoints: LatLng[],
  onInserted: (insertIndex: number, candidate: RoutePointCandidate) => void,
): void {
  const content = createRoutePointCalloutContent(candidate, waypoints, onInserted);

  if (routePointInfoWindow === null) {
    routePointInfoWindow = new google.maps.InfoWindow({
      maxWidth: 320,
    });
  }

  routePointInfoWindow.setContent(content);
  routePointInfoWindow.setPosition(candidate.position);
  routePointInfoWindow.open({
    map,
    shouldFocus: false,
  });
}

export function hideRoutePointCallout(): void {
  routePointInfoWindow?.close();
}

async function handleMapClick(event: google.maps.MapMouseEvent): Promise<void> {
  if (!event.latLng || !onRoutePointSelectedCallback) return;

  const position = {
    lat: event.latLng.lat(),
    lng: event.latLng.lng(),
  };
  const candidate = await createRoutePointCandidateFromMapClick(position);

  onRoutePointSelectedCallback(candidate);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function createRoutePointCandidateFromPlace(placePrediction: any, place: any): RoutePointCandidate {
  const position = {
    lat: place.location.lat(),
    lng: place.location.lng(),
  };
  const name = getPlaceName(placePrediction, place);
  const address = place.formattedAddress ?? place.formatted_address ?? "住所なし";

  return {
    name,
    address,
    position,
  };
}

async function createRoutePointCandidateFromMapClick(position: LatLng): Promise<RoutePointCandidate> {
  const geocodeResult = await reverseGeocode(position);
  const address = geocodeResult?.formatted_address ?? "住所なし";
  const name = geocodeResult?.address_components[0]?.long_name ?? "選択地点";

  return {
    name,
    address,
    position,
  };
}

function reverseGeocode(position: LatLng): Promise<google.maps.GeocoderResult | null> {
  return new Promise((resolve) => {
    geocoder.geocode({ location: position }, (results, status) => {
      if (status !== google.maps.GeocoderStatus.OK) {
        resolve(null);
        return;
      }

      resolve(results?.[0] ?? null);
    });
  });
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function getPlaceName(placePrediction: any, place: any): string {
  const displayName = place.displayName?.text ?? place.displayName;
  if (typeof displayName === "string" && displayName.length > 0) return displayName;

  const predictionText = placePrediction.text?.toString();
  if (typeof predictionText === "string" && predictionText.length > 0) return predictionText;

  return "検索地点";
}

function createRoutePointCalloutContent(
  candidate: RoutePointCandidate,
  waypoints: LatLng[],
  onInserted: (insertIndex: number, candidate: RoutePointCandidate) => void,
): HTMLElement {
  const container = document.createElement("div");
  container.className = "route-callout";
  stopCalloutPropagation(container);

  const title = document.createElement("div");
  title.className = "route-callout-title";
  title.textContent = "Route point";

  const fields = document.createElement("div");
  fields.className = "route-callout-fields";
  fields.append(
    createCalloutField("Name", candidate.name),
    createCalloutField("Address", candidate.address),
    createCalloutField("Coordinates", formatCoordinates(candidate.position)),
  );

  const insertLabel = document.createElement("label");
  insertLabel.className = "route-callout-insert-label";
  insertLabel.textContent = "Insert into ROUTE";

  const select = document.createElement("select");
  select.className = "route-callout-select";
  for (const option of createInsertOptions(waypoints)) {
    select.appendChild(option);
  }
  select.value = `${waypoints.length}`;

  const addButton = document.createElement("button");
  addButton.className = "route-callout-add";
  addButton.type = "button";
  addButton.textContent = "Add to ROUTE";
  addButton.addEventListener("click", () => {
    onInserted(Number(select.value), candidate);
    hideRoutePointCallout();
  });

  container.append(title, fields, insertLabel, select, addButton);

  return container;
}

function stopCalloutPropagation(container: HTMLElement): void {
  const eventNames = ["click", "pointerdown", "keydown", "keyup"] as const;
  for (const eventName of eventNames) {
    container.addEventListener(eventName, (event) => {
      event.stopPropagation();
    });
  }
}

function createCalloutField(label: string, value: string): HTMLElement {
  const row = document.createElement("div");
  row.className = "route-callout-field";

  const labelElement = document.createElement("span");
  labelElement.className = "route-callout-field-label";
  labelElement.textContent = label;

  const valueElement = document.createElement("span");
  valueElement.className = "route-callout-field-value";
  valueElement.textContent = value;
  valueElement.title = value;

  const copyButton = document.createElement("button");
  copyButton.className = "route-callout-copy";
  copyButton.type = "button";
  copyButton.title = `Copy ${label}`;
  copyButton.ariaLabel = `Copy ${label}`;
  copyButton.textContent = "\u29c9";
  copyButton.addEventListener("click", () => {
    void copyCalloutText(value, copyButton);
  });

  row.append(labelElement, valueElement, copyButton);

  return row;
}

function createInsertOptions(waypoints: LatLng[]): HTMLOptionElement[] {
  const options: HTMLOptionElement[] = [];

  for (let insertIndex = 0; insertIndex <= waypoints.length; insertIndex++) {
    const option = document.createElement("option");
    option.value = `${insertIndex}`;
    option.textContent = getInsertOptionLabel(insertIndex, waypoints.length);
    options.push(option);
  }

  return options;
}

function getInsertOptionLabel(insertIndex: number, waypointCount: number): string {
  if (waypointCount === 0) return "Set as Start";
  if (insertIndex === 0) return "Before Start";
  if (insertIndex === waypointCount) return waypointCount === 1 ? "After Start" : "After End";

  const beforeLabel = getRoutePointLabel(insertIndex - 1, waypointCount);
  const afterLabel = getRoutePointLabel(insertIndex, waypointCount);

  return `Between ${beforeLabel} and ${afterLabel}`;
}

function getRoutePointLabel(index: number, waypointCount: number): string {
  const isFirstWaypoint = index === 0;
  if (isFirstWaypoint) return "Start";

  const isLastWaypoint = index === waypointCount - 1;
  const hasMultipleWaypoints = waypointCount > 1;
  if (isLastWaypoint && hasMultipleWaypoints) return "End";

  return `Via ${index}`;
}

function formatCoordinates(position: LatLng): string {
  return `${position.lat},${position.lng}`;
}

async function copyCalloutText(text: string, button: HTMLButtonElement): Promise<void> {
  const didCopy = await writeClipboardText(text);
  if (!didCopy) {
    window.alert("コピーに失敗しました");
    return;
  }

  showCalloutCopiedFeedback(button);
}

async function writeClipboardText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return writeClipboardTextWithFallback(text);
  }
}

function writeClipboardTextWithFallback(text: string): boolean {
  const textArea = document.createElement("textarea");
  textArea.value = text;
  textArea.readOnly = true;
  textArea.style.position = "fixed";
  textArea.style.top = "-1000px";

  document.body.appendChild(textArea);
  textArea.select();

  try {
    return document.execCommand("copy");
  } finally {
    document.body.removeChild(textArea);
  }
}

function showCalloutCopiedFeedback(button: HTMLButtonElement): void {
  const originalTitle = button.title;
  button.classList.add("copied");
  button.title = "Copied";

  window.setTimeout(() => {
    button.classList.remove("copied");
    button.title = originalTitle;
  }, 1200);
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
 * アクティブなプロバイダ (Google / HERE) でルートを探索し、結果を返す。
 * ルート線はプロバイダのアクセントカラーで描画する（色が現在の経路提供元を表す）。
 */
export async function findRoute(waypoints: LatLng[]): Promise<ProviderRouteResult> {
  const empty: ProviderRouteResult = {
    coords: [],
    distanceMeters: null,
    durationSeconds: null,
    raw: null,
  };
  if (waypoints.length < 2) return empty;

  const provider = activeProvider();
  const result = await provider.computeRoute(waypoints);

  drawRoutePolyline(result.coords, provider.accent);
  fitToPath(result.coords);

  return result;
}

/** ルート線を 1 本の polyline として描画する（既存があれば置換）。 */
function drawRoutePolyline(coords: LatLng[], color: string): void {
  routePolyline?.setMap(null);
  routePolyline = null;
  if (coords.length === 0) return;

  routePolyline = new google.maps.Polyline({
    map,
    path: coords,
    strokeColor: color,
    strokeWeight: 5,
    strokeOpacity: 0.85,
  });
}

function fitToPath(coords: LatLng[]): void {
  if (coords.length === 0) return;
  const bounds = new google.maps.LatLngBounds();
  for (const point of coords) {
    bounds.extend(point);
  }
  map.fitBounds(bounds);
}

/**
 * ルート表示をクリアする。
 */
export function clearRoute(): void {
  routePolyline?.setMap(null);
  routePolyline = null;
  clearGpxPolyline();
}

/** API bench から任意のジオメトリ／地点を地図に重ねる。 */
export function drawBenchGeometry(
  lines: LatLng[][],
  points: LatLng[],
  color: string,
): void {
  clearBenchOverlays();

  for (const line of lines) {
    if (line.length < 2) continue;
    benchOverlays.push(
      new google.maps.Polyline({
        map,
        path: line,
        strokeColor: color,
        strokeWeight: 4,
        strokeOpacity: 0.9,
      }),
    );
  }

  for (const point of points) {
    const dot = document.createElement("div");
    dot.className = "bench-dot";
    dot.style.background = color;
    benchMarkers.push(
      new google.maps.marker.AdvancedMarkerElement({
        map,
        position: point,
        content: dot,
      }),
    );
  }

  const all = [...lines.flat(), ...points];
  fitToPath(all);
}

/** API bench のオーバーレイを消す。 */
export function clearBenchOverlays(): void {
  for (const overlay of benchOverlays) overlay.setMap(null);
  benchOverlays = [];
  for (const marker of benchMarkers) marker.map = null;
  benchMarkers = [];
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
