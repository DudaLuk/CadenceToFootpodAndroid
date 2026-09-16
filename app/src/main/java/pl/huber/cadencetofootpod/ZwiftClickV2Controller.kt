package pl.huber.cadencetofootpod

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

/**
 * Bezpośrednia obsługa prawego Zwift Click V2 po BLE.
 *
 * Click V2 nie jest urządzeniem HID. Używa własnego serwisu GATT oraz komunikatów
 * kontrolera Ride. Ta implementacja celowo obsługuje prawy kontroler (manufacturer
 * side id 0x0A), ponieważ może działać bez okresowego odblokowywania przez aplikację Zwift.
 */
@SuppressLint("MissingPermission")
class ZwiftClickV2Controller(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val listener: Listener
) {

    enum class Button(val label: String, val mask: Long) {
        A("A", 0x00010),
        B("B", 0x00020),
        Y("Y", 0x00040),
        Z("Z", 0x00080),
        PLUS("+", 0x01000)
    }

    interface Listener {
        fun onZwiftClickV2ScanStarted()
        fun onZwiftClickV2DeviceFound(device: BluetoothDevice, displayName: String, rssi: Int)
        fun onZwiftClickV2ScanStopped()
        fun onZwiftClickV2ConnectionState(message: String, connected: Boolean)
        fun onZwiftClickV2Button(button: Button)
        fun onZwiftClickV2Error(message: String)
    }

    private data class NotifyTarget(
        val characteristic: BluetoothGattCharacteristic,
        val descriptor: BluetoothGattDescriptor,
        val value: ByteArray
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingNotifyTargets = ArrayDeque<NotifyTarget>()
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var connectingDeviceAddress: String? = null
    private var syncRx: BluetoothGattCharacteristic? = null
    private var pressedButtons: Set<Button> = emptySet()
    private var handshakeSent = false

    private val stopScanRunnable = Runnable {
        if (scanning) stopScan()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mainHandler.removeCallbacks(stopScanRunnable)
            listener.onZwiftClickV2Error("Skanowanie Click V2 nie powiodło się (kod $errorCode).")
            listener.onZwiftClickV2ScanStopped()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onZwiftClickV2ConnectionState("Click V2: błąd GATT $status", false)
                closeGatt(gatt)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onZwiftClickV2ConnectionState("Click V2: połączony, wykrywanie usług…", false)
                    if (!gatt.discoverServices()) {
                        listener.onZwiftClickV2Error("Click V2: nie udało się rozpocząć wykrywania usług GATT.")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onZwiftClickV2ConnectionState("Click V2: rozłączony", false)
                    closeGatt(gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onZwiftClickV2Error("Click V2: błąd wykrywania usług GATT ($status).")
                return
            }

            val service = findRideService(gatt)
            if (service == null) {
                listener.onZwiftClickV2Error("Click V2: nie znaleziono serwisu Zwift Ride/Click.")
                return
            }

            syncRx = service.getCharacteristic(SYNC_RX_UUID)
            val syncTx = service.getCharacteristic(SYNC_TX_UUID)
            val async = service.getCharacteristic(ASYNC_UUID)

            if (syncRx == null) {
                listener.onZwiftClickV2Error("Click V2: brak charakterystyki RX protokołu Ride.")
                return
            }

            pendingNotifyTargets.clear()
            addNotifyTarget(gatt, syncTx)
            addNotifyTarget(gatt, async)

            if (pendingNotifyTargets.isEmpty()) {
                sendHandshake(gatt)
            } else {
                enableNextNotification(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onZwiftClickV2Error("Click V2: nie udało się włączyć powiadomień GATT ($status).")
                return
            }
            if (pendingNotifyTargets.isNotEmpty()) {
                enableNextNotification(gatt)
            } else {
                sendHandshake(gatt)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let(::handleRidePacket)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleRidePacket(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == SYNC_RX_UUID && handshakeSent) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onZwiftClickV2ConnectionState("Click V2: gotowy", true)
                } else {
                    listener.onZwiftClickV2Error("Click V2: zapis komendy startowej nie powiódł się ($status).")
                }
            }
        }
    }

    fun startScan() {
        if (scanning) return
        if (!bluetoothAdapter.isEnabled) {
            listener.onZwiftClickV2Error("Włącz Bluetooth przed skanowaniem Click V2.")
            return
        }

        disconnect()
        scanning = true
        connectingDeviceAddress = null
        listener.onZwiftClickV2ScanStarted()
        bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
            ?: run {
                scanning = false
                listener.onZwiftClickV2Error("Skaner BLE jest niedostępny.")
                return
            }
        mainHandler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        mainHandler.removeCallbacks(stopScanRunnable)
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        listener.onZwiftClickV2ScanStopped()
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect()
        connectingDeviceAddress = device.address
        handshakeSent = false
        pressedButtons = emptySet()
        listener.onZwiftClickV2ConnectionState("Click V2: łączenie z ${safeName(device)}…", false)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val current = gatt
        gatt = null
        syncRx = null
        pendingNotifyTargets.clear()
        pressedButtons = emptySet()
        handshakeSent = false
        connectingDeviceAddress = null
        current?.disconnect()
        current?.close()
    }

    fun close() {
        stopScan()
        disconnect()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun handleScanResult(result: ScanResult) {
        if (!isRightClickV2(result)) return
        val device = result.device
        if (connectingDeviceAddress == device.address) return
        connectingDeviceAddress = device.address
        val name = result.scanRecord?.deviceName ?: safeName(device)
        listener.onZwiftClickV2DeviceFound(device, name, result.rssi)
        connect(device)
    }

    private fun isRightClickV2(result: ScanResult): Boolean {
        val manufacturer = result.scanRecord?.getManufacturerSpecificData(ZWIFT_MANUFACTURER_ID)
        if (manufacturer != null && manufacturer.isNotEmpty()) {
            val side = manufacturer[0].toInt() and 0xFF
            if (side == CLICK_V2_RIGHT_SIDE) return true
            if (side == CLICK_V2_LEFT_SIDE) return false
        }

        // Fallback dla firmware, które nie wystawia side-id w pierwszym bajcie danych producenta.
        // W takim przypadku akceptujemy tylko reklamę z serwisem Zwift FC82 i nazwą wskazującą Click.
        val name = result.scanRecord?.deviceName.orEmpty()
        val hasZwiftService = result.scanRecord?.serviceUuids?.any { parcel ->
            parcel.uuid == RIDE_SERVICE_UUID
        } == true
        return hasZwiftService && name.contains("Click", ignoreCase = true)
    }

    private fun findRideService(gatt: BluetoothGatt): BluetoothGattService? =
        gatt.getService(RIDE_SERVICE_UUID) ?: gatt.getService(LEGACY_CUSTOM_SERVICE_UUID)

    private fun addNotifyTarget(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        if (!gatt.setCharacteristicNotification(characteristic, true)) return
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        val supportsIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (supportsIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        pendingNotifyTargets.add(NotifyTarget(characteristic, descriptor, value))
    }

    private fun enableNextNotification(gatt: BluetoothGatt) {
        val target = pendingNotifyTargets.pollFirst() ?: run {
            sendHandshake(gatt)
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(target.descriptor, target.value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                target.descriptor.value = target.value
                gatt.writeDescriptor(target.descriptor)
            }
        }
        if (!started) {
            listener.onZwiftClickV2Error("Click V2: nie można skonfigurować powiadomień GATT.")
        }
    }

    private fun sendHandshake(gatt: BluetoothGatt) {
        if (handshakeSent) return
        val rx = syncRx ?: return
        handshakeSent = true
        val command = RIDE_ON + RESPONSE_START_CLICK_V2
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                rx,
                command,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                rx.value = command
                gatt.writeCharacteristic(rx)
            }
        }
        if (!started) {
            handshakeSent = false
            listener.onZwiftClickV2Error("Click V2: nie udało się wysłać komendy startowej.")
        } else {
            // WRITE_NO_RESPONSE nie gwarantuje callbacku onCharacteristicWrite na każdym telefonie.
            listener.onZwiftClickV2ConnectionState("Click V2: gotowy", true)
        }
    }

    private fun handleRidePacket(data: ByteArray) {
        if (data.isEmpty()) return

        var offset = 0
        while (offset < data.size) {
            val opcode = data[offset].toInt() and 0xFF
            if (opcode == CONTROLLER_NOTIFICATION_OPCODE) {
                val buttonMap = parseButtonMap(data, offset + 1) ?: return
                emitButtonChanges(buttonMap)
                return
            }
            // Typowy pakiet ma opcode na początku. Przeszukanie kilku początkowych bajtów
            // pomaga w przypadku transportowego prefiksu w niektórych wersjach firmware.
            offset++
            if (offset > MAX_OPCODE_SEARCH_OFFSET) return
        }
    }

    private fun parseButtonMap(data: ByteArray, start: Int): Long? {
        var index = start
        while (index < data.size) {
            val tag = data[index++].toInt() and 0xFF
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                0 -> {
                    val decoded = readVarInt(data, index) ?: return null
                    index = decoded.second
                    if (fieldNumber == 1) return decoded.first
                }

                1 -> index += 8
                2 -> {
                    val length = readVarInt(data, index) ?: return null
                    index = length.second + length.first.toInt()
                }

                5 -> index += 4
                else -> return null
            }
            if (index > data.size) return null
        }
        return null
    }

    private fun readVarInt(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = start
        while (index < data.size && shift < 64) {
            val b = data[index++].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    private fun emitButtonChanges(buttonMap: Long) {
        val nowPressed = Button.entries.filterTo(mutableSetOf()) { button ->
            // Protokół Ride używa logiki aktywnej stanem niskim: wyzerowany bit = naciśnięty.
            buttonMap and button.mask == 0L
        }
        val newlyPressed = nowPressed - pressedButtons
        pressedButtons = nowPressed
        newlyPressed.forEach(listener::onZwiftClickV2Button)
    }

    private fun closeGatt(callbackGatt: BluetoothGatt) {
        if (gatt === callbackGatt) gatt = null
        syncRx = null
        pendingNotifyTargets.clear()
        pressedButtons = emptySet()
        handshakeSent = false
        connectingDeviceAddress = null
        callbackGatt.close()
    }

    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: "Zwift Click V2"
        } catch (_: SecurityException) {
            "Zwift Click V2"
        }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val MAX_OPCODE_SEARCH_OFFSET = 3
        private const val ZWIFT_MANUFACTURER_ID = 2378
        private const val CLICK_V2_RIGHT_SIDE = 0x0A
        private const val CLICK_V2_LEFT_SIDE = 0x0B
        private const val CONTROLLER_NOTIFICATION_OPCODE = 0x23

        private val RIDE_SERVICE_UUID = UUID.fromString("0000fc82-0000-1000-8000-00805f9b34fb")
        private val LEGACY_CUSTOM_SERVICE_UUID = UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1")
        private val ASYNC_UUID = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1")
        private val SYNC_RX_UUID = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1")
        private val SYNC_TX_UUID = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val RIDE_ON = "RideOn".toByteArray(Charsets.US_ASCII)
        private val RESPONSE_START_CLICK_V2 = byteArrayOf(0x02, 0x03)
    }
}
