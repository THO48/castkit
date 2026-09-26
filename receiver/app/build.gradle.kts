plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// CastKit: 首轮构建默认只出 arm64-v8a（本机交叉编译 OpenSSL/FFmpeg，3 个 ABI 会成倍耗时）。
// 需要多 ABI 时用 -PcastkitAbis=arm64-v8a,armeabi-v7a 覆盖。
val castkitAbis: List<String> = (findProperty("castkitAbis") as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: allAbis

// build-tools 的版本不能按 os.name 硬编码：
//   ① Windows 与 WSL/容器可能**共用同一个 SDK 目录**，而同一版本号在一侧是 Linux 可执行文件、
//      另一侧是 Windows .exe（`35.0.0` 是 Linux、`36.0.0` 是 Windows 的机器上就是这样）；
//   ② 一台机器上也常常只装了其中一个版本，另一个版本号会直接报
//        Installed Build Tools revision 35.0.0 is corrupted
//        Build-tool 35.0.0 is missing AAPT at ...\35.0.0\aapt.exe
// 所以这里改成按「目录存在 + 当前平台对应的 aapt2 可执行文件存在」来挑，
// 取版本号最高的那个；一个都没有就留空，让 AGP 用默认值并给出它自己的正常报错。
// AGP 不认识 -Pandroid.buildToolsVersion 这种属性覆盖，只认这里的 DSL。与 sender 保持一致。
val castkitBuildTools: String? = run {
    val sdkDir: File? = sequenceOf(
        localProps.getProperty("sdk.dir"),
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
    ).firstOrNull { !it.isNullOrBlank() }?.let { file(it) }

    val aapt2Name =
        if (providers.systemProperty("os.name").get().startsWith("Windows")) "aapt2.exe" else "aapt2"

    fun versionKey(name: String): Int {
        val v = name.split('.').mapNotNull { it.toIntOrNull() }
        return v.getOrElse(0) { 0 } * 10_000 + v.getOrElse(1) { 0 } * 100 + v.getOrElse(2) { 0 }
    }

    sdkDir?.resolve("build-tools")
        ?.takeIf { it.isDirectory }
        ?.listFiles()
        ?.filter { it.isDirectory && it.resolve(aapt2Name).isFile }
        ?.maxByOrNull { versionKey(it.name) }
        ?.name
}

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    castkitBuildTools?.let { buildToolsVersion = it }

    if (localProps.containsKey("storeFile")) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        // CastKit: 换掉上游 applicationId，避免与已安装的 F-Droid「AirPlay Server」冲突
        applicationId = "com.dsh.castkit.receiver"
        minSdk = 24
        targetSdk = 36
        versionCode = 34
        versionName = "0.0.31-castkit.4"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // CastKit: 指向 tools/build-native-deps.sh 预构建的 OpenSSL/FFmpeg
                // （本机的嵌套 make 会死锁，故不用上游的 ExternalProject）
                (findProperty("castkitDepsRoot") as String?)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { arguments += "-DCASTKIT_DEPS_ROOT=$it" }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += castkitAbis }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            ndk { abiFilters += castkitAbis }
        }
        // debuggable build with HWASan (arm64) + UBSan in native code
        create("sanitize") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake { arguments += "-DSANITIZE=ON" }
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        prefab = true
    }
}

// CastKit: 上游用 `git submodule` 拉 UxPlay 并在这里用 git apply 打补丁。
// 本机无法访问 github.com，third_party 由 tools/fetch-sources.sh 以普通目录形式获取（无 .git），
// 且实际使用的 UxPlay 版本与 fork 锁定的 commit 不同（上游 rebase），这 6 个补丁上下文对不上。
// 因此这里改成：优先尝试应用；无法应用就记录并跳过，不让构建失败。
//   想强制要求补丁必须生效：-PcastkitRequireUxplayPatches=true
tasks.register("applyUxplayPatches") {
    doLast {
        val uxplayDir = file("src/main/cpp/third_party/UxPlay")
        val requirePatches = (findProperty("castkitRequireUxplayPatches") as String?) == "true"
        val gitDir = File(uxplayDir, ".git")
        if (!gitDir.exists()) {
            val msg = "applyUxplayPatches: $uxplayDir 不是 git 仓库（离线 vendored 模式），跳过 UxPlay 补丁"
            if (requirePatches) error(msg) else logger.lifecycle("[CastKit] $msg")
            return@doLast
        }

        fun gitOrNull(vararg args: String): Pair<Int, String> {
            val proc = ProcessBuilder("git", "-C", uxplayDir.path, *args)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            return proc.waitFor() to out
        }

        val patches = file("src/main/cpp/patches/UxPlay")
            .listFiles { f -> f.extension == "patch" }!!.sorted()
        val touched = mutableListOf<String>()
        patches.forEach { patch ->
            val (code, out) = gitOrNull("apply", "--numstat", patch.path)
            if (code == 0) {
                out.trim().lines().filter { it.isNotBlank() }
                    .forEach { touched += it.substringAfterLast("\t") }
            }
        }
        if (touched.isNotEmpty()) gitOrNull("checkout", "--", *touched.toTypedArray())

        var applied = 0
        val skipped = mutableListOf<String>()
        patches.forEach { patch ->
            val (code, out) = gitOrNull("apply", "--unidiff-zero", patch.path)
            if (code == 0) {
                applied++
                logger.lifecycle("[CastKit] UxPlay 补丁已应用: ${patch.name}")
            } else {
                skipped += patch.name
                if (requirePatches) {
                    error("UxPlay 补丁 ${patch.name} 应用失败：\n$out")
                } else {
                    logger.lifecycle("[CastKit] 跳过 UxPlay 补丁 ${patch.name}（上下文与当前 UxPlay 版本不符）")
                }
            }
        }
        if (touched.isNotEmpty()) gitOrNull("checkout", "--", *touched.toTypedArray())
        logger.lifecycle("[CastKit] UxPlay 补丁：应用 $applied 个，跳过 ${skipped.size} 个")
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

tasks.withType<Zip>().configureEach {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore.prefs)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose.material3)
    implementation(libs.media3.transformer)
    // CastKit: FFmpeg 软解（NextLib）—— 见 renderer/LanVideoPlayer.kt 顶部说明
    implementation(libs.nextlib.media3ext)
    // CastKit: libVLC 兜底内核 —— Media3 与 NextLib 都没有 ASF 解封装器，WMV/WMA 靠它
    implementation(libs.libvlc)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.oboe)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
