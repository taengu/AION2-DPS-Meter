package com.tbread

import com.tbread.config.PcapCapturerConfig
import com.tbread.packet.*
import com.tbread.webview.BrowserApp
import com.tbread.windows.WindowTitleDetector
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import javafx.application.Application
import javafx.application.Platform
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

// This class handles the JavaFX lifecycle properly for Native Images
class AionMeterApp : Application() {
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun start(primaryStage: Stage) {
        // We initialize the logic inside start() to ensure the toolkit is ready
        val channel = Channel<CapturedPayload>(Channel.UNLIMITED)
        val config = PcapCapturerConfig.loadFromProperties()
        val dataStorage = DataStorage()
        val calculator = DpsCalculator(dataStorage)
        val capturer = PcapCapturer(config, channel)
        val dispatcher = CaptureDispatcher(channel, dataStorage)
        val iconStream = javaClass.getResourceAsStream("/resources/icon.ico")
        if (iconStream != null) {
            primaryStage.icons.add(javafx.scene.image.Image(iconStream))
        }

        // Launch background tasks
        appScope.launch {
            dispatcher.run()
        }

        appScope.launch(Dispatchers.IO) {
            var running = false
            while (true) {
                val detected = WindowTitleDetector.findAion2WindowTitle() != null
                if (detected != running) {
                    running = detected
                    if (running) {
                        capturer.start()
                    } else {
                        capturer.stop()
                    }
                }
                val delayMs = if (running) 60_000L else 10_000L
                delay(delayMs)
            }
        }

        // Initialize and show the browser
        val browserApp = BrowserApp(calculator)
        browserApp.start(primaryStage)

        // Ensure the window actually paints
        primaryStage.show()
        primaryStage.toFront()
    }

    override fun stop() {
        // Cleanup when the window is closed
        exitProcess(0)
    }
}

fun main(args: Array<String>) {
    // 1. Check Admin
    ensureAdminOnWindows()

    // 2. Setup Logging/Errors
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        println("Critical Error in thread ${t.name}: ${e.message}")
        e.printStackTrace()
    }

    println("Starting Native Aion2 Meter...")
    println("Java: ${System.getProperty("java.version")} | Path: ${System.getProperty("java.home")}")

    // 3. Launch the Application
    // This blocks the main thread until the window is closed
    Application.launch(AionMeterApp::class.java, *args)
}

private fun ensureAdminOnWindows() {
    val osName = System.getProperty("os.name") ?: return
    if (!osName.startsWith("Windows", ignoreCase = true)) return
    if (isProcessElevated()) return

    val currentProcess = ProcessHandle.current()
    val command = currentProcess.info().command().orElse(null) ?: return

    // If running as a native .exe, it won't end in java.exe
    val commandLower = command.lowercase()
    if (commandLower.endsWith("java.exe") || commandLower.endsWith("javaw.exe")) return

    val args = currentProcess.info().arguments().orElse(emptyArray())
    val parameters = args.joinToString(" ") { "\"$it\"" }

    println("Requesting Admin Privileges...")
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
