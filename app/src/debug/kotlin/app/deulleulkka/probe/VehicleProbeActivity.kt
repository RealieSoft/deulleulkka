package app.deulleulkka.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView

/**
 * E7 차량 신호 확인(Vehicle SDK).
 *
 * 방향지시등(CAR_LIGHTS)·브레이크 페달(CAR_DRIVE)·기어(CAR_DRIVE)·충전율(CAR_ENERGY)·운전석/동승석 착좌(CAR_SEATS)를
 * 지원 여부(capability) → 현재 값(get) → 변화 구독(register) 순으로 확인한다.
 * 화면을 띄운 동안 PC에서 Virtual Console(에뮬레이터 8088 포트)로 값을 바꾸고, 바뀐 값이 콜백으로 오는지 본다.
 * 15초마다 현재 값을 다시 읽어 콜백과 맞는지도 남긴다.
 *
 * 결과는 화면과 logcat(태그 DeulleulkkaProbe)에 남긴다.
 */
class VehicleProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val t0 = SystemClock.elapsedRealtime()
    private lateinit var out: TextView
    private lateinit var vehicle: Vehicle

    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val passenger = SeatArea(SeatValue.ROW_1_RIGHT)

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = log("cb.turnSignal $value")
        override fun onFailed(e: Exception) = log("cb.turnSignal 실패 ${err(e)}")
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) = log("cb.brakePedal pressed=$pressed")
        override fun onFailed(e: Exception) = log("cb.brakePedal 실패 ${err(e)}")
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = log("cb.gear $gear")
        override fun onFailed(e: Exception) = log("cb.gear 실패 ${err(e)}")
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = log("cb.soc $soc")
        override fun onFailed(e: Exception) = log("cb.soc 실패 ${err(e)}")
    }
    private val driverSeatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupied: Boolean?) = log("cb.seat.driver occupied=$occupied")
        override fun onFailed(e: Exception) = log("cb.seat.driver 실패 ${err(e)}")
    }
    private val passengerSeatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupied: Boolean?) = log("cb.seat.passenger occupied=$occupied")
        override fun onFailed(e: Exception) = log("cb.seat.passenger 실패 ${err(e)}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 18f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(out) })

        vehicle = Vehicle(applicationContext)
        step(0, "Vehicle.initialize()") { vehicle.initialize() }
        step(2_000, "지원 여부(capability)") {
            vehicle.getTurnSignal().checkTurnSignalStateCapabilities({ log("cap.turnSignal $it") }, { log("cap.turnSignal 실패 ${err(it)}") })
            vehicle.getBrake().checkBrakePedalStatusCapabilities({ log("cap.brakePedal $it") }, { log("cap.brakePedal 실패 ${err(it)}") })
            vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities({ log("cap.soc $it") }, { log("cap.soc 실패 ${err(it)}") })
            vehicle.getSeat().checkSeatOccupancyCapabilities({ areas: List<SeatArea> -> log("cap.seatOccupancy ${areas.map { a -> a.values.toList() }}") }, { log("cap.seatOccupancy 실패 ${err(it)}") })
        }
        step(4_000, "현재 값(get)") { readAll() }
        step(6_000, "변화 구독(register)") {
            vehicle.getTurnSignal().registerTurnSignalState(turnListener)
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)
            vehicle.getGear().registerGearSelection(gearListener)
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener)
            vehicle.getSeat().registerSeatOccupancy(driver, driverSeatListener)
            vehicle.getSeat().registerSeatOccupancy(passenger, passengerSeatListener)
            log("register 호출 끝")
        }
        for (i in 1..40) step(6_000 + i * 15_000L, "현재 값 다시 읽기 #$i") { readAll() }
    }

    private fun readAll() {
        vehicle.getTurnSignal().getCurrentTurnSignalState({ log("get.turnSignal $it") }, { log("get.turnSignal 실패 ${err(it)}") })
        vehicle.getBrake().getCurrentBrakePedalStatus({ log("get.brakePedal pressed=$it") }, { log("get.brakePedal 실패 ${err(it)}") })
        vehicle.getGear().getCurrentGearSelectionState({ log("get.gear $it") }, { log("get.gear 실패 ${err(it)}") })
        vehicle.getEvBattery().getEvBatteryStateOfCharge({ log("get.soc $it") }, { log("get.soc 실패 ${err(it)}") })
        vehicle.getSeat().getCurrentSeatOccupancy(driver, { log("get.seat.driver occupied=$it") }, { log("get.seat.driver 실패 ${err(it)}") })
        vehicle.getSeat().getCurrentSeatOccupancy(passenger, { log("get.seat.passenger occupied=$it") }, { log("get.seat.passenger 실패 ${err(it)}") })
    }

    private fun err(e: Exception) = "${e::class.java.simpleName} ${e.message}"

    private fun step(delayMs: Long, name: String, action: () -> Unit) {
        handler.postDelayed({
            log("── $name")
            runCatching(action).onFailure { log("예외 ${it::class.java.simpleName} ${it.message}") }
        }, delayMs)
    }

    private fun log(line: String) {
        val msg = "+${SystemClock.elapsedRealtime() - t0}ms $line"
        Log.i(TAG, msg)
        runOnUiThread { out.append(msg + "\n") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            vehicle.getTurnSignal().unregisterTurnSignalState(turnListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            vehicle.getSeat().unregisterSeatOccupancy(driver, driverSeatListener)
            vehicle.getSeat().unregisterSeatOccupancy(passenger, passengerSeatListener)
        }
        runCatching { vehicle.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DeulleulkkaProbe"
    }
}
