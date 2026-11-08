plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Builds web/ (Vite + React + assistant-ui) into app/src/main/assets/web.
 * The bundles are no longer committed, so this task fails loudly instead of
 * packaging a stale/missing WebView payload.
 */
// 关于页 git commit 编译期注入：Gradle 打包时把实际 HEAD 传给 Vite（否则 Vite
// 自己 rev-parse 出的是构建时 HEAD，APK 内资产可能滞后于发布提交）。
val webGitCommit: String = try {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    out.ifEmpty { "unknown" }
} catch (_: Exception) {
    "unknown"
}

val buildWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the assistant-ui WebView bundle with npm + Vite"
    workingDir = rootProject.file("web")
    environment("NODE_OPTIONS", "--max-old-space-size=4096")
    environment("ANDAGENT_GIT_COMMIT", webGitCommit)
    commandLine(
        "bash", "-c",
        """
        set -e
        if ! command -v npm >/dev/null 2>&1; then
          echo "[buildWeb] npm not found: install Node.js 20+, then run 'cd web && npm ci && npm run build'" >&2
          exit 1
        fi
        if [ ! -d node_modules ]; then
          echo "[buildWeb] installing web dependencies (npm ci)"
          npm ci --no-audit --no-fund
        fi
        npm run build
        """.trimIndent(),
    )
}

tasks.named("preBuild").configure { dependsOn(buildWeb) }

/** Maintainer-only refresh. Normal builds always package the checked-in archive. */
val downloadBootstrap by tasks.registering(Exec::class) {
    group = "maintenance"
    description = "Refresh the bundled aarch64 Termux bootstrap after SHA-256 verification"
    workingDir = rootProject.projectDir
    commandLine("bash", "-c", """
        set -euo pipefail
        target=app/src/main/assets/bootstrap/bootstrap-aarch64.zip
        tmp="${'$'}{target}.download"
        trap 'rm -f "${'$'}tmp"' EXIT
        curl -fL --retry 3 --connect-timeout 20 -o "${'$'}tmp" 'https://ghfast.top/https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-aarch64.zip'
        printf '%s  %s\n' '5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae' "${'$'}tmp" | sha256sum -c -
        python3 scripts/rewrite_termux_bootstrap.py "${'$'}tmp" "${'$'}target"
    """.trimIndent())
}

android {
    namespace = "com.dc.andagent"
    compileSdk = 34
    // AGP 8.7.3 默认的 27.0.12077973 在本机仅剩空壳安装目录，显式锁定可用版本。
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.dc.andagent"
        minSdk = 26
        targetSdk = 28
        versionCode = 59
        versionName = "0.30.0"
        // R101b: ABI 过滤——真机 99% 是 arm64；x86/x86_64 仅模拟器、armeabi-v7a
        // 是 32 位老设备（Termux bootstrap 本身也只内置 aarch64）。省 ~3.8MB。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    /**
     * R102: 仓库内置的开发签名。默认的 debug.keystore 每台机器自动生成、指纹不同，
     * 换机器打的包签名不一致，系统强制卸载才能装 —— 私有目录数据全丢。
     * 这里固定一把钥匙给 debug 与 release 共用，口令同样是公开的开发口令，
     * 不用于任何真实发布场景（正式发版请换成自己的 release keystore）。
     */
    signingConfigs {
        // 用户钦定（2026-11-08）：使用本机 ~/.android/debug.keystore（指纹
        // 2009f47f...，2026-10-17 生成）——与已分发的历史包升级链一致，
        // 用户可覆盖安装不丢数据。仓库内置 andagent-dev.jks 弃用。
        create("dev") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // This APK is distributed directly; targetSdk 28 is intentional for
        // the existing legacy storage and permission behavior.
        disable += "ExpiredTargetSdkVersion"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            // 固定签名：任何机器上构建出的 APK 都能覆盖安装同一台手机上的旧版本。
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Health Connect step data.
    // NOTE: 1.2.0-alpha06 (mentioned in the brief) declares minCompileSdk=37 and
    // AGP 9.1.0; this project is compileSdk 34 / AGP 8.7.3. 1.1.0-alpha07 is the
    // newest connect-client whose AAR metadata still allows compileSdk 34 and
    // exposes the same StepsRecord/PermissionController APIs used here.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // Same 16 KB page-aligned QuickJS Android artifact used by widget-chat-android.
    implementation(files("libs/quickjs-wrapper-android-3.2.0.jar"))
    implementation("wang.harlon.quickjs:wrapper-java:3.2.0")

    implementation("com.hierynomus:sshj:0.41.2")

    // sshj declares BouncyCastle as runtime-only, but MainActivity registers
    // BouncyCastleProvider explicitly (X25519 KeyAgreement is missing from the
    // Android built-in providers) - so bcprov must also be on the compile
    // classpath. Version aligned with sshj 0.41.2's transitive 1.84.
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    // R35: pure-Java QR encode/decode (ZXing core, Apache-2.0, vendored jar).
    // Chosen over ML Kit barcode scanning: no GMS, no Play Services model
    // download, zero permissions, fully offline. See app/libs/README.md.
    implementation(files("libs/zxing-core-3.5.3.jar"))
}
