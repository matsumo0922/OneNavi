/**
 * フィールド注釈のクライアント API。
 *
 * サーバー側 (Vite middleware) は dev-tools/proto-inspector/annotations/<rootId>.json
 * を読み書きする。本モジュールはそのラッパー。
 *
 * キー形式は `1.2.3` (root から末端 field まで field_number を `.` 区切り)。
 */

export interface FieldAnnotation {
  /** フィールド名 (例: `cum_distance_m`) */
  name?: string;
  /** 自由記述。値域・観測例・出典 spec のセクション等 */
  description?: string;
  /** 値解釈のヒント (`uint32` / `sint32` / `bool` / `enum` / `fixed32` / `float` / `string` / `bytes` / `packed_varint`) */
  typeHint?: string;
}

export interface AnnotationFile {
  root: string;
  savedAt?: string;
  fields: Record<string, FieldAnnotation>;
}

const BASE_URL = "";

export async function loadAnnotations(rootId: string): Promise<AnnotationFile> {
  const response = await fetch(`${BASE_URL}/api/annotations?root=${encodeURIComponent(rootId)}`);
  if (!response.ok) {
    throw new Error(`failed to load annotations: ${response.status}`);
  }
  return (await response.json()) as AnnotationFile;
}

export async function saveAnnotations(file: AnnotationFile): Promise<void> {
  const response = await fetch(`${BASE_URL}/api/annotations?root=${encodeURIComponent(file.root)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(file),
  });
  if (!response.ok) {
    throw new Error(`failed to save annotations: ${response.status}`);
  }
}

export async function listRoots(): Promise<string[]> {
  const response = await fetch(`${BASE_URL}/api/annotations/index`);
  if (!response.ok) {
    throw new Error(`failed to list annotation roots: ${response.status}`);
  }
  return (await response.json()) as string[];
}
