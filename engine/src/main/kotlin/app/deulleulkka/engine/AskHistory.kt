package app.deulleulkka.engine

/** 제안에 대한 운전자의 답. */
enum class Answer {
    /** "들를게"라고 말했거나 "들를게" 버튼을 눌렀다. */
    ACCEPT,

    /** "다음에"라고 답했다. */
    LATER,

    /** 8초 동안 답이 없었다. */
    NO_ANSWER,

    /** 넣은 뒤 8초 안에 "취소"라고 했거나 "넣기 취소"를 눌렀다. 그 이동에서는 다시 묻지 않고, 다음 이동부터는 다시 묻는다. */
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
    /** 장소별로, 답 없이 닫힌 횟수. */
    val noAnswerCount: Map<String, Int> = emptyMap(),
) {
    fun isSnoozed(placeId: String, today: Long): Boolean =
        today < (askAgainFrom[placeId] ?: Long.MIN_VALUE)

    /** 새 이동을 시작한다(주차에서 벗어난 때). 이동마다 세는 것만 비운다. */
    fun startNewTrip(): AskHistory = copy(askedThisTrip = emptyList())

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

    fun isVisited(dwellMinutes: Int): Boolean = dwellMinutes > MIN_DWELL_MIN
}
