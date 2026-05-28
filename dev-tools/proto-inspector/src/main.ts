import { decodeRootMessage, type DecodedField } from "./decoder";
import { loadAnnotations, saveAnnotations, type AnnotationFile, type FieldAnnotation } from "./annotations";
import { renderTree, refreshFieldName } from "./tree";
import { renderDetail } from "./detail";

const fileInput = document.querySelector<HTMLInputElement>("#file-input")!;
const rootIdInput = document.querySelector<HTMLInputElement>("#root-id")!;
const saveButton = document.querySelector<HTMLButtonElement>("#btn-save")!;
const saveStatus = document.querySelector<HTMLElement>("#save-status")!;
const treeContainer = document.querySelector<HTMLElement>("#tree")!;
const detailContainer = document.querySelector<HTMLElement>("#detail")!;
const summary = document.querySelector<HTMLElement>("#summary")!;

interface DecodedState {
  rootId: string;
  fields: DecodedField[];
  annotations: AnnotationFile;
  dirty: boolean;
}

let state: DecodedState | null = null;

async function reloadFor(rootId: string, fields: DecodedField[]): Promise<void> {
  let annotations: AnnotationFile;
  try {
    annotations = await loadAnnotations(rootId);
  } catch (error) {
    setStatus(`load failed: ${(error as Error).message}`, true);
    annotations = { root: rootId, fields: {} };
  }
  state = { rootId, fields, annotations, dirty: false };
  saveButton.disabled = true;
  summary.textContent = `${fields.length} top-level fields · root=${rootId}`;
  renderTree(treeContainer, fields, annotations, {
    onSelect: (field) => {
      if (!state) return;
      renderDetail(detailContainer, field, state.annotations, {
        onChange: (pathKey, next) => onAnnotationChange(pathKey, next),
      });
    },
  });
  detailContainer.innerHTML = '<p class="placeholder">ツリーからフィールドを選択してください。</p>';
}

function onAnnotationChange(pathKey: string, next: FieldAnnotation): void {
  if (!state) return;
  const cleaned: FieldAnnotation = {};
  if (next.name) cleaned.name = next.name;
  if (next.description) cleaned.description = next.description;
  if (next.typeHint) cleaned.typeHint = next.typeHint;
  if (Object.keys(cleaned).length === 0) {
    delete state.annotations.fields[pathKey];
  } else {
    state.annotations.fields[pathKey] = cleaned;
  }
  state.dirty = true;
  saveButton.disabled = false;
  refreshFieldName(treeContainer, pathKey, cleaned.name);
  setStatus("unsaved changes", false);
}

function setStatus(text: string, isError: boolean): void {
  saveStatus.textContent = text;
  saveStatus.classList.toggle("error", isError);
}

fileInput.addEventListener("change", async () => {
  const file = fileInput.files?.[0];
  if (!file) return;
  const buffer = new Uint8Array(await file.arrayBuffer());
  const { fields, error } = decodeRootMessage(buffer);
  if (error) {
    setStatus(error, true);
    summary.textContent = `decode failed: ${file.name}`;
    treeContainer.innerHTML = "";
    return;
  }
  const rootId = rootIdInput.value.trim() || "default";
  await reloadFor(rootId, fields);
  setStatus(`loaded ${file.name} (${buffer.length} bytes)`, false);
});

rootIdInput.addEventListener("change", async () => {
  if (!state) return;
  const rootId = rootIdInput.value.trim() || "default";
  if (state.dirty) {
    const confirmed = confirm("未保存の変更があります。別 root に切り替えると失われます。続行しますか？");
    if (!confirmed) {
      rootIdInput.value = state.rootId;
      return;
    }
  }
  await reloadFor(rootId, state.fields);
});

saveButton.addEventListener("click", async () => {
  if (!state) return;
  saveButton.disabled = true;
  try {
    await saveAnnotations(state.annotations);
    state.dirty = false;
    setStatus(`saved ${Object.keys(state.annotations.fields).length} annotations`, false);
  } catch (error) {
    setStatus(`save failed: ${(error as Error).message}`, true);
    saveButton.disabled = false;
  }
});

window.addEventListener("beforeunload", (event) => {
  if (state?.dirty) {
    event.preventDefault();
    event.returnValue = "";
  }
});
