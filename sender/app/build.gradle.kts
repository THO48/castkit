plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // 官方 Compose Preview 截图测试：在 JVM 上把 @Preview 渲染成 PNG，
    // 需要 gradle.properties 里的 android.experimental.enableScreenshotTest=true。
    alias(libs.plugins.android.compose.screenshot)
}

android {
    namespace = "com.dsh.castkit.sender"
    compileSdk = 36

    // build-tools 必须按宿主平台选：本机 Windows 与 WSL 共用同一个 Android SDK 目录，
    // 而该目录下 build-tools/35.0.0 是 Linux 可执行文件、36.0.0 是 Windows 版 .exe，
    // 任何单一取值都会让另一侧报 "Build Tools revision X is corrupted"。
    // AGP 不认识 -Pandroid.buildToolsVersion 这种属性覆盖，只认这里的 DSL，
    // 所以只能在此显式分支（providers 形式对配置缓存友好）。
    buildToolsVersion = if (
        providers.systemProperty("os.name").get().startsWith("Windows")
    ) { "36.0.0" } else { "35.0.0" }

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
