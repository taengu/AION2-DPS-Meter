import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.graalvm.buildtools.native") version "0.10.3"
    // REMOVED the failing JavaFX plugin - we handle it via jvmArgs/dependencies
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

group = "com.tbread"
version = "0.1.6"

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

    // Explicit JavaFX dependencies for Windows
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
            // Critical for UI and async behavior
            buildArgs.add("--initialize-at-build-time=javafx,com.sun.javafx,com.sun.javafx.tk.quantum.PrimaryTimer,com.sun.scenario.animation.SplineInterpolator,com.sun.scenario.animation.StepInterpolator,kotlinx.coroutines,kotlinx.coroutines.internal.ThreadContextKt\$countAll\$1,kotlinx.coroutines.internal.ThreadContextKt\$updateState\$1,kotlinx.coroutines.scheduling.DefaultScheduler,kotlin.coroutines.ContinuationInterceptor\$Key")
            buildArgs.add("--initialize-at-build-time=com.sun.scenario.effect.Offset")
            buildArgs.add("--initialize-at-build-time=com.sun.scenario.animation.AbstractPrimaryTimer\$MainLoop")
            buildArgs.add("--initialize-at-build-time=com.sun.scenario.animation.shared.SingleLoopClipEnvelope")
            buildArgs.add("--initialize-at-build-time=com.sun.scenario.animation.shared.GeneralClipInterpolator")
            buildArgs.add("--initialize-at-build-time=com.sun.scenario.animation.shared.TimelineClipCore")
            buildArgs.add("--initialize-at-run-time=org.pcap4j.core.Pcaps")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.Control")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.PopupControl")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.Labeled\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.Slider")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.Slider\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.TextField\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.TextInputControl\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.ScrollBar\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.Separator\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.skin.ProgressIndicatorSkin")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.skin.ProgressBarSkin\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.SkinBase\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.skin.TextInputControlSkin")
            buildArgs.add("--initialize-at-run-time=javafx.scene.control.skin.TextInputControlSkin\$StyleableProperties")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.scene.control.behavior.TextInputControlBehavior")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.scene.control.Properties")
            buildArgs.add("--initialize-at-run-time=javafx.scene.web.WebEngine")
            buildArgs.add("--initialize-at-run-time=javafx.stage.Screen")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.scene.control.ControlHelper")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.scene.control.LabeledHelper")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.tk.Toolkit")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.tk.quantum.QuantumToolkit")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.tk.quantum.QuantumRenderer")
            buildArgs.add("--initialize-at-run-time=com.sun.javafx.tk.quantum.PaintCollector")

            // Module support for the native compiler
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

compose.desktop {
    application {
        mainClass = "com.tbread.Launcher"

        jvmArgs(
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseCompactObjectHeaders",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            // We removed the --add-modules here.
            // JavaFX will load from the classpath as 'unnamed' modules.
            "-Dapple.laf.useScreenMenuBar=true"
        )

        nativeDistributions {
            windows {
                includeAllModules = true
                // Note: If you don't have an icon yet, comment out the line below
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            targetFormats(TargetFormat.Msi)
            packageName = "aion2meter-tw"
            packageVersion = "0.1.6"
        }
    }
}
