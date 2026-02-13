package com.tbread.profiling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.management.ObjectName

object MemoryProfiler {
    private val logger = LoggerFactory.getLogger(MemoryProfiler::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val fileFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val mbeanServer = ManagementFactory.getPlatformMBeanServer()
    private val diagnosticObjectName = ObjectName("com.sun.management:type=DiagnosticCommand")

    @Volatile
    private var profilerJob: Job? = null

    data class Config(
        val enabled: Boolean = false,
        val intervalSeconds: Long = 60,
        val outputDir: String = "memory-profile",
        val topClasses: Int = 30
    )

    fun fromArgs(args: Array<String>): Config {
        var enabled = System.getProperty("dpsMeter.memProfileEnabled")?.toBooleanStrictOrNull() ?: false
        var intervalSeconds = System.getProperty("dpsMeter.memProfileInterval")
            ?.toLongOrNull()
            ?.coerceAtLeast(5)
            ?: 60L
        var outputDir = System.getProperty("dpsMeter.memProfileOutput")?.ifBlank { null } ?: "memory-profile"
        var topClasses = System.getProperty("dpsMeter.memProfileTop")
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 30

        args.forEach { arg ->
            when {
                arg == "--mem-profile" -> enabled = true
                arg.startsWith("--mem-profile=") -> {
                    enabled = true
                    intervalSeconds = arg.substringAfter('=').toLongOrNull()?.coerceAtLeast(5) ?: intervalSeconds
                }
                arg.startsWith("--mem-profile-interval=") -> {
                    enabled = true
                    intervalSeconds = arg.substringAfter('=').toLongOrNull()?.coerceAtLeast(5) ?: intervalSeconds
                }
                arg.startsWith("--mem-profile-output=") -> {
                    enabled = true
                    outputDir = arg.substringAfter('=').ifBlank { outputDir }
                }
                arg.startsWith("--mem-profile-top=") -> {
                    enabled = true
                    topClasses = arg.substringAfter('=').toIntOrNull()?.coerceAtLeast(1) ?: topClasses
                }
            }
        }

        return Config(
            enabled = enabled,
            intervalSeconds = intervalSeconds,
            outputDir = outputDir,
            topClasses = topClasses
        )
    }

    fun start(scope: CoroutineScope, config: Config) {
        if (!config.enabled || profilerJob != null) return

        val outputDirectory = File(config.outputDir).apply { mkdirs() }
        val outputFile = File(outputDirectory, "memory-profile-${LocalDateTime.now().format(fileFormatter)}.log")

        profilerJob = scope.launch(Dispatchers.IO) {
            logger.info(
                "Memory profiling enabled. interval={}s, topClasses={}, output={}",
                config.intervalSeconds,
                config.topClasses,
                outputFile.absolutePath
            )
            while (isActive) {
                writeSnapshot(outputFile, config.topClasses)
                delay(config.intervalSeconds * 1_000)
            }
        }
    }

    private fun writeSnapshot(outputFile: File, topClasses: Int) {
        val now = LocalDateTime.now().format(timestampFormatter)
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val nonHeap = ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage
        val pools = ManagementFactory.getMemoryPoolMXBeans()
        val bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)

        val sb = StringBuilder()
        sb.appendLine("=== Memory Snapshot: $now ===")
        sb.appendLine(
            "Heap: used=${heap.used} committed=${heap.committed} max=${heap.max} | NonHeap: used=${nonHeap.used} committed=${nonHeap.committed} max=${nonHeap.max}"
        )
        sb.appendLine("Memory Pools:")
        pools.forEach { pool ->
            val usage = pool.usage
            val label = if (pool.type == MemoryType.HEAP) "HEAP" else "NON_HEAP"
            sb.appendLine("- [$label] ${pool.name}: used=${usage?.used} committed=${usage?.committed} max=${usage?.max}")
        }

        if (bufferPools.isNotEmpty()) {
            sb.appendLine("Buffer Pools:")
            bufferPools.forEach { pool ->
                sb.appendLine("- ${pool.name}: count=${pool.count} used=${pool.memoryUsed} totalCapacity=${pool.totalCapacity}")
            }
        }

        val histogram = classHistogram(topClasses)
        sb.appendLine("Top classes by memory:")
        sb.appendLine(histogram)
        sb.appendLine()

        outputFile.appendText(sb.toString())
    }

    private fun classHistogram(topClasses: Int): String {
        return try {
            val result = mbeanServer.invoke(
                diagnosticObjectName,
                "gcClassHistogram",
                arrayOf(arrayOf("-all")),
                arrayOf("[Ljava.lang.String;")
            ) as? String ?: return "DiagnosticCommand returned no data"

            val lines = result.lineSequence().toList()
            if (lines.size <= 5) return result
            val header = lines.take(3)
            val body = lines.drop(3).take(topClasses)
            (header + body).joinToString(System.lineSeparator())
        } catch (e: Exception) {
            "Class histogram unavailable (${e.javaClass.simpleName}: ${e.message}). If you are running a GraalVM native binary, run the JVM distribution for deep heap profiling."
        }
    }
}

