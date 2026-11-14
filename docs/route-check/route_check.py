# 들를까 우회 시간 어림(DetourEstimator) vs OSRM 도로 경로의 실제 추가 시간 비교.
# 사용: python route_check.py   (원 응답은 raw/ 에 저장, 결과는 results.json 과 표준출력)
#
# 절차(경우마다):
#  1) 출발 O -> 목적지 B 의 OSRM 경로(도형 포함)를 받는다.
#  2) 찜 P 는 가장 가까운 비고속도로 도로 위 점으로 붙인다.
#  3) 현재 위치 A = O->B 경로를 따라가다 P 에서 직선 RADIUS_KM 안에 처음 들어오는 지점(앱의 찜 반경 2km 진입 직후).
#  4) A->B, A->P->B 를 받아 실제 추가 시간·거리를 구하고, 엔진과 같은 식으로 어림을 낸다.
import json
import math
import os
import statistics
import sys

from osrm_common import (HERE, MIN_AVERAGE_KPH, ROAD_FACTOR, angle_off, average_kph, bearing_deg, distance_km, entry_point,
                         estimate_minutes, ll, nearest, osrm, polyline_with_names, route_with_steps)

# osrm()은 code가 "Ok"인 응답만 돌려주므로(아니면 OsrmError) 아래에서는 응답 code를 다시 보지 않는다.

RADIUS_KM = 1.9          # 앱의 찜 반경(2km) 바로 안쪽
ALLOWED_MIN = 10.0       # 기본 허용 우회 시간
SOLO_ALLOWED_MIN = 5.0   # 혼자 탄 이동(절반)
BEARING_RANGE = 30       # A 를 진행 방향 차로에 붙이기 위한 허용 각
HIGHWAY = "고속도로"

# (id, 유형, 설명, O, P, B). 좌표는 (위도, 경도)
CASES = [
    ("C1", "시내", "서울 테헤란로 동쪽 방향, 찜은 북쪽 골목 안 선정릉 / 목적지 하남 스타필드",
     (37.4979, 127.0276), (37.5087, 127.0469), (37.5455, 127.2240)),
    ("C2", "시내", "수원역 앞에서 판교 방향, 찜은 구도심 화성행궁 / 목적지 판교역",
     (37.2659, 126.9999), (37.2818, 127.0136), (37.3948, 127.1112)),
    ("C3", "시내", "대전 충무로 서쪽 방향, 찜은 중앙로 끝 옛 충남도청 / 목적지 계룡시청",
     (36.3119, 127.4549), (36.3270, 127.4207), (36.2745, 127.2486)),
    ("C4", "시내", "서울 충무로 북쪽 방향, 찜은 북촌 한옥마을 골목 / 목적지 의정부시청",
     (37.5547, 126.9707), (37.5826, 126.9850), (37.7381, 127.0337)),
    ("R1", "국도·지방도", "6번 국도(경강로) 양평 방향, 찜은 강가 두물머리 / 목적지 양평역",
     (37.5474, 127.2440), (37.5338, 127.3148), (37.4927, 127.4920)),
    ("R2", "국도·지방도", "46번 국도(경춘로) 춘천 방향, 찜은 강가 남이섬 가평나루 / 목적지 춘천역",
     (37.7357, 127.4263), (37.8128, 127.5253), (37.8846, 127.7168)),
    ("R3", "국도·지방도", "단양 삼봉로→5번 국도 제천 방향, 찜은 강가 도담삼봉 / 목적지 제천시청",
     (36.9846, 128.3654), (37.0010, 128.3440), (37.1326, 128.1909)),
    ("S1", "해안", "7번 국도(동해대로) 북쪽 방향, 찜은 바닷가 송지호 해변 길 / 목적지 고성 거진읍",
     (38.2070, 128.5918), (38.3390, 128.5270), (38.4467, 128.4575)),
    ("M1", "산지", "342번 지방도(남한산성로) 고갯길, 찜은 산성 안 행궁 앞 마을 길 / 목적지 광주 퇴촌",
     (37.4573, 127.1499), (37.47784, 127.18419), (37.4720, 127.3095)),
    ("M2", "산지", "44번 국도(설악로) 한계령 굽잇길, 찜은 고갯마루 길가(휴게소 부근 어림) / 목적지 인제 원통",
     (38.0790, 128.5280), (38.09387, 128.41610), (38.1240, 128.2050)),
    ("H1", "고속도로", "경부고속도로 남쪽 방향(오산 부근), 찜은 고속도로 서쪽 약 1.5km 오산역 앞 / 목적지 평택시청",
     (37.2750, 127.1150), (37.14516, 127.06762), (36.9921, 127.1129)),
    ("H2", "고속도로", "영동고속도로 동쪽 방향(용인 동백 부근), 찜은 고속도로 남쪽 약 1km 동백지구 길 / 목적지 이천시청",
     (37.2636, 127.0286), (37.27647, 127.16196), (37.2720, 127.4350)),
]


def snap_place(p):
    """가장 가까운 도로 중 고속도로가 아닌 첫 점."""
    r = nearest(p, number=5)
    for w in r["waypoints"]:
        if HIGHWAY not in (w.get("name") or ""):
            return (w["location"][1], w["location"][0]), w.get("name") or "", w["distance"]
    raise RuntimeError("고속도로 아닌 도로를 못 찾음")


def run_case(case):
    cid, kind, desc, o, p, b = case
    r0 = route_with_steps(o, b)
    pts = polyline_with_names(r0["routes"][0])
    p_snap, p_name, p_snap_m = snap_place(p)
    e = entry_point(pts, p_snap, RADIUS_KM)
    if not e:
        raise RuntimeError(f"{cid}: 경로가 찜 반경에 들어오지 않음")
    a_lat, a_lon, heading, a_label, a_along = e
    a = (a_lat, a_lon)

    bear = f"bearings={heading:.0f},{BEARING_RANGE};"
    r1 = osrm(f"/route/v1/driving/{ll(a)};{ll(b)}", f"overview=false&{bear}")
    r2 = osrm(f"/route/v1/driving/{ll(a)};{ll(p_snap)};{ll(b)}", f"overview=false&steps=true&{bear};&continue_straight=false")
    r3 = osrm(f"/route/v1/driving/{ll(a)};{ll(p_snap)};{ll(b)}", f"overview=false&{bear};")

    direct = r1["routes"][0]
    via = r2["routes"][0]
    via_noturn = r3["routes"][0]
    a_s = (r1["waypoints"][0]["location"][1], r1["waypoints"][0]["location"][0])
    b_s = (r1["waypoints"][1]["location"][1], r1["waypoints"][1]["location"][0])
    p_s = (r2["waypoints"][1]["location"][1], r2["waypoints"][1]["location"][0])

    remaining_km = direct["distance"] / 1000.0
    remaining_min = direct["duration"] / 60.0
    avg = average_kph(remaining_km, remaining_min)
    est_min, straight_extra = estimate_minutes(a_s, p_s, b_s, avg)
    actual_min = (via["duration"] - direct["duration"]) / 60.0
    actual_km = (via["distance"] - direct["distance"]) / 1000.0
    noturn_min = (via_noturn["duration"] - direct["duration"]) / 60.0
    # 찜 붙임이 숲길·비포장(OSRM 차량 시속 10km 미만 구간)으로 이어졌는지 확인용
    slow_m = sum(st["distance"] for leg in via["legs"] for st in leg["steps"]
                 if st["duration"] > 0 and st["distance"] / st["duration"] * 3.6 < 10.0)
    ratio = actual_min / est_min if est_min >= 0.05 else math.inf
    back_factor = ROAD_FACTOR * actual_min / est_min if est_min >= 0.05 else math.inf
    return {
        "id": cid, "kind": kind, "desc": desc,
        "A": a_s, "A_road": a_label, "A_along_km": a_along, "heading": heading,
        "A_snap_m": r1["waypoints"][0]["distance"],
        "P": p_s, "P_road": p_name, "P_snap_from_anchor_m": p_snap_m, "B": b_s,
        "d_AP_km": distance_km(*a_s, *p_s), "d_AB_km": distance_km(*a_s, *b_s),
        "angle_heading": angle_off(heading, a_s, p_s), "angle_dest": angle_off(bearing_deg(*a_s, *b_s), a_s, p_s),
        "AB_road_km": remaining_km, "AB_min": remaining_min, "avg_kph": avg,
        "straight_extra_km": straight_extra, "est_min": est_min,
        "actual_min": actual_min, "actual_km": actual_km, "noturn_min": noturn_min,
        "slow_m": slow_m, "diff_min": actual_min - est_min,
        "est_min_floor_speed": estimate_minutes(a_s, p_s, b_s, MIN_AVERAGE_KPH)[0], "ratio": ratio, "back_factor": back_factor,
    }


def spoken(est_min):
    """MomentJudge.question 과 같은 규칙: roundToInt(반올림) 결과가 1 미만이면 분을 빼고 묻는다."""
    m = math.floor(est_min + 0.5)
    return "분 없이 \"가는 길에 있어요\"" if m < 1 else f"\"약 {m}분\""


def fmt_inf(x, nd=2):
    return "∞(어림≈0)" if math.isinf(x) else f"{x:.{nd}f}"


def summarize(rows, label):
    diffs = [r["diff_min"] for r in rows]
    ratios = sorted(r["ratio"] for r in rows)
    under = sum(1 for r in rows if r["est_min"] < r["actual_min"])
    asked_over = [r["id"] for r in rows if r["est_min"] <= ALLOWED_MIN and r["actual_min"] > ALLOWED_MIN]
    asked_over5 = [r["id"] for r in rows if r["est_min"] <= SOLO_ALLOWED_MIN and r["actual_min"] > SOLO_ALLOWED_MIN]
    asked = sum(1 for r in rows if r["est_min"] <= ALLOWED_MIN)
    abs_diffs = [abs(d) for d in diffs]
    print(f"[{label}] n={len(rows)}")
    print(f"  차이(실제-어림) 중앙값 {statistics.median(diffs):.2f}분, 최대 {max(diffs):.2f}분, 최소 {min(diffs):.2f}분, "
          f"절댓값 중앙값 {statistics.median(abs_diffs):.2f}분")
    print(f"  실제/어림 비율 중앙값 {fmt_inf(statistics.median(ratios))} (정렬값: {', '.join(fmt_inf(x) for x in ratios)})")
    print(f"  어림이 실제보다 짧게 잡힌 경우 {under}/{len(rows)}")
    no_min_rows = [r for r in rows if math.floor(r["est_min"] + 0.5) < 1]
    no_min_actual = ", ".join("{} {:.2f}".format(r["id"], r["actual_min"]) for r in no_min_rows)
    print(f"  분 없이 '가는 길에 있어요'로 묻는 경우 {len(no_min_rows)}/{len(rows)}, 그 경우들의 실제 추가 분: {no_min_actual}")
    print(f"  어림(속도 하한 20km/h)의 최대 {max(r['est_min_floor_speed'] for r in rows):.2f}분")
    print(f"  허용 10분: 어림으로 묻는 경우 {asked}/{len(rows)}, 그중 실제 10분 초과(후속 알림) {len(asked_over)} {asked_over}")
    print(f"  (참고) 혼자 탄 이동 허용 5분: 어림 5분 이하인데 실제 5분 초과 {len(asked_over5)} {asked_over5}")


def finite_or_none(r):
    """표준 JSON에는 Infinity가 없으므로, 어림이 0에 가까워 무한대가 된 비율·보정값은 null로 저장한다(표에는 ∞로 찍음)."""
    return {k: (None if isinstance(v, float) and math.isinf(v) else v) for k, v in r.items()}


def main():
    results = [run_case(c) for c in CASES]
    with open(os.path.join(HERE, "results.json"), "w", encoding="utf-8") as f:
        json.dump([finite_or_none(r) for r in results], f, ensure_ascii=False, indent=1, allow_nan=False,
                  default=lambda x: None)

    print("| 경우 | 유형 | 직선 우회 km | 어림 분 | 실제 추가 분 | 실제 추가 도로 km | 차이(분) | 실제/어림 | 거꾸로 구한 보정값 | 앱이 묻는 말 |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---|")
    for r in results:
        print(f"| {r['id']} | {r['kind']} | {r['straight_extra_km']:.3f} | {r['est_min']:.2f} | {r['actual_min']:.2f} | "
              f"{r['actual_km']:.2f} | {r['diff_min']:+.2f} | {fmt_inf(r['ratio'])} | {fmt_inf(r['back_factor'])} | {spoken(r['est_min'])} |")
    print()
    print("| 경우 | A 도로(경로상 km) | d(A,P) km | d(A,B) km | 각도차 진행/목적지 | A→B 도로 km / 분 | 평균 속도 | 찜 붙인 도로(앵커에서 m) | U턴 금지 시 실제 추가 분 | 경유 경로 중 시속 10km 미만 구간 m | 어림(속도 하한 20km/h일 때) 분 |")
    print("|---|---|---:|---:|---:|---:|---:|---|---:|---:|---:|")
    for r in results:
        print(f"| {r['id']} | {r['A_road'] or '(이름 없음)'} ({r['A_along_km']:.1f}) | {r['d_AP_km']:.2f} | {r['d_AB_km']:.1f} | "
              f"{r['angle_heading']:.0f}° / {r['angle_dest']:.0f}° | {r['AB_road_km']:.1f} / {r['AB_min']:.1f} | "
              f"{r['avg_kph']:.0f} km/h | {r['P_road'] or '(이름 없음)'} ({r['P_snap_from_anchor_m']:.0f}) | {r['noturn_min']:.2f} | {r['slow_m']:.0f} | {r['est_min_floor_speed']:.2f} |")
    print()
    for r in results:
        print(f"{r['id']}: A={r['A'][0]:.5f},{r['A'][1]:.5f} P={r['P'][0]:.5f},{r['P'][1]:.5f} B={r['B'][0]:.5f},{r['B'][1]:.5f}"
              f" A붙임 {r['A_snap_m']:.1f}m")
    print()
    summarize([r for r in results if r["kind"] != "고속도로"], "고속도로 제외")
    summarize(results, "전체")
    summarize([r for r in results if r["kind"] == "고속도로"], "고속도로만")


if __name__ == "__main__":
    # 한국어 Windows의 기본 출력 인코딩(cp949)은 표의 ∞·≈를 쓰지 못해, 파일·파이프로 받으면 중간에 끊긴다
    sys.stdout.reconfigure(encoding="utf-8")
    main()
