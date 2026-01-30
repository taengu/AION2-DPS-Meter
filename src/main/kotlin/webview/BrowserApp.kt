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
import javafx.scene.input.KeyEvent
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.util.Duration
import javafx.application.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import netscape.javascript.JSObject
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import kotlin.system.exitProcess

class BrowserApp(private val dpsCalculator: DpsCalculator) : Application() {

    private val logger = LoggerFactory.getLogger(BrowserApp::class.java)

    @Serializable
    data class ConnectionInfo(
        val ip: String?,
        val port: Int?,
        val locked: Boolean,
        val characterName: String?
    )

    @Serializable
    data class KeybindEvent(
        val key: String?,
        val code: String?,
        val ctrlKey: Boolean,
        val altKey: Boolean,
        val shiftKey: Boolean,
        val metaKey: Boolean,
    )

    private class JSBridge(
        private val stage: Stage,
        private val dpsCalculator: DpsCalculator,
        private val hostServices: HostServices,
        private val hotkeyManager: GlobalHotkeyManager?,
    ) {
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
            val fallbackPort = PropertyHandler.getProperty("server.port")?.toIntOrNull()
            val info = ConnectionInfo(
                ip = ip,
                port = lockedPort ?: fallbackPort,
                locked = lockedPort != null,
                characterName = LocalPlayer.characterName
            )
            return Json.encodeToString(info)
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

        fun setRefreshHotkey(keybind: String?, enabled: Boolean) {
            hotkeyManager?.updateHotkey(keybind, enabled)
        }
        fun exitApp() {
          hotkeyManager?.stop()
          Platform.exit()     
          exitProcess(0)       
        }
    }

    @Volatile
    private var dpsData: DpsData = dpsCalculator.getDps()

    private val debugMode = false

    private val version = "0.1.3"
    private var firewallPromptSocket: ServerSocket? = null
    private var hotkeyManager: GlobalHotkeyManager? = null


    override fun start(stage: Stage) {
        stage.setOnCloseRequest {
            closeFirewallPrompt()
            hotkeyManager?.stop()
            exitProcess(0)
        }
        ensureWindowsFirewallPrompt()
        val webView = WebView()
        val engine = webView.engine
        engine.load(javaClass.getResource("/index.html")?.toExternalForm())

        hotkeyManager = if (isWindows()) {
            try {
                GlobalHotkeyManager {
                    try {
                        engine.executeScript("window.dpsApp?.triggerRefreshKeybind?.()")
                    } catch (e: Exception) {
                        logger.warn("Failed to trigger refresh keybind from global hotkey.", e)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to initialize global hotkey manager.", e)
                null
            }
        } else {
            null
        }
        val bridge = JSBridge(stage, dpsCalculator, hostServices, hotkeyManager)
        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                val window = engine.executeScript("window") as JSObject
                window.setMember("javaBridge", bridge)
                window.setMember("dpsData", this)
            }
        }


        val scene = Scene(webView, 1600.0, 1000.0)
        scene.fill = Color.TRANSPARENT
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            val payload = KeybindEvent(
                key = event.text?.takeIf { it.isNotBlank() } ?: event.code.name,
                code = event.code.name,
                ctrlKey = event.isControlDown,
                altKey = event.isAltDown,
                shiftKey = event.isShiftDown,
                metaKey = event.isMetaDown,
            )
            val handled = try {
                val json = Json.encodeToString(payload)
                engine.executeScript(
                    "window.dpsApp?.handleHostKeybindEvent?.($json)"
                ) as? Boolean
            } catch (e: Exception) {
                null
            }
            if (handled == true) {
                event.consume()
            }
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

    private fun ensureWindowsFirewallPrompt() {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!osName.contains("windows")) {
            return
        }
        if (firewallPromptSocket != null) {
            return
        }
        try {
            firewallPromptSocket = ServerSocket(0).apply {
                reuseAddress = true
            }
            logger.info(
                "Opened firewall prompt socket on port {} to trigger Windows firewall dialog.",
                firewallPromptSocket?.localPort
            )
        } catch (e: Exception) {
            logger.warn("Failed to open firewall prompt socket.", e)
        }
    }

    private fun closeFirewallPrompt() {
        try {
            firewallPromptSocket?.close()
        } catch (e: Exception) {
            logger.warn("Failed to close firewall prompt socket.", e)
        } finally {
            firewallPromptSocket = null
        }
    }

    private fun isWindows(): Boolean {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        return osName.contains("windows")
    }

    private data class Hotkey(val modifiers: Int, val keyCode: Int)

    private inner class GlobalHotkeyManager(
        private val onHotkey: () -> Unit
    ) {
        private val pmRemove = 0x0001
        private val running = AtomicBoolean(false)
        private val registeredId = AtomicReference<Int?>(null)
        private val taskQueue = LinkedBlockingQueue<() -> Unit>()
        private var thread: Thread? = null
        private var currentHotkey: Hotkey? = null

        fun updateHotkey(keybind: String?, enabled: Boolean) {
            if (!enabled || keybind.isNullOrBlank()) {
                enqueue { unregisterHotkey() }
                return
            }
            val parsed = parseKeybind(keybind) ?: return
            if (parsed == currentHotkey) return
            enqueue { registerHotkey(parsed) }
        }

        fun stop() {
            enqueue {
                unregisterHotkey()
                running.set(false)
            }
            thread?.interrupt()
            thread = null
        }

        private fun enqueue(task: () -> Unit) {
            if (thread == null) {
                startThread()
            }
            taskQueue.offer(task)
        }

        private fun startThread() {
            if (running.getAndSet(true)) return
            thread = Thread({
                val msg = WinUser.MSG()
                while (running.get()) {
                    while (User32.INSTANCE.PeekMessage(msg, null, 0, 0, pmRemove)) {
                        if (msg.message == WinUser.WM_HOTKEY) {
                            Platform.runLater {
                                onHotkey()
                            }
                        }
                    }
                    while (true) {
                        val task = taskQueue.poll() ?: break
                        task.invoke()
                    }
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        // continue loop
                    }
                }
            }, "global-hotkey-listener")
            thread?.isDaemon = true
            thread?.start()
        }

        private fun registerHotkey(hotkey: Hotkey) {
            unregisterHotkey()
            val id = 1
            val registered = User32.INSTANCE.RegisterHotKey(
                null,
                id,
                hotkey.modifiers,
                hotkey.keyCode
            )
            if (registered) {
                registeredId.set(id)
                currentHotkey = hotkey
            } else {
                logger.warn("Failed to register global hotkey {}", hotkey)
            }
        }

        private fun unregisterHotkey() {
            val id = registeredId.getAndSet(null) ?: return
            User32.INSTANCE.UnregisterHotKey(null, id)
            currentHotkey = null
        }

        private fun parseKeybind(keybind: String): Hotkey? {
            val parts = keybind.split("+").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            var modifiers = 0
            var key: String? = null
            for (part in parts) {
                when (part.lowercase()) {
                    "ctrl", "control" -> modifiers = modifiers or WinUser.MOD_CONTROL
                    "alt" -> modifiers = modifiers or WinUser.MOD_ALT
                    "shift" -> modifiers = modifiers or WinUser.MOD_SHIFT
                    "meta", "cmd", "command", "win", "windows" ->
                        modifiers = modifiers or WinUser.MOD_WIN
                    else -> key = part
                }
            }
            val keyCode = key?.let { mapKeyToVirtualKey(it) } ?: return null
            return Hotkey(modifiers, keyCode)
        }

        private fun mapKeyToVirtualKey(key: String): Int? {
            val normalized = key.trim()
            if (normalized.length == 1) {
                val charCode = normalized.uppercase()[0].code
                return when {
                    charCode in 0x30..0x39 -> charCode
                    charCode in 0x41..0x5A -> charCode
                    else -> null
                }
            }
            val upper = normalized.uppercase()
            if (upper.startsWith("F")) {
                val number = upper.removePrefix("F").toIntOrNull()
                if (number != null && number in 1..24) {
                    return WinUser.VK_F1 + (number - 1)
                }
            }
            if (upper.startsWith("NUMPAD")) {
                val num = upper.removePrefix("NUMPAD").toIntOrNull()
                if (num != null && num in 0..9) {
                    return WinUser.VK_NUMPAD0 + num
                }
            }
            return when (upper) {
                "SPACE" -> WinUser.VK_SPACE
                "TAB" -> WinUser.VK_TAB
                "ENTER", "RETURN" -> WinUser.VK_RETURN
                "ESC", "ESCAPE" -> WinUser.VK_ESCAPE
                "BACKSPACE" -> WinUser.VK_BACK
                "DELETE" -> WinUser.VK_DELETE
                "HOME" -> WinUser.VK_HOME
                "END" -> WinUser.VK_END
                "PAGEUP", "PAGE UP" -> WinUser.VK_PRIOR
                "PAGEDOWN", "PAGE DOWN" -> WinUser.VK_NEXT
                "UP" -> WinUser.VK_UP
                "DOWN" -> WinUser.VK_DOWN
                "LEFT" -> WinUser.VK_LEFT
                "RIGHT" -> WinUser.VK_RIGHT
                else -> null
            }
        }
    }

}
