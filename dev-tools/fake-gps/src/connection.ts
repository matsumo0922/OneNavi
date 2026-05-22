// Vite dev server に同居する emulator ブリッジ (vite.config.ts) へ送る。
// 同一オリジンなので相対パスで良い。
const BASE_URL = "";

export interface LocationPayload {
  lat: number;
  lng: number;
  bearing: number;
  speed: number;
  accuracy: number;
  altitude: number;
}

export interface StatusResponse {
  active: boolean;
  serial?: string | null;
  lastLocation: LocationPayload | null;
}

/**
 * emulator ブリッジに位置情報を送信する。
 * ブリッジが NMEA に変換し `adb emu geo nmea` で emulator に注入する。
 */
export async function sendLocation(payload: LocationPayload): Promise<boolean> {
  try {
    const response = await fetch(`${BASE_URL}/location`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * emulator ブリッジの状態 (emulator 接続有無・最終送信位置) を取得する。
 */
export async function getStatus(): Promise<StatusResponse | null> {
  try {
    const response = await fetch(`${BASE_URL}/status`);
    if (!response.ok) return null;
    return (await response.json()) as StatusResponse;
  } catch {
    return null;
  }
}

/**
 * 位置送信を停止する (ブリッジ側の最終位置を破棄)。
 * emulator は最後の位置を保持し続ける点に注意。
 */
export async function stopMockProvider(): Promise<boolean> {
  try {
    const response = await fetch(`${BASE_URL}/stop`, { method: "POST" });
    return response.ok;
  } catch {
    return false;
  }
}
