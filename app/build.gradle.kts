plugins {
    id("com.android.application")
}

// Bumped by release-please on each release (release-please-config.json).
val appVersion = "0.2.1" // x-release-please-version
// 1.2.3 -> 10203, so every release installs over the previous one.
val appVersionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
}

// The Streamer runs a 32-bit userspace (ro.product.cpu.abi = armeabi-v7a).
val targetAbis = listOf("armeabi-v7a")

android {
    namespace = "dev.matinzd.airplaytv"
    compileSdk = 36
    ndkVersion = "29.0.13599879"

    defaultConfig {
        applicationId = "dev.matinzd.airplaytv"
        minSdk = 28
        targetSdk = 34
        versionName = appVersion
        versionCode = appVersionCode
        ndk { abiFilters += targetAbis }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The release key, set by the release workflow (or locally from
    // ~/.android/airplay-tv-release/credentials.properties).
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the release key, use the debug key, so a local release build installs
            // over a debug one (and vice versa) without uninstalling. Fine for sideloading.
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    lint {
        // targetSdk stays at the Streamer's Android version (14) on purpose.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // AirPlay video (HLS) playback
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")

    testImplementation("junit:junit:4.13.2")
}

val sdkComponents = androidComponents.sdkComponents

// OpenSSL's libcrypto isn't part of the NDK: build it once, before CMake runs.
val buildOpenSsl by tasks.registering(Exec::class) {
    group = "airplay"
    description = "Builds static libcrypto for ${targetAbis.joinToString()} into third_party/openssl"
    val outputs = targetAbis.map { rootProject.file("third_party/openssl/$it/lib/libcrypto.a") }
    onlyIf { outputs.any { !it.exists() } }
    workingDir = rootDir
    commandLine("bash", "scripts/build-openssl.sh")
    environment("ABIS", targetAbis.joinToString(" "))
    doFirst { environment("ANDROID_NDK_ROOT", sdkComponents.ndkDirectory.get().asFile.absolutePath) }
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(buildOpenSsl)
}

// One-time device setup over adb: lets the app wake the TV from standby.
tasks.register<Exec>("setupDevice") {
    group = "airplay"
    description = "Grants the permissions the app needs to wake the TV (uses adb)"
    workingDir = rootDir
    commandLine("bash", "scripts/setup-device.sh")
    doFirst { environment("ADB", sdkComponents.adb.get().asFile.absolutePath) }
}
