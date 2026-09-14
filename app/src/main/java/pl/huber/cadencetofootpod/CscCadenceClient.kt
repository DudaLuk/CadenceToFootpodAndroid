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
import kotlin.math.roundToInt

class CscCadenceClient(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean, message: String)
        fun onCadenceChanged(rpm: Double)
        fun onError(message: String)
    }

    private var gatt: BluetoothGatt? = null
    private var previousCrankRevolutions: Int? = null
    private var previousCrankEventTime: Int? = null
    private var smoothedRpm: Double? = null
    private var lastMeasurementAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val zeroTimeout = object : Runnable {
        override fun run() {
            if (lastMeasurementAt > 0L && SystemClock.elapsedRealtime() - lastMeasurementAt > 3_000L) {
                if ((smoothedRpm ?: 0.0) != 0.0) {
                    smoothedRpm = 0.0
                    listener.onCadenceChanged(0.0)
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
        previousCrankRevolutions = null
        previousCrankEventTime = null
        smoothedRpm = null
        lastMeasurementAt = 0L
        listener.onConnectionChanged(false, "Łączenie z ${safeName(device)}…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        previousCrankRevolutions = null
        previousCrankEventTime = null
        smoothedRpm = null
        lastMeasurementAt = 0L
    }

    fun close() {
        disconnect()
        handler.removeCallbacks(zeroTimeout)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onConnectionChanged(true, "Połączono z ${safeName(gatt.device)}. Odczyt usług…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onConnectionChanged(false, "Czujnik kadencji rozłączony.")
                previousCrankRevolutions = null
                previousCrankEventTime = null
                smoothedRpm = null
                listener.onCadenceChanged(0.0)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Błąd połączenia GATT: status=$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Nie udało się odczytać usług GATT: status=$status")
                return
            }

            val service = gatt.getService(BleUuids.CSC_SERVICE)
            val measurement = service?.getCharacteristic(BleUuids.CSC_MEASUREMENT)
            if (measurement == null) {
                listener.onError("Urządzenie nie udostępnia CSC Measurement (0x2A5B).")
                return
            }

            if (!gatt.setCharacteristicNotification(measurement, true)) {
                listener.onError("Nie udało się włączyć powiadomień CSC.")
                return
            }

            val cccd = measurement.getDescriptor(BleUuids.CCCD)
            if (cccd == null) {
                listener.onError("Brak deskryptora CCCD dla CSC Measurement.")
                return
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

            if (started) {
                listener.onConnectionChanged(true, "Czujnik gotowy. Oczekiwanie na obrót korby…")
            } else {
                listener.onError("Nie udało się zapisać CCCD dla CSC Measurement.")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleMeasurement(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleMeasurement(value)
        }
    }

    private fun handleMeasurement(value: ByteArray) {
        if (value.size < 1) return
        val flags = value[0].toInt() and 0xFF
        var offset = 1

        val wheelPresent = flags and 0x01 != 0
        val crankPresent = flags and 0x02 != 0

        if (wheelPresent) offset += 6 // uint32 cumulative wheel rev + uint16 wheel event time
        if (!crankPresent || value.size < offset + 4) return

        val crankRevolutions = uint16Le(value, offset)
        val crankEventTime = uint16Le(value, offset + 2)
        val now = SystemClock.elapsedRealtime()

        val prevRev = previousCrankRevolutions
        val prevTime = previousCrankEventTime

        if (prevRev != null && prevTime != null) {
            val stale = lastMeasurementAt > 0L && now - lastMeasurementAt > 5_000L
            if (!stale) {
                val deltaRev = (crankRevolutions - prevRev) and 0xFFFF
                val deltaTicks = (crankEventTime - prevTime) and 0xFFFF

                if (deltaRev > 0 && deltaTicks > 0) {
                    val rpm = deltaRev * 60.0 * 1024.0 / deltaTicks.toDouble()
                    if (rpm in 0.0..250.0) {
                        val filtered = smoothedRpm?.let { previous -> previous * 0.65 + rpm * 0.35 } ?: rpm
                        smoothedRpm = filtered
                        listener.onCadenceChanged(filtered)
                    }
                }
            }
        }

        previousCrankRevolutions = crankRevolutions
        previousCrankEventTime = crankEventTime
        lastMeasurementAt = now
    }

    private fun uint16Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            "czujnikiem BLE"
        }
}
