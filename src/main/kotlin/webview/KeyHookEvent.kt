package com.tbread.webview

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Psapi
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.tbread.packet.PropertyHandler
import javafx.application.Platform
import javafx.scene.web.WebEngine
import org.slf4j.LoggerFactory

class KeyHookEvent(
    private val engine: WebEngine,
    private val onToggleWindowHotkey: () -> Unit
) {
    private val logger = LoggerFactory.getLogger(KeyHookEvent::class.java)

    private val resetHotkeyId = 1
    private val toggleWindowHotkeyId = 2
    private val hotkeyTargetProcess = "Aion2.exe"
    private val hotkeyTargetTitle = "Aion2"
    private val pmRemoveFlag = 0x0001
    private val vkLWin = 0x5B
    private val vkRWin = 0x5C

    @Volatile
    private var lastHotkeyMods = 0

    @Volatile
    private var lastHotkeyKey = 0

    @Volatile
    private var lastToggleMods = 0

    @Volatile
    private var lastToggleKey = 0

    @Volatile
    private var isAion2ForegroundCached = false

    @Volatile
    private var registeredHotkeyMods = 0

    @Volatile
    private var registeredHotkeyKey = 0

    @Volatile
    private var registeredToggleMods = 0

    @Volatile
    private var registeredToggleKey = 0

    @Volatile
    private var hotkeyThread: Thread? = null

    @Volatile
    private var hotkeyRunning = false

    init {
        runCatching {
            val storedMods = PropertyHandler.getProperty(HOTKEY_MODS_KEY)?.toIntOrNull()
            val storedKey = PropertyHandler.getProperty(HOTKEY_KEY_KEY)?.toIntOrNull()
            val normalized = normalizeHotkey(storedMods ?: DEFAULT_MODS, storedKey ?: DEFAULT_KEY_CODE)
            lastHotkeyMods = normalized.first
            lastHotkeyKey = normalized.second
            if (storedMods != lastHotkeyMods || storedKey != lastHotkeyKey) {
                PropertyHandler.setProperty(HOTKEY_MODS_KEY, lastHotkeyMods.toString())
                PropertyHandler.setProperty(HOTKEY_KEY_KEY, lastHotkeyKey.toString())
            }

            val storedToggleMods = PropertyHandler.getProperty(TOGGLE_MODS_KEY)?.toIntOrNull()
            val storedToggleKey = PropertyHandler.getProperty(TOGGLE_KEY_KEY)?.toIntOrNull()
            val normalizedToggle = normalizeHotkey(
                storedToggleMods ?: DEFAULT_TOGGLE_MODS,
                storedToggleKey ?: DEFAULT_TOGGLE_KEY_CODE
            )
            lastToggleMods = normalizedToggle.first
            lastToggleKey = normalizedToggle.second
            if (storedToggleMods != lastToggleMods || storedToggleKey != lastToggleKey) {
                PropertyHandler.setProperty(TOGGLE_MODS_KEY, lastToggleMods.toString())
                PropertyHandler.setProperty(TOGGLE_KEY_KEY, lastToggleKey.toString())
            }

            updateHotkeyRegistration()
        }.onFailure { error ->
            logger.warn("Failed to initialize hotkey registration", error)
        }
    }

    fun setHotkey(modifiers: Int, keyCode: Int) {
        val normalized = normalizeHotkey(modifiers, keyCode)
        lastHotkeyMods = normalized.first
        lastHotkeyKey = normalized.second
        PropertyHandler.setProperty(HOTKEY_MODS_KEY, lastHotkeyMods.toString())
        PropertyHandler.setProperty(HOTKEY_KEY_KEY, lastHotkeyKey.toString())
        updateHotkeyRegistration()
    }

    fun setToggleWindowHotkey(modifiers: Int, keyCode: Int) {
        val normalized = normalizeHotkey(modifiers, keyCode)
        lastToggleMods = normalized.first
        lastToggleKey = normalized.second
        PropertyHandler.setProperty(TOGGLE_MODS_KEY, lastToggleMods.toString())
        PropertyHandler.setProperty(TOGGLE_KEY_KEY, lastToggleKey.toString())
        updateHotkeyRegistration()
    }

    fun getCurrentHotKey(): String {
        return formatHotkey(registeredHotkeyMods, registeredHotkeyKey)
    }

    fun getCurrentToggleWindowHotKey(): String {
        return formatHotkey(registeredToggleMods, registeredToggleKey)
    }

    private fun formatHotkey(mods: Int, key: Int): String {
        if (mods == 0 || key == 0) {
            return ""
        }
        val parts = mutableListOf<String>()
        if ((mods and WinUser.MOD_CONTROL) != 0) parts += "Ctrl"
        if ((mods and WinUser.MOD_ALT) != 0) parts += "Alt"
        if ((mods and WinUser.MOD_SHIFT) != 0) parts += "Shift"
        if ((mods and WinUser.MOD_WIN) != 0) parts += "Win"
        parts += java.awt.event.KeyEvent.getKeyText(key)
        return parts.joinToString("+")
    }

    fun stop() {
        stopHotkeyThread()
    }

    fun resetToDefaults() {
        lastHotkeyMods = DEFAULT_MODS
        lastHotkeyKey = DEFAULT_KEY_CODE
        lastToggleMods = DEFAULT_TOGGLE_MODS
        lastToggleKey = DEFAULT_TOGGLE_KEY_CODE
        PropertyHandler.setProperty(HOTKEY_MODS_KEY, lastHotkeyMods.toString())
        PropertyHandler.setProperty(HOTKEY_KEY_KEY, lastHotkeyKey.toString())
        PropertyHandler.setProperty(TOGGLE_MODS_KEY, lastToggleMods.toString())
        PropertyHandler.setProperty(TOGGLE_KEY_KEY, lastToggleKey.toString())
        updateHotkeyRegistration()
    }

    private fun startHotkeyThread(modifiers: Int, keyCode: Int) {
        stopHotkeyThread()
        hotkeyRunning = true
        val toggleMods = lastToggleMods
        val toggleKey = lastToggleKey
        hotkeyThread = Thread {
            val registeredMods = modifiers or WinUser.MOD_NOREPEAT
            val resetRegistered = User32.INSTANCE.RegisterHotKey(null, resetHotkeyId, registeredMods, keyCode)
            if (!resetRegistered) {
                val err = Kernel32.INSTANCE.GetLastError()
                logger.warn("Register reset hotkey failed mods={} vk={} err={}", registeredMods, keyCode, err)
            } else {
                logger.info("Register reset hotkey registered mods={} vk={}", registeredMods, keyCode)
            }

            val toggleModsWithNoRepeat = (toggleMods or WinUser.MOD_NOREPEAT)
            val toggleRegistered = User32.INSTANCE.RegisterHotKey(
                null,
                toggleWindowHotkeyId,
                toggleModsWithNoRepeat,
                toggleKey
            )
            if (!toggleRegistered) {
                val err = Kernel32.INSTANCE.GetLastError()
                logger.warn("Register toggle hotkey failed mods={} vk={} err={}", toggleModsWithNoRepeat, toggleKey, err)
            } else {
                logger.info("Register toggle hotkey registered mods={} vk={}", toggleModsWithNoRepeat, toggleKey)
            }

            val msg = WinUser.MSG()
            while (hotkeyRunning) {
                try {
                    while (User32.INSTANCE.PeekMessage(msg, null, 0, 0, pmRemoveFlag)) {
                        if (msg.message != WinUser.WM_HOTKEY) {
                            continue
                        }
                        val hotkeyId = msg.wParam.toInt()
                        if (hotkeyId == resetHotkeyId) {
                            if (!matchesRegisteredHotkey(msg.lParam.toLong())) {
                                continue
                            }
                            val foreground = User32.INSTANCE.GetForegroundWindow()
                            if (foreground == null || !isAion2Window(foreground)) {
                                isAion2ForegroundCached = false
                                continue
                            }
                            isAion2ForegroundCached = true
                            dispatchResetHotKey()
                            continue
                        }
                        if (hotkeyId == toggleWindowHotkeyId) {
                            if (!matchesToggleWindowHotkey(msg.lParam.toLong())) {
                                continue
                            }
                            dispatchToggleWindowHotKey()
                        }
                    }
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (error: Exception) {
                    logger.debug("Hotkey loop error", error)
                }
            }

            User32.INSTANCE.UnregisterHotKey(null, resetHotkeyId)
            User32.INSTANCE.UnregisterHotKey(null, toggleWindowHotkeyId)
        }.apply {
            isDaemon = true
            name = "hotkey-thread"
            start()
        }
    }

    private fun getWindowTitle(hwnd: WinDef.HWND): String {
        val buffer = CharArray(256)
        val len = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
        return if (len > 0) String(buffer, 0, len) else ""
    }

    private fun isAion2Window(hwnd: WinDef.HWND): Boolean {
        val title = getWindowTitle(hwnd)
        if (title.contains(hotkeyTargetTitle, ignoreCase = true)) {
            return true
        }
        val pidRef = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
        val pidValue = pidRef.value
        if (pidValue <= 0) {
            return false
        }
        val processName = getProcessName(pidValue) ?: return false
        return processName.equals(hotkeyTargetProcess, ignoreCase = true)
    }

    private fun getProcessName(pid: Int): String? {
        val processHandle = Kernel32.INSTANCE.OpenProcess(
            WinNT.PROCESS_QUERY_LIMITED_INFORMATION or WinNT.PROCESS_VM_READ,
            false,
            pid
        ) ?: return null
        return try {
            val buffer = CharArray(260)
            val ok = Psapi.INSTANCE.GetProcessImageFileName(
                processHandle,
                buffer,
                buffer.size
            )
            if (ok > 0) {
                val fullPath = String(buffer, 0, ok)
                fullPath.substringAfterLast('\\', fullPath)
            } else {
                null
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(processHandle)
        }
    }

    private fun stopHotkeyThread() {
        hotkeyRunning = false
        hotkeyThread?.interrupt()
        hotkeyThread = null
        registeredHotkeyMods = 0
        registeredHotkeyKey = 0
        registeredToggleMods = 0
        registeredToggleKey = 0
    }

    @Synchronized
    private fun updateHotkeyRegistration() {
        if (!System.getProperty("os.name").lowercase().contains("windows")) {
            logger.info("Global hotkeys are only supported on Windows.")
            stopHotkeyThread()
            return
        }
        if (lastHotkeyMods == 0 && lastHotkeyKey == 0 && lastToggleMods == 0 && lastToggleKey == 0) {
            stopHotkeyThread()
            return
        }
        if (hotkeyRunning &&
            lastHotkeyMods == registeredHotkeyMods &&
            lastHotkeyKey == registeredHotkeyKey &&
            lastToggleMods == registeredToggleMods &&
            lastToggleKey == registeredToggleKey
        ) {
            return
        }

        startHotkeyThread(lastHotkeyMods, lastHotkeyKey)
        registeredHotkeyMods = lastHotkeyMods
        registeredHotkeyKey = lastHotkeyKey
        registeredToggleMods = lastToggleMods
        registeredToggleKey = lastToggleKey
    }

    private fun dispatchResetHotKey() {
        Platform.runLater {
            runCatching {
                engine.executeScript("window.dispatchEvent(new CustomEvent('nativeResetHotKey'));")
            }.onFailure { error ->
                logger.debug("Failed to dispatch nativeResetHotKey", error)
            }
        }
    }

    private fun dispatchToggleWindowHotKey() {
        Platform.runLater {
            runCatching {
                onToggleWindowHotkey()
            }.onFailure { error ->
                logger.debug("Failed to handle nativeToggleWindowHotKey", error)
            }
        }
    }

    private fun matchesRegisteredHotkey(lParam: Long): Boolean {
        val messageMods = (lParam and 0xFFFF).toInt() and HOTKEY_MODIFIER_MASK
        val messageKey = ((lParam ushr 16) and 0xFFFF).toInt()
        val expectedMods = registeredHotkeyMods and HOTKEY_MODIFIER_MASK
        return messageMods == expectedMods && messageKey == registeredHotkeyKey
    }

    private fun matchesToggleWindowHotkey(lParam: Long): Boolean {
        val messageMods = (lParam and 0xFFFF).toInt() and HOTKEY_MODIFIER_MASK
        val messageKey = ((lParam ushr 16) and 0xFFFF).toInt()
        val expectedMods = registeredToggleMods and HOTKEY_MODIFIER_MASK
        return messageMods == expectedMods && messageKey == registeredToggleKey
    }

    private fun isModifierVirtualKey(vk: Int): Boolean {
        return vk == WinUser.VK_CONTROL ||
            vk == WinUser.VK_MENU ||
            vk == WinUser.VK_SHIFT ||
            vk == vkLWin ||
            vk == vkRWin
    }

    private fun normalizeHotkey(modifiers: Int, keyCode: Int): Pair<Int, Int> {
        val normalizedMods = modifiers and HOTKEY_MODIFIER_MASK
        val normalizedKey = if (isModifierVirtualKey(keyCode) || keyCode <= 0) DEFAULT_KEY_CODE else keyCode
        val modifierCount = Integer.bitCount(normalizedMods)
        val finalMods = if (modifierCount < 1) DEFAULT_MODS else normalizedMods
        return finalMods to normalizedKey
    }

    companion object {
        private const val HOTKEY_MODS_KEY = "dpsMeter.hotkey.modifiers"
        private const val HOTKEY_KEY_KEY = "dpsMeter.hotkey.keyCode"
        private const val TOGGLE_MODS_KEY = "dpsMeter.hotkey.toggle.modifiers"
        private const val TOGGLE_KEY_KEY = "dpsMeter.hotkey.toggle.keyCode"
        private const val DEFAULT_MODS = WinUser.MOD_CONTROL or WinUser.MOD_ALT
        private const val DEFAULT_KEY_CODE = 82 // R
        private const val DEFAULT_TOGGLE_MODS = WinUser.MOD_CONTROL or WinUser.MOD_ALT
        private const val DEFAULT_TOGGLE_KEY_CODE = 0x26 // VK_UP (Up Arrow)
        private const val HOTKEY_MODIFIER_MASK = WinUser.MOD_ALT or WinUser.MOD_CONTROL or WinUser.MOD_SHIFT or WinUser.MOD_WIN
    }
}
