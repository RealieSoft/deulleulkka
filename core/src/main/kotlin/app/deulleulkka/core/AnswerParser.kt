package app.deulleulkka.core

/** 인식기가 적은 말 한 덩어리를 들를까가 받는 답으로 읽은 것. */
enum class AnswerKind { ACCEPT, LATER, CANCEL, OTHER }

/**
 * 받는 말은 화면에 적힌 두 낱말("들를게", "다음에")과 넣은 뒤의 "취소"뿐이다(기획서 3번).
 * 동승자끼리의 "응", "그래"나 "이 노래 들을게" 같은 말이 수락으로 잡히지 않도록, 인식 결과 한 덩어리가
 * 그 낱말 하나뿐일 때만 받는다. 띄어쓰기와 문장부호는 보지 않는다.
 * 에뮬레이터에서 합성 음성의 "들를게"가 "들을게"로 적혀 "들을게"도 받는다. 다른 표기는 사람 목소리 인식 표를 본 뒤 더한다.
 */
object AnswerParser {
    val ACCEPT_WORDS: Set<String> = setOf("들를게", "들을게")
    val LATER_WORDS: Set<String> = setOf("다음에")
    val CANCEL_WORDS: Set<String> = setOf("취소")

    fun parse(text: String): AnswerKind {
        val word = text.filter { it.isLetterOrDigit() }
        return when (word) {
            in ACCEPT_WORDS -> AnswerKind.ACCEPT
            in LATER_WORDS -> AnswerKind.LATER
            in CANCEL_WORDS -> AnswerKind.CANCEL
            else -> AnswerKind.OTHER
        }
    }
}
