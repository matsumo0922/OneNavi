import type { OptionValues } from "./types";

// HERE Routing オプション値の永続化（localStorage）。

const HERE_OPTIONS_KEY = "onenavi-dev-here-options";

let cached: OptionValues | null = null;

/** 保存済みの HERE Routing オプション値を返す。 */
export function getHereRoutingOptions(): OptionValues {
  if (cached !== null) return cached;

  cached = readFromStorage();
  return cached;
}

/** HERE Routing オプション値を保存する。 */
export function setHereRoutingOptions(values: OptionValues): void {
  cached = values;

  try {
    localStorage.setItem(HERE_OPTIONS_KEY, JSON.stringify(values));
  } catch {
    // localStorage が使えない環境は無視
  }
}

function readFromStorage(): OptionValues {
  try {
    const stored = localStorage.getItem(HERE_OPTIONS_KEY);
    if (!stored) return {};

    return JSON.parse(stored) as OptionValues;
  } catch {
    return {};
  }
}
