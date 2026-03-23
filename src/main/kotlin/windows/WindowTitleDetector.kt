package com.tbread.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Psapi
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import org.slf4j.LoggerFactory

data class Aion2Window(
    val title: String,
    val hwnd: WinDef.HWND
)

object WindowTitleDetector {

    private val logger = LoggerFactory.getLogger(WindowTitleDetector::class.java)
    private const val AION2_PREFIX = "AION2"
    private const val AION2_PROCESS = "Aion2.exe"
    @Volatile
    private var lastLogMessage: String? = null

    fun findAion2WindowTitle(): String? = findAion2Window()?.title

    /**
     * Find the AION2 game window, returning its title and HWND.
     */
    fun findAion2Window(): Aion2Window? {
        if (!isWindows()) return null
        return try {
            var result: Aion2Window? = null
            val callback = WinUser.WNDENUMPROC { hwnd, _ ->
                if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                    return@WNDENUMPROC true
                }
                val processName = getProcessName(hwnd)
                if (!processName.equals(AION2_PROCESS, ignoreCase = true)) {
                    return@WNDENUMPROC true
                }
                val titleLength = User32.INSTANCE.GetWindowTextLength(hwnd)
                if (titleLength <= 0) {
                    return@WNDENUMPROC true
                }
                val buffer = CharArray(titleLength + 1)
                User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
                val title = Native.toString(buffer).trim()
                if (title.startsWith(AION2_PREFIX)) {
                    result = Aion2Window(title, hwnd)
                    return@WNDENUMPROC false
                }
                true
            }
            User32.INSTANCE.EnumWindows(callback, Pointer.NULL)
            val found = result
            val message = if (found != null) {
                "Detected AION2 window title: ${found.title}"
            } else {
                "AION2 window title not found."
            }
            if (message != lastLogMessage) {
                logger.info(message)
                lastLogMessage = message
            }
            result
        } catch (e: Exception) {
            logger.debug("Failed to detect AION2 window title", e)
            null
        }
    }

    /**
     * Check if the given HWND is minimized by inspecting the WS_MINIMIZE style bit.
     */
    fun isMinimized(hwnd: WinDef.HWND): Boolean {
        val style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        return (style and WinUser.WS_MINIMIZE) != 0
    }

    /**
     * Check whether the foreground window belongs to the AION2 process.
     */
    fun isAion2Foreground(): Boolean {
        val fgHwnd = User32.INSTANCE.GetForegroundWindow() ?: return false
        val processName = getProcessName(fgHwnd) ?: return false
        return processName.equals(AION2_PROCESS, ignoreCase = true)
    }

    /**
     * Get the window rectangle for the given HWND.
     * Returns [left, top, right, bottom] or null on failure.
     */
    fun getWindowRect(hwnd: WinDef.HWND): WinDef.RECT? {
        val rect = WinDef.RECT()
        return if (User32.INSTANCE.GetWindowRect(hwnd, rect)) rect else null
    }

    private fun getProcessName(hwnd: WinDef.HWND): String? {
        val processId = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, processId)
        var handle: WinNT.HANDLE? = null
        return try {
            handle = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_LIMITED_INFORMATION,
                false,
                processId.value
            )
            val limitedName = handle?.let { queryProcessImageName(it) }
            if (limitedName != null) {
                return limitedName.substringAfterLast('\\')
            }
            handle?.let { Kernel32.INSTANCE.CloseHandle(it) }
            handle = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_INFORMATION or WinNT.PROCESS_VM_READ,
                false,
                processId.value
            )
            val buffer = CharArray(WinDef.MAX_PATH)
            val length = handle?.let { Psapi.INSTANCE.GetModuleFileNameExW(it, null, buffer, buffer.size) }
                ?: return null
            if (length <= 0) return null
            val fullPath = String(buffer, 0, length)
            fullPath.substringAfterLast('\\')
        } catch (e: Exception) {
            null
        } finally {
            handle?.let { Kernel32.INSTANCE.CloseHandle(it) }
        }
    }

    private fun queryProcessImageName(handle: WinNT.HANDLE): String? {
        val size = IntByReference(WinDef.MAX_PATH)
        val buffer = CharArray(WinDef.MAX_PATH)
        val success = Kernel32.INSTANCE.QueryFullProcessImageName(handle, 0, buffer, size)
        if (!success || size.value <= 0) return null
        return String(buffer, 0, size.value)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    }
}
