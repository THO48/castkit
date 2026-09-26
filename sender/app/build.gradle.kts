plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // 官方 Compose Preview 截图测试：在 JVM 上把 @Preview 渲染成 PNG，
    // 需要 gradle.properties 里的 android.experimental.enableScreenshotTest=true。
    alias(libs.plugins.android.compose.screenshot)
}

import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// build-tools 的版本不能按 os.name 硬编码：
//   ① Windows 与 WSL/容器可能**共用同一个 SDK 目录**，而同一版本号在一侧是 Linux 可执行文件、
//      另一侧是 Windows .exe（`35.0.0` 是 Linux、`36.0.0` 是 Windows 的机器上就是这样）；
//   ② 一台机器上也常常只装了其中一个版本，另一个版本号会直接报
//        Installed Build Tools revision 35.0.0 is corrupted
// 所以这里改成按「目录存在 + 当前平台对应的 aapt2 可执行文件存在」来挑，
// 取版本号最高的那个；一个都没有就留空，让 AGP 用默认值并给出它自己的正常报错。
// AGP 不认识 -Pandroid.buildToolsVersion 这种属性覆盖，只认这里的 DSL。与 receiver 保持一致。
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
    namespace = "com.dsh.castkit.sender"
    compileSdk = 36

    castkitBuildTools?.let { buildToolsVersion = it }

    defaultConfig {
        applicationId = "com.dsh.castkit.sender"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    }
    // 截图测试插件要求「模块级」显式开启（只有 gradle.properties 的全局开关不够，
    // 插件会直接报 "Please enable screenshotTest source set in module first"）。
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines)
    // CastKit: 本地播放引擎（见 media/LocalVideoPlayer.kt 顶部说明）。
    // 只引 ExoPlayer 本体，不引 FFmpeg —— 发送端只做"预览"，投屏走的是字节转发不解码，
    // 而 NextLib 的 FFmpeg .so 要按 ABI 各带一份（本工程出 4 个 ABI，约 +24 MB）。
    implementation(libs.media3.exoplayer)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    // 官方规范图标集（PlayArrow / Pause / ScreenRotation / Cast / Stop 等）。
    // core 集里没有 Pause、ScreenRotation、Cast，播放页需要它们；版本由 compose-bom 管。
    // 官方规范图标集（PlayArrow / Pause / ScreenRotation / Cast / Stop）。
    // 这里用全限定坐标而非常量目录别名：compose-material3 已经占用了 `compose.material`
    // 这一段访问器名，别名会和它冲突。版本与 material-icons-core 实际解析出的 1.7.6 对齐。
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    debugImplementation(libs.compose.ui.tooling)

    // 截图测试源集（src/screenshotTest/kotlin）。ui-tooling 提供 layoutlib 渲染入口，
    // 其余 compose 依赖必须与预览里用到的保持一致（material3 等），否则截图任务编译不过。
    // 刻意「不」依赖 miuix：先用最小依赖把通道跑通，harness 预览只画 MaterialTheme。
    screenshotTestImplementation(platform(libs.compose.bom))
    screenshotTestImplementation(libs.compose.ui.tooling)
    screenshotTestImplementation(libs.compose.ui)
    screenshotTestImplementation(libs.compose.material3)
    screenshotTestImplementation(libs.compose.ui.tooling.preview)
    // @PreviewTest 注解：截图引擎只认这个注解，只写 @Preview 会「成功但 0 张图」。
    screenshotTestImplementation(libs.screenshot.validation.api)
}
