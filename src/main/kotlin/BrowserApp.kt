package com.tbread

import javafx.application.Application
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.stage.StageStyle
import netscape.javascript.JSObject
import java.io.File

class BrowserApp : Application() {

    class JSBridge(private val stage: Stage) {
        fun moveWindow(x: Double, y: Double) {
            stage.x = x
            stage.y = y
        }
    }

//    private var xOffset = 0.0
//    private var yOffset = 0.0

    override fun start(stage: Stage) {
            val webView = WebView()
            val engine = webView.engine
            engine.load(javaClass.getResource("/index.html")?.toExternalForm())
//
//            webView.addEventFilter(MouseEvent.MOUSE_PRESSED) { e ->
//                xOffset = e.sceneX
//                yOffset = e.sceneY
//                e.consume()
//            }
//            webView.addEventFilter(MouseEvent.MOUSE_DRAGGED) { e ->
//                stage.x = e.sceneX - xOffset
//                stage.y = e.sceneY - yOffset
//                e.consume()
//            }

        val bridge = JSBridge(stage)
        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                val window = engine.executeScript("window") as JSObject
                window.setMember("javaBridge", bridge)
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
        }
}
