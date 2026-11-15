package app.deulleulkka

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.deulleulkka.core.Card
import app.deulleulkka.core.Choice
import app.deulleulkka.core.Lines
import app.deulleulkka.engine.Decision
import app.deulleulkka.engine.Outcome
import app.deulleulkka.engine.Place
import app.deulleulkka.engine.Verdict
import kotlin.math.roundToInt

/**
 * 들를까의 하나뿐인 화면. 내비 옆 칸에 띄워 둔다.
 * - 주행 중(P 아님): 위쪽 한 줄("이번 이동에서 물을 수 있는 찜 N곳")과, 물을 때만 카드 한 장. 주행 중에는 누를 것이 없다.
 * - 주차(P): 가 볼 곳 목록, 새 즐겨찾기·최근 목적지 넣기/넣지 않기, 다녀온 곳, 최근 판단의 근거. 카드에는 버튼이 생긴다.
 * 모양은 시안(proposal/mockups)의 정보만 따르고, 색·크기 다듬기는 2차 결정 뒤에 한다.
 */
class MainActivity : Activity(), FlowRunner.Ui {

    private lateinit var runner: FlowRunner
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var card: LinearLayout
    private var lastView: FlowRunner.View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = text(SIZE_STATUS, Color.DKGRAY).apply { setPadding(pad(24), pad(16), pad(24), pad(16)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(24), 0, pad(24), pad(24)) }
        listScroll = ScrollView(this).apply { addView(list) }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SIGN_BLUE)
            setPadding(pad(44), pad(40), pad(44), pad(44))
            visibility = View.GONE
            isClickable = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(listScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(card, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            },
        )
        runner = FlowRunner(this, this)
        FlowRunner.current = runner
    }

    override fun onStart() {
        super.onStart()
        // 권한은 바로 묻지 않는다. Pleos 위치 권한은 시스템 허락 창에 "알 수 없는 작업"으로 떠서, 쓰임새를 먼저 보여 주고
        // 사용자가 "허용하기"를 누를 때 묻는다(render의 안내)
        runner.start(hasLocationPermission = checkSelfPermission(LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    private fun askPermissions() {
        if (locationBlocked()) {
            // "다시 묻지 않음"을 고른 뒤에는 허락 창이 뜨지 않으므로 앱 설정 화면으로 보낸다
            // 설정 화면은 따로 된 작업으로 연다. 들를까 작업 안에 열면 앱이 죽었을 때 설정 화면이 작업의 바닥에 남아,
            // 다시 열린 들를까 칸이 "창 크기를 넓게 조정하세요"로 가려졌다(2026-11-15 에뮬레이터)
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(LOCATION_ASKED, true).apply()
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    /**
     * 위치 권한을 다시 물을 수 없는지. 한 번 물은 뒤 거절됐고 시스템이 더는 허락 창을 띄우지 않으면
     * (shouldShowRequestPermissionRationale가 false) "다시 묻지 않음"을 고른 것이다. 처음 묻기 전에도 false라 물은 적이 있는지 함께 본다.
     */
    private fun locationBlocked(): Boolean =
        checkSelfPermission(LOCATION) != PackageManager.PERMISSION_GRANTED &&
            getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(LOCATION_ASKED, false) &&
            !shouldShowRequestPermissionRationale(LOCATION)

    override fun onStop() {
        runner.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (FlowRunner.current === runner) FlowRunner.current = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 위치·속도 권한을 새로 받았으면 SDK 연결을 다시 맺어 반영한다(속도 콜백은 시작할 때만 건다)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            runner.stop()
            runner.start(hasLocationPermission = checkSelfPermission(LOCATION) == PackageManager.PERMISSION_GRANTED)
        }
        lastView?.let(::draw)
    }

    // ---------- 카드 ----------

    override fun showCard(card: Card) {
        this.card.removeAllViews()
        when (card) {
            is Card.Ask -> {
                this.card.addView(spacer())
                this.card.addView(signText("★ ${card.place.name.trim()}", SIZE_NAME))
                // 말과 같은 숫자를 보인다(허용 시간 경계에서는 소수 한 자리)
                val minutes = card.minutesText
                this.card.addView(signText(if (minutes == null) "가는 길에 있어요" else "약 ${minutes}분 더 걸려요", SIZE_FIGURE))
                this.card.addView(spacer())
                if (card.retryHint) this.card.addView(signText(Lines.CARD_HINT, SIZE_HINT))
                if (card.buttons) {
                    this.card.addView(button("들를게") { runner.press(Choice.ACCEPT) })
                    this.card.addView(button("다음에") { runner.press(Choice.LATER) })
                } else if (!card.silent) {
                    this.card.addView(signText("“들를게”", SIZE_ANSWER))
                    this.card.addView(signText("“다음에”", SIZE_ANSWER))
                }
            }
            is Card.Adding -> {
                this.card.addView(spacer())
                this.card.addView(signText(card.place.name.trim(), SIZE_NAME))
                this.card.addView(signText("경유지에 넣는 중", SIZE_HINT))
                this.card.addView(spacer())
            }
            is Card.Added -> {
                this.card.addView(spacer())
                this.card.addView(signText(card.place.name.trim(), SIZE_NAME))
                this.card.addView(signText("경유지에", SIZE_HINT))
                this.card.addView(signText("넣었어요", SIZE_RESULT))
                this.card.addView(spacer())
                // 버튼은 주차 중 늘 쓸 수 있고, 말로 답하는 안내는 마이크가 열린 뒤에만 보인다
                if (card.buttons) {
                    this.card.addView(button("취소") { runner.press(Choice.CANCEL) })
                } else if (card.listening) {
                    this.card.addView(signText("“취소”", SIZE_ANSWER))
                }
            }
        }
        this.card.visibility = View.VISIBLE
    }

    override fun hideCard() {
        card.visibility = View.GONE
        card.removeAllViews()
    }

    // ---------- 주차 중 화면 ----------

    /** 1초마다 불린다. 바뀐 것이 없으면 다시 그리지 않는다(누르는 도중 버튼이 새로 만들어져 누름이 사라지지 않도록). */
    override fun render(view: FlowRunner.View) {
        if (view == lastView) return
        draw(view)
    }

    private fun draw(view: FlowRunner.View) {
        lastView = view
        status.text = buildString {
            when {
                checkSelfPermission(LOCATION) != PackageManager.PERMISSION_GRANTED -> append("위치 권한을 허용해야 가는 길 근처의 찜을 찾을 수 있어요")
                !view.naviReady -> append("내비게이션에 연결하는 중")
                !view.locationKnown -> append("현재 위치를 확인하는 중")
                else -> append("이번 이동에서 물을 수 있는 찜 ${view.onRoute}곳")
            }
            if (view.simulated) append("  · 시험 입력")
        }
        list.removeAllViews()
        if (checkSelfPermission(LOCATION) != PackageManager.PERMISSION_GRANTED) {
            section("처음 한 번, 허락이 필요해요")
            list.addView(note("위치: 가는 길 근처에 가 볼 곳이 있는지 찾는 데 씁니다. 위치와 찜은 이 차 안에만 두고 밖으로 보내지 않습니다."))
            list.addView(note("차량 속도: 묻기 좋은 순간(속도·회전까지 거리)을 고르는 데 씁니다."))
            if (locationBlocked()) {
                // 에뮬레이터 설정의 앱 > 들를까 > 권한에는 이 권한이 보이지 않았다(이름 없는 Pleos 권한, 2026-11-15). 실차 설정은 [미확인]
                list.addView(note("허락 창에서 \"다시 묻지 않음\"을 골라 창이 더는 뜨지 않아요. 설정 > 앱 > 들를까 > 권한에서 켜 주세요."))
                list.addView(note("그곳에 위치 권한이 보이지 않으면, 들를까를 지우고 다시 설치해야 다시 물을 수 있어요."))
                list.addView(button("설정에서 허용하기") { askPermissions() })
            } else {
                list.addView(note("허락 창에 \"알 수 없는 작업\"으로 보이는 항목이 위치 권한입니다."))
                list.addView(button("허용하기") { askPermissions() })
            }
            return
        }
        if (!view.parked) {
            list.addView(text(SIZE_BODY, Color.GRAY).apply { text = "주행 중에는 물을 때만 카드를 띄웁니다. 목록은 주차(P)일 때 보여요." })
            return
        }
        if (!splitGuideSeen()) splitGuide()
        view.pending?.let { p ->
            section("방금 넣은 경유지")
            row(p.name.ifBlank { "이름 없는 곳" }, "경유지에서 빼기" to { runner.press(Choice.CANCEL) })
        }
        section("가 볼 곳 ${view.places.size}곳")
        if (view.places.isEmpty()) list.addView(note("아래 즐겨찾기·최근 목적지에서 넣어 주세요"))
        view.places.forEach { p -> row(p.name, "빼기" to { runner.removePlace(p) }) }
        section("새 즐겨찾기·최근 목적지")
        if (view.newFavorites.isEmpty()) list.addView(note("새로 고를 곳이 없어요(집·회사·충전소는 빼고 보여요)"))
        view.newFavorites.forEach { p -> row(p.name.ifBlank { "이름 없는 곳" }, "넣기" to { runner.addPlace(p) }, "넣지 않기" to { runner.declineFavorite(p) }) }
        list.addView(button("즐겨찾기 다시 읽기", dark = false) { runner.refreshFavorites() })
        if (view.visited.isNotEmpty()) {
            section("다녀온 곳")
            // 머문 시간으로 잘못 판정했을 수 있어 다시 가 볼 곳으로 넣을 수 있게 한다
            view.visited.forEach { p -> row(p.name.ifBlank { "이름 없는 곳" }, "다시 넣기" to { runner.restoreVisited(p) }) }
        }
        if (view.decisions.isNotEmpty()) {
            section("최근 판단")
            view.decisions.forEach { (p, d) -> decision(p, d) }
        }
    }

    /**
     * 나눠 띄우기 안내(한 번). 칸이 하나뿐이면 내비게이션과 같은 칸을 번갈아 쓰게 되어, 들를까가 가려진 동안은 묻지 않고
     * 경유지를 넣으면 내비게이션이 앞으로 나와 "넣었어요"·"취소"를 받지 못한다(2026-11-15 에뮬레이터). 창 모드로는 나눴는지
     * 가릴 수 없어(혼자 띄워도 multi-window로 옴) 처음 한 번 알리고, 알겠다고 하면 다시 띄우지 않는다.
     */
    private fun splitGuide() {
        section("내비 옆에 띄워 두세요")
        list.addView(note("앱 영역 오른쪽 끝 손잡이(⋮)를 왼쪽으로 끌어 화면을 둘로 나누고, 한쪽에 내비게이션, 다른 쪽에 들를까를 띄워 두세요."))
        list.addView(note("들를까가 가려져 있으면 묻지 않아요. 혼자 띄우면 경유지를 넣을 때 내비게이션이 앞으로 나와 \"넣었어요\"와 \"취소\"를 듣지 못해요."))
        list.addView(button("알겠어요") {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(SPLIT_GUIDE_SEEN, true).apply()
            lastView?.let(::draw)
        })
    }

    private fun splitGuideSeen(): Boolean = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(SPLIT_GUIDE_SEEN, false)

    private fun decision(place: Place, d: Decision) {
        list.addView(text(SIZE_BODY, Color.BLACK).apply { text = "${place.name} — ${verdictText(d.verdict)}"; setTypeface(typeface, Typeface.BOLD) })
        d.checks.forEach { c ->
            val mark = when (c.outcome) {
                Outcome.PASS -> "통과"
                Outcome.HOLD -> "보류"
                Outcome.SKIP -> "묻지 않음"
                Outcome.NOT_CHECKED -> "보지 않음"
            }
            // 앞 단계에서 멈춰 보지 않은 단계는 본 값도 "보지 않음"이라 한 번만 쓴다
            val line = if (c.outcome == Outcome.NOT_CHECKED) mark else "${c.seen} → $mark"
            list.addView(note("${c.step.label} · ${c.question}: $line"))
        }
    }

    /** 판단 결과를 운전자가 읽는 말로. 근거 줄은 아래에 따로 보인다. */
    private fun verdictText(v: Verdict): String = when (v) {
        is Verdict.Ask -> "물음(약 ${v.detourMin.roundToInt()}분 더)"
        is Verdict.Hold -> "기다림: ${v.reason}"
        is Verdict.Skip -> "묻지 않음: ${v.reason}"
    }

    private fun section(title: String) {
        list.addView(text(SIZE_SECTION, Color.BLACK).apply { text = title; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad(24), 0, pad(8)) })
    }

    private fun row(label: String, vararg actions: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(text(SIZE_BODY, Color.BLACK).apply { text = label }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.forEach { (name, act) -> r.addView(button(name, dark = false, act)) }
        list.addView(r)
    }

    // ---------- 작은 도우미 ----------

    private fun pad(dp: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).roundToInt()

    private fun text(sizeDp: Float, color: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        setTextColor(color)
    }

    private fun note(s: String) = text(SIZE_NOTE, Color.DKGRAY).apply { text = s }

    private fun signText(s: String, sizeDp: Float) = text(sizeDp, Color.WHITE).apply {
        text = s
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) }

    private fun button(label: String, dark: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (dark) SIZE_ANSWER else SIZE_BODY)
        minHeight = pad(MIN_TOUCH_DP)
        minWidth = pad(MIN_TOUCH_DP)
        setOnClickListener { onClick() }
    }

    private companion object {
        const val LOCATION = "pleos.car.permission.FUSED_LOCATION"
        val PERMISSIONS = listOf(LOCATION, "android.car.permission.CAR_SPEED")
        const val REQUEST_PERMISSIONS = 1

        /** 화면 설정(나눠 띄우기 안내를 봤는지). 찜·기록(Store)과 따로 둔다. */
        const val PREFS = "deulleulkka-ui"
        const val SPLIT_GUIDE_SEEN = "splitGuideSeen"
        const val LOCATION_ASKED = "locationAsked"

        /** 시안 토큰(proposal/mockups/tokens.css): 표지판 파랑, 주행 글자 크기(1px = 1dp). */
        val SIGN_BLUE = Color.parseColor("#14509A")
        const val SIZE_NAME = 76f
        const val SIZE_FIGURE = 66f
        const val SIZE_RESULT = 104f
        const val SIZE_ANSWER = 50f
        const val SIZE_HINT = 44f
        const val SIZE_STATUS = 28f
        const val SIZE_SECTION = 30f
        const val SIZE_BODY = 26f
        const val SIZE_NOTE = 22f

        /** 터치 대상 76dp 이상(기획서 5번, Google 차량용 설계 원칙). */
        const val MIN_TOUCH_DP = 76
    }
}
