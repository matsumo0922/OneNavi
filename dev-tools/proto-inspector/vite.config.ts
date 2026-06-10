import type { IncomingMessage, ServerResponse } from "node:http";
import fs from "node:fs";
import path from "node:path";
import { defineConfig, type Plugin, type ViteDevServer } from "vite";

/**
 * proto-inspector はスキーマ無しで protobuf バイナリを raw decode し、
 * 各フィールドに対するユーザー入力のメタデータ (name / description / type_hint)
 * をローカル JSON に永続化する。
 *
 * 仕様:
 * - GET  /api/annotations?root=<id>      → 該当 root の注釈 JSON を返す
 * - PUT  /api/annotations?root=<id>      → リクエストボディの注釈 JSON で上書き保存
 * - GET  /api/annotations/index          → 保存済み root id 一覧
 *
 * 保存先は dev-tools/proto-inspector/annotations/<root>.json。
 * パストラバーサル防止のため root id は [a-zA-Z0-9_-] のみ許可する。
 */

const ANNOTATIONS_DIR = path.resolve(process.cwd(), "annotations");
const VALID_ROOT_ID = /^[a-zA-Z0-9_-]{1,64}$/;

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

function rootIdFromQuery(url: string): string | null {
  const parsed = new URL(url, "http://localhost");
  const root = parsed.searchParams.get("root");
  if (!root || !VALID_ROOT_ID.test(root)) return null;
  return root;
}

function annotationFilePath(rootId: string): string {
  return path.join(ANNOTATIONS_DIR, `${rootId}.json`);
}

function handleGetAnnotations(rootId: string, response: ServerResponse): void {
  const file = annotationFilePath(rootId);
  if (!fs.existsSync(file)) {
    sendJson(response, 200, { root: rootId, fields: {} });
    return;
  }
  try {
    const parsed = JSON.parse(fs.readFileSync(file, "utf8")) as unknown;
    sendJson(response, 200, parsed);
  } catch (error) {
    sendJson(response, 500, { error: (error as Error).message });
  }
}

async function handlePutAnnotations(
  rootId: string,
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  try {
    const body = (await readJsonBody(request)) as Record<string, unknown>;
    fs.mkdirSync(ANNOTATIONS_DIR, { recursive: true });
    const payload = { ...body, root: rootId, savedAt: new Date().toISOString() };
    fs.writeFileSync(annotationFilePath(rootId), `${JSON.stringify(payload, null, 2)}\n`);
    sendJson(response, 200, { success: true });
  } catch (error) {
    sendJson(response, 500, { success: false, error: (error as Error).message });
  }
}

function handleIndex(response: ServerResponse): void {
  let files: string[] = [];
  try {
    files = fs.readdirSync(ANNOTATIONS_DIR).filter((entry) => entry.endsWith(".json"));
  } catch {
    sendJson(response, 200, []);
    return;
  }
  const ids = files.map((file) => file.replace(/\.json$/, ""));
  sendJson(response, 200, ids);
}

function annotationsBridge(): Plugin {
  return {
    name: "onenavi-proto-inspector-annotations",
    configureServer(server: ViteDevServer) {
      server.middlewares.use((request, response, next) => {
        const url = request.url ?? "";
        const method = request.method ?? "GET";

        if (method === "GET" && url.startsWith("/api/annotations/index")) {
          handleIndex(response);
          return;
        }
        if (method === "GET" && url.startsWith("/api/annotations")) {
          const rootId = rootIdFromQuery(url);
          if (!rootId) {
            sendJson(response, 400, { error: "invalid root id" });
            return;
          }
          handleGetAnnotations(rootId, response);
          return;
        }
        if (method === "PUT" && url.startsWith("/api/annotations")) {
          const rootId = rootIdFromQuery(url);
          if (!rootId) {
            sendJson(response, 400, { error: "invalid root id" });
            return;
          }
          void handlePutAnnotations(rootId, request, response);
          return;
        }
        next();
      });
    },
  };
}

export default defineConfig({
  root: ".",
  plugins: [annotationsBridge()],
  build: {
    outDir: "dist",
  },
  server: {
    port: 5175,
    open: true,
  },
});
