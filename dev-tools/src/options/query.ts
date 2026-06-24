import type { OptionField, OptionValue, OptionValues } from "./types";

// オプション値を URL のクエリパラメータへ直列化する共通ロジック。
// 空・未設定の項目は送信しない。multiselect は , 区切り、boolean は true のみ送る。

/** 指定スキーマに従い、値を URL のクエリへ反映する（共通）。 */
export function applyOptionValues(
  url: URL,
  values: OptionValues,
  fields: readonly OptionField[],
): void {
  for (const field of fields) {
    const serialized = serializeField(field, values[field.key]);
    if (serialized === null) continue;

    url.searchParams.set(field.key, serialized);
  }
}

/** 1項目を文字列化する。送信不要なら null を返す。 */
function serializeField(field: OptionField, value: OptionValue): string | null {
  if (value === undefined || value === null) return null;

  if (field.control === "multiselect") {
    const list = Array.isArray(value) ? value : [];
    return list.length > 0 ? list.join(",") : null;
  }

  if (field.control === "boolean") {
    return value === true ? "true" : null;
  }

  if (field.control === "number") {
    const numeric = typeof value === "number" ? value : Number(value);
    return Number.isFinite(numeric) ? String(numeric) : null;
  }

  const text = String(value).trim();
  return text.length > 0 ? text : null;
}
