import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.graalvm.buildtools.native") version "0.10.3"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

group = "com.tbread"
version = "0.1.6"

val appVersion = version.toString()

fun computeMsiVersion(version: String): String {
    val base = version.substringBefore("-")
    val parts = base.split(".").mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return listOf(major, minor, patch).joinToString(".")
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.slf4j:slf4j-simple:1.7.26")
    implementation("net.java.dev.jna:jna:5.16.0")
    implementation("net.java.dev.jna:jna-platform:5.16.0")

    // Windows WebView2 backend via SWT Browser (Edge)
    implementation("org.eclipse.platform:org.eclipse.swt.win32.win32.x86_64:3.129.0")
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("com.tbread.Launcher")
            val runtimeClasspath = configurations.runtimeClasspath.get()
            val appClassPath = runtimeClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }

            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+AddAllCharsets")
            buildArgs.add("--no-fallback")
            buildArgs.add("--initialize-at-run-time=org.pcap4j.core.Pcaps")
            buildArgs.add("-H:ReflectionConfigurationFiles=${project.file("src/main/resources/native-image/reflect-config.json")}")
            buildArgs.addAll(listOf("--class-path", appClassPath))
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.tbread.Launcher"

        jvmArgs(
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseCompactObjectHeaders",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "-DdpsMeter.memProfileEnabled=true",
            "-DdpsMeter.memProfileInterval=30",
            "-DdpsMeter.memProfileTop=50",
            "-DdpsMeter.memProfileOutput=build/memory-profile",
            "-Dapple.laf.useScreenMenuBar=true"
        )

        nativeDistributions {
            windows {
                includeAllModules = true
                iconFile.set(project.file("src/main/resources/icon.ico"))
                shortcut = true
                menuGroup = "AION2 DPS Meter"
                upgradeUuid = "d1f8995e-c0af-4f01-9067-a69ee897361a"
                msiPackageVersion = computeMsiVersion(appVersion)
            }
            targetFormats(TargetFormat.Msi)
            packageName = "AION2 DPS Meter"
            packageVersion = appVersion
        }
    }
}
