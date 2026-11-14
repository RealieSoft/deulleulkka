# 들를까 우회 어림 검증용 공통 함수. 원 응답은 raw/ 에 URL 해시 이름으로 저장하고 다시 쓴다.
import hashlib
import json
import math
import os
import time
import urllib.error
import urllib.request

BASE = "https://router.project-osrm.org"
UA = "deulleulkka-route-check"
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
MIN_INTERVAL_S = 1.1  # 데모 서버 이용 수칙: 1초에 1번 이하
_last = [0.0]

# ---- 엔진(DetourEstimator.kt)과 같은 상수·식 ----
ROAD_FACTOR = 1.3
MIN_AVERAGE_KPH = 20.0
EARTH_RADIUS_KM = 6371.0
AHEAD_MAX_DEG = 90.0


def distance_km(lat1, lon1, lat2, lon2):
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    a = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lon / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def bearing_deg(lat1, lon1, lat2, lon2):
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    d_lon = math.radians(lon2 - lon1)
    y = math.sin(d_lon) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(d_lon)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def average_kph(remaining_km, remaining_min):
    return max(remaining_km / remaining_min * 60.0, MIN_AVERAGE_KPH)


def estimate_minutes(a, p, b, avg_kph, road_factor=ROAD_FACTOR):
    """a, p, b = (lat, lon). 반환: (어림 분, 직선 우회 km). DetourEstimator.estimateMinutes 와 같은 식."""
    direct = distance_km(a[0], a[1], b[0], b[1])
    via = distance_km(a[0], a[1], p[0], p[1]) + distance_km(p[0], p[1], b[0], b[1])
    straight_extra = max(via - direct, 0.0)
    return straight_extra * road_factor / avg_kph * 60.0, straight_extra


def angle_off(ahead_deg, a, p):
    """MomentJudge.isBehind 의 각도 차(0~180)."""
    to_place = bearing_deg(a[0], a[1], p[0], p[1])
    return abs((to_place - ahead_deg + 540.0) % 360.0 - 180.0)


# ---- OSRM 호출 ----
def osrm(path, params):
    url = f"{BASE}{path}?{params}"
    key = hashlib.sha1(url.encode("utf-8")).hexdigest()[:16]
    os.makedirs(RAW, exist_ok=True)
    fp = os.path.join(RAW, key + ".json")
    if os.path.exists(fp):
        with open(fp, encoding="utf-8") as f:
            return json.load(f)["response"]
    wait = MIN_INTERVAL_S - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            body = r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
    _last[0] = time.time()
    data = json.loads(body)
    with open(fp, "w", encoding="utf-8") as f:
        json.dump({"url": url, "fetched_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "response": data},
                  f, ensure_ascii=False, indent=1)
    return data


def ll(p):
    """(lat, lon) -> 'lon,lat' (OSRM 순서)."""
    return f"{p[1]:.6f},{p[0]:.6f}"


def route_with_steps(o, b):
    return osrm(f"/route/v1/driving/{ll(o)};{ll(b)}", "overview=full&geometries=geojson&steps=true")


def nearest(p, number=3):
    return osrm(f"/nearest/v1/driving/{ll(p)}", f"number={number}")


def polyline_with_names(route):
    """경로를 (lat, lon, 도로명) 점 목록으로 편다. 단계(step)별 도형을 이어 붙인다."""
    pts = []
    for leg in route["legs"]:
        for st in leg["steps"]:
            name = st.get("name") or ""
            ref = st.get("ref") or ""
            label = (name + (f" ({ref})" if ref else "")).strip()
            for lon, lat in st["geometry"]["coordinates"]:
                if pts and abs(pts[-1][0] - lat) < 1e-9 and abs(pts[-1][1] - lon) < 1e-9:
                    continue
                pts.append((lat, lon, label))
    return pts


def entry_point(pts, p, radius_km):
    """경로를 따라가다 p 에서 직선 radius_km 안에 처음 들어오는 점(선분 위 보간). (lat, lon, heading, label, 경로상 km)."""
    along = 0.0
    if distance_km(pts[0][0], pts[0][1], p[0], p[1]) <= radius_km:
        h = bearing_deg(pts[0][0], pts[0][1], pts[1][0], pts[1][1])
        return pts[0][0], pts[0][1], h, pts[0][2], 0.0
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        seg = distance_km(a[0], a[1], b[0], b[1])
        if distance_km(b[0], b[1], p[0], p[1]) <= radius_km:
            lo, hi = 0.0, 1.0
            for _ in range(50):
                mid = (lo + hi) / 2
                lat = a[0] + (b[0] - a[0]) * mid
                lon = a[1] + (b[1] - a[1]) * mid
                if distance_km(lat, lon, p[0], p[1]) <= radius_km:
                    hi = mid
                else:
                    lo = mid
            lat = a[0] + (b[0] - a[0]) * hi
            lon = a[1] + (b[1] - a[1]) * hi
            h = bearing_deg(a[0], a[1], b[0], b[1])
            return lat, lon, h, b[2], along + seg * hi
        along += seg
    return None
