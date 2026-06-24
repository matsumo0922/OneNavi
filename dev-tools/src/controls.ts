import type {SimulationEngine, SimulationState} from "./simulation";
import type {LatLng} from "./geo-utils";
import {parseGpx} from "./gpx-parser";
import {getStatus} from "./connection";
import {deleteRoute, listRoutes, saveRoute, type SavedRoute} from "./routes";
import {activeProvider, setActiveProvider, type ProviderId} from "./providers";
import {createOptionsForm} from "./options/form";
import {HERE_ROUTING_GROUPS} from "./options/here-routing-schema";
import {getHereRoutingOptions, setHereRoutingOptions} from "./options/store";
import {hideRouteDetail, showRouteDetail} from "./route-detail";
import {
  clearRoute,
  findRoute,
  hideRoutePointCallout,
  onRoutePointSelected,
  showGpxPath,
  showRoutePointCallout,
  type RoutePointCandidate,
  updateCurrentPosition,
  updateWaypointMarkers,
} from "./map";

/**
 * UI コントロールのバインディングとイベントハンドリングを行う。
 */
const WAYPOINTS_STORAGE_KEY = "onenavi-dev-waypoints";

export class ControlsManager {
  private waypoints: LatLng[] = [];
  private savedRoutes: SavedRoute[] = [];
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
    this.bindRateButtons();
    this.bindProviderToggle();
    this.bindRouteOptions();
    this.bindWaypointButtons();
    this.bindSavedRoutes();
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

    // 保存ルート一覧を取得して表示
    void this.refreshSavedRoutes();
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

  private bindRateButtons(): void {
    const buttons = document.querySelectorAll<HTMLButtonElement>(".rate-btn");
    for (const button of buttons) {
      button.addEventListener("click", () => {
        const hz = parseFloat(button.dataset.hz ?? "10");
        this.engine.setTickIntervalMs(1000 / hz);
        for (const other of buttons) {
          other.classList.toggle("active", other === button);
        }
      });
    }
  }

  private bindProviderToggle(): void {
    const toggle = document.getElementById("provider-toggle");
    if (!toggle) return;

    const buttons = toggle.querySelectorAll<HTMLButtonElement>(".provider-btn");
    const syncActive = (): void => {
      const current = activeProvider().id;
      for (const button of buttons) {
        button.classList.toggle("active", button.dataset.provider === current);
      }
      document.documentElement.style.setProperty("--provider-accent", activeProvider().accent);
    };

    for (const button of buttons) {
      button.addEventListener("click", () => {
        const id = button.dataset.provider as ProviderId;
        if (id === activeProvider().id) return;

        setActiveProvider(id);
        syncActive();

        // 既にルートが引かれていれば、新プロバイダで即引き直す
        if (this.waypoints.length >= 2) {
          void this.startRouteSimulation();
        }
      });
    }

    syncActive();
  }

  /**
   * HERE ルート検索オプションの編集フォームを構築し、折りたたみを制御する。
   */
  private bindRouteOptions(): void {
    const body = document.getElementById("route-options-body");
    const toggle = document.getElementById("route-options-toggle");
    if (!body || !toggle) return;

    const form = createOptionsForm(
      HERE_ROUTING_GROUPS,
      getHereRoutingOptions(),
      (values) => setHereRoutingOptions(values),
    );
    body.appendChild(form.element);

    toggle.addEventListener("click", () => {
      const expanded = toggle.getAttribute("aria-expanded") === "true";
      toggle.setAttribute("aria-expanded", String(!expanded));
      body.hidden = expanded;
    });
  }

  private bindWaypointButtons(): void {
    document.getElementById("btn-find-route")!.addEventListener("click", () => {
      this.startRouteSimulation();
    });

    document.getElementById("btn-clear")!.addEventListener("click", () => {
      hideRoutePointCallout();
      hideRouteDetail();
      this.waypoints = [];
      this.saveWaypoints();
      this.engine.stop();
      clearRoute();
      this.renderWaypointList();
      updateWaypointMarkers([]);
    });
  }

  private bindSavedRoutes(): void {
    document.getElementById("btn-save-route")!.addEventListener("click", () => {
      void this.saveCurrentRoute();
    });
  }

  /**
   * 現在の waypoints を名前付きで保存する。
   */
  private async saveCurrentRoute(): Promise<void> {
    if (this.waypoints.length < 2) {
      window.alert("スタートとエンド（2 点以上）を指定してください");
      return;
    }
    const name = window.prompt("ルート名を入力");
    if (!name?.trim()) return;

    const saved = await saveRoute(name.trim(), this.waypoints);
    if (!saved) {
      window.alert("保存に失敗しました（dev server に接続できません）");
      return;
    }
    await this.refreshSavedRoutes();
  }

  /**
   * 保存ルートを現在の waypoints として読み込む。
   */
  private loadSavedRoute(route: SavedRoute): void {
    this.waypoints = route.waypoints.map((waypoint) => ({ lat: waypoint.lat, lng: waypoint.lng }));
    this.saveWaypoints();
    this.engine.stop();
    clearRoute();
    hideRoutePointCallout();
    this.renderWaypointList();
    updateWaypointMarkers(this.waypoints);
  }

  private async removeSavedRoute(route: SavedRoute): Promise<void> {
    if (!window.confirm(`「${route.name}」を削除しますか?`)) return;
    await deleteRoute(route.file);
    await this.refreshSavedRoutes();
  }

  private async refreshSavedRoutes(): Promise<void> {
    this.savedRoutes = await listRoutes();
    this.renderSavedRoutes();
  }

  private renderSavedRoutes(): void {
    const container = document.getElementById("saved-route-list")!;
    container.innerHTML = "";

    for (const route of this.savedRoutes) {
      const item = document.createElement("div");
      item.className = "saved-route-item";

      const loadButton = document.createElement("button");
      loadButton.className = "load-btn";
      loadButton.title = "Load route";
      loadButton.textContent = route.name;
      loadButton.addEventListener("click", () => this.loadSavedRoute(route));

      const count = document.createElement("span");
      count.className = "count";
      count.textContent = `${route.waypoints.length}`;
      count.title = `${route.waypoints.length} points`;

      const removeButton = document.createElement("button");
      removeButton.className = "remove-btn";
      removeButton.title = "Delete route";
      removeButton.innerHTML = "&times;";
      removeButton.addEventListener("click", () => {
        void this.removeSavedRoute(route);
      });

      item.append(loadButton, count, removeButton);
      container.appendChild(item);
    }
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
    onRoutePointSelected((candidate) => {
      showRoutePointCallout(candidate, this.waypoints, (insertIndex, insertedCandidate) => {
        this.insertRoutePoint(insertIndex, insertedCandidate);
      });
    });
  }

  private insertRoutePoint(insertIndex: number, candidate: RoutePointCandidate): void {
    this.waypoints.splice(insertIndex, 0, candidate.position);
    this.saveWaypoints();
    this.renderWaypointList();
    updateWaypointMarkers(this.waypoints);
  }

  private async startRouteSimulation(): Promise<void> {
    if (this.waypoints.length < 2) return;

    try {
      const result = await findRoute(this.waypoints);
      if (result.coords.length >= 2) {
        this.engine.startRoute(result.coords);
      }

      // 詳細パネルは HERE 固有データ（spans/tolls/snaps）を扱うため HERE 時のみ表示
      if (activeProvider().id === "here") {
        showRouteDetail(result.raw);
      } else {
        hideRouteDetail();
      }
    } catch (error) {
      console.error("Route search failed:", error);
      window.alert(
        `ルート探索に失敗しました\n${error instanceof Error ? error.message : String(error)}`,
      );
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
      const waypoint = this.waypoints[index];
      const label = this.getWaypointLabel(index);
      const coordinates = this.formatWaypointCoordinates(waypoint);

      const item = document.createElement("div");
      item.className = "waypoint-item";

      const labelElement = document.createElement("span");
      labelElement.className = "label";
      labelElement.textContent = label;

      const nameElement = document.createElement("span");
      nameElement.className = "name";
      nameElement.textContent = `${waypoint.lat.toFixed(5)}, ${waypoint.lng.toFixed(5)}`;

      const copyButton = document.createElement("button");
      copyButton.className = "copy-btn";
      copyButton.type = "button";
      copyButton.title = "Copy coordinates";
      copyButton.ariaLabel = `Copy ${label} coordinates`;
      copyButton.textContent = "\u29c9";
      copyButton.addEventListener("click", () => {
        void this.copyWaypointCoordinates(coordinates, copyButton);
      });

      const removeButton = document.createElement("button");
      removeButton.className = "remove-btn";
      removeButton.type = "button";
      removeButton.title = "Remove waypoint";
      removeButton.ariaLabel = `Remove ${label}`;
      removeButton.textContent = "\u00d7";
      removeButton.addEventListener("click", () => {
        hideRoutePointCallout();
        this.waypoints.splice(index, 1);
        this.saveWaypoints();
        this.renderWaypointList();
        updateWaypointMarkers(this.waypoints);
      });

      item.append(labelElement, nameElement, copyButton, removeButton);
      container.appendChild(item);
    }
  }

  private getWaypointLabel(index: number): string {
    const isFirstWaypoint = index === 0;
    if (isFirstWaypoint) return "Start";

    const isLastWaypoint = index === this.waypoints.length - 1;
    const hasMultipleWaypoints = this.waypoints.length > 1;
    if (isLastWaypoint && hasMultipleWaypoints) return "End";

    return `Via ${index}`;
  }

  private formatWaypointCoordinates(waypoint: LatLng): string {
    return `${waypoint.lat},${waypoint.lng}`;
  }

  private async copyWaypointCoordinates(coordinates: string, button: HTMLButtonElement): Promise<void> {
    const didCopy = await this.writeClipboardText(coordinates);
    if (!didCopy) {
      window.alert("座標のコピーに失敗しました");
      return;
    }

    this.showCopiedFeedback(button);
  }

  private async writeClipboardText(text: string): Promise<boolean> {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      return this.writeClipboardTextWithFallback(text);
    }
  }

  private writeClipboardTextWithFallback(text: string): boolean {
    const textArea = document.createElement("textarea");
    textArea.value = text;
    textArea.readOnly = true;
    textArea.style.position = "fixed";
    textArea.style.top = "-1000px";

    document.body.appendChild(textArea);
    textArea.select();

    try {
      return document.execCommand("copy");
    } finally {
      document.body.removeChild(textArea);
    }
  }

  private showCopiedFeedback(button: HTMLButtonElement): void {
    const originalTitle = button.title;
    button.classList.add("copied");
    button.title = "Copied";

    window.setTimeout(() => {
      button.classList.remove("copied");
      button.title = originalTitle;
    }, 1200);
  }

  private startStatusPolling(): void {
    this.statusPollHandle = setInterval(async () => {
      const status = await getStatus();
      const el = document.getElementById("connection-status")!;
      if (status?.active) {
        el.textContent = "\u25CF Connected";
        el.className = "badge connected";
      } else {
        el.textContent = "\u25CF Disconnected";
        el.className = "badge disconnected";
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
        `${state.bearing.toFixed(0)}\u00B0`;
      document.getElementById("speed-info")!.textContent =
        `${state.speedKmh.toFixed(0)} km/h`;

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
      `${modeLabels[state.mode] ?? state.mode}${playbackLabels[state.playback] ?? ""}`;
  }
}
