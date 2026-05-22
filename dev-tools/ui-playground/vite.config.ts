import {defineConfig} from "vite";

// designs/<id>/*.html は ?url で iframe に読ませるため、Vite の publicDir 的な
// 扱いではなく通常のアセットとして root 配下から配信する。
export default defineConfig({
  root: ".",
  build: {
    outDir: "dist",
  },
  server: {
    port: 5175,
    open: true,
  },
});
