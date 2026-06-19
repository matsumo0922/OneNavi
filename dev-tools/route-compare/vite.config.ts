import {defineConfig} from "vite";
import {resolve} from "node:path";

// dev-tools/route-compare から OneNavi リポジトリ全体を /@fs 経由で
// 読めるよう allow リストを広げる。外部API の sample
// 配下の生データを直接 fetch するため。
export default defineConfig({
  root: ".",
  build: {
    outDir: "dist",
  },
  server: {
    port: 5174,
    open: true,
    fs: {
      allow: [resolve(__dirname, "../..")],
    },
  },
});
