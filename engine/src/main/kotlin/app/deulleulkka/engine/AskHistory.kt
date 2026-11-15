package app.deulleulkka.engine

/** 제안에 대한 운전자의 답. */
enum class Answer {
    /** "들를게"라고 말했거나 "들를게" 버튼을 눌렀다. */
    ACCEPT,

    /** "다음에"라고 답했다. */
    LATER,

    /** 8초 동안 답이 없었다. */
    NO_ANSWER,

    /** 넣은 뒤 그 이동이 끝나기 전에 "취소"라고 했거나 (주차 중) "취소"를 눌렀다. 그 이동에서는 다시 묻지 않고, 다음 이동부터는 다시 묻는다. */
    CANCEL,
}

/**
 * 묻고 답한 기록. 기기 안에만 둔다.
 * 날짜는 기준일부터 센 날수(epoch day)로 다룬다.
 */
data class AskHistory(
    /** 이번 이동에서 물은 곳. 답이 없던 것도 들어간다. */
    val askedThisTrip: List<String> = emptyList(),
    /** 장소별로, 다시 물어도 되는 첫날. */
    val askAgainFrom: Map<String, Long> = emptyMap(),
    /**
     * 장소별로, 답 없이 닫힌 횟수. 기한 없이 쌓으므로 오래전의 한 번과 오늘의 한 번이 합쳐져 "다음에"로 본다.
     * 기획서 응답 표는 "같은 곳에서 두 번 쌓이면"만 정했고 쌓는 기한은 정하지 않았다.
     */
    val noAnswerCount: Map<String, Int> = emptyMap(),
    /** 지금 보류 중인 곳: 이번 이동에서 마지막 판단이 보류(Hold)였던 곳. */
    val onHold: Set<String> = emptySet(),
    /** 이번 이동에서 보류 중에 판단 반경을 벗어난 곳. 이 이동에서는 다시 묻지 않는다. */
    val leftWhileHeldThisTrip: Set<String> = emptySet(),
) {
    fun isSnoozed(placeId: String, today: Long): Boolean =
        today < (askAgainFrom[placeId] ?: Long.MIN_VALUE)

    /**
     * 판단할 때마다 앱이 그 결과를 남긴다. 보류(Hold)면 보류 중으로 두고, 물음·묻지 않음이면 보류가 끝난 것으로 둔다.
     * 그래서 보류 뒤 "이미 지나친 곳"으로 묻지 않은 찜은 그 뒤 반경을 벗어나도 "보류한 사이 이탈"이 아니다.
     */
    fun recordVerdict(placeId: String, verdict: Verdict): AskHistory =
        if (verdict is Verdict.Hold) copy(onHold = onHold + placeId) else copy(onHold = onHold - placeId)

    /** 찜의 판단 반경을 벗어났을 때(위치 SDK의 영역 이탈 알림) 앱이 부른다. 보류 중인 곳만 남긴다. */
    fun recordRadiusExit(placeId: String): AskHistory =
        if (placeId in onHold) copy(leftWhileHeldThisTrip = leftWhileHeldThisTrip + placeId) else this

    /**
     * 새 이동을 시작한다. 이동은 시동을 켠 뒤 처음 기어 P에서 벗어난 때부터 시동을 끌 때까지다(기획서 판단 표).
     * 시동을 켠 채 P에 섰다가 다시 출발하는 것은 같은 이동이므로 다시 부르지 않는다.
     * 이동마다 세는 것(물은 곳, 보류 중인 곳, 보류한 사이 반경을 벗어난 곳)만 비운다.
     */
    fun startNewTrip(): AskHistory =
        copy(askedThisTrip = emptyList(), onHold = emptySet(), leftWhileHeldThisTrip = emptySet())

    /**
     * 질문 하나에 대한 답을 남긴다. 질문 한 번에 한 번만 부른다: 이번 이동에서 물은 횟수가 부른 횟수만큼 늘기 때문이다.
     * "들를게" 뒤 그 이동이 끝나기 전에 취소하면 그 질문의 답은 [Answer.CANCEL] 하나로 남긴다([Answer.ACCEPT]와 둘 다 남기지 않는다).
     */
    fun record(placeId: String, answer: Answer, today: Long, settings: Settings = Settings()): AskHistory {
        val asked = askedThisTrip + placeId
        val snoozed = askAgainFrom + (placeId to today + settings.snoozeDays)
        return when (answer) {
            Answer.ACCEPT, Answer.CANCEL -> copy(askedThisTrip = asked, noAnswerCount = noAnswerCount - placeId)
            Answer.LATER -> copy(askedThisTrip = asked, askAgainFrom = snoozed, noAnswerCount = noAnswerCount - placeId)
            Answer.NO_ANSWER -> {
                val count = (noAnswerCount[placeId] ?: 0) + 1
                if (count >= NO_ANSWERS_TREATED_AS_LATER) {
                    copy(askedThisTrip = asked, askAgainFrom = snoozed, noAnswerCount = noAnswerCount - placeId)
                } else {
                    copy(askedThisTrip = asked, noAnswerCount = noAnswerCount + (placeId to count))
                }
            }
        }
    }

    companion object {
        /** 같은 곳에서 답 없이 닫힌 일이 이만큼 쌓이면 "다음에"로 본다. */
        const val NO_ANSWERS_TREATED_AS_LATER = 2
    }
}

/** 방문 졸업: 도착해서 이 시간 넘게 머물렀으면 다녀온 곳으로 바꾼다. */
object VisitRule {
    const val MIN_DWELL_MIN = 10

    /**
     * [dwellMinutes]는 머문 시간을 올림한 정수 분으로 넘긴다(10분 1초 → 11분). 그래야 "10분 넘게"가 초 단위까지 지켜진다:
     * 정확히 10분은 10이라 다녀온 곳이 아니고, 10분을 조금이라도 넘으면 11 이상이다. 내림해 넘기면 10분 59초가 빠진다.
     */
    fun isVisited(dwellMinutes: Int): Boolean = dwellMinutes > MIN_DWELL_MIN
}
