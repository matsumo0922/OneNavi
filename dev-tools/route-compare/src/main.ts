/**
 * Entry point。サンプル選択 → guide-points 読み込み → Routes API chunked 呼び出し
 * → Map 上で外部 polyline と並べて描画する。
 */

import {fitBoundsTo, initMap, setCustomInputMarkers, setExternalPolyline, setGooglePolylines, setLayerVisibility, setMapClickHandler, setSentWaypointMarkers, setWaypointMarkers,} from "./map";
import {loadManeuverIndices, type ManeuverIndices} from "./maneuvers";
import {computeChunkBoundaryIndices, computeChunkedRoute, type WaypointInput,} from "./routes-api";
import {type GuidePoint, type LatLng, loadExternalPolyline, loadGuidePoints, SAMPLES,} from "./samples";

interface Elements {
  sampleSelect: HTMLSelectElement;
  maneuverSampling: HTMLSelectElement;
  betweenSampling: HTMLSelectElement;
  chunkSize: HTMLInputElement;
  fetchButton: HTMLButtonElement;
  toggleExternal: HTMLInputElement;
  toggleGoogle: HTMLInputElement;
  toggleWaypoints: HTMLInputElement;
  toggleSent: HTMLInputElement;
  toggleHeading: HTMLInputElement;
  toggleVia: HTMLInputElement;
  status: HTMLDivElement;
  metrics: HTMLDivElement;
  customPanel: HTMLDivElement;
  customPointList: HTMLUListElement;
  customUndoButton: HTMLButtonElement;
  customClearButton: HTMLButtonElement;
}

/** sample-select の特殊値: 「Custom (click on map)」モード。 */
const CUSTOM_SAMPLE_ID = "__custom__";

let elements: Elements;
let currentWaypoints: LatLng[] = [];
let currentExternal: LatLng[] = [];
/** sample ロード時に解決した case 案内地点 / 案内地点外 用 index 集合。 */
let currentManeuverIndices: ManeuverIndices | null = null;
/** 各 GP の出発地点からの累積距離 (m)。距離ベース sampling に使う。 */
let currentCumDistances: number[] = [];

/** Custom mode でユーザがクリックして追加した waypoint 列 (origin, ...via..., destination)。 */
let customInputs: LatLng[] = [];

async function main(): Promise<void> {
  const apiKey = import.meta.env.VITE_GOOGLE_API_KEY as string;
  if (!apiKey || apiKey === "your_google_api_key_here") {
    document.body.innerHTML = `
      <div style="display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;">
        <div style="text-align:center;">
          <h2>API Key Required</h2>
          <p>Create <code>.env</code> with <code>VITE_GOOGLE_API_KEY=your_key</code></p>
        </div>
      </div>
    `;
    return;
  }

  await loadGoogleMapsApi(apiKey);
  await initMap();

  elements = bindElements();
  populateSampleSelect();
  attachEventListeners(apiKey);
  await loadSelectedSample();
}

function bindElements(): Elements {
  return {
    sampleSelect: document.getElementById("sample-select") as HTMLSelectElement,
    maneuverSampling: document.getElementById("maneuver-sampling") as HTMLSelectElement,
    betweenSampling: document.getElementById("between-sampling") as HTMLSelectElement,
    chunkSize: document.getElementById("chunk-size") as HTMLInputElement,
    fetchButton: document.getElementById("btn-fetch") as HTMLButtonElement,
    toggleExternal: document.getElementById("toggle-external") as HTMLInputElement,
    toggleGoogle: document.getElementById("toggle-google") as HTMLInputElement,
    toggleWaypoints: document.getElementById("toggle-waypoints") as HTMLInputElement,
    toggleSent: document.getElementById("toggle-sent") as HTMLInputElement,
    toggleHeading: document.getElementById("toggle-heading") as HTMLInputElement,
    toggleVia: document.getElementById("toggle-via") as HTMLInputElement,
    status: document.getElementById("status") as HTMLDivElement,
    metrics: document.getElementById("metrics") as HTMLDivElement,
    customPanel: document.getElementById("custom-panel") as HTMLDivElement,
    customPointList: document.getElementById("custom-point-list") as HTMLUListElement,
    customUndoButton: document.getElementById("btn-custom-undo") as HTMLButtonElement,
    customClearButton: document.getElementById("btn-custom-clear") as HTMLButtonElement,
  };
}

function populateSampleSelect(): void {
  for (const sample of SAMPLES) {
    const option = document.createElement("option");
    option.value = sample.id;
    option.textContent = sample.label + (sample.hasExternalPolyline ? "" : "  [no polyline]");
    elements.sampleSelect.appendChild(option);
  }
  // sample 群の後に Custom mode を追加 (任意の地点を指定したい時に選ぶ)
  const customOption = document.createElement("option");
  customOption.value = CUSTOM_SAMPLE_ID;
  customOption.textContent = "Custom (click on map)";
  elements.sampleSelect.appendChild(customOption);
}

function isCustomMode(): boolean {
  return elements.sampleSelect.value === CUSTOM_SAMPLE_ID;
}

function attachEventListeners(apiKey: string): void {
  elements.sampleSelect.addEventListener("change", () => {
    void loadSelectedSample();
  });

  elements.fetchButton.addEventListener("click", () => {
    void fetchAndRender(apiKey);
  });

  elements.toggleExternal.addEventListener("change", () => {
    setLayerVisibility("external", elements.toggleExternal.checked);
  });
  elements.toggleGoogle.addEventListener("change", () => {
    setLayerVisibility("google", elements.toggleGoogle.checked);
  });
  elements.toggleWaypoints.addEventListener("change", () => {
    setLayerVisibility("waypoints", elements.toggleWaypoints.checked);
  });
  elements.toggleSent.addEventListener("change", () => {
    setLayerVisibility("sent", elements.toggleSent.checked);
  });

  // Custom mode 用の操作。click は map.ts で受けて handler 経由で本ファイルに来る。
  setMapClickHandler(onCustomMapClick);
  elements.customUndoButton.addEventListener("click", () => {
    if (!isCustomMode()) return;
    customInputs.pop();
    refreshCustomState();
  });
  elements.customClearButton.addEventListener("click", () => {
    if (!isCustomMode()) return;
    customInputs = [];
    refreshCustomState();
  });
}

/** Custom mode で地図をクリックしたときの hook。waypoint を末尾に追加する。 */
function onCustomMapClick(latLng: LatLng): void {
  if (!isCustomMode()) return;
  customInputs.push(latLng);
  refreshCustomState();
}

/** customInputs を反映: marker 再描画 + リスト更新 + status 更新。 */
function refreshCustomState(): void {
  setCustomInputMarkers(customInputs);
  renderCustomPointList();
  setStatus(buildCustomStatusText());
}

function buildCustomStatusText(): string {
  if (customInputs.length === 0) {
    return "Custom mode: 地図をクリックして waypoint を追加してください (最低 2 点)。";
  }
  if (customInputs.length === 1) {
    return "Custom mode: あと最低 1 点 (destination) が必要。";
  }
  return [
    `Custom mode: ${customInputs.length} 点設定済み`,
    `  origin → ${customInputs.length - 2} 経由地 → destination`,
    `Fetch & Render で reference + FPS chunked 比較を実行。`,
  ].join("\n");
}

function renderCustomPointList(): void {
  const list = elements.customPointList;
  list.replaceChildren();
  for (let index = 0; index < customInputs.length; index++) {
    const role =
      index === 0
        ? "origin"
        : index === customInputs.length - 1 && customInputs.length >= 2
          ? "dest  "
          : "via   ";
    const item = document.createElement("li");
    item.textContent = `${(index + 1).toString().padStart(2)}. [${role}] ${customInputs[index].lat.toFixed(5)}, ${customInputs[index].lng.toFixed(5)}`;
    list.appendChild(item);
  }
}

/** Custom mode に入る/出る時に共通でやる state リセット。 */
function resetRouteRenderState(): void {
  setExternalPolyline(null);
  setGooglePolylines([]);
  setWaypointMarkers([], false);
  setSentWaypointMarkers([], new Set(), false);
  currentExternal = [];
  currentWaypoints = [];
  currentCumDistances = [];
  currentManeuverIndices = null;
}

async function loadSelectedSample(): Promise<void> {
  const sampleId = elements.sampleSelect.value;

  // Custom mode は sample データを読まないので別経路で初期化する。
  if (sampleId === CUSTOM_SAMPLE_ID) {
    resetRouteRenderState();
    setCustomInputMarkers([]);
    customInputs = [];
    elements.customPanel.style.display = "";
    setMetrics("");
    refreshCustomState();
    return;
  }

  // sample mode: custom UI は隠して click handler は無効化のままでよい (isCustomMode 判定で守る)
  elements.customPanel.style.display = "none";
  setCustomInputMarkers([]);

  setStatus(`Loading ${sampleId}...`);
  setMetrics("");
  resetRouteRenderState();

  try {
    const [externalPath, guide] = await Promise.all([
      loadExternalPolyline(sampleId),
      loadGuidePoints(sampleId),
    ]);

    currentWaypoints = guide.points;
    currentExternal = externalPath ?? [];
    currentCumDistances = computeCumulativeDistances(guide.raw);
    currentManeuverIndices = await loadManeuverIndices(sampleId, guide.raw).catch(() => null);

    setExternalPolyline(currentExternal);
    setLayerVisibility("external", elements.toggleExternal.checked);

    setWaypointMarkers(currentWaypoints, elements.toggleWaypoints.checked);

    fitBoundsTo(currentExternal.length > 0 ? currentExternal : currentWaypoints);
    setStatus(
      [
        `Sample: ${sampleId}`,
        `External polyline vertices: ${currentExternal.length}`,
        `Guide points: ${currentWaypoints.length}`,
        currentManeuverIndices
          ? `Maneuvers: ${currentManeuverIndices.maneuver.length}, exclude-from-between: ${currentManeuverIndices.excludeFromBetween.length}`
          : `Maneuvers: n/a (guide.json not parsable)`,
      ].join("\n"),
    );
  } catch (error) {
    setStatus(`Failed to load sample: ${(error as Error).message}`);
  }
}

async function fetchAndRender(apiKey: string): Promise<void> {
  if (isCustomMode()) {
    await fetchAndRenderCustom(apiKey);
    return;
  }
  if (currentWaypoints.length < 2) {
    setStatus("Sample not loaded yet.");
    return;
  }

  const useHeading = elements.toggleHeading.checked;
  const useVia = elements.toggleVia.checked;
  const waypointsForApi = buildSentWaypoints(
    elements.maneuverSampling.value,
    elements.betweenSampling.value,
    useHeading,
  );
  const sampled: LatLng[] = waypointsForApi.map((wp) => ({ lat: wp.lat, lng: wp.lng }));
  const chunkSize = parseChunkSize(elements.chunkSize.value);
  const boundaries = computeChunkBoundaryIndices(sampled.length, chunkSize);

  // Routes API 呼び出し前に「実際に送る waypoint」を即時可視化する
  // (失敗しても何を送ろうとしたかが分かる)
  setSentWaypointMarkers(sampled, boundaries, elements.toggleSent.checked);

  elements.fetchButton.disabled = true;
  setStatus(
    `Calling Routes API: ${sampled.length} waypoints, chunk=${chunkSize}, heading=${useHeading}, via=${useVia}...`,
  );

  try {
    const chunks = await computeChunkedRoute(
      waypointsForApi,
      { chunkSize, useVia },
      apiKey,
    );
    setGooglePolylines(chunks.map((chunk) => ({ polyline: chunk.polyline, origin: chunk.origin })));
    setLayerVisibility("google", elements.toggleGoogle.checked);

    const totalGoogle = chunks.reduce((sum, chunk) => sum + chunk.distanceMeters, 0);
    const externalDistance = approximatePolylineDistance(currentExternal);

    setStatus(
      [
        `Chunks: ${chunks.length}`,
        `Sampled waypoints: ${sampled.length}`,
        chunks
          .map(
            (chunk) =>
              `  #${chunk.chunkIndex}: intermediates=${chunk.intermediateCount} dist=${(chunk.distanceMeters / 1000).toFixed(2)}km verts=${chunk.polyline.length}`,
          )
          .join("\n"),
      ].join("\n"),
    );

    setMetrics(
      [
        `Google total: ${(totalGoogle / 1000).toFixed(2)} km`,
        externalDistance > 0
          ? `External:     ${(externalDistance / 1000).toFixed(2)} km (haversine)`
          : `External:     n/a`,
        externalDistance > 0
          ? `Inflation:    ${(totalGoogle / externalDistance).toFixed(3)}x`
          : "",
      ]
        .filter(Boolean)
        .join("\n"),
    );
  } catch (error) {
    setStatus(`Routes API call failed:\n${(error as Error).message}`);
  } finally {
    elements.fetchButton.disabled = false;
  }
}

/**
 * Custom mode の Fetch 動作。2 段階で実行する:
 *
 *   Step 1: ユーザの clicked 点をそのまま Routes API に投げて reference polyline を取得
 *           (chunk 分割が必要なら chunked、ただし FPS は通さない)。これが「外部ルート」役。
 *   Step 2: reference polyline を currentExternal にセットし、既存の buildSentWaypoints
 *           (= polyline-based FPS + heading + chunk + via) を走らせて test polyline を取得。
 *
 * サンプルデータ無しで任意ルートに対しアルゴリズム妥当性を確認するための経路。
 */
async function fetchAndRenderCustom(apiKey: string): Promise<void> {
  if (customInputs.length < 2) {
    setStatus("Custom mode: 最低 2 点 (origin + destination) が必要。");
    return;
  }

  const useHeading = elements.toggleHeading.checked;
  const useVia = elements.toggleVia.checked;
  const chunkSize = parseChunkSize(elements.chunkSize.value);

  elements.fetchButton.disabled = true;

  try {
    // Step 1: reference polyline を取得 (FPS なし、ユーザ点そのまま)
    setStatus("Step 1/2: reference polyline を取得中...");
    const referenceWaypoints: WaypointInput[] = customInputs.map((point) => ({
      lat: point.lat,
      lng: point.lng,
    }));
    const referenceChunks = await computeChunkedRoute(
      referenceWaypoints,
      { chunkSize, useVia: false },
      apiKey,
    );
    const referencePolyline = mergeChunkPolylines(referenceChunks.map((chunk) => chunk.polyline));

    // 既存のサンプリング/描画ロジックが触る state を「Custom 由来の reference」で埋める
    currentExternal = referencePolyline;
    currentWaypoints = customInputs.slice();
    currentManeuverIndices = null;
    currentCumDistances = synthesizeCumDistances(referencePolyline, customInputs.length);

    setExternalPolyline(currentExternal);
    setLayerVisibility("external", elements.toggleExternal.checked);
    fitBoundsTo(currentExternal);

    // Step 2: FPS + chunked test
    setStatus("Step 2/2: FPS + chunked Routes API を実行中...");
    const waypointsForApi = buildSentWaypoints(
      elements.maneuverSampling.value,
      elements.betweenSampling.value,
      useHeading,
    );
    const sampled: LatLng[] = waypointsForApi.map((wp) => ({ lat: wp.lat, lng: wp.lng }));
    const boundaries = computeChunkBoundaryIndices(sampled.length, chunkSize);
    setSentWaypointMarkers(sampled, boundaries, elements.toggleSent.checked);

    const testChunks = await computeChunkedRoute(
      waypointsForApi,
      { chunkSize, useVia },
      apiKey,
    );
    setGooglePolylines(testChunks.map((chunk) => ({ polyline: chunk.polyline, origin: chunk.origin })));
    setLayerVisibility("google", elements.toggleGoogle.checked);

    const referenceTotal = referenceChunks.reduce((sum, chunk) => sum + chunk.distanceMeters, 0);
    const testTotal = testChunks.reduce((sum, chunk) => sum + chunk.distanceMeters, 0);
    const referenceLength = approximatePolylineDistance(referencePolyline);

    setStatus(
      [
        `Custom: ${customInputs.length} 入力点 (origin + ${customInputs.length - 2} via + dest)`,
        `Reference: ${referenceChunks.length} chunks, ${(referenceTotal / 1000).toFixed(2)}km`,
        `Test:      ${testChunks.length} chunks, ${(testTotal / 1000).toFixed(2)}km, sampled ${sampled.length} waypoints`,
        ...testChunks.map(
          (chunk) =>
            `  #${chunk.chunkIndex}: intermediates=${chunk.intermediateCount} dist=${(chunk.distanceMeters / 1000).toFixed(2)}km verts=${chunk.polyline.length}`,
        ),
      ].join("\n"),
    );
    setMetrics(
      [
        `Reference: ${(referenceTotal / 1000).toFixed(2)} km`,
        `Test:      ${(testTotal / 1000).toFixed(2)} km`,
        referenceLength > 0
          ? `Inflation: ${(testTotal / referenceLength).toFixed(3)}x (test / reference-haversine)`
          : "",
      ]
        .filter(Boolean)
        .join("\n"),
    );
  } catch (error) {
    setStatus(`Custom fetch failed:\n${(error as Error).message}`);
  } finally {
    elements.fetchButton.disabled = false;
  }
}

/**
 * chunked Routes API レスポンスの polyline 配列を 1 本に結合する。
 * 隣接 chunk は最後/最初の頂点が重複する (Routes API の chunk 設計上) ので、
 * 2 つ目以降は先頭 1 頂点を読み飛ばす。
 */
function mergeChunkPolylines(chunkPolylines: LatLng[][]): LatLng[] {
  const merged: LatLng[] = [];
  for (let chunkIndex = 0; chunkIndex < chunkPolylines.length; chunkIndex++) {
    const chunk = chunkPolylines[chunkIndex];
    const startVertex = chunkIndex === 0 ? 0 : 1;
    for (let vertex = startVertex; vertex < chunk.length; vertex++) {
      merged.push(chunk[vertex]);
    }
  }
  return merged;
}

/**
 * Custom mode 用に「ユーザ入力 GP に紐付く累積距離」配列を合成する。
 * buildSentWaypoints は origin (index 0, cumDist=0) と destination (index last,
 * cumDist=totalLength) の cumDist しか実質参照しないので、両端のみ正しく埋めて
 * 残りは 0 で良い (案内地点 mode は custom 中は使わないため)。
 */
function synthesizeCumDistances(referencePolyline: LatLng[], inputCount: number): number[] {
  const totalLength = approximatePolylineDistance(referencePolyline);
  const result = new Array<number>(inputCount).fill(0);
  if (inputCount > 0) result[inputCount - 1] = totalLength;
  return result;
}

/**
 * 案内地点 (GP-based maneuver) + 案内地点外 (polyline-based middle point) を
 * 統合して route 順に並べた WaypointInput 列を返す。
 *
 * - 案内地点 = GP index list から index-stride で間引き
 * - 案内地点外 = 外部 polyline 頂点から距離ベース固定間隔で間引き
 *   (NAVITIME GP データは交差点中心なので、つくば市内の「中継点」が GP に
 *    存在しない問題への対策。polyline は道路形状そのもので mid-segment 頂点が
 *    豊富にある)
 * - origin / destination は両 mode が "none" でも必ず含める
 * - heading は各 source の局所接線方向で計算
 */
function buildSentWaypoints(
  maneuverMode: string,
  betweenMode: string,
  useHeading: boolean,
): WaypointInput[] {
  type Tagged = { lat: number; lng: number; heading?: number; cumDist: number };
  const collected: Tagged[] = [];

  const lastIndex = currentWaypoints.length - 1;
  const totalLength = currentCumDistances[lastIndex] ?? 0;

  // origin
  collected.push({
    lat: currentWaypoints[0].lat,
    lng: currentWaypoints[0].lng,
    cumDist: 0,
    heading: useHeading ? computeHeadingAt(currentWaypoints, 0) : undefined,
  });

  // 案内地点 (GP-based)
  if (currentManeuverIndices) {
    const inner = currentManeuverIndices.maneuver.filter(
      (index) => index !== 0 && index !== lastIndex,
    );
    for (const index of pickByStride(inner, modeToStride(maneuverMode))) {
      collected.push({
        lat: currentWaypoints[index].lat,
        lng: currentWaypoints[index].lng,
        cumDist: currentCumDistances[index],
        heading: useHeading ? computeHeadingAt(currentWaypoints, index) : undefined,
      });
    }
  }

  // 案内地点外 (polyline-based)
  // polyline が無い sample (= 大半) では fallback として GP-based を使う。
  if (currentExternal.length >= 2) {
    for (const wp of samplePolylineBetween(
      modeToStride(betweenMode),
      collected.map((c) => c.cumDist),
      useHeading,
    )) {
      collected.push(wp);
    }
  } else if (currentManeuverIndices) {
    // GP-based fallback (旧挙動)
    const excludeSet = new Set(currentManeuverIndices.excludeFromBetween);
    const betweenInner: number[] = [];
    for (let index = 1; index < lastIndex; index++) {
      if (excludeSet.has(index)) continue;
      if (excludeSet.has(index - 1) || excludeSet.has(index + 1)) continue;
      betweenInner.push(index);
    }
    for (const index of pickByDistance(
      betweenInner,
      currentCumDistances,
      modeToStride(betweenMode),
    )) {
      collected.push({
        lat: currentWaypoints[index].lat,
        lng: currentWaypoints[index].lng,
        cumDist: currentCumDistances[index],
        heading: useHeading ? computeHeadingAt(currentWaypoints, index) : undefined,
      });
    }
  }

  // destination
  collected.push({
    lat: currentWaypoints[lastIndex].lat,
    lng: currentWaypoints[lastIndex].lng,
    cumDist: totalLength,
    heading: useHeading ? computeHeadingAt(currentWaypoints, lastIndex) : undefined,
  });

  // 距離順に並べ重複を除く (同じ cumDist が来た場合は最初の 1 つを残す)
  collected.sort((a, b) => a.cumDist - b.cumDist);
  const deduped: Tagged[] = [];
  for (const wp of collected) {
    if (deduped.length === 0 || wp.cumDist - deduped[deduped.length - 1].cumDist > 1) {
      deduped.push(wp);
    }
  }
  return deduped.map((wp) => ({ lat: wp.lat, lng: wp.lng, heading: wp.heading }));
}

/**
 * 外部 polyline の頂点から「距離が等間隔になる」waypoint を farthest-point sampling で抽出する。
 *
 * stride を「km/pick」のスケールとして解釈し直す:
 *   All=1km 間隔 / Every 2nd=2km / Every 4th=4km / Every 8th=8km
 * (旧 GP-based の "Every Nth = 候補の 1/N" とは意味が変わるが、polyline は
 *  GP より圧倒的に頂点が多いので距離指定の方が直感的)
 */
function samplePolylineBetween(
  stride: number,
  alreadyPickedDistances: number[],
  useHeading: boolean,
): { lat: number; lng: number; cumDist: number; heading?: number }[] {
  if (stride <= 0 || currentExternal.length < 2) return [];

  // polyline 頂点ごとの累積距離 (haversine)
  const polyCum: number[] = [0];
  for (let pi = 1; pi < currentExternal.length; pi++) {
    polyCum.push(polyCum[pi - 1] + haversineMeters(currentExternal[pi - 1], currentExternal[pi]));
  }
  const totalLength = polyCum[polyCum.length - 1];
  if (totalLength <= 0) return [];

  const targetGap = stride * 1000;
  const desiredCount = Math.max(1, Math.ceil(totalLength / targetGap));

  // 候補は内側頂点のみ (端点は origin/dest と重複)
  const candidates: number[] = [];
  for (let pi = 1; pi < currentExternal.length - 1; pi++) candidates.push(pi);

  // 既選択 cumDist を seed に farthest-point
  const seeds: number[] = [...alreadyPickedDistances, totalLength];
  if (!seeds.includes(0)) seeds.push(0);
  const selected = new Set<number>();
  for (let pick = 0; pick < desiredCount; pick++) {
    let bestIdx = -1;
    let bestMin = -Infinity;
    for (const candidate of candidates) {
      if (selected.has(candidate)) continue;
      const dist = polyCum[candidate];
      let minDist = Infinity;
      for (const seed of seeds) {
        const diff = Math.abs(dist - seed);
        if (diff < minDist) minDist = diff;
      }
      if (minDist > bestMin) {
        bestMin = minDist;
        bestIdx = candidate;
      }
    }
    if (bestIdx < 0) break;
    // targetGap より近い seed しかなければ採用しない (既存 maneuver と被るのを防ぐ)
    if (bestMin < targetGap * 0.4) break;
    selected.add(bestIdx);
    seeds.push(polyCum[bestIdx]);
  }

  return Array.from(selected)
    .sort((a, b) => a - b)
    .map((pi) => {
      const v = currentExternal[pi];
      const prev = currentExternal[Math.max(0, pi - 1)];
      const next = currentExternal[Math.min(currentExternal.length - 1, pi + 1)];
      return {
        lat: v.lat,
        lng: v.lng,
        cumDist: polyCum[pi],
        heading: useHeading ? computeBearing(prev, next) : undefined,
      };
    });
}

function haversineMeters(a: LatLng, b: LatLng): number {
  const earthRadius = 6_371_000;
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const sinHalfLat = Math.sin(dLat / 2);
  const sinHalfLng = Math.sin(dLng / 2);
  const formula =
    sinHalfLat * sinHalfLat +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * sinHalfLng * sinHalfLng;
  return 2 * earthRadius * Math.asin(Math.sqrt(formula));
}

/**
 * 候補 GP index を route 上で「最も距離カバレッジが広がる」順に desiredCount 個まで採用する
 * (farthest-point sampling)。
 *
 * 単純な等間隔貪欲だと候補が cluster しがちな区間で「次の点が枯れる」現象が起き、
 * desired より大幅に少なくしか拾えないことがある。farthest-point なら原点と終点を
 * 既選択点として種にし、各 iteration で「既選択点群から最も遠い候補」を 1 つ拾うため、
 * desired_count を必ず満たしつつ最大ギャップから優先的に埋まる。
 */
function pickByDistance(
  candidateIndices: number[],
  cumDistance: number[],
  stride: number,
): number[] {
  if (stride <= 0 || candidateIndices.length === 0) return [];
  if (stride === 1) return candidateIndices.slice();

  const totalLength = cumDistance[cumDistance.length - 1] ?? 0;
  if (totalLength <= 0) return candidateIndices.slice();

  const desiredCount = Math.max(1, Math.ceil(candidateIndices.length / stride));

  // 原点 0 と終点 totalLength を seed として farthest-point sampling
  const selectedDistances: number[] = [0, totalLength];
  const selectedSet = new Set<number>();

  for (let pick = 0; pick < desiredCount; pick++) {
    let bestIndex = -1;
    let bestMinDist = -Infinity;
    for (const candidate of candidateIndices) {
      if (selectedSet.has(candidate)) continue;
      const dist = cumDistance[candidate];
      let minDist = Infinity;
      for (const seed of selectedDistances) {
        const diff = Math.abs(dist - seed);
        if (diff < minDist) minDist = diff;
      }
      if (minDist > bestMinDist) {
        bestMinDist = minDist;
        bestIndex = candidate;
      }
    }
    if (bestIndex < 0) break;
    selectedSet.add(bestIndex);
    selectedDistances.push(cumDistance[bestIndex]);
  }

  return Array.from(selectedSet).sort((a, b) => a - b);
}

function computeCumulativeDistances(rawPoints: GuidePoint[]): number[] {
  const result: number[] = [];
  let running = 0;
  for (const point of rawPoints) {
    running += point.cum_distance_m;
    result.push(running);
  }
  return result;
}

/**
 * GP[index] における進行方向 (compass bearing 0-360) を、
 * 隣接 GP (i-1 → i+1) のチョードから推定する。Routes API の
 * Waypoint.location.heading に渡し、高架/側道のような同位置の
 * 道路間で snap 先を絞るのに使う。
 */
function computeHeadingAt(allPoints: LatLng[], index: number): number {
  const prevIndex = Math.max(0, index - 1);
  const nextIndex = Math.min(allPoints.length - 1, index + 1);
  if (prevIndex === nextIndex) return 0;
  return computeBearing(allPoints[prevIndex], allPoints[nextIndex]);
}

function computeBearing(from: LatLng, to: LatLng): number {
  const lat1 = (from.lat * Math.PI) / 180;
  const lat2 = (to.lat * Math.PI) / 180;
  const dLng = ((to.lng - from.lng) * Math.PI) / 180;
  const y = Math.sin(dLng) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
  const bearingRad = Math.atan2(y, x);
  const bearingDeg = (bearingRad * 180) / Math.PI;
  return (bearingDeg + 360) % 360;
}

/** "none" → 0 (=採用しない) / "all" → 1 / "everyN" → N */
function modeToStride(mode: string): number {
  switch (mode) {
    case "none":
      return 0;
    case "all":
      return 1;
    case "every2":
      return 2;
    case "every4":
      return 4;
    case "every8":
      return 8;
    default:
      return 0;
  }
}

function pickByStride(indices: number[], stride: number): number[] {
  if (stride <= 0) return [];
  if (stride === 1) return indices.slice();
  const result: number[] = [];
  for (let pos = 0; pos < indices.length; pos += stride) {
    result.push(indices[pos]);
  }
  return result;
}

function parseChunkSize(value: string): number {
  const parsed = parseInt(value, 10);
  if (!Number.isFinite(parsed)) return 25;
  return Math.max(1, Math.min(25, parsed));
}

function approximatePolylineDistance(path: LatLng[]): number {
  if (path.length < 2) return 0;
  let total = 0;
  for (let index = 1; index < path.length; index++) {
    total += haversine(path[index - 1], path[index]);
  }
  return total;
}

function haversine(a: LatLng, b: LatLng): number {
  const earthRadius = 6_371_000;
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const sinHalfLat = Math.sin(dLat / 2);
  const sinHalfLng = Math.sin(dLng / 2);
  const formula =
    sinHalfLat * sinHalfLat +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * sinHalfLng * sinHalfLng;
  return 2 * earthRadius * Math.asin(Math.sqrt(formula));
}

function setStatus(text: string): void {
  elements.status.textContent = text;
}

function setMetrics(text: string): void {
  elements.metrics.textContent = text;
}

function loadGoogleMapsApi(apiKey: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (window.google?.maps) {
      resolve();
      return;
    }

    const script = document.createElement("script");
    script.src = `https://maps.googleapis.com/maps/api/js?key=${apiKey}&libraries=geometry&v=weekly&loading=async&callback=__gmcb`;
    script.async = true;
    script.defer = true;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).__gmcb = () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      delete (window as any).__gmcb;
      resolve();
    };

    script.onerror = () => reject(new Error("Failed to load Google Maps API"));
    document.head.appendChild(script);
  });
}

main().catch(console.error);
