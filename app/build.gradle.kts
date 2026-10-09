import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Secrets are read from -P properties, local.properties or the environment, never from the repo.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun setting(name: String): String =
    providers.gradleProperty(name).orNull ?: localProps.getProperty(name) ?: System.getenv(name) ?: ""

android {
    namespace = "com.diagramm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.diagramm"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        val yandexClientId = setting("YANDEX_CLIENT_ID")
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$yandexClientId\"")
        // Redirect scheme for Yandex OAuth: yx<client id>://token
        manifestPlaceholders["yandexScheme"] = "yx$yandexClientId"
    }

    // A project-specific key checked into the repo on purpose: Google OAuth ties the Android client to
    // the signing certificate's SHA-1, so every build (local or CI) must be signed with the same key.
    // It is a convenience key for personal/side-loaded builds - create a private key before publishing.
    signingConfigs {
        getByName("debug") {
            storeFile = file("diagramm-debug.jks")
            storePassword = "android"
            keyAlias = "diagramm"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { abortOnError = false }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:chart"))
    implementation(project(":core:storage"))
    implementation(project(":core:net"))
    implementation(project(":provider:gdrive"))
    implementation(project(":provider:yandex"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.auth)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
