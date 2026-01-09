import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.0.0"

}

group = "com.tbread"
version = "1.0-SNAPSHOT"

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

//    implementation("io.github.kevinnzou:compose-webview-multiplatform:1.9.20")
//    //cef 래퍼 포함 라이브러리
//
//    implementation ("net.java.dev.jna:jna:5.18.1")
//    implementation ("net.java.dev.jna:jna-platform:5.18.1")
//    //jna
//
//    implementation("org.jetbrains.intellij.deps.jcef:jcef:137.0.17-gf354b0e-chromium-137.0.7151.104-api-1.20-261-b15")
//
//    implementation("org.jogamp.gluegen:gluegen-rt:2.3.2")
//    implementation("org.jogamp.gluegen:gluegen-rt:2.3.2:natives-windows-amd64")
//    implementation("org.jogamp.jogl:jogl-all:2.3.2")
//    implementation("org.jogamp.jogl:jogl-all:2.3.2:natives-windows-amd64")

    implementation("org.openjfx:javafx-base:21.0.5:win")
    implementation("org.openjfx:javafx-graphics:21.0.5:win")
    implementation("org.openjfx:javafx-controls:21.0.5:win")
    implementation("org.openjfx:javafx-swing:21.0.5:win")
    implementation("org.openjfx:javafx-web:21.0.5:win")
    implementation("org.openjfx:javafx-media:21.0.5:win")


    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

}

compose.desktop {

    application {
        mainClass = "com.tbread.MainKt"



        nativeDistributions {
            windows{
                includeAllModules = true
            }
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "aion2meter4j"
            packageVersion = "1.0.0"
        }


    }
}
