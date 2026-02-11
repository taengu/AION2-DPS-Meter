package com.tbread

import com.tbread.config.PcapCapturerConfig
import com.tbread.logging.UnifiedLogger
import com.tbread.packet.CaptureDispatcher
import com.tbread.packet.CapturedPayload
import com.tbread.packet.PcapCapturer
import com.tbread.profiling.MemoryProfiler
import com.tbread.webview.BrowserApp
import com.tbread.windows.WindowTitleDetector
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("Main")

fun main(args: Array<String>) {
    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val memoryProfilerConfig = MemoryProfiler.fromArgs(args)

    ensureAdminOnWindows()

    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        logger.error("Critical Error in thread {}: {}", t.name, e.message, e)
        UnifiedLogger.crash("Uncaught exception in thread ${t.name}", e)
    }

    val channel = Channel<CapturedPayload>(Channel.UNLIMITED)
    val config = PcapCapturerConfig.loadFromProperties()
    val dataStorage = DataStorage()
    val calculator = DpsCalculator(dataStorage)
    val capturer = PcapCapturer(config, channel)
    val dispatcher = CaptureDispatcher(channel, dataStorage)

    val uiReady = CompletableDeferred<Unit>()
    val browserApp = BrowserApp(calculator, dispatcher) {
        if (!uiReady.isCompleted) {
            uiReady.complete(Unit)
        }
    }

    MemoryProfiler.start(appScope, memoryProfilerConfig)

    appScope.launch {
        try {
            dispatcher.run()
        } catch (e: Exception) {
            UnifiedLogger.crash("Capture dispatcher stopped unexpectedly", e)
            throw e
        }
    }

    appScope.launch(Dispatchers.IO) {
        uiReady.await()
        var running = false
        while (true) {
            val detected = WindowTitleDetector.findAion2WindowTitle() != null
            if (detected != running) {
                running = detected
                if (running) capturer.start() else capturer.stop()
            }
            delay(if (running) 60_000L else 10_000L)
        }
    }

    browserApp.start()
}

private fun ensureAdminOnWindows() {
    val osName = System.getProperty("os.name") ?: return
    if (!osName.startsWith("Windows", ignoreCase = true)) return
    if (isProcessElevated()) return

    val currentProcess = ProcessHandle.current()
    val command = currentProcess.info().command().orElse(null) ?: return

    val args = currentProcess.info().arguments().orElse(emptyArray())
    val parameters = args.joinToString(" ") { "\"$it\"" }

    logger.info("Requesting Admin Privileges...")
    Shell32.INSTANCE.ShellExecute(
        null,
        "runas",
        command,
        parameters.ifBlank { null },
        null,
        WinUser.SW_SHOWNORMAL
    )
    exitProcess(0)
}

private fun isProcessElevated(): Boolean {
    val token = WinNT.HANDLEByReference()
    val process = Kernel32.INSTANCE.GetCurrentProcess()
    if (!Advapi32.INSTANCE.OpenProcessToken(process, WinNT.TOKEN_QUERY, token)) {
        return false
    }
    return try {
        val elevation = WinNT.TOKEN_ELEVATION()
        val size = IntByReference()
        val ok = Advapi32.INSTANCE.GetTokenInformation(
            token.value,
            WinNT.TOKEN_INFORMATION_CLASS.TokenElevation,
            elevation,
            elevation.size(),
            size
        )
        ok && elevation.TokenIsElevated != 0
    } finally {
        Kernel32.INSTANCE.CloseHandle(token.value)
    }
}
