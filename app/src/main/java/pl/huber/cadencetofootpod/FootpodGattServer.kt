package pl.huber.cadencetofootpod

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlin.math.roundToInt

class FootpodGattServer(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {
    interface Listener {
        fun onState(message: String)
        fun onClientCountChanged(count: Int)
        fun onError(message: String)
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var gattServer: BluetoothGattServer? = null
    private var measurementCharacteristic: BluetoothGattCharacteristic? = null
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    private val subscribedAddresses = mutableSetOf<String>()

    private var originalAdapterName: String? = null
    private var advertising = false
    private var started = false

    @Volatile
    private var latestCadenceRpm: Double = 0.0

    @Volatile
    private var metersPerRevolution: Double = 2.0

    private val transmitLoop = object : Runnable {
        override fun run() {
            if (started) {
                notifyMeasurement()
                handler.postDelayed(this, 500L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(metersPerRevolution: Double) {
        if (started) {
            setMetersPerRevolution(metersPerRevolution)
            return
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onError("Ten telefon nie udostępnia BLE advertising/peripheral mode.")
            return
        }

        this.metersPerRevolution = metersPerRevolution.coerceIn(0.1, 10.0)
        originalAdapterName = try { adapter.name } catch (_: SecurityException) { null }
        try {
            adapter.name = "Cadence Footpod"
        } catch (_: Exception) {
            // Nazwa jest wygodna, ale nie jest wymagana do działania profilu RSC.
        }

        val server = bluetoothManager.openGattServer(context, serverCallback)
        if (server == null) {
            listener.onError("Nie udało się uruchomić BluetoothGattServer.")
            restoreAdapterName()
            return
        }

        gattServer = server
        val service = createRscService()
        started = true
        listener.onState("Uruchamianie wirtualnego Footpoda…")

        if (!server.addService(service)) {
            listener.onError("Nie udało się dodać usługi RSC 0x1814.")
            stop()
            return
        }

        handler.removeCallbacks(transmitLoop)
        handler.post(transmitLoop)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        started = false
        handler.removeCallbacks(transmitLoop)

        if (advertising) {
            try {
                adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {
            }
        }
        advertising = false

        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        measurementCharacteristic = null
        connectedDevices.clear()
        subscribedAddresses.clear()
        listener.onClientCountChanged(0)
        restoreAdapterName()
        listener.onState("Wirtualny Footpod zatrzymany.")
    }

    fun updateCadence(rpm: Double) {
        latestCadenceRpm = rpm.coerceIn(0.0, 255.0)
    }

    fun setMetersPerRevolution(value: Double) {
        metersPerRevolution = value.coerceIn(0.1, 10.0)
    }

    fun isStarted(): Boolean = started

    private fun createRscService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleUuids.RSC_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val measurement = BluetoothGattCharacteristic(
            BleUuids.RSC_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        val cccd = BluetoothGattDescriptor(
            BleUuids.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        measurement.addDescriptor(cccd)
        measurementCharacteristic = measurement
        service.addCharacteristic(measurement)

        val feature = BluetoothGattCharacteristic(
            BleUuids.RSC_FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // 0x0000 = bez dodatkowych funkcji opcjonalnych; speed + cadence nadal są raportowane w Measurement.
        @Suppress("DEPRECATION")
        run { feature.value = byteArrayOf(0x00, 0x00) }
        service.addCharacteristic(feature)

        val sensorLocation = BluetoothGattCharacteristic(
            BleUuids.SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // 0x02 = In Shoe. Pole jest opcjonalne, ale zwiększa kompatybilność klientów oczekujących Footpoda.
        @Suppress("DEPRECATION")
        run { sensorLocation.value = byteArrayOf(0x02) }
        service.addCharacteristic(sensorLocation)

        return service
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == BleUuids.RSC_SERVICE) {
                startAdvertising()
            } else if (service.uuid == BleUuids.RSC_SERVICE) {
                listener.onError("Błąd dodawania usługi RSC: status=$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices[device.address] = device
                listener.onState("Klient Footpoda połączony: ${safeName(device)}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device.address)
                subscribedAddresses.remove(device.address)
                listener.onState("Klient Footpoda rozłączony: ${safeName(device)}")
            }
            listener.onClientCountChanged(connectedDevices.size)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid != BleUuids.CCCD) return
            val enabled = device.address in subscribedAddresses
            val value = if (enabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BleUuids.CCCD) {
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) subscribedAddresses.add(device.address) else subscribedAddresses.remove(device.address)
                listener.onState(
                    if (enabled) "Klient włączył dane RSC." else "Klient wyłączył dane RSC."
                )
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                BleUuids.RSC_FEATURE -> byteArrayOf(0x00, 0x00)
                BleUuids.SENSOR_LOCATION -> byteArrayOf(0x02)
                else -> null
            }

            if (value == null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onError("BLE advertiser zniknął przed startem reklamy.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleUuids.RSC_SERVICE))
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            listener.onState("Footpod BLE aktywny: RSC 0x1814 / nazwa Cadence Footpod")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            listener.onError("Nie udało się rozpocząć BLE advertising: kod=$errorCode")
        }
    }

    private fun buildMeasurement(): ByteArray {
        val cadence = latestCadenceRpm.coerceIn(0.0, 255.0)
        val speedMps = (cadence * metersPerRevolution / 60.0).coerceAtLeast(0.0)
        val speedRaw = (speedMps * 256.0).roundToInt().coerceIn(0, 0xFFFF)
        val cadenceRaw = cadence.roundToInt().coerceIn(0, 255)

        // Flags = 0: brak stride length / total distance / walking-running status.
        // Instantaneous Speed (uint16, 1/256 m/s) + Instantaneous Cadence (uint8, 1/min).
        return byteArrayOf(
            0x00,
            (speedRaw and 0xFF).toByte(),
            ((speedRaw shr 8) and 0xFF).toByte(),
            cadenceRaw.toByte()
        )
    }

    @SuppressLint("MissingPermission")
    private fun notifyMeasurement() {
        val server = gattServer ?: return
        val characteristic = measurementCharacteristic ?: return
        if (subscribedAddresses.isEmpty()) return

        val value = buildMeasurement()
        val devices = connectedDevices.values.filter { it.address in subscribedAddresses }

        for (device in devices) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, false, value)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        characteristic.value = value
                        server.notifyCharacteristicChanged(device, characteristic, false)
                    }
                }
            } catch (e: Exception) {
                listener.onError("Błąd wysyłania RSC do ${safeName(device)}: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (_: SecurityException) { device.address }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterName() {
        val oldName = originalAdapterName ?: return
        try {
            adapter.name = oldName
        } catch (_: Exception) {
        }
        originalAdapterName = null
    }
}
