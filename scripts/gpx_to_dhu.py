import argparse
import math
import sys
import time
import xml.etree.ElementTree as ET


def haversine(lat1, lon1, lat2, lon2):
    R = 6371000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi, dlambda = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dphi/2)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda/2)**2
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def calculate_bearing(lat1, lon1, lat2, lon2):
    lat1, lon1, lat2, lon2 = map(math.radians, [lat1, lon1, lat2, lon2])
    dlon = lon2 - lon1
    x = math.sin(dlon) * math.cos(lat2)
    y = math.cos(lat1) * math.sin(lat2) - (math.sin(lat1) * math.cos(lat2) * math.cos(dlon))
    bearing = math.degrees(math.atan2(x, y))
    return (bearing + 360) % 360

def main():
    parser = argparse.ArgumentParser(description="Feed GPX route to Android Auto DHU")
    parser.add_argument("gpx_file", help="Path to the GPX file")
    parser.add_argument("--speed", type=float, default=60.0, help="Simulation speed in km/h")
    parser.add_argument("--pipe", type=str, default="dhu_pipe", help="Path to the named pipe")
    args = parser.parse_args()

    tree = ET.parse(args.gpx_file)
    points = []
    for trkpt in tree.getroot().findall('.//{*}trkpt'):
        points.append((float(trkpt.attrib['lat']), float(trkpt.attrib['lon'])))

    if len(points) < 2:
        print("Error: GPX file must contain at least 2 points.", file=sys.stderr)
        sys.exit(1)

    speed_ms = args.speed * 1000 / 3600
    print(f"Loaded {len(points)} points. Target speed: {args.speed} km/h ({speed_ms:.2f} m/s)", file=sys.stderr)

    last_lat, last_lon = points[0]

    for i in range(1, len(points)):
        curr_lat, curr_lon = points[i]

        dist = haversine(last_lat, last_lon, curr_lat, curr_lon)
        if dist < 0.5:
            continue

        bearing = calculate_bearing(last_lat, last_lon, curr_lat, curr_lon)
        cmd = f"location {curr_lat} {curr_lon} 5 0 {speed_ms:.2f} {bearing:.2f}"
        sleep_time = dist / speed_ms

        # 1. ターミナルで進捗を確認できるように標準エラー出力にプリント
        print(f"Sent: {cmd} (sleeping {sleep_time:.2f}s)", file=sys.stderr)

        # 2. echoコマンドと同じように、都度パイプを開いて閉じることで確実に押し出す
        try:
            with open(args.pipe, "w") as f:
                f.write(cmd + "\n")
        except IOError as e:
            print(f"Pipe error: {e}", file=sys.stderr)

        time.sleep(sleep_time)
        last_lat, last_lon = curr_lat, curr_lon

    # 最終地点（停止）
    last_p = points[-1]
    end_cmd = f"location {last_p[0]} {last_p[1]} 5 0 0.0 0.0"
    print(f"End: {end_cmd}", file=sys.stderr)
    with open(args.pipe, "w") as f:
        f.write(end_cmd + "\n")

if __name__ == "__main__":
    main()
