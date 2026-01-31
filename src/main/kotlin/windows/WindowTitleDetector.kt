package com.tbread.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import org.slf4j.LoggerFactory

object WindowTitleDetector {

    private val logger = LoggerFactory.getLogger(WindowTitleDetector::class.java)
    private const val AION2_PREFIX = "AION2"

    fun findAion2WindowTitle(): String? {
        if (!isWindows()) return null
        return try {
            var result: String? = null
            val callback = WinUser.WNDENUMPROC { hwnd, _ ->
                if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
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

    private fun isWindows(): Boolean {
        return System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    }
}
