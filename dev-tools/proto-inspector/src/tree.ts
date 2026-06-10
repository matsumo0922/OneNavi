import { type DecodedField, type DecodedValue, pathToKey } from "./decoder";
import type { AnnotationFile, FieldAnnotation } from "./annotations";

/** ツリー上で選択中の field と注釈の編集状態をやり取りする callback 群。 */
export interface TreeCallbacks {
  /** field が選択されたとき。詳細パネルに表示する。 */
  onSelect(field: DecodedField): void;
}

/**
 * 検索結果を保持する。`includeIds` は「マッチ + マッチの祖先」の物理行 ID 集合。
 * `matchedIds` は「直接マッチした field の物理行 ID」。`matchLabels` は
 * 各マッチ行で「どの属性 (name / zigzag / hex 等) に query が含まれていたか」を示す
 * (可視テキストにマッチが見えないケースの補助表示用)。
 */
export interface TreeFilter {
  query: string;
  includeIds: Set<number>;
  matchedIds: Set<number>;
  matchLabels: Map<number, string[]>;
}

const WIRE_TYPE_LABELS: Record<number, string> = {
  0: "VARINT",
  1: "I64",
  2: "LEN",
  5: "I32",
};

/** field の値を 1 行サマリーに変換する（ツリーの行内表示用）。 */
export function summarizeValue(value: DecodedValue): string {
  switch (value.kind) {
    case "varint": {
      const parts = [`u=${value.unsigned}`];
      if (value.signed !== value.unsigned) parts.push(`s=${value.signed}`);
      if (value.asBool !== null) parts.push(`bool=${value.asBool}`);
      return parts.join(" / ");
    }
    case "i64":
      return `u=${value.asUint} double=${value.asDouble}`;
    case "i32":
      return `u=${value.asUint} float=${value.asFloat}`;
    case "string":
      return JSON.stringify(value.text.length > 60 ? `${value.text.slice(0, 60)}…` : value.text);
    case "bytes":
      return `<bytes len=${value.length} hex=${value.hex.length > 40 ? `${value.hex.slice(0, 40)}…` : value.hex}>`;
    case "packed":
      return `[packed × ${value.values.length}] ${value.values.slice(0, 5).join(", ")}${value.values.length > 5 ? "…" : ""}`;
    case "message":
      return `{ ${value.fields.length} fields, conf=${value.confidence} }`;
  }
}

function lookupAnnotation(annotations: AnnotationFile, field: DecodedField): FieldAnnotation | undefined {
  return annotations.fields[pathToKey(field.path)];
}

/**
 * field を検索対象トークン群に展開する。検索成立時に「どこにマッチしたか」を
 * 表示するため、ラベル付きで返す。
 */
interface HaystackPart {
  label: string;
  text: string;
}

function buildHaystackParts(field: DecodedField, annotation: FieldAnnotation | undefined): HaystackPart[] {
  const parts: HaystackPart[] = [
    { label: "path", text: pathToKey(field.path) },
    { label: "field", text: `#${field.fieldNumber}` },
    { label: "summary", text: summarizeValue(field.value) },
  ];
  if (annotation?.name) parts.push({ label: "name", text: annotation.name });
  if (annotation?.description) parts.push({ label: "description", text: annotation.description });
  if (annotation?.typeHint) parts.push({ label: "typeHint", text: annotation.typeHint });
  const value = field.value;
  switch (value.kind) {
    case "varint":
      parts.push({ label: "unsigned", text: value.unsigned });
      if (value.signed !== value.unsigned) parts.push({ label: "signed", text: value.signed });
      parts.push({ label: "zigzag", text: value.zigzag });
      if (value.asBool !== null) parts.push({ label: "bool", text: String(value.asBool) });
      break;
    case "i64":
      parts.push({ label: "uint64", text: value.asUint });
      parts.push({ label: "int64", text: value.asInt });
      parts.push({ label: "double", text: String(value.asDouble) });
      parts.push({ label: "hex", text: value.hex });
      break;
    case "i32":
      parts.push({ label: "uint32", text: String(value.asUint) });
      parts.push({ label: "int32", text: String(value.asInt) });
      parts.push({ label: "float", text: String(value.asFloat) });
      parts.push({ label: "hex", text: value.hex });
      break;
    case "string":
      parts.push({ label: "text", text: value.text });
      break;
    case "bytes":
      parts.push({ label: "hex", text: value.hex });
      break;
    case "packed":
      parts.push({ label: "packed", text: value.values.join(" ") });
      break;
    case "message":
      // 子は再帰で別途検査される。サマリは含めない。
      break;
  }
  return parts;
}

/** 大小無視で query を含むラベル一覧を返す。 */
function matchLabels(parts: HaystackPart[], query: string): string[] {
  const result: string[] = [];
  for (const part of parts) {
    if (part.text.toLowerCase().includes(query)) result.push(part.label);
  }
  return result;
}

/** field およびその全 descendant を include に加える (subtree 取り込み)。 */
function includeSubtree(field: DecodedField, includeIds: Set<number>): void {
  includeIds.add(field.id);
  if (field.value.kind === "message") {
    for (const child of field.value.fields) {
      includeSubtree(child, includeIds);
    }
  }
}

export function buildFilter(
  fields: DecodedField[],
  annotations: AnnotationFile,
  query: string,
): TreeFilter | null {
  const trimmed = query.trim().toLowerCase();
  if (trimmed === "") return null;
  const includeIds = new Set<number>();
  const matchedIds = new Set<number>();
  const labels = new Map<number, string[]>();

  /**
   * `parent` は呼び出し側 (1 つ上の message field)。root 直下なら null。
   * マッチ時に parent の subtree を丸ごと include することで「兄弟」と
   * 「マッチした message の descendant」の両方が見えるようにする。
   */
  function visit(field: DecodedField, ancestorIds: number[], parent: DecodedField | null): boolean {
    const annotation = lookupAnnotation(annotations, field);
    const parts = buildHaystackParts(field, annotation);
    const hits = matchLabels(parts, trimmed);
    const selfMatch = hits.length > 0;
    let descendantMatch = false;
    if (field.value.kind === "message") {
      const nextAncestors = [...ancestorIds, field.id];
      for (const child of field.value.fields) {
        if (visit(child, nextAncestors, field)) descendantMatch = true;
      }
    }
    if (selfMatch) {
      matchedIds.add(field.id);
      labels.set(field.id, hits);
      // 同列 (siblings) と 以下 (descendants) を見られるよう、親の subtree を丸ごと include。
      // 親が無ければ自身の subtree のみ取り込む。
      if (parent) {
        includeSubtree(parent, includeIds);
      } else {
        includeSubtree(field, includeIds);
      }
    }
    if (selfMatch || descendantMatch) {
      includeIds.add(field.id);
      for (const ancestorId of ancestorIds) includeIds.add(ancestorId);
      return true;
    }
    return false;
  }

  for (const field of fields) {
    visit(field, [], null);
  }
  return { query: trimmed, includeIds, matchedIds, matchLabels: labels };
}

/** 大小無視で query にマッチする部分を `<mark>` で囲んだ DOM を返す。 */
function makeHighlightedText(text: string, query: string | null): DocumentFragment {
  const fragment = document.createDocumentFragment();
  if (!query) {
    fragment.appendChild(document.createTextNode(text));
    return fragment;
  }
  const lower = text.toLowerCase();
  let cursor = 0;
  while (cursor < text.length) {
    const next = lower.indexOf(query, cursor);
    if (next < 0) {
      fragment.appendChild(document.createTextNode(text.slice(cursor)));
      break;
    }
    if (next > cursor) {
      fragment.appendChild(document.createTextNode(text.slice(cursor, next)));
    }
    const mark = document.createElement("mark");
    mark.textContent = text.slice(next, next + query.length);
    fragment.appendChild(mark);
    cursor = next + query.length;
  }
  return fragment;
}

/** 可視テキストを差し替えるユーティリティ。 */
function setHighlighted(element: HTMLElement, text: string, query: string | null): void {
  element.textContent = "";
  element.appendChild(makeHighlightedText(text, query));
}

function renderField(
  field: DecodedField,
  annotations: AnnotationFile,
  callbacks: TreeCallbacks,
  filter: TreeFilter | null,
): HTMLLIElement | null {
  if (filter && !filter.includeIds.has(field.id)) return null;

  const pathKey = pathToKey(field.path);
  const annotation = lookupAnnotation(annotations, field);
  const li = document.createElement("li");
  li.className = "tree-node";

  const row = document.createElement("div");
  row.className = "tree-row";
  const isSelfMatched = filter ? filter.matchedIds.has(field.id) : false;
  if (filter && !isSelfMatched) row.classList.add("ancestor");
  if (isSelfMatched) row.classList.add("matched");
  row.tabIndex = 0;
  row.dataset.path = pathKey;
  row.dataset.id = String(field.id);

  const query = filter?.query ?? null;
  const isExpandable = field.value.kind === "message";

  const toggle = document.createElement("span");
  toggle.className = `tree-toggle${isExpandable ? "" : " tree-toggle-leaf"}`;
  toggle.textContent = isExpandable ? "▶" : "•";
  row.appendChild(toggle);

  const tag = document.createElement("span");
  tag.className = "tree-tag";
  setHighlighted(tag, `#${field.fieldNumber}`, query);
  row.appendChild(tag);

  const wire = document.createElement("span");
  wire.className = `tree-wire wire-${field.wireType}`;
  wire.textContent = WIRE_TYPE_LABELS[field.wireType] ?? `?${field.wireType}`;
  row.appendChild(wire);

  const name = document.createElement("span");
  name.className = "tree-name";
  const nameText = annotation?.name ?? "(unnamed)";
  setHighlighted(name, nameText, query);
  if (!annotation?.name) name.classList.add("tree-name-empty");
  row.appendChild(name);

  const summary = document.createElement("span");
  summary.className = "tree-summary";
  setHighlighted(summary, summarizeValue(field.value), query);
  row.appendChild(summary);

  if (isSelfMatched && filter) {
    const labelsForRow = filter.matchLabels.get(field.id) ?? [];
    // 可視テキストにマッチが見えない場合のみ、どこにマッチしたかを補助表示
    const summaryText = summarizeValue(field.value).toLowerCase();
    const hasVisibleHit =
      summaryText.includes(filter.query) ||
      nameText.toLowerCase().includes(filter.query) ||
      pathKey.toLowerCase().includes(filter.query) ||
      `#${field.fieldNumber}`.includes(filter.query);
    const hidden = labelsForRow.filter((label) => !["field", "name", "path", "summary"].includes(label));
    if (!hasVisibleHit && hidden.length > 0) {
      const hint = document.createElement("span");
      hint.className = "tree-match-hint";
      hint.textContent = `match: ${hidden.join(",")}`;
      row.appendChild(hint);
    }
  }

  const select = (): void => {
    document.querySelectorAll(".tree-row.selected").forEach((node) => node.classList.remove("selected"));
    row.classList.add("selected");
    callbacks.onSelect(field);
  };
  row.addEventListener("click", (event) => {
    event.stopPropagation();
    if (event.target === toggle && isExpandable) {
      toggleChildren();
      return;
    }
    select();
  });
  row.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      select();
    } else if (isExpandable && (event.key === "ArrowRight" || event.key === "ArrowLeft")) {
      event.preventDefault();
      toggleChildren(event.key === "ArrowRight" ? true : false);
    }
  });

  li.appendChild(row);

  let childrenContainer: HTMLUListElement | null = null;
  let expanded = false;
  const autoExpand = filter !== null && isExpandable;

  function toggleChildren(force?: boolean): void {
    if (!isExpandable) return;
    const next = force ?? !expanded;
    if (next === expanded) return;
    expanded = next;
    if (expanded) {
      if (!childrenContainer) {
        childrenContainer = document.createElement("ul");
        childrenContainer.className = "tree-children";
        if (field.value.kind === "message") {
          for (const child of field.value.fields) {
            const childEl = renderField(child, annotations, callbacks, filter);
            if (childEl) childrenContainer.appendChild(childEl);
          }
        }
        li.appendChild(childrenContainer);
      }
      childrenContainer.style.display = "";
      toggle.textContent = "▼";
    } else {
      if (childrenContainer) childrenContainer.style.display = "none";
      toggle.textContent = "▶";
    }
  }

  if (autoExpand) toggleChildren(true);
  return li;
}

export function renderTree(
  container: HTMLElement,
  fields: DecodedField[],
  annotations: AnnotationFile,
  callbacks: TreeCallbacks,
  filter: TreeFilter | null,
): void {
  container.innerHTML = "";
  const root = document.createElement("ul");
  root.className = "tree-root";
  for (const field of fields) {
    const el = renderField(field, annotations, callbacks, filter);
    if (el) root.appendChild(el);
  }
  container.appendChild(root);
}

/**
 * すでに描画済みのツリーで、同じ semantic path を共有する全行の name 表示を更新する
 * (注釈は repeated インスタンスで共有されるため複数行に反映する)。
 */
export function refreshFieldName(container: HTMLElement, pathKey: string, name: string | undefined): void {
  const escaped = pathKey.replace(/"/g, '\\"');
  const rows = container.querySelectorAll<HTMLElement>(`.tree-row[data-path="${escaped}"]`);
  for (const row of rows) {
    const nameEl = row.querySelector<HTMLElement>(".tree-name");
    if (!nameEl) continue;
    nameEl.textContent = name && name.length > 0 ? name : "(unnamed)";
    nameEl.classList.toggle("tree-name-empty", !name || name.length === 0);
  }
}
