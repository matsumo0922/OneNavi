import { execSync } from "node:child_process";

const PORT = 5556;

try {
  execSync(`adb forward tcp:${PORT} tcp:${PORT}`, { stdio: "pipe" });
  console.log(`[setup-adb] Forwarding tcp:${PORT} -> device tcp:${PORT}`);
} catch {
  console.error(
    "[setup-adb] Failed to set up ADB forward. Is a device connected?\n" +
      "  1. Connect your Android device via USB\n" +
      "  2. Enable USB debugging\n" +
      "  3. Run: adb devices"
  );
  process.exit(1);
}
