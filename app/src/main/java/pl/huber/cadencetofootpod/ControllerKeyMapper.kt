package pl.huber.cadencetofootpod

import android.content.Context
import android.view.KeyEvent

/**
 * Zapamiętuje mapowanie przycisków sprzętowego kontrolera/pilota HID na akcje aplikacji.
 *
 * Android sam obsługuje parowanie urządzenia Bluetooth HID. Aplikacja przechwytuje później
 * zwykłe KeyEvent i wiąże przycisk z konkretnym urządzeniem przez InputDevice.descriptor.
 */
class ControllerKeyMapper(context: Context) {

    enum class Action {
        SHIFT_UP,
        SHIFT_DOWN
    }

    data class Mapping(
        val keyCode: Int,
        val deviceDescriptor: String?,
        val deviceName: String?
    ) {
        val keyLabel: String
            get() = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

        fun displayLabel(): String {
            val source = deviceName?.takeIf { it.isNotBlank() } ?: "kontroler"
            return "$keyLabel • $source"
        }

        fun matches(event: KeyEvent): Boolean {
            if (event.keyCode != keyCode) return false
            val expectedDescriptor = deviceDescriptor?.takeIf { it.isNotBlank() } ?: return true
            return event.device?.descriptor == expectedDescriptor
        }
    }

    sealed class HandleResult {
        data class Learned(val action: Action, val mapping: Mapping) : HandleResult()
        data class Triggered(val action: Action, val mapping: Mapping) : HandleResult()
        data object Consumed : HandleResult()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var learningAction: Action? = null

    fun beginLearning(action: Action) {
        learningAction = action
    }

    fun cancelLearning() {
        learningAction = null
    }

    fun clearMappings() {
        learningAction = null
        prefs.edit()
            .remove(KEY_UP_CODE)
            .remove(KEY_UP_DESCRIPTOR)
            .remove(KEY_UP_NAME)
            .remove(KEY_DOWN_CODE)
            .remove(KEY_DOWN_DESCRIPTOR)
            .remove(KEY_DOWN_NAME)
            .apply()
    }

    fun getMapping(action: Action): Mapping? {
        val codeKey = if (action == Action.SHIFT_UP) KEY_UP_CODE else KEY_DOWN_CODE
        if (!prefs.contains(codeKey)) return null

        val descriptorKey = if (action == Action.SHIFT_UP) KEY_UP_DESCRIPTOR else KEY_DOWN_DESCRIPTOR
        val nameKey = if (action == Action.SHIFT_UP) KEY_UP_NAME else KEY_DOWN_NAME

        return Mapping(
            keyCode = prefs.getInt(codeKey, KeyEvent.KEYCODE_UNKNOWN),
            deviceDescriptor = prefs.getString(descriptorKey, null),
            deviceName = prefs.getString(nameKey, null)
        ).takeIf { it.keyCode != KeyEvent.KEYCODE_UNKNOWN }
    }

    fun handle(event: KeyEvent): HandleResult? {
        val learning = learningAction
        if (learning != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN || isSystemNavigationKey(event.keyCode)) return null

            val mapping = Mapping(
                keyCode = event.keyCode,
                deviceDescriptor = event.device?.descriptor,
                deviceName = event.device?.name
            )
            saveMapping(learning, mapping)
            removeDuplicateFromOtherAction(learning, mapping)
            learningAction = null
            return HandleResult.Learned(learning, mapping)
        }

        for (action in Action.entries) {
            val mapping = getMapping(action) ?: continue
            if (!mapping.matches(event)) continue

            return if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                HandleResult.Triggered(action, mapping)
            } else {
                // Przejmujemy również ACTION_UP i powtórzenia, żeby np. przycisk głośności
                // nie wykonał jednocześnie swojej systemowej funkcji po użyciu jako zmiana biegu.
                HandleResult.Consumed
            }
        }

        return null
    }

    private fun isSystemNavigationKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
            keyCode == KeyEvent.KEYCODE_POWER

    private fun saveMapping(action: Action, mapping: Mapping) {
        val codeKey = if (action == Action.SHIFT_UP) KEY_UP_CODE else KEY_DOWN_CODE
        val descriptorKey = if (action == Action.SHIFT_UP) KEY_UP_DESCRIPTOR else KEY_DOWN_DESCRIPTOR
        val nameKey = if (action == Action.SHIFT_UP) KEY_UP_NAME else KEY_DOWN_NAME

        prefs.edit().apply {
            putInt(codeKey, mapping.keyCode)
            if (mapping.deviceDescriptor.isNullOrBlank()) remove(descriptorKey)
            else putString(descriptorKey, mapping.deviceDescriptor)
            if (mapping.deviceName.isNullOrBlank()) remove(nameKey)
            else putString(nameKey, mapping.deviceName)
        }.apply()
    }

    private fun removeDuplicateFromOtherAction(action: Action, mapping: Mapping) {
        val otherAction = if (action == Action.SHIFT_UP) Action.SHIFT_DOWN else Action.SHIFT_UP
        val other = getMapping(otherAction) ?: return
        if (other.keyCode != mapping.keyCode) return
        if (other.deviceDescriptor != mapping.deviceDescriptor) return

        val codeKey = if (otherAction == Action.SHIFT_UP) KEY_UP_CODE else KEY_DOWN_CODE
        val descriptorKey = if (otherAction == Action.SHIFT_UP) KEY_UP_DESCRIPTOR else KEY_DOWN_DESCRIPTOR
        val nameKey = if (otherAction == Action.SHIFT_UP) KEY_UP_NAME else KEY_DOWN_NAME
        prefs.edit()
            .remove(codeKey)
            .remove(descriptorKey)
            .remove(nameKey)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "controller_key_mapping"
        private const val KEY_UP_CODE = "shift_up_code"
        private const val KEY_UP_DESCRIPTOR = "shift_up_descriptor"
        private const val KEY_UP_NAME = "shift_up_name"
        private const val KEY_DOWN_CODE = "shift_down_code"
        private const val KEY_DOWN_DESCRIPTOR = "shift_down_descriptor"
        private const val KEY_DOWN_NAME = "shift_down_name"
    }
}
