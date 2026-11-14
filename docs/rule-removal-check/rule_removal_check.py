# 묻지 않게 하는 규칙을 하나씩 지운 임시 사본에서 :engine:test를 돌려, 그 규칙을 지키는 검사가 실패하는지 확인한다.
#
# 사용(저장소 맨 위에서): python docs/rule-removal-check/rule_removal_check.py [--jobs N] [--only 이름 ...]
# - JDK 17이 필요하다(JAVA_HOME). Android SDK는 필요 없다(사본의 settings.gradle.kts에서 :app을 뺀다).
# - 사본은 임시 폴더에 만들고 끝나면 지운다. 저장소 파일은 바꾸지 않는다.
# - 지울 줄은 줄 번호가 아니라 글자 그대로 찾는다. 소스가 바뀌어 한 곳에서 찾지 못하면 그 규칙은 '찾지 못함'으로 멈춘다.
# - 맨 처음에 아무것도 지우지 않은 사본(기준)이 모두 통과하는지 먼저 본다.
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
J = "engine/src/main/kotlin/app/deulleulkka/engine/MomentJudge.kt"
P = "SafetyPropertyTest"


@dataclass(frozen=True)
class Mutation:
    name: str
    file: str
    old: str      # 사본에서 찾을 글자(한 곳에만 있어야 함)
    new: str      # 바꿀 글자. 규칙을 지울 때는 ""
    expect: str   # 실패해야 하는 검사("클래스 :: 검사 이름"). 기준 사본은 ""


# 기획서·README가 "묻지 않게 하는 규칙"으로 든 것과, 그 밖에 엔진이 이동 단위로 막는 규칙
RULES = [
    Mutation("통화·안내 음성 중", J,
             '            ctx.otherAudioPlaying == true -> "통화 중이거나 안내 음성이 나오는 중" to Outcome.HOLD\n', "",
             f"{P} :: 통화 중이거나 안내 음성이 나오면 다른 값이 무엇이든 말을 걸지 않는다"),
    Mutation("확인할 수 없는 안전 신호", J,
             '            unknown.isNotEmpty() -> "${unknown.joinToString()} 확인 불가 (완전히 멈춘 때만 물음)" to Outcome.HOLD\n', "",
             f"{P} :: 확인할 수 없는 안전 신호가 하나라도 있으면 완전히 멈춘 때만 묻는다"),
    Mutation("회사 가는 길", J,
             '            dest.isOffice -> "목적지가 회사" to Outcome.SKIP\n', "",
             f"{P} :: 목적지가 회사면 다른 값이 무엇이든 묻지 않는다"),
    Mutation("이미 있는 경유지", J,
             '            ctx.waypointCount > 0 -> "$energyText, 이미 경유지 ${ctx.waypointCount}곳 있음" to Outcome.SKIP\n', "",
             f"{P} :: 이미 경유지가 있거나 잔량이 기준 아래면 묻지 않는다"),
    Mutation("낮은 잔량", J,
             '            energy != null && energy < settings.minEnergyPercent -> "$energyText (${settings.minEnergyPercent}% 아래)" to Outcome.SKIP\n', "",
             f"{P} :: 이미 경유지가 있거나 잔량이 기준 아래면 묻지 않는다"),
    Mutation("지나친 찜", J,
             '        if (isBehind(ctx, place)) return "이미 지나친 곳 (되돌아가야 함)" to Outcome.SKIP\n', "",
             f"{P} :: 진행 방향 뒤쪽의 찜은 다른 값이 무엇이든 묻지 않는다"),
    Mutation("시속 90km 이상", J,
             '            speed >= HIGH_SPEED_KPH -> "고속 주행 중 (시속 ${HIGH_SPEED_KPH.roundToInt()}km 이상)" to Outcome.HOLD\n', "",
             f"{P} :: 시속 90km 이상이면 다른 값이 무엇이든 묻지 않는다"),
    Mutation("판단 반경 밖", J,
             '        if (!inTriggerRadius(ctx, place)) {\n'
             '            val km = shownAgainst(toPlaceMeters / METERS_PER_KM, TRIGGER_RADIUS_KM, over = true, digits = 1)\n'
             '            return "찜까지 ${km}km (판단 반경 ${exactNumber(TRIGGER_RADIUS_KM)}km 밖)" to Outcome.SKIP\n'
             '        }\n', "",
             f"{P} :: 찜이 판단 반경 2km 밖이면 다른 값이 무엇이든 묻지 않는다"),
    Mutation("보류한 사이 반경 이탈", J,
             '        if (place.id in history.leftWhileHeldThisTrip) {\n'
             '            return "보류한 사이 반경을 벗어났던 곳 (이번 이동에서는 묻지 않음)" to Outcome.SKIP\n'
             '        }\n', "",
             f"{P} :: 보류한 사이 반경을 벗어난 찜은 그 이동에서는 다른 값이 무엇이든 묻지 않는다"),
]

BASELINE = Mutation("기준(지우지 않음)", J, "", "", "")
COPY_TOP = ["build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"]


def make_copy(dest: Path) -> None:
    shutil.copytree(ROOT / "engine", dest / "engine", ignore=shutil.ignore_patterns("build", ".gradle"))
    shutil.copytree(ROOT / "gradle", dest / "gradle")
    for name in COPY_TOP:
        shutil.copy2(ROOT / name, dest / name)
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    kept = [line for line in settings.splitlines(keepends=True) if line.strip() != 'include(":app")']
    (dest / "settings.gradle.kts").write_text("".join(kept), encoding="utf-8", newline="")


def line_of(text: str, old: str) -> int:
    return text[: text.index(old)].count("\n") + 1


def apply(dest: Path, m: Mutation) -> str:
    """사본에 변이를 넣고 '파일:줄'을 돌려준다. 찾지 못하거나 여러 곳이면 ValueError."""
    if not m.old:
        return "-"
    path = dest / m.file
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    count = text.count(m.old)
    if count != 1:
        raise ValueError(f"지울 글자를 {count}곳에서 찾음({m.file})")
    where = f"{Path(m.file).name}:{line_of(text, m.old)}"
    path.write_text(text.replace(m.old, m.new), encoding="utf-8", newline="")
    return where


def run_tests(dest: Path) -> tuple[int, list[str], int]:
    """(검사 수, 실패한 검사 목록, gradle 종료 코드)."""
    gradlew = str(dest / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    cmd = [gradlew, "--no-daemon", "-q", "-Pkotlin.compiler.execution.strategy=in-process", ":engine:test"]
    proc = subprocess.run(cmd, cwd=dest, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    total, failed = 0, []
    results = dest / "engine/build/test-results/test"
    for f in sorted(results.glob("TEST-*.xml")):
        suite = ET.parse(f).getroot()
        total += int(suite.get("tests"))
        cls = suite.get("name").split(".")[-1]
        for case in suite.findall("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(f"{cls} :: {case.get('name').removesuffix('()')}")
    return total, failed, proc.returncode


def check(m: Mutation) -> dict:
    dest = Path(tempfile.mkdtemp(prefix="rule-removal-"))
    try:
        make_copy(dest)
        where = apply(dest, m)
        total, failed, rc = run_tests(dest)
        if not m.expect:
            ok = rc == 0 and total > 0 and not failed
        else:
            ok = m.expect in failed
        return {"m": m, "where": where, "total": total, "failed": failed, "rc": rc, "ok": ok, "error": ""}
    except ValueError as e:
        return {"m": m, "where": "-", "total": 0, "failed": [], "rc": -1, "ok": False, "error": str(e)}
    finally:
        shutil.rmtree(dest, ignore_errors=True)


def report(r: dict) -> None:
    m = r["m"]
    if r["error"]:
        print(f"[찾지 못함] {m.name}: {r['error']}")
        return
    if not m.expect:
        state = "통과" if r["ok"] else "실패"
        print(f"[기준 {state}] 검사 {r['total']}개, 실패 {len(r['failed'])}개 (gradle 종료 코드 {r['rc']})")
        for name in r["failed"]:
            print(f"    실패: {name}")
        return
    state = "잡힘" if r["ok"] else "살아남음"
    print(f"[{state}] {m.name} — 지운 곳 {r['where']} — 검사 {r['total']}개 중 실패 {len(r['failed'])}개")
    print(f"    실패해야 하는 검사: {m.expect} → {'실패함' if r['ok'] else '통과함'}")
    for name in r["failed"]:
        if name != m.expect:
            print(f"    함께 실패: {name}")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="규칙을 하나씩 지운 사본에서 그 규칙의 검사가 실패하는지 확인한다.")
    parser.add_argument("--jobs", type=int, default=1, help="동시에 돌릴 사본 수(기본 1)")
    parser.add_argument("--only", nargs="*", help="이 이름의 규칙만 돌린다")
    args = parser.parse_args()
    rules = [m for m in RULES if not args.only or m.name in args.only]

    base = check(BASELINE)
    report(base)
    if not base["ok"]:
        print("기준 사본이 통과하지 않아 멈춤")
        return 2
    with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        results = list(pool.map(check, rules))
    for r in results:
        report(r)
    caught = sum(1 for r in results if r["ok"])
    print(f"규칙 {len(results)}개 가운데 {caught}개에서 그 규칙의 검사가 실패함(잡힘), {len(results) - caught}개는 아님")
    return 0 if caught == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
