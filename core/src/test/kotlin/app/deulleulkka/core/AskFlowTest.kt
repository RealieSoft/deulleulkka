package app.deulleulkka.core

import app.deulleulkka.engine.DriveContext
import app.deulleulkka.engine.MomentJudge
import app.deulleulkka.engine.Place
import app.deulleulkka.engine.replay.Scenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기획서 응답 표(들를게, 다음에, 답 없음, 받지 않는 말, 취소)와 묻는 동안의 멈춤 규칙을 옮긴 테스트. */
class AskFlowTest {
    private val flow = AskFlow()
    private val bakery = Scenarios.bakery

    /** 시나리오 A의 묻는 순간(직선 구간, 동승자 있음). 엔진이 약 6분 우회를 묻는다. */
    private val calm = Scenarios.saturdayOuting.last().ctx
    private val today = 100L

    private fun tick(
        s: FlowState,
        now: Long,
        ctx: DriveContext = calm,
        visible: Boolean = true,
        parked: Boolean = false,
        candidates: List<Place> = listOf(bakery),
        voiceBusy: Boolean = false,
        voiceReady: Boolean = true,
    ): FlowStep = flow.reduce(s, FlowEvent.Tick(ctx, candidates, visible, parked, now, today, voiceBusy, voiceReady))

    private fun heard(s: FlowState, text: String, now: Long): FlowStep = flow.reduce(s, FlowEvent.Heard(text, now, today))

    private fun asked(s: FlowState = FlowState()): FlowStep = tick(s, 0L)

    private fun listening(): FlowState {
        val a = asked().state
        val id = assertIs<Phase.Speaking>(a.phase).id
        return flow.reduce(a, FlowEvent.SpeechDone(id, 1_000L)).state
    }

    private fun added(): FlowState {
        val adding = heard(listening(), "들를게", 2_000L).state
        return flow.reduce(adding, FlowEvent.WaypointAdded(bakery.id, 9_000L)).state
    }

    private inline fun <reified T> FlowStep.only(): List<T> = commands.filterIsInstance<T>()

    @Test
    fun `엔진이 물어도 된다고 하면 카드를 띄우고 처음에는 답 낱말 안내를 붙여 묻는다`() {
        val step = asked()
        val speaking = assertIs<Phase.Speaking>(step.state.phase)
        val card = assertIs<FlowCommand.ShowCard>(step.commands[0]).card as Card.Ask
        assertEquals(bakery, card.place)
        assertFalse(card.silent)
        assertFalse(card.buttons)
        assertEquals(MomentJudge.question(bakery, card.detourMin, MomentJudge().allowedDetourMin(calm)), card.question)
        assertEquals(FlowCommand.Speak(speaking.id, "${card.question} ${Lines.HINT}"), step.commands[1])
        assertEquals(1, step.state.questionsAsked)
    }

    @Test
    fun `답 낱말 안내는 처음 3번만 붙인다`() {
        val step = asked(FlowState(questionsAsked = Timing.HINT_QUESTIONS))
        val speak = step.only<FlowCommand.Speak>().single()
        assertFalse(speak.text.contains(Lines.HINT))
    }

    @Test
    fun `물을 찜이 없거나 창이 가려져 있으면 묻지 않는다`() {
        assertEquals(emptyList(), tick(FlowState(), 0L, candidates = emptyList()).commands)
        assertEquals(emptyList(), tick(FlowState(), 0L, visible = false).commands)
    }

    @Test
    fun `다 말하면 그때부터 8초 동안 듣는다`() {
        val a = asked().state
        val id = assertIs<Phase.Speaking>(a.phase).id
        val step = flow.reduce(a, FlowEvent.SpeechDone(id, 1_000L))
        assertEquals(listOf(FlowCommand.Listen(1_000L + Timing.LISTEN_MS)), step.commands)
        assertIs<Phase.Listening>(step.state.phase)
        // 다른 말의 끝 알림은 무시한다
        assertEquals(emptyList(), flow.reduce(a, FlowEvent.SpeechDone(id + 99, 1_000L)).commands)
    }

    @Test
    fun `말 끝 알림이 오지 않으면 15초 뒤 듣기로 넘어간다`() {
        val step = tick(asked().state, Timing.SPEAK_TIMEOUT_MS)
        assertIs<Phase.Listening>(step.state.phase)
        assertEquals(listOf(FlowCommand.Listen(Timing.SPEAK_TIMEOUT_MS + Timing.LISTEN_MS)), step.commands)
    }

    @Test
    fun `들를게라고 하면 경유지를 넣고 다시 계산된 값이 오면 넣었어요라고 말한다`() {
        val step = heard(listening(), "들를게", 2_000L)
        assertTrue(FlowCommand.AddWaypoint(bakery) in step.commands)
        assertTrue(FlowCommand.ShowCard(Card.Adding(bakery)) in step.commands)
        assertEquals(bakery, step.state.pendingAccept)

        val added = flow.reduce(step.state, FlowEvent.WaypointAdded(bakery.id, 9_000L))
        assertEquals(listOf(FlowCommand.ShowCard(Card.Added(bakery, buttons = false))), added.commands)
        val announce = tick(added.state, 9_500L)
        assertEquals(Lines.ADDED, announce.only<FlowCommand.Speak>().single().text)
        // 말한 뒤에는 "취소"를 듣고, 그때 카드에 "취소"를 보인다(마이크가 닫힌 동안에는 보이지 않음)
        val announceId = assertIs<Phase.Added>(announce.state.phase).announceId
        val listen = flow.reduce(announce.state, FlowEvent.SpeechDone(announceId, 12_000L))
        assertEquals(
            listOf(
                FlowCommand.Listen(maxOf(9_000L + Timing.CARD_MS, 12_000L + Timing.LISTEN_MS)),
                FlowCommand.ShowCard(Card.Added(bakery, buttons = false, listening = true)),
            ),
            listen.commands,
        )
        // 주차 상태가 바뀌어 카드를 다시 띄울 때도 듣는 중임을 잃지 않는다
        val parked = tick(listen.state, 13_000L, ctx = calm.copy(speedKph = 0.0), parked = true)
        assertTrue(FlowCommand.ShowCard(Card.Added(bakery, buttons = true, listening = true)) in parked.commands)
    }

    @Test
    fun `넣었어요는 다음 회전이 가까우면 여유 구간에서 말한다`() {
        val near = tick(added(), 9_500L, ctx = calm.copy(nextTurnMeters = 200))
        assertEquals(emptyList(), near.only<FlowCommand.Speak>())
        val later = tick(near.state, 10_500L)
        assertEquals(Lines.ADDED, later.only<FlowCommand.Speak>().single().text)
    }

    @Test
    fun `넣은 뒤 취소하면 경유지를 빼고 그 이동에서는 다시 묻지 않는다`() {
        val step = heard(added(), "취소", 10_000L)
        assertTrue(FlowCommand.RemoveWaypoint(bakery) in step.commands)
        // 뺐다는 말은 내비가 뺐다고 알린 뒤에 한다
        assertEquals(emptyList(), step.only<FlowCommand.Speak>())
        assertIs<Phase.Removing>(step.state.phase)
        assertNull(step.state.pendingAccept)
        assertEquals(listOf(bakery.id), step.state.history.askedThisTrip)
        assertFalse(step.state.history.isSnoozed(bakery.id, today + 1))
        val removed = flow.reduce(step.state, FlowEvent.WaypointRemoved(bakery.id))
        assertEquals(Lines.REMOVED, removed.only<FlowCommand.Speak>().single().text)
        assertIs<Phase.Idle>(removed.state.phase)
        // 같은 이동에서는 다시 묻지 않는다
        assertEquals(emptyList(), tick(removed.state, 20_000L).only<FlowCommand.Speak>())
    }

    @Test
    fun `경유지를 빼지 못하면 뺐다고 하지 않고 내비에서 빼 달라고 한다`() {
        val removing = heard(added(), "취소", 10_000L).state
        val failed = flow.reduce(removing, FlowEvent.WaypointFailed(bakery.id, 11_000L, today))
        assertEquals(Lines.REMOVE_FAILED, failed.only<FlowCommand.Speak>().single().text)
        assertIs<Phase.Idle>(failed.state.phase)
        // 알림이 오지 않고 기다림이 끝나도 같다
        val timedOut = tick(removing, 10_000L + Timing.REMOVE_TIMEOUT_MS)
        assertEquals(Lines.REMOVE_FAILED, timedOut.only<FlowCommand.Speak>().single().text)
        assertIs<Phase.Idle>(timedOut.state.phase)
        // 빼는 중에는 다른 말을 받지 않는다
        assertEquals(emptyList(), heard(removing, "취소", 11_000L).commands)
    }

    @Test
    fun `카드가 닫힌 뒤에도 그 이동이 끝날 때까지 취소를 받는다`() {
        val closed = tick(added(), 9_000L + Timing.CARD_MS)
        assertEquals(listOf(FlowCommand.HideCard), closed.commands)
        assertIs<Phase.Idle>(closed.state.phase)
        val listen = tick(closed.state, 20_000L)
        assertEquals(listOf(FlowCommand.Listen(20_000L + Timing.CANCEL_LISTEN_MS)), listen.commands)
        // 넣은 곳이 있는 동안에는 새로 묻지 않는다
        assertEquals(emptyList(), listen.only<FlowCommand.ShowCard>())
        val cancel = heard(listen.state, "취소", 25_000L)
        assertTrue(FlowCommand.RemoveWaypoint(bakery) in cancel.commands)
    }

    @Test
    fun `이동이 끝나면 들를게를 그 질문의 답으로 남긴다`() {
        val withNoAnswer = added().let { it.copy(history = it.history.copy(noAnswerCount = mapOf(bakery.id to 1))) }
        val ended = flow.reduce(withNoAnswer, FlowEvent.TripEnded(today))
        assertNull(ended.state.pendingAccept)
        // ACCEPT는 답 없던 횟수를 지운다
        assertNull(ended.state.history.noAnswerCount[bakery.id])
        assertEquals(listOf(bakery.id), ended.state.history.askedThisTrip)
        val next = flow.reduce(ended.state, FlowEvent.TripStarted(today + 1))
        assertEquals(emptyList(), next.state.history.askedThisTrip)
    }

    @Test
    fun `다음에라고 하면 30일 쉬고 한 달 뒤에 다시 물을게요라고 말한다`() {
        val step = heard(listening(), "다음에", 2_000L)
        assertEquals("한 달 뒤에 다시 물을게요.", step.only<FlowCommand.Speak>().single().text)
        assertTrue(FlowCommand.HideCard in step.commands)
        assertTrue(step.state.history.isSnoozed(bakery.id, today + 29))
        assertFalse(step.state.history.isSnoozed(bakery.id, today + 30))
    }

    @Test
    fun `8초 동안 답이 없으면 답 없음으로 남기고 닫는다`() {
        val step = tick(listening(), 1_000L + Timing.LISTEN_MS)
        assertEquals(listOf(FlowCommand.StopSpeaking, FlowCommand.StopListening, FlowCommand.HideCard), step.commands)
        assertEquals(1, step.state.history.noAnswerCount[bakery.id])
    }

    @Test
    fun `받지 않는 말을 들으면 한 번만 알림음과 안내를 보이고 닫힐 때 30일 계산에 세지 않는다`() {
        val first = heard(listening(), "응", 2_000L)
        assertEquals(FlowCommand.Beep, first.commands[0])
        val card = assertIs<FlowCommand.ShowCard>(first.commands[1]).card as Card.Ask
        assertTrue(card.retryHint)
        assertEquals(emptyList(), heard(first.state, "네", 3_000L).commands)
        val closed = tick(first.state, 1_000L + Timing.LISTEN_MS)
        assertNull(closed.state.history.noAnswerCount[bakery.id])
        assertEquals(listOf(bakery.id), closed.state.history.askedThisTrip)
    }

    @Test
    fun `묻는 중에 들은 취소도 받지 않는 말로 본다`() {
        val step = heard(listening(), "취소", 2_000L)
        assertEquals(FlowCommand.Beep, step.commands[0])
        assertEquals(emptyList(), step.only<FlowCommand.RemoveWaypoint>())
    }

    @Test
    fun `통화와 안내 음성을 확인할 수 없으면 소리 없이 카드만 띄우고 마이크를 열지 않는다`() {
        // 확인할 수 없는 신호가 있으면 엔진은 완전히 멈춘 때만 묻는다
        val unknownAudio = calm.copy(speedKph = 0.0, otherAudioPlaying = null)
        val step = tick(FlowState(), 0L, ctx = unknownAudio)
        val card = assertIs<FlowCommand.ShowCard>(step.commands.single()).card as Card.Ask
        assertTrue(card.silent)
        assertIs<Phase.Silent>(step.state.phase)
        assertEquals(emptyList(), heard(step.state, "들를게", 1_000L).commands)
        // 주행 중(P 아님)이라 답할 방법이 없었으므로 30일 계산에 세지 않는다
        val closed = tick(step.state, Timing.CARD_MS, ctx = unknownAudio)
        assertNull(closed.state.history.noAnswerCount[bakery.id])
        assertEquals(listOf(bakery.id), closed.state.history.askedThisTrip)
    }

    @Test
    fun `무음 카드도 주차 중이면 버튼으로 답을 받는다`() {
        val unknownAudio = calm.copy(speedKph = 0.0, otherAudioPlaying = null)
        val step = tick(FlowState(), 0L, ctx = unknownAudio, parked = true)
        assertTrue((assertIs<FlowCommand.ShowCard>(step.commands.single()).card as Card.Ask).buttons)
        val pressed = flow.reduce(step.state, FlowEvent.Pressed(Choice.ACCEPT, 1_000L, today))
        assertTrue(FlowCommand.AddWaypoint(bakery) in pressed.commands)
    }

    @Test
    fun `주차가 아니면 버튼을 받지 않는다`() {
        assertEquals(emptyList(), flow.reduce(listening(), FlowEvent.Pressed(Choice.ACCEPT, 2_000L, today)).commands)
    }

    @Test
    fun `묻는 도중 방향지시등이 켜지면 말을 멈추고 기록하지 않는다`() {
        val step = tick(asked().state, 500L, ctx = calm.copy(turnSignalOn = true))
        assertEquals(listOf(FlowCommand.StopSpeaking, FlowCommand.StopListening, FlowCommand.HideCard), step.commands)
        assertEquals(emptyList(), step.state.history.askedThisTrip)
        assertIs<Phase.Idle>(step.state.phase)
    }

    @Test
    fun `답을 듣는 중에 창이 가려지면 멈추고 30일 계산에 세지 않는다`() {
        val step = tick(listening(), 2_000L, visible = false)
        assertTrue(FlowCommand.StopListening in step.commands)
        assertNull(step.state.history.noAnswerCount[bakery.id])
        assertEquals(listOf(bakery.id), step.state.history.askedThisTrip)
    }

    @Test
    fun `경유지를 넣지 못하면 알리고 30일 계산에 세지 않는다`() {
        val adding = heard(listening(), "들를게", 2_000L).state
        val failed = flow.reduce(adding, FlowEvent.WaypointFailed(bakery.id, 3_000L, today))
        assertEquals(Lines.ADD_FAILED, failed.only<FlowCommand.Speak>().single().text)
        assertNull(failed.state.pendingAccept)
        assertNull(failed.state.history.noAnswerCount[bakery.id])
        // 기다리다 끝나도 넣지 못했다고 알리고, 늦게 들어가면 말없이 뺀다
        val timedOut = tick(adding, 2_000L + Timing.ADD_TIMEOUT_MS)
        assertEquals(Lines.ADD_FAILED, timedOut.only<FlowCommand.Speak>().single().text)
        assertEquals(emptyList(), timedOut.only<FlowCommand.RemoveWaypoint>())
        val late = flow.reduce(timedOut.state, FlowEvent.WaypointAdded(bakery.id, 30_000L))
        assertEquals(listOf(FlowCommand.RemoveWaypoint(bakery)), late.commands)
        val cleaned = flow.reduce(late.state, FlowEvent.WaypointRemoved(bakery.id))
        assertEquals(emptyList(), cleaned.commands)
        assertIs<Phase.Idle>(cleaned.state.phase)
        // 지켜보는 시간 안에 나타나지 않으면 말없이 끝낸다
        val quiet = tick(timedOut.state, 2_000L + Timing.ADD_TIMEOUT_MS + Timing.CLEANUP_WATCH_MS)
        assertEquals(emptyList(), quiet.commands)
        assertIs<Phase.Idle>(quiet.state.phase)
    }

    @Test
    fun `넣는 중에 취소하면 다 넣은 뒤에 뺀다`() {
        val adding = heard(listening(), "들를게", 2_000L).state
        val cancelled = heard(adding, "취소", 3_000L)
        // 아직 넣는 중이라 지금 빼라고 하면 넣기보다 먼저 닿을 수 있다
        assertEquals(emptyList(), cancelled.only<FlowCommand.RemoveWaypoint>())
        assertEquals(emptyList(), cancelled.only<FlowCommand.Speak>())
        val added = flow.reduce(cancelled.state, FlowEvent.WaypointAdded(bakery.id, 9_000L))
        assertEquals(listOf(FlowCommand.RemoveWaypoint(bakery)), added.commands)
        val removed = flow.reduce(added.state, FlowEvent.WaypointRemoved(bakery.id))
        assertEquals(Lines.REMOVED, removed.only<FlowCommand.Speak>().single().text)
        assertIs<Phase.Idle>(removed.state.phase)
    }

    @Test
    fun `넣는 중에 취소했는데 넣지 못했으면 뺄 것이 없다`() {
        val adding = heard(listening(), "들를게", 2_000L).state
        val cancelled = heard(adding, "취소", 3_000L).state
        val failed = flow.reduce(cancelled, FlowEvent.WaypointFailed(bakery.id, 4_000L, today))
        assertEquals(emptyList(), failed.only<FlowCommand.RemoveWaypoint>())
        assertEquals(Lines.REMOVED, failed.only<FlowCommand.Speak>().single().text)
        // 실패 뒤 늦게 들어가면 말없이 뺀다
        val late = flow.reduce(failed.state, FlowEvent.WaypointAdded(bakery.id, 9_000L))
        assertEquals(listOf(FlowCommand.RemoveWaypoint(bakery)), late.commands)
        // 넣었다는 알림도 실패도 오지 않으면 넣었을 수도 있어 빼 본다
        val waited = tick(cancelled, 3_000L + Timing.REMOVE_TIMEOUT_MS)
        assertEquals(listOf(FlowCommand.RemoveWaypoint(bakery)), waited.only<FlowCommand.RemoveWaypoint>())
        assertIs<Phase.Removing>(waited.state.phase)
    }

    @Test
    fun `주차 상태가 바뀌면 카드의 버튼을 맞춰 다시 띄운다`() {
        val step = tick(listening(), 2_000L, ctx = calm.copy(speedKph = 0.0), parked = true)
        val card = assertIs<FlowCommand.ShowCard>(step.commands.single()).card as Card.Ask
        assertTrue(card.buttons)
    }

    @Test
    fun `음성이 아직 말하는 중이면 새로 묻지 않는다`() {
        assertEquals(emptyList(), tick(FlowState(), 0L, voiceBusy = true).commands)
        assertTrue(tick(FlowState(), 0L).only<FlowCommand.Speak>().isNotEmpty())
    }

    @Test
    fun `음성 서비스가 준비되지 않으면 소리 없이 카드만 띄우고 답 없음으로 세지 않는다`() {
        val step = tick(FlowState(), 0L, voiceReady = false)
        assertEquals(emptyList(), step.only<FlowCommand.Speak>())
        assertTrue(assertIs<Card.Ask>(step.only<FlowCommand.ShowCard>().single().card).silent)
        val closed = tick(step.state, Timing.CARD_MS, voiceReady = false)
        assertIs<Phase.Idle>(closed.state.phase)
        assertNull(closed.state.history.noAnswerCount[bakery.id])
    }

    @Test
    fun `주차 중 들를게 뒤 출발해 새 이동이 시작돼도 취소를 받는다`() {
        val adding = heard(listening(), "들를게", 2_000L).state
        val added = flow.reduce(adding, FlowEvent.WaypointAdded(bakery.id, 9_000L)).state
        val started = flow.reduce(added, FlowEvent.TripStarted(today))
        assertEquals(bakery, started.state.pendingAccept)
        assertIs<Phase.Added>(started.state.phase)
        assertTrue(FlowCommand.RemoveWaypoint(bakery) in heard(started.state, "취소", 10_000L).commands)
    }

    @Test
    fun `가려지면 취소 듣기 시간을 비워 다시 보일 때 바로 연다`() {
        val closed = tick(added(), 9_000L + Timing.CARD_MS).state
        val listen = tick(closed, 20_000L)
        assertEquals(20_000L + Timing.CANCEL_LISTEN_MS, listen.state.cancelListenUntil)
        val hidden = tick(listen.state, 21_000L, visible = false)
        assertEquals(0L, hidden.state.cancelListenUntil)
        assertEquals(listOf(FlowCommand.Listen(25_000L + Timing.CANCEL_LISTEN_MS)), tick(hidden.state, 25_000L).commands)
    }

    @Test
    fun `통화나 안내 음성 중에는 취소 듣기를 닫는다`() {
        val closed = tick(added(), 9_000L + Timing.CARD_MS).state
        val listen = tick(closed, 20_000L).state
        val call = tick(listen, 21_000L, ctx = calm.copy(otherAudioPlaying = true))
        assertEquals(listOf(FlowCommand.StopListening), call.commands)
        assertEquals(0L, call.state.cancelListenUntil)
        assertEquals(emptyList(), tick(call.state, 22_000L, ctx = calm.copy(otherAudioPlaying = true)).commands)
    }

    @Test
    fun `넣은 경유지가 경로에서 사라지면 들를게로 남기고 듣기를 멈춘다`() {
        val closed = tick(added(), 9_000L + Timing.CARD_MS).state
        val listen = tick(closed, 20_000L).state
        val gone = flow.reduce(listen, FlowEvent.WaypointGone(bakery.id, today))
        assertNull(gone.state.pendingAccept)
        assertEquals(0L, gone.state.cancelListenUntil)
        assertTrue(FlowCommand.StopListening in gone.commands)
        assertEquals(listOf(bakery.id), gone.state.history.askedThisTrip)
        // 다른 곳의 알림은 무시한다
        assertEquals(emptyList(), flow.reduce(listen, FlowEvent.WaypointGone("other", today)).commands)
    }
}
