package pl.huber.cadencetofootpod

import kotlin.math.abs

/**
 * Regulator przełożenia oparty o sprzężenie zwrotne mocy i kadencji.
 *
 * Kierunek:
 * - SHIFT UP = cięższy bieg: zwykle podnosi wymaganą moc przy tej samej kadencji,
 * - SHIFT DOWN = lżejszy bieg: zwykle obniża wymaganą moc i ułatwia podniesienie kadencji.
 *
 * Moc i kadencja są traktowane jako dwa cele. Jeżeli oba błędy wskazują ten sam
 * kierunek, zmiana jest jednoznaczna. Jeżeli wskazują kierunki przeciwne, automat
 * czeka zamiast powodować oscylację biegów.
 */
class PowerTargetEngine(
    private val listener: Listener
) {
    interface Listener {
        fun onPowerTargetDecision(
            direction: Direction,
            powerWatts: Double,
            cadenceRpm: Double,
            reason: String
        )

        fun onPowerTargetState(message: String)
    }

    enum class Direction { UP, DOWN }

    data class Config(
        val targetPowerW: Double = 200.0,
        val powerHysteresisW: Double = 10.0,
        val targetCadenceRpm: Double = 85.0,
        val cadenceHysteresisRpm: Double = 5.0,
        val triggerDelayMs: Long = 2_000L,
        val cooldownMs: Long = 4_000L,
        val minActiveCadenceRpm: Double = 20.0,
        val powerSmoothingAlpha: Double = 0.25
    ) {
        val minPowerW: Double get() = targetPowerW - powerHysteresisW
        val maxPowerW: Double get() = targetPowerW + powerHysteresisW
        val minCadenceRpm: Double get() = targetCadenceRpm - cadenceHysteresisRpm
        val maxCadenceRpm: Double get() = targetCadenceRpm + cadenceHysteresisRpm
    }

    @Volatile
    private var enabled = false

    @Volatile
    private var config = Config()

    private var filteredPowerW: Double? = null
    private var pendingDirection: Direction? = null
    private var pendingSinceMs: Long? = null
    private var lastShiftAtMs = Long.MIN_VALUE
    private var lastState = "Target mocy wyłączony"

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        resetDecision()
        filteredPowerW = null
        lastState = if (value) {
            "Target mocy włączony — oczekiwanie na telemetrię"
        } else {
            "Target mocy wyłączony"
        }
        listener.onPowerTargetState(lastState)
    }

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun currentConfig(): Config = config

    @Synchronized
    fun updateConfig(newConfig: Config) {
        require(newConfig.targetPowerW >= 30.0) { "Moc docelowa musi wynosić co najmniej 30 W." }
        require(newConfig.powerHysteresisW >= 2.0) { "Histereza mocy musi wynosić co najmniej 2 W." }
        require(newConfig.targetPowerW - newConfig.powerHysteresisW > 0.0) { "Dolna granica mocy musi być większa od zera." }
        require(newConfig.targetCadenceRpm > 0.0) { "Kadencja docelowa musi być większa od zera." }
        require(newConfig.cadenceHysteresisRpm >= 0.5) { "Histereza kadencji musi wynosić co najmniej 0.5 RPM." }
        require(newConfig.targetCadenceRpm - newConfig.cadenceHysteresisRpm > 0.0) { "Dolna granica kadencji musi być większa od zera." }
        require(newConfig.powerSmoothingAlpha in 0.05..1.0) { "Nieprawidłowe wygładzanie mocy." }

        config = newConfig
        resetDecision()
        filteredPowerW = null
        publishState(
            "Cel ${fmt(newConfig.targetPowerW)} W ±${fmt(newConfig.powerHysteresisW)} W, " +
                "${fmt(newConfig.targetCadenceRpm)} RPM ±${fmt(newConfig.cadenceHysteresisRpm)}"
        )
    }

    @Synchronized
    fun onTelemetry(powerWatts: Double, cadenceRpm: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled) return

        val cfg = config
        if (cadenceRpm < cfg.minActiveCadenceRpm) {
            resetDecision()
            filteredPowerW = null
            publishState("Target mocy: brak aktywnej jazdy (${fmt(cadenceRpm)} RPM)")
            return
        }

        if (powerWatts < 0.0 || powerWatts > 5000.0) {
            publishState("Target mocy: brak poprawnego pomiaru mocy")
            return
        }

        val filtered = filteredPowerW?.let {
            it + cfg.powerSmoothingAlpha * (powerWatts - it)
        } ?: powerWatts
        filteredPowerW = filtered

        val powerDirection = when {
            filtered < cfg.minPowerW -> Direction.UP
            filtered > cfg.maxPowerW -> Direction.DOWN
            else -> null
        }

        val cadenceDirection = when {
            cadenceRpm < cfg.minCadenceRpm -> Direction.DOWN
            cadenceRpm > cfg.maxCadenceRpm -> Direction.UP
            else -> null
        }

        val decision = when {
            powerDirection == null && cadenceDirection == null -> null
            powerDirection != null && cadenceDirection == null -> powerDirection
            powerDirection == null && cadenceDirection != null -> cadenceDirection
            powerDirection == cadenceDirection -> powerDirection
            else -> {
                resetDecision()
                publishState(
                    "Konflikt: ${fmt(filtered)} W / ${fmt(cadenceRpm)} RPM — czekam na zmianę wysiłku"
                )
                return
            }
        }

        if (decision == null) {
            resetDecision()
            publishState(
                "W celu: ${fmt(filtered)} W i ${fmt(cadenceRpm)} RPM"
            )
            return
        }

        val cooldownRemaining = cooldownRemainingMs(nowMs, cfg)
        if (cooldownRemaining > 0) {
            resetPendingIfChanged(decision, nowMs)
            publishState(
                "${directionLabel(decision)} — cooldown ${fmt(cooldownRemaining / 1000.0)} s; " +
                    "${fmt(filtered)} W / ${fmt(cadenceRpm)} RPM"
            )
            return
        }

        if (pendingDirection != decision) {
            pendingDirection = decision
            pendingSinceMs = nowMs
        }

        val pendingFor = nowMs - (pendingSinceMs ?: nowMs)
        if (pendingFor < cfg.triggerDelayMs) {
            publishState(
                "${directionLabel(decision)} — stabilizacja ${fmt((cfg.triggerDelayMs - pendingFor) / 1000.0)} s; " +
                    "${fmt(filtered)} W / ${fmt(cadenceRpm)} RPM"
            )
            return
        }

        val reasons = mutableListOf<String>()
        if (powerDirection != null) {
            reasons += if (powerDirection == Direction.UP) {
                "moc poniżej ${fmt(cfg.minPowerW)} W"
            } else {
                "moc powyżej ${fmt(cfg.maxPowerW)} W"
            }
        }
        if (cadenceDirection != null) {
            reasons += if (cadenceDirection == Direction.UP) {
                "kadencja powyżej ${fmt(cfg.maxCadenceRpm)} RPM"
            } else {
                "kadencja poniżej ${fmt(cfg.minCadenceRpm)} RPM"
            }
        }

        performShift(
            direction = decision,
            powerWatts = filtered,
            cadenceRpm = cadenceRpm,
            nowMs = nowMs,
            reason = reasons.joinToString(" + ")
        )
    }

    @Synchronized
    fun onPowerUnavailable() {
        filteredPowerW = null
        resetDecision()
        if (enabled) publishState("Target mocy: brak pomiaru mocy z trenażera")
    }

    @Synchronized
    fun registerManualShift(nowMs: Long = System.currentTimeMillis()) {
        lastShiftAtMs = nowMs
        resetDecision()
        if (enabled) publishState("Ręczna zmiana biegu — uruchomiono cooldown")
    }

    @Synchronized
    fun reset() {
        resetDecision()
        filteredPowerW = null
        lastShiftAtMs = Long.MIN_VALUE
    }

    private fun performShift(
        direction: Direction,
        powerWatts: Double,
        cadenceRpm: Double,
        nowMs: Long,
        reason: String
    ) {
        lastShiftAtMs = nowMs
        resetDecision()
        publishState(
            "${directionLabel(direction)} przy ${fmt(powerWatts)} W / ${fmt(cadenceRpm)} RPM — $reason"
        )
        listener.onPowerTargetDecision(direction, powerWatts, cadenceRpm, reason)
    }

    private fun cooldownRemainingMs(nowMs: Long, cfg: Config): Long {
        if (lastShiftAtMs == Long.MIN_VALUE) return 0L
        return (cfg.cooldownMs - (nowMs - lastShiftAtMs)).coerceAtLeast(0L)
    }

    private fun resetPendingIfChanged(direction: Direction, nowMs: Long) {
        if (pendingDirection != direction) {
            pendingDirection = direction
            pendingSinceMs = nowMs
        }
    }

    private fun resetDecision() {
        pendingDirection = null
        pendingSinceMs = null
    }

    private fun directionLabel(direction: Direction): String =
        if (direction == Direction.UP) "SHIFT UP" else "SHIFT DOWN"

    private fun publishState(message: String) {
        if (message == lastState) return
        lastState = message
        listener.onPowerTargetState(message)
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
