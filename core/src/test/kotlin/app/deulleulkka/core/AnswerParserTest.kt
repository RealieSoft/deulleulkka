package app.deulleulkka.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** 기획서 3번 "받는 말" 규칙과 에뮬레이터 인식 결과(README "확인한 것")를 옮긴 테스트. */
class AnswerParserTest {
    @Test
    fun `화면에 적힌 낱말 하나뿐인 덩어리만 받는다`() {
        assertEquals(AnswerKind.ACCEPT, AnswerParser.parse("들를게"))
        assertEquals(AnswerKind.LATER, AnswerParser.parse("다음에"))
        assertEquals(AnswerKind.CANCEL, AnswerParser.parse("취소"))
    }

    @Test
    fun `합성 음성의 들를게가 들을게로 적혀 들을게도 받는다`() {
        assertEquals(AnswerKind.ACCEPT, AnswerParser.parse("들을게"))
    }

    @Test
    fun `띄어쓰기와 문장부호는 보지 않는다`() {
        assertEquals(AnswerKind.LATER, AnswerParser.parse(" 다음 에. "))
        assertEquals(AnswerKind.ACCEPT, AnswerParser.parse("들를게!"))
    }

    @Test
    fun `앞뒤에 다른 말이 붙거나 낱말이 아니면 받지 않는다`() {
        // 에뮬레이터에서 "응, 들를게"는 "응"과 "길게"로 나뉘어 왔다. 한 덩어리로 와도 받지 않는다
        for (heard in listOf("응, 들를게", "응", "길게", "이 노래 들을게", "들를게요", "들려줘", "넣기 취소", "네", "")) {
            assertEquals(AnswerKind.OTHER, AnswerParser.parse(heard), heard)
        }
    }
}
