import { type DecodedField, type DecodedValue, pathToKey } from "./decoder";
import type { AnnotationFile, FieldAnnotation } from "./annotations";

/** ツリー上で選択中の field と注釈の編集状態をやり取りする callback 群。 */
export interface TreeCallbacks {
  /** field が選択されたとき。詳細パネルに表示する。 */
  onSelect(field: DecodedField): void;
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

/**
 * 注釈レコードをルックアップする。注釈がなければ undefined。
 */
function lookupAnnotation(annotations: AnnotationFile, field: DecodedField): FieldAnnotation | undefined {
  return annotations.fields[pathToKey(field.path)];
}

/** field 1 件分の <li> を描画する (子要素は遅延展開)。 */
function renderField(
  field: DecodedField,
  annotations: AnnotationFile,
  callbacks: TreeCallbacks,
): HTMLLIElement {
  const annotation = lookupAnnotation(annotations, field);
  const li = document.createElement("li");
  li.className = "tree-node";

  const row = document.createElement("div");
  row.className = "tree-row";
  row.tabIndex = 0;
  row.dataset.path = pathToKey(field.path);

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
            childrenContainer.appendChild(renderField(child, annotations, callbacks));
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

  return li;
}

export function renderTree(
  container: HTMLElement,
  fields: DecodedField[],
  annotations: AnnotationFile,
  callbacks: TreeCallbacks,
): void {
  container.innerHTML = "";
  const root = document.createElement("ul");
  root.className = "tree-root";
  for (const field of fields) {
    root.appendChild(renderField(field, annotations, callbacks));
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
