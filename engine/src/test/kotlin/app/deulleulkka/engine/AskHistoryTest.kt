package app.deulleulkka.engine

import app.deulleulkka.engine.replay.Scenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 기획서의 응답 규칙 표(들를게, 다음에, 답 없음)와 방문 졸업 규칙을 옮긴 테스트. */
class AskHistoryTest {
    private val place = Scenarios.bakery.id
    private val day = 100L

    @Test
    fun `다음에라고 답하면 30일 동안 쉬고 그 뒤에 다시 묻는다`() {
        val history = AskHistory().record(place, Answer.LATER, day).startNewTrip()
        assertTrue(history.isSnoozed(place, day + 29))
        assertFalse(history.isSnoozed(place, day + 30))

        val judge = MomentJudge()
        val calm = Scenarios.saturdayOuting.last().ctx
        assertIs<Verdict.Skip>(judge.judge(calm, Scenarios.bakery, history, day + 29).verdict)
        assertIs<Verdict.Ask>(judge.judge(calm, Scenarios.bakery, history, day + 30).verdict)
    }

    @Test
    fun `답 없이 닫힌 일이 같은 곳에서 두 번 쌓이면 다음에로 보고 30일 쉰다`() {
        val once = AskHistory().record(place, Answer.NO_ANSWER, day).startNewTrip()
        assertFalse(once.isSnoozed(place, day + 1))

        val twice = once.record(place, Answer.NO_ANSWER, day + 1)
        assertTrue(twice.isSnoozed(place, day + 2))
        assertFalse(twice.isSnoozed(place, day + 31))
    }

    @Test
    fun `들르기로 하면 답 없던 횟수를 지운다`() {
        val history = AskHistory()
            .record(place, Answer.NO_ANSWER, day).startNewTrip()
            .record(place, Answer.ACCEPT, day + 1).startNewTrip()
            .record(place, Answer.NO_ANSWER, day + 2)
        assertFalse(history.isSnoozed(place, day + 3))
    }

    @Test
    fun `넣은 뒤 취소하면 그 이동에서는 다시 묻지 않고 다음 이동에서는 다시 묻는다`() {
        val judge = MomentJudge()
        val calm = Scenarios.saturdayOuting.last().ctx
        val cancelled = AskHistory().record(place, Answer.CANCEL, day)
        assertIs<Verdict.Skip>(judge.judge(calm, Scenarios.bakery, cancelled, day).verdict)
        assertFalse(cancelled.isSnoozed(place, day + 1))
        assertIs<Verdict.Ask>(judge.judge(calm, Scenarios.bakery, cancelled.startNewTrip(), day + 1).verdict)
    }

    @Test
    fun `답이 없던 것도 이번 이동에서 물은 횟수에 센다`() {
        val history = AskHistory().record("a", Answer.NO_ANSWER, day).record("b", Answer.ACCEPT, day)
        assertEquals(listOf("a", "b"), history.askedThisTrip)
    }

    @Test
    fun `새 이동을 시작하면 횟수는 비우고 쉬는 곳은 그대로 둔다`() {
        val history = AskHistory().record(place, Answer.LATER, day).startNewTrip()
        assertEquals(emptyList(), history.askedThisTrip)
        assertTrue(history.isSnoozed(place, day + 1))
    }

    @Test
    fun `도착해 10분 넘게 머물면 다녀온 곳이고 바로 떠나면 가 볼 곳에 남는다`() {
        assertTrue(VisitRule.isVisited(dwellMinutes = 20))
        assertFalse(VisitRule.isVisited(dwellMinutes = 10))
        assertFalse(VisitRule.isVisited(dwellMinutes = 2))
    }
}
