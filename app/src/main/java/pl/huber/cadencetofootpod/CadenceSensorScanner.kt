package pl.huber.cadencetofootpod

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper

class CadenceSensorScanner(
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {
    enum class DeviceType(val label: String) {
        CSC("CSC"),
        FTMS("FTMS / trainer"),
        CYCLING_POWER("Cycling Power / trainer")
    }

    data class FoundDevice(
        val device: BluetoothDevice,
        val rssi: Int,
        val type: DeviceType,
        val advertisedName: String?
    )

    interface Listener {
        fun onScanStarted()
        fun onDeviceFound(foundDevice: FoundDevice)
        fun onScanStopped()
        fun onScanError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val seen = mutableSetOf<String>()
    private var scanning = false

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val serviceUuids = record?.serviceUuids?.map { it.uuid }.orEmpty()
            val advertisedName = record?.deviceName ?: try {
                device.name
            } catch (_: SecurityException) {
                null
            }

            val type = when {
                BleUuids.CSC_SERVICE in serviceUuids -> DeviceType.CSC
                BleUuids.FTMS_SERVICE in serviceUuids -> DeviceType.FTMS
                BleUuids.CYCLING_POWER_SERVICE in serviceUuids -> DeviceType.CYCLING_POWER
                advertisedName?.contains("KICKR", ignoreCase = true) == true -> DeviceType.FTMS
                else -> return
            }

            if (seen.add(device.address)) {
                listener.onDeviceFound(
                    FoundDevice(
                        device = device,
                        rssi = result.rssi,
                        type = type,
                        advertisedName = advertisedName
                    )
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onScanError("Błąd skanowania BLE: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(durationMs: Long = 12_000L) {
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onScanError("Bluetooth LE scanner jest niedostępny.")
            return
        }

        seen.clear()
        scanning = true
        listener.onScanStarted()

        // Celowo skanujemy bez ScanFilter. Nie wszystkie trenażery umieszczają
        // FTMS/Cycling Power UUID w każdym pakiecie reklamowym. Wyniki filtrujemy
        // w onScanResult po usługach i nazwie KICKR.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)
        handler.postDelayed({ stop() }, durationMs)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        adapter.bluetoothLeScanner?.stopScan(callback)
        scanning = false
        handler.removeCallbacksAndMessages(null)
        listener.onScanStopped()
    }
}
