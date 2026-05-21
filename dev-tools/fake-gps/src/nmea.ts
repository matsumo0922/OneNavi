/**
 * NMEA 0183 センテンス生成ユーティリティ。
 *
 * Android Emulator の `adb emu geo nmea <sentence>` は GPS 受信機が出力する NMEA 文を
 * そのまま流し込めるため、位置 (GGA) に加えて進行方向と速度 (RMC) を注入できる。
 * `geo fix` では bearing を渡せないので、方位まで再現したいケースではこちらを使う。
 *
 * GGA で位置を fix させた直後に RMC で速度・進行方向を与える 2 文ペアで送ること。
 */

/** emulator に注入する 1 点分の GPS 状態。 */
export interface GpsFix {
  /** 緯度 (WGS84, degree)。 */
  lat: number;
  /** 経度 (WGS84, degree)。 */
  lng: number;
  /** 進行方向 (compass bearing, 0-359)。 */
  bearing: number;
  /** 速度 (m/s)。 */
  speed: number;
  /** 高度 (m)。 */
  altitude: number;
}

/** m/s → knot 変換係数 (1 海里 = 1852m)。 */
const KNOTS_PER_METER_PER_SECOND = 3600 / 1852;

/** "$" と "*" の間の本文を 1 文字ずつ XOR し、2 桁 16 進のチェックサムを返す。 */
function checksum(body: string): string {
  let xor = 0;
  for (let index = 0; index < body.length; index++) {
    xor ^= body.charCodeAt(index);
  }
  return xor.toString(16).toUpperCase().padStart(2, "0");
}

/** 本文を "$<body>*<checksum>" の完全な NMEA 文に組み立てる。 */
function withChecksum(body: string): string {
  return `$${body}*${checksum(body)}`;
}

/**
 * 度を NMEA の度分形式に変換する。
 * 緯度は ddmm.mmmm (degreeDigits=2)、経度は dddmm.mmmm (degreeDigits=3)。
 */
function toDegreesMinutes(value: number, degreeDigits: number): string {
  const absolute = Math.abs(value);
  const degrees = Math.floor(absolute);
  const minutes = (absolute - degrees) * 60;
  const degreePart = degrees.toString().padStart(degreeDigits, "0");
  const minutePart = minutes.toFixed(4).padStart(7, "0"); // "mm.mmmm"
  return `${degreePart}${minutePart}`;
}

/** NMEA の時刻 (hhmmss.ss) / 日付 (ddmmyy) フィールド。 */
interface UtcFields {
  time: string;
  date: string;
}

/** Date を UTC ベースの NMEA 時刻・日付フィールドに変換する。 */
function toUtcFields(when: Date): UtcFields {
  const pad = (num: number): string => num.toString().padStart(2, "0");
  const time =
    pad(when.getUTCHours()) +
    pad(when.getUTCMinutes()) +
    pad(when.getUTCSeconds()) +
    "." +
    Math.floor(when.getUTCMilliseconds() / 10).toString().padStart(2, "0");
  const date =
    pad(when.getUTCDate()) +
    pad(when.getUTCMonth() + 1) +
    (when.getUTCFullYear() % 100).toString().padStart(2, "0");
  return { time, date };
}

/** $GPGGA (位置・高度・衛星数) を生成する。 */
export function buildGgaSentence(fix: GpsFix, when: Date = new Date()): string {
  const { time } = toUtcFields(when);
  const body = [
    "GPGGA",
    time,
    toDegreesMinutes(fix.lat, 2),
    fix.lat >= 0 ? "N" : "S",
    toDegreesMinutes(fix.lng, 3),
    fix.lng >= 0 ? "E" : "W",
    "1", // fix quality: GPS fix
    "08", // satellites in use
    "0.9", // HDOP
    fix.altitude.toFixed(1),
    "M",
    "0.0", // geoid separation
    "M",
    "", // age of DGPS data
    "", // DGPS station id
  ].join(",");
  return withChecksum(body);
}

/** $GPRMC (速度・進行方向) を生成する。 */
export function buildRmcSentence(fix: GpsFix, when: Date = new Date()): string {
  const { time, date } = toUtcFields(when);
  const speedKnots = (fix.speed * KNOTS_PER_METER_PER_SECOND).toFixed(1);
  const track = (((fix.bearing % 360) + 360) % 360).toFixed(1);
  const body = [
    "GPRMC",
    time,
    "A", // status: active
    toDegreesMinutes(fix.lat, 2),
    fix.lat >= 0 ? "N" : "S",
    toDegreesMinutes(fix.lng, 3),
    fix.lng >= 0 ? "E" : "W",
    speedKnots,
    track,
    date,
    "", // magnetic variation
    "", // variation E/W
  ].join(",");
  return withChecksum(body);
}
