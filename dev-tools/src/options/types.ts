// API オプション編集の共通スキーマ型。
// HERE Routing に限らず、将来 API Bench から任意の API スキーマを
// 同じフォーム（form.ts）でレンダリングできるよう汎用化している。

/** オプション1項目の UI コントロール種別。 */
export type OptionControl = "select" | "multiselect" | "boolean" | "number" | "text";

/** select / multiselect の選択肢1件。 */
export interface OptionChoice {
  /** 送信される値。 */
  value: string;
  /** 表示ラベル（省略時は value を表示）。 */
  label?: string;
}

/** オプション1項目のスキーマ定義。 */
export interface OptionField {
  /** クエリパラメータのキー（角括弧を含む）。例: "avoid[features]" */
  key: string;
  /** 表示ラベル。 */
  label: string;
  /** UI コントロール種別。 */
  control: OptionControl;
  /** select / multiselect の選択肢。 */
  choices?: readonly OptionChoice[];
  /** 入力欄のプレースホルダ。 */
  placeholder?: string;
  /** 補足説明（ラベル下に小さく表示）。 */
  hint?: string;
  /** number の最小値。 */
  min?: number;
  /** number の最大値。 */
  max?: number;
  /** number のステップ。 */
  step?: number;
  /** 末尾に表示する単位（cm / kg 等）。 */
  unit?: string;
}

/** オプションのグループ（UI 上の小見出し単位）。 */
export interface OptionGroup {
  /** グループ識別子。 */
  id: string;
  /** グループ見出し。 */
  label: string;
  /** 所属する項目。 */
  fields: readonly OptionField[];
}

/** オプション値の集合。キーは {@link OptionField.key}。 */
export type OptionValue = string | string[] | number | boolean | undefined;

/** オプション値の集合（キー→値）。 */
export type OptionValues = Record<string, OptionValue>;
