package pl.huber.cadencetofootpod

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Odczyt telemetrii ze smart trenażera.
 * Preferuje FTMS / Indoor Bike Data (0x1826 / 0x2AD2), a jako fallback
 * korzysta z Cycling Power Measurement (0x1818 / 0x2A63).
 *
 * Z FTMS odczytywane są kadencja i moc chwilowa. Cycling Power Measurement
 * zawsze zawiera moc chwilową i opcjonalnie dane obrotu korby do wyliczenia kadencji.
 */
class TrainerCadenceClient(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean, message: String)
        fun onCadenceChanged(rpm: Double)
        fun onPowerChanged(watts: Int)
        fun onError(message: String)
    }

    private enum class Protocol { FTMS, CYCLING_POWER }

    private var gatt: BluetoothGatt? = null
    private var protocol: Protocol? = null
    private var lastCadenceAt = 0L
    private var lastPowerAt = 0L
    private var previousCrankRevolutions: Int? = null
    private var previousCrankEventTime: Int? = null
    private var smoothedRpm: Double? = null
    private var lastPowerWatts: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private val zeroTimeout = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (lastCadenceAt > 0L && now - lastCadenceAt > 3_000L) {
                if ((smoothedRpm ?: 0.0) != 0.0) {
                    smoothedRpm = 0.0
                    listener.onCadenceChanged(0.0)
                }
            }
            if (lastPowerAt > 0L && now - lastPowerAt > 3_000L) {
                if ((lastPowerWatts ?: 0) != 0) {
                    lastPowerWatts = 0
                    listener.onPowerChanged(0)
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    init {
        handler.post(zeroTimeout)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        resetMeasurementState()
        listener.onConnectionChanged(false, "Łączenie z trenażerem ${safeName(device)}…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        protocol = null
        resetMeasurementState()
    }

    fun close() {
        disconnect()
        handler.removeCallbacks(zeroTimeout)
    }

    private fun resetMeasurementState() {
        lastCadenceAt = 0L
        lastPowerAt = 0L
        previousCrankRevolutions = null
        previousCrankEventTime = null
        smoothedRpm = null
        lastPowerWatts = null
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onConnectionChanged(true, "Połączono z ${safeName(gatt.device)}. Odczyt usług trenażera…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onConnectionChanged(false, "Trenażer rozłączony.")
                resetMeasurementState()
                listener.onCadenceChanged(0.0)
                listener.onPowerChanged(-1)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Błąd połączenia z trenażerem GATT: status=$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Nie udało się odczytać usług trenażera: status=$status")
                return
            }

            val indoorBikeData = gatt.getService(BleUuids.FTMS_SERVICE)
                ?.getCharacteristic(BleUuids.INDOOR_BIKE_DATA)

            if (indoorBikeData != null) {
                protocol = Protocol.FTMS
                if (enableNotifications(gatt, indoorBikeData)) {
                    listener.onConnectionChanged(true, "KICKR/FTMS gotowy. Oczekiwanie na kadencję i moc…")
                }
                return
            }

            val cyclingPower = gatt.getService(BleUuids.CYCLING_POWER_SERVICE)
                ?.getCharacteristic(BleUuids.CYCLING_POWER_MEASUREMENT)

            if (cyclingPower != null) {
                protocol = Protocol.CYCLING_POWER
                if (enableNotifications(gatt, cyclingPower)) {
                    listener.onConnectionChanged(true, "Cycling Power gotowy. Oczekiwanie na moc i dane korby…")
                }
                return
            }

            listener.onError("Trenażer nie udostępnia FTMS Indoor Bike Data ani Cycling Power Measurement.")
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleCharacteristic(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristic(characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            listener.onError("Nie udało się włączyć powiadomień ${characteristic.uuid}.")
            return false
        }

        val cccd = characteristic.getDescriptor(BleUuids.CCCD)
        if (cccd == null) {
            listener.onError("Brak CCCD dla ${characteristic.uuid}.")
            return false
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }

        if (!started) listener.onError("Nie udało się zapisać CCCD dla ${characteristic.uuid}.")
        return started
    }

    private fun handleCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        when (characteristic.uuid) {
            BleUuids.INDOOR_BIKE_DATA -> handleIndoorBikeData(value)
            BleUuids.CYCLING_POWER_MEASUREMENT -> handleCyclingPower(value)
        }
    }

    /**
     * FTMS Indoor Bike Data (0x2AD2).
     * Pola są obecne zgodnie z flagami i muszą być czytane w kolejności z FTMS.
     * Instantaneous Cadence ma rozdzielczość 0.5 RPM, Instantaneous Power 1 W (sint16).
     */
    private fun handleIndoorBikeData(value: ByteArray) {
        if (value.size < 2) return
        val flags = uint16Le(value, 0)
        var offset = 2

        fun skip(bytes: Int): Boolean {
            if (value.size < offset + bytes) return false
            offset += bytes
            return true
        }

        // bit 0 = More Data. Gdy 0, Instantaneous Speed jest obecne.
        if (flags and 0x0001 == 0 && !skip(2)) return
        if (flags and 0x0002 != 0 && !skip(2)) return // Average Speed

        if (flags and 0x0004 != 0) { // Instantaneous Cadence
            if (value.size < offset + 2) return
            val rpm = uint16Le(value, offset) / 2.0
            offset += 2
            if (rpm in 0.0..250.0) emitCadence(rpm)
        }

        if (flags and 0x0008 != 0 && !skip(2)) return // Average Cadence
        if (flags and 0x0010 != 0 && !skip(3)) return // Total Distance (uint24)
        if (flags and 0x0020 != 0 && !skip(2)) return // Resistance Level

        if (flags and 0x0040 != 0) { // Instantaneous Power
            if (value.size < offset + 2) return
            val watts = int16Le(value, offset)
            offset += 2
            if (watts in -2000..5000) emitPower(watts.coerceAtLeast(0))
        }

        if (flags and 0x0080 != 0 && !skip(2)) return // Average Power
        if (flags and 0x0100 != 0 && !skip(5)) return // Total Energy + Energy/h + Energy/min
        if (flags and 0x0200 != 0 && !skip(1)) return // Heart Rate
        if (flags and 0x0400 != 0 && !skip(1)) return // Metabolic Equivalent
        if (flags and 0x0800 != 0 && !skip(2)) return // Elapsed Time
        if (flags and 0x1000 != 0) skip(2) // Remaining Time
    }

    /** Cycling Power Measurement fallback. Moc chwilowa jest zawsze pierwszym polem po flagach. */
    private fun handleCyclingPower(value: ByteArray) {
        if (value.size < 4) return
        val flags = uint16Le(value, 0)
        val instantaneousPower = int16Le(value, 2)
        if (instantaneousPower in -2000..5000) emitPower(instantaneousPower.coerceAtLeast(0))

        var offset = 4 // flags + instantaneous power

        if (flags and 0x0001 != 0) offset += 1 // Pedal Power Balance
        if (flags and 0x0004 != 0) offset += 2 // Accumulated Torque
        if (flags and 0x0010 != 0) offset += 6 // Wheel Revolution Data

        val crankPresent = flags and 0x0020 != 0
        if (!crankPresent || value.size < offset + 4) return

        val crankRevolutions = uint16Le(value, offset)
        val crankEventTime = uint16Le(value, offset + 2)
        val now = SystemClock.elapsedRealtime()

        val prevRev = previousCrankRevolutions
        val prevTime = previousCrankEventTime
        if (prevRev != null && prevTime != null) {
            val deltaRev = (crankRevolutions - prevRev) and 0xFFFF
            val deltaTicks = (crankEventTime - prevTime) and 0xFFFF
            if (deltaRev > 0 && deltaTicks > 0) {
                val rpm = deltaRev * 60.0 * 1024.0 / deltaTicks.toDouble()
                if (rpm in 0.0..250.0) {
                    val filtered = smoothedRpm?.let { it * 0.65 + rpm * 0.35 } ?: rpm
                    emitCadence(filtered)
                }
            }
        }

        previousCrankRevolutions = crankRevolutions
        previousCrankEventTime = crankEventTime
        lastCadenceAt = now
    }

    private fun emitCadence(rpm: Double) {
        smoothedRpm = rpm
        lastCadenceAt = SystemClock.elapsedRealtime()
        listener.onCadenceChanged(rpm)
    }

    private fun emitPower(watts: Int) {
        lastPowerWatts = watts
        lastPowerAt = SystemClock.elapsedRealtime()
        listener.onPowerChanged(watts)
    }

    private fun uint16Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun int16Le(data: ByteArray, offset: Int): Int {
        val raw = uint16Le(data, offset)
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            "trenażerem BLE"
        }
}
