package pl.huber.cadencetofootpod

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Minimalny kontroler OpenBikeControl po mDNS + TCP.
 * MyWhoosh odkrywa usługę _openbikecontrol._tcp i łączy się do tego serwera.
 */
class OpenBikeControlServer(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onObcState(message: String)
        fun onObcClientCountChanged(count: Int)
        fun onObcLog(message: String)
        fun onObcError(message: String)
    }

    companion object {
        private const val SERVICE_TYPE = "_openbikecontrol._tcp."
        private const val SERVICE_NAME = "Cadence AutoShift"
        private const val PROTOCOL_VERSION = "1"
        private const val OBC_SERVICE_UUID = "d273f680-d548-419d-b9d1-fa0472345229"
        private const val BUTTON_SHIFT_UP = 0x01
        private const val BUTTON_SHIFT_DOWN = 0x02
    }

    private data class Client(
        val socket: Socket,
        val key: String
    )

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val clients = ConcurrentHashMap<String, Client>()

    @Volatile
    private var started = false

    @Volatile
    private var nsdRegistered = false

    private var registeredServiceName: String? = null
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var ioExecutor: ExecutorService? = null
    private var commandExecutor: ExecutorService? = null
    private var statusExecutor: ScheduledExecutorService? = null

    fun isStarted(): Boolean = started
    fun clientCount(): Int = clients.size

    @Synchronized
    fun start() {
        if (started) return

        try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
            serverSocket = socket
            started = true

            ioExecutor = Executors.newCachedThreadPool()
            commandExecutor = Executors.newSingleThreadExecutor()
            statusExecutor = Executors.newSingleThreadScheduledExecutor()

            ioExecutor?.execute { acceptLoop(socket) }
            statusExecutor?.scheduleAtFixedRate(
                { sendDeviceStatus() },
                30L,
                30L,
                TimeUnit.SECONDS
            )

            registerNsd(socket.localPort)
            listener.onObcState("OpenBikeControl uruchamianie — TCP port ${socket.localPort}")
        } catch (e: Exception) {
            listener.onObcError("Nie udało się uruchomić OpenBikeControl: ${e.message}")
            stop()
        }
    }

    @Synchronized
    fun stop() {
        if (!started && serverSocket == null && !nsdRegistered) return
        started = false

        registrationListener?.let { registration ->
            if (nsdRegistered) {
                try {
                    nsdManager.unregisterService(registration)
                } catch (_: Exception) {
                }
            }
        }
        nsdRegistered = false
        registrationListener = null
        registeredServiceName = null

        clients.values.forEach { client ->
            try {
                client.socket.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()
        listener.onObcClientCountChanged(0)

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        statusExecutor?.shutdownNow()
        statusExecutor = null
        commandExecutor?.shutdownNow()
        commandExecutor = null
        ioExecutor?.shutdownNow()
        ioExecutor = null

        listener.onObcState("OpenBikeControl zatrzymany")
    }

    fun shiftUp(count: Int = 1) = sendButtonPress(BUTTON_SHIFT_UP, "SHIFT UP", count)
    fun shiftDown(count: Int = 1) = sendButtonPress(BUTTON_SHIFT_DOWN, "SHIFT DOWN", count)

    private fun sendButtonPress(buttonId: Int, label: String, requestedCount: Int) {
        if (!started) {
            listener.onObcError("OpenBikeControl nie jest uruchomiony.")
            return
        }
        if (clients.isEmpty()) {
            listener.onObcLog("$label pominięty — brak połączonego klienta MyWhoosh.")
            return
        }

        val count = requestedCount.coerceIn(1, 5)
        commandExecutor?.execute {
            repeat(count) { index ->
                if (!started || clients.isEmpty()) return@execute

                sendToAll(byteArrayOf(0x01, buttonId.toByte(), 0x01))
                if (!sleepCommandDelay(70L)) return@execute
                sendToAll(byteArrayOf(0x01, buttonId.toByte(), 0x00))

                // Krótki odstęp między kolejnymi zmianami zapobiega zgubieniu
                // części kliknięć przez aplikację odbierającą.
                if (index < count - 1 && !sleepCommandDelay(130L)) return@execute
            }
            listener.onObcLog("Wysłano $label ×$count")
        }
    }

    private fun sleepCommandDelay(delayMs: Long): Boolean = try {
        Thread.sleep(delayMs)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun sendDeviceStatus() {
        if (!started || clients.isEmpty()) return
        // 0xFF = urządzenie bez własnego pomiaru baterii, 0x01 = gotowe.
        sendToAll(byteArrayOf(0x02, 0xFF.toByte(), 0x01))
    }

    private fun sendToAll(payload: ByteArray) {
        val failed = mutableListOf<String>()
        clients.values.forEach { client ->
            try {
                synchronized(client.socket) {
                    client.socket.getOutputStream().write(payload)
                    client.socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
                failed += client.key
            }
        }
        failed.forEach { removeClient(it) }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (started) {
            try {
                val clientSocket = socket.accept().apply {
                    tcpNoDelay = true
                    keepAlive = true
                }
                val key = clientSocket.remoteSocketAddress?.toString() ?: UUID.randomUUID().toString()
                val client = Client(clientSocket, key)
                clients[key] = client
                listener.onObcClientCountChanged(clients.size)
                listener.onObcState("MyWhoosh/OpenBikeControl połączony: $key")
                listener.onObcLog("Klient TCP połączony: $key")

                try {
                    synchronized(clientSocket) {
                        clientSocket.getOutputStream().write(byteArrayOf(0x02, 0xFF.toByte(), 0x01))
                        clientSocket.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                }

                ioExecutor?.execute { readClient(client) }
            } catch (e: Exception) {
                if (started) listener.onObcError("Błąd TCP OpenBikeControl: ${e.message}")
            }
        }
    }

    private fun readClient(client: Client) {
        try {
            val input = client.socket.getInputStream()
            while (started && !client.socket.isClosed) {
                when (val type = input.read()) {
                    -1 -> break
                    0x03 -> readHaptic(input)
                    0x04 -> readAppInformation(input)
                    else -> listener.onObcLog("OBC: odebrano nieobsługiwany typ 0x${type.toString(16).padStart(2, '0')}")
                }
            }
        } catch (_: EOFException) {
        } catch (e: Exception) {
            if (started) listener.onObcLog("Klient OBC zakończył połączenie: ${e.message ?: "EOF"}")
        } finally {
            removeClient(client.key)
        }
    }

    private fun readHaptic(input: InputStream) {
        val data = input.readExactly(3)
        val pattern = data[0].toInt() and 0xFF
        val duration = (data[1].toInt() and 0xFF) * 10
        val intensity = data[2].toInt() and 0xFF
        listener.onObcLog("Haptic z aplikacji: pattern=$pattern duration=${duration}ms intensity=$intensity")
    }

    private fun readAppInformation(input: InputStream) {
        val version = input.readRequired()
        val appIdLength = input.readRequired()
        val appId = input.readExactly(appIdLength).toString(Charsets.UTF_8)
        val appVersionLength = input.readRequired()
        val appVersion = input.readExactly(appVersionLength).toString(Charsets.UTF_8)
        val buttonCount = input.readRequired()
        val buttons = if (buttonCount > 0) input.readExactly(buttonCount) else ByteArray(0)
        val buttonsText = if (buttons.isEmpty()) {
            "wszystkie"
        } else {
            buttons.joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }
        }
        listener.onObcLog(
            "App info: id=$appId version=$appVersion format=$version buttons=$buttonsText"
        )
    }

    private fun removeClient(key: String) {
        val removed = clients.remove(key) ?: return
        try {
            removed.socket.close()
        } catch (_: Exception) {
        }
        listener.onObcClientCountChanged(clients.size)
        listener.onObcState(
            if (clients.isEmpty()) "OpenBikeControl aktywny — oczekiwanie na MyWhoosh" else "OpenBikeControl: ${clients.size} klient(ów)"
        )
    }

    private fun registerNsd(port: Int) {
        val stableId = context.getSharedPreferences("obc", Context.MODE_PRIVATE)
            .let { prefs ->
                prefs.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
                    prefs.edit().putString("device_id", it).apply()
                }
            }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("version", PROTOCOL_VERSION)
            setAttribute("id", stableId)
            setAttribute("name", SERVICE_NAME)
            setAttribute("service-uuids", OBC_SERVICE_UUID)
            setAttribute("manufacturer", "HUBER")
            setAttribute("model", "CadenceToFootpodAndroid")
        }

        val listenerImpl = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                nsdRegistered = true
                registeredServiceName = info.serviceName
                listener.onObcState("OpenBikeControl aktywny: ${info.serviceName}:$port")
                listener.onObcLog("mDNS zarejestrowany: ${info.serviceName}.$SERVICE_TYPE")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                nsdRegistered = false
                listener.onObcError("Rejestracja mDNS nie powiodła się: kod=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                nsdRegistered = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                listener.onObcLog("Nie udało się wyrejestrować mDNS: kod=$errorCode")
            }
        }

        registrationListener = listenerImpl
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listenerImpl)
    }

    private fun InputStream.readRequired(): Int {
        val value = read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val readNow = read(result, offset, length - offset)
            if (readNow < 0) throw EOFException()
            offset += readNow
        }
        return result
    }
}
