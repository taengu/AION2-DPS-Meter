package com.tbread.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Psapi
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import org.slf4j.LoggerFactory

object WindowTitleDetector {

    private val logger = LoggerFactory.getLogger(WindowTitleDetector::class.java)
    private const val AION2_PREFIX = "AION2"
    private const val AION2_PROCESS = "Aion2.exe"

    fun findAion2WindowTitle(): String? {
        if (!isWindows()) return null
        return try {
            var result: String? = null
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
                    result = title
                    return@WNDENUMPROC false
                }
                true
            }
            User32.INSTANCE.EnumWindows(callback, Pointer.NULL)
            result
        } catch (e: Exception) {
            logger.debug("Failed to detect AION2 window title", e)
            null
        }
    }

    private fun getProcessName(hwnd: WinUser.HWND): String? {
        val processId = IntArray(1)
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, processId)
        val handle = Kernel32.INSTANCE.OpenProcess(
            WinNT.PROCESS_QUERY_INFORMATION or WinNT.PROCESS_VM_READ,
            false,
            processId[0]
        ) ?: return null
        return try {
            val buffer = CharArray(WinDef.MAX_PATH)
            val length = Psapi.INSTANCE.GetModuleBaseName(handle, null, buffer, buffer.size)
            if (length > 0) {
                String(buffer, 0, length)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    }
}
