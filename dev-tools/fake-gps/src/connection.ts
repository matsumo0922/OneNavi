const BASE_URL = "http://localhost:5556";

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
  lastLocation: LocationPayload | null;
}

/**
 * Android の FakeGpsServer に位置情報を送信する。
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
 * FakeGpsServer の状態を取得する。
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
 * Mock Provider を停止する。
 */
export async function stopMockProvider(): Promise<boolean> {
  try {
    const response = await fetch(`${BASE_URL}/stop`, { method: "POST" });
    return response.ok;
  } catch {
    return false;
  }
}
