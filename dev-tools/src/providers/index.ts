import { GoogleRouteProvider } from "./google";
import { HereRouteProvider } from "./here";
import type { ProviderId, RouteProvider } from "./types";

export type { ProviderId, RouteProvider, ProviderRouteResult } from "./types";

const PROVIDER_STORAGE_KEY = "onenavi-dev-route-provider";

const providers: Record<ProviderId, RouteProvider> = {
  google: new GoogleRouteProvider(),
  here: new HereRouteProvider(),
};

let activeId: ProviderId = restoreProviderId();

function restoreProviderId(): ProviderId {
  const stored = localStorage.getItem(PROVIDER_STORAGE_KEY);
  return stored === "here" ? "here" : "google";
}

/** 利用可能な全プロバイダ。 */
export function allProviders(): RouteProvider[] {
  return [providers.google, providers.here];
}

/** 現在アクティブなプロバイダ。 */
export function activeProvider(): RouteProvider {
  return providers[activeId];
}

/** アクティブなプロバイダを切り替える（永続化する）。 */
export function setActiveProvider(id: ProviderId): RouteProvider {
  activeId = id;
  localStorage.setItem(PROVIDER_STORAGE_KEY, id);
  return providers[id];
}
