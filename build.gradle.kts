import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"

}

group = "com.tbread"
version = "0.1.5"

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


    implementation("org.openjfx:javafx-base:21.0.5:win")
    implementation("org.openjfx:javafx-graphics:21.0.5:win")
    implementation("org.openjfx:javafx-controls:21.0.5:win")
    implementation("org.openjfx:javafx-swing:21.0.5:win")
    implementation("org.openjfx:javafx-web:21.0.5:win")
    implementation("org.openjfx:javafx-media:21.0.5:win")


    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.slf4j:slf4j-simple:1.7.26")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")



}

compose.desktop {

    application {
        mainClass = "com.tbread.MainKt"



        nativeDistributions {
            windows{
                includeAllModules = true
            }
            targetFormats(TargetFormat.Msi)
            packageName = "aion2meter-tw"
            packageVersion = "0.1.5"
            copyright = "Copyright 2026 Taengu Licensed under MIT License"
        }


    }
}
