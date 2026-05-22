import "./style.css";

/** デザインが必ず実装すべきビューポート（画面サイズ）の定義。 */
interface ViewportDef {
  /** ファイル名と対応するキー（例: "phone-portrait"）。 */
  key: string;
  /** UI 上の表示ラベル。 */
  label: string;
  /** 実寸の幅(px)。iframe はこの寸法で描画してから縮小する。 */
  width: number;
  /** 実寸の高さ(px)。 */
  height: number;
}

/** designs/<id>/meta.json のスキーマ。 */
interface DesignMeta {
  /** デザインのタイトル。 */
  title: string;
  /** デザインの説明文。 */
  description: string;
}

/** 解決済みの1デザイン。viewports は key→iframe 用 URL（未実装は undefined）。 */
interface Design {
  /** designs/ 直下のフォルダ名。URL ハッシュにも使う。 */
  id: string;
  /** meta.json の内容。 */
  meta: DesignMeta;
  /** ビューポート key ごとの HTML URL。 */
  viewports: Record<string, string | undefined>;
}

/** ズーム表示モード。fit=枠に収まるよう縮小 / actual=実寸100%。 */
type ZoomMode = "fit" | "actual";

const VIEWPORTS: ViewportDef[] = [
  {key: "phone-portrait", label: "Phone · Portrait", width: 412, height: 915},
  {key: "phone-landscape", label: "Phone · Landscape", width: 915, height: 412},
  {key: "tablet-portrait", label: "Tablet · Portrait", width: 800, height: 1280},
  {key: "tablet-landscape", label: "Tablet · Landscape", width: 1280, height: 800},
];

const FIT_MAX_WIDTH = 480;
const FIT_MAX_HEIGHT = 760;
const PREVIEW_WIDTH = 220;

// designs/<id>/meta.json を全件 eager 読み込み（JSON は default export がオブジェクト）。
const metaModules = import.meta.glob("../designs/*/meta.json", {
  eager: true,
  import: "default",
}) as Record<string, DesignMeta>;

// designs/<id>/<key>.html を URL として eager 読み込み。存在するファイルのみ列挙される。
const htmlModules = import.meta.glob("../designs/*/*.html", {
  eager: true,
  query: "?url",
  import: "default",
}) as Record<string, string>;

let zoomMode: ZoomMode = "fit";
const designs = buildDesigns();

/** glob の結果からデザイン一覧を構築する。フォルダ名を id として束ねる。 */
function buildDesigns(): Design[] {
  const byId = new Map<string, Design>();

  for (const [path, meta] of Object.entries(metaModules)) {
    const id = path.replace("../designs/", "").replace("/meta.json", "");
    byId.set(id, {id, meta, viewports: {}});
  }

  for (const [path, url] of Object.entries(htmlModules)) {
    const relative = path.replace("../designs/", "");
    const separatorIndex = relative.indexOf("/");
    const id = relative.slice(0, separatorIndex);
    const fileKey = relative.slice(separatorIndex + 1).replace(".html", "");
    const design = byId.get(id);
    if (design) {
      design.viewports[fileKey] = url;
    }
  }

  return [...byId.values()].sort((first, second) => first.id.localeCompare(second.id));
}

/** className / textContent をまとめて指定できる createElement のラッパー。 */
function el(tag: string, className?: string, text?: string): HTMLElement {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/** fit モードでビューポートを枠に収めるための拡縮率。actual は常に 1。 */
function scaleFor(viewport: ViewportDef, mode: ZoomMode): number {
  if (mode === "actual") {
    return 1;
  }
  return Math.min(FIT_MAX_WIDTH / viewport.width, FIT_MAX_HEIGHT / viewport.height, 1);
}

/** 1ビューポート分の描画領域。url が無ければ「未実装」プレースホルダを返す。 */
function renderStage(viewport: ViewportDef, url: string | undefined, scale: number): HTMLElement {
  const stage = el("div", "stage");
  stage.style.width = `${Math.round(viewport.width * scale)}px`;
  stage.style.height = `${Math.round(viewport.height * scale)}px`;

  if (url) {
    const frame = document.createElement("iframe");
    frame.className = "stage-frame";
    frame.src = url;
    frame.style.width = `${viewport.width}px`;
    frame.style.height = `${viewport.height}px`;
    frame.style.transform = `scale(${scale})`;
    frame.setAttribute("loading", "lazy");
    stage.appendChild(frame);
  } else {
    stage.classList.add("stage-empty");
    stage.appendChild(el("span", "stage-empty-label", "未実装"));
  }

  return stage;
}

/** 共通トップバー。戻るリンクや右側ノードを差し込める。 */
function renderTopbar(title: string, options: {back?: boolean; subtitle?: string; right?: HTMLElement}): HTMLElement {
  const header = el("header", "topbar");

  if (options.back) {
    const back = el("a", "back-link", "← 一覧");
    (back as HTMLAnchorElement).href = "#/";
    header.appendChild(back);
  }

  const titles = el("div", "topbar-titles");
  titles.appendChild(el("h1", "topbar-title", title));
  if (options.subtitle) {
    titles.appendChild(el("p", "topbar-desc", options.subtitle));
  }
  header.appendChild(titles);

  if (options.right) {
    header.appendChild(options.right);
  }

  return header;
}

/** 一覧画面。各デザインをカードで並べ、phone-portrait をサムネイルにする。 */
function renderList(root: HTMLElement): void {
  const counter = el("span", "topbar-count", `${designs.length} designs`);
  root.appendChild(renderTopbar("OneNavi UI Playground", {right: counter}));

  if (designs.length === 0) {
    const empty = el("div", "page-empty");
    empty.appendChild(el("p", undefined, "デザインがまだありません。"));
    empty.appendChild(el("p", "muted", "designs/<id>/ に meta.json と *.html を追加してください。"));
    root.appendChild(empty);
    return;
  }

  const phonePortrait = VIEWPORTS[0];
  const previewScale = PREVIEW_WIDTH / phonePortrait.width;

  const grid = el("div", "card-grid");
  for (const design of designs) {
    const card = el("a", "card");
    (card as HTMLAnchorElement).href = `#/${design.id}`;

    const preview = el("div", "card-preview");
    preview.appendChild(renderStage(phonePortrait, design.viewports[phonePortrait.key], previewScale));
    card.appendChild(preview);

    const body = el("div", "card-body");
    body.appendChild(el("h2", "card-title", design.meta.title));
    body.appendChild(el("p", "card-desc", design.meta.description));

    const badges = el("div", "badge-row");
    for (const viewport of VIEWPORTS) {
      const implemented = Boolean(design.viewports[viewport.key]);
      badges.appendChild(el("span", `badge ${implemented ? "badge-on" : "badge-off"}`, viewport.label.replace(" · ", " ")));
    }
    body.appendChild(badges);

    card.appendChild(body);
    grid.appendChild(card);
  }
  root.appendChild(grid);
}

/** 詳細画面。4ビューポートを並べて表示する。未実装は空枠で表示。 */
function renderDetail(root: HTMLElement, design: Design): void {
  const zoomButton = el("button", "zoom-btn", zoomMode === "fit" ? "Fit" : "100%");
  zoomButton.addEventListener("click", () => {
    zoomMode = zoomMode === "fit" ? "actual" : "fit";
    render();
  });

  root.appendChild(renderTopbar(design.meta.title, {back: true, subtitle: design.meta.description, right: zoomButton}));

  const grid = el("div", "viewport-grid");
  for (const viewport of VIEWPORTS) {
    const url = design.viewports[viewport.key];

    const panel = el("section", "viewport-panel");
    const head = el("div", "viewport-head");
    head.appendChild(el("span", "viewport-label", viewport.label));
    head.appendChild(el("span", "viewport-dims", `${viewport.width}×${viewport.height}`));
    if (!url) {
      head.appendChild(el("span", "viewport-tag", "empty"));
    }
    panel.appendChild(head);

    const scroller = el("div", "viewport-scroller");
    scroller.appendChild(renderStage(viewport, url, scaleFor(viewport, zoomMode)));
    panel.appendChild(scroller);

    grid.appendChild(panel);
  }
  root.appendChild(grid);
}

/** ハッシュルーティング。空=一覧、#/<id>=詳細。未知 id は一覧へ戻す。 */
function render(): void {
  const root = document.getElementById("app");
  if (!root) {
    return;
  }
  root.innerHTML = "";

  const id = decodeURIComponent(location.hash.replace(/^#\/?/, ""));
  if (!id) {
    renderList(root);
    return;
  }

  const design = designs.find((candidate) => candidate.id === id);
  if (!design) {
    renderList(root);
    return;
  }
  renderDetail(root, design);
}

window.addEventListener("hashchange", render);
render();
