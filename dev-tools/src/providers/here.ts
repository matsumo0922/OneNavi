import type { LatLng } from "../geo-utils";
import { callHere } from "../here";
import { HERE_ROUTING_FIELDS } from "../options/here-routing-schema";
import { applyOptionValues } from "../options/query";
import { getHereRoutingOptions } from "../options/store";
import { decodeFlexiblePolyline } from "./flexpolyline";
import type { ProviderRouteResult, RouteProvider } from "./types";

const ROUTING_V8_URL = "https://router.hereapi.com/v8/routes";

/** 描画・距離算出・詳細表示に必須で、ユーザー設定に関わらず常時付与する return 値。 */
const FORCED_RETURN = ["polyline", "summary", "actions", "instructions", "travelSummary", "tolls"];

/** 詳細パネル（speed graph 等）に必須で常時付与する spans 値。 */
const FORCED_SPANS = [
  "length",
  "duration",
  "baseDuration",
  "speedLimit",
  "maxSpeed",
  "dynamicSpeedInfo",
  "functionalClass",
  "names",
];

/** HERE Routing v8 のセクション形状（必要分のみ）。 */
interface HereSection {
  polyline?: string;
  summary?: { length?: number; duration?: number };
}

interface HereRoute {
  sections?: HereSection[];
}

interface HereRoutingResponse {
  routes?: HereRoute[];
}

/** HERE Routing v8 プロバイダ。REST を直接叩き flexible polyline をデコードする。 */
export class HereRouteProvider implements RouteProvider {
  readonly id = "here" as const;
  readonly label = "HERE";
  readonly accent = "#00afaa";

  async computeRoute(waypoints: LatLng[]): Promise<ProviderRouteResult> {
    const origin = waypoints[0];
    const destination = waypoints[waypoints.length - 1];
    const vias = waypoints.slice(1, -1);

    const url = new URL(ROUTING_V8_URL);
    url.searchParams.set("transportMode", "car");
    url.searchParams.set("origin", `${origin.lat},${origin.lng}`);
    url.searchParams.set("destination", `${destination.lat},${destination.lng}`);
    for (const via of vias) {
      url.searchParams.append("via", `${via.lat},${via.lng}`);
    }

    // ユーザー設定オプションを反映（transportMode / return 等を上書きしうる）
    applyOptionValues(url, getHereRoutingOptions(), HERE_ROUTING_FIELDS);

    if (!url.searchParams.has("lang")) {
      url.searchParams.set("lang", "ja-JP");
    }
    if (!url.searchParams.has("currency")) {
      url.searchParams.set("currency", "JPY");
    }

    mergeCsvParam(url, "return", FORCED_RETURN);
    mergeCsvParam(url, "spans", FORCED_SPANS);

    const result = await callHere(url.toString());
    if (!result.ok) {
      throw new Error(`HERE Routing v8 ${result.status} ${result.statusText}`);
    }

    const response = result.json as HereRoutingResponse;
    const sections = response.routes?.[0]?.sections ?? [];
    if (sections.length === 0) {
      throw new Error("HERE Routing v8: ルートが見つかりませんでした");
    }

    const coords = decodeSections(sections);
    const { distanceMeters, durationSeconds } = sumSections(sections);

    return { coords, distanceMeters, durationSeconds, raw: result.json };
  }
}

/** CSV 形式のクエリパラメータに、必須値をユーザー選択と和集合で必ず含める。 */
function mergeCsvParam(url: URL, key: string, forced: readonly string[]): void {
  const current = (url.searchParams.get(key) ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);

  const merged = new Set([...current, ...forced]);

  url.searchParams.set(key, [...merged].join(","));
}

function decodeSections(sections: HereSection[]): LatLng[] {
  const coords: LatLng[] = [];

  for (const section of sections) {
    if (!section.polyline) continue;
    const decoded = decodeFlexiblePolyline(section.polyline);
    // セクション境界の重複端点を 1 点に畳む
    const startIndex = coords.length > 0 && decoded.length > 0 ? 1 : 0;
    for (let index = startIndex; index < decoded.length; index++) {
      coords.push(decoded[index]);
    }
  }

  return coords;
}

function sumSections(sections: HereSection[]): {
  distanceMeters: number | null;
  durationSeconds: number | null;
} {
  let distanceMeters = 0;
  let durationSeconds = 0;
  let hasSummary = false;

  for (const section of sections) {
    if (section.summary) {
      hasSummary = true;
      distanceMeters += section.summary.length ?? 0;
      durationSeconds += section.summary.duration ?? 0;
    }
  }

  if (!hasSummary) {
    return { distanceMeters: null, durationSeconds: null };
  }

  return { distanceMeters, durationSeconds };
}
