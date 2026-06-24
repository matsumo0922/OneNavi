// HERE REST API クライアント（ブラウザ直叩き / apikey クエリ方式）。
// ルートプロバイダと API bench の両方が使う共通の呼び出し層。

export interface HereCallResult {
  ok: boolean;
  status: number;
  statusText: string;
  /** リクエスト往復のミリ秒。 */
  elapsedMs: number;
  /** パース済み JSON（JSON でなければ null）。 */
  json: unknown;
  /** 生テキスト（JSON パース失敗時の表示用）。 */
  text: string;
  /** apikey をマスクした実行 URL。 */
  maskedUrl: string;
}

/** dev-tools/.env の VITE_HERE_API_KEY。 */
export function hereApiKey(): string {
  return (import.meta.env.VITE_HERE_API_KEY as string | undefined) ?? "";
}

/** HERE apikey が設定済みか。 */
export function hasHereApiKey(): boolean {
  const key = hereApiKey();
  return key.length > 0 && key !== "your_here_api_key_here";
}

function withApiKey(rawUrl: string): { url: string; masked: string } {
  const url = new URL(rawUrl);
  url.searchParams.set("apikey", hereApiKey());

  const masked = new URL(url.toString());
  masked.searchParams.set("apikey", "***");

  return { url: url.toString(), masked: masked.toString() };
}

/**
 * HERE エンドポイントを呼ぶ。apikey は自動付与するので呼び出し側は付けない。
 * URL 文字列をそのまま受け取り、ステータス・所要時間・JSON を返す。
 */
export async function callHere(rawUrl: string, init?: RequestInit): Promise<HereCallResult> {
  if (!hasHereApiKey()) {
    throw new Error("VITE_HERE_API_KEY が未設定です。dev-tools/.env を確認してください。");
  }

  const { url, masked } = withApiKey(rawUrl);
  const startedAt = performance.now();
  const response = await fetch(url, init);
  const elapsedMs = Math.round(performance.now() - startedAt);
  const text = await response.text();

  let json: unknown = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }

  return {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    elapsedMs,
    json,
    text,
    maskedUrl: masked,
  };
}
