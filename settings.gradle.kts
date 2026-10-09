pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "Diagramm"

// The Android app needs an Android SDK. Everything else is plain Kotlin/JVM and builds anywhere.
// The app module (and the Android Gradle plugins on the root classpath) is enabled only when an SDK
// is found, so `./gradlew test` works on a machine without Android tooling.
fun findAndroidSdk(): String? {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!fromEnv.isNullOrBlank()) return fromEnv
    val props = File(rootDir, "local.properties")
    if (!props.exists()) return null
    val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
    return p.getProperty("sdk.dir")
}

val androidEnabled = findAndroidSdk()?.let { File(it).isDirectory } == true ||
    providers.gradleProperty("withApp").isPresent

rootProject.buildFileName = if (androidEnabled) "build.android.gradle.kts" else "build.jvm.gradle.kts"

include(":core:model", ":core:net", ":core:chart", ":core:storage")
include(":provider:gdrive", ":provider:yandex")
if (androidEnabled) include(":app")
