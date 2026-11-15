package app.deulleulkka.core

import app.deulleulkka.engine.Answer
import app.deulleulkka.engine.AskHistory
import app.deulleulkka.engine.Decision
import app.deulleulkka.engine.DriveContext
import app.deulleulkka.engine.MomentJudge
import app.deulleulkka.engine.Outcome
import app.deulleulkka.engine.Place
import app.deulleulkka.engine.Settings
import app.deulleulkka.engine.Step
import app.deulleulkka.engine.Verdict

/** 차가 하는 말과 카드 글. 기획서 응답 표의 문장 그대로. */
object Lines {
    const val HINT = "들를게나 다음에로 답해 주세요."
    const val CARD_HINT = "들를게 / 다음에로 답해 주세요"
    const val ADDED = "넣었어요. 빼려면 취소라고 하세요."
    const val REMOVED = "뺐어요."
    const val ADD_FAILED = "경유지를 넣지 못했어요."

    /** 기획서 응답 표에 없는 문장. 빼지 못했는데 "뺐어요"라고 하지 않으려고 더했다(2026-11-15). */
    const val REMOVE_FAILED = "경유지를 빼지 못했어요. 내비에서 빼 주세요."

    /** "다음에" 뒤의 말. 쉬는 날수가 기본 30일이면 "한 달 뒤". */
    fun later(snoozeDays: Int): String =
        if (snoozeDays == MONTH_DAYS) "한 달 뒤에 다시 물을게요." else "${snoozeDays}일 뒤에 다시 물을게요."

    private const val MONTH_DAYS = 30
}

/** 시간 값(ms). 기획서 응답 표와 에뮬레이터 측정에서 정한 것. */
object Timing {
    /** 차의 말이 끝난 때부터 답을 받는 시간. */
    const val LISTEN_MS = 8_000L

    /** 무음 카드와 넣은 뒤 카드가 떠 있는 시간. */
    const val CARD_MS = 8_000L

    /** 질문을 말하기 시작한 뒤 끝났다는 알림이 이만큼 오지 않으면 끝난 것으로 보고 듣기로 넘어간다(음성 서비스가 알림을 잃은 때). */
    const val SPEAK_TIMEOUT_MS = 15_000L

    /** 경유지를 넣고 다시 계산된 값을 기다리는 한도. 에뮬레이터에서 6.2~7.1초 걸렸다. */
    const val ADD_TIMEOUT_MS = 20_000L

    /** 경유지를 빼고 뺐다는 알림을 기다리는 한도. 에뮬레이터에서 0.4초 걸렸으나 넣을 때처럼 경로를 다시 계산하므로 같은 한도를 둔다. */
    const val REMOVE_TIMEOUT_MS = 20_000L

    /** 넣지 못했다고 알린 뒤에도 그 경유지가 늦게 들어가는지 지켜보는 시간. 들어가면 말없이 뺀다(실차는 다시 계산이 더 걸릴 수 있음). */
    const val CLEANUP_WATCH_MS = 60_000L

    /** 넣은 뒤 그 이동이 끝날 때까지 "취소"를 받으려고 다시 여는 듣기 한 번의 길이. */
    const val CANCEL_LISTEN_MS = 30_000L

    /** 답 낱말 안내를 질문 끝에 붙이는 횟수(처음 3번). */
    const val HINT_QUESTIONS = 3
}

/** 주차(P)일 때 화면 버튼으로 받는 답. */
enum class Choice(val kind: AnswerKind) { ACCEPT(AnswerKind.ACCEPT), LATER(AnswerKind.LATER), CANCEL(AnswerKind.CANCEL) }

/** 앱이 보여 줄 카드. 모양은 앱이 정하고, 여기서는 무엇을 보여 줄지만 정한다. */
sealed interface Card {
    /**
     * 묻는 카드. [silent]면 소리 없이 띄운 카드(마이크도 닫힘). [retryHint]면 받지 않는 말을 들은 뒤라 [Lines.CARD_HINT]를 함께 보인다.
     * [buttons]면 주차(P)라 "들를게"·"다음에" 버튼을 보인다.
     */
    data class Ask(
        val place: Place,
        val detourMin: Double,
        val question: String,
        val silent: Boolean,
        val retryHint: Boolean,
        val buttons: Boolean,
        /** 카드에 보일 추가 시간(분) 글. 묻는 말과 같은 숫자(MomentJudge.detourMinutesText). null이면 "가는 길에 있어요". */
        val minutesText: String? = null,
    ) : Card

    /** 경유지를 넣는 중. */
    data class Adding(val place: Place) : Card

    /**
     * 넣은 뒤. [buttons]면 주차(P)라 "취소" 버튼을 보인다. [listening]이면 "취소"를 듣는 중이라 말로 답하는 안내("취소")를 보인다
     * ("넣었어요"를 말하기 전·말하는 동안은 마이크가 닫혀 있어 보이지 않는다).
     */
    data class Added(val place: Place, val buttons: Boolean, val listening: Boolean = false) : Card
}

/** 앱(Android)에 시키는 일. 앱은 받은 순서대로 한다. */
sealed interface FlowCommand {
    /** [text]를 말한다. 다 말하면 앱이 [FlowEvent.SpeechDone]에 같은 [id]를 실어 보낸다. */
    data class Speak(val id: Long, val text: String) : FlowCommand
    data object StopSpeaking : FlowCommand

    /** [untilMs]까지 듣고, 최종 결과 덩어리마다 [FlowEvent.Heard]를 보낸다. */
    data class Listen(val untilMs: Long) : FlowCommand
    data object StopListening : FlowCommand
    data class ShowCard(val card: Card) : FlowCommand
    data object HideCard : FlowCommand

    /** 받지 않는 말을 들었을 때의 짧은 알림음. */
    data object Beep : FlowCommand
    data class AddWaypoint(val place: Place) : FlowCommand
    data class RemoveWaypoint(val place: Place) : FlowCommand
}

/** 앱이 넣는 일. 시각([nowMs])은 앱의 단조 시계, 날짜([today])는 기준일부터 센 날수. */
sealed interface FlowEvent {
    /**
     * 1초쯤마다 보내는 지금의 맥락. [candidates]는 영역 알림으로 들어온 찜(다녀온 곳·고르지 않은 곳 제외).
     * [visible]은 들를까 창이 화면에 보이는지, [parked]는 기어가 P인지.
     * [voiceBusy]는 들를까나 다른 음성 비서가 아직 말하는 중인지(끝나기 전에는 새로 묻지 않는다. TTS 끝 알림에는 어느 말인지가 없어,
     * 앞 말의 끝 알림이 다음 질문의 끝으로 잡히지 않게 한다). [voiceReady]는 말하기·듣기가 준비됐는지(아니면 소리 없이 카드만).
     */
    data class Tick(
        val ctx: DriveContext,
        val candidates: List<Place>,
        val visible: Boolean,
        val parked: Boolean,
        val nowMs: Long,
        val today: Long,
        val voiceBusy: Boolean = false,
        val voiceReady: Boolean = true,
    ) : FlowEvent

    data class SpeechDone(val id: Long, val nowMs: Long) : FlowEvent

    /** 인식기의 최종 결과 한 덩어리. */
    data class Heard(val text: String, val nowMs: Long, val today: Long) : FlowEvent

    /** 화면 버튼. 주차(P)일 때만 받는다. */
    data class Pressed(val choice: Choice, val nowMs: Long, val today: Long) : FlowEvent
    data class WaypointAdded(val placeId: String, val nowMs: Long) : FlowEvent
    /** 경유지를 넣거나 빼지 못함. */
    data class WaypointFailed(val placeId: String, val nowMs: Long, val today: Long) : FlowEvent

    /** 내비가 경유지를 뺐다고 알림(또는 경유지 목록에서 빠진 것을 앱이 확인함). */
    data class WaypointRemoved(val placeId: String) : FlowEvent

    /** "들를게"로 넣어 둔 경유지가 경로에서 사라짐(그곳에 닿았거나 운전자가 내비에서 뺌). 더는 "취소"를 받지 않는다. */
    data class WaypointGone(val placeId: String, val today: Long) : FlowEvent

    /** 위치 SDK의 영역 이탈 알림. */
    data class RadiusExit(val placeId: String) : FlowEvent

    /** 시동을 켠 뒤 처음 기어 P에서 벗어남. */
    data class TripStarted(val today: Long) : FlowEvent

    /** 시동을 끔. */
    data class TripEnded(val today: Long) : FlowEvent
}

/** 지금 하는 일. */
sealed interface Phase {
    data object Idle : Phase

    /** 질문을 말하는 중. */
    data class Speaking(val id: Long, val card: Card.Ask, val startedAt: Long) : Phase

    /** 질문을 다 말하고 답을 듣는 중. [heardOther]는 받지 않는 말을 이미 한 번 들었는지. */
    data class Listening(val card: Card.Ask, val until: Long, val heardOther: Boolean = false) : Phase

    /** 통화·안내 음성을 확인할 수 없어 소리 없이 띄운 카드. 마이크는 열지 않고 주차(P)일 때만 버튼으로 받는다. */
    data class Silent(val card: Card.Ask, val until: Long) : Phase

    /** 경유지를 넣고 다시 계산된 값을 기다리는 중. */
    data class Adding(val place: Place, val until: Long) : Phase

    /** 넣은 뒤 카드. [announceId]가 0이면 "넣었어요"를 아직 말하지 않음(다음 회전이 가까우면 여유 구간에서 말함). */
    data class Added(val place: Place, val cardUntil: Long, val announceId: Long = 0L) : Phase

    /**
     * "취소" 뒤 경유지를 빼고 내비의 알림을 기다리는 중. [waitingAdd]면 아직 넣는 중이라, 넣었다는 알림이 온 뒤에 뺀다
     * (지금 빼라고 하면 넣기보다 먼저 닿아 넣은 것이 남을 수 있음).
     * [quiet]면 넣지 못했다고 이미 알린 뒤 늦게 들어간 경유지를 치우는 중이라 "뺐어요"를 말하지 않는다.
     */
    data class Removing(val place: Place, val until: Long, val waitingAdd: Boolean, val quiet: Boolean = false) : Phase
}

data class FlowState(
    val history: AskHistory = AskHistory(),
    val phase: Phase = Phase.Idle,
    /** 지금까지 물은 질문 수. 처음 [Timing.HINT_QUESTIONS]번에만 답 낱말 안내를 붙인다. */
    val questionsAsked: Int = 0,
    val nextId: Long = 1L,
    /**
     * "들를게" 뒤 아직 그 이동이 끝나지 않아 "취소"를 받는 곳. 엔진은 한 질문의 답을 하나만 남기므로
     * ([AskHistory.record]) 취소하면 CANCEL, 이동이 끝나면 ACCEPT로 그때 남긴다.
     */
    val pendingAccept: Place? = null,
    /** "취소"를 받으려고 연 듣기가 끝나는 때. 0이면 열려 있지 않음. */
    val cancelListenUntil: Long = 0L,
    val parked: Boolean = false,
    val visible: Boolean = false,
    /** 찜마다 마지막 판단과 근거. 주차 중 "왜 그때 물었는지" 화면에 쓴다. */
    val decisions: Map<String, Decision> = emptyMap(),
)

data class FlowStep(val state: FlowState, val commands: List<FlowCommand>)

/**
 * 묻고 답하는 흐름. 순수 함수 [reduce] 하나로, 같은 상태와 일이면 늘 같은 결과를 낸다.
 * 엔진([MomentJudge])이 "지금 물어도 된다"고 할 때만 묻고, 묻는 동안 그 판단이 바뀌면(안전 조건이 깨짐, 창이 가려짐)
 * 말을 멈춘다. 답은 [AnswerParser]로 읽는다.
 */
class AskFlow(private val settings: Settings = Settings(), private val judge: MomentJudge = MomentJudge(settings)) {

    fun reduce(state: FlowState, event: FlowEvent): FlowStep = when (event) {
        is FlowEvent.Tick -> tick(state, event)
        is FlowEvent.SpeechDone -> speechDone(state, event)
        is FlowEvent.Heard -> answer(state, AnswerParser.parse(event.text), event.nowMs, event.today, byVoice = true)
        is FlowEvent.Pressed ->
            if (state.parked) answer(state, event.choice.kind, event.nowMs, event.today, byVoice = false) else FlowStep(state, emptyList())
        is FlowEvent.WaypointAdded -> waypointAdded(state, event)
        is FlowEvent.WaypointFailed -> waypointFailed(state, event)
        is FlowEvent.WaypointRemoved -> waypointRemoved(state, event)
        is FlowEvent.WaypointGone -> waypointGone(state, event)
        is FlowEvent.RadiusExit -> FlowStep(state.copy(history = state.history.recordRadiusExit(event.placeId)), emptyList())
        // 새 이동은 이번 이동에서 센 것만 비운다. 주차 중 "들를게"로 넣고 출발한 곳은 새 이동의 것이라 "취소"를 계속 받는다
        // (지난 이동의 것은 이동이 끝날 때 TripEnded로 정산된다)
        is FlowEvent.TripStarted -> FlowStep(state.copy(history = state.history.startNewTrip()), emptyList())
        is FlowEvent.TripEnded -> {
            val settled = settlePending(state, event.today)
            FlowStep(settled.copy(phase = Phase.Idle), listOf(FlowCommand.StopSpeaking, FlowCommand.StopListening, FlowCommand.HideCard))
        }
    }

    private fun tick(before: FlowState, e: FlowEvent.Tick): FlowStep {
        // 모든 후보의 판단을 남긴다(보류·반경 이탈 기록과 근거 화면). 묻는 중인 곳도 여기서 다시 판단된다
        var history = before.history
        val decisions = LinkedHashMap<String, Decision>()
        for (place in e.candidates) {
            val decision = judge.judge(e.ctx, place, history, e.today)
            decisions[place.id] = decision
            history = history.recordVerdict(place.id, decision.verdict)
        }
        val s = before.copy(history = history, decisions = before.decisions + decisions, parked = e.parked, visible = e.visible)
        val parkedChanged = before.parked != e.parked

        return when (val ph = s.phase) {
            // 가려지면 마이크가 닫히므로 듣는 시간도 비운다(다시 보이면 곧바로 다시 연다)
            is Phase.Idle -> if (e.visible) idle(s, e) else FlowStep(s.copy(cancelListenUntil = 0L), stopCancelListening(s))
            is Phase.Speaking -> when {
                // 묻는 동안 판단이 바뀌거나 창이 가려지면 말을 멈추고 보류로 돌아간다(다 말하기 전이라 기록하지 않음)
                !e.visible || !stillAsks(decisions, ph.card.place) -> stopAsking(s, record = null, today = e.today)
                e.nowMs - ph.startedAt >= Timing.SPEAK_TIMEOUT_MS -> startListening(s, ph.card, e.nowMs)
                parkedChanged -> withButtons(s, ph.card, e.parked) { s.copy(phase = ph.copy(card = it)) }
                else -> FlowStep(s, emptyList())
            }
            is Phase.Listening -> when {
                // 다 들은 질문이라 이번 이동에서 물은 것으로 세되 30일 계산에는 넣지 않는다
                !e.visible || !stillAsks(decisions, ph.card.place) -> stopAsking(s, Answer.DISMISSED, e.today)
                // 듣는 사이 음성 서비스가 떨어졌으면 들을 수 없던 것이라 답 없음으로 세지 않는다
                e.nowMs >= ph.until ->
                    stopAsking(s, if (ph.heardOther || !e.voiceReady) Answer.DISMISSED else Answer.NO_ANSWER, e.today)
                parkedChanged -> withButtons(s, ph.card, e.parked) { s.copy(phase = ph.copy(card = it)) }
                else -> FlowStep(s, emptyList())
            }
            is Phase.Silent -> when {
                !e.visible || !stillAsks(decisions, ph.card.place) -> stopAsking(s, Answer.DISMISSED, e.today)
                // 주차(P)라 버튼으로 답할 수 있었으면 답이 없던 것, 아니면 답할 방법이 없던 것
                e.nowMs >= ph.until -> stopAsking(s, if (e.parked) Answer.NO_ANSWER else Answer.DISMISSED, e.today)
                parkedChanged -> withButtons(s, ph.card, e.parked) { s.copy(phase = ph.copy(card = it)) }
                else -> FlowStep(s, emptyList())
            }
            is Phase.Adding -> if (e.nowMs >= ph.until) failAdding(s, ph.place, e.nowMs, e.today) else FlowStep(s, emptyList())
            is Phase.Added -> added(s, ph, e, parkedChanged)
            is Phase.Removing -> when {
                e.nowMs < ph.until -> FlowStep(s, emptyList())
                // 넣지 못했다고 알린 뒤 지켜보는 동안 나타나지 않았으면 말없이 끝낸다
                ph.waitingAdd && ph.quiet -> FlowStep(s.copy(phase = Phase.Idle), emptyList())
                // 넣었다는 알림도 실패도 오지 않았으면 넣었을 수도 있어 빼 본다(앱은 목록에 그곳이 있을 때만 뺀다)
                ph.waitingAdd -> FlowStep(
                    s.copy(phase = ph.copy(until = e.nowMs + Timing.REMOVE_TIMEOUT_MS, waitingAdd = false)),
                    listOf(FlowCommand.RemoveWaypoint(ph.place)),
                )
                else -> say(s, Lines.REMOVE_FAILED)
            }
        }
    }

    /** 아무것도 묻지 않는 중: 넣은 곳이 있으면 "취소"를 받으려고 듣기를 다시 열고, 없으면 물을 곳을 고른다. */
    private fun idle(s: FlowState, e: FlowEvent.Tick): FlowStep {
        if (s.pendingAccept != null) {
            // 통화·안내 음성 중에는 "취소"를 듣지 않는다(남의 말을 듣지 않도록). 끝나면 다시 연다
            if (e.ctx.otherAudioPlaying == true) {
                return FlowStep(s.copy(cancelListenUntil = 0L), stopCancelListening(s))
            }
            // 들를까가 말하는 중에는 그 소리를 듣지 않도록 기다렸다가 연다
            if (e.voiceBusy || e.nowMs < s.cancelListenUntil) return FlowStep(s, emptyList())
            val until = e.nowMs + Timing.CANCEL_LISTEN_MS
            return FlowStep(s.copy(cancelListenUntil = until), listOf(FlowCommand.Listen(until)))
        }
        // 앞 말("한 달 뒤에…" 등)이 끝나기 전에는 묻지 않는다
        if (e.voiceBusy) return FlowStep(s, emptyList())
        val place = judge.pickFirst(e.ctx, e.candidates, s.history, e.today) ?: return FlowStep(s, emptyList())
        val ask = s.decisions[place.id]?.verdict as? Verdict.Ask ?: return FlowStep(s, emptyList())
        val question = MomentJudge.question(place, ask.detourMin, judge.allowedDetourMin(e.ctx))
        // 통화·안내 음성을 확인할 수 없거나 말하기·듣기가 준비되지 않았으면 소리 없이 카드만(엔진은 소리를 정하지 않음, Verdict.Ask 주석)
        val silent = e.ctx.otherAudioPlaying == null || !e.voiceReady
        val minutes = MomentJudge.detourMinutesText(ask.detourMin, judge.allowedDetourMin(e.ctx))
        val card = Card.Ask(place, ask.detourMin, question, silent, retryHint = false, buttons = e.parked, minutesText = minutes)
        val asked = s.copy(questionsAsked = s.questionsAsked + 1, nextId = s.nextId + 1)
        if (silent) {
            return FlowStep(asked.copy(phase = Phase.Silent(card, e.nowMs + Timing.CARD_MS)), listOf(FlowCommand.ShowCard(card)))
        }
        val text = if (s.questionsAsked < Timing.HINT_QUESTIONS) "$question ${Lines.HINT}" else question
        val id = s.nextId
        return FlowStep(
            asked.copy(phase = Phase.Speaking(id, card, e.nowMs)),
            listOf(FlowCommand.ShowCard(card), FlowCommand.Speak(id, text)),
        )
    }

    private fun added(s: FlowState, ph: Phase.Added, e: FlowEvent.Tick, parkedChanged: Boolean): FlowStep {
        if (!e.visible || e.nowMs >= ph.cardUntil) {
            // 카드는 닫지만 "취소"는 그 이동이 끝날 때까지 받는다(idle에서 듣기를 다시 연다)
            return FlowStep(s.copy(phase = Phase.Idle), listOf(FlowCommand.HideCard))
        }
        val commands = mutableListOf<FlowCommand>()
        var next = ph
        if (ph.announceId == 0L && safeToSpeak(e, ph.place, s.history)) {
            commands += FlowCommand.Speak(s.nextId, Lines.ADDED)
            next = ph.copy(announceId = s.nextId)
        }
        if (parkedChanged) commands += FlowCommand.ShowCard(Card.Added(ph.place, e.parked, listening = s.cancelListenUntil > 0L))
        val nextId = if (next.announceId != ph.announceId) s.nextId + 1 else s.nextId
        return FlowStep(s.copy(phase = next, nextId = nextId), commands)
    }

    private fun speechDone(s: FlowState, e: FlowEvent.SpeechDone): FlowStep = when (val ph = s.phase) {
        is Phase.Speaking -> if (e.id == ph.id) startListening(s, ph.card, e.nowMs) else FlowStep(s, emptyList())
        is Phase.Added -> if (e.id == ph.announceId) {
            // "넣었어요" 뒤에는 "취소"를 듣는다. 카드가 닫힌 뒤에도 idle에서 이어 연다. 듣기 시작할 때 카드에 "취소"를 보인다
            val until = maxOf(ph.cardUntil, e.nowMs + Timing.LISTEN_MS)
            FlowStep(
                s.copy(cancelListenUntil = until),
                listOf(FlowCommand.Listen(until), FlowCommand.ShowCard(Card.Added(ph.place, s.parked, listening = true))),
            )
        } else {
            FlowStep(s, emptyList())
        }
        else -> FlowStep(s, emptyList())
    }

    private fun answer(s: FlowState, kind: AnswerKind, nowMs: Long, today: Long, byVoice: Boolean): FlowStep {
        return when (val ph = s.phase) {
            // 질문 도중의 말은 받지 않는다(마이크도 아직 닫혀 있음). 주차 중 버튼은 말을 끊고 받는다
            is Phase.Speaking -> if (byVoice) FlowStep(s, emptyList()) else decide(s, ph.card, kind, nowMs, today, heardOther = false)
            is Phase.Listening -> decide(s, ph.card, kind, nowMs, today, ph.heardOther)
            // 무음 카드는 마이크를 열지 않았으므로 말로 온 결과는 버린다
            is Phase.Silent -> if (byVoice) FlowStep(s, emptyList()) else decide(s, ph.card, kind, nowMs, today, heardOther = false)
            is Phase.Adding ->
                if (kind == AnswerKind.CANCEL) cancel(s, ph.place, nowMs, today, addConfirmed = false) else FlowStep(s, emptyList())
            is Phase.Added ->
                if (kind == AnswerKind.CANCEL) cancel(s, ph.place, nowMs, today, addConfirmed = true) else FlowStep(s, emptyList())
            is Phase.Removing -> FlowStep(s, emptyList())
            is Phase.Idle -> {
                val pending = s.pendingAccept
                if (kind == AnswerKind.CANCEL && pending != null) {
                    cancel(s, pending, nowMs, today, addConfirmed = true)
                } else {
                    FlowStep(s, emptyList())
                }
            }
        }
    }

    private fun decide(s: FlowState, card: Card.Ask, kind: AnswerKind, nowMs: Long, today: Long, heardOther: Boolean): FlowStep =
        when (kind) {
            AnswerKind.ACCEPT -> FlowStep(
                s.copy(phase = Phase.Adding(card.place, nowMs + Timing.ADD_TIMEOUT_MS), pendingAccept = card.place),
                listOf(
                    FlowCommand.StopSpeaking,
                    FlowCommand.StopListening,
                    FlowCommand.ShowCard(Card.Adding(card.place)),
                    FlowCommand.AddWaypoint(card.place),
                ),
            )
            AnswerKind.LATER -> FlowStep(
                s.copy(phase = Phase.Idle, history = s.history.record(card.place.id, Answer.LATER, today, settings), nextId = s.nextId + 1),
                listOf(
                    FlowCommand.StopSpeaking,
                    FlowCommand.StopListening,
                    FlowCommand.HideCard,
                    FlowCommand.Speak(s.nextId, Lines.later(settings.snoozeDays)),
                ),
            )
            // 묻는 중의 "취소"도 받지 않는 말이다. 되묻지 않고 한 번만 알린다
            AnswerKind.CANCEL, AnswerKind.OTHER -> {
                val ph = s.phase
                if (heardOther || ph !is Phase.Listening) {
                    FlowStep(s, emptyList())
                } else {
                    val hinted = card.copy(retryHint = true)
                    FlowStep(
                        s.copy(phase = ph.copy(card = hinted, heardOther = true)),
                        listOf(FlowCommand.Beep, FlowCommand.ShowCard(hinted)),
                    )
                }
            }
        }

    /**
     * "취소": 답은 곧바로 CANCEL로 남기고, 경유지를 뺀다. "뺐어요"는 내비가 뺐다고 알린 뒤에 말한다([waypointRemoved]).
     * 아직 넣는 중이면([addConfirmed]가 아니면) 넣었다는 알림을 기다렸다가 뺀다.
     */
    private fun cancel(s: FlowState, place: Place, nowMs: Long, today: Long, addConfirmed: Boolean): FlowStep = FlowStep(
        s.copy(
            phase = Phase.Removing(place, nowMs + Timing.REMOVE_TIMEOUT_MS, waitingAdd = !addConfirmed),
            pendingAccept = null,
            cancelListenUntil = 0L,
            history = s.history.record(place.id, Answer.CANCEL, today, settings),
        ),
        buildList {
            add(FlowCommand.StopSpeaking)
            add(FlowCommand.StopListening)
            add(FlowCommand.HideCard)
            if (addConfirmed) add(FlowCommand.RemoveWaypoint(place))
        },
    )

    private fun waypointAdded(s: FlowState, e: FlowEvent.WaypointAdded): FlowStep = when (val ph = s.phase) {
        is Phase.Adding -> if (ph.place.id == e.placeId) {
            FlowStep(
                s.copy(phase = Phase.Added(ph.place, e.nowMs + Timing.CARD_MS)),
                listOf(FlowCommand.ShowCard(Card.Added(ph.place, s.parked))),
            )
        } else {
            FlowStep(s, emptyList())
        }
        // 넣는 중에 "취소"한 곳: 이제 넣었으니 뺀다
        is Phase.Removing -> if (ph.waitingAdd && ph.place.id == e.placeId) {
            FlowStep(
                s.copy(phase = ph.copy(until = e.nowMs + Timing.REMOVE_TIMEOUT_MS, waitingAdd = false)),
                listOf(FlowCommand.RemoveWaypoint(ph.place)),
            )
        } else {
            FlowStep(s, emptyList())
        }
        else -> FlowStep(s, emptyList())
    }

    private fun waypointFailed(s: FlowState, e: FlowEvent.WaypointFailed): FlowStep = when (val ph = s.phase) {
        is Phase.Adding -> if (ph.place.id == e.placeId) failAdding(s, ph.place, e.nowMs, e.today) else FlowStep(s, emptyList())
        is Phase.Removing -> when {
            ph.place.id != e.placeId -> FlowStep(s, emptyList())
            // 늦게 들어갈까 지켜보던 중에 넣기가 실패로 끝남: 치울 것이 없다
            ph.waitingAdd && ph.quiet -> FlowStep(s.copy(phase = Phase.Idle), emptyList())
            // 넣는 중에 "취소"했는데 넣지 못했으면 지금 경로에 없으니 뺀 것과 같다. 그래도 늦게 들어가면 말없이 뺀다
            ph.waitingAdd -> FlowStep(
                s.copy(phase = ph.copy(until = e.nowMs + Timing.CLEANUP_WATCH_MS, quiet = true), nextId = s.nextId + 1),
                listOf(FlowCommand.Speak(s.nextId, Lines.REMOVED)),
            )
            // 빼다가 못 뺐으면 그대로 알린다
            else -> say(s, Lines.REMOVE_FAILED)
        }
        else -> FlowStep(s, emptyList())
    }

    private fun waypointRemoved(s: FlowState, e: FlowEvent.WaypointRemoved): FlowStep {
        val ph = s.phase
        if (ph !is Phase.Removing || ph.waitingAdd || ph.place.id != e.placeId) return FlowStep(s, emptyList())
        return if (ph.quiet) FlowStep(s.copy(phase = Phase.Idle), emptyList()) else say(s, Lines.REMOVED)
    }

    /** 넣어 둔 경유지가 경로에서 사라짐: 그 질문의 답을 "들를게"로 남기고 "취소" 듣기를 멈춘다. */
    private fun waypointGone(s: FlowState, e: FlowEvent.WaypointGone): FlowStep {
        if (s.pendingAccept?.id != e.placeId) return FlowStep(s, emptyList())
        val ph = s.phase
        if (ph !is Phase.Idle && ph !is Phase.Added) return FlowStep(s, emptyList())
        val settled = settlePending(s, e.today)
        return FlowStep(
            settled.copy(phase = Phase.Idle),
            buildList {
                add(FlowCommand.StopListening)
                if (ph is Phase.Added) add(FlowCommand.HideCard)
            },
        )
    }

    /** 하던 일을 끝내고 [text]를 말한다. */
    private fun say(s: FlowState, text: String): FlowStep =
        FlowStep(s.copy(phase = Phase.Idle, nextId = s.nextId + 1), listOf(FlowCommand.Speak(s.nextId, text)))

    /**
     * 넣지 못함. 운전자는 "들를게"라고 했으니 30일 계산에는 넣지 않는다.
     * 실패 알림이나 시간 초과 뒤에도 늦게 들어갈 수 있어 [Timing.CLEANUP_WATCH_MS] 동안 지켜보다가 나타나면 말없이 뺀다.
     */
    private fun failAdding(s: FlowState, place: Place, nowMs: Long, today: Long): FlowStep = FlowStep(
        s.copy(
            phase = Phase.Removing(place, nowMs + Timing.CLEANUP_WATCH_MS, waitingAdd = true, quiet = true),
            pendingAccept = null,
            history = s.history.record(place.id, Answer.DISMISSED, today, settings),
            nextId = s.nextId + 1,
        ),
        listOf(FlowCommand.HideCard, FlowCommand.Speak(s.nextId, Lines.ADD_FAILED)),
    )

    private fun startListening(s: FlowState, card: Card.Ask, nowMs: Long): FlowStep {
        val until = nowMs + Timing.LISTEN_MS
        return FlowStep(s.copy(phase = Phase.Listening(card, until)), listOf(FlowCommand.Listen(until)))
    }

    /** 묻기를 멈춘다. [record]가 있으면 그 질문의 답으로 남긴다. */
    private fun stopAsking(s: FlowState, record: Answer?, today: Long): FlowStep {
        val place = when (val ph = s.phase) {
            is Phase.Speaking -> ph.card.place
            is Phase.Listening -> ph.card.place
            is Phase.Silent -> ph.card.place
            else -> null
        }
        val history = if (record != null && place != null) s.history.record(place.id, record, today, settings) else s.history
        return FlowStep(
            s.copy(phase = Phase.Idle, history = history),
            listOf(FlowCommand.StopSpeaking, FlowCommand.StopListening, FlowCommand.HideCard),
        )
    }

    private inline fun withButtons(s: FlowState, card: Card.Ask, parked: Boolean, next: (Card.Ask) -> FlowState): FlowStep {
        val updated = card.copy(buttons = parked)
        return FlowStep(next(updated), listOf(FlowCommand.ShowCard(updated)))
    }

    /** "들를게"로 넣어 둔 곳을 ACCEPT로 남긴다(이동이 끝나 더는 취소를 받지 않음). */
    private fun settlePending(s: FlowState, today: Long): FlowState {
        val pending = s.pendingAccept ?: return s
        return s.copy(pendingAccept = null, cancelListenUntil = 0L, history = s.history.record(pending.id, Answer.ACCEPT, today, settings))
    }

    private fun stopCancelListening(s: FlowState): List<FlowCommand> = stopListeningIf(s.cancelListenUntil > 0L)

    private fun stopListeningIf(condition: Boolean): List<FlowCommand> = if (condition) listOf(FlowCommand.StopListening) else emptyList()

    private fun stillAsks(decisions: Map<String, Decision>, place: Place): Boolean = decisions[place.id]?.verdict is Verdict.Ask

    /** "넣었어요"를 지금 말해도 되는지: 그 찜의 안전 단계가 통과일 때(회전·방향지시등·안내 음성이 없을 때). */
    private fun safeToSpeak(e: FlowEvent.Tick, place: Place, history: AskHistory): Boolean {
        val safety = judge.judge(e.ctx, place, history, e.today).checks.firstOrNull { it.step == Step.SAFETY }
        return safety?.outcome == Outcome.PASS
    }
}
