package com.tbread.keyboard

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.Executors
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

    @Volatile
    private var captureActive = false

    @Volatile
    private var capturePending: String? = null

    @Volatile
    private var captureCallback: ((String) -> Unit)? = null
    private val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "keybind-dispatcher").apply { isDaemon = true }
    }

    fun start() {
        try {
            Logger.getLogger(GlobalScreen::class.java.packageName).level = Level.OFF
            Logger.getLogger(GlobalScreen::class.java.packageName).useParentHandlers = false
            GlobalScreen.setEventDispatcher(eventExecutor)
            if (!GlobalScreen.isNativeHookRegistered()) {
                GlobalScreen.registerNativeHook()
            }
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
        } finally {
            eventExecutor.shutdownNow()
        }
    }

    fun updateKeybind(value: String?) {
        val next = parseKeybind(value ?: "")
        if (next.keyCode == NativeKeyEvent.VC_UNDEFINED) {
            return
        }
        keybind = next
    }

    fun beginCapture(onCaptured: (String) -> Unit): Boolean {
        captureActive = true
        capturePending = null
        captureCallback = onCaptured
        return true
    }

    fun cancelCapture() {
        captureActive = false
        capturePending = null
        captureCallback = null
    }

    override fun nativeKeyPressed(nativeEvent: NativeKeyEvent) {
        if (captureActive) {
            val combo = buildCombo(nativeEvent)
            if (combo.isNotBlank()) {
                capturePending = combo
            }
            return
        }
        val current = keybind
        if (nativeEvent.keyCode != current.keyCode) return
        val mods = nativeEvent.modifiers
        val ctrlPressed = (mods and NativeKeyEvent.CTRL_L_MASK != 0) ||
            (mods and NativeKeyEvent.CTRL_R_MASK != 0) ||
            (mods and NativeKeyEvent.CTRL_MASK != 0)
        val altPressed = (mods and NativeKeyEvent.ALT_L_MASK != 0) ||
            (mods and NativeKeyEvent.ALT_R_MASK != 0) ||
            (mods and NativeKeyEvent.ALT_MASK != 0)
        val shiftPressed = (mods and NativeKeyEvent.SHIFT_L_MASK != 0) ||
            (mods and NativeKeyEvent.SHIFT_R_MASK != 0) ||
            (mods and NativeKeyEvent.SHIFT_MASK != 0)
        val metaPressed = (mods and NativeKeyEvent.META_L_MASK != 0) ||
            (mods and NativeKeyEvent.META_R_MASK != 0) ||
            (mods and NativeKeyEvent.META_MASK != 0)
        if (current.ctrl && !ctrlPressed) return
        if (current.alt && !altPressed) return
        if (current.shift && !shiftPressed) return
        if (current.meta && !metaPressed) return
        onTrigger()
    }

    override fun nativeKeyReleased(nativeEvent: NativeKeyEvent) {
        if (!captureActive) return
        val keyText = NativeKeyEvent.getKeyText(nativeEvent.keyCode).orEmpty()
        if (keyText.isBlank()) return
        if (isModifierKey(nativeEvent.keyCode)) return
        val captured = capturePending
        if (!captured.isNullOrBlank()) {
            captureCallback?.invoke(captured)
        }
        cancelCapture()
    }
    override fun nativeKeyTyped(nativeEvent: NativeKeyEvent) = Unit

    companion object {
        private fun isModifierKey(keyCode: Int): Boolean {
            return when (keyCode) {
                NativeKeyEvent.VC_CONTROL,
                NativeKeyEvent.VC_SHIFT,
                NativeKeyEvent.VC_ALT,
                NativeKeyEvent.VC_META -> true
                else -> false
            }
        }

        private fun buildCombo(event: NativeKeyEvent): String {
            val keyText = NativeKeyEvent.getKeyText(event.keyCode).orEmpty()
            if (keyText.isBlank()) return ""
            if (isModifierKey(event.keyCode)) return ""
            val mods = event.modifiers
            val ctrlPressed = (mods and NativeKeyEvent.CTRL_L_MASK != 0) ||
                (mods and NativeKeyEvent.CTRL_R_MASK != 0) ||
                (mods and NativeKeyEvent.CTRL_MASK != 0)
            val altPressed = (mods and NativeKeyEvent.ALT_L_MASK != 0) ||
                (mods and NativeKeyEvent.ALT_R_MASK != 0) ||
                (mods and NativeKeyEvent.ALT_MASK != 0)
            val shiftPressed = (mods and NativeKeyEvent.SHIFT_L_MASK != 0) ||
                (mods and NativeKeyEvent.SHIFT_R_MASK != 0) ||
                (mods and NativeKeyEvent.SHIFT_MASK != 0)
            val metaPressed = (mods and NativeKeyEvent.META_L_MASK != 0) ||
                (mods and NativeKeyEvent.META_R_MASK != 0) ||
                (mods and NativeKeyEvent.META_MASK != 0)
            if (!ctrlPressed && !altPressed && !metaPressed) return ""
            val parts = mutableListOf<String>()
            if (ctrlPressed) parts.add("Ctrl")
            if (altPressed) parts.add("Alt")
            if (shiftPressed) parts.add("Shift")
            if (metaPressed) parts.add("Meta")
            parts.add(keyText.uppercase(Locale.getDefault()))
            return parts.joinToString("+")
        }

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
