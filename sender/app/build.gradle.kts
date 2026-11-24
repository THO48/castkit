plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

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
        versionCode = 3
        versionName = "2.0.0"
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
    implementation(libs.miuix)
    implementation(libs.media3.exoplayer)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
}
