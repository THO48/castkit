pluginManagement {
    repositories {
        // 本机不可达 plugins.gradle.org / Maven Central / repo1.maven.org，
        // 只保留可达镜像（阿里云 + dl.google.com），避免卡在超时上
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
    }
}

rootProject.name = "CastKitSender"
include(":app")
