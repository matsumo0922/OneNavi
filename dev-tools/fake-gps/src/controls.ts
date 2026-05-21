import type {SimulationEngine, SimulationState} from "./simulation";
import type {LatLng} from "./geo-utils";
import {parseGpx} from "./gpx-parser";
import {getStatus} from "./connection";
import {clearRoute, findRoute, onMapClick, showGpxPath, updateCurrentPosition, updateWaypointMarkers,} from "./map";

/**
 * UI コントロールのバインディングとイベントハンドリングを行う。
 */
const WAYPOINTS_STORAGE_KEY = "onenavi-dev-waypoints";

export class ControlsManager {
  private waypoints: LatLng[] = [];
  private statusPollHandle: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly engine: SimulationEngine) {
    this.loadWaypoints();
  }

  /**
   * ポーリングを停止する。
   */
  destroy(): void {
    if (this.statusPollHandle !== null) {
      clearInterval(this.statusPollHandle);
      this.statusPollHandle = null;
    }
  }

  /**
   * DOM 要素にイベントリスナーをバインドする。
   */
  bind(): void {
    this.bindPlaybackButtons();
    this.bindSpeedButtons();
    this.bindWaypointButtons();
    this.bindGpxImport();
    this.bindMapClick();
    this.startStatusPolling();

    this.engine.onStateChange((state) => this.updateUI(state));
    this.updateUI(this.engine.getState());

    // 保存済み waypoints があれば復元表示
    if (this.waypoints.length > 0) {
      this.renderWaypointList();
      updateWaypointMarkers(this.waypoints);
    }
  }

  private bindPlaybackButtons(): void {
    document.getElementById("btn-play")!.addEventListener("click", () => {
      const state = this.engine.getState();
      if (state.mode === "manual" && this.engine.getRoutePath().length >= 2) {
        this.engine.resumeRoute();
      } else if (state.playback === "paused") {
        this.engine.play();
      } else if (this.waypoints.length >= 2) {
        this.startRouteSimulation();
      }
    });

    document.getElementById("btn-pause")!.addEventListener("click", () => {
      this.engine.pause();
    });

    document.getElementById("btn-stop")!.addEventListener("click", () => {
      this.engine.stop();
      clearRoute();
    });

    document.getElementById("btn-restart")!.addEventListener("click", () => {
      this.engine.restart();
    });
  }

  private bindSpeedButtons(): void {
    const buttons = document.querySelectorAll<HTMLButtonElement>(".speed-btn");
    for (const btn of buttons) {
      btn.addEventListener("click", () => {
        const speed = parseFloat(btn.dataset.speed ?? "1");
        this.engine.setSpeedMultiplier(speed);
        for (const other of buttons) {
          other.classList.toggle("active", other === btn);
        }
      });
    }
  }

  private bindWaypointButtons(): void {
    document.getElementById("btn-add-via")!.addEventListener("click", () => {
      // 次のクリックで via を追加するモードに (UI 表示で誘導)
      // 実際には地図クリックで追加されるため no-op
    });

    document.getElementById("btn-find-route")!.addEventListener("click", () => {
      this.startRouteSimulation();
    });

    document.getElementById("btn-clear")!.addEventListener("click", () => {
      this.waypoints = [];
      this.saveWaypoints();
      this.engine.stop();
      clearRoute();
      this.renderWaypointList();
      updateWaypointMarkers([]);
    });
  }

  private bindGpxImport(): void {
    const fileInput = document.getElementById("gpx-file-input") as HTMLInputElement;

    document.getElementById("btn-load-gpx")!.addEventListener("click", () => {
      fileInput.click();
    });

    fileInput.addEventListener("change", () => {
      const file = fileInput.files?.[0];
      if (file) this.loadGpxFile(file);
      fileInput.value = "";
    });

    // Drag & Drop
    const dropZone = document.getElementById("drop-zone")!;
    dropZone.addEventListener("dragover", (event) => {
      event.preventDefault();
      dropZone.classList.add("dragover");
    });
    dropZone.addEventListener("dragleave", () => {
      dropZone.classList.remove("dragover");
    });
    dropZone.addEventListener("drop", (event) => {
      event.preventDefault();
      dropZone.classList.remove("dragover");
      const file = event.dataTransfer?.files[0];
      if (file?.name.endsWith(".gpx")) {
        this.loadGpxFile(file);
      }
    });
  }

  private bindMapClick(): void {
    onMapClick((latLng: LatLng) => {
      this.waypoints.push(latLng);
      this.saveWaypoints();
      this.renderWaypointList();
      updateWaypointMarkers(this.waypoints);
    });
  }

  private async startRouteSimulation(): Promise<void> {
    if (this.waypoints.length < 2) return;

    try {
      const path = await findRoute(this.waypoints);
      if (path.length >= 2) {
        this.engine.startRoute(path);
      }
    } catch (error) {
      console.error("Route search failed:", error);
    }
  }

  private async loadGpxFile(file: File): Promise<void> {
    try {
      const text = await file.text();
      const points = parseGpx(text);
      if (points.length < 2) {
        console.error("GPX file has insufficient track points");
        return;
      }
      showGpxPath(points);
      this.engine.startGpx(points);
    } catch (error) {
      console.error("Failed to load GPX:", error);
    }
  }

  private renderWaypointList(): void {
    const container = document.getElementById("waypoint-list")!;
    container.innerHTML = "";

    for (let index = 0; index < this.waypoints.length; index++) {
      const wp = this.waypoints[index];
      const label = index === 0 ? "Start" : index === this.waypoints.length - 1 && this.waypoints.length > 1 ? "End" : `Via ${index}`;

      const item = document.createElement("div");
      item.className = "waypoint-item";
      item.innerHTML = `
        <span class="label">${label}</span>
        <span class="name">${wp.lat.toFixed(5)}, ${wp.lng.toFixed(5)}</span>
        <button class="remove-btn" data-index="${index}">&times;</button>
      `;

      const removeBtn = item.querySelector(".remove-btn")!;
      removeBtn.addEventListener("click", () => {
        this.waypoints.splice(index, 1);
        this.saveWaypoints();
        this.renderWaypointList();
        updateWaypointMarkers(this.waypoints);
      });

      container.appendChild(item);
    }
  }

  private startStatusPolling(): void {
    this.statusPollHandle = setInterval(async () => {
      const status = await getStatus();
      const el = document.getElementById("connection-status")!;
      if (status?.active) {
        el.textContent = "\u25CF Connected";
        el.className = "connected";
      } else {
        el.textContent = "\u25CF Disconnected";
        el.className = "disconnected";
      }
    }, 3000);
  }

  private saveWaypoints(): void {
    try {
      localStorage.setItem(WAYPOINTS_STORAGE_KEY, JSON.stringify(this.waypoints));
    } catch {
      // localStorage が使えない環境は無視
    }
  }

  private loadWaypoints(): void {
    try {
      const stored = localStorage.getItem(WAYPOINTS_STORAGE_KEY);
      if (stored) {
        this.waypoints = JSON.parse(stored) as LatLng[];
      }
    } catch {
      this.waypoints = [];
    }
  }

  private updateUI(state: SimulationState): void {
    // Progress bar
    const fill = document.getElementById("progress-fill")!;
    fill.style.width = `${state.progress * 100}%`;

    // Position info
    if (state.position) {
      document.getElementById("position-info")!.textContent =
        `${state.position.lat.toFixed(6)}, ${state.position.lng.toFixed(6)}`;
      document.getElementById("bearing-info")!.textContent =
        `Bearing: ${state.bearing.toFixed(0)}\u00B0`;
      document.getElementById("speed-info")!.textContent =
        `Speed: ${state.speedKmh.toFixed(0)} km/h`;

      updateCurrentPosition(state.position, state.bearing);
    }

    // Mode info
    const modeLabels: Record<string, string> = {
      idle: "Idle",
      route: "Route Playback",
      gpx: "GPX Playback",
      manual: "Manual Control",
    };
    const playbackLabels: Record<string, string> = {
      stopped: "",
      playing: " \u25B6",
      paused: " \u23F8",
    };
    document.getElementById("mode-info")!.textContent =
      `Mode: ${modeLabels[state.mode] ?? state.mode}${playbackLabels[state.playback] ?? ""}`;
  }
}
