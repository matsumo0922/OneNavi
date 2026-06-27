import type { LatLng } from "../geo-utils";
import type { ProviderRouteResult, RouteProvider } from "./types";

/** Google Directions API プロバイダ。地図 JS API の DirectionsService を使う。 */
export class GoogleRouteProvider implements RouteProvider {
  readonly id = "google" as const;
  readonly label = "Google";
  readonly accent = "#4285f4";

  private service: google.maps.DirectionsService | null = null;

  private directionsService(): google.maps.DirectionsService {
    if (!this.service) {
      this.service = new google.maps.DirectionsService();
    }
    return this.service;
  }

  async computeRoute(waypoints: LatLng[]): Promise<ProviderRouteResult> {
    const origin = waypoints[0];
    const destination = waypoints[waypoints.length - 1];
    const vias = waypoints.slice(1, -1).map((waypoint) => ({
      location: new google.maps.LatLng(waypoint.lat, waypoint.lng),
      stopover: true,
    }));

    const result = await this.directionsService().route({
      origin: new google.maps.LatLng(origin.lat, origin.lng),
      destination: new google.maps.LatLng(destination.lat, destination.lng),
      waypoints: vias,
      travelMode: google.maps.TravelMode.DRIVING,
    });

    const coords = extractPath(result);
    const { distanceMeters, durationSeconds } = sumLegs(result);

    return { coords, distanceMeters, durationSeconds, raw: result };
  }
}

function extractPath(result: google.maps.DirectionsResult): LatLng[] {
  const path: LatLng[] = [];
  const legs = result.routes[0]?.legs ?? [];

  for (const leg of legs) {
    for (const step of leg.steps ?? []) {
      for (const point of step.path ?? []) {
        path.push({ lat: point.lat(), lng: point.lng() });
      }
    }
  }

  return path;
}

function sumLegs(result: google.maps.DirectionsResult): {
  distanceMeters: number | null;
  durationSeconds: number | null;
} {
  const legs = result.routes[0]?.legs ?? [];
  if (legs.length === 0) {
    return { distanceMeters: null, durationSeconds: null };
  }

  let distanceMeters = 0;
  let durationSeconds = 0;
  for (const leg of legs) {
    distanceMeters += leg.distance?.value ?? 0;
    durationSeconds += leg.duration?.value ?? 0;
  }

  return { distanceMeters, durationSeconds };
}
