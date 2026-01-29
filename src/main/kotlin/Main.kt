package com.tbread

import com.tbread.config.PcapCapturerConfig
import com.tbread.packet.LocalPlayer
import com.tbread.packet.PcapCapturer
import com.tbread.packet.PropertyHandler
import com.tbread.packet.StreamAssembler
import com.tbread.packet.StreamProcessor
import com.tbread.webview.BrowserApp
import javafx.application.Application
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
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val config = PcapCapturerConfig.loadFromProperties()
    LocalPlayer.characterName = PropertyHandler.getProperty("character.name")

    val dataStorage = DataStorage()
    val processor = StreamProcessor(dataStorage)
    val assembler = StreamAssembler(processor)
    val capturer = PcapCapturer(config, channel)
    val calculator = DpsCalculator(dataStorage)

    launch(Dispatchers.Default) {
        for (chunk in channel) {
            assembler.processChunk(chunk)
        }
    }

    launch(Dispatchers.IO) {
        capturer.start()
    }

    Platform.startup{
        val browserApp = BrowserApp(calculator)
        browserApp.start(Stage())
    }
}

