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

fun computePackageVersion(version: String): String {
    val base = version.substringBefore("-")
    val parts = base.split(".").mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    val build = if (version.contains("-")) 0 else 1
    return listOf(major, minor, patch, build).joinToString(".")
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

    val javafxVersion = "25"
    implementation("org.openjfx:javafx-base:$javafxVersion:win")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:win")
    implementation("org.openjfx:javafx-controls:$javafxVersion:win")
    implementation("org.openjfx:javafx-web:$javafxVersion:win")
    implementation("org.openjfx:javafx-media:$javafxVersion:win")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.slf4j:slf4j-simple:1.7.26")
    implementation("net.java.dev.jna:jna:5.16.0")
    implementation("net.java.dev.jna:jna-platform:5.16.0")
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("com.tbread.Launcher")
            val runtimeClasspath = configurations.runtimeClasspath.get()
            val javafxModuleNames = setOf(
                "javafx-base",
                "javafx-graphics",
                "javafx-controls",
                "javafx-web",
                "javafx-media"
            )
            val javafxModulePath = runtimeClasspath.files
                .filter { file -> javafxModuleNames.any { name -> file.name.startsWith(name) } }
                .joinToString(File.pathSeparator) { it.absolutePath }
            val appClassPath = runtimeClasspath.files
                .filterNot { file -> javafxModuleNames.any { name -> file.name.startsWith(name) } }
                .joinToString(File.pathSeparator) { it.absolutePath }

            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+AddAllCharsets")
            buildArgs.add("-Dprism.fontdir=C:\\Windows\\Fonts")
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED,javafx.base,javafx.graphics,javafx.controls,javafx.web,javafx.media")

            // GraalVM Native Image Memory Limits
            buildArgs.add("-R:MaxHeapSize=256m")

            // ----- Build Time Initializations -----
            buildArgs.add("--initialize-at-build-time=javafx,com.sun.javafx,com.sun.javafx.tk.quantum.PrimaryTimer,com.sun.scenario.animation.SplineInterpolator,com.sun.scenario.animation.StepInterpolator,kotlinx.coroutines,kotlinx.coroutines.internal.ThreadContextKt\$countAll\$1,kotlinx.coroutines.internal.ThreadContextKt\$updateState\$1,kotlinx.coroutines.scheduling.DefaultScheduler,kotlin.coroutines.ContinuationInterceptor\$Key")
            buildArgs.add("--initialize-at-build-time=com.sun.scenario.effect.Offset")
            buildArgs.add("--initialize-at-build-time=com.sun.scenario.animation")

            // ----- Run Time Initializations -----
            buildArgs.add("--initialize-at-run-time=org.pcap4j.core.Pcaps")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.scene.control")
            buildArgs.add("--initialize-at-run-time=javafx.scene.web")
            buildArgs.add("--initialize-at-run-time=com.sun.java.scene.web")
            buildArgs.add("--initialize-at-run-time=com.sun.webkit")
            buildArgs.add("--initialize-at-run-time=javafx.stage.Screen")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.tk")
            buildArgs.add("--initialize-at-run-time=com.sun.glass")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.font")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.text")

            // ----- Include Resources -----
            buildArgs.add("-H:IncludeResources=.*\\.html$|.*\\.js$|.*\\.css$|.*\\.png$|.*\\.ico$|.*\\.json$|^prism_sw\\\\.dll$")
            buildArgs.add(
                "-H:ReflectionConfigurationFiles=${project.file("src/main/resources/native-image/reflect-config.json")}"
            )
            buildArgs.add("-H:JNIConfigurationFiles=${project.file("src/main/resources/native-image/jni-config.json")}")

            buildArgs.addAll(listOf("--class-path", appClassPath))
            buildArgs.addAll(listOf("--module-path", javafxModulePath))
            buildArgs.addAll(
                listOf(
                    "--add-modules",
                    "jdk.jsobject,jdk.net,javafx.base,javafx.controls,javafx.web,javafx.graphics,javafx.media"
                )
            )
        }
    }
}

val javafxNativeLibPatterns = listOf(
    "**/jfxwebkit*.dll",
    "**/jfxwebkit*.pak",
    "**/icudtl.dat",
    "**/prism_sw.dll",
    "**/prism_d3d.dll",
    "**/prism_common.dll",
    "**/glass.dll",
    "**/javafx_font.dll",
    "**/javafx_iio.dll",
    "**/jfxmedia.dll"
)

tasks.named("nativeCompile").configure {
    doLast {
        val outputDir = layout.buildDirectory.dir("native/nativeCompile").get().asFile
        val runtimeJars = configurations.runtimeClasspath.get().files.filter { it.extension == "jar" }
        runtimeJars.forEach { jar ->
            copy {
                from(zipTree(jar)) {
                    include(javafxNativeLibPatterns)
                }
                into(outputDir)
            }
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
            "--add-opens=javafx.web/com.sun.webkit.dom=ALL-UNNAMED",
            "--add-exports=javafx.web/com.sun.webkit.dom=ALL-UNNAMED",
            "--add-opens=jdk.jsobject/netscape.javascript=ALL-UNNAMED",
            "-Dprism.order=sw",
            "-DdpsMeter.memProfileEnabled=true",
            "-DdpsMeter.memProfileInterval=30",
            "-DdpsMeter.memProfileTop=50",
            "-DdpsMeter.memProfileOutput=build/memory-profile",
            "-Dapple.laf.useScreenMenuBar=true",

            // JVM Memory Shrinking Flags for UI Runtime / MSI Package
            "-Xmx256m",
            "-XX:+UseG1GC",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:MinHeapFreeRatio=10",
            "-XX:+ShrinkHeapInSteps"
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