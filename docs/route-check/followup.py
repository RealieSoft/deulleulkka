# 후속 측정: 앞 구간 실제 값(t1)을 쓰는 어림 식 비교. 식·지표·경우는 결과를 보기 전에 정한 것(README.md).
# 사용: python followup.py   (원 응답은 raw/ 캐시를 같이 씀. 개발용 12건은 기존 응답 그대로 재사용)
import json
import math
import os
import statistics
import sys

from osrm_common import (HERE, angle_off, average_kph, bearing_deg, distance_km, entry_point, estimate_minutes, ll,
                         osrm, polyline_with_names, route_with_steps)
from route_check import BEARING_RANGE, CASES as DEV_CASES, RADIUS_KM, snap_place

# osrm()은 code가 "Ok"인 응답만 돌려주므로(아니면 OsrmError) 아래에서는 응답 code를 다시 보지 않는다.

SNAP_LIMIT_M = 200.0
NO_MINUTE_LIMIT = 1        # 반올림 결과가 이보다 작으면 분 없이 묻는다(MomentJudge.question)
BIG_GAP_ACTUAL_MIN = 2.0   # 지표 2: 실제가 이 이상
SPOKEN_GAP_MIN = 3.0       # 지표 4: 말하는 값과 실제의 차이가 이보다 크면
ALLOWANCES = (10.0, 5.0)
SLOW_KPH = 10.0

# 결과를 보기 전에 정한 검증용 8건과 예비 2건(후보 경로 22개에서 고른 과정은 README.md "순서" 3)
VAL_CASES = [
    ("NC1", "시내", "경주 분황로→서쪽, 찜 대릉원 / 건천읍", (35.8420, 129.2830), (35.8382, 129.2115), (35.8400, 129.1000)),
    ("NC2", "시내", "여수 좌수영로→돌산, 찜 이순신광장 / 향일암", (34.7604, 127.6622), (34.7380, 127.7380), (34.5913, 127.8030)),
    ("NR1", "국도·지방도", "19번 국도 섬진강대로, 찜 화개장터 / 구례군청", (35.0672, 127.7513), (35.1888, 127.6236), (35.2025, 127.4627)),
    ("NR2", "국도·지방도", "3번 국도 북쪽, 찜 문경새재 도립공원 / 수안보", (36.5868, 128.1867), (36.7630, 128.0770), (36.8460, 127.9930)),
    ("NS1", "해안", "7번 국도 동해대로, 찜 강구항 대게거리 / 영해면", (36.1100, 129.3460), (36.3575, 129.3880), (36.5340, 129.4100)),
    ("NM1", "산지", "노고단로·861번 지리산, 찜 성삼재 휴게소 / 뱀사골 입구", (35.2025, 127.4627), (35.3060, 127.5110), (35.3700, 127.5850)),
    ("NH1", "고속도로", "광주대구고속도로 동쪽, 찜 담양 메타세쿼이아길 / 순창군청", (35.1740, 126.9120), (35.3253, 126.9947), (35.3744, 127.1373)),
    ("NH2", "고속도로", "광주대구고속도로 서쪽, 찜 함양 상림공원 / 남원시청", (35.6866, 127.9095), (35.5280, 127.7280), (35.4164, 127.3904)),
]
SPARES = [
    ("NX1", "해안", "해운대로→기장, 찜 해동용궁사 / 간절곶", (35.1587, 129.1604), (35.1884, 129.2232), (35.3590, 129.3600)),
    ("NX2", "지방도·해안", "1132번 일주서로, 찜 애월 한담해변 / 협재", (33.5070, 126.4930), (33.4630, 126.3100), (33.3940, 126.2390)),
]


def snap_check(case):
    return snap_place(case[4])[2]


def measure(case):
    cid, kind, desc, o, p, b = case
    r0 = route_with_steps(o, b)
    pts = polyline_with_names(r0["routes"][0])
    p_snap, p_name, p_snap_m = snap_place(p)
    e = entry_point(pts, p_snap, RADIUS_KM)
    if not e:
        raise RuntimeError(f"{cid}: 반경 진입 없음")
    a = (e[0], e[1])
    heading = e[2]
    bear = f"bearings={heading:.0f},{BEARING_RANGE};"
    # route_check.py 와 같은 URL(같은 캐시)
    r1 = osrm(f"/route/v1/driving/{ll(a)};{ll(b)}", f"overview=false&{bear}")
    r2 = osrm(f"/route/v1/driving/{ll(a)};{ll(p_snap)};{ll(b)}", f"overview=false&steps=true&{bear};&continue_straight=false")
    direct, via = r1["routes"][0], r2["routes"][0]
    a_s = (r1["waypoints"][0]["location"][1], r1["waypoints"][0]["location"][0])
    b_s = (r1["waypoints"][1]["location"][1], r1["waypoints"][1]["location"][0])
    p_s = (r2["waypoints"][1]["location"][1], r2["waypoints"][1]["location"][0])

    t_ab = direct["duration"] / 60.0
    k_ab = direct["distance"] / 1000.0
    v0, _ = estimate_minutes(a_s, p_s, b_s, average_kph(k_ab, t_ab))
    d_ab = distance_km(*a_s, *b_s)
    d_pb = distance_km(*p_s, *b_s)
    s = max(0.0, d_ab - d_pb) / d_ab
    t1 = via["legs"][0]["duration"] / 60.0
    v1 = max(0.0, t1 - s * t_ab)
    v2 = 2.0 * v1
    v3 = max(v0, v2)
    actual = (via["duration"] - direct["duration"]) / 60.0
    slow_m = sum(st["distance"] for leg in via["legs"] for st in leg["steps"]
                 if st["duration"] > 0 and st["distance"] / st["duration"] * 3.6 < SLOW_KPH)
    return {
        "id": cid, "kind": kind, "desc": desc, "A": a_s, "P": p_s, "B": b_s, "A_road": e[3], "P_road": p_name,
        "P_snap_m": p_snap_m, "d_AP": distance_km(*a_s, *p_s), "d_AB": d_ab,
        "angle_heading": angle_off(heading, a_s, p_s), "angle_dest": angle_off(bearing_deg(*a_s, *b_s), a_s, p_s),
        "T_AB": t_ab, "K_AB": k_ab, "t1": t1, "s": s, "actual": actual, "actual_km": (via["distance"] - direct["distance"]) / 1000.0,
        "slow_m": slow_m, "V0": v0, "V1": v1, "V2": v2, "V3": v3,
    }


def spoken_value(est):
    m = math.floor(est + 0.5)
    return m if m >= NO_MINUTE_LIMIT else 0


def metrics(rows, key):
    diffs = [r["actual"] - r[key] for r in rows]
    no_min = [r["id"] for r in rows if math.floor(r[key] + 0.5) < NO_MINUTE_LIMIT and r["actual"] >= BIG_GAP_ACTUAL_MIN]
    lt1 = [r["id"] for r in rows if r[key] < 1.0 and r["actual"] >= BIG_GAP_ACTUAL_MIN]
    out = {"median": statistics.median(diffs), "max": max(diffs), "min": min(diffs), "no_min": no_min, "lt1": lt1}
    for x in ALLOWANCES:
        under = [r["id"] for r in rows if r[key] <= x and r["actual"] > x]
        over = [r["id"] for r in rows if r[key] > x and r["actual"] <= x]
        out[f"split{x:.0f}"] = (under, over)
    out["spoken_gap"] = [r["id"] for r in rows if abs(spoken_value(r[key]) - r["actual"]) > SPOKEN_GAP_MIN]
    return out


def ids(xs):
    return ", ".join(xs) if xs else "-"


def print_set(label, rows):
    print(f"### {label} (n={len(rows)})")
    print()
    print("| 경우 | 유형 | T_AB 분 | t1 분 | s | s·T_AB 분 | 실제 추가 분 | V0 | V1 | V2 | V3 |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows:
        print(f"| {r['id']} | {r['kind']} | {r['T_AB']:.2f} | {r['t1']:.2f} | {r['s']:.3f} | {r['s'] * r['T_AB']:.2f} | "
              f"{r['actual']:.2f} | {r['V0']:.2f} | {r['V1']:.2f} | {r['V2']:.2f} | {r['V3']:.2f} |")
    print()
    print("| 식 | 차이 중앙값 | 차이 최대 | 차이 최소 | 분 없이 묻는데 실제 ≥2분 (반올림<1) | 참고: 어림<1.0인데 실제 ≥2분 | 10분 판정 갈림 (어림≤10·실제>10 / 어림>10·실제≤10) | 5분 판정 갈림 (같은 순서) | 말하는 값과 실제 차 >3분 |")
    print("|---|---:|---:|---:|---|---|---|---|---|")
    for key in ("V0", "V1", "V2", "V3"):
        m = metrics(rows, key)
        u10, o10 = m["split10"]
        u5, o5 = m["split5"]
        print(f"| {key} | {m['median']:+.2f} | {m['max']:+.2f} | {m['min']:+.2f} | {len(m['no_min'])} ({ids(m['no_min'])}) | "
              f"{len(m['lt1'])} ({ids(m['lt1'])}) | {len(u10) + len(o10)} ({len(u10)}: {ids(u10)} / {len(o10)}: {ids(o10)}) | "
              f"{len(u5) + len(o5)} ({len(u5)}: {ids(u5)} / {len(o5)}: {ids(o5)}) | {len(m['spoken_gap'])} ({ids(m['spoken_gap'])}) |")
    print()


def main():
    dev = [measure(c) for c in DEV_CASES]
    # 개발용: 첫 측정 결과와 같은 값인지 확인(같은 원 응답이어야 함)
    first = {r["id"]: r for r in json.load(open(os.path.join(HERE, "results.json"), encoding="utf-8"))}
    worst = max(max(abs(r["V0"] - first[r["id"]]["est_min"]), abs(r["actual"] - first[r["id"]]["actual_min"])) for r in dev)
    print(f"개발용 12건 재계산과 첫 측정의 최대 차이(어림·실제): {worst:.2e}분")

    val, used_spares, dropped = [], [], []
    spares = list(SPARES)
    for c in VAL_CASES:
        m = snap_check(c)
        if m > SNAP_LIMIT_M:
            dropped.append(f"{c[0]}({m:.0f}m)")
            c = spares.pop(0)
            used_spares.append(c[0])
        val.append(measure(c))
    print(f"검증용: 200m 초과로 버린 경우 {dropped or '없음'}, 쓴 예비 {used_spares or '없음'}")
    print()
    print("| 경우 | A 도로 | 찜 붙인 도로(m) | d(A,P) km | d(A,B) km | 각도차 진행/목적지 | 실제 추가 도로 km | 시속 10km 미만 구간 m |")
    print("|---|---|---|---:|---:|---:|---:|---:|")
    for r in val:
        print(f"| {r['id']} | {r['A_road'] or '(이름 없음)'} | {r['P_road'] or '(이름 없음)'} ({r['P_snap_m']:.0f}) | {r['d_AP']:.2f} | "
              f"{r['d_AB']:.1f} | {r['angle_heading']:.0f}° / {r['angle_dest']:.0f}° | {r['actual_km']:.2f} | {r['slow_m']:.0f} |")
    print()
    print_set("(가) 개발용 12건", dev)
    print_set("(가) 개발용 중 고속도로 제외", [r for r in dev if r["kind"] != "고속도로"])
    print_set("(나) 검증용 8건", val)
    print_set("(나) 검증용 중 고속도로 제외", [r for r in val if r["kind"] != "고속도로"])
    with open(os.path.join(HERE, "followup_results.json"), "w", encoding="utf-8") as f:
        json.dump({"dev": dev, "val": val, "dropped": dropped, "used_spares": used_spares}, f, ensure_ascii=False, indent=1,
                  allow_nan=False)


if __name__ == "__main__":
    # 한국어 Windows의 기본 출력 인코딩(cp949)으로는 표의 일부 글자를 쓰지 못할 수 있어 UTF-8로 낸다
    sys.stdout.reconfigure(encoding="utf-8")
    main()
