import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {

    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {

        // Android Gradle Plugin - upgraded for Kotlin 2.3.0 support
        classpath(
            "com.android.tools.build:gradle:8.8.0"
        )

        // CloudStream Gradle Plugin
        classpath(
            "com.github.recloudstream:gradle:master-SNAPSHOT"
        )

        // Kotlin - must match cloudstream.jar version
        classpath(
            "org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0"
        )
    }
}

allprojects {

    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(
    configuration: CloudstreamExtension.() -> Unit
) = extensions
    .getByName<CloudstreamExtension>("cloudstream")
    .configuration()

fun Project.android(
    configuration: BaseExtension.() -> Unit
) = extensions
    .getByName<BaseExtension>("android")
    .configuration()

subprojects {

    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {

        setRepo(
            System.getenv("GITHUB_REPOSITORY")
                ?: "https://github.com/duro92/ExtCloud"
        )

        authors = listOf(
            "duro92"
        )
    }

    android {

        namespace = "com.excloud"

        defaultConfig {

            minSdk = 21

            compileSdkVersion(35)

            targetSdk = 35
        }

        // =========================
        // JAVA 17 FIX
        // =========================

        compileOptions {

            sourceCompatibility =
                JavaVersion.VERSION_17

            targetCompatibility =
                JavaVersion.VERSION_17
        }

        // =========================
        // KOTLIN JVM 17 FIX
        // =========================

        tasks.withType<KotlinJvmCompile>() {

            compilerOptions {

                jvmTarget.set(
                    JvmTarget.JVM_17
                )

                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {

        val cloudstream by configurations
        val implementation by configurations

        // =========================
        // CLOUDSTREAM
        // =========================

        cloudstream(
            "com.lagradost:cloudstream3:pre-release"
        )

        // =========================
        // KOTLIN
        // =========================

        implementation(
            kotlin("stdlib")
        )

        implementation(
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1"
        )

        implementation(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1"
        )

        implementation(
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3"
        )

        // =========================
        // NETWORK
        // =========================

        implementation(
            "com.github.Blatzar:NiceHttp:0.4.13"
        )

        implementation(
            "com.squareup.okhttp3:okhttp:4.12.0"
        )

        // =========================
        // HTML PARSER
        // =========================

        implementation(
            "org.jsoup:jsoup:1.18.3"
        )

        // =========================
        // JSON
        // =========================

        implementation(
            "com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0"
        )

        implementation(
            "com.fasterxml.jackson.core:jackson-databind:2.16.0"
        )

        implementation(
            "com.google.code.gson:gson:2.11.0"
        )

        // =========================
        // JAVASCRIPT ENGINE
        // =========================

        implementation(
            "com.faendir.rhino:rhino-android:1.6.0"
        )

        implementation(
            "app.cash.quickjs:quickjs-android:0.9.2"
        )

        // =========================
        // UTILS
        // =========================

        implementation(
            "me.xdrop:fuzzywuzzy:1.4.0"
        )

        implementation(
            "androidx.core:core-ktx:1.16.0"
        )
    }
}

// =========================
// CLEAN
// =========================

task<Delete>("clean") {

    delete(
        rootProject.layout.buildDirectory
    )
}