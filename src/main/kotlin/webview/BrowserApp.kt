package com.tbread.webview

import com.tbread.DpsCalculator
import com.tbread.entity.DpsData
import com.tbread.keyboard.RefreshKeybindManager
import com.tbread.logging.DebugLogWriter
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
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.web.WebView
import javafx.scene.web.WebEngine
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.util.Duration
import javafx.application.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import netscape.javascript.JSObject
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class BrowserApp(
    private val dpsCalculator: DpsCalculator,
    private val captureDispatcher: CaptureDispatcher,
    private val onUiReady: (() -> Unit)? = null
) : Application() {

    private val logger = LoggerFactory.getLogger(BrowserApp::class.java)
    private var webEngine: WebEngine? = null
    private val refreshKeybindManager = RefreshKeybindManager({ triggerRefreshFromKeybind() })
    @Volatile
    private var refreshKeybindValue: String = "Ctrl+R"
    @Volatile
    private var keybindCaptureActive: Boolean = false
    @Volatile
    private var keybindCapturePending: String? = null
    override fun stop() {
        refreshKeybindManager.stop()
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
        private val uiReadyNotifier: () -> Unit
    ) {
        private val logger = LoggerFactory.getLogger(JSBridge::class.java)

        fun moveWindow(x: Double, y: Double) {
            stage.x = x
            stage.y = y
        }

        fun resetDps(){
            dpsCalculator.resetDataStorage()
        }

        fun resetAutoDetection() {
            CombatPortDetector.reset()
        }

        fun setCharacterName(name: String?) {
            val trimmed = name?.trim().orEmpty()
            val normalized = if (trimmed.isBlank()) null else trimmed
            val changed = LocalPlayer.characterName != normalized
            LocalPlayer.characterName = normalized
            if (changed) {
                LocalPlayer.playerId = null
            }
        }

        fun setLocalPlayerId(actorId: String?) {
            val parsed = actorId?.trim()?.toLongOrNull()
            LocalPlayer.playerId = parsed?.takeIf { it > 0 }
        }

        fun setTargetSelection(mode: String?) {
            dpsCalculator.setTargetSelectionModeById(mode)
        }

        fun setAllTargetsWindowMs(value: String?) {
            val parsed = value?.trim()?.toLongOrNull() ?: return
            dpsCalculator.setAllTargetsWindowMs(parsed)
        }

        fun setTrainSelectionMode(mode: String?) {
            dpsCalculator.setTrainSelectionModeById(mode)
        }

        fun bindLocalActorId(actorId: String?) {
            val parsed = actorId?.trim()?.toLongOrNull() ?: return
            dpsCalculator.bindLocalActorId(parsed)
        }

        fun bindLocalNickname(actorId: String?, nickname: String?) {
            val parsed = actorId?.trim()?.toLongOrNull() ?: return
            dpsCalculator.bindLocalNickname(parsed, nickname)
        }

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

        fun getAion2WindowTitle(): String? {
            return windowTitleProvider()
        }

        fun openBrowser(url: String) {
            try {
                hostServices.showDocument(url)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun readResource(path: String): String? {
            val normalized = if (path.startsWith("/")) path else "/$path"
            return try {
                javaClass.getResourceAsStream(normalized)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
        }

        fun getSetting(key: String): String? {
            return PropertyHandler.getProperty(key)
        }

        fun setSetting(key: String, value: String) {
            PropertyHandler.setProperty(key, value)
        }

        fun setDebugLoggingEnabled(enabled: Boolean) {
            DebugLogWriter.setEnabled(enabled)
            PropertyHandler.setProperty(DebugLogWriter.SETTING_KEY, enabled.toString())
        }

        fun setRefreshKeybind(value: String?) {
            val normalized = value?.trim().orEmpty()
            PropertyHandler.setProperty("dpsMeter.refreshKeybind", normalized)
            refreshKeybindManager.updateKeybind(normalized)
            refreshKeybindValue = normalized.ifBlank { "Ctrl+R" }
        }

        fun startRefreshKeybindCapture(): Boolean {
            keybindCaptureActive = true
            keybindCapturePending = null
            return refreshKeybindManager.beginCapture { combo ->
                notifyKeybindCaptured(combo)
            }
        }

        fun cancelRefreshKeybindCapture() {
            keybindCaptureActive = false
            keybindCapturePending = null
            refreshKeybindManager.cancelCapture()
        }

        fun logDebug(message: String?) {
            if (message.isNullOrBlank()) return
            DebugLogWriter.debug(logger, "UI {}", message.trim())
        }

        fun isRunningViaGradle(): Boolean {
            val gradleAppName = System.getProperty("org.gradle.appname")
            val javaCommand = System.getProperty("sun.java.command").orEmpty()
            return gradleAppName != null || javaCommand.contains("org.gradle", ignoreCase = true)
        }

        fun isRunningFromIde(): Boolean {
            return System.getProperty("idea.version") != null ||
                System.getProperty("idea.active") != null ||
                System.getProperty("idea.platform.prefix") != null
        }

        fun getParsingBacklog(): Int {
            return captureDispatcher.getParsingBacklog()
        }

        fun exitApp() {
          Platform.exit()     
          exitProcess(0)       
        }

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

        fun notifyUiReady() {
            uiReadyNotifier()
        }
    }

    @Volatile
    private var dpsData: DpsData = dpsCalculator.getDps()

    private val debugMode = false

    private val version = "0.1.5"

    @Volatile
    private var cachedWindowTitle: String? = null
    private val windowTitlePollerStarted = AtomicBoolean(false)
    private val uiReadyReported = AtomicBoolean(false)
    private val uiReadyNotifier: () -> Unit = {
        if (uiReadyReported.compareAndSet(false, true)) {
            onUiReady?.invoke()
        }
    }

    private fun startWindowTitlePolling() {
        if (!windowTitlePollerStarted.compareAndSet(false, true)) return
        thread(name = "window-title-poller", isDaemon = true) {
            while (true) {
                cachedWindowTitle = WindowTitleDetector.findAion2WindowTitle()
                Thread.sleep(1000)
            }
        }
    }

    private fun triggerRefreshFromKeybind() {
        val engine = webEngine
        if (engine == null) {
            dpsCalculator.resetDataStorage()
            return
        }
        Platform.runLater {
            try {
                engine.executeScript("window.dpsApp?.triggerRefreshFromKeybind?.()")
            } catch (e: Exception) {
                logger.warn("Failed to trigger refresh via keybind", e)
            }
        }
    }

    private fun parseKeybindParts(value: String): Pair<Set<String>, String> {
        val cleaned = value.replace("\\s+".toRegex(), "").uppercase()
        if (cleaned.isBlank()) return emptySet<String>() to ""
        val parts = cleaned.split("+").filter { it.isNotBlank() }.toMutableList()
        var key = ""
        val mods = mutableSetOf<String>()
        parts.forEach { part ->
            when (part) {
                "CTRL", "CONTROL" -> mods.add("Ctrl")
                "ALT" -> mods.add("Alt")
                "SHIFT" -> mods.add("Shift")
                "META", "CMD", "WIN" -> mods.add("Meta")
                else -> key = part
            }
        }
        return mods to key
    }

    private fun matchesKeybind(event: KeyEvent, keybindValue: String): Boolean {
        val (mods, key) = parseKeybindParts(keybindValue)
        if (key.isBlank()) return false
        if (event.isControlDown != mods.contains("Ctrl")) return false
        if (event.isAltDown != mods.contains("Alt")) return false
        if (event.isShiftDown != mods.contains("Shift")) return false
        if (event.isMetaDown != mods.contains("Meta")) return false
        val code = event.code
        val keyText = when {
            code.isDigitKey -> code.name.removePrefix("DIGIT")
            code.isLetterKey -> code.name.removePrefix("KEY")
            else -> code.name
        }
        return keyText.equals(key, ignoreCase = true)
    }

    private fun buildCombo(event: KeyEvent): String {
        if (event.code.isModifierKey) return ""
        if (!event.isControlDown && !event.isAltDown && !event.isMetaDown) return ""
        val parts = mutableListOf<String>()
        if (event.isControlDown) parts.add("Ctrl")
        if (event.isAltDown) parts.add("Alt")
        if (event.isShiftDown) parts.add("Shift")
        if (event.isMetaDown) parts.add("Meta")
        val code = event.code
        val keyText = when {
            code.isDigitKey -> code.name.removePrefix("DIGIT")
            code.isLetterKey -> code.name.removePrefix("KEY")
            else -> code.name
        }
        if (keyText.isBlank()) return ""
        parts.add(keyText.uppercase())
        return parts.joinToString("+")
    }

    private fun notifyKeybindCaptured(combo: String) {
        val engine = webEngine ?: return
        Platform.runLater {
            try {
                val escaped = combo.replace("\\", "\\\\").replace("'", "\\'")
                engine.executeScript("window.dpsApp?.receiveKeybindCapture?.('$escaped')")
            } catch (e: Exception) {
                logger.warn("Failed to deliver keybind capture", e)
            }
        }
    }

    override fun start(stage: Stage) {
        DebugLogWriter.loadFromSettings()
        startWindowTitlePolling()
        stage.setOnCloseRequest {
            exitProcess(0)
        }
        val webView = WebView()
        val engine = webView.engine
        webEngine = engine

        val bridge = JSBridge(stage, dpsCalculator, hostServices, { cachedWindowTitle }, uiReadyNotifier)
        @Suppress("DEPRECATION")
        val injectBridge = {
            runCatching {
                val window = engine.executeScript("window")
                when (window) {
                    is JSObject -> {
                        window.setMember("javaBridge", bridge)
                        window.setMember("dpsData", this)
                    }
                    else -> {
                        val setMember = window.javaClass.methods.firstOrNull {
                            it.name == "setMember" && it.parameterCount == 2
                        } ?: error("setMember(String, Object) not found on window object")
                        setMember.invoke(window, "javaBridge", bridge)
                        setMember.invoke(window, "dpsData", this)
                    }
                }
            }.onFailure { error ->
                logger.warn("Failed to inject Java bridge into WebView", error)
            }
        }
        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            when (newState) {
                Worker.State.SUCCEEDED -> injectBridge()
                Worker.State.FAILED -> logger.error("WebView failed to load index.html")
                else -> Unit
            }
        }
        engine.load(javaClass.getResource("/index.html")?.toExternalForm())
        if (engine.loadWorker.state == Worker.State.SUCCEEDED) {
            injectBridge()
        }

        val storedKeybind = PropertyHandler.getProperty("dpsMeter.refreshKeybind") ?: "Ctrl+R"
        refreshKeybindValue = storedKeybind
        refreshKeybindManager.updateKeybind(storedKeybind)
        refreshKeybindManager.start()


        val scene = Scene(webView, 1600.0, 1000.0)
        scene.fill = Color.TRANSPARENT
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (keybindCaptureActive) {
                keybindCapturePending = buildCombo(event).ifBlank { keybindCapturePending }
                event.consume()
                return@addEventFilter
            }
            if (matchesKeybind(event, refreshKeybindValue)) {
                triggerRefreshFromKeybind()
                event.consume()
            }
        }
        scene.addEventFilter(KeyEvent.KEY_RELEASED) { event ->
            if (!keybindCaptureActive) return@addEventFilter
            if (event.code.isModifierKey) return@addEventFilter
            val captured = keybindCapturePending
            if (!captured.isNullOrBlank()) {
                notifyKeybindCaptured(captured)
            }
            keybindCaptureActive = false
            keybindCapturePending = null
            event.consume()
        }

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


        stage.show()
        Timeline(KeyFrame(Duration.millis(500.0), {
            dpsData = dpsCalculator.getDps()
        })).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    fun getDpsData(): String {
        return Json.encodeToString(dpsData)
    }

    fun isDebuggingMode(): Boolean {
        return debugMode
    }

    fun getBattleDetail(uid:Int):String{
        return Json.encodeToString(dpsData.map[uid]?.analyzedData)
    }

    fun getDetailsContext(): String {
        return Json.encodeToString(dpsCalculator.getDetailsContext())
    }

    fun getTargetDetails(targetId: Int, actorIdsJson: String?): String {
        val actorIds = actorIdsJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.decodeFromString<List<Int>>(it) }.getOrNull() }
            ?.toSet()
        return Json.encodeToString(dpsCalculator.getTargetDetails(targetId, actorIds))
    }

    fun getVersion():String{
        return version
    }

}
