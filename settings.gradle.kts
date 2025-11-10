rootProject.name = "SCBot-Kotlin"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")


pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://m2.dv8tion.net/releases")
            name = "m2-dv8tion"
        }
        maven("https://raw.githubusercontent.com/graalvm/native-build-tools/snapshots")
        maven("https://jitpack.io/")
        maven("https://plugins.gradle.org/m2/")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://m2.dv8tion.net/releases")
            name = "m2-dv8tion"
        }
        maven("https://raw.githubusercontent.com/graalvm/native-build-tools/snapshots")
        maven("https://jitpack.io/")
        maven("https://plugins.gradle.org/m2/")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
