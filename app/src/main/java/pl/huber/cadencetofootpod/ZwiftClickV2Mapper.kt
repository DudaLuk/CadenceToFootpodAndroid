package pl.huber.cadencetofootpod

import android.content.Context

/**
 * Mapowanie przycisków prawego Zwift Click V2 na akcje zmiany biegu.
 *
 * Domyślnie zachowujemy układ używany przez tryb right-side-only:
 *   + -> bieg w górę
 *   B -> bieg w dół
 */
class ZwiftClickV2Mapper(context: Context) {

    sealed class HandleResult {
        data class Learned(
            val action: ControllerKeyMapper.Action,
            val button: ZwiftClickV2Controller.Button
        ) : HandleResult()

        data class Triggered(
            val action: ControllerKeyMapper.Action,
            val button: ZwiftClickV2Controller.Button
        ) : HandleResult()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var learningAction: ControllerKeyMapper.Action? = null

    init {
        ensureDefaults()
    }

    fun beginLearning(action: ControllerKeyMapper.Action) {
        learningAction = action
    }

    fun cancelLearning() {
        learningAction = null
    }

    fun resetDefaults() {
        learningAction = null
        prefs.edit()
            .putString(KEY_UP, ZwiftClickV2Controller.Button.PLUS.name)
            .putString(KEY_DOWN, ZwiftClickV2Controller.Button.B.name)
            .apply()
    }

    fun getMapping(action: ControllerKeyMapper.Action): ZwiftClickV2Controller.Button? {
        val key = if (action == ControllerKeyMapper.Action.SHIFT_UP) KEY_UP else KEY_DOWN
        val default = if (action == ControllerKeyMapper.Action.SHIFT_UP) {
            ZwiftClickV2Controller.Button.PLUS
        } else {
            ZwiftClickV2Controller.Button.B
        }
        val stored = prefs.getString(key, default.name) ?: return default
        if (stored == NONE) return null
        return ZwiftClickV2Controller.Button.entries.firstOrNull { it.name == stored } ?: default
    }

    fun handle(button: ZwiftClickV2Controller.Button): HandleResult? {
        val learning = learningAction
        if (learning != null) {
            saveMapping(learning, button)
            removeDuplicateFromOtherAction(learning, button)
            learningAction = null
            return HandleResult.Learned(learning, button)
        }

        for (action in ControllerKeyMapper.Action.entries) {
            if (getMapping(action) == button) {
                return HandleResult.Triggered(action, button)
            }
        }
        return null
    }

    private fun saveMapping(action: ControllerKeyMapper.Action, button: ZwiftClickV2Controller.Button) {
        val key = if (action == ControllerKeyMapper.Action.SHIFT_UP) KEY_UP else KEY_DOWN
        prefs.edit().putString(key, button.name).apply()
    }

    private fun removeDuplicateFromOtherAction(
        action: ControllerKeyMapper.Action,
        button: ZwiftClickV2Controller.Button
    ) {
        val other = if (action == ControllerKeyMapper.Action.SHIFT_UP) {
            ControllerKeyMapper.Action.SHIFT_DOWN
        } else {
            ControllerKeyMapper.Action.SHIFT_UP
        }
        if (getMapping(other) != button) return
        val otherKey = if (other == ControllerKeyMapper.Action.SHIFT_UP) KEY_UP else KEY_DOWN
        prefs.edit().putString(otherKey, NONE).apply()
    }

    private fun ensureDefaults() {
        if (!prefs.contains(KEY_UP) || !prefs.contains(KEY_DOWN)) {
            val edit = prefs.edit()
            if (!prefs.contains(KEY_UP)) edit.putString(KEY_UP, ZwiftClickV2Controller.Button.PLUS.name)
            if (!prefs.contains(KEY_DOWN)) edit.putString(KEY_DOWN, ZwiftClickV2Controller.Button.B.name)
            edit.apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "zwift_click_v2_mapping"
        private const val KEY_UP = "shift_up_button"
        private const val KEY_DOWN = "shift_down_button"
        private const val NONE = "__NONE__"
    }
}
