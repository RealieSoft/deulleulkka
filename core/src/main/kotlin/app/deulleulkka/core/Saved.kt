package app.deulleulkka.core

import app.deulleulkka.engine.AskHistory
import app.deulleulkka.engine.DetourEstimator
import app.deulleulkka.engine.Place
import app.deulleulkka.engine.VisitRule
import kotlin.math.ceil

/** 기기 안에만 남기는 것. 밖으로 보내지 않는다(기획서 6번 개인정보). */
data class SavedState(
    /** 가 볼 곳(찜). */
    val places: List<Place> = emptyList(),
    /** 다녀온 곳. 다시 묻지 않는다. */
    val visited: List<Place> = emptyList(),
    /** 주차 중 "넣지 않기"를 고른 즐겨찾기·최근 목적지의 id. 다시 묻지 않는다. */
    val declined: Set<String> = emptySet(),
    val history: AskHistory = AskHistory(),
    val questionsAsked: Int = 0,
    /** "들를게" 뒤 아직 그 이동이 끝나지 않은 곳(앱이 꺼졌다 켜져도 취소·기록을 잇는다). */
    val pendingAccept: Place? = null,
    val inTrip: Boolean = false,
)

/**
 * [SavedState]를 줄 단위 글로 바꾼다. 줄마다 첫 칸이 종류이고 칸은 [SEP]로 나눈다.
 * 읽지 못하는 줄은 건너뛰고, 머리 줄이 맞지 않으면 빈 상태를 돌려준다(망가진 저장 때문에 앱이 서지 않게).
 */
object SavedStateCodec {
    private const val SEP = '\u001F'
    private const val HEADER = "deulleulkka-state${SEP}1"

    fun encode(s: SavedState): String = buildString {
        appendLine(HEADER)
        s.places.forEach { appendLine(place("P", it)) }
        s.visited.forEach { appendLine(place("V", it)) }
        s.declined.sorted().forEach { appendLine(row("D", it)) }
        s.history.askedThisTrip.forEach { appendLine(row("A", it)) }
        s.history.askAgainFrom.toSortedMap().forEach { (id, day) -> appendLine(row("S", id, day.toString())) }
        s.history.noAnswerCount.toSortedMap().forEach { (id, n) -> appendLine(row("N", id, n.toString())) }
        s.history.onHold.sorted().forEach { appendLine(row("H", it)) }
        s.history.leftWhileHeldThisTrip.sorted().forEach { appendLine(row("L", it)) }
        appendLine(row("Q", s.questionsAsked.toString()))
        s.pendingAccept?.let { appendLine(place("C", it)) }
        appendLine(row("T", if (s.inTrip) "1" else "0"))
    }

    fun decode(text: String): SavedState {
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return SavedState()
        val places = mutableListOf<Place>()
        val visited = mutableListOf<Place>()
        val declined = mutableSetOf<String>()
        val asked = mutableListOf<String>()
        val snooze = mutableMapOf<String, Long>()
        val noAnswer = mutableMapOf<String, Int>()
        val held = mutableSetOf<String>()
        val left = mutableSetOf<String>()
        var questions = 0
        var pending: Place? = null
        var inTrip = false
        for (line in lines.drop(1)) {
            val f = line.split(SEP)
            when (f[0]) {
                "P" -> parsePlace(f)?.let(places::add)
                "V" -> parsePlace(f)?.let(visited::add)
                "C" -> pending = parsePlace(f)
                "D" -> f.getOrNull(1)?.let(declined::add)
                "A" -> f.getOrNull(1)?.let(asked::add)
                "S" -> f.getOrNull(2)?.toLongOrNull()?.let { snooze[f[1]] = it }
                "N" -> f.getOrNull(2)?.toIntOrNull()?.let { noAnswer[f[1]] = it }
                "H" -> f.getOrNull(1)?.let(held::add)
                "L" -> f.getOrNull(1)?.let(left::add)
                "Q" -> questions = f.getOrNull(1)?.toIntOrNull() ?: 0
                "T" -> inTrip = f.getOrNull(1) == "1"
            }
        }
        return SavedState(
            places = places,
            visited = visited,
            declined = declined,
            history = AskHistory(asked, snooze, noAnswer, held, left),
            questionsAsked = questions,
            pendingAccept = pending,
            inTrip = inTrip,
        )
    }

    /** 이름 안의 줄바꿈·구분 문자는 빈칸으로 바꾼다. */
    private fun clean(s: String): String = s.map { if (it == SEP || it == '\n' || it == '\r') ' ' else it }.joinToString("")

    private fun row(kind: String, vararg fields: String): String = (listOf(kind) + fields.map(::clean)).joinToString(SEP.toString())

    private fun place(kind: String, p: Place): String = row(kind, p.id, p.name, p.lat.toString(), p.lon.toString())

    private fun parsePlace(f: List<String>): Place? {
        if (f.size < 5) return null
        val lat = f[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val lon = f[4].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return Place(f[1], f[2], lat, lon)
    }
}

/**
 * 다녀온 곳 판단: 찜에서 [NEAR_METERS] 안에 기어 P로 [VisitRule.MIN_DWELL_MIN]분 넘게 머물면 다녀온 곳이다(기획서 판단 표).
 * 경유지로 넣지 않고 직접 찾아간 경우도 같이 본다.
 */
class VisitTracker {
    private var since: Pair<String, Long>? = null

    /** 다녀온 곳이 되었으면 그 찜을 돌려준다(한 번만). */
    fun update(lat: Double, lon: Double, parked: Boolean, nowMs: Long, places: List<Place>): Place? {
        val here = if (parked) places.firstOrNull { DetourEstimator.distanceKm(lat, lon, it.lat, it.lon) * METERS_PER_KM <= NEAR_METERS } else null
        if (here == null) {
            since = null
            return null
        }
        val start = since?.takeIf { it.first == here.id }?.second ?: nowMs.also { since = here.id to it }
        val dwellMinutes = ceil((nowMs - start) / MS_PER_MINUTE).toInt()
        if (!VisitRule.isVisited(dwellMinutes)) return null
        since = null
        return here
    }

    companion object {
        const val NEAR_METERS = 150.0
        private const val METERS_PER_KM = 1000.0
        private const val MS_PER_MINUTE = 60_000.0
    }
}
