package com.tbread

import com.tbread.config.PcapCapturerConfig
import com.tbread.packet.PcapCapturer
import com.tbread.packet.StreamAssembler
import com.tbread.packet.StreamProcessor
import com.tbread.webview.BrowserApp
import javafx.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        println("thread dead ${t.name}")
        e.printStackTrace()
    }
//    var deviceIdx = PropertyHandler.getProperty("device")
//    if (deviceIdx == null) {
//        println("사용할 네트워크 기기 번호를 입력하세요.")
//        PcapCapturer.printDevices()
//        deviceIdx = run {
//            while (true) {
//                readLine()?.toIntOrNull()?.takeIf { it in 0..<PcapCapturer.getDeviceSize() }?.let { return@run it }
//                println("유효하지 않은 값입니다. 다시 입력해주세요.")
//            }
//        }.toString()
//        PropertyHandler.setProperty("device", deviceIdx)
//    }
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val config = PcapCapturerConfig.loadFromProperties()

    val dataStorage = DataStorage()
    val processor = StreamProcessor(dataStorage)
    val assembler = StreamAssembler(processor)
    val capturer = PcapCapturer(config, channel)

    launch(Dispatchers.Default) {
        for (chunk in channel) {
            assembler.processChunk(chunk)
        }
    }

    launch(Dispatchers.IO) {
        capturer.start()
    }

    Application.launch(BrowserApp::class.java)
}


