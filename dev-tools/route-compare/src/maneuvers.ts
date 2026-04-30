/**
 * guide.json を解析し「物理的な maneuver (実際にハンドルを切る / 進路を選ぶ地点)」
 * に対応する GP (guide-point) index を抽出する。
 *
 * 2 種類のフィルタを併用する:
 *
 * 1. announcement priority (template_id) ベース
 *    - block.announcements[].priority が template_id を表す (spec/21)
 *    - PHYSICAL_TEMPLATE_IDS に含まれるものが音声付き物理 maneuver
 *
 * 2. proto-level flag ベース
 *    - guide.json の route_info.points[i].flags.field_3.field_1 が GP の
 *      構造分類 (spec/21 §Q-3): 31=通常道なり / 1=分岐 / 7=合流 / 10=高速ランプ
 *    - flags.field_1 が立っている GP は JCT 等の主要 maneuver (place ラベル付き)
 *    - これらは音声を伴わない pass-through 分岐 (IC を素通りする等) も含むため、
 *      announcement だけでは取れない構造的な分岐点を拾える
 *
 * Block → GP の対応付け:
 * - block.range.field_3 = 目的地までの残距離 (m)
 * - GP の cum_from_start[i] = sum(cum_distance_m[0..i])
 * - target_cum = total - block.range.field_3 を使って最近傍 GP を決定
 */

import type {GuidePoint} from "./samples";

const PHYSICAL_TEMPLATE_IDS = new Set<number>([
  100, // 交差点案内
  104, // 交差点案内 (まもなく)
  105, // 速度に応じたガイダンス発話
  314, // 自動車専用道路入口案内
  // ── 高速道路の分岐 / 合流系 (JCT で本線か分岐かを Routes API に伝える)
  303, // 高速推奨レーン
  304, // 渋滞を考慮した高速推奨レーン
  404, // 合流地点 (JCT 直後の分岐確定に効く)
  422, // トンネル分岐案内
]);

/**
 * route_info.points[i].flags.field_3.field_1 の値で physical 扱いするもの。
 * 31 (通常道なり) は除外。
 */
const PHYSICAL_F3_VALUES = new Set<number>([
  1, // 分岐 (IC pass-through 等、音声無しでも構造分岐)
  7, // 合流 (本線への合流点)
  10, // 高速ランプ系 (本線 ↔ ランプ遷移)
]);

const SAMPLE_ROOT_ABS =
  "/Users/daichi-matsumoto/dev/Android/OneNavi/drive-supporter-api/analysis/sample";

interface GuideFile {
  body: {
    route_info?: {
      points?: Array<{
        attr?: {
          // 連結道路数の指標 (= 交差点判定)。1 = 単純 pass-through、それ以外は交差点。
          unknown_104?: number;
        };
        flags?: {
          // 主要 maneuver (JCT 等) フラグ
          field_1?: { field_1?: number; field_2?: number };
          field_3?: { field_1?: number };
          field_4?: number;
        };
        label?: {
          // 名前付き交差点 / IC / JCT 等 (素通りでも分岐選択点)
          place?: { text?: { sjis?: string } };
        };
      }>;
    };
    guide: {
      blocks: Array<{
        range: { field_3: number };
        // 単一 announcement の sample もあるため dict / list の両形式を吸収する
        announcements?:
          | Array<{ priority?: number }>
          | { priority?: number };
      }>;
    };
  };
}

export interface ManeuverIndices {
  /** 案内地点 (実際にハンドルを切る maneuver) */
  maneuver: number[];
  /**
   * 案内地点外 sampling から除外すべき index 集合。
   * maneuver に加え、名前付き交差点 / IC / JCT 等の「素通りでも Routes API
   * に分岐選択を強制してしまう構造点」も含む superset。
   */
  excludeFromBetween: number[];
}

/**
 * 物理 maneuver と「分岐されうる構造点」の index list を返す。
 * origin (0) と destination (last) は両方の集合に含まれる。
 *
 * guide.json が無い sample では null を返す (上位で fallback)。
 */
export async function loadManeuverIndices(
  sampleId: string,
  rawGuidePoints: GuidePoint[],
): Promise<ManeuverIndices | null> {
  const url = `/@fs${SAMPLE_ROOT_ABS}/${sampleId}/extracted/guide/guide.json`;
  const response = await fetch(url);
  if (!response.ok) return null;

  const guide = (await response.json()) as GuideFile;
  const blocks = guide.body?.guide?.blocks;
  if (!Array.isArray(blocks)) return null;

  const cumFromStart = computeCumulativeFromStart(rawGuidePoints);
  const totalDistance = cumFromStart[cumFromStart.length - 1] ?? 0;

  const maneuverIndices = new Set<number>();

  // (1) announcement template_id ベース
  for (const block of blocks) {
    if (!hasPhysicalManeuver(block.announcements)) continue;

    const remainingDistance = block.range?.field_3;
    if (typeof remainingDistance !== "number") continue;

    const targetCum = totalDistance - remainingDistance;
    const nearestIndex = findNearestGpIndex(cumFromStart, targetCum);
    if (nearestIndex >= 0) maneuverIndices.add(nearestIndex);
  }

  const protoPoints = guide.body?.route_info?.points;

  // (2) proto flag ベース (音声無しでも構造的に分岐 / 合流 / ランプの GP)
  if (Array.isArray(protoPoints)) {
    for (let index = 0; index < protoPoints.length; index++) {
      if (isStructuralBranch(protoPoints[index])) maneuverIndices.add(index);
    }
  }

  // 案内地点外の除外集合 = maneuver ∪ あらゆる構造マーカー持ち GP
  const excludeFromBetween = new Set<number>(maneuverIndices);
  if (Array.isArray(protoPoints)) {
    for (let index = 0; index < protoPoints.length; index++) {
      if (isStructuralPoint(protoPoints[index])) excludeFromBetween.add(index);
    }
  }

  // origin / destination は必ず保持
  maneuverIndices.add(0);
  maneuverIndices.add(rawGuidePoints.length - 1);
  excludeFromBetween.add(0);
  excludeFromBetween.add(rawGuidePoints.length - 1);

  return {
    maneuver: Array.from(maneuverIndices).sort((a, b) => a - b),
    excludeFromBetween: Array.from(excludeFromBetween).sort((a, b) => a - b),
  };
}

function hasPlaceName(
  point: NonNullable<NonNullable<GuideFile["body"]["route_info"]>["points"]>[number],
): boolean {
  const text = point?.label?.place?.text?.sjis;
  return typeof text === "string" && text.length > 0;
}

/**
 * 「素通りでも分岐選択を強制してしまう構造点」の判定。
 *
 * 純粋な polyline 中継点 (= 案内地点外として安全) は以下を全て満たす:
 *   - attr.unknown_104 === 1 (単一連結 = 交差点ではない)
 *   - flags.field_3.field_1 === 31 (通常道なり)
 *   - flags.field_1 == null
 *   - label.place.text 空
 *
 * 1 つでも該当しなければ構造点扱いで除外。
 *
 * spec/21 D-2103 / Q-3: f3.a の値分布
 *   31: 通常道なり (50-75%) / 7: 合流 / 1: 分岐 / 10: 高速ランプ
 *   4 / 6 / 19: レア値 (構造的な特殊点)
 *
 * 実データ観察: attr.unknown_104 が連結道路数を示す:
 *   1 = 単一連結の道なり点 (97 GPs in shakuji-tsukuba)
 *   4 = 4-way 交差点 (41 GPs。つくば市内の素通り交差点が大量に該当)
 *   7 / 14 = より複雑な交差点
 * `f3=31 かつ f4=null かつ place 無し` でも u104=4 なら交差点なので、
 * Routes API がそこで方向選択を間違える原因になる。
 */
function isStructuralPoint(
  point: NonNullable<NonNullable<GuideFile["body"]["route_info"]>["points"]>[number],
): boolean {
  if (!point) return false;
  if (hasPlaceName(point)) return true;

  // 交差点判定 (連結道路数 != 1 なら交差点)
  const u104 = point.attr?.unknown_104;
  if (typeof u104 === "number" && u104 !== 1) return true;

  const flags = point.flags;
  if (!flags) return false;
  if (flags.field_1?.field_1 !== undefined) return true;
  const f3 = flags.field_3?.field_1;
  if (typeof f3 === "number" && f3 !== 31) return true;
  return false;
}

function isStructuralBranch(
  point: NonNullable<NonNullable<GuideFile["body"]["route_info"]>["points"]>[number],
): boolean {
  const flags = point?.flags;
  if (!flags) return false;
  // 主要 maneuver (JCT 等) は flags.field_1 を持つ
  if (flags.field_1?.field_1 !== undefined) return true;
  // 構造分類 (1=分岐 / 7=合流 / 10=ランプ)
  const f3 = flags.field_3?.field_1;
  if (typeof f3 === "number" && PHYSICAL_F3_VALUES.has(f3)) return true;
  return false;
}

function computeCumulativeFromStart(points: GuidePoint[]): number[] {
  const result: number[] = [];
  let running = 0;
  for (const point of points) {
    running += point.cum_distance_m;
    result.push(running);
  }
  return result;
}

function hasPhysicalManeuver(
  announcements: GuideFile["body"]["guide"]["blocks"][number]["announcements"],
): boolean {
  if (!announcements) return false;
  const list = Array.isArray(announcements) ? announcements : [announcements];
  for (const announcement of list) {
    if (announcement?.priority !== undefined && PHYSICAL_TEMPLATE_IDS.has(announcement.priority)) {
      return true;
    }
  }
  return false;
}

function findNearestGpIndex(cumFromStart: number[], target: number): number {
  let bestIndex = -1;
  let bestDiff = Infinity;
  for (let index = 0; index < cumFromStart.length; index++) {
    const diff = Math.abs(cumFromStart[index] - target);
    if (diff < bestDiff) {
      bestDiff = diff;
      bestIndex = index;
    }
  }
  return bestIndex;
}
