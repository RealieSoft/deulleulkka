package app.deulleulkka.engine

/**
 * 우회 어림값의 도로 보정값([DetourEstimator.ROAD_FACTOR])을 이 차량의 내비게이션 값에 맞춰 간다.
 *
 * 경유지를 넣으면 내비게이션이 경로를 다시 계산해 알려 준다(에뮬레이터에서 약 6초 뒤 `onWaypointChanged`).
 * 그때의 실제 추가 시간과 물을 때의 어림값을 비교해, 그 어림에 썼어야 할 보정값을 거꾸로 구해 쌓는다.
 * 이 값은 도로가 돌아가는 정도뿐 아니라 우회 구간의 속도 차이도 함께 담는다.
 * 직선거리로 어림한 경우에만 기록한다. 찜까지의 도로 시간으로 어림했다면([DetourEstimator.usesTrip]) 이 값을 쓰지 않았으므로 기록하지 않는다.
 * 기록은 기기 안에만 두고, 서버나 언어 모델을 쓰지 않는다.
 */
data class RoadFactorCalibrator(
    /** 최근 경유지 추가에서 거꾸로 구한 보정값. 오래된 것부터. */
    val samples: List<Double> = emptyList(),
) {
    /** 지금 쓸 보정값. 기록이 적으면 출발값, 쌓이면 최근 기록의 가운데값(튀는 값 하나에 흔들리지 않게). */
    val factor: Double
        get() = if (samples.size < MIN_SAMPLES) DetourEstimator.ROAD_FACTOR else median(samples)

    /**
     * 경유지를 넣은 뒤 내비게이션이 다시 계산한 값을 반영한다.
     * [estimatedMin]은 물을 때 [factorUsed]로 어림한 추가 시간, [actualMin]은 넣기 전후 남은 시간의 차이다.
     */
    fun record(estimatedMin: Double, actualMin: Double, factorUsed: Double): RoadFactorCalibrator {
        // 어림값이 아주 작으면 비율이 크게 흔들리므로 쓰지 않는다
        if (estimatedMin < MIN_ESTIMATED_MIN) return this
        val implied = (factorUsed * actualMin / estimatedMin).coerceIn(MIN_FACTOR, MAX_FACTOR)
        return copy(samples = (samples + implied).takeLast(MAX_SAMPLES))
    }

    companion object {
        /** 이만큼 쌓이기 전에는 출발값을 쓴다. */
        const val MIN_SAMPLES = 3

        /** 최근 이만큼만 남긴다. 도로 사정이 바뀌어도 따라가도록. */
        const val MAX_SAMPLES = 10

        /** 어림값이 이 시간(분)보다 짧으면 기록하지 않는다. */
        const val MIN_ESTIMATED_MIN = 2.0

        /** 도로는 직선보다 짧을 수 없으므로 하한은 1이다. */
        const val MIN_FACTOR = 1.0

        /** 한 번의 이상한 값이 판단을 지나치게 바꾸지 않도록 둔 상한. */
        const val MAX_FACTOR = 2.5

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
