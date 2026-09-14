package pl.huber.cadencetofootpod

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Automat utrzymujący kadencję wokół wartości docelowej.
 *
 * Przykład: target=85 RPM, hysteresis=5 RPM -> strefa bez zmian 80..90 RPM.
 * Dodatkowo wykrywa gwałtowną zmianę kadencji i może wykonać serię do 5 zmian biegów.
 */
class AutoShiftEngine(
    private val listener: Listener
) {
    interface Listener {
        fun onAutoShiftDecision(
            direction: Direction,
            cadenceRpm: Double,
            gearSteps: Int,
            reason: String
        )

        fun onAutoShiftState(message: String)
    }

    enum class Direction { UP, DOWN }

    data class Config(
        val targetRpm: Double = 85.0,
        val hysteresisRpm: Double = 5.0,
        val triggerDelayMs: Long = 1_500L,
        val cooldownMs: Long = 3_000L,
        val minActiveCadenceRpm: Double = 20.0,
        val rapidChangeEnabled: Boolean = true,
        val rapidChangeThresholdRpm: Double = 10.0,
        val rapidChangeWindowMs: Long = 1_500L,
        val rapidRpmPerGear: Double = 5.0,
        val maxRapidShifts: Int = 5
    ) {
        val minRpm: Double get() = targetRpm - hysteresisRpm
        val maxRpm: Double get() = targetRpm + hysteresisRpm
    }

    private data class CadenceSample(val atMs: Long, val rpm: Double)

    @Volatile
    private var enabled = false

    @Volatile
    private var config = Config()

    private var belowSinceMs: Long? = null
    private var aboveSinceMs: Long? = null
    private var lastShiftAtMs = Long.MIN_VALUE
    private var lastState = "AutoShift wyłączony"
    private val rapidSamples = ArrayDeque<CadenceSample>()

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        resetZones()
        rapidSamples.clear()
        lastState = if (value) {
            "AutoShift włączony — oczekiwanie na kadencję"
        } else {
            "AutoShift wyłączony"
        }
        listener.onAutoShiftState(lastState)
    }

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun currentConfig(): Config = config

    @Synchronized
    fun updateConfig(newConfig: Config) {
        require(newConfig.targetRpm > 0.0) { "Kadencja docelowa musi być większa od zera." }
        require(newConfig.hysteresisRpm >= 0.5) { "Histereza musi wynosić co najmniej 0.5 RPM." }
        require(newConfig.targetRpm - newConfig.hysteresisRpm > 0.0) { "Dolna granica kadencji musi być większa od zera." }
        require(newConfig.rapidChangeThresholdRpm > 0.0) { "Próg gwałtownej zmiany musi być większy od zera." }
        require(newConfig.rapidRpmPerGear > 0.0) { "RPM na bieg musi być większe od zera." }
        require(newConfig.maxRapidShifts in 1..5) { "Szybka korekta może mieć od 1 do 5 biegów." }

        config = newConfig
        resetZones()
        rapidSamples.clear()
        publishState(
            "Cel ${fmt(newConfig.targetRpm)} RPM ±${fmt(newConfig.hysteresisRpm)} " +
                "(${fmt(newConfig.minRpm)}–${fmt(newConfig.maxRpm)} RPM)"
        )
    }

    @Synchronized
    fun onCadence(rpm: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled) return

        val cfg = config
        if (rpm < cfg.minActiveCadenceRpm) {
            resetZones()
            rapidSamples.clear()
            publishState("AutoShift: brak aktywnej jazdy (${fmt(rpm)} RPM)")
            return
        }

        addRapidSample(rpm, nowMs, cfg)

        val cooldownRemaining = cooldownRemainingMs(nowMs, cfg)
        if (cooldownRemaining <= 0 && cfg.rapidChangeEnabled) {
            val rapidDecision = detectRapidChange(rpm, nowMs, cfg)
            if (rapidDecision != null) {
                val (direction, count, delta) = rapidDecision
                val sign = if (delta >= 0.0) "+" else ""
                performShift(
                    direction = direction,
                    rpm = rpm,
                    nowMs = nowMs,
                    gearSteps = count,
                    reason = "gwałtowna zmiana kadencji $sign${fmt(delta)} RPM"
                )
                return
            }
        }

        when {
            rpm < cfg.minRpm -> {
                aboveSinceMs = null
                if (belowSinceMs == null) belowSinceMs = nowMs
                val zoneMs = nowMs - (belowSinceMs ?: nowMs)

                if (cooldownRemaining > 0) {
                    publishState(
                        "Za niska kadencja ${fmt(rpm)} RPM — cooldown ${fmt(cooldownRemaining / 1000.0)} s"
                    )
                    return
                }

                if (zoneMs >= cfg.triggerDelayMs) {
                    performShift(
                        Direction.DOWN,
                        rpm,
                        nowMs,
                        1,
                        "kadencja poniżej ${fmt(cfg.minRpm)} RPM"
                    )
                } else {
                    publishState(
                        "Za niska kadencja ${fmt(rpm)} RPM — oczekiwanie ${fmt((cfg.triggerDelayMs - zoneMs).coerceAtLeast(0L) / 1000.0)} s"
                    )
                }
            }

            rpm > cfg.maxRpm -> {
                belowSinceMs = null
                if (aboveSinceMs == null) aboveSinceMs = nowMs
                val zoneMs = nowMs - (aboveSinceMs ?: nowMs)

                if (cooldownRemaining > 0) {
                    publishState(
                        "Za wysoka kadencja ${fmt(rpm)} RPM — cooldown ${fmt(cooldownRemaining / 1000.0)} s"
                    )
                    return
                }

                if (zoneMs >= cfg.triggerDelayMs) {
                    performShift(
                        Direction.UP,
                        rpm,
                        nowMs,
                        1,
                        "kadencja powyżej ${fmt(cfg.maxRpm)} RPM"
                    )
                } else {
                    publishState(
                        "Za wysoka kadencja ${fmt(rpm)} RPM — oczekiwanie ${fmt((cfg.triggerDelayMs - zoneMs).coerceAtLeast(0L) / 1000.0)} s"
                    )
                }
            }

            else -> {
                resetZones()
                publishState(
                    "Kadencja w celu: ${fmt(rpm)} RPM (${fmt(cfg.minRpm)}–${fmt(cfg.maxRpm)})"
                )
            }
        }
    }

    @Synchronized
    fun registerManualShift(nowMs: Long = System.currentTimeMillis()) {
        lastShiftAtMs = nowMs
        resetZones()
        rapidSamples.clear()
        if (enabled) publishState("Ręczna zmiana biegu — uruchomiono cooldown")
    }

    @Synchronized
    fun reset() {
        resetZones()
        rapidSamples.clear()
        lastShiftAtMs = Long.MIN_VALUE
    }

    private fun addRapidSample(rpm: Double, nowMs: Long, cfg: Config) {
        rapidSamples.addLast(CadenceSample(nowMs, rpm))
        val oldestAllowed = nowMs - cfg.rapidChangeWindowMs
        while (rapidSamples.isNotEmpty() && rapidSamples.first().atMs < oldestAllowed) {
            rapidSamples.removeFirst()
        }
    }

    private fun detectRapidChange(
        rpm: Double,
        nowMs: Long,
        cfg: Config
    ): Triple<Direction, Int, Double>? {
        if (rapidSamples.size < 2) return null

        val reference = rapidSamples.first()
        val spanMs = nowMs - reference.atMs
        // Nie reagujemy na pojedynczy skok jednej próbki BLE.
        if (spanMs < 400L) return null

        val delta = rpm - reference.rpm
        if (abs(delta) < cfg.rapidChangeThresholdRpm) return null

        val count = ceil(abs(delta) / cfg.rapidRpmPerGear)
            .toInt()
            .coerceIn(1, cfg.maxRapidShifts)

        val direction = if (delta > 0.0) Direction.UP else Direction.DOWN
        return Triple(direction, count, delta)
    }

    private fun performShift(
        direction: Direction,
        rpm: Double,
        nowMs: Long,
        gearSteps: Int,
        reason: String
    ) {
        lastShiftAtMs = nowMs
        resetZones()
        rapidSamples.clear()
        rapidSamples.addLast(CadenceSample(nowMs, rpm))

        val label = if (direction == Direction.UP) "SHIFT UP" else "SHIFT DOWN"
        publishState("$label ×$gearSteps przy ${fmt(rpm)} RPM — $reason")
        listener.onAutoShiftDecision(direction, rpm, gearSteps, reason)
    }

    private fun cooldownRemainingMs(nowMs: Long, cfg: Config): Long {
        if (lastShiftAtMs == Long.MIN_VALUE) return 0L
        return (cfg.cooldownMs - (nowMs - lastShiftAtMs)).coerceAtLeast(0L)
    }

    private fun resetZones() {
        belowSinceMs = null
        aboveSinceMs = null
    }

    private fun publishState(message: String) {
        if (message == lastState) return
        lastState = message
        listener.onAutoShiftState(message)
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
