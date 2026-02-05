package com.tbread.keyboard

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

class RefreshKeybindManager(
    private val onTrigger: () -> Unit,
    defaultKeybind: String = "CTRL+R"
) : NativeKeyListener {
    private val logger = LoggerFactory.getLogger(RefreshKeybindManager::class.java)

    data class Keybind(
        val keyCode: Int,
        val ctrl: Boolean,
        val alt: Boolean,
        val shift: Boolean,
        val meta: Boolean,
        val display: String
    )

    @Volatile
    private var keybind: Keybind = parseKeybind(defaultKeybind)

    fun start() {
        try {
            Logger.getLogger(GlobalScreen::class.java.packageName).level = Level.OFF
            Logger.getLogger(GlobalScreen::class.java.packageName).useParentHandlers = false
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
        } catch (e: NativeHookException) {
            logger.warn("Failed to register global keybind hook", e)
        }
    }

    fun stop() {
        try {
            GlobalScreen.removeNativeKeyListener(this)
            GlobalScreen.unregisterNativeHook()
        } catch (e: NativeHookException) {
            logger.warn("Failed to unregister global keybind hook", e)
        }
    }

    fun updateKeybind(value: String?) {
        val next = parseKeybind(value ?: "")
        if (next.keyCode == NativeKeyEvent.VC_UNDEFINED) {
            return
        }
        keybind = next
    }

    override fun nativeKeyPressed(nativeEvent: NativeKeyEvent) {
        val current = keybind
        if (nativeEvent.keyCode != current.keyCode) return
        if (current.ctrl && !nativeEvent.isControlDown) return
        if (current.alt && !nativeEvent.isAltDown) return
        if (current.shift && !nativeEvent.isShiftDown) return
        if (current.meta && !nativeEvent.isMetaDown) return
        onTrigger()
    }

    override fun nativeKeyReleased(nativeEvent: NativeKeyEvent) = Unit
    override fun nativeKeyTyped(nativeEvent: NativeKeyEvent) = Unit

    companion object {
        fun parseKeybind(raw: String): Keybind {
            val cleaned = raw.replace("\\s+".toRegex(), "").uppercase(Locale.getDefault())
            if (cleaned.isBlank()) {
                return Keybind(NativeKeyEvent.VC_UNDEFINED, false, false, false, false, "")
            }
            val parts = cleaned.split("+").filter { it.isNotBlank() }
            var ctrl = false
            var alt = false
            var shift = false
            var meta = false
            var key = ""
            parts.forEach { part ->
                when (part) {
                    "CTRL", "CONTROL" -> ctrl = true
                    "ALT" -> alt = true
                    "SHIFT" -> shift = true
                    "META", "CMD", "WIN" -> meta = true
                    else -> key = part
                }
            }
            val keyCode = keyToKeyCode(key)
            val displayParts = mutableListOf<String>()
            if (ctrl) displayParts.add("Ctrl")
            if (alt) displayParts.add("Alt")
            if (shift) displayParts.add("Shift")
            if (meta) displayParts.add("Meta")
            if (key.isNotBlank()) {
                displayParts.add(if (key.length == 1) key.uppercase(Locale.getDefault()) else key)
            }
            return Keybind(
                keyCode = keyCode,
                ctrl = ctrl,
                alt = alt,
                shift = shift,
                meta = meta,
                display = displayParts.joinToString("+")
            )
        }

        private fun keyToKeyCode(key: String): Int {
            if (key.isBlank()) return NativeKeyEvent.VC_UNDEFINED
            val normalized = key.uppercase(Locale.getDefault())
            return try {
                val fieldName = "VC_${normalized}"
                NativeKeyEvent::class.java.getField(fieldName).getInt(null)
            } catch (e: Exception) {
                NativeKeyEvent.VC_UNDEFINED
            }
        }
    }
}
