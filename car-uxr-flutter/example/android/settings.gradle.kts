pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}

include(":app")

// ---------------------------------------------------------------------------
// Dart fork additions: the same toolchain as aaos-apps-gradle-project (AGP 9.3.1, Gradle 9.7.1,
// compileSdk 37, targetSdk 36; see FORK.md). The SDK levels are hardcoded duplicates of the main
// build's, and app/build.gradle.kts has a third copy of compileSdk; keep them in step. Flutter
// writes the APK under example/build/, not example/android/app/build. car-ui-lib's projects are
// included so the plugin's `implementation project(':car-ui-lib')` resolves.
// ---------------------------------------------------------------------------
gradle.extra["aaosLatestSDK"] = 37
gradle.extra["aaosTargetSDK"] = 36

// The included modules take their dependency versions from the main build's catalog.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../../aaos-apps-gradle-project/gradle/libs.versions.toml"))
        }
    }
}

include(":car-ui-lib")
project(":car-ui-lib").projectDir = file("../../../car-ui-lib/car-ui-lib")

include(":car-rotary-lib")
project(":car-rotary-lib").projectDir = file("../../../car-ui-lib/car-rotary-lib")

include(":oem-apis")
project(":oem-apis").projectDir = file("../../../car-ui-lib/oem-apis")
