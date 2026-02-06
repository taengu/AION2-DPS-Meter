package com.tbread.webview

import com.tbread.DpsCalculator
import com.tbread.entity.DpsData
import com.tbread.keyboard.RefreshKeybindManager
import com.tbread.logging.DebugLogWriter
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
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class BrowserApp(
    private val dpsCalculator: DpsCalculator,
    private val onUiReady: (() -> Unit)? = null
) : Application() {

    private val logger = LoggerFactory.getLogger(BrowserApp::class.java)
    private var webEngine: WebEngine? = null
    private val refreshKeybindManager = RefreshKeybindManager({ triggerRefreshFromKeybind() })
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
        val device: String?
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
            LocalPlayer.characterName = if (trimmed.isBlank()) null else trimmed
        }

        fun setTargetSelection(mode: String?) {
            dpsCalculator.setTargetSelectionModeById(mode)
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
                device = lockedDevice
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

        fun exitApp() {
          Platform.exit()     
          exitProcess(0)       
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

    override fun start(stage: Stage) {
        DebugLogWriter.loadFromSettings()
        startWindowTitlePolling()
        stage.setOnCloseRequest {
            exitProcess(0)
        }
        val webView = WebView()
        val engine = webView.engine
        webEngine = engine
        engine.load(javaClass.getResource("/index.html")?.toExternalForm())

        val bridge = JSBridge(stage, dpsCalculator, hostServices, { cachedWindowTitle }, uiReadyNotifier)
        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                val window = engine.executeScript("window") as JSObject
                window.setMember("javaBridge", bridge)
                window.setMember("dpsData", this)
            }
        }

        val storedKeybind = PropertyHandler.getProperty("dpsMeter.refreshKeybind") ?: "Ctrl+R"
        refreshKeybindManager.updateKeybind(storedKeybind)
        refreshKeybindManager.start()


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
