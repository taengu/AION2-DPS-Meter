package com.tbread.webview

import com.tbread.DpsCalculator
import com.tbread.entity.DpsData
import com.tbread.logging.UnifiedLogger
import com.tbread.packet.CaptureDispatcher
import com.tbread.packet.CombatPortDetector
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
import java.util.concurrent.TimeUnit
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO
import kotlin.system.exitProcess

class BrowserApp(
    private val dpsCalculator: DpsCalculator,
    private val captureDispatcher: CaptureDispatcher,
    private val onUiReady: (() -> Unit)? = null
) : Application() {

    private val logger = LoggerFactory.getLogger(BrowserApp::class.java)
    private var webEngine: WebEngine? = null
    private var jsBridge: JSBridge? = null
    override fun stop() {
        jsBridge?.dispose()
        super.stop()
    }

    @Serializable
    data class ConnectionInfo(
        val ip: String?,
        val port: Int?,
        val locked: Boolean,
        val characterName: String?,
        val device: String?,
        val localPlayerId: Long?
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
        }

        @Suppress("unused")
        fun resetDps(){
            dpsCalculator.resetDataStorage()
        }

        @Suppress("unused")
        fun resetAutoDetection() {
            CombatPortDetector.reset()
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
                localPlayerId = LocalPlayer.playerId
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
        fun exitApp() {
            Platform.exit()
            exitProcess(0)
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

        fun dispose() {
            keyHookEvent.stop()
        }

        @Suppress("unused")
        fun captureScreenshotToClipboard(x: Double, y: Double, width: Double, height: Double, scale: Double): Boolean {
            val scene = stage.scene ?: return false
            val latch = CountDownLatch(1)
            var success = false
            Platform.runLater {
                try {
                    val image = scene.snapshot(null)
                    val pixelReader = image.pixelReader
                    if (pixelReader == null) {
                        latch.countDown()
                        return@runLater
                    }
                    val imageWidth = image.width.toInt()
                    val imageHeight = image.height.toInt()
                    val scaledX = (x * scale).toInt()
                    val scaledY = (y * scale).toInt()
                    val scaledWidth = (width * scale).toInt()
                    val scaledHeight = (height * scale).toInt()
                    val safeX = scaledX.coerceAtLeast(0)
                    val safeY = scaledY.coerceAtLeast(0)
                    val safeWidth = scaledWidth.coerceAtLeast(1).coerceAtMost(imageWidth - safeX)
                    val safeHeight = scaledHeight.coerceAtLeast(1).coerceAtMost(imageHeight - safeY)
                    val cropped = javafx.scene.image.WritableImage(pixelReader, safeX, safeY, safeWidth, safeHeight)
                    val clipboard = Clipboard.getSystemClipboard()
                    val content = ClipboardContent()
                    content.putImage(cropped)
                    success = clipboard.setContent(content)
                } catch (e: Exception) {
                    logger.warn("Failed to capture screenshot", e)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            return success
        }

        @Suppress("unused")
        fun captureScreenshotToFile(
            x: Double,
            y: Double,
            width: Double,
            height: Double,
            scale: Double,
            folderPath: String?,
            filename: String?
        ): Boolean {
            val scene = stage.scene ?: return false
            if (folderPath.isNullOrBlank() || filename.isNullOrBlank()) return false
            val latch = CountDownLatch(1)
            var success = false
            Platform.runLater {
                try {
                    val image = scene.snapshot(null)
                    val pixelReader = image.pixelReader
                    if (pixelReader == null) {
                        latch.countDown()
                        return@runLater
                    }
                    val imageWidth = image.width.toInt()
                    val imageHeight = image.height.toInt()
                    val scaledX = (x * scale).toInt()
                    val scaledY = (y * scale).toInt()
                    val scaledWidth = (width * scale).toInt()
                    val scaledHeight = (height * scale).toInt()
                    val safeX = scaledX.coerceAtLeast(0)
                    val safeY = scaledY.coerceAtLeast(0)
                    val safeWidth = scaledWidth.coerceAtLeast(1).coerceAtMost(imageWidth - safeX)
                    val safeHeight = scaledHeight.coerceAtLeast(1).coerceAtMost(imageHeight - safeY)
                    val folder = File(folderPath)
                    if (!folder.exists()) {
                        folder.mkdirs()
                    }
                    val targetFile = File(folder, filename)
                    val buffer = IntArray(safeWidth * safeHeight)
                    val format = WritablePixelFormat.getIntArgbInstance()
                    pixelReader.getPixels(safeX, safeY, safeWidth, safeHeight, format, buffer, 0, safeWidth)
                    val buffered = BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_INT_ARGB)
                    buffered.setRGB(0, 0, safeWidth, safeHeight, buffer, 0, safeWidth)
                    success = ImageIO.write(buffered, "png", targetFile)
                } catch (e: Exception) {
                    logger.warn("Failed to capture screenshot to file", e)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            return success
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
    private var dpsData: DpsData = dpsCalculator.getDps()

    private val debugMode = false

    private val version = "1.0.0-pre1"

    @Volatile
    private var cachedWindowTitle: String? = null
    private val uiReadyReported = AtomicBoolean(false)
    private val uiReadyNotifier: () -> Unit = {
        if (uiReadyReported.compareAndSet(false, true)) {
            onUiReady?.invoke()
        }
    }

    private fun startWindowTitlePolling() {
        Timeline(KeyFrame(Duration.seconds(1.0), {
            cachedWindowTitle = WindowTitleDetector.findAion2WindowTitle()
        })).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    private fun ensureStageVisible(stage: Stage, reason: String) {
        if (!stage.isShowing) {
            logger.warn("Stage not showing ({}); forcing show", reason)
            stage.show()
        }
        if (stage.isIconified) {
            stage.isIconified = false
        }
        stage.toFront()
        stage.requestFocus()
    }

    override fun start(stage: Stage) {
        UnifiedLogger.loadDebugFromSettings()
        startWindowTitlePolling()
        stage.setOnCloseRequest {
            exitProcess(0)
        }
        val webView = WebView()
        val engine = webView.engine

        engine.history.maxSize = 0

        webEngine = engine

        val bridge = JSBridge(stage, dpsCalculator, hostServices, { cachedWindowTitle }, uiReadyNotifier, engine)
        jsBridge = bridge

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
        stage.setOnShown { uiReadyNotifier() }
        stage.opacity = 0.0
        stage.show()
        ensureStageVisible(stage, "initial")
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
        Timeline(KeyFrame(Duration.millis(500.0), {
            dpsData = dpsCalculator.getDps()
        })).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    @Suppress("unused")
    fun getDpsData(): String {
        return Json.encodeToString(dpsData)
    }

    @Suppress("unused")
    fun isDebuggingMode(): Boolean {
        return debugMode
    }

    @Suppress("unused")
    fun getBattleDetail(uid:Int):String{
        return Json.encodeToString(dpsData.map[uid]?.analyzedData)
    }

    @Suppress("unused")
    fun getDetailsContext(): String {
        return Json.encodeToString(dpsCalculator.getDetailsContext())
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