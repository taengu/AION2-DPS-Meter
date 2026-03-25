package com.tbread.packet

import org.slf4j.LoggerFactory
import java.io.*
import java.util.*

object PropertyHandler {
    private val props = Properties()
    private const val PROPERTIES_FILE_NAME = "settings.properties"
    private val logger = LoggerFactory.getLogger(PropertyHandler::class.java)

    private val settingsFile: File by lazy {
        val appdata = System.getenv("APPDATA") ?: System.getProperty("user.home")
        val dir = File(appdata, "AionDPS")
        dir.mkdirs()
        val target = File(dir, PROPERTIES_FILE_NAME)

        // Migrate from old location (install directory) if the AppData file doesn't exist yet
        if (!target.exists()) {
            val legacy = File(PROPERTIES_FILE_NAME)
            if (legacy.exists() && legacy.length() > 0) {
                try {
                    legacy.copyTo(target)
                    logger.info("Migrated settings from {} to {}", legacy.absolutePath, target.absolutePath)
                } catch (e: IOException) {
                    logger.error("Failed to migrate settings file", e)
                }
            }
        }
        target
    }

    init {
        loadProperties(settingsFile)
    }

    fun loadProperties(file: File) {
        try {
            InputStreamReader(FileInputStream(file), Charsets.UTF_8).use { reader ->
                props.load(reader)
            }
        } catch (_: FileNotFoundException) {
            logger.info("Settings file not found; creating a new one at {}", file.absolutePath)
            file.createNewFile()
        } catch (_: IOException) {
            logger.error("Failed to read settings file.")
        }
    }

    @Deprecated("Use loadProperties(File) instead", ReplaceWith("loadProperties(File(fname))"))
    fun loadProperties(fname: String) {
        loadProperties(File(fname))
    }

    private fun save(){
        OutputStreamWriter(FileOutputStream(settingsFile), Charsets.UTF_8).use { writer ->
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

    fun clearAll() {
        props.clear()
        save()
    }

}
