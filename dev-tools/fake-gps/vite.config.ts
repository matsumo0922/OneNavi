import type { IncomingMessage, ServerResponse } from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import grpc from "@grpc/grpc-js";
import protoLoader from "@grpc/proto-loader";
import { defineConfig, type Plugin, type ViteDevServer } from "vite";

/**
 * dev-tool から受け取る 1 点分の位置。
 * `adb emu geo fix` と違い gRPC setGps は bearing / speed / satellites も注入できるため、
 * フロントが算出した進行方向・速度をそのまま emulator の GPS に流す。
 * これにより生の gps provider を直接購読するナビアプリでも自車が滑らかに追従する。
 */
interface LocationPayload {
  lat: number;
  lng: number;
  bearing: number;
  speed: number;
  accuracy: number;
  altitude: number;
}

/** setGps に渡す衛星数 (fix quality を満たすためのダミー値)。 */
const SATELLITE_COUNT = 12;

/** 実行中 emulator の gRPC 接続情報 (discovery ini 由来)。 */
interface EmulatorEndpoint {
  port: string;
  token: string;
  serial: string;
}

/** discovery ini を探すディレクトリ候補 (macOS を主、Linux も一応)。 */
function discoveryDirs(): string[] {
  const home = os.homedir();
  const candidates = [
    process.env.XDG_RUNTIME_DIR ? path.join(process.env.XDG_RUNTIME_DIR, "avd", "running") : null,
    path.join(os.tmpdir(), "avd", "running"),
    path.join(home, "Library", "Caches", "TemporaryItems", "avd", "running"),
    path.join(home, ".android", "avd", "running"),
  ];
  return candidates.filter((dir): dir is string => dir !== null);
}

/** ini テキストから 1 キーの値を取り出す (値に `=` を含む token に対応)。 */
function readIniValue(text: string, key: string): string | null {
  const line = text.split("\n").find((entry) => entry.startsWith(`${key}=`));
  if (!line) return null;
  return line.slice(line.indexOf("=") + 1).trim();
}

/**
 * 実行中 emulator の gRPC ポート・トークンを discovery ini から解決する。
 * EMU_SERIAL env があれば port.serial で照合、無ければ最も新しい ini を採用する。
 */
function findEndpoint(): EmulatorEndpoint | null {
  const wantSerial = process.env.EMU_SERIAL?.replace(/^emulator-/, "") ?? null;
  const found: Array<{ endpoint: EmulatorEndpoint; mtimeMs: number }> = [];

  for (const dir of discoveryDirs()) {
    let entries: string[] = [];
    try {
      entries = fs.readdirSync(dir);
    } catch {
      continue;
    }
    for (const entry of entries) {
      if (!/^pid_\d+\.ini$/.test(entry)) continue;
      const filePath = path.join(dir, entry);
      try {
        const text = fs.readFileSync(filePath, "utf8");
        const port = readIniValue(text, "grpc.port");
        const token = readIniValue(text, "grpc.token");
        const serial = readIniValue(text, "port.serial");
        if (!port || !token || !serial) continue;
        found.push({
          endpoint: { port, token, serial: `emulator-${serial}` },
          mtimeMs: fs.statSync(filePath).mtimeMs,
        });
      } catch {
        // 壊れた ini はスキップ
      }
    }
  }

  if (found.length === 0) return null;
  if (wantSerial) {
    const matched = found.find((item) => item.endpoint.serial === `emulator-${wantSerial}`);
    if (matched) return matched.endpoint;
  }
  found.sort((left, right) => right.mtimeMs - left.mtimeMs);
  return found[0].endpoint;
}

/** EmulatorController の gRPC クライアント型 (最小定義)。 */
interface EmulatorControllerClient {
  setGps(
    request: Record<string, unknown>,
    metadata: grpc.Metadata,
    callback: (error: grpc.ServiceError | null) => void,
  ): void;
}

const protoDefinition = protoLoader.loadSync(path.resolve(process.cwd(), "proto", "emulator_min.proto"), {
  keepCase: true,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true,
});
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const ControllerCtor = (grpc.loadPackageDefinition(protoDefinition) as any).android.emulation.control
  .EmulatorController as new (address: string, credentials: grpc.ChannelCredentials) => EmulatorControllerClient;

/** 解決済みの接続情報・クライアント・認証メタデータをキャッシュする。 */
let cached: { endpoint: EmulatorEndpoint; client: EmulatorControllerClient; metadata: grpc.Metadata } | null = null;
/** 直近に注入した位置。/status で dev-tool 側に返す。 */
let lastFix: LocationPayload | null = null;

/** クライアントを取得する。接続情報が変わっていれば張り直す。 */
function ensureClient(): typeof cached {
  const endpoint = findEndpoint();
  if (!endpoint) {
    cached = null;
    return null;
  }
  if (cached && cached.endpoint.port === endpoint.port && cached.endpoint.token === endpoint.token) {
    return cached;
  }
  const client = new ControllerCtor(`localhost:${endpoint.port}`, grpc.credentials.createInsecure());
  const metadata = new grpc.Metadata();
  metadata.set("authorization", `Bearer ${endpoint.token}`);
  cached = { endpoint, client, metadata };
  return cached;
}

/**
 * gRPC EmulatorController/setGps で位置を注入する。
 * passiveUpdate=false で Location UI の 1Hz 上書きを止め、本ツールの更新を優先させる。
 */
function sendGps(payload: LocationPayload): Promise<string> {
  const connection = ensureClient();
  if (!connection) {
    return Promise.reject(new Error("No running emulator found (discovery ini not located)"));
  }
  const request = {
    passiveUpdate: false,
    latitude: payload.lat,
    longitude: payload.lng,
    speed: payload.speed,
    bearing: ((payload.bearing % 360) + 360) % 360,
    altitude: payload.altitude,
    satellites: SATELLITE_COUNT,
  };
  return new Promise((resolve, reject) => {
    connection.client.setGps(request, connection.metadata, (error) => {
      if (error) {
        cached = null; // 失敗時は次回張り直す (emulator 再起動でポート/トークンが変わるため)
        reject(error);
      } else {
        resolve(connection.endpoint.serial);
      }
    });
  });
}

/** リクエストボディを JSON として読み取る。 */
function readJsonBody(request: IncomingMessage): Promise<unknown> {
  return new Promise((resolve, reject) => {
    let raw = "";
    request.on("data", (chunk) => {
      raw += chunk;
    });
    request.on("end", () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch (error) {
        reject(error);
      }
    });
    request.on("error", reject);
  });
}

function sendJson(response: ServerResponse, status: number, payload: unknown): void {
  response.statusCode = status;
  response.setHeader("Content-Type", "application/json");
  response.end(JSON.stringify(payload));
}

async function handleLocation(request: IncomingMessage, response: ServerResponse): Promise<void> {
  try {
    const payload = (await readJsonBody(request)) as LocationPayload;
    await sendGps(payload);
    lastFix = payload;
    sendJson(response, 200, { success: true });
  } catch (error) {
    sendJson(response, 503, { success: false, error: (error as Error).message });
  }
}

function handleStatus(response: ServerResponse): void {
  const endpoint = findEndpoint();
  sendJson(response, 200, {
    active: endpoint !== null,
    serial: endpoint?.serial ?? null,
    lastLocation: lastFix,
  });
}

/**
 * ブラウザは emulator の gRPC を直接叩けないため、Vite dev server に同居する HTTP ブリッジを立てる。
 * dev-tool からの POST /location を gRPC EmulatorController/setGps で emulator に注入する。
 */
function emulatorGeoBridge(): Plugin {
  return {
    name: "onenavi-emulator-geo-bridge",
    configureServer(server: ViteDevServer) {
      server.middlewares.use((request, response, next) => {
        const url = request.url ?? "";
        const method = request.method ?? "GET";

        if (method === "POST" && url === "/location") {
          void handleLocation(request, response);
          return;
        }
        if (method === "GET" && url === "/status") {
          handleStatus(response);
          return;
        }
        if (method === "POST" && url === "/stop") {
          // emulator は最後の位置を保持し続けるため停止概念は無い。最終位置を忘れるだけ。
          lastFix = null;
          sendJson(response, 200, { success: true });
          return;
        }
        next();
      });
    },
  };
}

export default defineConfig({
  root: ".",
  plugins: [emulatorGeoBridge()],
  build: {
    outDir: "dist",
  },
  server: {
    port: 5173,
    open: true,
  },
});
