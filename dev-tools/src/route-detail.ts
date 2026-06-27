import type { LatLng } from "./geo-utils";
import { distanceMeters } from "./geo-utils";

// HERE ルート検索結果の詳細表示パネル（画面下部ドック・タブ切替式）。
// summary / turn-by-turn / graph(speed) / snaps / tolls / response の 6 タブを持つ。
// HERE 専用（graph/tolls/snaps は HERE 固有データのため）。

/** タブ識別子。 */
type DetailTab = "summary" | "turns" | "graph" | "snaps" | "tolls" | "raw";

/** HERE レスポンスの最小型（詳細表示に使う分のみ）。 */
interface HerePlace {
  location?: LatLng;
  originalLocation?: LatLng;
}

interface HereWaypoint {
  time?: string;
  place?: HerePlace;
}

interface HereAction {
  action?: string;
  instruction?: string;
  length?: number;
  duration?: number;
  offset?: number;
  direction?: string;
}

interface HereDynamicSpeed {
  baseSpeed?: number;
  trafficSpeed?: number;
  turnTime?: number;
}

interface HereSpan {
  offset?: number;
  length?: number;
  duration?: number;
  baseDuration?: number;
  speedLimit?: number;
  maxSpeed?: number;
  dynamicSpeedInfo?: HereDynamicSpeed;
  names?: { value?: string; language?: string }[];
}

interface HerePrice {
  currency?: string;
  value?: number;
}

interface HereFare {
  name?: string;
  price?: HerePrice;
  convertedPrice?: HerePrice;
  reason?: string;
  paymentMethods?: string[];
}

interface HereToll {
  countryCode?: string;
  tollSystem?: string;
  fares?: HereFare[];
  tollCollectionLocations?: { location?: LatLng }[];
}

interface HereSummary {
  duration?: number;
  length?: number;
  baseDuration?: number;
}

interface HereSection {
  summary?: HereSummary;
  departure?: HereWaypoint;
  arrival?: HereWaypoint;
  actions?: HereAction[];
  spans?: HereSpan[];
  tolls?: HereToll[];
  transport?: { mode?: string };
}

interface HereResponse {
  routes?: { sections?: HereSection[] }[];
}

/** 1 つの speed graph 用サンプル点。 */
interface SpeedSample {
  /** 区間始点の累積距離 (m)。 */
  distance: number;
  /** 区間長 (m)。 */
  length: number;
  /** 制限速度 (km/h)。null は不明。 */
  speedLimit: number | null;
  /** 規制なし基準速度 (km/h)。 */
  baseSpeed: number | null;
  /** 交通考慮速度 (km/h)。 */
  trafficSpeed: number | null;
}

const SPEED_COLORS = {
  limit: "#9aa0aa",
  base: "#4285f4",
  traffic: "#00afaa",
} as const;

/** body 高さ永続化キー。 */
const HEIGHT_STORAGE_KEY = "onenavi-dev-detail-height";

/** body 高さの既定値 (px)。 */
const DEFAULT_BODY_HEIGHT = 260;

/** body 高さの下限 (px)。 */
const MIN_BODY_HEIGHT = 140;

let panel: HTMLElement | null = null;
let body: HTMLElement | null = null;
let activeTab: DetailTab = "summary";
let lastSections: HereSection[] = [];
let lastRaw: unknown = null;
let bodyHeight = restoreBodyHeight();

/** 詳細パネルを初期化する（タブ・折りたたみ・高さ調節のバインド）。 */
export function initRouteDetail(): void {
  panel = document.getElementById("route-detail");
  body = document.getElementById("detail-body");
  if (!panel || !body) return;

  bindTabs();
  bindCollapse();
  bindHeightResize();
  bindWindowResize();
  applyBodyHeight();
}

/** HERE レスポンスを解析し、詳細パネルを表示する。 */
export function showRouteDetail(raw: unknown): void {
  if (!panel) return;

  lastRaw = raw;
  lastSections = collectSections(raw);
  panel.classList.remove("hidden", "collapsed");
  renderActiveTab();
}

/** 詳細パネルを隠す。 */
export function hideRouteDetail(): void {
  panel?.classList.add("hidden");
}

function bindTabs(): void {
  const tabs = panel!.querySelectorAll<HTMLButtonElement>(".detail-tab");
  for (const tab of tabs) {
    tab.addEventListener("click", () => {
      activeTab = tab.dataset.tab as DetailTab;
      for (const other of tabs) {
        other.classList.toggle("active", other === tab);
      }
      panel!.classList.remove("collapsed");
      renderActiveTab();
    });
  }
}

function bindCollapse(): void {
  document.getElementById("detail-collapse")?.addEventListener("click", () => {
    panel!.classList.toggle("collapsed");
  });
}

function bindWindowResize(): void {
  window.addEventListener("resize", () => {
    if (panel?.classList.contains("hidden")) return;

    // ビューポート縮小時に高さを収め直し、graph はサイズ追従で再描画
    applyBodyHeight();
    if (activeTab === "graph") renderActiveTab();
  });
}

/** 上端ハンドルのドラッグで body 高さを調節する。 */
function bindHeightResize(): void {
  const handle = document.getElementById("detail-resize");
  if (!handle || !body) return;

  handle.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);

    const startY = event.clientY;
    const startHeight = body!.clientHeight;

    const onMove = (moveEvent: PointerEvent): void => {
      // ハンドルは上端なので、上方向ドラッグ（Y 減少）で高くする
      bodyHeight = startHeight + (startY - moveEvent.clientY);
      applyBodyHeight();
      if (activeTab === "graph") renderActiveTab();
    };

    const onUp = (): void => {
      handle.releasePointerCapture(event.pointerId);
      handle.removeEventListener("pointermove", onMove);
      handle.removeEventListener("pointerup", onUp);
      persistBodyHeight();
    };

    handle.addEventListener("pointermove", onMove);
    handle.addEventListener("pointerup", onUp);
  });
}

function applyBodyHeight(): void {
  if (body) body.style.height = `${clampBodyHeight(bodyHeight)}px`;
}

function clampBodyHeight(height: number): number {
  const max = Math.max(MIN_BODY_HEIGHT, window.innerHeight - 120);
  return Math.min(max, Math.max(MIN_BODY_HEIGHT, Math.round(height)));
}

function persistBodyHeight(): void {
  bodyHeight = clampBodyHeight(bodyHeight);
  try {
    localStorage.setItem(HEIGHT_STORAGE_KEY, String(bodyHeight));
  } catch {
    // localStorage が使えない環境は無視
  }
}

function restoreBodyHeight(): number {
  try {
    const stored = Number(localStorage.getItem(HEIGHT_STORAGE_KEY));
    return Number.isFinite(stored) && stored > 0 ? stored : DEFAULT_BODY_HEIGHT;
  } catch {
    return DEFAULT_BODY_HEIGHT;
  }
}

function collectSections(raw: unknown): HereSection[] {
  const response = raw as HereResponse | null;
  return response?.routes?.[0]?.sections ?? [];
}

function renderActiveTab(): void {
  if (!body) return;
  body.innerHTML = "";

  if (lastSections.length === 0) {
    body.appendChild(emptyNote("ルートデータがありません"));
    return;
  }

  const renderers: Record<DetailTab, (host: HTMLElement) => void> = {
    summary: renderSummary,
    turns: renderTurns,
    graph: renderGraph,
    snaps: renderSnaps,
    tolls: renderTolls,
    raw: renderRaw,
  };

  renderers[activeTab](body);
}

// ── Summary ──────────────────────────────────

function renderSummary(host: HTMLElement): void {
  const totals = sumSummaries(lastSections);
  const delay = totals.duration - totals.baseDuration;
  const avgKmh = totals.duration > 0 ? (totals.length / totals.duration) * 3.6 : 0;
  const transport = lastSections[0]?.transport?.mode ?? "-";
  const actionCount = lastSections.reduce((sum, section) => sum + (section.actions?.length ?? 0), 0);
  const tollTotal = sumTolls(lastSections);

  const rows: [string, string][] = [
    ["Distance", formatDistance(totals.length)],
    ["Duration (traffic)", formatDuration(totals.duration)],
    ["Base duration", formatDuration(totals.baseDuration)],
    ["Traffic delay", delay > 0 ? `+${formatDuration(delay)}` : "なし"],
    ["Average speed", `${avgKmh.toFixed(0)} km/h`],
    ["Transport", transport],
    ["Sections", String(lastSections.length)],
    ["Maneuvers", String(actionCount)],
    ["Toll total", tollTotal.value > 0 ? `${tollTotal.value} ${tollTotal.currency}` : "なし"],
  ];

  host.appendChild(keyValueGrid(rows));
}

// ── Turn-by-turn ─────────────────────────────

function renderTurns(host: HTMLElement): void {
  const actions = lastSections.flatMap((section) => section.actions ?? []);
  if (actions.length === 0) {
    host.appendChild(emptyNote("turn-by-turn データがありません"));
    return;
  }

  const list = document.createElement("ol");
  list.className = "detail-turns";

  for (const action of actions) {
    list.appendChild(turnItem(action));
  }

  host.appendChild(list);
}

function turnItem(action: HereAction): HTMLElement {
  const item = document.createElement("li");
  item.className = "turn-item";

  const glyph = document.createElement("span");
  glyph.className = "turn-glyph";
  glyph.textContent = directionGlyph(action);
  item.appendChild(glyph);

  const main = document.createElement("div");
  main.className = "turn-main";

  const instruction = document.createElement("div");
  instruction.className = "turn-instruction";
  instruction.textContent = action.instruction ?? action.action ?? "-";
  main.appendChild(instruction);

  const meta = document.createElement("div");
  meta.className = "turn-meta";
  meta.textContent = `${formatDistance(action.length ?? 0)} ・ ${formatDuration(action.duration ?? 0)}`;
  main.appendChild(meta);

  item.appendChild(main);
  return item;
}

function directionGlyph(action: HereAction): string {
  if (action.action === "depart") return "◉";
  if (action.action === "arrive") return "⚑";

  const direction = action.direction ?? "";
  if (direction.includes("left")) return "↰";
  if (direction.includes("right")) return "↱";

  return "↑";
}

// ── Graph (speed) ────────────────────────────

function renderGraph(host: HTMLElement): void {
  const samples = collectSpeedSamples(lastSections);
  if (samples.length === 0) {
    host.appendChild(emptyNote("speed データがありません（spans 未取得）"));
    return;
  }

  host.appendChild(speedLegend());

  const canvas = document.createElement("canvas");
  canvas.className = "speed-chart";
  host.appendChild(canvas);

  // レイアウト確定後の実寸でクリスプに描画する
  requestAnimationFrame(() => drawSpeedChart(canvas, samples));
}

function speedLegend(): HTMLElement {
  const legend = document.createElement("div");
  legend.className = "speed-legend";

  const entries: [string, string][] = [
    [SPEED_COLORS.limit, "Speed limit"],
    [SPEED_COLORS.base, "Base speed"],
    [SPEED_COLORS.traffic, "Traffic speed"],
  ];

  for (const [color, label] of entries) {
    const item = document.createElement("span");
    item.className = "speed-legend-item";

    const swatch = document.createElement("i");
    swatch.style.background = color;
    item.appendChild(swatch);
    item.appendChild(document.createTextNode(label));

    legend.appendChild(item);
  }

  return legend;
}

function collectSpeedSamples(sections: HereSection[]): SpeedSample[] {
  const samples: SpeedSample[] = [];
  let cumulative = 0;

  for (const section of sections) {
    for (const span of section.spans ?? []) {
      const length = span.length ?? 0;
      samples.push({
        distance: cumulative,
        length,
        speedLimit: toKmh(span.speedLimit),
        baseSpeed: toKmh(span.dynamicSpeedInfo?.baseSpeed),
        trafficSpeed: toKmh(span.dynamicSpeedInfo?.trafficSpeed),
      });
      cumulative += length;
    }
  }

  return samples;
}

function drawSpeedChart(canvas: HTMLCanvasElement, samples: SpeedSample[]): void {
  const ratio = window.devicePixelRatio || 1;
  const cssWidth = canvas.clientWidth || 600;
  const cssHeight = 200;
  canvas.width = cssWidth * ratio;
  canvas.height = cssHeight * ratio;

  const context = canvas.getContext("2d");
  if (!context) return;
  context.scale(ratio, ratio);

  const padding = { top: 10, right: 12, bottom: 22, left: 34 };
  const plotWidth = cssWidth - padding.left - padding.right;
  const plotHeight = cssHeight - padding.top - padding.bottom;

  const totalDistance = samples[samples.length - 1].distance + samples[samples.length - 1].length;
  const maxSpeed = chartMaxSpeed(samples);

  const xAt = (distance: number): number => padding.left + (distance / totalDistance) * plotWidth;
  const yAt = (speed: number): number => padding.top + plotHeight - (speed / maxSpeed) * plotHeight;

  drawChartGrid(context, { padding, plotWidth, plotHeight }, totalDistance, maxSpeed, xAt, yAt);

  drawSpeedSeries(context, samples, (sample) => sample.speedLimit, SPEED_COLORS.limit, xAt, yAt, true);
  drawSpeedSeries(context, samples, (sample) => sample.baseSpeed, SPEED_COLORS.base, xAt, yAt, false);
  drawSpeedSeries(context, samples, (sample) => sample.trafficSpeed, SPEED_COLORS.traffic, xAt, yAt, false);
}

function chartMaxSpeed(samples: SpeedSample[]): number {
  let max = 0;
  for (const sample of samples) {
    max = Math.max(max, sample.speedLimit ?? 0, sample.baseSpeed ?? 0, sample.trafficSpeed ?? 0);
  }
  return Math.max(20, Math.ceil(max / 20) * 20);
}

interface ChartLayout {
  padding: { top: number; right: number; bottom: number; left: number };
  plotWidth: number;
  plotHeight: number;
}

function drawChartGrid(
  context: CanvasRenderingContext2D,
  layout: ChartLayout,
  totalDistance: number,
  maxSpeed: number,
  xAt: (distance: number) => number,
  yAt: (speed: number) => number,
): void {
  const { padding, plotWidth, plotHeight } = layout;

  context.font = "10px -apple-system, sans-serif";
  context.fillStyle = "#8a909a";
  context.strokeStyle = "#e3e6ea";
  context.lineWidth = 1;

  const speedStep = maxSpeed / 4;
  for (let speed = 0; speed <= maxSpeed; speed += speedStep) {
    const y = yAt(speed);
    context.beginPath();
    context.moveTo(padding.left, y);
    context.lineTo(padding.left + plotWidth, y);
    context.stroke();
    context.textAlign = "right";
    context.fillText(String(Math.round(speed)), padding.left - 5, y + 3);
  }

  const distanceKm = totalDistance / 1000;
  const kmStep = niceStep(distanceKm / 5);
  context.textAlign = "center";
  for (let km = 0; km <= distanceKm; km += kmStep) {
    const x = xAt(km * 1000);
    context.fillText(`${km}`, x, padding.top + plotHeight + 14);
  }
}

function drawSpeedSeries(
  context: CanvasRenderingContext2D,
  samples: SpeedSample[],
  pick: (sample: SpeedSample) => number | null,
  color: string,
  xAt: (distance: number) => number,
  yAt: (speed: number) => number,
  dashed: boolean,
): void {
  context.strokeStyle = color;
  context.lineWidth = 1.5;
  context.setLineDash(dashed ? [4, 3] : []);
  context.beginPath();

  let started = false;
  for (const sample of samples) {
    const speed = pick(sample);
    if (speed === null) {
      started = false;
      continue;
    }

    // 区間は始点と終点で水平に保ち、ステップ状に描く
    const xStart = xAt(sample.distance);
    const xEnd = xAt(sample.distance + sample.length);
    const y = yAt(speed);

    if (!started) {
      context.moveTo(xStart, y);
      started = true;
    } else {
      context.lineTo(xStart, y);
    }
    context.lineTo(xEnd, y);
  }

  context.stroke();
  context.setLineDash([]);
}

// ── Snaps ────────────────────────────────────

function renderSnaps(host: HTMLElement): void {
  const places = collectSnapPlaces(lastSections);
  if (places.length === 0) {
    host.appendChild(emptyNote("snap データがありません"));
    return;
  }

  const list = document.createElement("div");
  list.className = "detail-snaps";

  for (const entry of places) {
    list.appendChild(snapItem(entry.label, entry.place));
  }

  host.appendChild(list);
}

function collectSnapPlaces(sections: HereSection[]): { label: string; place: HerePlace }[] {
  const entries: { label: string; place: HerePlace }[] = [];

  const firstDeparture = sections[0]?.departure?.place;
  if (firstDeparture) entries.push({ label: "Origin", place: firstDeparture });

  for (let index = 0; index < sections.length; index++) {
    const arrival = sections[index]?.arrival?.place;
    if (!arrival) continue;

    const isLast = index === sections.length - 1;
    entries.push({ label: isLast ? "Destination" : `Via ${index + 1}`, place: arrival });
  }

  return entries;
}

function snapItem(label: string, place: HerePlace): HTMLElement {
  const item = document.createElement("div");
  item.className = "snap-item";

  const title = document.createElement("div");
  title.className = "snap-title";
  title.textContent = label;
  item.appendChild(title);

  const snapped = place.location;
  const original = place.originalLocation;
  const moved = snapped && original ? distanceMeters(original, snapped) : null;

  const rows: [string, string][] = [
    ["requested", original ? formatLatLng(original) : "-"],
    ["snapped", snapped ? formatLatLng(snapped) : "-"],
    ["moved", moved !== null ? `${moved.toFixed(0)} m` : "-"],
  ];
  item.appendChild(keyValueGrid(rows));

  return item;
}

// ── Tolls ────────────────────────────────────

function renderTolls(host: HTMLElement): void {
  const tolls = lastSections.flatMap((section) => section.tolls ?? []);
  if (tolls.length === 0) {
    host.appendChild(emptyNote("料金所はありません（無料ルート）"));
    return;
  }

  const total = sumTolls(lastSections);
  const totalNote = document.createElement("div");
  totalNote.className = "toll-total";
  totalNote.textContent = `合計 ${total.value} ${total.currency}`;
  host.appendChild(totalNote);

  const list = document.createElement("div");
  list.className = "detail-tolls";
  for (const toll of tolls) {
    list.appendChild(tollItem(toll));
  }
  host.appendChild(list);
}

function tollItem(toll: HereToll): HTMLElement {
  const item = document.createElement("div");
  item.className = "toll-item";

  const head = document.createElement("div");
  head.className = "toll-head";
  head.textContent = `${toll.tollSystem ?? "Toll"} (${toll.countryCode ?? "-"})`;
  item.appendChild(head);

  for (const fare of toll.fares ?? []) {
    const row = document.createElement("div");
    row.className = "toll-fare";

    const name = document.createElement("span");
    name.textContent = fare.name ?? fare.reason ?? "fare";

    const price = document.createElement("span");
    price.className = "toll-price";
    price.textContent = fare.price
      ? `${fare.price.value ?? 0} ${fare.price.currency ?? ""}`
      : "-";

    row.append(name, price);
    item.appendChild(row);

    if (fare.paymentMethods?.length) {
      const methods = document.createElement("div");
      methods.className = "toll-methods";
      methods.textContent = fare.paymentMethods.join(" / ");
      item.appendChild(methods);
    }
  }

  return item;
}

// ── Response (raw JSON) ──────────────────────

function renderRaw(host: HTMLElement): void {
  const pre = document.createElement("pre");
  pre.className = "detail-raw";
  pre.textContent = JSON.stringify(lastRaw, null, 2);
  host.appendChild(pre);
}

// ── 共通ヘルパー ─────────────────────────────

function sumSummaries(sections: HereSection[]): Required<HereSummary> {
  return sections.reduce(
    (totals, section) => {
      const summary = section.summary ?? {};
      totals.length += summary.length ?? 0;
      totals.duration += summary.duration ?? 0;
      totals.baseDuration += summary.baseDuration ?? 0;
      return totals;
    },
    { length: 0, duration: 0, baseDuration: 0 },
  );
}

function sumTolls(sections: HereSection[]): { value: number; currency: string } {
  let value = 0;
  let currency = "JPY";

  for (const section of sections) {
    for (const toll of section.tolls ?? []) {
      for (const fare of toll.fares ?? []) {
        value += fare.price?.value ?? 0;
        if (fare.price?.currency) currency = fare.price.currency;
      }
    }
  }

  return { value, currency };
}

function keyValueGrid(rows: [string, string][]): HTMLElement {
  const grid = document.createElement("dl");
  grid.className = "detail-kv";

  for (const [key, value] of rows) {
    const term = document.createElement("dt");
    term.textContent = key;
    const desc = document.createElement("dd");
    desc.textContent = value;
    grid.append(term, desc);
  }

  return grid;
}

function emptyNote(text: string): HTMLElement {
  const note = document.createElement("div");
  note.className = "detail-empty";
  note.textContent = text;
  return note;
}

function toKmh(speedMps: number | undefined): number | null {
  return typeof speedMps === "number" ? speedMps * 3.6 : null;
}

function formatDistance(meters: number): string {
  if (meters >= 1000) return `${(meters / 1000).toFixed(1)} km`;
  return `${Math.round(meters)} m`;
}

function formatDuration(seconds: number): string {
  const total = Math.round(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;

  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${secs}s`;
  return `${secs}s`;
}

function formatLatLng(point: LatLng): string {
  return `${point.lat.toFixed(6)}, ${point.lng.toFixed(6)}`;
}

function niceStep(rough: number): number {
  if (rough <= 1) return 1;
  if (rough <= 2) return 2;
  if (rough <= 5) return 5;
  return Math.ceil(rough / 10) * 10;
}
