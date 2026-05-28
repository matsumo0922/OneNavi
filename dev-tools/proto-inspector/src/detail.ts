import type { DecodedField } from "./decoder";
import { pathToKey } from "./decoder";
import type { AnnotationFile, FieldAnnotation } from "./annotations";

/** 詳細パネルでの注釈変更を呼び出し元に通知する。 */
export interface DetailCallbacks {
  /** name / description / typeHint が変わったとき。即時 save はせず、メモリ上の AnnotationFile を更新する。 */
  onChange(pathKey: string, next: FieldAnnotation): void;
}

const TYPE_HINTS = [
  "(auto)",
  "uint32",
  "uint64",
  "int32",
  "int64",
  "sint32",
  "sint64",
  "bool",
  "enum",
  "fixed32",
  "sfixed32",
  "float",
  "fixed64",
  "sfixed64",
  "double",
  "string",
  "bytes",
  "packed_varint",
  "message",
];

/** 値部分を読みやすいフォーマットで詳細パネルに展開する。 */
function renderValueDetails(field: DecodedField): HTMLElement {
  const container = document.createElement("dl");
  container.className = "detail-value";

  function addRow(label: string, value: string): void {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    container.appendChild(dt);
    container.appendChild(dd);
  }

  switch (field.value.kind) {
    case "varint":
      addRow("unsigned", field.value.unsigned);
      addRow("signed", field.value.signed);
      addRow("zigzag", field.value.zigzag);
      if (field.value.asBool !== null) addRow("bool", String(field.value.asBool));
      break;
    case "i64":
      addRow("uint64", field.value.asUint);
      addRow("int64", field.value.asInt);
      addRow("double", String(field.value.asDouble));
      addRow("hex", field.value.hex);
      break;
    case "i32":
      addRow("uint32", String(field.value.asUint));
      addRow("int32", String(field.value.asInt));
      addRow("float", String(field.value.asFloat));
      addRow("hex", field.value.hex);
      break;
    case "string":
      addRow("text", field.value.text);
      addRow("hex", field.value.hex);
      break;
    case "bytes":
      addRow("length", String(field.value.length));
      addRow("hex", field.value.hex);
      break;
    case "packed":
      addRow("count", String(field.value.values.length));
      addRow("values", field.value.values.join(", "));
      addRow("hex", field.value.hex);
      break;
    case "message":
      addRow("fields", String(field.value.fields.length));
      addRow("confidence", String(field.value.confidence));
      addRow("hex", field.value.hex);
      break;
  }
  return container;
}

export function renderDetail(
  container: HTMLElement,
  field: DecodedField,
  annotations: AnnotationFile,
  callbacks: DetailCallbacks,
): void {
  container.innerHTML = "";
  const pathKey = pathToKey(field.path);
  const current: FieldAnnotation = { ...(annotations.fields[pathKey] ?? {}) };

  const heading = document.createElement("h3");
  heading.className = "detail-heading";
  heading.textContent = `#${field.fieldNumber} (path ${pathKey})`;
  container.appendChild(heading);

  const meta = document.createElement("div");
  meta.className = "detail-meta";
  meta.textContent = `wire_type=${field.wireType} · offset=${field.offset} · value_len=${field.byteLength}`;
  container.appendChild(meta);

  const form = document.createElement("form");
  form.className = "detail-form";
  form.addEventListener("submit", (event) => event.preventDefault());

  const nameField = makeTextInput("name", current.name ?? "", (value) => {
    current.name = value;
    callbacks.onChange(pathKey, { ...current });
  });
  form.appendChild(nameField);

  const descField = makeTextareaInput("description", current.description ?? "", (value) => {
    current.description = value;
    callbacks.onChange(pathKey, { ...current });
  });
  form.appendChild(descField);

  const typeField = makeSelectInput("typeHint", current.typeHint ?? "(auto)", TYPE_HINTS, (value) => {
    current.typeHint = value === "(auto)" ? undefined : value;
    callbacks.onChange(pathKey, { ...current });
  });
  form.appendChild(typeField);

  container.appendChild(form);
  container.appendChild(renderValueDetails(field));
}

function makeTextInput(label: string, initial: string, onInput: (value: string) => void): HTMLElement {
  const wrapper = document.createElement("label");
  wrapper.className = "detail-input";
  wrapper.textContent = label;
  const input = document.createElement("input");
  input.type = "text";
  input.value = initial;
  input.addEventListener("input", () => onInput(input.value));
  wrapper.appendChild(input);
  return wrapper;
}

function makeTextareaInput(label: string, initial: string, onInput: (value: string) => void): HTMLElement {
  const wrapper = document.createElement("label");
  wrapper.className = "detail-input";
  wrapper.textContent = label;
  const input = document.createElement("textarea");
  input.rows = 4;
  input.value = initial;
  input.addEventListener("input", () => onInput(input.value));
  wrapper.appendChild(input);
  return wrapper;
}

function makeSelectInput(
  label: string,
  initial: string,
  options: string[],
  onChange: (value: string) => void,
): HTMLElement {
  const wrapper = document.createElement("label");
  wrapper.className = "detail-input";
  wrapper.textContent = label;
  const select = document.createElement("select");
  for (const option of options) {
    const optionEl = document.createElement("option");
    optionEl.value = option;
    optionEl.textContent = option;
    if (option === initial) optionEl.selected = true;
    select.appendChild(optionEl);
  }
  select.addEventListener("change", () => onChange(select.value));
  wrapper.appendChild(select);
  return wrapper;
}
