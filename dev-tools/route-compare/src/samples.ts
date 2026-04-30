/**
 * drive-supporter-api/analysis/sample 配下のサンプルデータを fetch する。
 *
 * Vite の `/@fs/` 機構で repo 外の絶対パスを直接読む。
 * (vite.config.ts の server.fs.allow にプロジェクトルートを追加済み)
 *
 * NOTE: サンプルデータはサーバー既定の Tokyo Datum (旧日本測地系) で記録されて
 * いるため、Google Maps (WGS84) で表示する前に `datum.ts` で変換する必要がある。
 */

import {tokyoToWgs84, tokyoToWgs84Path} from "./datum";

export interface LatLng {
  lat: number;
  lng: number;
}

export interface GuidePoint {
  index: number;
  /** Cumulative distance from previous GP (meters). */
  cum_distance_m: number;
  coord: {
    /** arc-millisecond ÷ 3,600,000 = degree */
    lat_e6: number;
    lon_e6: number;
  };
}

export interface SampleEntry {
  id: string;
  label: string;
  /** route-polyline.geojson が存在するか (無い sample もある) */
  hasExternalPolyline: boolean;
}

const SAMPLE_ROOT_ABS =
  "/Users/daichi-matsumoto/dev/Android/OneNavi/drive-supporter-api/analysis/sample";

/**
 * 利用可能なサンプル一覧。新しい sample を追加した場合はここに追記する。
 */
export const SAMPLES: SampleEntry[] = [
  { id: "shakuji-tsukuba", label: "shakuji-tsukuba (74km)", hasExternalPolyline: true },
  { id: "tokyo-gotemba", label: "tokyo-gotemba", hasExternalPolyline: false },
  { id: "tokyo-nagoya-hiroshima", label: "tokyo-nagoya-hiroshima", hasExternalPolyline: false },
  { id: "hiroshima-ferry-beppu", label: "hiroshima-ferry-beppu", hasExternalPolyline: false },
];

function fsUrl(absolutePath: string): string {
  return `/@fs${absolutePath}`;
}

/**
 * route-polyline.geojson を読み、LineString を LatLng[] に変換する。
 * sample に存在しなければ null を返す。
 */
export async function loadExternalPolyline(sampleId: string): Promise<LatLng[] | null> {
  const url = fsUrl(`${SAMPLE_ROOT_ABS}/${sampleId}/extracted/route/route-polyline.geojson`);
  const response = await fetch(url);
  if (!response.ok) return null;

  const geojson = (await response.json()) as {
    features: Array<{
      geometry: { type: string; coordinates: number[][] };
    }>;
  };

  const feature = geojson.features.find((feat) => feat.geometry.type === "LineString");
  if (!feature) return null;

  const tokyoDatumPath = feature.geometry.coordinates.map(([lng, lat]) => ({ lat, lng }));
  return tokyoToWgs84Path(tokyoDatumPath);
}

/**
 * guide-points.json を読む。N 社の座標は arc-msec エンコードなので degree に変換した
 * LatLng と元の cum_distance_m を返す。
 */
export async function loadGuidePoints(
  sampleId: string,
): Promise<{ points: LatLng[]; raw: GuidePoint[] }> {
  const url = fsUrl(`${SAMPLE_ROOT_ABS}/${sampleId}/extracted/guide/guide-points.json`);
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`guide-points.json not found for ${sampleId}: ${response.status}`);
  }

  const raw = (await response.json()) as GuidePoint[];
  const points = raw.map((point) =>
    tokyoToWgs84({
      lat: point.coord.lat_e6 / 3_600_000,
      lng: point.coord.lon_e6 / 3_600_000,
    }),
  );
  return { points, raw };
}
