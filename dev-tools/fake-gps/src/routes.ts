import type { LatLng } from "./geo-utils";

// Vite dev server に同居するブリッジ (vite.config.ts) の /routes エンドポイントと通信する。
// 保存ルートは dev-tools/fake-gps/routes/ 配下に 1 ルート 1 JSON で永続化され git 管理できる。

/** 保存済みルート 1 件。 */
export interface SavedRoute {
  name: string;
  waypoints: LatLng[];
  savedAt: string;
  file: string;
}

/** 保存済みルートを新しい順に取得する。 */
export async function listRoutes(): Promise<SavedRoute[]> {
  try {
    const response = await fetch("/routes");
    if (!response.ok) return [];
    return (await response.json()) as SavedRoute[];
  } catch {
    return [];
  }
}

/** 現在の waypoints を名前付きで保存する (同名は上書き)。 */
export async function saveRoute(name: string, waypoints: LatLng[]): Promise<boolean> {
  try {
    const response = await fetch("/routes", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, waypoints }),
    });
    return response.ok;
  } catch {
    return false;
  }
}

/** 指定ファイルの保存ルートを削除する。 */
export async function deleteRoute(file: string): Promise<boolean> {
  try {
    const response = await fetch(`/routes?file=${encodeURIComponent(file)}`, { method: "DELETE" });
    return response.ok;
  } catch {
    return false;
  }
}
