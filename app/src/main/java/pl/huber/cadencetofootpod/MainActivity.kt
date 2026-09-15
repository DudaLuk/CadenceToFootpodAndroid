package pl.huber.cadencetofootpod

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(),
    CadenceSensorScanner.Listener,
    CscCadenceClient.Listener,
    TrainerCadenceClient.Listener,
    FootpodGattServer.Listener,
    OpenBikeControlServer.Listener,
    AutoShiftEngine.Listener,
    PowerTargetEngine.Listener {

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val REQ_ENABLE_BT = 1002
    }

    private enum class AppTab { CONNECTION, BIKE, POWER }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var scanner: CadenceSensorScanner
    private lateinit var cadenceClient: CscCadenceClient
    private lateinit var trainerCadenceClient: TrainerCadenceClient
    private lateinit var footpodServer: FootpodGattServer
    private lateinit var obcServer: OpenBikeControlServer
    private lateinit var autoShiftEngine: AutoShiftEngine
    private lateinit var powerTargetEngine: PowerTargetEngine

    private lateinit var connectionContent: View
    private lateinit var bikeContent: View
    private lateinit var powerContent: View
    private lateinit var connectionTabButton: Button
    private lateinit var bikeTabButton: Button
    private lateinit var powerTabButton: Button

    private lateinit var permissionsText: TextView
    private lateinit var cadenceStatusText: TextView
    private lateinit var cadenceValueText: TextView
    private lateinit var bikeCadenceValueText: TextView
    private lateinit var powerCurrentPowerText: TextView
    private lateinit var powerCurrentCadenceText: TextView
    private lateinit var footpodStatusText: TextView
    private lateinit var footpodClientsText: TextView
    private lateinit var speedText: TextView
    private lateinit var metersPerRevEdit: EditText
    private lateinit var scanButton: Button
    private lateinit var footpodButton: Button
    private lateinit var deviceList: ListView

    private lateinit var obcButton: Button
    private lateinit var obcStatusText: TextView
    private lateinit var obcClientsText: TextView
    private lateinit var bikeObcText: TextView

    private lateinit var targetRpmEdit: EditText
    private lateinit var hysteresisEdit: EditText
    private lateinit var targetRangeText: TextView
    private lateinit var triggerDelayEdit: EditText
    private lateinit var cooldownEdit: EditText
    private lateinit var rapidThresholdEdit: EditText
    private lateinit var rapidCorrectionButton: Button
    private lateinit var autoShiftButton: Button
    private lateinit var autoShiftStatusText: TextView

    private lateinit var powerTargetWattsEdit: EditText
    private lateinit var powerHysteresisEdit: EditText
    private lateinit var powerTargetCadenceEdit: EditText
    private lateinit var powerCadenceHysteresisEdit: EditText
    private lateinit var powerTriggerDelayEdit: EditText
    private lateinit var powerCooldownEdit: EditText
    private lateinit var powerTargetRangeText: TextView
    private lateinit var powerTargetButton: Button
    private lateinit var powerTargetStatusText: TextView
    private lateinit var powerObcText: TextView

    private lateinit var logText: TextView

    private val foundDevices = mutableListOf<CadenceSensorScanner.FoundDevice>()
    private val foundRows = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    private var currentCadence = 0.0
    private var currentPowerWatts = 0
    private var powerTelemetryAvailable = false
    private var powerSourceSelected = false
    private var rapidCorrectionEnabled = true
    private val rpmFormat = DecimalFormat("0.0")
    private val speedFormat = DecimalFormat("0.00")
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Toast.makeText(this, "Ten telefon nie ma Bluetooth.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        bluetoothAdapter = adapter

        buildUi()

        scanner = CadenceSensorScanner(bluetoothAdapter, this)
        cadenceClient = CscCadenceClient(this, this)
        trainerCadenceClient = TrainerCadenceClient(this, this)
        footpodServer = FootpodGattServer(this, bluetoothAdapter, this)
        obcServer = OpenBikeControlServer(this, this)
        autoShiftEngine = AutoShiftEngine(this)
        powerTargetEngine = PowerTargetEngine(this)

        applyAutoShiftConfig(showToastOnError = false, writeLog = false)
        applyPowerTargetConfig(showToastOnError = false, writeLog = false)
        refreshPermissionsState()
        requestMissingPermissions()
        ensureBluetoothEnabled()

        deviceList.setOnItemClickListener { _, _, position, _ ->
            if (!hasRequiredPermissions()) return@setOnItemClickListener
            scanner.stop()
            val found = foundDevices[position]
            appendLog("Wybrano źródło kadencji: ${safeDeviceLabel(found.device)} [${found.type.label}]")
            when (found.type) {
                CadenceSensorScanner.DeviceType.CSC -> {
                    trainerCadenceClient.disconnect()
                    powerSourceSelected = false
                    powerTelemetryAvailable = false
                    currentPowerWatts = 0
                    powerTargetEngine.onPowerUnavailable()
                    if (powerTargetEngine.isEnabled()) {
                        powerTargetEngine.setEnabled(false)
                        refreshPowerTargetButton()
                    }
                    cadenceClient.connect(found.device)
                }

                CadenceSensorScanner.DeviceType.FTMS,
                CadenceSensorScanner.DeviceType.CYCLING_POWER -> {
                    cadenceClient.disconnect()
                    powerSourceSelected = true
                    powerTelemetryAvailable = false
                    currentPowerWatts = 0
                    trainerCadenceClient.connect(found.device)
                }
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        root.addView(TextView(this).apply {
            text = "Cadence → Footpod + AutoShift"
            textSize = 23f
        })
        root.addView(TextView(this).apply {
            text = "CSC / KICKR FTMS → Footpod + OpenBikeControl → MyWhoosh"
            textSize = 12f
        })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }
        connectionTabButton = Button(this).apply {
            text = "Połączenie"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showTab(AppTab.CONNECTION) }
        }
        bikeTabButton = Button(this).apply {
            text = "Rower"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showTab(AppTab.BIKE) }
        }
        powerTabButton = Button(this).apply {
            text = "Moc"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showTab(AppTab.POWER) }
        }
        tabs.addView(connectionTabButton)
        tabs.addView(bikeTabButton)
        tabs.addView(powerTabButton)
        root.addView(tabs)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        connectionContent = buildConnectionTab()
        bikeContent = buildBikeTab()
        powerContent = buildPowerTab()
        frame.addView(connectionContent)
        frame.addView(bikeContent)
        frame.addView(powerContent)
        root.addView(frame)

        setContentView(root)
        showTab(AppTab.CONNECTION)
    }

    private fun buildConnectionTab(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(20))
        }

        permissionsText = sectionText("Uprawnienia: sprawdzanie…")
        content.addView(permissionsText)

        addHeader(content, "1. Czujnik kadencji / KICKR")
        scanButton = Button(this).apply {
            text = "Skanuj czujnik / KICKR"
            setOnClickListener {
                if (!prepareBluetoothOperation()) return@setOnClickListener
                foundDevices.clear()
                foundRows.clear()
                listAdapter.notifyDataSetChanged()
                scanner.start()
            }
        }
        content.addView(scanButton)

        cadenceStatusText = sectionText("Czujnik: niepołączony")
        content.addView(cadenceStatusText)
        cadenceValueText = TextView(this).apply {
            text = "Kadencja: 0.0 RPM"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(8))
        }
        content.addView(cadenceValueText)

        content.addView(TextView(this).apply {
            text = "Znalezione źródła kadencji (CSC / KICKR / FTMS):"
        })
        deviceList = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(135)
            )
        }
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, foundRows)
        deviceList.adapter = listAdapter
        content.addView(deviceList)

        addHeader(content, "2. Wirtualny BLE Footpod")
        val metersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metersRow.addView(TextView(this).apply {
            text = "Metry / obrót: "
            textSize = 16f
        })
        metersPerRevEdit = decimalEdit("2.00", 110)
        metersRow.addView(metersPerRevEdit)
        content.addView(metersRow)

        speedText = TextView(this).apply {
            text = "Emulowana prędkość: 0.00 km/h"
            textSize = 16f
        }
        content.addView(speedText)

        footpodButton = Button(this).apply {
            text = "Uruchom wirtualny Footpod"
            setOnClickListener {
                if (!prepareBluetoothOperation()) return@setOnClickListener
                if (footpodServer.isStarted()) {
                    footpodServer.stop()
                    text = "Uruchom wirtualny Footpod"
                } else {
                    footpodServer.start(readMetersPerRevolution())
                    text = if (footpodServer.isStarted()) {
                        "Zatrzymaj wirtualny Footpod"
                    } else {
                        "Uruchom wirtualny Footpod"
                    }
                }
            }
        }
        content.addView(footpodButton)
        footpodStatusText = sectionText("Footpod: zatrzymany")
        content.addView(footpodStatusText)
        footpodClientsText = TextView(this).apply { text = "Klienci Footpoda: 0" }
        content.addView(footpodClientsText)

        addHeader(content, "3. OpenBikeControl / MyWhoosh")
        obcButton = Button(this).apply {
            text = "Uruchom OpenBikeControl"
            setOnClickListener {
                if (obcServer.isStarted()) {
                    autoShiftEngine.setEnabled(false)
                    powerTargetEngine.setEnabled(false)
                    refreshAutoShiftButton()
                    refreshPowerTargetButton()
                    obcServer.stop()
                    text = "Uruchom OpenBikeControl"
                } else {
                    obcServer.start()
                    text = if (obcServer.isStarted()) {
                        "Zatrzymaj OpenBikeControl"
                    } else {
                        "Uruchom OpenBikeControl"
                    }
                }
            }
        }
        content.addView(obcButton)
        obcStatusText = sectionText("OpenBikeControl: zatrzymany")
        content.addView(obcStatusText)
        obcClientsText = TextView(this).apply { text = "Klienci OpenBikeControl: 0" }
        content.addView(obcClientsText)

        addHeader(content, "Log")
        logText = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            minHeight = dp(220)
        }
        content.addView(logText)

        return ScrollView(this).apply { addView(content) }
    }

    private fun buildBikeTab(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(24))
        }

        addHeader(content, "Rower / AutoShift")
        bikeCadenceValueText = TextView(this).apply {
            text = "0.0 RPM"
            textSize = 38f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
        }
        content.addView(bikeCadenceValueText)
        content.addView(TextView(this).apply {
            text = "aktualna kadencja"
            gravity = Gravity.CENTER_HORIZONTAL
        })

        bikeObcText = sectionText("MyWhoosh / OpenBikeControl: 0 klientów")
        bikeObcText.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(bikeObcText)

        addHeader(content, "Kadencja docelowa")
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        targetRow.addView(TextView(this).apply { text = "Kadencja: " })
        targetRpmEdit = decimalEdit("85", 80)
        targetRow.addView(targetRpmEdit)
        targetRow.addView(TextView(this).apply { text = " RPM    Histereza: ±" })
        hysteresisEdit = decimalEdit("5", 65)
        targetRow.addView(hysteresisEdit)
        targetRow.addView(TextView(this).apply { text = " RPM" })
        content.addView(targetRow)

        targetRangeText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(targetRangeText)
        refreshTargetRangeText()

        val cadenceAdjustRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        cadenceAdjustRow.addView(Button(this).apply {
            text = "Kadencja −1"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { adjustTargetCadence(-1.0) }
        })
        cadenceAdjustRow.addView(Button(this).apply {
            text = "Kadencja +1"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { adjustTargetCadence(+1.0) }
        })
        content.addView(cadenceAdjustRow)

        addHeader(content, "Ręczna zmiana biegu")
        val gearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        gearRow.addView(Button(this).apply {
            text = "Bieg −1"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { manualShift(AutoShiftEngine.Direction.DOWN) }
        })
        gearRow.addView(Button(this).apply {
            text = "Bieg +1"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { manualShift(AutoShiftEngine.Direction.UP) }
        })
        content.addView(gearRow)

        addHeader(content, "Zachowanie automatu")
        val timingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timingRow.addView(TextView(this).apply { text = "Zwłoka [s]: " })
        triggerDelayEdit = decimalEdit("1.5", 75)
        timingRow.addView(triggerDelayEdit)
        timingRow.addView(TextView(this).apply { text = "   Cooldown [s]: " })
        cooldownEdit = decimalEdit("3.0", 75)
        timingRow.addView(cooldownEdit)
        content.addView(timingRow)

        val rapidRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rapidRow.addView(TextView(this).apply { text = "Gwałtowna zmiana od: " })
        rapidThresholdEdit = decimalEdit("10", 65)
        rapidRow.addView(rapidThresholdEdit)
        rapidRow.addView(TextView(this).apply { text = " RPM / 1.5 s" })
        content.addView(rapidRow)

        content.addView(TextView(this).apply {
            text = "Szybka korekta: 1 bieg na każde 5 RPM zmiany, maksymalnie ±5 biegów w jednej serii."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        })

        rapidCorrectionButton = Button(this).apply {
            setOnClickListener {
                rapidCorrectionEnabled = !rapidCorrectionEnabled
                refreshRapidCorrectionButton()
                applyAutoShiftConfig(showToastOnError = true, writeLog = true)
            }
        }
        content.addView(rapidCorrectionButton)
        refreshRapidCorrectionButton()

        content.addView(Button(this).apply {
            text = "Zastosuj ustawienia"
            setOnClickListener {
                applyAutoShiftConfig(showToastOnError = true, writeLog = true)
            }
        })

        autoShiftButton = Button(this).apply {
            text = "AutoShift: WYŁĄCZONY"
            setOnClickListener {
                val enable = !autoShiftEngine.isEnabled()
                if (enable) {
                    if (!applyAutoShiftConfig(showToastOnError = true, writeLog = true)) {
                        return@setOnClickListener
                    }
                    if (powerTargetEngine.isEnabled()) {
                        powerTargetEngine.setEnabled(false)
                        refreshPowerTargetButton()
                    }
                    ensureObcStarted()
                }
                autoShiftEngine.setEnabled(enable)
                refreshAutoShiftButton()
            }
        }
        content.addView(autoShiftButton)

        autoShiftStatusText = sectionText("AutoShift wyłączony")
        autoShiftStatusText.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(autoShiftStatusText)

        return ScrollView(this).apply { addView(content) }
    }

    private fun buildPowerTab(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(24))
        }

        addHeader(content, "Jazda na moc / Power Target")

        val telemetryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        powerCurrentPowerText = TextView(this).apply {
            text = "— W"
            textSize = 36f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        powerCurrentCadenceText = TextView(this).apply {
            text = "0.0 RPM"
            textSize = 30f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        telemetryRow.addView(powerCurrentPowerText)
        telemetryRow.addView(powerCurrentCadenceText)
        content.addView(telemetryRow)

        content.addView(TextView(this).apply {
            text = "aktualna moc                              aktualna kadencja"
            gravity = Gravity.CENTER_HORIZONTAL
        })

        powerObcText = sectionText("MyWhoosh / OpenBikeControl: 0 klientów")
        powerObcText.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(powerObcText)

        addHeader(content, "Moc docelowa")
        val powerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        powerRow.addView(TextView(this).apply { text = "Moc: " })
        powerTargetWattsEdit = decimalEdit("200", 85)
        powerRow.addView(powerTargetWattsEdit)
        powerRow.addView(TextView(this).apply { text = " W    Histereza: ±" })
        powerHysteresisEdit = decimalEdit("10", 65)
        powerRow.addView(powerHysteresisEdit)
        powerRow.addView(TextView(this).apply { text = " W" })
        content.addView(powerRow)

        val powerAdjustRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        powerAdjustRow.addView(Button(this).apply {
            text = "Moc −5 W"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { adjustPowerTarget(-5.0) }
        })
        powerAdjustRow.addView(Button(this).apply {
            text = "Moc +5 W"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { adjustPowerTarget(+5.0) }
        })
        content.addView(powerAdjustRow)

        addHeader(content, "Kadencja docelowa")
        val cadenceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cadenceRow.addView(TextView(this).apply { text = "Kadencja: " })
        powerTargetCadenceEdit = decimalEdit("85", 80)
        cadenceRow.addView(powerTargetCadenceEdit)
        cadenceRow.addView(TextView(this).apply { text = " RPM    Histereza: ±" })
        powerCadenceHysteresisEdit = decimalEdit("5", 65)
        cadenceRow.addView(powerCadenceHysteresisEdit)
        cadenceRow.addView(TextView(this).apply { text = " RPM" })
        content.addView(cadenceRow)

        val cadenceAdjustRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        cadenceAdjustRow.addView(Button(this).apply {
            text = "Kadencja −1"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { adjustPowerCadenceTarget(-1.0) }
        })
        cadenceAdjustRow.addView(Button(this).apply {
            text = "Kadencja +1"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { adjustPowerCadenceTarget(+1.0) }
        })
        content.addView(cadenceAdjustRow)

        powerTargetRangeText = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }
        content.addView(powerTargetRangeText)
        refreshPowerTargetRangeText()

        addHeader(content, "Ręczna zmiana biegu")
        val gearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        gearRow.addView(Button(this).apply {
            text = "Bieg −1"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { manualShift(AutoShiftEngine.Direction.DOWN) }
        })
        gearRow.addView(Button(this).apply {
            text = "Bieg +1"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { manualShift(AutoShiftEngine.Direction.UP) }
        })
        content.addView(gearRow)

        addHeader(content, "Zachowanie regulatora")
        val timingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timingRow.addView(TextView(this).apply { text = "Zwłoka [s]: " })
        powerTriggerDelayEdit = decimalEdit("2.0", 75)
        timingRow.addView(powerTriggerDelayEdit)
        timingRow.addView(TextView(this).apply { text = "   Cooldown [s]: " })
        powerCooldownEdit = decimalEdit("4.0", 75)
        timingRow.addView(powerCooldownEdit)
        content.addView(timingRow)

        content.addView(TextView(this).apply {
            text = "Regulator zmienia jeden bieg naraz. Gdy błąd mocy i kadencji wskazują przeciwne kierunki, nie zmienia biegu i czeka na zmianę wysiłku."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
        })

        content.addView(Button(this).apply {
            text = "Zastosuj ustawienia"
            setOnClickListener {
                applyPowerTargetConfig(showToastOnError = true, writeLog = true)
            }
        })

        powerTargetButton = Button(this).apply {
            text = "Target mocy: WYŁĄCZONY"
            setOnClickListener {
                val enable = !powerTargetEngine.isEnabled()
                if (enable) {
                    if (!powerSourceSelected) {
                        Toast.makeText(
                            this@MainActivity,
                            "Tryb mocy wymaga KICKR / FTMS / Cycling Power jako źródła danych.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    if (!applyPowerTargetConfig(showToastOnError = true, writeLog = true)) {
                        return@setOnClickListener
                    }
                    if (autoShiftEngine.isEnabled()) {
                        autoShiftEngine.setEnabled(false)
                        refreshAutoShiftButton()
                    }
                    ensureObcStarted()
                }
                powerTargetEngine.setEnabled(enable)
                refreshPowerTargetButton()
            }
        }
        content.addView(powerTargetButton)

        powerTargetStatusText = sectionText("Target mocy wyłączony")
        powerTargetStatusText.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(powerTargetStatusText)

        return ScrollView(this).apply { addView(content) }
    }

    private fun showTab(tab: AppTab) {
        connectionContent.visibility = if (tab == AppTab.CONNECTION) View.VISIBLE else View.GONE
        bikeContent.visibility = if (tab == AppTab.BIKE) View.VISIBLE else View.GONE
        powerContent.visibility = if (tab == AppTab.POWER) View.VISIBLE else View.GONE
        connectionTabButton.isEnabled = tab != AppTab.CONNECTION
        bikeTabButton.isEnabled = tab != AppTab.BIKE
        powerTabButton.isEnabled = tab != AppTab.POWER
    }

    private fun addHeader(root: LinearLayout, textValue: String) {
        root.addView(TextView(this).apply {
            text = textValue
            textSize = 18f
            setPadding(0, dp(14), 0, dp(6))
        })
    }

    private fun sectionText(initial: String): TextView = TextView(this).apply {
        text = initial
        textSize = 15f
        setPadding(0, dp(6), 0, dp(5))
    }

    private fun decimalEdit(value: String, widthDp: Int): EditText = EditText(this).apply {
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestMissingPermissions() {
        val permissions = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun refreshPermissionsState() {
        if (!::permissionsText.isInitialized) return
        permissionsText.text = if (hasRequiredPermissions()) {
            "Uprawnienia BLE: OK"
        } else {
            "Uprawnienia BLE: wymagane"
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothEnabled() {
        if (!hasRequiredPermissions()) return
        if (!bluetoothAdapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
        } else {
            appendLog(
                "BLE advertising dostępny: ${bluetoothAdapter.bluetoothLeAdvertiser != null}; " +
                    "multiple advertisements: ${bluetoothAdapter.isMultipleAdvertisementSupported}"
            )
        }
    }

    private fun prepareBluetoothOperation(): Boolean {
        if (!hasRequiredPermissions()) {
            requestMissingPermissions()
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            ensureBluetoothEnabled()
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            refreshPermissionsState()
            if (hasRequiredPermissions()) {
                appendLog("Uprawnienia BLE przyznane.")
                ensureBluetoothEnabled()
            } else {
                appendLog("Brak wymaganych uprawnień BLE.")
            }
        }
    }

    @Deprecated("Deprecated in Android SDK, zachowane dla minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT) {
            appendLog(if (resultCode == RESULT_OK) "Bluetooth włączony." else "Bluetooth pozostał wyłączony.")
        }
    }

    override fun onScanStarted() {
        runOnUiThread {
            cadenceStatusText.text = "Czujnik: skanowanie CSC / FTMS / KICKR…"
            scanButton.isEnabled = false
            appendLog("Skanowanie CSC 0x1816, FTMS 0x1826, Cycling Power 0x1818 i urządzeń KICKR…")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceFound(foundDevice: CadenceSensorScanner.FoundDevice) {
        runOnUiThread {
            foundDevices.add(foundDevice)
            val name = foundDevice.advertisedName ?: safeDeviceLabel(foundDevice.device)
            foundRows.add("$name   [${foundDevice.type.label}]   RSSI ${foundDevice.rssi} dBm")
            listAdapter.notifyDataSetChanged()
        }
    }

    override fun onScanStopped() {
        runOnUiThread {
            scanButton.isEnabled = true
            if (foundDevices.isEmpty()) {
                cadenceStatusText.text = "Czujnik: nic nie znaleziono"
                appendLog("Skan zakończony — brak CSC/FTMS/Cycling Power/KICKR. Obudź trenażer pedałując kilka obrotów.")
            } else {
                cadenceStatusText.text = "Czujnik: wybierz urządzenie z listy"
                appendLog("Skan zakończony — znaleziono ${foundDevices.size} źródło/a kadencji.")
            }
        }
    }

    override fun onScanError(message: String) {
        runOnUiThread {
            scanButton.isEnabled = true
            cadenceStatusText.text = "Czujnik: błąd skanowania"
            appendLog(message)
        }
    }

    override fun onConnectionChanged(connected: Boolean, message: String) {
        runOnUiThread {
            cadenceStatusText.text = "Czujnik: $message"
            appendLog(message)
        }
    }

    override fun onCadenceChanged(rpm: Double) {
        currentCadence = rpm
        footpodServer.updateCadence(rpm)
        autoShiftEngine.onCadence(rpm)
        if (powerTelemetryAvailable) {
            powerTargetEngine.onTelemetry(currentPowerWatts.toDouble(), rpm)
        }
        runOnUiThread {
            cadenceValueText.text = "Kadencja: ${rpmFormat.format(rpm)} RPM"
            bikeCadenceValueText.text = "${rpmFormat.format(rpm)} RPM"
            powerCurrentCadenceText.text = "${rpmFormat.format(rpm)} RPM"
            updateCalculatedSpeed()
        }
    }

    override fun onPowerChanged(watts: Int) {
        if (watts < 0) {
            powerTelemetryAvailable = false
            currentPowerWatts = 0
            powerTargetEngine.onPowerUnavailable()
            runOnUiThread {
                powerCurrentPowerText.text = "— W"
            }
            return
        }

        powerTelemetryAvailable = true
        currentPowerWatts = watts
        powerTargetEngine.onTelemetry(currentPowerWatts.toDouble(), currentCadence)
        runOnUiThread {
            powerCurrentPowerText.text = "$currentPowerWatts W"
        }
    }

    // Footpod callbacks
    override fun onError(message: String) {
        runOnUiThread { appendLog("BŁĄD BLE: $message") }
    }

    override fun onState(message: String) {
        runOnUiThread {
            footpodStatusText.text = "Footpod: $message"
            appendLog(message)
            if (!footpodServer.isStarted()) {
                footpodButton.text = "Uruchom wirtualny Footpod"
            }
        }
    }

    override fun onClientCountChanged(count: Int) {
        runOnUiThread { footpodClientsText.text = "Klienci Footpoda: $count" }
    }

    // OpenBikeControl callbacks
    override fun onObcState(message: String) {
        runOnUiThread {
            obcStatusText.text = "OpenBikeControl: $message"
            if (!obcServer.isStarted()) obcButton.text = "Uruchom OpenBikeControl"
        }
    }

    override fun onObcClientCountChanged(count: Int) {
        runOnUiThread {
            obcClientsText.text = "Klienci OpenBikeControl: $count"
            bikeObcText.text = "MyWhoosh / OpenBikeControl: $count klient(ów)"
            powerObcText.text = "MyWhoosh / OpenBikeControl: $count klient(ów)"
        }
    }

    override fun onObcLog(message: String) {
        runOnUiThread { appendLog("OBC: $message") }
    }

    override fun onObcError(message: String) {
        runOnUiThread {
            appendLog("BŁĄD OBC: $message")
            obcStatusText.text = "OpenBikeControl: błąd — $message"
        }
    }

    // AutoShift callbacks
    override fun onAutoShiftDecision(
        direction: AutoShiftEngine.Direction,
        cadenceRpm: Double,
        gearSteps: Int,
        reason: String
    ) {
        when (direction) {
            AutoShiftEngine.Direction.UP -> obcServer.shiftUp(gearSteps)
            AutoShiftEngine.Direction.DOWN -> obcServer.shiftDown(gearSteps)
        }
        runOnUiThread {
            val label = if (direction == AutoShiftEngine.Direction.UP) "SHIFT UP" else "SHIFT DOWN"
            appendLog(
                "AUTO: $label ×$gearSteps przy ${rpmFormat.format(cadenceRpm)} RPM — $reason"
            )
        }
    }

    override fun onAutoShiftState(message: String) {
        runOnUiThread { autoShiftStatusText.text = message }
    }

    // Power Target callbacks
    override fun onPowerTargetDecision(
        direction: PowerTargetEngine.Direction,
        powerWatts: Double,
        cadenceRpm: Double,
        reason: String
    ) {
        when (direction) {
            PowerTargetEngine.Direction.UP -> obcServer.shiftUp(1)
            PowerTargetEngine.Direction.DOWN -> obcServer.shiftDown(1)
        }
        runOnUiThread {
            val label = if (direction == PowerTargetEngine.Direction.UP) "SHIFT UP" else "SHIFT DOWN"
            appendLog(
                "POWER: $label przy ${formatCompact(powerWatts)} W / ${rpmFormat.format(cadenceRpm)} RPM — $reason"
            )
        }
    }

    override fun onPowerTargetState(message: String) {
        runOnUiThread {
            if (::powerTargetStatusText.isInitialized) powerTargetStatusText.text = message
        }
    }

    private fun ensureObcStarted() {
        if (!obcServer.isStarted()) {
            obcServer.start()
            obcButton.text = if (obcServer.isStarted()) {
                "Zatrzymaj OpenBikeControl"
            } else {
                "Uruchom OpenBikeControl"
            }
        }
    }

    private fun manualShift(direction: AutoShiftEngine.Direction) {
        ensureObcStarted()
        autoShiftEngine.registerManualShift()
        powerTargetEngine.registerManualShift()
        when (direction) {
            AutoShiftEngine.Direction.UP -> obcServer.shiftUp(1)
            AutoShiftEngine.Direction.DOWN -> obcServer.shiftDown(1)
        }
        appendLog(
            "RĘCZNIE: ${if (direction == AutoShiftEngine.Direction.UP) "Bieg +1" else "Bieg -1"}"
        )
    }

    private fun adjustTargetCadence(delta: Double) {
        val current = readDecimal(targetRpmEdit, 85.0)
        val updated = (current + delta).coerceIn(30.0, 200.0)
        targetRpmEdit.setText(if (updated % 1.0 == 0.0) updated.toInt().toString() else rpmFormat.format(updated))
        refreshTargetRangeText()
        if (::autoShiftEngine.isInitialized) {
            applyAutoShiftConfig(showToastOnError = true, writeLog = false)
        }
    }

    private fun applyAutoShiftConfig(
        showToastOnError: Boolean,
        writeLog: Boolean
    ): Boolean {
        val target = readDecimal(targetRpmEdit, 85.0).coerceIn(30.0, 200.0)
        val hysteresis = readDecimal(hysteresisEdit, 5.0).coerceIn(0.5, 30.0)
        val delaySeconds = readDecimal(triggerDelayEdit, 1.5).coerceIn(0.2, 30.0)
        val cooldownSeconds = readDecimal(cooldownEdit, 3.0).coerceIn(0.5, 60.0)
        val rapidThreshold = readDecimal(rapidThresholdEdit, 10.0).coerceIn(5.0, 50.0)

        if (target - hysteresis < 20.0) {
            if (showToastOnError) {
                Toast.makeText(
                    this,
                    "Kadencja docelowa minus histereza musi wynosić co najmniej 20 RPM.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return false
        }

        targetRpmEdit.setText(formatCompact(target))
        hysteresisEdit.setText(formatCompact(hysteresis))
        rapidThresholdEdit.setText(formatCompact(rapidThreshold))
        refreshTargetRangeText()

        autoShiftEngine.updateConfig(
            AutoShiftEngine.Config(
                targetRpm = target,
                hysteresisRpm = hysteresis,
                triggerDelayMs = (delaySeconds * 1000.0).toLong(),
                cooldownMs = (cooldownSeconds * 1000.0).toLong(),
                minActiveCadenceRpm = 20.0,
                rapidChangeEnabled = rapidCorrectionEnabled,
                rapidChangeThresholdRpm = rapidThreshold,
                rapidChangeWindowMs = 1_500L,
                rapidRpmPerGear = 5.0,
                maxRapidShifts = 5
            )
        )

        if (writeLog) {
            appendLog(
                "Ustawienia roweru: cel ${formatCompact(target)} RPM, histereza ±${formatCompact(hysteresis)}, " +
                    "szybka korekta ${if (rapidCorrectionEnabled) "ON" else "OFF"}, próg ${formatCompact(rapidThreshold)} RPM."
            )
        }
        return true
    }

    private fun refreshTargetRangeText() {
        if (!::targetRangeText.isInitialized) return
        val target = readDecimal(targetRpmEdit, 85.0)
        val hysteresis = readDecimal(hysteresisEdit, 5.0)
        val min = (target - hysteresis).coerceAtLeast(0.0)
        val max = target + hysteresis
        targetRangeText.text = "Strefa bez zmiany biegu: ${formatCompact(min)}–${formatCompact(max)} RPM"
    }

    private fun refreshRapidCorrectionButton() {
        if (!::rapidCorrectionButton.isInitialized) return
        rapidCorrectionButton.text = if (rapidCorrectionEnabled) {
            "Gwałtowna korekta: WŁĄCZONA (maks. ±5 biegów)"
        } else {
            "Gwałtowna korekta: WYŁĄCZONA"
        }
    }

    private fun refreshAutoShiftButton() {
        if (!::autoShiftButton.isInitialized) return
        autoShiftButton.text = if (autoShiftEngine.isEnabled()) {
            "AutoShift: WŁĄCZONY"
        } else {
            "AutoShift: WYŁĄCZONY"
        }
    }

    private fun adjustPowerTarget(delta: Double) {
        val current = readDecimal(powerTargetWattsEdit, 200.0)
        val updated = (current + delta).coerceIn(30.0, 2000.0)
        powerTargetWattsEdit.setText(formatCompact(updated))
        refreshPowerTargetRangeText()
        if (::powerTargetEngine.isInitialized) {
            applyPowerTargetConfig(showToastOnError = true, writeLog = false)
        }
    }

    private fun adjustPowerCadenceTarget(delta: Double) {
        val current = readDecimal(powerTargetCadenceEdit, 85.0)
        val updated = (current + delta).coerceIn(30.0, 200.0)
        powerTargetCadenceEdit.setText(formatCompact(updated))
        refreshPowerTargetRangeText()
        if (::powerTargetEngine.isInitialized) {
            applyPowerTargetConfig(showToastOnError = true, writeLog = false)
        }
    }

    private fun applyPowerTargetConfig(
        showToastOnError: Boolean,
        writeLog: Boolean
    ): Boolean {
        val targetPower = readDecimal(powerTargetWattsEdit, 200.0).coerceIn(30.0, 2000.0)
        val powerHysteresis = readDecimal(powerHysteresisEdit, 10.0).coerceIn(2.0, 500.0)
        val targetCadence = readDecimal(powerTargetCadenceEdit, 85.0).coerceIn(30.0, 200.0)
        val cadenceHysteresis = readDecimal(powerCadenceHysteresisEdit, 5.0).coerceIn(0.5, 30.0)
        val delaySeconds = readDecimal(powerTriggerDelayEdit, 2.0).coerceIn(0.5, 30.0)
        val cooldownSeconds = readDecimal(powerCooldownEdit, 4.0).coerceIn(1.0, 60.0)

        if (targetPower - powerHysteresis <= 0.0) {
            if (showToastOnError) {
                Toast.makeText(this, "Moc docelowa minus histereza musi być większa od zera.", Toast.LENGTH_LONG).show()
            }
            return false
        }
        if (targetCadence - cadenceHysteresis < 20.0) {
            if (showToastOnError) {
                Toast.makeText(this, "Kadencja docelowa minus histereza musi wynosić co najmniej 20 RPM.", Toast.LENGTH_LONG).show()
            }
            return false
        }

        powerTargetWattsEdit.setText(formatCompact(targetPower))
        powerHysteresisEdit.setText(formatCompact(powerHysteresis))
        powerTargetCadenceEdit.setText(formatCompact(targetCadence))
        powerCadenceHysteresisEdit.setText(formatCompact(cadenceHysteresis))
        powerTriggerDelayEdit.setText(formatCompact(delaySeconds))
        powerCooldownEdit.setText(formatCompact(cooldownSeconds))
        refreshPowerTargetRangeText()

        powerTargetEngine.updateConfig(
            PowerTargetEngine.Config(
                targetPowerW = targetPower,
                powerHysteresisW = powerHysteresis,
                targetCadenceRpm = targetCadence,
                cadenceHysteresisRpm = cadenceHysteresis,
                triggerDelayMs = (delaySeconds * 1000.0).toLong(),
                cooldownMs = (cooldownSeconds * 1000.0).toLong(),
                minActiveCadenceRpm = 20.0,
                powerSmoothingAlpha = 0.25
            )
        )

        if (writeLog) {
            appendLog(
                "Target mocy: ${formatCompact(targetPower)} W ±${formatCompact(powerHysteresis)} W, " +
                    "kadencja ${formatCompact(targetCadence)} RPM ±${formatCompact(cadenceHysteresis)}."
            )
        }
        return true
    }

    private fun refreshPowerTargetRangeText() {
        if (!::powerTargetRangeText.isInitialized) return
        val targetPower = readDecimal(powerTargetWattsEdit, 200.0)
        val powerHysteresis = readDecimal(powerHysteresisEdit, 10.0)
        val targetCadence = readDecimal(powerTargetCadenceEdit, 85.0)
        val cadenceHysteresis = readDecimal(powerCadenceHysteresisEdit, 5.0)
        powerTargetRangeText.text =
            "Cel: ${formatCompact(targetPower)} W (${formatCompact((targetPower - powerHysteresis).coerceAtLeast(0.0))}–${formatCompact(targetPower + powerHysteresis)} W)  •  " +
                "${formatCompact(targetCadence)} RPM (${formatCompact((targetCadence - cadenceHysteresis).coerceAtLeast(0.0))}–${formatCompact(targetCadence + cadenceHysteresis)} RPM)"
    }

    private fun refreshPowerTargetButton() {
        if (!::powerTargetButton.isInitialized) return
        powerTargetButton.text = if (powerTargetEngine.isEnabled()) {
            "Target mocy: WŁĄCZONY"
        } else {
            "Target mocy: WYŁĄCZONY"
        }
    }

    private fun readMetersPerRevolution(): Double {
        val value = readDecimal(metersPerRevEdit, 2.0).coerceIn(0.1, 10.0)
        footpodServer.setMetersPerRevolution(value)
        return value
    }

    private fun readDecimal(editText: EditText, fallback: Double): Double =
        editText.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: fallback

    private fun formatCompact(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else rpmFormat.format(value)

    private fun updateCalculatedSpeed() {
        val meters = readMetersPerRevolution()
        val kmh = currentCadence * meters / 60.0 * 3.6
        speedText.text = "Emulowana prędkość: ${speedFormat.format(kmh)} km/h"
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceLabel(device: BluetoothDevice): String =
        try {
            "${device.name ?: "(bez nazwy)"} [${device.address}]"
        } catch (_: SecurityException) {
            "urządzenie BLE"
        }

    private fun appendLog(message: String) {
        if (!::logText.isInitialized) return
        val line = "[${timeFormat.format(Date())}] $message"
        val current = logText.text?.toString().orEmpty()
        val newText = if (current.isBlank()) line else "$current\n$line"
        logText.text = newText.takeLast(20_000)
    }

    override fun onDestroy() {
        if (::autoShiftEngine.isInitialized) autoShiftEngine.setEnabled(false)
        if (::powerTargetEngine.isInitialized) powerTargetEngine.setEnabled(false)
        if (::obcServer.isInitialized) obcServer.stop()
        if (::scanner.isInitialized) scanner.stop()
        if (::cadenceClient.isInitialized) cadenceClient.close()
        if (::trainerCadenceClient.isInitialized) trainerCadenceClient.close()
        if (::footpodServer.isInitialized) footpodServer.stop()
        super.onDestroy()
    }
}
