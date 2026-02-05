import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    implementation("com.github.kwhat:jnativehook:2.2.2")
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("com.tbread.Launcher")

            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+AddAllCharsets")
            buildArgs.add("-Dprism.fontdir=C:\\Windows\\Fonts")
            buildArgs.add("--no-fallback")

            // Critical for UI and async behavior
            buildArgs.add("--initialize-at-build-time=javafx,com.sun.javafx,kotlinx.coroutines")
            buildArgs.add("--initialize-at-run-time=org.pcap4j.core.Pcaps")

            // Module support for the native compiler
            buildArgs.add("--add-modules=jdk.jsobject,jdk.net,javafx.controls,javafx.web,javafx.graphics,javafx.media")
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
