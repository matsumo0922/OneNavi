import type { LatLng } from "./geo-utils";

export interface GpxTrackPoint extends LatLng {
  /** Unix timestamp in ms (if available in GPX) */
  time: number | null;
  ele: number | null;
}

/**
 * GPX XML 文字列をパースして、トラックポイントの配列を返す。
 * `<trk>/<trkseg>/<trkpt>` のみ対応。
 */
export function parseGpx(xmlString: string): GpxTrackPoint[] {
  const parser = new DOMParser();
  const doc = parser.parseFromString(xmlString, "application/xml");
  const points: GpxTrackPoint[] = [];

  const trkpts = doc.querySelectorAll("trkpt");
  for (const trkpt of trkpts) {
    const lat = parseFloat(trkpt.getAttribute("lat") ?? "");
    const lng = parseFloat(trkpt.getAttribute("lon") ?? "");
    if (isNaN(lat) || isNaN(lng)) continue;

    const timeEl = trkpt.querySelector("time");
    const eleEl = trkpt.querySelector("ele");

    points.push({
      lat,
      lng,
      time: timeEl?.textContent ? new Date(timeEl.textContent).getTime() : null,
      ele: eleEl?.textContent ? parseFloat(eleEl.textContent) : null,
    });
  }

  return points;
}
