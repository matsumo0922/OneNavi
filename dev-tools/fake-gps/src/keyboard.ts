import { destinationPoint, normalizeBearing, type LatLng } from "./geo-utils";
import { snapToRoad } from "./snap";
import type { SimulationEngine } from "./simulation";

/** 基本速度 (km/h) を m/s に変換。1 tick = 1 秒とみなす。 */
const BASE_SPEED_MS = (30 * 1000) / 3600; // ≈ 8.33 m/s

/**
 * 矢印キー操作を管理するクラス。
 * heading 基準で前進/後退/左右旋回し、Roads API で道路にスナップする。
 */
export class KeyboardController {
  private activeKeys = new Set<string>();
  private tickHandle: ReturnType<typeof setInterval> | null = null;
  private snapping = false;

  constructor(private readonly engine: SimulationEngine) {}

  /**
   * キーボードイベントの監視を開始する。
   */
  attach(): void {
    document.addEventListener("keydown", this.onKeyDown);
    document.addEventListener("keyup", this.onKeyUp);
  }

  detach(): void {
    document.removeEventListener("keydown", this.onKeyDown);
    document.removeEventListener("keyup", this.onKeyUp);
    this.stopTick();
  }

  private onKeyDown = (event: KeyboardEvent): void => {
    // テキスト入力中は無視
    if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) {
      return;
    }

    if (event.code === "Space") {
      event.preventDefault();
      this.togglePause();
      return;
    }

    const arrowKeys = ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"];
    if (!arrowKeys.includes(event.code)) return;

    event.preventDefault();
    this.activeKeys.add(event.code);
    this.startTick();
  };

  private onKeyUp = (event: KeyboardEvent): void => {
    this.activeKeys.delete(event.code);
    if (this.activeKeys.size === 0) {
      this.stopTick();
    }
  };

  private togglePause(): void {
    const state = this.engine.getState();
    if (state.playback === "playing") {
      this.engine.pause();
    } else if (state.playback === "paused") {
      // ルートが残っている場合はルート再生に復帰
      if (this.engine.getRoutePath().length >= 2) {
        this.engine.resumeRoute();
      }
    }
  }

  private startTick(): void {
    if (this.tickHandle !== null) return;
    this.processTick(); // 即時実行
    this.tickHandle = setInterval(() => this.processTick(), 100); // 10Hz for smooth movement
  }

  private stopTick(): void {
    if (this.tickHandle !== null) {
      clearInterval(this.tickHandle);
      this.tickHandle = null;
    }
  }

  private async processTick(): Promise<void> {
    if (this.snapping) return;

    const { position, bearing } = this.engine.getCurrentPosition();
    if (!position) return;

    const speedMultiplier = this.engine.getState().speedMultiplier;
    const distance = (BASE_SPEED_MS * speedMultiplier) / 10; // 10Hz なので 1/10

    let newBearing = bearing;
    let newPosition: LatLng = position;

    // 左右キーで旋回 (1 tick あたり 15 度)
    if (this.activeKeys.has("ArrowLeft")) {
      newBearing = normalizeBearing(newBearing - 15);
    }
    if (this.activeKeys.has("ArrowRight")) {
      newBearing = normalizeBearing(newBearing + 15);
    }

    // 前進/後退
    if (this.activeKeys.has("ArrowUp")) {
      newPosition = destinationPoint(position, newBearing, distance);
    }
    if (this.activeKeys.has("ArrowDown")) {
      newPosition = destinationPoint(position, normalizeBearing(newBearing + 180), distance);
    }

    // 道路スナップ (移動があった場合のみ)
    if (newPosition !== position) {
      this.snapping = true;
      try {
        newPosition = await snapToRoad(newPosition);
      } finally {
        this.snapping = false;
      }
    }

    this.engine.setManualPosition(newPosition, newBearing);
  }
}
