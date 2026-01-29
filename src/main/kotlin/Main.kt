package com.tbread

import com.tbread.config.PcapCapturerConfig
import com.tbread.packet.*
import com.tbread.webview.BrowserApp
import javafx.application.Platform
import javafx.stage.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        println("thread dead ${t.name}")
        e.printStackTrace()
    }

    val channel = Channel<CapturedPayload>(Channel.UNLIMITED)
    val config = PcapCapturerConfig.loadFromProperties()

    val dataStorage = DataStorage()
    val calculator = DpsCalculator(dataStorage)

    val capturer = PcapCapturer(config, channel)
    val dispatcher = CaptureDispatcher(channel, dataStorage)

    launch(Dispatchers.Default) {
        dispatcher.run()
    }

    launch(Dispatchers.IO) {
        capturer.start()
    }

    Platform.startup {
        val browserApp = BrowserApp(calculator)
        browserApp.start(Stage())
    }
}
