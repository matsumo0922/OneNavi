/**
 * protobuf wire format の schema-less デコーダ。
 *
 * wire format リファレンス: https://protobuf.dev/programming-guides/encoding/
 *
 * tag (varint) = (field_number << 3) | wire_type
 *   wire_type:
 *     0 = VARINT       (int32 / int64 / uint32 / uint64 / sint32 / sint64 / bool / enum)
 *     1 = I64          (fixed64 / sfixed64 / double)
 *     2 = LEN          (string / bytes / embedded message / packed repeated)
 *     3 = SGROUP       (deprecated)
 *     4 = EGROUP       (deprecated)
 *     5 = I32          (fixed32 / sfixed32 / float)
 *
 * LEN は中身が string / bytes / 子メッセージ / packed repeated のどれかを文脈で
 * 判断する必要がある。本デコーダは「子メッセージとして再帰 parse を試行 →
 * 失敗したら UTF-8 string として decode 試行 → それも失敗したら bytes」の順で
 * ヒューリスティック判定し、信頼度スコアを併記する。
 */

export type WireType = 0 | 1 | 2 | 5;

export interface DecodedField {
  /** field_number (tag の上位ビット) */
  fieldNumber: number;
  /** ルートからこの field までの field_number 列 (root 直下は [1] のような単独要素)。 */
  path: number[];
  /** wire type */
  wireType: WireType;
  /** バイナリ内の開始 byte offset (debug 用) */
  offset: number;
  /** raw bytes 長 (debug 用。tag を含まない値部分のみ) */
  byteLength: number;
  /** デコード結果 */
  value: DecodedValue;
}

export type DecodedValue =
  | VarintValue
  | I64Value
  | LenMessageValue
  | LenStringValue
  | LenBytesValue
  | LenPackedValue
  | I32Value;

/** varint 値。複数解釈を併記する。 */
export interface VarintValue {
  kind: "varint";
  /** 符号なし解釈 (JS 安全範囲を超える場合は string 表現) */
  unsigned: string;
  /** 符号付き解釈 (二の補数 64bit) */
  signed: string;
  /** ZigZag デコード (sint32 / sint64) */
  zigzag: string;
  /** bool 候補 (0 or 1 のみ) */
  asBool: boolean | null;
}

/** 64bit 固定長。 */
export interface I64Value {
  kind: "i64";
  /** 符号なし 64bit (string 表現) */
  asUint: string;
  /** 符号付き 64bit (string 表現) */
  asInt: string;
  /** IEEE 754 double */
  asDouble: number;
  /** raw bytes (hex) */
  hex: string;
}

/** 32bit 固定長。 */
export interface I32Value {
  kind: "i32";
  asUint: number;
  asInt: number;
  asFloat: number;
  hex: string;
}

/** LEN がネスト message と判定された場合。 */
export interface LenMessageValue {
  kind: "message";
  fields: DecodedField[];
  /** 信頼度 (子の field 数が多い・末尾まで消費した等で増える)。 */
  confidence: number;
  /** 元の bytes (利用者が string / bytes に再解釈する用) */
  hex: string;
}

/** LEN が UTF-8 string と判定された場合。 */
export interface LenStringValue {
  kind: "string";
  text: string;
  hex: string;
}

/** LEN が生 bytes 扱いになった場合。 */
export interface LenBytesValue {
  kind: "bytes";
  hex: string;
  /** 長さ */
  length: number;
}

/** LEN が packed repeated varint と推定された場合。 */
export interface LenPackedValue {
  kind: "packed";
  values: string[];
  hex: string;
}

class Reader {
  position = 0;
  constructor(readonly bytes: Uint8Array) {}

  get eof(): boolean {
    return this.position >= this.bytes.length;
  }

  readVarint(): { value: bigint; byteLength: number } | null {
    let result = 0n;
    let shift = 0n;
    const start = this.position;
    for (let index = 0; index < 10; index += 1) {
      if (this.position >= this.bytes.length) {
        this.position = start;
        return null;
      }
      const byte = this.bytes[this.position];
      this.position += 1;
      result |= BigInt(byte & 0x7f) << shift;
      shift += 7n;
      if ((byte & 0x80) === 0) {
        // 64bit を超えるバイト列は無効 (protobuf 仕様)
        if (result >> 64n !== 0n) {
          this.position = start;
          return null;
        }
        return { value: result, byteLength: this.position - start };
      }
    }
    this.position = start;
    return null;
  }

  readFixed32(): number | null {
    if (this.position + 4 > this.bytes.length) return null;
    const view = new DataView(this.bytes.buffer, this.bytes.byteOffset + this.position, 4);
    const value = view.getUint32(0, true);
    this.position += 4;
    return value;
  }

  readFixed64Bytes(): Uint8Array | null {
    if (this.position + 8 > this.bytes.length) return null;
    const slice = this.bytes.slice(this.position, this.position + 8);
    this.position += 8;
    return slice;
  }

  readBytes(length: number): Uint8Array | null {
    if (this.position + length > this.bytes.length) return null;
    const slice = this.bytes.slice(this.position, this.position + length);
    this.position += length;
    return slice;
  }
}

function toHex(bytes: Uint8Array): string {
  let result = "";
  for (const byte of bytes) {
    result += byte.toString(16).padStart(2, "0");
  }
  return result;
}

function decodeVarint(unsignedBig: bigint): VarintValue {
  const signedBig = unsignedBig >= 1n << 63n ? unsignedBig - (1n << 64n) : unsignedBig;
  // ZigZag: (n >> 1) ^ -(n & 1)
  const shifted = unsignedBig >> 1n;
  const lowBit = unsignedBig & 1n;
  const zigzag = lowBit === 1n ? -(shifted + 1n) : shifted;
  const isBool = unsignedBig === 0n || unsignedBig === 1n;
  return {
    kind: "varint",
    unsigned: unsignedBig.toString(),
    signed: signedBig.toString(),
    zigzag: zigzag.toString(),
    asBool: isBool ? unsignedBig === 1n : null,
  };
}

function decodeI64(bytes: Uint8Array): I64Value {
  const view = new DataView(bytes.buffer, bytes.byteOffset, 8);
  const lo = view.getUint32(0, true);
  const hi = view.getUint32(4, true);
  const unsignedBig = (BigInt(hi) << 32n) | BigInt(lo);
  const signedBig = unsignedBig >= 1n << 63n ? unsignedBig - (1n << 64n) : unsignedBig;
  const asDouble = view.getFloat64(0, true);
  return {
    kind: "i64",
    asUint: unsignedBig.toString(),
    asInt: signedBig.toString(),
    asDouble,
    hex: toHex(bytes),
  };
}

function decodeI32(raw: number): I32Value {
  const buffer = new ArrayBuffer(4);
  const view = new DataView(buffer);
  view.setUint32(0, raw, true);
  return {
    kind: "i32",
    asUint: raw,
    asInt: view.getInt32(0, true),
    asFloat: view.getFloat32(0, true),
    hex: raw.toString(16).padStart(8, "0"),
  };
}

const PRINTABLE_THRESHOLD = 0.9;

function looksLikePrintableUtf8(bytes: Uint8Array): { ok: boolean; text: string } {
  if (bytes.length === 0) return { ok: false, text: "" };
  try {
    const decoder = new TextDecoder("utf-8", { fatal: true });
    const text = decoder.decode(bytes);
    // 制御文字 (タブ・改行除く) の比率で判定する
    let printable = 0;
    for (const char of text) {
      const code = char.codePointAt(0) ?? 0;
      if (code === 9 || code === 10 || code === 13 || code >= 32) printable += 1;
    }
    const ratio = printable / [...text].length;
    return { ok: ratio >= PRINTABLE_THRESHOLD, text };
  } catch {
    return { ok: false, text: "" };
  }
}

function tryDecodePackedVarint(bytes: Uint8Array): string[] | null {
  if (bytes.length === 0) return null;
  const reader = new Reader(bytes);
  const values: string[] = [];
  while (!reader.eof) {
    const raw = reader.readVarint();
    if (!raw) return null;
    values.push(raw.value.toString());
  }
  // 全体が消費できて、かつ要素が 2 個以上ある場合のみ packed と扱う
  if (reader.position !== bytes.length) return null;
  if (values.length < 2) return null;
  return values;
}

/**
 * LEN フィールドのバイト列を「ネスト message として再帰 parse」が成功するか試す。
 * 成功 = tag を最後まで読み切り、wire_type が有効値で、子 field を 1 個以上得られた。
 */
function tryDecodeAsMessage(bytes: Uint8Array, parentPath: number[]): LenMessageValue | null {
  if (bytes.length === 0) return null;
  const fields = decodeMessage(bytes, parentPath);
  if (!fields) return null;
  if (fields.length === 0) return null;
  // 信頼度: field 数 + 子 message の数。string らしい LEN を含まないものを優遇。
  let confidence = fields.length;
  for (const field of fields) {
    if (field.value.kind === "message") confidence += 1;
  }
  return {
    kind: "message",
    fields,
    confidence,
    hex: toHex(bytes),
  };
}

/** バイト列を message として decode (全 byte を消費できなければ null)。 */
function decodeMessage(bytes: Uint8Array, parentPath: number[]): DecodedField[] | null {
  const reader = new Reader(bytes);
  const fields: DecodedField[] = [];
  while (!reader.eof) {
    const startOffset = reader.position;
    const tag = reader.readVarint();
    if (!tag) return null;
    // tag は uint32 範囲内であること
    if (tag.value >> 32n !== 0n) return null;
    const tagNum = Number(tag.value);
    const wireType = (tagNum & 0x7) as WireType;
    const fieldNumber = tagNum >>> 3;
    if (fieldNumber === 0) return null;
    if (wireType !== 0 && wireType !== 1 && wireType !== 2 && wireType !== 5) return null;

    const currentPath = [...parentPath, fieldNumber];
    let value: DecodedValue;
    const valueStart = reader.position;
    if (wireType === 0) {
      const raw = reader.readVarint();
      if (!raw) return null;
      value = decodeVarint(raw.value);
    } else if (wireType === 1) {
      const slice = reader.readFixed64Bytes();
      if (!slice) return null;
      value = decodeI64(slice);
    } else if (wireType === 5) {
      const raw = reader.readFixed32();
      if (raw === null) return null;
      value = decodeI32(raw);
    } else {
      // LEN
      const lengthVarint = reader.readVarint();
      if (!lengthVarint) return null;
      if (lengthVarint.value >> 31n !== 0n) return null;
      const length = Number(lengthVarint.value);
      const slice = reader.readBytes(length);
      if (!slice) return null;
      value = interpretLen(slice, currentPath);
    }

    fields.push({
      fieldNumber,
      path: currentPath,
      wireType,
      offset: startOffset,
      byteLength: reader.position - valueStart,
      value,
    });
  }
  return fields;
}

function interpretLen(bytes: Uint8Array, parentPath: number[]): DecodedValue {
  // 優先順: nested message → string → packed varint → bytes
  const asMessage = tryDecodeAsMessage(bytes, parentPath);
  if (asMessage && asMessage.confidence >= 2) return asMessage;
  const asString = looksLikePrintableUtf8(bytes);
  if (asString.ok) return { kind: "string", text: asString.text, hex: toHex(bytes) };
  if (asMessage) return asMessage;
  const asPacked = tryDecodePackedVarint(bytes);
  if (asPacked) return { kind: "packed", values: asPacked, hex: toHex(bytes) };
  return { kind: "bytes", hex: toHex(bytes), length: bytes.length };
}

/**
 * バイナリ全体を root message として decode する。先頭が tag varint で始まる
 * 前提（GUIDE / ROUTE proto はいずれもそう）。
 */
export function decodeRootMessage(bytes: Uint8Array): { fields: DecodedField[]; error: string | null } {
  const fields = decodeMessage(bytes, []);
  if (!fields) {
    return { fields: [], error: "binary is not a valid protobuf root message" };
  }
  return { fields, error: null };
}

/** path 配列を `1.2.3` 形式の文字列キーに変換する。 */
export function pathToKey(path: number[]): string {
  return path.join(".");
}
