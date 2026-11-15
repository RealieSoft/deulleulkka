package app.deulleulkka.core

import app.deulleulkka.engine.AskHistory
import app.deulleulkka.engine.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 기기 안 저장(앱을 다시 켜도 찜·기록·취소 대기가 남음)과 다녀온 곳 규칙. */
class SavedTest {
    private val bakery = Place("b:1", "밀밭 베이커리", 37.6143, 127.2016)
    private val odd = Place("b:2", "이름에\n줄바꿈\u001F과 구분", 37.5, 127.0)

    @Test
    fun `저장했다 읽으면 같은 상태가 된다`() {
        val state = SavedState(
            places = listOf(bakery),
            visited = listOf(Place("v", "다녀온 곳", 37.0, 127.0)),
            declined = setOf("r:9", "b:7"),
            history = AskHistory(listOf("b:1", "x"), mapOf("y" to 130L), mapOf("z" to 1), setOf("b:1"), setOf("q")),
            questionsAsked = 4,
            pendingAccept = bakery,
            inTrip = true,
        )
        assertEquals(state, SavedStateCodec.decode(SavedStateCodec.encode(state)))
    }

    @Test
    fun `이름의 줄바꿈과 구분 문자는 빈칸으로 바꿔 저장이 깨지지 않는다`() {
        val back = SavedStateCodec.decode(SavedStateCodec.encode(SavedState(places = listOf(odd, bakery))))
        assertEquals(listOf(odd.copy(name = "이름에 줄바꿈 과 구분"), bakery), back.places)
    }

    @Test
    fun `저장이 망가졌으면 빈 상태로 시작하고 읽지 못하는 줄은 건너뛴다`() {
        assertEquals(SavedState(), SavedStateCodec.decode("엉뚱한 글"))
        assertEquals(SavedState(), SavedStateCodec.decode(""))
        val encoded = SavedStateCodec.encode(SavedState(places = listOf(bakery)))
        val broken = encoded.replace("37.6143", "NaN") + "\nP\u001F짧은 줄\nX\u001F모르는 종류"
        assertEquals(emptyList(), SavedStateCodec.decode(broken).places)
    }

    @Test
    fun `찜 가까이에 P로 10분 넘게 머물면 다녀온 곳이고 한 번만 알린다`() {
        val t = VisitTracker()
        val here = bakery.lat to bakery.lon
        assertNull(t.update(here.first, here.second, parked = true, nowMs = 0L, places = listOf(bakery)))
        assertNull(t.update(here.first, here.second, parked = true, nowMs = 10 * 60_000L, places = listOf(bakery)))
        assertEquals(bakery, t.update(here.first, here.second, parked = true, nowMs = 10 * 60_000L + 1, places = listOf(bakery)))
        assertNull(t.update(here.first, here.second, parked = true, nowMs = 11 * 60_000L, places = listOf(bakery)))
    }

    @Test
    fun `P가 아니거나 자리를 떠나면 머문 시간을 다시 센다`() {
        val t = VisitTracker()
        t.update(bakery.lat, bakery.lon, parked = true, nowMs = 0L, places = listOf(bakery))
        t.update(bakery.lat, bakery.lon, parked = false, nowMs = 5 * 60_000L, places = listOf(bakery))
        assertNull(t.update(bakery.lat, bakery.lon, parked = true, nowMs = 11 * 60_000L, places = listOf(bakery)))
        // 150m 밖이면 다녀온 곳이 아니다
        assertNull(t.update(bakery.lat + 0.002, bakery.lon, parked = true, nowMs = 30 * 60_000L, places = listOf(bakery)))
    }
}
