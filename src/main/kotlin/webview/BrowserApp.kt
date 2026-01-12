package com.tbread.webview

import com.tbread.DpsCalculator
import com.tbread.entity.DpsData
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.util.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import netscape.javascript.JSObject
import kotlin.system.exitProcess

class BrowserApp(private val dpsCalculator: DpsCalculator) : Application() {

    class JSBridge(private val stage: Stage,private val dpsCalculator: DpsCalculator) {
        fun moveWindow(x: Double, y: Double) {
            stage.x = x
            stage.y = y
        }

        fun resetDps(){
            dpsCalculator.resetDataStorage()
        }
    }

    @Volatile
    private var dpsData: DpsData = dpsCalculator.getDps()

    private val debugMode = true


    override fun start(stage: Stage) {
        stage.setOnCloseRequest {
            exitProcess(0)
        }
        val webView = WebView()
        val engine = webView.engine
        engine.load(javaClass.getResource("/index.html")?.toExternalForm())

        val bridge = JSBridge(stage,dpsCalculator)
        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                val window = engine.executeScript("window") as JSObject
                window.setMember("javaBridge", bridge)
                window.setMember("dpsData", this)
            }
        }


        val scene = Scene(webView, 1000.0, 800.0)
        scene.fill = Color.TRANSPARENT

        try {
            val pageField = engine.javaClass.getDeclaredField("page")
            pageField.isAccessible = true
            val page = pageField.get(engine)

            val setBgMethod = page.javaClass.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
            setBgMethod.isAccessible = true
            setBgMethod.invoke(page, 0)
        } catch (e: Exception) {
            println("리플렉션 실패: ${e.message}")
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
        return Json.encodeToString(dpsData.map)
    }

    fun isDebuggingMode(): Boolean {
        return debugMode
    }

}
