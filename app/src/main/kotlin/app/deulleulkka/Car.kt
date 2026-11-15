package app.deulleulkka

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler

/**
 * 차량 신호: Vehicle SDK(방향지시등·브레이크·기어·충전율), Android 표준 차량 속성(속도), 오디오(통화·안내 음성).
 * 읽지 못한 값은 null로 둔다. 착좌는 에뮬레이터에서 SDK 값이 콘솔과 반대로 와 뜻을 확인하기 전까지 쓰지 않는다(null).
 */
class CarSignals(private val context: Context, private val main: Handler, private val sink: Sink) {

    interface Sink {
        fun onTurnSignal(on: Boolean?)
        fun onBraking(pressed: Boolean?)
        fun onParked(parked: Boolean?)
        fun onEnergy(percent: Int?)
        fun onSpeed(kph: Double?)
        fun onOtherAudio(playing: Boolean)

        /** 다른 음성 비서(들를까 말고도 USAGE_ASSISTANT로 말하는 것)가 말하는 중인지. 그동안은 새로 묻지 않는다. */
        fun onAssistantAudio(playing: Boolean)
    }

    private val vehicle = Vehicle(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var started = false

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = post { sink.onTurnSignal(value?.let { it != TurnSignalValue.OFF }) }
        override fun onFailed(e: Exception) = post { sink.onTurnSignal(null) }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) = post { sink.onBraking(pressed) }
        override fun onFailed(e: Exception) = post { sink.onBraking(null) }
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = post { sink.onParked(parkedOf(gear)) }
        override fun onFailed(e: Exception) = post { sink.onParked(null) }
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = post { sink.onEnergy(soc) }
        override fun onFailed(e: Exception) = post { sink.onEnergy(null) }
    }
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float)?.toDouble()
            post { sink.onSpeed(mps?.takeIf { it.isFinite() }?.let { it * KPH_PER_MPS }) }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) = post { sink.onSpeed(null) }
    }
    /** API 31부터 있는 오디오 모드 알림. 그 아래 판에서 클래스를 읽지 않도록 확인한 뒤에만 만든다. */
    private var modeListener: Any? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = reportAudio()
    }

    fun start() {
        if (started) return
        started = true
        // initialize()는 끝났다는 알림이 없고 서비스에 붙는 데 시간이 걸린다. 확인용 앱처럼 조금 뒤에 구독하고,
        // 그 뒤로도 15초마다 지금 값을 다시 읽어 놓친 콜백을 메운다
        runCatching { vehicle.initialize() }.onFailure { post { sink.onTurnSignal(null); sink.onParked(null) } }
        main.postDelayed(subscribe, SUBSCRIBE_DELAY_MS)
        main.postDelayed(reread, SUBSCRIBE_DELAY_MS + REREAD_MS / 2)
        // 속도: 권한(CAR_SPEED)이 없거나 속성이 없으면 null로 두고, 앱이 잇단 위치로 어림한다
        runCatching {
            val c = Car.createCar(context)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            properties = pm
            pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { post { sink.onSpeed(null) } }
        audio.registerAudioPlaybackCallback(playbackCallback, main)
        // 통화 시작은 재생 목록보다 오디오 모드가 먼저 바뀐다(API 31부터 알림을 받을 수 있음)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { reportAudio() }
            modeListener = listener
            runCatching { audio.addOnModeChangedListener(main::post, listener) }
        }
        reportAudio()
    }

    /** 가려졌다가 다시 보일 때: 쌓여 온 콜백 대신 지금 값을 새로 읽는다(가려진 앱은 약 10초 뒤 멈춤, README). */
    fun rereadVehicle() {
        // 하나가 예외를 내도 나머지(특히 기어)는 읽도록 따로 감싼다
        runCatching {
            vehicle.getTurnSignal().getCurrentTurnSignalState({ v -> post { sink.onTurnSignal(v?.let { it != TurnSignalValue.OFF }) } }, { post { sink.onTurnSignal(null) } })
        }.onFailure { post { sink.onTurnSignal(null) } }
        runCatching {
            vehicle.getBrake().getCurrentBrakePedalStatus({ p -> post { sink.onBraking(p) } }, { post { sink.onBraking(null) } })
        }.onFailure { post { sink.onBraking(null) } }
        runCatching {
            vehicle.getGear().getCurrentGearSelectionState({ g -> post { sink.onParked(parkedOf(g)) } }, { post { sink.onParked(null) } })
        }.onFailure { post { sink.onParked(null) } }
        runCatching {
            vehicle.getEvBattery().getEvBatteryStateOfCharge({ s -> post { sink.onEnergy(s) } }, { post { sink.onEnergy(null) } })
        }.onFailure { post { sink.onEnergy(null) } }
        reportAudio()
    }

    private val subscribe = Runnable {
        if (!started) return@Runnable
        runCatching { vehicle.getTurnSignal().registerTurnSignalState(turnListener) }.onFailure { post { sink.onTurnSignal(null) } }
        runCatching { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }.onFailure { post { sink.onBraking(null) } }
        runCatching { vehicle.getGear().registerGearSelection(gearListener) }.onFailure { post { sink.onParked(null) } }
        runCatching { vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener) }.onFailure { post { sink.onEnergy(null) } }
        rereadVehicle()
    }

    private val reread = object : Runnable {
        override fun run() {
            if (!started) return
            rereadVehicle()
            main.postDelayed(this, REREAD_MS)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(subscribe)
        main.removeCallbacks(reread)
        runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
        runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
        runCatching { vehicle.release() }
        runCatching { properties?.unregisterCallback(speedCallback) }
        runCatching { car?.disconnect() }
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { runCatching { audio.removeOnModeChangedListener(it) } }
            modeListener = null
        }
    }

    /**
     * 통화 중(벨·통화·통신 모드)이거나 내비 안내 음성이 나오면 남의 소리가 나오는 중이다.
     * 재생한 앱은 가려져 오지만 쓰임새(usage)는 보인다. 들를까의 말은 USAGE_ASSISTANT로 나가므로(2026-11-15 dumpsys audio로 확인)
     * 들를까가 말하는 중에도 안내 음성을 따로 알아본다(전에는 말하는 동안 안내 음성을 보지 않아 겹쳐 말할 수 있었음).
     */
    fun reportAudio() {
        val mode = audio.mode
        val inCall = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        val playing = audio.activePlaybackConfigurations
        val guidance = playing.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
        val assistant = playing.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT }
        post {
            sink.onOtherAudio(inCall || guidance)
            sink.onAssistantAudio(assistant)
        }
    }

    private fun parkedOf(gear: VehicleGear?): Boolean? = when (gear) {
        null, VehicleGear.GEAR_UNKNOWN -> null
        VehicleGear.GEAR_PARK -> true
        else -> false
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private companion object {
        const val KPH_PER_MPS = 3.6
        const val SUBSCRIBE_DELAY_MS = 2_000L
        const val REREAD_MS = 15_000L
    }
}
