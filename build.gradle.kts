import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.0" // Update this line
    id("org.jetbrains.compose") version "1.7.3" // Ensure Compose is also recent
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

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://jogamp.org/deployment/maven")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    google()
}

dependencies {
    // Note, if you develop a library, you should use compose.desktop.common.
    // compose.desktop.currentOs should be used in launcher-sourceSet
    // (in a separate module for demo project and in testMain).
    // With compose.desktop.common you will also lose @Preview functionality
    implementation(compose.desktop.currentOs)
    implementation ("org.pcap4j:pcap4j-core:1.8.2")
    implementation ("org.pcap4j:pcap4j-packetfactory-static:1.8.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")


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
            // This is the specific class Kotlin generates for your main function
            mainClass.set("com.tbread.MainKt")

            imageName.set("Aion2Meter")

            // Critical for JNA and Pcap4J support in native images
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-http")
            buildArgs.add("--enable-https")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.tbread.MainKt"

        // PHASE 3: ADD THESE JVM ARGUMENTS HERE
        jvmArgs(
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseCompactObjectHeaders",       // The major RAM saver for JDK 25
            "--add-opens=java.base/java.nio=ALL-UNNAMED", // Needed for Pcap4J/JNA memory access
            "--add-modules=jdk.net",
            "--add-modules=jdk.jsobject"
        )

        nativeDistributions {
            windows {
                includeAllModules = true
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            targetFormats(TargetFormat.Msi)
            packageName = "aion2meter-tw"
            packageVersion = "0.1.6"
            copyright = "Copyright 2026 Taengu Licensed under MIT License"
        }
    }
}
