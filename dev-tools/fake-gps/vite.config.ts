import { execFile } from "node:child_process";
import type { IncomingMessage, ServerResponse } from "node:http";
import { promisify } from "node:util";
import { defineConfig, type Plugin, type ViteDevServer } from "vite";
import { buildGgaSentence, buildRmcSentence, type GpsFix } from "./src/nmea";

const execFileAsync = promisify(execFile);

/** 直近に解決した emulator のシリアル。adb 失敗時は null に戻して取り直す。 */
let cachedSerial: string | null = null;
/** 直近に注入した位置。/status で dev-tool 側に返す。 */
let lastFix: GpsFix | null = null;

/**
 * 対象 emulator のシリアルを解決する。
 * EMU_SERIAL env があれば優先。無ければ `adb devices` の先頭 emulator を使う。
 */
async function resolveEmulatorSerial(forceRefresh = false): Promise<string | null> {
  if (process.env.EMU_SERIAL) return process.env.EMU_SERIAL;
  if (cachedSerial && !forceRefresh) return cachedSerial;

  try {
    const { stdout } = await execFileAsync("adb", ["devices"]);
    const line = stdout
      .split("\n")
      .map((entry) => entry.trim())
      .find((entry) => entry.startsWith("emulator-") && entry.endsWith("device"));
    cachedSerial = line ? line.split(/\s+/)[0] : null;
  } catch {
    cachedSerial = null;
  }
  return cachedSerial;
}

/** `adb -s <serial> emu geo nmea <sentence>` を実行する。 */
async function sendNmea(serial: string, sentence: string): Promise<void> {
  await execFileAsync("adb", ["-s", serial, "emu", "geo", "nmea", sentence]);
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
    const fix = (await readJsonBody(request)) as GpsFix;
    const serial = await resolveEmulatorSerial();
    if (!serial) {
      sendJson(response, 503, { success: false, error: "No emulator detected (adb devices)" });
      return;
    }
    const now = new Date();
    // GGA で位置を確定 → RMC で速度・進行方向を与える 2 文ペアで送る。
    await sendNmea(serial, buildGgaSentence(fix, now));
    await sendNmea(serial, buildRmcSentence(fix, now));
    lastFix = fix;
    sendJson(response, 200, { success: true });
  } catch (error) {
    cachedSerial = null; // adb 失敗時は次回 serial を取り直す
    sendJson(response, 500, { success: false, error: (error as Error).message });
  }
}

async function handleStatus(response: ServerResponse): Promise<void> {
  const serial = await resolveEmulatorSerial(true);
  sendJson(response, 200, { active: serial !== null, serial, lastLocation: lastFix });
}

/**
 * ブラウザは `adb` を直接叩けないため、Vite dev server に同居する HTTP ブリッジを立てる。
 * dev-tool からの POST /location を NMEA に変換し、`adb emu geo nmea` で emulator に注入する。
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
          void handleStatus(response);
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
