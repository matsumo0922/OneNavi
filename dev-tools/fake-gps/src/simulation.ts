import type { LatLng } from "./geo-utils";
import {
  bearingDeg,
  interpolateAlongPath,
  nearestPointOnPath,
  pathTotalDistance,
} from "./geo-utils";
import { sendLocation } from "./connection";

export type SimulationMode = "idle" | "route" | "gpx" | "manual";
export type PlaybackState = "stopped" | "playing" | "paused";

export interface SimulationState {
  mode: SimulationMode;
  playback: PlaybackState;
  position: LatLng | null;
  bearing: number;
  speedKmh: number;
  progress: number; // 0..1
  speedMultiplier: number;
}

type StateListener = (state: SimulationState) => void;

/** 基本速度 (km/h)。矢印キーおよびルート再生のデフォルト速度。 */
const BASE_SPEED_KMH = 30;

/**
 * シミュレーションエンジン。
 * ルート再生、GPX 再生、手動操作の 3 モードを管理する。
 */
export class SimulationEngine {
  private mode: SimulationMode = "idle";
  private playback: PlaybackState = "stopped";
  private position: LatLng | null = null;
  private bearing = 0;
  private speedMultiplier = 1;

  private routePath: LatLng[] = [];
  private traveledDistance = 0;
  private totalDistance = 0;

  private timerHandle: ReturnType<typeof setInterval> | null = null;
  private listeners: StateListener[] = [];

  onStateChange(listener: StateListener): void {
    this.listeners.push(listener);
  }

  getState(): SimulationState {
    const progress = this.totalDistance > 0 ? this.traveledDistance / this.totalDistance : 0;
    const speedKmh = this.playback === "playing" ? BASE_SPEED_KMH * this.speedMultiplier : 0;
    return {
      mode: this.mode,
      playback: this.playback,
      position: this.position,
      bearing: this.bearing,
      speedKmh,
      progress: Math.min(progress, 1),
      speedMultiplier: this.speedMultiplier,
    };
  }

  setSpeedMultiplier(multiplier: number): void {
    this.speedMultiplier = multiplier;
    this.notify();
  }

  /**
   * ルート再生を開始する。
   */
  startRoute(path: LatLng[]): void {
    if (path.length < 2) return;
    this.stop();

    this.mode = "route";
    this.routePath = path;
    this.traveledDistance = 0;
    this.totalDistance = pathTotalDistance(path);
    this.position = path[0];
    this.bearing = bearingDeg(path[0], path[1]);

    this.play();
  }

  /**
   * GPX 再生を開始する。
   */
  startGpx(path: LatLng[]): void {
    if (path.length < 2) return;
    this.stop();

    this.mode = "gpx";
    this.routePath = path;
    this.traveledDistance = 0;
    this.totalDistance = pathTotalDistance(path);
    this.position = path[0];
    this.bearing = bearingDeg(path[0], path[1]);

    this.play();
  }

  play(): void {
    if (this.routePath.length < 2) return;
    if (this.playback === "playing") return;

    this.playback = "playing";
    this.startTimer();
    this.notify();
  }

  pause(): void {
    if (this.playback !== "playing") return;
    this.playback = "paused";
    this.stopTimer();
    this.notify();
  }

  stop(): void {
    this.stopTimer();
    this.playback = "stopped";
    this.mode = "idle";
    this.routePath = [];
    this.traveledDistance = 0;
    this.totalDistance = 0;
    this.notify();
  }

  /**
   * 手動操作で位置を直接セットする（矢印キー操作用）。
   * 再生中の場合は自動再生を一時停止して手動モードに切り替える。
   */
  setManualPosition(pos: LatLng, newBearing: number): void {
    if (this.playback === "playing") {
      this.stopTimer();
      this.playback = "paused";
    }
    this.mode = "manual";
    this.position = pos;
    this.bearing = newBearing;
    this.sendCurrentPosition();
    this.notify();
  }

  /**
   * 手動操作後にルート再生に復帰する。
   * 現在位置に最も近いルート上の点から再開。
   */
  resumeRoute(): void {
    if (this.routePath.length < 2) return;
    if (this.position) {
      const nearest = nearestPointOnPath(this.routePath, this.position);
      this.traveledDistance = nearest.distance;
    }
    this.mode = this.routePath.length > 0 ? "route" : "idle";
    this.play();
  }

  /** 現在の位置と bearing を返す。 */
  getCurrentPosition(): { position: LatLng | null; bearing: number } {
    return { position: this.position, bearing: this.bearing };
  }

  getRoutePath(): LatLng[] {
    return this.routePath;
  }

  // --- Private ---

  private startTimer(): void {
    this.stopTimer();
    // 1Hz (1 秒間隔)
    this.timerHandle = setInterval(() => this.tick(), 1000);
    // 即座に最初の位置を送信
    this.tick();
  }

  private stopTimer(): void {
    if (this.timerHandle !== null) {
      clearInterval(this.timerHandle);
      this.timerHandle = null;
    }
  }

  private tick(): void {
    if (this.playback !== "playing" || this.routePath.length < 2) return;

    // 1 秒あたりの移動距離
    const distPerTick = (BASE_SPEED_KMH * this.speedMultiplier * 1000) / 3600;
    this.traveledDistance += distPerTick;

    const result = interpolateAlongPath(this.routePath, this.traveledDistance);
    if (!result) {
      this.stop();
      return;
    }

    this.position = result.point;
    this.bearing = result.bearing;

    // 終端に到達
    if (this.traveledDistance >= this.totalDistance) {
      this.position = this.routePath[this.routePath.length - 1];
      this.sendCurrentPosition();
      this.stop();
      return;
    }

    this.sendCurrentPosition();
    this.notify();
  }

  private async sendCurrentPosition(): Promise<void> {
    if (!this.position) return;

    const speedMs = (BASE_SPEED_KMH * this.speedMultiplier * 1000) / 3600;
    await sendLocation({
      lat: this.position.lat,
      lng: this.position.lng,
      bearing: this.bearing,
      speed: this.playback === "playing" ? speedMs : 0,
      accuracy: 5,
      altitude: 0,
    });
  }

  private notify(): void {
    const state = this.getState();
    for (const listener of this.listeners) {
      listener(state);
    }
  }
}
