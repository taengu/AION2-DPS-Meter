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

class KeyHookEvent(private val engine: WebEngine) {
    private val logger = LoggerFactory.getLogger(KeyHookEvent::class.java)

    private val hotkeyId = 1
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
    private var isAion2ForegroundCached = false

    @Volatile
    private var registeredHotkeyMods = 0

    @Volatile
    private var registeredHotkeyKey = 0

    @Volatile
    private var hotkeyThread: Thread? = null

    @Volatile
    private var hotkeyRunning = false

    init {
        runCatching {
            lastHotkeyMods = PropertyHandler.getProperty(HOTKEY_MODS_KEY)?.toIntOrNull() ?: DEFAULT_MODS
            lastHotkeyKey = PropertyHandler.getProperty(HOTKEY_KEY_KEY)?.toIntOrNull() ?: DEFAULT_KEY_CODE
            updateHotkeyRegistration(true)
        }.onFailure { error ->
            logger.warn("Failed to initialize hotkey registration", error)
        }
    }

    fun setHotkey(modifiers: Int, keyCode: Int) {
        lastHotkeyMods = modifiers
        lastHotkeyKey = keyCode
        PropertyHandler.setProperty(HOTKEY_MODS_KEY, modifiers.toString())
        PropertyHandler.setProperty(HOTKEY_KEY_KEY, keyCode.toString())
        updateHotkeyRegistration(true)
    }

    fun getCurrentHotKey(): String {
        val mods = registeredHotkeyMods
        val key = registeredHotkeyKey
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

    private fun startHotkeyThread(modifiers: Int, keyCode: Int) {
        stopHotkeyThread()
        hotkeyRunning = true
        hotkeyThread = Thread {
            val registeredMods = modifiers or WinUser.MOD_NOREPEAT
            val registered = User32.INSTANCE.RegisterHotKey(null, hotkeyId, registeredMods, keyCode)
            if (!registered) {
                val err = Kernel32.INSTANCE.GetLastError()
                logger.warn("RegisterHotKey failed mods={} vk={} err={}", registeredMods, keyCode, err)
            } else {
                logger.info("RegisterHotKey registered mods={} vk={}", registeredMods, keyCode)
            }

            val msg = WinUser.MSG()
            while (hotkeyRunning) {
                try {
                    while (User32.INSTANCE.PeekMessage(msg, null, 0, 0, pmRemoveFlag)) {
                        if (msg.message != WinUser.WM_HOTKEY) {
                            continue
                        }
                        if (msg.wParam.toInt() != hotkeyId || !matchesRegisteredHotkey(msg.lParam.toLong())) {
                            continue
                        }
                        if (!isRegisteredComboStillPressed(registeredHotkeyMods, registeredHotkeyKey)) {
                            continue
                        }
                        val foreground = User32.INSTANCE.GetForegroundWindow()
                        if (foreground == null || !isAion2Window(foreground)) {
                            isAion2ForegroundCached = false
                            continue
                        }
                        isAion2ForegroundCached = true
                        dispatchResetHotKey()
                    }
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (error: Exception) {
                    logger.debug("Hotkey loop error", error)
                }
            }

            User32.INSTANCE.UnregisterHotKey(null, hotkeyId)
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
    }

    @Synchronized
    private fun updateHotkeyRegistration(shouldRegister: Boolean) {
        if (!System.getProperty("os.name").lowercase().contains("windows")) {
            logger.info("Global hotkeys are only supported on Windows.")
            stopHotkeyThread()
            return
        }
        if (!shouldRegister) {
            stopHotkeyThread()
            return
        }
        if (lastHotkeyMods == 0 && lastHotkeyKey == 0) {
            stopHotkeyThread()
            return
        }
        if (hotkeyRunning &&
            lastHotkeyMods == registeredHotkeyMods &&
            lastHotkeyKey == registeredHotkeyKey
        ) {
            return
        }

        startHotkeyThread(lastHotkeyMods, lastHotkeyKey)
        registeredHotkeyMods = lastHotkeyMods
        registeredHotkeyKey = lastHotkeyKey
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

    private fun matchesRegisteredHotkey(lParam: Long): Boolean {
        val messageMods = (lParam and 0xFFFF).toInt()
        val messageKey = ((lParam ushr 16) and 0xFFFF).toInt()
        return messageMods == registeredHotkeyMods && messageKey == registeredHotkeyKey
    }


    private fun isRegisteredComboStillPressed(modifiers: Int, keyCode: Int): Boolean {
        if (keyCode <= 0) {
            return false
        }

        if ((modifiers and WinUser.MOD_CONTROL) != 0 && !isVirtualKeyDown(WinUser.VK_CONTROL)) return false
        if ((modifiers and WinUser.MOD_ALT) != 0 && !isVirtualKeyDown(WinUser.VK_MENU)) return false
        if ((modifiers and WinUser.MOD_SHIFT) != 0 && !isVirtualKeyDown(WinUser.VK_SHIFT)) return false
        if ((modifiers and WinUser.MOD_WIN) != 0 && !isVirtualKeyDown(vkLWin) && !isVirtualKeyDown(vkRWin)) return false

        return isVirtualKeyDown(keyCode)
    }

    private fun isVirtualKeyDown(vk: Int): Boolean {
        return (User32.INSTANCE.GetAsyncKeyState(vk).toInt() and 0x8000) != 0
    }

    companion object {
        private const val HOTKEY_MODS_KEY = "dpsMeter.hotkey.modifiers"
        private const val HOTKEY_KEY_KEY = "dpsMeter.hotkey.keyCode"
        private const val DEFAULT_MODS = WinUser.MOD_CONTROL or WinUser.MOD_ALT
        private const val DEFAULT_KEY_CODE = 82 // R
    }
}
