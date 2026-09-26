# CastKit 构建说明（本机离线镜像 + aarch64 宿主方案）

CastKit 由两个 Android 工程组成：

| 目录 | 产物 | 说明 |
|---|---|---|
| `receiver/` | 接收端 App（`com.dsh.castkit.receiver`） | fork 自 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)（GPL-3.0，UxPlay 内核）：收 iPhone/iPad/Mac 的 AirPlay 镜像 + 音频，并接收 CastKit 发送端的局域网投屏 |
| `sender/` | 发送端 App（`com.dsh.castkit.sender`） | 自研：MediaProjection + MediaCodec H.264，分辨率/码率/帧率可配，走 LANCast v1（见 `docs/LANCast-v1.md`） |

> 先读 `README.md` 的「快速开始」，下面是完整环境说明。

## 0. 本机限制（都是实测结论）

| 限制 | 影响 | 方案 |
|---|---|---|
| 无法直连 `github.com` / `raw.githubusercontent.com` / `repo1.maven.org` / `plugins.gradle.org` / `services.gradle.org` / `mirror.viaduck.org` / `openssl.org` / `jitpack.io` | 源码、Gradle、依赖都拿不到 | 全部走可达镜像（见下表），`~/.gradle/init.d/mirrors.gradle` 统一注入依赖仓库 |
| `/sdcard` 是 **noexec** 挂载，且不保留执行位 | CMake 生成的 `prefab_command`、OpenSSL/FFmpeg 的 in-source `configure` 无法执行 | 构建树放在 `/root/castkit`（`tools/build-tree.sh` 单向同步源码），并用 `tar`/`cp -a` 而不是 `rsync`（本机文件系统层会让 rsync 建目录报 EROFS） |
| 宿主机是 **aarch64**，而 Android SDK 的 `aapt2`/`zipalign`、NDK 的 clang/llvm-*、SDK 的 CMake/Ninja 都是 **x86_64** | 全链路无法直接运行 | 见 `tools/setup-host-compat.sh`：① aapt2/zipalign 用 qemu-user 模拟（配 amd64 运行库）② NDK 宿主工具换成本机 aarch64 clang（sysroot/目标库原样保留）③ CMake/Ninja 用 apt 的系统版本（`local.properties: cmake.dir=/usr`） |
| 无 `cc`/`gcc` 时 FFmpeg 报 “Host compiler lacks C11 support” | FFmpeg（ALAC 软解）配置失败 | `apt-get install gcc` |

### 可用镜像

| 用途 | 镜像 |
|---|---|
| apt | `http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/` |
| Android SDK / NDK / Google Maven | `https://dl.google.com` |
| Maven Central / Gradle 插件 | `https://maven.aliyun.com/repository/{public,google,gradle-plugin}` |
| Gradle 发行包 | `https://mirrors.cloud.tencent.com/gradle/` |
| GitHub（git 协议代理） | `https://gitclone.com/github.com/<owner>/<repo>.git` |
| GitHub 镜像（完整历史） | `https://gitcode.com/gh_mirrors/<2 字母>/<repo>` |
| OpenSSL 源码 | `https://gitclone.com/github.com/openssl/openssl.git`（tag `openssl-3.4.4`） |

## 1. 一次性环境准备

```bash
# 1) apt 源（原 ports.ubuntu.com 不可达）
cp -a /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/ubuntu.sources.orig
#    把两个 URIs 改成 http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
apt-get update
apt-get install -y openjdk-21-jdk-headless make unzip file patch python3 perl \
                   clang lld llvm ninja-build cmake gcc zstd qemu-user-static

# 2) Android SDK（cmdline-tools + platform 36 + build-tools + NDK）
mkdir -p /opt/android-sdk/cmdline-tools
curl -L -o /tmp/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
python3 -c "import zipfile;zipfile.ZipFile('/tmp/cmdline-tools.zip').extractall('/tmp/ct')"
mv /tmp/ct/cmdline-tools /opt/android-sdk/cmdline-tools/latest
chmod +x /opt/android-sdk/cmdline-tools/latest/bin/*     # zip 解压不带可执行位
export ANDROID_SDK_ROOT=/opt/android-sdk
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_SDK_ROOT --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_SDK_ROOT \
  platform-tools platforms;android-36 build-tools;36.0.0 ndk;27.0.12077973

# 3) 宿主兼容（aapt2/qemu、NDK 工具包装）
bash tools/setup-host-compat.sh

# 4) 原生依赖源码 + 离线补丁
bash tools/fetch-sources.sh
bash tools/patch-sources.sh
```

> OpenSSL 3.4.4 与最小 FFmpeg（仅 ALAC）由 `tools/build-native-deps.sh` 预构建
> （构建 时会自动调用），产物在 `$CASTKIT_TREE/native-deps/<abi>/{openssl,ffmpeg}`，
> 由 `-PcastkitDepsRoot` 传给 CMake。原因见该脚本顶部注释：本机里
> ninja → python 包装器 → make 的嵌套调用会触发 GNU make jobserver 死锁。

## 2. 构建

```bash
bash tools/build-receiver.sh                 # 默认 :app:assembleDebug（arm64-v8a）
bash tools/build-receiver.sh :app:assembleRelease
bash tools/build-sender.sh

# 多 ABI（OpenSSL/FFmpeg 会按 ABI 各编译一遍，耗时成倍）
CASTKIT_ABIS=arm64-v8a,armeabi-v7a bash tools/build-receiver.sh
```

产物：`out/castkit-receiver-debug-app-debug.apk`、`out/castkit-sender-debug-app-debug.apk`
（同时保留在 `/root/castkit/*/app/build/outputs/apk/`）。

首次构建需 20–45 分钟（OpenSSL 3.4.4 + 最小 FFmpeg + UxPlay 交叉编译）；之后增量构建为分钟级。

## 3. 原生依赖与版本锁定（`tools/fetch-sources.sh`）

| 组件 | 上游 | 锁定版本 | 获取方式 |
|---|---|---|---|
| UxPlay | `FDH2/UxPlay` | gitcode 镜像 commit `942d7b2`（2026-09-16） | gitcode 镜像全量克隆 |
| openssl-cmake | `viaduck/openssl-cmake` | `4edd36a8` | gitclone 代理按 sha 浅取（内置重试） |
| libplist | `libimobiledevice/libplist` | `f41b1ea6` | gitcode 镜像（blob 按需） |
| FFmpeg（仅 ALAC 软解） | `FFmpeg/FFmpeg` | `38b88335` | gitcode 镜像（blob 按需） |
| OpenSSL | `openssl/openssl` | tag `openssl-3.4.4` | gitclone 代理浅克隆 |

**与上游的偏差（重要）**：接收端仓库实际锁定 UxPlay commit `4621533`，但该 commit 在所有可达镜像中都不存在
（上游 rebase/强推，镜像里连它的父提交状态都检索不到）。本方案改用镜像中最新的稳定 commit `942d7b2`，
仓库自带的 6 个 UxPlay 补丁上下文已对不上，因此**默认不应用**（`applyUxplayPatches` 在无 `.git` 时跳过；
要强制可用于 `-PcastkitRequireUxplayPatches=true`）。这些补丁修的是 HLS 播放列表崩溃/UAF/视频发送端兼容等边缘问题，
不影响 iOS 屏幕镜像主链路；真机若遇到对应问题再按补丁手工移植。

## 4. 离线/兼容补丁

| 脚本 | 作用 |
|---|---|
| `tools/setup-host-compat.sh` | amd64 运行库 + qemu 包装 `aapt2`/`zipalign`/`aapt`；调用 `setup-ndk-host-wrappers.sh` |
| `tools/setup-ndk-host-wrappers.sh` | 把 NDK `bin/`（x86_64）换成指向本机 clang-18/lld-18/llvm-18 的包装脚本，并注入 `--sysroot`、`-resource-dir <NDK clang 资源目录>`、`-rtlib=compiler-rt`、`-unwindlib=libunwind` |
| `tools/patch-sources.sh` | ① `openssl-cmake` 改用本地 `openssl-src`（原 mirror.viaduck.org 不可达）② Gradle wrapper 指向腾讯镜像 ③ 写 `~/.gradle/init.d/mirrors.gradle` |
| `tools/build-tree.sh` | 工作区 → `/root/castkit` 源码同步（排除 `third_party` 与 build 产物） |

## 5. 常见失败与处理

| 现象 | 处理 |
|---|---|
| `sdkmanager: Permission denied` | `chmod +x /opt/android-sdk/cmdline-tools/latest/bin/*` |
| `AAPT2 Daemon #0: Daemon startup failed` | 确认接收/发送端 `gradle.properties` 里有 `android.aapt2FromMavenOverride=/opt/castkit-host/bin/aapt2` |
| `ld.lld: unable to find library -lgcc / -latomic` | 重新执行 `tools/setup-ndk-host-wrappers.sh`（确认包装脚本含 `-resource-dir` 与 `-rtlib=compiler-rt`） |
| `./configure: Permission denied` | `bash tools/fetch-sources.sh`（会补齐脚本执行位）或手动 `chmod +x` 那两个 configure |
| `Host compiler lacks C11 support`（FFmpeg） | `apt-get install -y gcc` |
| `Daemon compilation failed: null`（KSP/Kotlin） | 已通过 `kotlin.compiler.execution.strategy=in-process` 规避（两个工程 gradle.properties） |
| 解析依赖卡在 “Still waiting for package manifests…” | 检查 `~/.gradle/init.d/mirrors.gradle`；仓库里已移除不可达的 `mavenCentral()`/`gradlePluginPortal()` |
| `gradlew`/脚本无法执行 | `/sdcard` 无 exec 位，一律 `bash <脚本>` |
