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

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    // build-tools 必须按宿主平台选：Windows 与 aarch64 容器共用同一个 Android SDK 目录，
    // 而该目录下 build-tools/35.0.0 是 Linux 可执行文件、36.0.0 是 Windows 版 .exe，
    // 任何单一取值都会让另一侧报
    //   Installed Build Tools revision 35.0.0 is corrupted
    //   Build-tool 35.0.0 is missing AAPT at ...\35.0.0\aapt.exe
    // AGP 不认识 -Pandroid.buildToolsVersion 这种属性覆盖，只认这里的 DSL，
    // 所以只能在此显式分支（providers 形式对配置缓存友好）。与 sender 保持一致。
    buildToolsVersion = if (
        providers.systemProperty("os.name").get().startsWith("Windows")
    ) { "36.0.0" } else { "35.0.0" }

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
        versionCode = 31
        versionName = "0.0.31-castkit.1"

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
