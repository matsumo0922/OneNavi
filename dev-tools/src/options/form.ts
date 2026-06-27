import type { OptionField, OptionGroup, OptionValues } from "./types";

// スキーマ駆動の汎用オプションフォーム。
// グループ＋項目の宣言を受け取り、コントロール種別ごとに適切な UI を生成する。
// ルート検索・API Bench の双方から同じ関数で利用できる（UI を共通化）。

/** 生成したフォームのハンドル。 */
export interface OptionsFormHandle {
  /** マウント用のルート要素。 */
  element: HTMLElement;
  /** 現在の値のスナップショット。 */
  getValues(): OptionValues;
}

/** スキーマからオプション編集フォームを生成する。 */
export function createOptionsForm(
  groups: readonly OptionGroup[],
  initial: OptionValues,
  onChange: (values: OptionValues) => void,
): OptionsFormHandle {
  const values: OptionValues = { ...initial };

  const notify = (): void => onChange({ ...values });

  const root = document.createElement("div");
  root.className = "opt-form";

  for (const group of groups) {
    root.appendChild(renderGroup(group, values, notify));
  }

  return {
    element: root,
    getValues: () => ({ ...values }),
  };
}

function renderGroup(group: OptionGroup, values: OptionValues, notify: () => void): HTMLElement {
  const section = document.createElement("div");
  section.className = "opt-group";

  const heading = document.createElement("div");
  heading.className = "opt-group-title";
  heading.textContent = group.label;
  section.appendChild(heading);

  for (const field of group.fields) {
    section.appendChild(renderField(field, values, notify));
  }

  return section;
}

function renderField(field: OptionField, values: OptionValues, notify: () => void): HTMLElement {
  const wrapper = document.createElement("label");
  wrapper.className = "opt-field";

  const head = document.createElement("span");
  head.className = "opt-field-label";
  head.textContent = field.label;
  if (field.unit) {
    const unit = document.createElement("em");
    unit.className = "opt-unit";
    unit.textContent = field.unit;
    head.appendChild(unit);
  }
  wrapper.appendChild(head);

  wrapper.appendChild(renderControl(field, values, notify));

  if (field.hint) {
    const hint = document.createElement("span");
    hint.className = "opt-hint";
    hint.textContent = field.hint;
    wrapper.appendChild(hint);
  }

  return wrapper;
}

function renderControl(field: OptionField, values: OptionValues, notify: () => void): HTMLElement {
  if (field.control === "select") return buildSelect(field, values, notify);
  if (field.control === "multiselect") return buildMultiselect(field, values, notify);
  if (field.control === "boolean") return buildBoolean(field, values, notify);
  if (field.control === "number") return buildNumber(field, values, notify);

  return buildText(field, values, notify);
}

function buildSelect(field: OptionField, values: OptionValues, notify: () => void): HTMLElement {
  const select = document.createElement("select");
  select.className = "opt-control opt-select";

  const empty = document.createElement("option");
  empty.value = "";
  empty.textContent = "(default)";
  select.appendChild(empty);

  for (const choice of field.choices ?? []) {
    const option = document.createElement("option");
    option.value = choice.value;
    option.textContent = choice.label ?? choice.value;
    select.appendChild(option);
  }

  const current = values[field.key];
  select.value = typeof current === "string" ? current : "";

  select.addEventListener("change", () => {
    setOrClear(values, field.key, select.value);
    notify();
  });

  return select;
}

function buildMultiselect(field: OptionField, values: OptionValues, notify: () => void): HTMLElement {
  const container = document.createElement("div");
  container.className = "opt-control opt-chips";

  const selected = new Set(toStringArray(values[field.key]));

  for (const choice of field.choices ?? []) {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "opt-chip";
    chip.textContent = choice.label ?? choice.value;
    chip.classList.toggle("active", selected.has(choice.value));

    chip.addEventListener("click", () => {
      toggleChip(selected, choice.value, chip);
      writeArray(values, field.key, selected);
      notify();
    });

    container.appendChild(chip);
  }

  return container;
}

function buildBoolean(field: OptionField, values: OptionValues, notify: () => void): HTMLElement {
  const toggle = document.createElement("input");
  toggle.type = "checkbox";
  toggle.className = "opt-control opt-check";
  toggle.checked = values[field.key] === true;

  toggle.addEventListener("change", () => {
    if (toggle.checked) {
      values[field.key] = true;
    } else {
      delete values[field.key];
    }
    notify();
  });

  return toggle;
}

function buildNumber(field: OptionField, values: OptionValues, notify: () => void): HTMLElement {
  const input = document.createElement("input");
  input.type = "number";
  input.className = "opt-control opt-input";
  if (field.min !== undefined) input.min = String(field.min);
  if (field.max !== undefined) input.max = String(field.max);
  if (field.step !== undefined) input.step = String(field.step);
  if (field.placeholder) input.placeholder = field.placeholder;

  const current = values[field.key];
  input.value = typeof current === "number" ? String(current) : "";

  input.addEventListener("input", () => {
    const trimmed = input.value.trim();
    if (trimmed === "") {
      delete values[field.key];
    } else {
      values[field.key] = Number(trimmed);
    }
    notify();
  });

  return input;
}

function buildText(field: OptionField, values: OptionValues, notify: () => void): HTMLElement {
  const input = document.createElement("input");
  input.type = "text";
  input.className = "opt-control opt-input";
  if (field.placeholder) input.placeholder = field.placeholder;

  const current = values[field.key];
  input.value = typeof current === "string" ? current : "";

  input.addEventListener("input", () => {
    setOrClear(values, field.key, input.value.trim());
    notify();
  });

  return input;
}

function setOrClear(values: OptionValues, key: string, value: string): void {
  if (value === "") {
    delete values[key];
  } else {
    values[key] = value;
  }
}

function toggleChip(selected: Set<string>, value: string, chip: HTMLButtonElement): void {
  const isActive = selected.has(value);
  if (isActive) {
    selected.delete(value);
  } else {
    selected.add(value);
  }
  chip.classList.toggle("active", !isActive);
}

function writeArray(values: OptionValues, key: string, selected: Set<string>): void {
  if (selected.size === 0) {
    delete values[key];
  } else {
    values[key] = [...selected];
  }
}

function toStringArray(value: OptionValues[string]): string[] {
  return Array.isArray(value) ? value : [];
}
