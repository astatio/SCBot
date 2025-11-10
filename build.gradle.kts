import java.util.*

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.stackTraceDecoroutinator)
    embeddedKotlin("plugin.serialization")
    application
    alias(libs.plugins.shadow)
}


group = "com.astatio"
version =
    (Date().toInstant().toEpochMilli() - 1668273517039) / 1000 / 10
application {
    mainClass.set("MainKt")
}

dependencies {
    runtimeOnly(libs.kotlin.scripting.jsr223)
    implementation(kotlin("reflect"))
    implementation(libs.facebook.ktfmt)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.json.json)

    //PROMETHEUS
    implementation(libs.prometheus.core)
    implementation(libs.prometheus.instrumentation)
    implementation(libs.prometheus.exporter)

    //TRANSLITERATION
    implementation(libs.icu4j)

    //ALGORITHM FOR AUTOCOMPLETE
    implementation(libs.debatty.java.string.similarity)

    //RNG
    implementation(libs.apache.commons.rng.simple)
    implementation(libs.apache.commons.rng.sampling)

    //IMAGE MANIPULATION
    implementation(libs.imgscalr)

    //SKIKO
    implementation(libs.skiko.core)


    implementation(libs.skiko.awt.linux)
    //implementation(libs.skiko.awt.macos)
    //implementation(libs.skiko.awt.windows)

    //DATABASE
    implementation(libs.mongodb.driver.kotlin)

    //JDA
    implementation(libs.jda) {
        exclude(module = "opus-java")
    }
    implementation(libs.freya.jda.ktx)
    implementation(libs.minn.discord.webhooks)

    //SYSTEM INFO
    implementation(libs.oshi.core)

    //LOGGING
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)

    //OUTPUT COLORING
    implementation(libs.jansi)

    //HTTP CLIENT
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.json)
    implementation(libs.ktor.client.serialization)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)
    implementation(libs.kotlin.logging)
}

tasks.shadowJar {
    archiveBaseName.set("SCBot-Kotlin")
    archiveVersion.set(version.toString())
}

kotlin {
    jvmToolchain(24)
    compilerOptions {
        // extraWarnings.set(true)
        freeCompilerArgs.addAll(
            "-Xcontext-parameters"
        )
    }
}