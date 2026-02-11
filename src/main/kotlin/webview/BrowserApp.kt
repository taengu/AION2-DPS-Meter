package com.tbread.webview

import com.tbread.DpsCalculator
import com.tbread.entity.DpsData
import com.tbread.logging.UnifiedLogger
import com.tbread.packet.CaptureDispatcher
import com.tbread.packet.CombatPortDetector
import com.tbread.packet.LocalPlayer
import com.tbread.packet.PropertyHandler
import com.tbread.windows.WindowTitleDetector
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.swt.SWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.browser.BrowserFunction
import org.eclipse.swt.layout.FillLayout
import org.eclipse.swt.widgets.DirectoryDialog
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Shell
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import com.sun.net.httpserver.HttpServer

class BrowserApp(
    private val dpsCalculator: DpsCalculator,
    private val captureDispatcher: CaptureDispatcher,
    private val onUiReady: (() -> Unit)? = null
) {
    private val logger = LoggerFactory.getLogger(BrowserApp::class.java)

    @Serializable
    data class ConnectionInfo(
        val ip: String?,
        val port: Int?,
        val locked: Boolean,
        val characterName: String?,
        val device: String?,
        val localPlayerId: Long?
    )

    @Volatile
    private var dpsData: DpsData = dpsCalculator.getDps()

    @Volatile
    private var cachedWindowTitle: String? = null

    private val uiReadyReported = AtomicBoolean(false)

    private val scheduler = Executors.newScheduledThreadPool(2)
    private var resourceServer: HttpServer? = null

    private fun notifyUiReady() {
        if (uiReadyReported.compareAndSet(false, true)) {
            onUiReady?.invoke()
        }
    }

    fun start() {
        UnifiedLogger.loadDebugFromSettings()
        startBackgroundRefresh()

        val display = Display()
        val shell = Shell(display, SWT.NO_TRIM)
        shell.layout = FillLayout()
        shell.isAlwaysOnTop = true
        shell.setSize(1600, 1000)
        shell.text = "Aion2 Dps Overlay"

        val browser = Browser(shell, SWT.NONE)
        installBridge(display, shell, browser)

        val baseUrl = startResourceServer()
        browser.addProgressListener(object : org.eclipse.swt.browser.ProgressAdapter() {
            override fun completed(event: org.eclipse.swt.browser.ProgressEvent?) {
                injectBridgeObjects(browser)
                notifyUiReady()
            }
        })
        browser.setUrl("$baseUrl/index.html")

        shell.open()
        while (!shell.isDisposed) {
            if (!display.readAndDispatch()) {
                display.sleep()
            }
        }

        shutdown()
        display.dispose()
    }

    private fun startBackgroundRefresh() {
        scheduler.scheduleAtFixedRate({
            runCatching {
                dpsData = dpsCalculator.getDps()
            }
        }, 0, 500, TimeUnit.MILLISECONDS)

        scheduler.scheduleAtFixedRate({
            runCatching {
                cachedWindowTitle = WindowTitleDetector.findAion2WindowTitle()
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun startResourceServer(): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val rawPath = exchange.requestURI.path.ifBlank { "/index.html" }
            val normalized = if (rawPath == "/") "/index.html" else rawPath
            val resourcePath = if (normalized.startsWith("/")) normalized else "/$normalized"
            val stream = javaClass.getResourceAsStream(resourcePath)
            if (stream == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }

            val bytes = stream.use { it.readAllBytes() }
            exchange.responseHeaders.add("Content-Type", contentTypeFor(resourcePath))
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        resourceServer = server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun contentTypeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun installBridge(display: Display, shell: Shell, browser: Browser) {
        val keyHookEvent = KeyHookEvent {
            display.asyncExec {
                if (!browser.isDisposed) {
                    browser.execute("window.dispatchEvent(new CustomEvent('nativeResetHotKey'));")
                }
            }
        }

        object : BrowserFunction(browser, "nativeBridgeInvoke") {
            override fun function(arguments: Array<Any>): Any? {
                if (arguments.isEmpty()) return null
                val method = arguments.firstOrNull()?.toString() ?: return null
                val args = arguments.drop(1)

                return when (method) {
                "moveWindow" -> {
                    val x = args.getOrNull(0)?.toString()?.toDoubleOrNull() ?: return false
                    val y = args.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return false
                    shell.location = org.eclipse.swt.graphics.Point(x.toInt(), y.toInt())
                    true
                }
                "resetDps" -> {
                    dpsCalculator.resetDataStorage()
                    true
                }
                "resetAutoDetection" -> {
                    CombatPortDetector.reset()
                    true
                }
                "setCharacterName" -> {
                    val trimmed = args.getOrNull(0)?.toString()?.trim().orEmpty()
                    LocalPlayer.characterName = trimmed.ifBlank { null }
                    true
                }
                "setLocalPlayerId" -> {
                    LocalPlayer.playerId = args.getOrNull(0)?.toString()?.toLongOrNull()
                    true
                }
                "setTargetSelection" -> {
                    dpsCalculator.setTargetSelectionModeById(args.getOrNull(0)?.toString())
                    true
                }
                "restartTargetSelection" -> {
                    dpsCalculator.restartTargetSelection()
                    true
                }
                "setAllTargetsWindowMs" -> {
                    args.getOrNull(0)?.toString()?.toLongOrNull()?.let { dpsCalculator.setAllTargetsWindowMs(it) }
                    true
                }
                "setTrainSelectionMode" -> {
                    dpsCalculator.setTrainSelectionModeById(args.getOrNull(0)?.toString())
                    true
                }
                "setTargetSelectionWindowMs" -> {
                    args.getOrNull(0)?.toString()?.toLongOrNull()?.let { dpsCalculator.setTargetSelectionWindowMs(it) }
                    true
                }
                "bindLocalActorId" -> {
                    args.getOrNull(0)?.toString()?.toLongOrNull()?.let { dpsCalculator.bindLocalActorId(it) }
                    true
                }
                "bindLocalNickname" -> {
                    val actor = args.getOrNull(0)?.toString()?.toLongOrNull()
                    val nickname = args.getOrNull(1)?.toString()
                    if (actor != null) dpsCalculator.bindLocalNickname(actor, nickname)
                    true
                }
                "getConnectionInfo" -> Json.encodeToString(
                    ConnectionInfo(
                        ip = PropertyHandler.getProperty("server.ip"),
                        port = CombatPortDetector.currentPort(),
                        locked = CombatPortDetector.currentPort() != null,
                        characterName = LocalPlayer.characterName,
                        device = CombatPortDetector.currentDevice(),
                        localPlayerId = LocalPlayer.playerId
                    )
                )
                "getLastParsedAtMs" -> CombatPortDetector.lastParsedAtMs()
                "getAion2WindowTitle" -> cachedWindowTitle
                "openBrowser" -> {
                    val url = args.getOrNull(0)?.toString() ?: return false
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                    true
                }
                "readResource" -> {
                    val path = args.getOrNull(0)?.toString().orEmpty()
                    val normalized = if (path.startsWith("/")) path else "/$path"
                    javaClass.getResourceAsStream(normalized)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                }
                "getSetting" -> PropertyHandler.getProperty(args.getOrNull(0)?.toString().orEmpty())
                "setSetting" -> {
                    val key = args.getOrNull(0)?.toString().orEmpty()
                    val value = args.getOrNull(1)?.toString().orEmpty()
                    PropertyHandler.setProperty(key, value)
                    true
                }
                "setDebugLoggingEnabled" -> {
                    val enabled = args.getOrNull(0)?.toString()?.toBooleanStrictOrNull() ?: false
                    UnifiedLogger.setDebugEnabled(enabled)
                    PropertyHandler.setProperty(UnifiedLogger.DEBUG_SETTING_KEY, enabled.toString())
                    true
                }
                "logDebug" -> {
                    UnifiedLogger.debug(logger, "UI {}", args.getOrNull(0)?.toString().orEmpty())
                    true
                }
                "isRunningViaGradle" -> (System.getProperty("org.gradle.appname") != null)
                "isRunningFromIde" -> (
                    System.getProperty("idea.version") != null ||
                        System.getProperty("idea.active") != null ||
                        System.getProperty("idea.platform.prefix") != null
                    )
                "getParsingBacklog" -> captureDispatcher.getParsingBacklog()
                "exitApp" -> {
                    shutdown()
                    exitProcess(0)
                }
                "setHotkey" -> {
                    val modifiers = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return false
                    val keyCode = args.getOrNull(1)?.toString()?.toIntOrNull() ?: return false
                    keyHookEvent.setHotkey(modifiers, keyCode)
                    true
                }
                "getCurrentHotKey" -> keyHookEvent.getCurrentHotKey()
                "captureScreenshotToClipboard" -> false
                "captureScreenshotToFile" -> false
                "getDefaultScreenshotFolder" -> {
                    val userHome = System.getProperty("user.home") ?: "."
                    Paths.get(userHome, "Pictures", "AION2 DPS Meter").toString()
                }
                "chooseScreenshotFolder" -> {
                    val dialog = DirectoryDialog(shell)
                    dialog.text = "Select screenshot folder"
                    dialog.open()
                }
                "notifyUiReady" -> {
                    notifyUiReady()
                    true
                }
                "getDpsData" -> Json.encodeToString(dpsData)
                "isDebuggingMode" -> false
                "getBattleDetail" -> {
                    val uid = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return "{}"
                    Json.encodeToString(dpsData.map[uid]?.analyzedData)
                }
                "getDetailsContext" -> Json.encodeToString(dpsCalculator.getDetailsContext())
                "getTargetDetails" -> {
                    val targetId = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return "{}"
                    val actorIds = args.getOrNull(1)?.toString()
                        ?.takeIf { s -> s.isNotBlank() }
                        ?.let { s -> runCatching { Json.decodeFromString<List<Int>>(s) }.getOrNull() }
                        ?.toSet()
                    Json.encodeToString(dpsCalculator.getTargetDetails(targetId, actorIds))
                }
                "getVersion" -> "0.1.6"
                else -> null
                }
            }
        }
    }

    private fun injectBridgeObjects(browser: Browser) {
        browser.execute(
            """
            window.javaBridge = new Proxy({}, {
              get: (_, prop) => (...args) => nativeBridgeInvoke(String(prop), ...args)
            });
            window.dpsData = new Proxy({}, {
              get: (_, prop) => (...args) => nativeBridgeInvoke(String(prop), ...args)
            });
            """.trimIndent()
        )
    }

    private fun shutdown() {
        runCatching { resourceServer?.stop(0) }
        runCatching { scheduler.shutdownNow() }
    }
}
