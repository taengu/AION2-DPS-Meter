package com.tbread

import javafx.application.Application

class Launcher {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // This calls the main in your Main.kt
            com.tbread.main(args)
        }
    }
}