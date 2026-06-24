import type { OptionField, OptionGroup } from "./types";

// HERE Routing API v8 (GET /routes) のオプションスキーマ。
// origin / destination / via は waypoint UI 側で管理するためここには含めない。
// polyline / summary は描画に必須なので provider 側で return に強制付与する
// （ゆえに return の選択肢からは除外している）。
// 仕様: https://docs.here.com/routing/reference/calculateroutes

/** transportMode の選択肢。 */
const TRANSPORT_MODES = [
  { value: "car" },
  { value: "truck" },
  { value: "pedestrian" },
  { value: "bicycle" },
  { value: "scooter" },
  { value: "taxi" },
  { value: "bus" },
  { value: "privateBus" },
  { value: "networkRestrictedTruck" },
] as const;

/** avoid[features] の選択肢。 */
const AVOID_FEATURES = [
  { value: "tollRoad", label: "有料道路" },
  { value: "controlledAccessHighway", label: "高速道路" },
  { value: "ferry", label: "フェリー" },
  { value: "tunnel", label: "トンネル" },
  { value: "dirtRoad", label: "未舗装路" },
  { value: "difficultTurns", label: "難所ターン" },
  { value: "uTurns", label: "Uターン" },
  { value: "seasonalClosure", label: "季節通行止め" },
  { value: "carShuttleTrain", label: "カートレイン" },
] as const;

/** avoid[zoneCategories] の選択肢。 */
const ZONE_CATEGORIES = [
  { value: "environmental" },
  { value: "vignette" },
  { value: "congestionPricing" },
] as const;

/** return の選択肢（polyline / summary は常時付与のため除外）。 */
const RETURN_VALUES = [
  { value: "travelSummary" },
  { value: "actions" },
  { value: "instructions" },
  { value: "turnByTurnActions" },
  { value: "elevation" },
  { value: "typicalDuration" },
  { value: "mlDuration" },
  { value: "routeHandle" },
  { value: "passthrough" },
  { value: "incidents" },
  { value: "routingZones" },
  { value: "truckRoadTypes" },
  { value: "tolls" },
  { value: "routeLabels" },
] as const;

/** spans の選択肢。 */
const SPAN_VALUES = [
  { value: "names" },
  { value: "length" },
  { value: "duration" },
  { value: "baseDuration" },
  { value: "typicalDuration" },
  { value: "countryCode" },
  { value: "stateCode" },
  { value: "functionalClass" },
  { value: "routeNumbers" },
  { value: "speedLimit" },
  { value: "maxSpeed" },
  { value: "dynamicSpeedInfo" },
  { value: "segmentId" },
  { value: "segmentRef" },
  { value: "consumption" },
  { value: "streetAttributes" },
  { value: "carAttributes" },
  { value: "truckAttributes" },
  { value: "routingZones" },
  { value: "truckRoadTypes" },
  { value: "notices" },
  { value: "incidents" },
  { value: "tollSystems" },
  { value: "gates" },
  { value: "railwayCrossings" },
] as const;

/** HERE Routing v8 オプションのグループ定義。 */
export const HERE_ROUTING_GROUPS: readonly OptionGroup[] = [
  {
    id: "routing",
    label: "Routing",
    fields: [
      {
        key: "transportMode",
        label: "Transport mode",
        control: "select",
        choices: TRANSPORT_MODES,
        hint: "未指定時は car",
      },
      {
        key: "routingMode",
        label: "Routing mode",
        control: "select",
        choices: [{ value: "fast" }, { value: "short" }],
      },
      {
        key: "alternatives",
        label: "Alternatives",
        control: "number",
        min: 0,
        max: 6,
        step: 1,
        placeholder: "0",
      },
      {
        key: "units",
        label: "Units",
        control: "select",
        choices: [{ value: "metric" }, { value: "imperial" }],
      },
      {
        key: "lang",
        label: "Language",
        control: "text",
        placeholder: "ja-JP",
        hint: "BCP 47。未指定時は ja-JP",
      },
    ],
  },
  {
    id: "time",
    label: "Time & traffic",
    fields: [
      {
        key: "departureTime",
        label: "Departure time",
        control: "text",
        placeholder: "2026-06-24T09:00:00+09:00 / any",
        hint: "RFC 3339。any で時刻無視",
      },
      {
        key: "arrivalTime",
        label: "Arrival time",
        control: "text",
        placeholder: "2026-06-24T12:00:00+09:00",
        hint: "RFC 3339（EV では非対応）",
      },
      {
        key: "traffic[mode]",
        label: "Traffic mode",
        control: "select",
        choices: [{ value: "default" }, { value: "disabled" }],
      },
    ],
  },
  {
    id: "avoid",
    label: "Avoid & exclude",
    fields: [
      {
        key: "avoid[features]",
        label: "Avoid features",
        control: "multiselect",
        choices: AVOID_FEATURES,
      },
      {
        key: "avoid[zoneCategories]",
        label: "Avoid zone categories",
        control: "multiselect",
        choices: ZONE_CATEGORIES,
      },
      {
        key: "avoid[areas]",
        label: "Avoid areas",
        control: "text",
        placeholder: "bbox:139.6,35.6,140.0,35.9",
        hint: "bbox / corridor / polygon。複数は | 区切り",
      },
      {
        key: "exclude[countries]",
        label: "Exclude countries",
        control: "text",
        placeholder: "JPN,KOR",
        hint: "ISO3 国コード。, 区切り",
      },
    ],
  },
  {
    id: "response",
    label: "Response",
    fields: [
      {
        key: "return",
        label: "Return",
        control: "multiselect",
        choices: RETURN_VALUES,
        hint: "polyline・summary は常時付与",
      },
      {
        key: "spans",
        label: "Spans",
        control: "multiselect",
        choices: SPAN_VALUES,
        hint: "polyline 付き return が前提（自動付与）",
      },
    ],
  },
  {
    id: "cost",
    label: "Cost",
    fields: [
      {
        key: "currency",
        label: "Currency",
        control: "text",
        placeholder: "JPY",
        hint: "ISO 4217。料金表示は return=tolls と併用",
      },
    ],
  },
  {
    id: "vehicle",
    label: "Vehicle (truck)",
    fields: [
      { key: "vehicle[grossWeight]", label: "Gross weight", control: "number", min: 0, unit: "kg" },
      { key: "vehicle[weightPerAxle]", label: "Weight per axle", control: "number", min: 0, unit: "kg" },
      { key: "vehicle[height]", label: "Height", control: "number", min: 0, unit: "cm" },
      { key: "vehicle[width]", label: "Width", control: "number", min: 0, unit: "cm" },
      { key: "vehicle[length]", label: "Length", control: "number", min: 0, unit: "cm" },
      { key: "vehicle[axleCount]", label: "Axle count", control: "number", min: 2, step: 1 },
      { key: "vehicle[trailerCount]", label: "Trailer count", control: "number", min: 0, step: 1 },
    ],
  },
  {
    id: "mode-extras",
    label: "Mode extras",
    fields: [
      {
        key: "pedestrian[speed]",
        label: "Pedestrian speed",
        control: "number",
        min: 0.5,
        max: 2,
        step: 0.1,
        unit: "m/s",
      },
      { key: "scooter[allowHighway]", label: "Scooter: allow highway", control: "boolean" },
      {
        key: "taxi[allowDriveThroughTaxiRoads]",
        label: "Taxi: drive-through taxi roads",
        control: "boolean",
      },
      { key: "billingTag", label: "Billing tag", control: "text", placeholder: "tagA" },
    ],
  },
] as const;

/** 全グループを平坦化した項目リスト（直列化・既定値判定に使う）。 */
export const HERE_ROUTING_FIELDS: readonly OptionField[] = HERE_ROUTING_GROUPS.flatMap(
  (group) => group.fields,
);
