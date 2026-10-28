import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("eta.compose-semantics-cache")
    // 消费 :baselineprofile 生成的基准配置文件，降低滑动 JIT 抖动（仅 release 生效）。
    id("androidx.baselineprofile")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("ETA_RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("ETA_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ETA_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ETA_RELEASE_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

android {
    namespace = "io.github.mangi.eta"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.mangi.eta"
        minSdk = 34
        targetSdk = 36
        // versionCode 规则：yyyyMMdd + 两位当日序号（01 起），发版时随 versionName 一起手动递增。
        versionCode = 2026102801
        versionName = "5.3.16"
        val diagnosticSha = System.getenv("GITHUB_SHA")?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) } ?: "unknown"
        buildConfigField("String", "GIT_SHA", "\"$diagnosticSha\"")

        ndk {
            // 同时过滤本地与依赖 AAR 的原生库，确保 APK 仅包含 ARM64。
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            signingConfig = if (hasReleaseSigning && System.getenv("ETA_DISABLE_RELEASE_SIGNING") != "true") {
                signingConfigs.getByName("release")
            } else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }

    packaging {
        dex {
            // DEX 压缩后入包，安装时释放；classes.dex 不压缩直接占一成体积。
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libproot_exec.so", "**/libproot_loader.so", "**/libeta_pty.so")
        }
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // versionCode 采用 yyyyMMdd+当日序号方案（见 defaultConfig），数值天然接近 int 上限；
        // 方案上限约到 2030 年，届时需整体迁移递增序号方案，现在每次发版都满足单调递增。
        disable += "HighAppVersionCode"
        // LocalContextGetResourceValueCall：本检查假定「Activity 不随配置变化重建」。
        // 主 Activity 未声明 locale/configChanges，语言切换必然重建并重新组合；且命中
        // 的 96 处均在事件回调/协程内读取文案而非组合期渲染，无实际过期风险。
        disable += "LocalContextGetResourceValueCall"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// CI must identify the last started test even if the worker never finishes a report.
if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        testLogging {
            events("started", "passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}

// Keep compiler function slices available in the signed release diagnostic capture.
composeCompiler {
    includeTraceMarkers = true
}

dependencies {
    implementation(libs.commons.compress)
    implementation(libs.jsoup)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.material.icons.extended)
    // 官方类型安全导航（NavHost / NavController）
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // markdown-renderer-m3 将 material3 作为 compileOnly，需显式引入以满足运行时依赖
    implementation(libs.material3)
    // 取色引擎：由强调色种子 + 配色风格生成 9 套 DynamicScheme（Spec2025）
    implementation(libs.material.color.utilities)
    // 基准配置文件：由 :baselineprofile 模块在真机 / 模拟器上生成（generateBaselineProfile）。
    baselineProfile(project(":baselineprofile"))
    implementation(libs.hidden.api.bypass)

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    // 本地多模态 Embedding（EmbeddingGemma 2 运行时）；Google Maven。
    implementation(libs.litertlm.android)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    // 与 App 使用的 Compose UI 版本保持一致；selection 相关用例需要真实手势覆盖。
    testImplementation("androidx.compose.ui:ui-test-junit4:1.12.0-rc01")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.0-rc01")
}

// Pin and verify the small JNI runtime; speech model is opt-in at runtime, never bundled.
val speechJniDir = layout.buildDirectory.dir("generated/speech/jniLibs")
val prepareSpeechRuntime by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("scripts/prepare-speech-runtime.py"))
    outputs.dir(speechJniDir)
    commandLine("python3", rootProject.file("scripts/prepare-speech-runtime.py").absolutePath,
        speechJniDir.get().asFile.absolutePath)
}
android.sourceSets.getByName("main").jniLibs.srcDir(speechJniDir.get().asFile)
tasks.named("preBuild").configure { dependsOn(prepareSpeechRuntime) }

// Release artifacts must never silently fall back to a runner-generated debug certificate.
val releaseSigningEnabled = hasReleaseSigning && System.getenv("ETA_DISABLE_RELEASE_SIGNING") != "true"
val releaseKeystoreFile = releaseStoreFile?.let { storeFileValue ->
    val storeFileCandidate = File(storeFileValue)
    if (storeFileCandidate.isAbsolute) storeFileCandidate else File(projectDir, storeFileValue)
}
tasks.matching { it.name in setOf("packageRelease", "bundleRelease", "assembleRelease") }.configureEach {
    // 闭包只能捕获纯值：引用 Project 会生成指向脚本对象的 this$0，配置缓存存不下来。
    val signingEnabled = releaseSigningEnabled
    val keystoreFile = releaseKeystoreFile
    doFirst {
        check(signingEnabled) {
            "Original release signing configuration is required; debug fallback is forbidden."
        }
        check(keystoreFile?.isFile == true) { "Release keystore is missing." }
    }
}
