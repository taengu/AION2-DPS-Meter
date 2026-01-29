package com.tbread.webview

import com.tbread.DpsCalculator
import com.tbread.entity.DpsData
import com.tbread.packet.CombatPortDetector
import com.tbread.packet.LocalPlayer
import com.tbread.packet.PropertyHandler
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.HostServices
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.util.Duration
import javafx.application.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import netscape.javascript.JSObject
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

class BrowserApp(private val dpsCalculator: DpsCalculator) : Application() {

    private val logger = LoggerFactory.getLogger(BrowserApp::class.java)

    @kotlinx.serialization.Serializable
    data class LockedEndpoint(val ip: String? = null, val port: Int? = null)

    class JSBridge(private val stage: Stage,private val dpsCalculator: DpsCalculator,private val hostServices: HostServices,) {
        fun moveWindow(x: Double, y: Double) {
            stage.x = x
            stage.y = y
        }

        fun resetDps(){
            dpsCalculator.resetDataStorage()
        }

        fun resetAutoDetection() {
            CombatPortDetector.reset()
            dpsCalculator.resetDataStorage()
        }

        fun getLockedEndpoint(): String {
            val lock = CombatPortDetector.currentLock()
            return Json.encodeToString(LockedEndpoint(lock?.ip, lock?.port))
        }

        fun getCharacterName(): String? {
            return LocalPlayer.characterName
        }

        fun setCharacterName(name: String?) {
            val trimmed = name?.trim()?.ifBlank { null }
            LocalPlayer.characterName = trimmed
            PropertyHandler.setProperty("character.name", trimmed ?: "")
        }

        fun openBrowser(url: String) {
            try {
                hostServices.showDocument(url)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        fun exitApp() {
          Platform.exit()     
          exitProcess(0)       
        }
    }

    @Volatile
    private var dpsData: DpsData = dpsCalculator.getDps()

    private val debugMode = false

    private val version = "0.2.4"


    override fun start(stage: Stage) {
        stage.setOnCloseRequest {
            exitProcess(0)
        }
        val webView = WebView()
        val engine = webView.engine
        engine.load(javaClass.getResource("/index.html")?.toExternalForm())

        val bridge = JSBridge(stage, dpsCalculator, hostServices)
        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                val window = engine.executeScript("window") as JSObject
                window.setMember("javaBridge", bridge)
                window.setMember("dpsData", this)
            }
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
            logger.error("리플렉션 실패",e)
        }

        stage.initStyle(StageStyle.TRANSPARENT)
        stage.scene = scene
        stage.isAlwaysOnTop = true
        stage.title = "Aion2 Dps Overlay"

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

    fun getVersion():String{
        return version
    }

}
