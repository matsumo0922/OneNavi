import { callHere, hasHereApiKey } from "./here";
import { decodeFlexiblePolyline } from "./providers/flexpolyline";
import { clearBenchOverlays, drawBenchGeometry } from "./map";
import type { LatLng } from "./geo-utils";

// HERE API プレイグラウンド。任意のエンドポイントを叩いて JSON を確認し、
// ジオメトリを地図へ重ねる。apikey は here.ts が自動付与する。

const BENCH_ACCENT = "#f4a020";

/** プリセット 1 件。url は apikey 抜きのテンプレ。 */
interface BenchPreset {
  label: string;
  url: string;
}

// サンプル座標は golden sample（石神井→筑波）と東京駅周辺。
const PRESETS: BenchPreset[] = [
  {
    label: "Routing v8 + spans",
    url:
      "https://router.hereapi.com/v8/routes?transportMode=car" +
      "&origin=35.7438,139.6060&destination=36.0820,140.1110" +
      "&return=polyline,summary,actions" +
      "&spans=names,speedLimit,dynamicSpeedInfo,functionalClass&lang=ja-JP",
  },
  {
    label: "Routing v8 tolls",
    url:
      "https://router.hereapi.com/v8/routes?transportMode=car" +
      "&origin=35.7438,139.6060&destination=36.0820,140.1110" +
      "&return=summary,tolls&currency=JPY&lang=ja-JP",
  },
  {
    label: "Browse SA/PA",
    url:
      "https://browse.search.hereapi.com/v1/browse?at=36.00,140.05" +
      "&categories=400-4300&limit=20&lang=ja-JP",
  },
  {
    label: "Discover",
    url:
      "https://discover.search.hereapi.com/v1/discover?at=35.6812,139.7671" +
      "&q=サービスエリア&limit=20&lang=ja-JP",
  },
  {
    label: "Reverse geocode",
    url: "https://revgeocode.search.hereapi.com/v1/revgeocode?at=35.6812,139.7671&lang=ja-JP",
  },
];

/** 直近レスポンス（plot 用に保持）。 */
let lastResponse: unknown = null;

/** API bench を初期化する。 */
export function initApiBench(): void {
  const panel = document.getElementById("api-bench");
  if (!panel) return;

  const presetsHost = document.getElementById("bench-presets")!;
  const urlInput = document.getElementById("bench-url") as HTMLTextAreaElement;
  const output = document.getElementById("bench-output")!;
  const status = document.getElementById("bench-status")!;

  renderPresets(presetsHost, urlInput);
  urlInput.value = PRESETS[0].url;

  if (!hasHereApiKey()) {
    status.innerHTML = `<span class="bench-pill warn">no key</span> VITE_HERE_API_KEY 未設定`;
  }

  document.getElementById("bench-toggle")!.addEventListener("click", () => {
    panel.classList.toggle("collapsed");
  });

  document.getElementById("bench-send")!.addEventListener("click", () => {
    void sendRequest(urlInput, output, status);
  });

  document.getElementById("bench-plot")!.addEventListener("click", () => {
    plotLastResponse(status);
  });

  document.getElementById("bench-clear-plot")!.addEventListener("click", () => {
    clearBenchOverlays();
  });
}

function renderPresets(host: HTMLElement, urlInput: HTMLTextAreaElement): void {
  host.innerHTML = "";
  for (const preset of PRESETS) {
    const chip = document.createElement("button");
    chip.className = "bench-chip";
    chip.textContent = preset.label;
    chip.addEventListener("click", () => {
      urlInput.value = preset.url;
    });
    host.appendChild(chip);
  }
}

async function sendRequest(
  urlInput: HTMLTextAreaElement,
  output: HTMLElement,
  status: HTMLElement,
): Promise<void> {
  const url = urlInput.value.trim();
  if (!url) return;

  status.innerHTML = `<span class="bench-pill">…</span> sending`;
  output.textContent = "";

  try {
    const result = await callHere(url);
    lastResponse = result.json ?? result.text;

    const pillClass = result.ok ? "ok" : "err";
    status.innerHTML =
      `<span class="bench-pill ${pillClass}">${result.status}</span>` +
      `<span class="bench-meta">${result.elapsedMs} ms</span>`;
    output.textContent = result.json
      ? JSON.stringify(result.json, null, 2)
      : result.text || "(empty body)";
  } catch (error) {
    lastResponse = null;
    status.innerHTML = `<span class="bench-pill err">error</span>`;
    output.textContent = error instanceof Error ? error.message : String(error);
  }
}

function plotLastResponse(status: HTMLElement): void {
  if (lastResponse === null) {
    status.innerHTML = `<span class="bench-pill warn">no data</span> Send してから plot`;
    return;
  }

  const { lines, points } = extractGeometry(lastResponse);
  if (lines.length === 0 && points.length === 0) {
    status.innerHTML = `<span class="bench-pill warn">no geo</span> 描けるジオメトリなし`;
    return;
  }

  drawBenchGeometry(lines, points, BENCH_ACCENT);
}

/** レスポンスから描画可能なジオメトリ（polyline / 地点）を最大限拾う。 */
function extractGeometry(data: unknown): { lines: LatLng[][]; points: LatLng[] } {
  const lines: LatLng[][] = [];
  const points: LatLng[] = [];

  const visit = (node: unknown): void => {
    if (node === null || typeof node !== "object") return;

    if (Array.isArray(node)) {
      for (const child of node) visit(child);
      return;
    }

    const record = node as Record<string, unknown>;

    // HERE flexible polyline 文字列
    if (typeof record.polyline === "string") {
      try {
        lines.push(decodeFlexiblePolyline(record.polyline));
      } catch {
        // 無効な polyline は無視
      }
    }

    // { lat, lng } 地点（browse / discover / revgeocode の position 等）
    if (typeof record.lat === "number" && typeof record.lng === "number") {
      points.push({ lat: record.lat, lng: record.lng });
    }

    for (const value of Object.values(record)) visit(value);
  };

  visit(data);
  return { lines, points };
}
