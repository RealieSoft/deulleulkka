# raw/의 응답 가운데 두 스크립트가 쓰는 것과 쓰지 않는 것을 세고, 검증용 후보를 고른 단계를 시각 순서로 보인다.
# 사용: python raw_audit.py   (raw/만 읽고 인터넷에 접속하지 않으며, 다른 파일을 쓰지 않는다. 출력은 raw_audit_out.txt)
import collections
import glob
import hashlib
import json
import os
import sys
from urllib.parse import parse_qs, urlsplit

import osrm_common

used = collections.defaultdict(set)
_current = ["?"]
_orig_osrm = osrm_common.osrm


def _recording_osrm(path, params):
    url = f"{osrm_common.BASE}{path}?{params}"
    key = hashlib.sha1(url.encode("utf-8")).hexdigest()[:16]
    if not os.path.exists(os.path.join(osrm_common.RAW, key + ".json")):
        raise RuntimeError(f"raw/에 없는 요청(이 스크립트는 받지 않음): {url}")
    used[key].add(_current[0])
    return _orig_osrm(path, params)


osrm_common.osrm = _recording_osrm  # 아래 두 모듈을 불러오기 전에 바꿔야 그쪽 osrm도 이것을 쓴다
import route_check  # noqa: E402
import followup  # noqa: E402

CANDIDATE_FROM, CANDIDATE_TO = "2026-11-14T03:21", "2026-11-14T03:25"


def collect_used():
    """두 스크립트의 main()과 같은 순서로 경우를 재되, 결과 파일은 쓰지 않는다."""
    _current[0] = "route_check"
    for c in route_check.CASES:
        route_check.run_case(c)
    _current[0] = "followup"
    for c in followup.DEV_CASES:
        followup.measure(c)
    spares = list(followup.SPARES)
    for c in followup.VAL_CASES:
        if followup.snap_check(c) > followup.SNAP_LIMIT_M:
            c = spares.pop(0)
        followup.measure(c)


def load_raw():
    rows = []
    for fp in glob.glob(os.path.join(osrm_common.RAW, "*.json")):
        with open(fp, encoding="utf-8") as f:
            j = json.load(f)
        u = urlsplit(j["url"])
        rows.append({"key": os.path.basename(fp)[:-5], "t": j["fetched_utc"], "url": j["url"],
                     "service": u.path.split("/")[1], "points": u.path.rsplit("/", 1)[1].split(";"),
                     "q": parse_qs(u.query), "response": j["response"], "bytes": os.path.getsize(fp)})
    rows.sort(key=lambda r: r["t"])
    return rows


def category(r):
    """쓰지 않는 응답의 묶음. URL의 특징과 받은 시각만으로 가른다."""
    q, n = r["q"], len(r["points"])
    if "annotations" in q:
        return "구간 속도 보조 분석(annotations=speed)"
    if r["service"] == "route" and n == 3 and "bearings" not in q:
        return "진행 방향 제약 없는 경유(bearings 없음)"
    if CANDIDATE_FROM <= r["t"] < CANDIDATE_TO:
        return "검증용 후보 단계(03:21~03:24Z)"
    if r["service"] == "nearest" and q.get("number") == ["3"]:
        return "찜 붙임 number=3(지금은 5)"
    if r["service"] == "route" and n == 3 and "continue_straight" in q and "steps" not in q:
        return "경유 요청에 steps가 없는 것"
    if r["service"] == "route" and q.get("overview") == ["full"]:
        return "개발용 기준 경로 중 쓰지 않은 것"
    return "개발용 찜 3곳(M1·H1·H2)을 바꾸기 전 붙임·경로"


def snapped(resp):
    for w in resp["waypoints"]:
        if route_check.HIGHWAY not in (w.get("name") or ""):
            return (w["location"][1], w["location"][0]), w["distance"]
    return None, None


def candidate_stage(rows):
    """후보 단계의 기준 경로와, 그 뒤 다음 경로 전까지 받은 찜 붙임을 짝지어 반경 통과·붙임 거리를 보인다."""
    stage = [r for r in rows if CANDIDATE_FROM <= r["t"] < CANDIDATE_TO]
    print(f"후보 단계({stage[0]['t']} ~ {stage[-1]['t']}): 기준 경로 "
          f"{sum(r['service'] == 'route' for r in stage)}개, 찜 붙임 {sum(r['service'] == 'nearest' for r in stage)}곳")
    later = [r for r in rows if r["t"] >= CANDIDATE_TO]
    print(f"그 뒤 첫 요청: {later[0]['t'] if later else '-'}")
    print()
    print("| 받은 시각 | 종류 | 좌표(경도,위도) | 쓰는 곳 | 붙임 거리 m | 바로 앞 경로에서 찜까지 최소 km | 1.9km 안을 지남 |")
    print("|---|---|---|---|---:|---:|---|")
    pts = None
    for r in stage:
        who = ", ".join(sorted(used[r["key"]])) or "-"
        coords = " → ".join(r["points"])
        if r["service"] == "route":
            pts = osrm_common.polyline_with_names(r["response"]["routes"][0])
            print(f"| {r['t'][11:19]} | 기준 경로 | {coords} | {who} | | | |")
            continue
        p, snap_m = snapped(r["response"])
        near = min(osrm_common.distance_km(a[0], a[1], p[0], p[1]) for a in pts)
        inside = osrm_common.entry_point(pts, p, route_check.RADIUS_KM) is not None
        print(f"| {r['t'][11:19]} | 찜 붙임 | {coords} | {who} | {snap_m:.0f} | {near:.2f} | {'예' if inside else '아니오'} |")


def main():
    collect_used()
    rows = load_raw()
    keys = {r["key"] for r in rows}
    rc = {k for k, w in used.items() if "route_check" in w}
    fu = {k for k, w in used.items() if "followup" in w}
    print(f"raw/ 응답 {len(rows)}건(code가 Ok인 것 {sum(r['response'].get('code') == 'Ok' for r in rows)}건)")
    print(f"route_check.py가 쓰는 것 {len(rc)}건, followup.py가 쓰는 것 {len(fu)}건, 둘 중 하나라도 쓰는 것 {len(rc | fu)}건")
    unused = [r for r in rows if r["key"] not in used]
    print(f"쓰지 않는 것 {len(unused)}건, {sum(r['bytes'] for r in unused):,}바이트")
    assert set(used) <= keys
    print()
    print("| 쓰지 않는 묶음 | 건수 | 바이트 | 받은 시각(UTC) | 종류 |")
    print("|---|---:|---:|---|---|")
    groups = collections.defaultdict(list)
    for r in unused:
        groups[category(r)].append(r)
    for name, rs in sorted(groups.items(), key=lambda kv: kv[1][0]["t"]):
        kinds = collections.Counter(r["service"] for r in rs)
        print(f"| {name} | {len(rs)} | {sum(r['bytes'] for r in rs):,} | {rs[0]['t'][11:19]}~{rs[-1]['t'][11:19]} | "
              f"{', '.join(f'{k} {v}' for k, v in sorted(kinds.items()))} |")
    print()
    candidate_stage(rows)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
