package com.tbread.packet

import org.slf4j.LoggerFactory
import java.io.*
import java.util.*

object PropertyHandler {
    private val props = Properties()
    private const val PROPERTIES_FILE_NAME = "settings.properties"
    private val logger = LoggerFactory.getLogger(PropertyHandler::class.java)

    init {
        loadProperties(PROPERTIES_FILE_NAME)
    }

    fun loadProperties(fname: String) {
        try {
            InputStreamReader(FileInputStream(fname), Charsets.UTF_8).use { reader ->
                props.load(reader)
            }
        } catch (_: FileNotFoundException) {
            logger.info("Settings file not found; creating a new one.")
            FileOutputStream(fname).use {}
        } catch (_: IOException) {
            logger.error("Failed to read settings file.")
        }
    }

    private fun save(){
        OutputStreamWriter(FileOutputStream(PROPERTIES_FILE_NAME), Charsets.UTF_8).use { writer ->
            props.store(writer, "settings")
        }
    }

    fun getProperty(key: String): String? {
        return props.getProperty(key)
    }

    fun getProperty(key: String, defaultValue: String): String? {
        return props.getProperty(key, defaultValue)
    }

    fun setProperty(key:String,value:String){
        props.setProperty(key,value)
        save()
    }

}
