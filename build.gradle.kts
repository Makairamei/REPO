import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        // Android Gradle Plugin
        classpath("com.android.tools.build:gradle:8.7.3")
        // CloudStream Gradle Plugin
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        // Kotlin
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
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
                ?: "https://github.com/sad25kag/GKI_KernelSU_SUSFS"
        )
    }

    // Menggunakan Compiler Options DSL Baru yang Kompatibel dengan Kotlin 2.x
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            // SAKLAR ANTI-MOGOK GLOBAL: Warning dicuekin total di seluruh folder provider!
            allWarningsAsErrors.set(false)
        }
    }

    android {
        compileSdkVersion(35)

        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    // Menggunakan konfigurasi dependensi subproject yang sah agar tidak "Unresolved reference"
    project.dependencies {
        val implementation = "implementation"

        add(implementation, "org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

        // =========================
        // NETWORK
        // =========================
        add(implementation, "com.github.Blatzar:NiceHttp:0.4.13")
        add(implementation, "com.squareup.okhttp3:okhttp:4.12.0")

        // =========================
        // HTML PARSER
        // =========================
        add(implementation, "org.jsoup:jsoup:1.18.3")

        // =========================
        // JSON
        // =========================
        add(implementation, "com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        add(implementation, "com.fasterxml.jackson.core:jackson-databind:2.16.0")
        add(implementation, "com.google.code.gson:gson:2.11.0")

        // =========================
        // JAVASCRIPT ENGINE
        // =========================
        add(implementation, "com.faendir.rhino:rhino-android:1.6.0")
        add(implementation, "app.cash.quickjs:quickjs-android:0.9.2")

        // =========================
        // UTILS
        // =========================
        add(implementation, "me.xdrop:fuzzywuzzy:1.4.0")
        add(implementation, "androidx.core:core-ktx:1.16.0")
    }
}

// =========================
// CLEAN
// =========================
task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
