package com.tbread

import java.io.BufferedReader
import java.io.InputStreamReader

object WindowsFirewallPrompt {
    private const val RULE_NAME = "Aion2 DPS Meter"

    fun requestInboundAccess() {
        if (!isWindows()) {
            return
        }

        val executablePath = currentExecutablePath() ?: return
        if (firewallRuleExists()) {
            return
        }

        val argumentList = buildString {
            append("advfirewall firewall add rule ")
            append("name=\\\"")
            append(RULE_NAME)
            append("\\\" ")
            append("dir=in action=allow ")
            append("program=\\\"")
            append(executablePath)
            append("\\\" ")
            append("enable=yes profile=any")
        }

        val command = "Start-Process -FilePath netsh -ArgumentList \"$argumentList\" -Verb RunAs"
        runProcess(listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command))
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    private fun currentExecutablePath(): String? {
        return ProcessHandle.current().info().command().orElse(null)
    }

    private fun firewallRuleExists(): Boolean {
        val result = runProcess(
            listOf("netsh", "advfirewall", "firewall", "show", "rule", "name=$RULE_NAME"),
            captureOutput = true
        )
        return result.output?.none { it.contains("No rules match", ignoreCase = true) } == true
    }

    private data class ProcessResult(val exitCode: Int, val output: List<String>?)

    private fun runProcess(command: List<String>, captureOutput: Boolean = false): ProcessResult {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = if (captureOutput) {
                BufferedReader(InputStreamReader(process.inputStream)).readLines()
            } else {
                null
            }
            val exitCode = process.waitFor()
            ProcessResult(exitCode, output)
        } catch (e: Exception) {
            ProcessResult(-1, null)
        }
    }
}
