package com.tbread

import com.tbread.config.PcapCapturerConfig
import com.tbread.logging.UnifiedLogger
import com.tbread.packet.*
import com.tbread.profiling.MemoryProfiler
import com.tbread.webview.BrowserApp
import com.tbread.windows.WindowTitleDetector
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import javafx.application.Application
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

// This class handles the JavaFX lifecycle properly for Native Images
private val logger = LoggerFactory.getLogger("Main")

class AionMeterApp : Application() {
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        var memoryProfilerConfig: MemoryProfiler.Config = MemoryProfiler.Config()
    }

    override fun start(primaryStage: Stage) {
        // We initialize the logic inside start() to ensure the toolkit is ready
        val replayLogFile = PropertyHandler.getProperty("capture.replayFilePath")?.trim().orEmpty()
        val replayAccelerate = PropertyHandler.getProperty("capture.replayAccelerate")
            ?.trim()
            ?.equals("true", ignoreCase = true) == true
        val isReplayMode = replayLogFile.isNotBlank()
        val replayNick = PropertyHandler.getProperty("capture.replayNick")?.trim()?.takeIf { it.isNotBlank() }
        val replayId = PropertyHandler.getProperty("capture.replayID")?.trim()?.toLongOrNull()

        if (isReplayMode) {
            if (replayNick != null) {
                LocalPlayer.characterName = replayNick
                logger.info("Replay override: characterName={}", replayNick)
            }
            if (replayId != null && replayId > 0) {
                LocalPlayer.playerId = replayId
                logger.info("Replay override: playerId={}", replayId)
            }
        }

        val channel = Channel<CapturedPayload>(Channel.UNLIMITED)
        val config = PcapCapturerConfig.loadFromProperties()
        val dataStorage = DataStorage()

        // Pre-register replay nick/ID so the meter recognises the local player
        // from the very first damage packet (before nickname parsing finds it).
        // Uses setPermanentNickname so it survives resetNicknameStorage() calls
        // triggered by the UI identity setup during startup.
        if (isReplayMode && replayNick != null && replayId != null && replayId > 0) {
            dataStorage.setPermanentNickname(replayId.toInt(), replayNick)
            logger.info("Replay: pre-registered nickname {} -> {}", replayId, replayNick)
        }

        val calculator = DpsCalculator(dataStorage)
        val capturer = PcapCapturer(config, channel)
        CombatPortDetector.onDeviceLocked = { lockedDevice ->
            capturer.stopOtherDevices(lockedDevice)
        }
        CombatPortDetector.onReset = {
            capturer.restartStoppedDevices()
        }
        CombatPortDetector.onPreferredDeviceChanged = { deviceLabel ->
            if (deviceLabel != null) {
                capturer.ensureDeviceCapturing(deviceLabel)
            } else {
                // Switched back to auto-detect: full restart with loopback priority
                capturer.restart()
            }
        }
        val dispatcher = CaptureDispatcher(channel, dataStorage, isReplayMode)
        val uiReady = CompletableDeferred<Unit>()
        val markUiReady = {
            if (!uiReady.isCompleted) {
                uiReady.complete(Unit)
            }
        }
        val iconStream = javaClass.getResourceAsStream("/resources/icon.ico")
        if (iconStream != null) {
            primaryStage.icons.add(javafx.scene.image.Image(iconStream))
        }

        // Initialize and show the browser
        val browserApp = BrowserApp(calculator, dispatcher, replayNick) { markUiReady() }
        try {
            browserApp.start(primaryStage)
        } catch (e: Exception) {
            UnifiedLogger.crash("Failed to start JavaFX browser window", e)
            throw e
        }

        MemoryProfiler.start(appScope, memoryProfilerConfig)

        // Launch background tasks after UI initialization
        appScope.launch {
            try {
                dispatcher.run()
            } catch (e: Exception) {
                UnifiedLogger.crash("Capture dispatcher stopped unexpectedly", e)
                throw e
            }
        }

        if (isReplayMode) {
            // Offline Replay Mode
            appScope.launch(Dispatchers.IO) {
                uiReady.await()
                val fileCapturer = FilePacketCapturer(replayLogFile, channel, playbackSpeed = 1.0, replayAccelerate = replayAccelerate)
                fileCapturer.start()
            }
        } else {
            // Standard Live PCAP Mode
            appScope.launch(Dispatchers.IO) {
                uiReady.await()
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
        }
    }

    override fun stop() {
        // Cleanup when the window is closed
        exitProcess(0)
    }
}

fun main(args: Array<String>) {
    AionMeterApp.memoryProfilerConfig = MemoryProfiler.fromArgs(args)
    configureJavaFxPipeline()

    // Load debug logging setting from properties before startup logging.
    UnifiedLogger.loadDebugFromSettings()
    com.tbread.logging.BossEncounterLogger.loadFromSettings()
    com.tbread.logging.RawPacketLogger.loadFromSettings()

    // 1. Check Admin
    ensureAdminOnWindows()
    ensureRunAsAdminFlag()

    // 2. Setup Logging/Errors
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        logger.error("Critical Error in thread {}: {}", t.name, e.message, e)
        e.printStackTrace()
        UnifiedLogger.crash("Uncaught exception in thread ${t.name}", e)
    }

    logger.info("Starting Native Aion2 Meter...")
    logger.info("Java: {} | Path: {}", System.getProperty("java.version"), System.getProperty("java.home"))

    // 3. Launch the Application
    // This blocks the main thread until the window is closed
    Application.launch(AionMeterApp::class.java, *args)
}

private fun configureJavaFxPipeline() {
    val osName = System.getProperty("os.name") ?: return
    val isWindows = osName.startsWith("Windows", ignoreCase = true)
    val isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null
    if (!isWindows || !isNativeImage) return
    if (!System.getProperty("prism.order").isNullOrBlank()) return
    System.setProperty("prism.order", "d3d")

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

/**
 * Sets the AppCompatFlags\Layers registry entry so the exe always triggers UAC elevation,
 * even if the embedded admin manifest is not read by Windows for some reason.
 */
private fun ensureRunAsAdminFlag() {
    val osName = System.getProperty("os.name") ?: return
    if (!osName.startsWith("Windows", ignoreCase = true)) return
    if (!isProcessElevated()) return

    val exePath = ProcessHandle.current().info().command().orElse(null) ?: return
    try {
        val regKey = "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\AppCompatFlags\\Layers"
        val result = ProcessBuilder(
            "reg", "query", regKey, "/v", exePath
        ).redirectErrorStream(true).start()
        val output = result.inputStream.bufferedReader().readText()
        result.waitFor()
        if (output.contains("RUNASADMIN")) return

        ProcessBuilder(
            "reg", "add", regKey,
            "/v", exePath,
            "/t", "REG_SZ",
            "/d", "~ RUNASADMIN",
            "/f"
        ).redirectErrorStream(true).start().waitFor()
    } catch (_: Exception) {
        // Best-effort; don't block startup if registry write fails
    }
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
