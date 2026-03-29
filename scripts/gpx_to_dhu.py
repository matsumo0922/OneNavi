import argparse
import math
import sys
import time
import xml.etree.ElementTree as ET


def haversine(lat1, lon1, lat2, lon2):
    """2点間の距離（メートル）を計算"""
    R = 6371000  # 地球の半径 (m)
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi, dlambda = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dphi/2)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda/2)**2
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def calculate_bearing(lat1, lon1, lat2, lon2):
    """2点間の方位角（度）を計算"""
    lat1, lon1, lat2, lon2 = map(math.radians, [lat1, lon1, lat2, lon2])
    dlon = lon2 - lon1
    x = math.sin(dlon) * math.cos(lat2)
    y = math.cos(lat1) * math.sin(lat2) - (math.sin(lat1) * math.cos(lat2) * math.cos(dlon))
    bearing = math.degrees(math.atan2(x, y))
    return (bearing + 360) % 360

def main():
    parser = argparse.ArgumentParser(description="Feed GPX route to Android Auto DHU")
    parser.add_argument("gpx_file", help="Path to the GPX file")
    parser.add_argument("--speed", type=float, default=40.0, help="Simulation speed in km/h (default: 40)")
    args = parser.parse_args()

    # 名前空間を無視してtrkptタグを抽出 (Python 3.8+)
    tree = ET.parse(args.gpx_file)
    points = []
    for trkpt in tree.getroot().findall('.//{*}trkpt'):
        points.append((float(trkpt.attrib['lat']), float(trkpt.attrib['lon'])))

    if len(points) < 2:
        print("Error: GPX file must contain at least 2 points.", file=sys.stderr)
        sys.exit(1)

    speed_ms = args.speed * 1000 / 3600
    print(f"Loaded {len(points)} points. Target speed: {args.speed} km/h ({speed_ms:.2f} m/s)", file=sys.stderr)

    for i in range(len(points) - 1):
        lat1, lon1 = points[i]
        lat2, lon2 = points[i+1]

        dist = haversine(lat1, lon1, lat2, lon2)
        if dist < 0.5:  # ポイントが近すぎる場合はスキップ
            continue

        bearing = calculate_bearing(lat1, lon1, lat2, lon2)

        # DHU command: location <lat> <long> [accuracy] [altitude] [speed] [bearing]
        # 精度(accuracy)は 5m、高度(altitude)は 0m で固定
        cmd = f"location {lat1} {lon1} 5 0 {speed_ms:.2f} {bearing:.2f}"
        print(cmd, flush=True)

        # 次のポイントに到達するまでの時間待機
        sleep_time = dist / speed_ms
        time.sleep(sleep_time)

    # 最終地点（速度0で停止）
    last_lat, last_lon = points[-1]
    print(f"location {last_lat} {last_lon} 5 0 0.0 0.0", flush=True)

if __name__ == "__main__":
    main()
