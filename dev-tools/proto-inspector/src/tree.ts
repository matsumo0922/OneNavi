import { type DecodedField, type DecodedValue, pathToKey } from "./decoder";
import type { AnnotationFile, FieldAnnotation } from "./annotations";

/** ツリー上で選択中の field と注釈の編集状態をやり取りする callback 群。 */
export interface TreeCallbacks {
  /** field が選択されたとき。詳細パネルに表示する。 */
  onSelect(field: DecodedField): void;
}

/**
 * 検索結果を保持する。`includePaths` は「マッチ自体 + マッチの祖先」、
 * `matchedPaths` は「直接マッチした field の path」。フィルタ未指定なら null。
 */
export interface TreeFilter {
  query: string;
  includePaths: Set<string>;
  matchedPaths: Set<string>;
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

/** 注釈レコードをルックアップする。 */
function lookupAnnotation(annotations: AnnotationFile, pathKey: string): FieldAnnotation | undefined {
  return annotations.fields[pathKey];
}

/** field を検索対象文字列に展開する (小文字化済み)。 */
function buildHaystack(field: DecodedField, annotation: FieldAnnotation | undefined): string {
  const parts: string[] = [pathToKey(field.path), `#${field.fieldNumber}`];
  if (annotation?.name) parts.push(annotation.name);
  if (annotation?.description) parts.push(annotation.description);
  if (annotation?.typeHint) parts.push(annotation.typeHint);
  const value = field.value;
  switch (value.kind) {
    case "varint":
      parts.push(value.unsigned, value.signed, value.zigzag);
      if (value.asBool !== null) parts.push(String(value.asBool));
      break;
    case "i64":
      parts.push(value.asUint, value.asInt, String(value.asDouble), value.hex);
      break;
    case "i32":
      parts.push(String(value.asUint), String(value.asInt), String(value.asFloat), value.hex);
      break;
    case "string":
      parts.push(value.text);
      break;
    case "bytes":
      parts.push(value.hex);
      break;
    case "packed":
      parts.push(value.values.join(" "));
      break;
    case "message":
      // 子は再帰で別途検査される。サマリだけ。
      parts.push(`{${value.fields.length}fields}`);
      break;
  }
  return parts.join(" ").toLowerCase();
}

/**
 * 検索クエリにマッチする path を走査して filter を作る。
 * query が空なら null。
 */
export function buildFilter(
  fields: DecodedField[],
  annotations: AnnotationFile,
  query: string,
): TreeFilter | null {
  const trimmed = query.trim().toLowerCase();
  if (trimmed === "") return null;
  const includePaths = new Set<string>();
  const matchedPaths = new Set<string>();

  function visit(field: DecodedField, ancestors: string[]): boolean {
    const pathKey = pathToKey(field.path);
    const annotation = lookupAnnotation(annotations, pathKey);
    const haystack = buildHaystack(field, annotation);
    let selfMatch = haystack.includes(trimmed);
    let descendantMatch = false;
    if (field.value.kind === "message") {
      const nextAncestors = [...ancestors, pathKey];
      for (const child of field.value.fields) {
        if (visit(child, nextAncestors)) {
          descendantMatch = true;
        }
      }
    }
    if (selfMatch) matchedPaths.add(pathKey);
    if (selfMatch || descendantMatch) {
      includePaths.add(pathKey);
      for (const ancestor of ancestors) includePaths.add(ancestor);
      return true;
    }
    return false;
  }

  for (const field of fields) {
    visit(field, []);
  }
  return { query: trimmed, includePaths, matchedPaths };
}

/** field 1 件分の <li> を描画する (子要素は遅延展開)。 */
function renderField(
  field: DecodedField,
  annotations: AnnotationFile,
  callbacks: TreeCallbacks,
  filter: TreeFilter | null,
): HTMLLIElement | null {
  const pathKey = pathToKey(field.path);
  if (filter && !filter.includePaths.has(pathKey)) return null;

  const annotation = lookupAnnotation(annotations, pathKey);
  const li = document.createElement("li");
  li.className = "tree-node";

  const row = document.createElement("div");
  row.className = "tree-row";
  if (filter && filter.matchedPaths.has(pathKey)) row.classList.add("matched");
  row.tabIndex = 0;
  row.dataset.path = pathKey;

  const isExpandable = field.value.kind === "message";
  const toggle = document.createElement("span");
  toggle.className = `tree-toggle${isExpandable ? "" : " tree-toggle-leaf"}`;
  toggle.textContent = isExpandable ? "▶" : "•";
  row.appendChild(toggle);

  const tag = document.createElement("span");
  tag.className = "tree-tag";
  tag.textContent = `#${field.fieldNumber}`;
  row.appendChild(tag);

  const wire = document.createElement("span");
  wire.className = `tree-wire wire-${field.wireType}`;
  wire.textContent = WIRE_TYPE_LABELS[field.wireType] ?? `?${field.wireType}`;
  row.appendChild(wire);

  const name = document.createElement("span");
  name.className = "tree-name";
  name.textContent = annotation?.name ?? "(unnamed)";
  if (!annotation?.name) name.classList.add("tree-name-empty");
  row.appendChild(name);

  const summary = document.createElement("span");
  summary.className = "tree-summary";
  summary.textContent = summarizeValue(field.value);
  row.appendChild(summary);

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
  // フィルタ中はマッチを辿りやすいよう祖先を自動展開する
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

/** すでに描画済みのツリーで、特定 path の name 表示を更新する (注釈保存後)。 */
export function refreshFieldName(container: HTMLElement, pathKey: string, name: string | undefined): void {
  const row = container.querySelector<HTMLElement>(`.tree-row[data-path="${pathKey}"]`);
  if (!row) return;
  const nameEl = row.querySelector<HTMLElement>(".tree-name");
  if (!nameEl) return;
  nameEl.textContent = name && name.length > 0 ? name : "(unnamed)";
  nameEl.classList.toggle("tree-name-empty", !name || name.length === 0);
}
