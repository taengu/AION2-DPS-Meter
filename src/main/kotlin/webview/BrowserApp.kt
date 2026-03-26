package com.tbread.webview

import com.tbread.DpsCalculator
import com.tbread.FightHistoryManager
import com.tbread.entity.DpsData
import com.tbread.entity.FightSummary
import com.tbread.logging.UnifiedLogger
import com.tbread.packet.CaptureDispatcher
import com.tbread.packet.CombatPortDetector
import com.tbread.packet.PcapCapturer
import com.tbread.packet.PingTracker
import com.tbread.packet.LocalPlayer
import com.tbread.packet.PropertyHandler
import com.tbread.windows.WindowTitleDetector
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.HostServices
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.web.WebView
import javafx.scene.web.WebEngine
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.util.Duration
import javafx.application.Platform
import javafx.stage.DirectoryChooser
import javafx.scene.image.WritablePixelFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO
import kotlin.system.exitProcess

class BrowserApp(
    private val dpsCalculator: DpsCalculator,
    private val captureDispatcher: CaptureDispatcher,
    private val replayNick: String? = null,
    private val onUiReady: (() -> Unit)? = null
) : Application() {

    private val logger = LoggerFactory.getLogger(BrowserApp::class.java)
    private var webEngine: WebEngine? = null
    private var jsBridge: JSBridge? = null
    override fun stop() {
        jsBridge?.dispose()
        historyAutoSaveScheduler.shutdown()
        gameVisibilityScheduler.shutdown()
        super.stop()
    }

    @Serializable
    data class ConnectionInfo(
        val ip: String?,
        val port: Int?,
        val locked: Boolean,
        val characterName: String?,
        val device: String?,
        val localPlayerId: Long?,
        val pcapError: String? = null
    )

    inner class JSBridge(
        private val stage: Stage,
        private val dpsCalculator: DpsCalculator,
        private val hostServices: HostServices,
        private val windowTitleProvider: () -> String?,
        private val uiReadyNotifier: () -> Unit,
        engine: WebEngine
    ) {
        private val logger = LoggerFactory.getLogger(JSBridge::class.java)
        private val webEngine = engine
        private val keyHookEvent = KeyHookEvent(engine) { toggleMainWindowVisibility() }
        private var windowHiddenByHotkey = false

        @Suppress("unused")
        fun moveWindow(x: Double, y: Double) {
            if (stage.x == x && stage.y == y) {
                return
            }
            stage.x = x
            stage.y = y
        }

        private fun toggleMainWindowVisibility() {
            if (!windowHiddenByHotkey) {
                windowHiddenByHotkey = true
                stage.opacity = 0.0
                stage.isAlwaysOnTop = false
                stage.scene?.root?.isMouseTransparent = true
                notifyJsWindowHidden(true)
                return
            }
            windowHiddenByHotkey = false
            if (!stage.isShowing) {
                stage.show()
            }
            stage.opacity = 1.0
            stage.scene?.root?.isMouseTransparent = false
            stage.isAlwaysOnTop = true
            if (stage.isIconified) {
                stage.isIconified = false
            }
            stage.toFront()
            stage.requestFocus()
            notifyJsWindowHidden(false)
        }

        private fun notifyJsWindowHidden(hidden: Boolean) {
            runCatching {
                webEngine.executeScript("window._dpsApp?._setWindowHidden?.($hidden)")
            }
        }

        @Suppress("unused")
        fun resetDps() {
            try {
                val records = dpsCalculator.snapshotBossFights()
                records.forEach { FightHistoryManager.save(it) }
            } catch (e: Exception) {
                logger.warn("Failed to snapshot boss fights before reset", e)
            }
            dpsCalculator.resetDataStorage()
        }

        @Suppress("unused")
        fun getFightHistory(): String {
            val liveRecords = try { dpsCalculator.snapshotBossFights() } catch (e: Exception) { emptyList() }
            val liveSummaries = liveRecords
                .sortedByDescending { it.startTimeMs }
                .map { record ->
                    FightSummary(
                        id = "live_${record.targetId}",
                        bossName = record.bossName,
                        targetId = record.targetId,
                        startTimeMs = record.startTimeMs,
                        durationMs = record.durationMs,
                        totalDamage = record.totalDamage,
                        jobs = record.jobs,
                        isTrain = record.isTrain,
                        isLive = true,
                    )
                }
            val savedSummaries = FightHistoryManager.list()
            return Json.encodeToString(liveSummaries + savedSummaries)
        }

        @Suppress("unused")
        fun getFightDetails(id: String): String? {
            if (id.startsWith("live_")) {
                val targetId = id.removePrefix("live_").toIntOrNull() ?: return null
                val records = try { dpsCalculator.snapshotBossFights() } catch (e: Exception) { emptyList() }
                val record = records.find { it.targetId == targetId } ?: return null
                return Json.encodeToString(record.copy(id = id))
            }
            val record = FightHistoryManager.load(id) ?: return null
            return Json.encodeToString(record)
        }

        @Suppress("unused")
        fun deleteFight(id: String): Boolean {
            if (id.startsWith("live_")) return false
            return FightHistoryManager.delete(id)
        }

        @Suppress("unused")
        fun resetAutoDetection() {
            CombatPortDetector.reset()
        }

        @Suppress("unused")
        fun getAvailableDevices(): String {
            return Json.encodeToString(PcapCapturer.getDeviceLabels())
        }

        @Suppress("unused")
        fun setManualDevice(deviceLabel: String?) {
            val trimmed = deviceLabel?.trim()?.takeIf { it.isNotBlank() }
            if (trimmed != null) {
                PropertyHandler.setProperty("dpsMeter.manualDevice", trimmed)
                // Force lock to the selected device by resetting and letting the
                // dispatcher filter by device. We store the preference and reset
                // so the next magic-byte detection prefers this device.
                CombatPortDetector.setPreferredDevice(trimmed)
                CombatPortDetector.reset()
            } else {
                PropertyHandler.removeProperty("dpsMeter.manualDevice")
                CombatPortDetector.setPreferredDevice(null)
                CombatPortDetector.reset()
            }
        }

        @Suppress("unused")
        fun setCharacterName(name: String?) {
            val trimmed = name?.trim().orEmpty()
            val normalized = if (trimmed.isBlank()) null else trimmed
            val changed = LocalPlayer.characterName != normalized
            LocalPlayer.characterName = normalized
            if (changed) {
                dpsCalculator.resetLocalIdentity()
            }
        }

        @Suppress("unused")
        fun setLocalPlayerId(actorId: String?) {
            val parsed = actorId?.trim()?.toLongOrNull()
            LocalPlayer.playerId = parsed?.takeIf { it > 0 }
        }

        @Suppress("unused")
        fun setTargetSelection(mode: String?) {
            dpsCalculator.setTargetSelectionModeById(mode)
        }

        @Suppress("unused")
        fun restartTargetSelection() {
            dpsCalculator.restartTargetSelection()
        }

        @Suppress("unused")
        fun setAllTargetsWindowMs(value: String?) {
            val parsed = value?.trim()?.toLongOrNull() ?: return
            dpsCalculator.setAllTargetsWindowMs(parsed)
        }

        @Suppress("unused")
        fun setTrainSelectionMode(mode: String?) {
            dpsCalculator.setTrainSelectionModeById(mode)
        }

        @Suppress("unused")
        fun setTargetSelectionWindowMs(value: String?) {
            val parsed = value?.trim()?.toLongOrNull() ?: return
            dpsCalculator.setTargetSelectionWindowMs(parsed)
        }

        @Suppress("unused")
        fun bindLocalActorId(actorId: String?) {
            val parsed = actorId?.trim()?.toLongOrNull() ?: return
            dpsCalculator.bindLocalActorId(parsed)
        }

        @Suppress("unused")
        fun bindLocalNickname(actorId: String?, nickname: String?) {
            val parsed = actorId?.trim()?.toLongOrNull() ?: return
            dpsCalculator.bindLocalNickname(parsed, nickname)
        }

        @Suppress("unused")
        fun getConnectionInfo(): String {
            val ip = PropertyHandler.getProperty("server.ip")
            val lockedPort = CombatPortDetector.currentPort()
            val lockedDevice = CombatPortDetector.currentDevice()
            val info = ConnectionInfo(
                ip = ip,
                port = lockedPort,
                locked = lockedPort != null,
                characterName = LocalPlayer.characterName,
                device = lockedDevice,
                localPlayerId = LocalPlayer.playerId,
                pcapError = PcapCapturer.pcapError
            )
            return Json.encodeToString(info)
        }

        @Suppress("unused")
        fun getLastParsedAtMs(): Long {
            return CombatPortDetector.lastParsedAtMs()
        }

        @Suppress("unused")
        fun getAion2WindowTitle(): String? {
            return windowTitleProvider()
        }

        @Suppress("unused")
        fun openBrowser(url: String) {
            try {
                hostServices.showDocument(url)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // ── Skill icon disk cache ──
        private val iconCacheDir: File by lazy {
            val appdata = System.getenv("APPDATA") ?: System.getProperty("user.home")
            File(appdata, "AionDPS/icon_cache").also { it.mkdirs() }
        }

        @Suppress("unused")
        fun readCachedIcon(filename: String): String? {
            val safe = filename.replace(Regex("[^a-zA-Z0-9._-]"), "")
            val file = File(iconCacheDir, safe)
            if (!file.exists()) return null
            return try {
                java.util.Base64.getEncoder().encodeToString(file.readBytes())
            } catch (_: Exception) { null }
        }

        @Suppress("unused")
        fun writeCachedIcon(filename: String, base64Data: String) {
            val safe = filename.replace(Regex("[^a-zA-Z0-9._-]"), "")
            try {
                val bytes = java.util.Base64.getDecoder().decode(base64Data)
                File(iconCacheDir, safe).writeBytes(bytes)
            } catch (_: Exception) {}
        }

        @Suppress("unused")
        fun readResource(path: String): String? {
            val normalized = if (path.startsWith("/")) path else "/$path"
            return try {
                javaClass.getResourceAsStream(normalized)?.bufferedReader(StandardCharsets.UTF_8)?.use {
                    it.readText()
                }
            } catch (_: Exception) {
                null
            }
        }

        @Suppress("unused")
        fun getSetting(key: String): String? {
            return PropertyHandler.getProperty(key)
        }

        @Suppress("unused")
        fun setSetting(key: String, value: String) {
            PropertyHandler.setProperty(key, value)
        }

        @Suppress("unused")
        fun setDebugLoggingEnabled(enabled: Boolean) {
            UnifiedLogger.setDebugEnabled(enabled)
            PropertyHandler.setProperty(UnifiedLogger.DEBUG_SETTING_KEY, enabled.toString())
        }

        @Suppress("unused")
        fun setBossLogsEnabled(enabled: Boolean) {
            com.tbread.logging.BossEncounterLogger.enabled = enabled
            PropertyHandler.setProperty(com.tbread.logging.BossEncounterLogger.SETTING_KEY, enabled.toString())
        }

        @Suppress("unused")
        fun setSaveRawPackets(enabled: Boolean) {
            com.tbread.logging.RawPacketLogger.enabled = enabled
            PropertyHandler.setProperty(com.tbread.logging.RawPacketLogger.SETTING_KEY, enabled.toString())
        }

        @Suppress("unused")
        fun setAutoHideMeter(enabled: Boolean) {
            autoHideEnabled = enabled
            PropertyHandler.setProperty("dpsMeter.autoHideMeter", enabled.toString())
        }

        @Suppress("unused")
        fun clearAllSettings() {
            PropertyHandler.clearAll()
            keyHookEvent.resetToDefaults()
        }

        @Suppress("unused")
        fun logDebug(message: String?) {
            if (message.isNullOrBlank()) return
            UnifiedLogger.debug(logger, "UI {}", message.trim())
        }

        @Suppress("unused")
        fun isRunningViaGradle(): Boolean {
            val gradleAppName = System.getProperty("org.gradle.appname")
            val javaCommand = System.getProperty("sun.java.command").orEmpty()
            return gradleAppName != null || javaCommand.contains("org.gradle", ignoreCase = true)
        }

        @Suppress("unused")
        fun isRunningFromIde(): Boolean {
            return System.getProperty("idea.version") != null ||
                    System.getProperty("idea.active") != null ||
                    System.getProperty("idea.platform.prefix") != null
        }

        @Suppress("unused")
        fun getParsingBacklog(): Int {
            return captureDispatcher.getParsingBacklog()
        }

        @Suppress("unused")
        fun getPingMs(): Int {
            return PingTracker.currentPingMs() ?: -1
        }

        @Suppress("unused")
        fun exitApp() {
            Platform.exit()
            exitProcess(0)
        }

        @Volatile
        private var updateCancelled = false

        @Suppress("unused")
        fun startUpdate(msiUrl: String) {
            updateCancelled = false
            Thread {
                try {
                    val tempDir = System.getProperty("java.io.tmpdir")
                    val msiFile = File(tempDir, "aion2meter_tw_update.msi")

                    val connection = java.net.URI(msiUrl).toURL().openConnection() as java.net.HttpURLConnection
                    connection.setRequestProperty("Accept", "application/octet-stream")
                    connection.connect()
                    val totalBytes = connection.contentLengthLong

                    var downloadedBytes = 0L
                    connection.inputStream.use { input ->
                        java.io.FileOutputStream(msiFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (updateCancelled) {
                                    connection.disconnect()
                                    msiFile.delete()
                                    Platform.runLater { webEngine.executeScript("window.onDownloadCancelled && window.onDownloadCancelled()") }
                                    return@Thread
                                }
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    val percent = (downloadedBytes * 100 / totalBytes).toInt()
                                    Platform.runLater {
                                        webEngine.executeScript("window.onDownloadProgress && window.onDownloadProgress($percent)")
                                    }
                                }
                            }
                        }
                    }

                    Platform.runLater { webEngine.executeScript("window.onDownloadComplete && window.onDownloadComplete()") }

                    val currentExe = ProcessHandle.current().info().command().orElse(null)
                    val relaunchLine = if (currentExe != null)
                        "Start-Process '${currentExe.replace("'", "''")}'"
                    else ""

                    // Pass current install directory so MSI upgrades in-place
                    val installDirArg = if (currentExe != null) {
                        val installDir = File(currentExe).parentFile?.absolutePath
                        if (installDir != null) ",'INSTALLDIR=${installDir.replace("'", "''")}'" else ""
                    } else ""

                    val psFile = File(tempDir, "aion2meter_tw_updater.ps1")
                    psFile.writeText("""
                        Start-Process msiexec -ArgumentList '/i','${msiFile.absolutePath.replace("'", "''")}','/qn','/norestart'$installDirArg -Wait
                        $relaunchLine
                    """.trimIndent())

                    ProcessBuilder(
                        "powershell", "-ExecutionPolicy", "Bypass",
                        "-WindowStyle", "Hidden",
                        "-File", psFile.absolutePath
                    ).start()

                    Platform.exit()
                    exitProcess(0)
                } catch (e: Exception) {
                    if (updateCancelled) return@Thread
                    logger.error("Update failed", e)
                    Platform.runLater { webEngine.executeScript("window.onDownloadError && window.onDownloadError()") }
                }
            }.apply { isDaemon = true }.start()
        }

        @Suppress("unused")
        fun cancelUpdate() {
            updateCancelled = true
        }

        @Suppress("unused")
        fun setHotkey(modifiers: Int, keyCode: Int) {
            logger.info("setHotkey called mods={} vk={}", modifiers, keyCode)
            keyHookEvent.setHotkey(modifiers, keyCode)
        }

        @Suppress("unused")
        fun getCurrentHotKey(): String {
            return keyHookEvent.getCurrentHotKey()
        }

        @Suppress("unused")
        fun setToggleWindowHotkey(modifiers: Int, keyCode: Int) {
            logger.info("setToggleWindowHotkey called mods={} vk={}", modifiers, keyCode)
            keyHookEvent.setToggleWindowHotkey(modifiers, keyCode)
        }

        @Suppress("unused")
        fun getCurrentToggleWindowHotKey(): String {
            return keyHookEvent.getCurrentToggleWindowHotKey()
        }

        fun isWindowHiddenByHotkey(): Boolean = windowHiddenByHotkey

        fun dispose() {
            keyHookEvent.stop()
        }

        private fun computeCrop(
            x: Double, y: Double, width: Double, height: Double,
            scale: Double, imageWidth: Int, imageHeight: Int
        ): IntArray {
            val sx = (x * scale).toInt().coerceAtLeast(0)
            val sy = (y * scale).toInt().coerceAtLeast(0)
            val sw = (width * scale).toInt().coerceAtLeast(1).coerceAtMost(imageWidth - sx)
            val sh = (height * scale).toInt().coerceAtLeast(1).coerceAtMost(imageHeight - sy)
            return intArrayOf(sx, sy, sw, sh)
        }

        private fun <T> runOnFxThread(timeoutSeconds: Long = 2, action: () -> T?): T? {
            val latch = CountDownLatch(1)
            var result: T? = null
            Platform.runLater {
                try {
                    result = action()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
            return result
        }

        @Suppress("unused")
        fun captureScreenshotToClipboard(x: Double, y: Double, width: Double, height: Double, scale: Double): Boolean {
            val scene = stage.scene ?: return false
            return runOnFxThread {
                try {
                    val image = scene.snapshot(null)
                    val pixelReader = image.pixelReader ?: return@runOnFxThread false
                    val (cx, cy, cw, ch) = computeCrop(x, y, width, height, scale, image.width.toInt(), image.height.toInt())
                    val cropped = javafx.scene.image.WritableImage(pixelReader, cx, cy, cw, ch)
                    val content = ClipboardContent()
                    content.putImage(cropped)
                    Clipboard.getSystemClipboard().setContent(content)
                } catch (e: Exception) {
                    logger.warn("Failed to capture screenshot", e)
                    false
                }
            } ?: false
        }

        @Suppress("unused")
        fun captureScreenshotToFile(
            x: Double, y: Double, width: Double, height: Double,
            scale: Double, folderPath: String?, filename: String?
        ): Boolean {
            val scene = stage.scene ?: return false
            if (folderPath.isNullOrBlank() || filename.isNullOrBlank()) return false
            return runOnFxThread {
                try {
                    val image = scene.snapshot(null)
                    val pixelReader = image.pixelReader ?: return@runOnFxThread false
                    val (cx, cy, cw, ch) = computeCrop(x, y, width, height, scale, image.width.toInt(), image.height.toInt())
                    val folder = File(folderPath)
                    if (!folder.exists()) folder.mkdirs()
                    val buffer = IntArray(cw * ch)
                    val format = WritablePixelFormat.getIntArgbInstance()
                    pixelReader.getPixels(cx, cy, cw, ch, format, buffer, 0, cw)
                    val buffered = BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB)
                    buffered.setRGB(0, 0, cw, ch, buffer, 0, cw)
                    ImageIO.write(buffered, "png", File(folder, filename))
                } catch (e: Exception) {
                    logger.warn("Failed to capture screenshot to file", e)
                    false
                }
            } ?: false
        }

        @Suppress("unused")
        fun getDefaultScreenshotFolder(): String {
            val userHome = System.getProperty("user.home") ?: "."
            return Paths.get(userHome, "Pictures", "AION2 DPS Meter").toString()
        }

        @Suppress("unused")
        fun chooseScreenshotFolder(currentPath: String?): String? {
            val chooser = DirectoryChooser()
            chooser.title = "Select screenshot folder"
            val initial = currentPath?.let { File(it) }
            if (initial?.exists() == true && initial.isDirectory) {
                chooser.initialDirectory = initial
            }
            if (Platform.isFxApplicationThread()) {
                return try {
                    chooser.showDialog(stage)?.absolutePath
                } catch (e: Exception) {
                    logger.warn("Failed to choose screenshot folder", e)
                    null
                }
            }
            val latch = CountDownLatch(1)
            var selectedPath: String? = null
            Platform.runLater {
                try {
                    selectedPath = chooser.showDialog(stage)?.absolutePath
                } catch (e: Exception) {
                    logger.warn("Failed to choose screenshot folder", e)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(10, TimeUnit.SECONDS)
            return selectedPath
        }

        @Suppress("unused")
        fun notifyUiReady() {
            uiReadyNotifier()
        }
    }

    @Volatile
    private var dpsData: DpsData = DpsData()

    @Volatile
    private var cachedDpsJson: String = Json.encodeToString(DpsData())

    private val dpsUpdateScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dps-updater").apply { isDaemon = true }
    }

    private val debugMode = false

    private val version = "1.0.6"

    @Volatile
    private var cachedWindowTitle: String? = null
    private val uiReadyReported = AtomicBoolean(false)
    private val uiReadyNotifier: () -> Unit = {
        if (uiReadyReported.compareAndSet(false, true)) {
            onUiReady?.invoke()
        }
    }

    private val windowTitleScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "window-title-poller").apply { isDaemon = true }
    }

    private val historyAutoSaveScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "history-autosave").apply { isDaemon = true }
    }

    private val gameVisibilityScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "game-visibility-poller").apply { isDaemon = true }
    }

    /** Tracks whether the meter was hidden by the game being minimized (vs. hidden by hotkey). */
    @Volatile
    private var hiddenByGameMinimize = false

    /** Whether auto-hide is enabled (hides meter when game is not focused). */
    @Volatile
    private var autoHideEnabled = PropertyHandler.getProperty("dpsMeter.autoHideMeter") != "false"

    private fun startHistoryAutoSave() {
        historyAutoSaveScheduler.scheduleAtFixedRate({
            try {
                val records = dpsCalculator.snapshotBossFights()
                records.forEach { FightHistoryManager.save(it) }
            } catch (e: Exception) {
                logger.warn("History auto-save failed", e)
            }
        }, 10L, 10L, TimeUnit.SECONDS)
    }

    /**
     * Position the stage on the same screen as the game window.
     * If the game isn't running, defaults to the primary screen.
     */
    // ── Window position persistence ──

    private fun hasSavedWindowPosition(): Boolean {
        return PropertyHandler.getProperty("window.x") != null &&
               PropertyHandler.getProperty("window.y") != null
    }

    private fun restoreWindowPosition(stage: Stage) {
        val x = PropertyHandler.getProperty("window.x")?.toDoubleOrNull() ?: return
        val y = PropertyHandler.getProperty("window.y")?.toDoubleOrNull() ?: return
        // Verify the saved position is on a visible screen
        val onScreen = Screen.getScreens().any { it.visualBounds.contains(x, y) }
        if (onScreen) {
            stage.x = x
            stage.y = y
            logger.info("Restored window position: ({}, {})", x.toInt(), y.toInt())
        } else {
            logger.info("Saved window position ({}, {}) is off-screen, ignoring", x.toInt(), y.toInt())
        }
    }

    private fun saveWindowPosition(stage: Stage) {
        if (stage.x.isNaN() || stage.y.isNaN()) return
        PropertyHandler.setProperty("window.x", stage.x.toString())
        PropertyHandler.setProperty("window.y", stage.y.toString())
    }

    private fun listenForWindowMoves(stage: Stage) {
        // Save position when the user stops dragging (debounced via Timeline)
        var saveTimer: Timeline? = null
        val saveDelay = Duration.millis(500.0)
        val handler = { _: Any ->
            saveTimer?.stop()
            val t = Timeline(KeyFrame(saveDelay, { saveWindowPosition(stage) }))
            saveTimer = t
            t.play()
        }
        stage.xProperty().addListener { _, _, _ -> handler(Unit) }
        stage.yProperty().addListener { _, _, _ -> handler(Unit) }
    }

    /**
     * If the game is running on a non-primary screen, move the meter there.
     * Otherwise leave the default JavaFX position untouched.
     */
    private fun positionOnGameScreen(stage: Stage) {
        val gameWindow = WindowTitleDetector.findAion2Window() ?: return
        val rect = WindowTitleDetector.getWindowRect(gameWindow.hwnd) ?: return

        val gameCenterX = (rect.left + rect.right) / 2.0
        val gameCenterY = (rect.top + rect.bottom) / 2.0
        val gameScreen = Screen.getScreens().firstOrNull { it.bounds.contains(gameCenterX, gameCenterY) }
            ?: return

        // Only reposition if the game is on a different screen than primary
        val primary = Screen.getPrimary()
        if (gameScreen == primary) return

        val bounds = gameScreen.visualBounds
        stage.x = bounds.minX
        stage.y = bounds.minY
        logger.info("Moved meter to game screen: {}x{} at ({}, {})",
            bounds.width.toInt(), bounds.height.toInt(), stage.x.toInt(), stage.y.toInt())
    }

    /**
     * Poll game window visibility and auto-hide/restore the meter accordingly.
     * Does not interfere with hotkey-based hiding.
     */
    private fun startGameVisibilityPolling(stage: Stage) {
        if (replayNick != null) return  // Don't poll in replay mode

        gameVisibilityScheduler.scheduleAtFixedRate({
            try {
                if (!autoHideEnabled) {
                    // If auto-hide was just disabled while meter is hidden, restore it
                    if (hiddenByGameMinimize) {
                        Platform.runLater {
                            hiddenByGameMinimize = false
                            stage.opacity = 1.0
                            stage.isAlwaysOnTop = true
                            stage.scene?.root?.isMouseTransparent = false
                        }
                    }
                    return@scheduleAtFixedRate
                }

                val gameWindow = WindowTitleDetector.findAion2Window()
                val gameRunning = gameWindow != null
                val gameMinimized = gameWindow != null && WindowTitleDetector.isMinimized(gameWindow.hwnd)
                val gameOrMeterForeground = WindowTitleDetector.isAion2Foreground() || stage.isFocused

                // Hide when game is running but not active (minimized or alt-tabbed away)
                val shouldHide = gameRunning && (gameMinimized || !gameOrMeterForeground)

                Platform.runLater {
                    val bridge = jsBridge
                    // Don't interfere if the user hid the meter via hotkey
                    if (bridge != null && bridge.isWindowHiddenByHotkey()) return@runLater

                    if (shouldHide && !hiddenByGameMinimize) {
                        hiddenByGameMinimize = true
                        stage.opacity = 0.0
                        stage.isAlwaysOnTop = false
                        stage.scene?.root?.isMouseTransparent = true
                        runCatching { webEngine?.executeScript("window._dpsApp?._setWindowHidden?.(true)") }
                    } else if (!shouldHide && hiddenByGameMinimize) {
                        hiddenByGameMinimize = false
                        stage.opacity = 1.0
                        stage.isAlwaysOnTop = true
                        stage.scene?.root?.isMouseTransparent = false
                        runCatching { webEngine?.executeScript("window._dpsApp?._setWindowHidden?.(false)") }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Game visibility polling failed", e)
            }
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun startWindowTitlePolling() {
        // In replay mode with a forced nickname, keep the synthetic window title
        if (replayNick != null) return

        // Run off the JavaFX Application Thread — EnumWindows + OpenProcess per window
        // can take 50–200ms, which would block rendering and JS execution if on the JAT.
        windowTitleScheduler.scheduleAtFixedRate({
            try {
                cachedWindowTitle = WindowTitleDetector.findAion2WindowTitle()
            } catch (e: Exception) {
                logger.warn("Window title polling failed", e)
            }
        }, 0L, 1L, TimeUnit.SECONDS)
    }

    private fun ensureStageVisible(stage: Stage, reason: String, grabFocus: Boolean = false) {
        if (!stage.isShowing) {
            logger.warn("Stage not showing ({}); forcing show", reason)
            stage.show()
        }
        if (stage.isIconified) {
            stage.isIconified = false
        }
        if (grabFocus) {
            stage.toFront()
            stage.requestFocus()
        }
    }

    override fun start(stage: Stage) {
        UnifiedLogger.loadDebugFromSettings()
        // Restore manual device preference from settings
        val savedDevice = PropertyHandler.getProperty("dpsMeter.manualDevice")
        if (!savedDevice.isNullOrBlank()) {
            CombatPortDetector.setPreferredDevice(savedDevice)
        }
        if (replayNick != null) {
            // Provide a synthetic window title so the JS polling detects the replay nickname
            cachedWindowTitle = "AION2 | $replayNick"
        }
        startWindowTitlePolling()
        startHistoryAutoSave()
        stage.setOnCloseRequest {
            saveWindowPosition(stage)
            exitProcess(0)
        }
        val webView = WebView()
        val engine = webView.engine

        engine.history.maxSize = 0

        webEngine = engine

        val bridge = JSBridge(stage, dpsCalculator, hostServices, { cachedWindowTitle }, uiReadyNotifier, engine)
        jsBridge = bridge

        // Push connection info updates to the webview when port detection changes
        val pushConnectionInfo = {
            Platform.runLater {
                runCatching {
                    engine.executeScript("window._dpsApp?.refreshConnectionInfo?.()")
                }
            }
        }
        val previousOnDeviceLocked = CombatPortDetector.onDeviceLocked
        CombatPortDetector.onDeviceLocked = { lockedDevice ->
            previousOnDeviceLocked?.invoke(lockedDevice)
            pushConnectionInfo()
        }
        val previousOnReset = CombatPortDetector.onReset
        CombatPortDetector.onReset = {
            previousOnReset?.invoke()
            pushConnectionInfo()
        }

        // Push ping updates to the webview immediately when parsed
        PingTracker.onPingUpdate = { pingMs ->
            Platform.runLater {
                runCatching {
                    engine.executeScript("window._dpsApp?.updatePing?.($pingMs)")
                }
            }
        }

        val injectBridge = {
            runCatching {
                val window = engine.executeScript("window")
                // Use getMethod instead of firstOrNull to be more explicit,
                // and ensure we are looking at the correct interface
                val setMember = window.javaClass.getMethod("setMember", String::class.java, Any::class.java)

                // This is the critical line that works with the --add-opens above
                setMember.isAccessible = true

                setMember.invoke(window, "javaBridge", bridge)
                setMember.invoke(window, "dpsData", this)
            }.onFailure { error ->
                logger.warn("Failed to inject Java bridge into WebView", error)
            }
        }
        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            when (newState) {
                Worker.State.SUCCEEDED -> {
                    injectBridge()
                    uiReadyNotifier()
                    if (stage.opacity < 1.0) {
                        stage.opacity = 1.0
                        ensureStageVisible(stage, "webview-loaded")
                    }
                }
                Worker.State.FAILED -> logger.error("WebView failed to load index.html")
                else -> Unit
            }
        }
        val indexUrl = requireNotNull(javaClass.getResource("/index.html")) { "index.html not found" }
        engine.load(indexUrl.toExternalForm())
        if (engine.loadWorker.state == Worker.State.SUCCEEDED) {
            injectBridge()
        }

        val scene = Scene(webView, 1600.0, 1000.0)
        scene.fill = Color.TRANSPARENT

        try {
            val pageField = engine.javaClass.getDeclaredField("page")
            pageField.isAccessible = true
            val page = pageField.get(engine)

            val setBgMethod = page.javaClass.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
            setBgMethod.isAccessible = true
            setBgMethod.invoke(page, 0)
        } catch (e: Exception) {
            logger.error("Failed to set webview background via reflection", e)
        }

        stage.initStyle(StageStyle.TRANSPARENT)
        stage.scene = scene
        stage.isAlwaysOnTop = true
        stage.title = "Aion2 Dps Overlay"
        stage.setOnShown { /* uiReady now triggered by WebView SUCCEEDED instead */ }
        stage.opacity = 0.0
        restoreWindowPosition(stage)
        stage.show()
        // Only override saved position if the game is on a non-primary screen and no position was restored
        if (!hasSavedWindowPosition()) {
            positionOnGameScreen(stage)
        }
        ensureStageVisible(stage, "initial", grabFocus = true)
        listenForWindowMoves(stage)
        startGameVisibilityPolling(stage)
        Timeline(KeyFrame(Duration.seconds(2.0), {
            if (stage.opacity < 1.0) {
                stage.opacity = 1.0
                ensureStageVisible(stage, "fallback-show")
            }
        })).apply {
            cycleCount = 1
            play()
        }
        Timeline(KeyFrame(Duration.seconds(1.0), {
            ensureStageVisible(stage, "delayed")
        })).apply {
            cycleCount = 1
            play()
        }
        // Run getDps() on a background thread so it never blocks the JavaFX Application
        // Thread (which also drives WebView rendering and JS↔Java bridge calls).
        // The volatile write to dpsData is safe to read from any thread.
        dpsUpdateScheduler.scheduleAtFixedRate({
            try {
                // Skip expensive DPS calculation when window is not visible
                if (jsBridge?.isWindowHiddenByHotkey() == true || hiddenByGameMinimize) return@scheduleAtFixedRate
                val data = dpsCalculator.getDps()
                dpsData = data
                cachedDpsJson = Json.encodeToString(data)
            } catch (e: Exception) {
                logger.warn("getDps() failed on background thread", e)
                UnifiedLogger.info(logger, "getDps() crashed: {}", e.message)
                // Reset to empty so the JS sees a change and doesn't stay frozen on stale data
                val empty = DpsData()
                dpsData = empty
                cachedDpsJson = Json.encodeToString(empty)
            }
        }, 0L, 100L, TimeUnit.MILLISECONDS)
    }

    @Suppress("unused")
    fun getDpsData(): String {
        return cachedDpsJson
    }

    @Suppress("unused")
    fun isDebuggingMode(): Boolean {
        return debugMode
    }

    @Suppress("unused")
    fun getBattleDetail(uid:Int):String{
        return try {
            Json.encodeToString(dpsData.map[uid]?.analyzedData)
        } catch (e: Exception) {
            logger.warn("getBattleDetail({}) failed", uid, e)
            "{}"
        }
    }

    @Suppress("unused")
    fun getDetailsContext(): String {
        return try {
            Json.encodeToString(dpsCalculator.getDetailsContext())
        } catch (e: Exception) {
            logger.warn("getDetailsContext() failed", e)
            "{\"currentTargetId\":0,\"targets\":[],\"actors\":[]}"
        }
    }

    @Suppress("unused")
    fun getTargetDetails(targetId: Int, actorIdsJson: String?): String {
        val actorIds = actorIdsJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.decodeFromString<List<Int>>(it) }.getOrNull() }
            ?.toSet()
        return Json.encodeToString(dpsCalculator.getTargetDetails(targetId, actorIds))
    }

    @Suppress("unused")
    fun getVersion():String{
        return version
    }

}