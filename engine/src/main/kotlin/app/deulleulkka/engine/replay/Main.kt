package app.deulleulkka.engine.replay

import app.deulleulkka.engine.AskHistory
import app.deulleulkka.engine.Decision
import app.deulleulkka.engine.MomentJudge
import app.deulleulkka.engine.Place
import app.deulleulkka.engine.Verdict
import kotlin.math.roundToInt

/** 시나리오를 차례로 재생하며 판단과 그 근거를 글로 찍는다. 실행: ./gradlew :engine:run */
fun main() {
    val judge = MomentJudge()
    replay("시나리오 D → A. 토요일 나들이: 미뤘다가 묻는다", Scenarios.saturdayOuting, Scenarios.bakery, judge)
    replay("시나리오 C. 월요일 출근길: 조용하다", Scenarios.mondayCommute, Scenarios.bakery, judge)
    replay("목적지 없는 주행: 묻지 않는다", Scenarios.noDestination, Scenarios.bakery, judge)
}

private fun replay(title: String, frames: List<Frame>, place: Place, judge: MomentJudge) {
    println("=== $title ===")
    for (frame in frames) {
        val decision = judge.judge(frame.ctx, place, AskHistory(), today = 0)
        println("[${frame.clock}] ${frame.note} → ${describe(decision, place)}")
        for (check in decision.checks) {
            println("    ${check.step.label} | ${check.question} | ${check.seen} | ${check.outcome.label}")
        }
    }
    println()
}

private fun describe(decision: Decision, place: Place): String = when (val verdict = decision.verdict) {
    is Verdict.Ask ->
        "묻는다: \"찜해 둔 ${place.name}가 가는 길 근처에 있어요. 약 ${verdict.detourMin.roundToInt()}분 더 걸리는데 들를까요?\""
    is Verdict.Hold -> "보류 (${verdict.reason})"
    is Verdict.Skip -> "묻지 않음 (${verdict.reason})"
}
