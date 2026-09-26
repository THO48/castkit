# CastKit 构建说明（Windows + WSL2 混合路线 / aarch64 容器路线）

CastKit 由两个 Android 工程组成：

| 目录 | 产物 | 说明 |
|---|---|---|
| `receiver/` | 接收端 App（`com.dsh.castkit.receiver`） | fork 自 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)（GPL-3.0，UxPlay 内核）：收 iPhone/iPad/Mac 的 AirPlay 镜像 + 音频，并接收 CastKit 发送端的局域网投屏 |
| `sender/` | 发送端 App（`com.dsh.castkit.sender`） | 自研：MediaProjection + MediaCodec H.264，分辨率/码率/帧率可配，走 LANCast v1（见 `docs/LANCast-v1.md`） |

> 先读 `README.md` 的「快速开始」，下面是完整环境说明。

## 0-A. 两条构建路线

接收端的原生依赖（OpenSSL / FFmpeg / UxPlay）是 C/C++，编译只需要一个 Linux 宿主的 NDK，
**不需要 JDK**；而 APK 打包需要 JDK + Android SDK。本机（Windows + WSL2 Debian）刚好一半一半，
所以用「Linux 编原生依赖、Windows 打 APK」的混合路线，比在容器里补齐 JDK 更快也更好维护：

| 路线 | 适用 | 说明 |
|---|---|---|
| **混合（Windows + WSL2）** 推荐 | 本机日常开发、Android Studio 里直接跑 | WSL 里只跑 `tools/build-native-deps.sh`；其余全在 Windows 侧 Gradle 完成。见下面「0-B」 |
| 纯容器（aarch64） | 完整的离线镜像方案 | 也就是第 1 节之后描述的 `tools/build-receiver.sh` 全流程；宿主是 aarch64，需要 qemu 模拟 x86_64 工具链 |

## 0-B. 混合路线：Windows + WSL2 构建接收端

### B1. 在 WSL 里预编译原生依赖

```bash
# 1) Linux 版 NDK r27（Windows 侧的 NDK 里的 clang 是 .exe，WSL 用不了）
mkdir -p ~/dl ~/opt && cd ~/dl
curl -sSL -o ndk.zip https://dl.google.com/android/repository/android-ndk-r27-linux.zip

# 2) 解包必须用「认符号链接」的解包器：python3 -m zipfile -e 和 ZipFile.extractall()
#    都会把符号链接写成普通小文件（bin/clang 变成内容是 "clang-18" 的 7 字节文本，
#    且不带执行位），于是 make 阶段报 `clang-18: error: no input files`。
#    tools/unzip-symlinks.py 处理 stat.S_ISLNK(external_attr >> 16) 并补执行位。
python3 <repo>/tools/unzip-symlinks.py ~/dl/ndk.zip ~/opt/
~/opt/android-ndk-r27/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --version   # 自检

# 3) 原生源码：third_party/ 被 .gitignore 忽略，由 tools/fetch-sources.sh 取（见第 3 节）。
#    把已取好的那份拷到 ext4（9p 上编译慢一个数量级），然后交叉编译
export CASTKIT_TREE=$HOME/build/castkit
mkdir -p "$CASTKIT_TREE/receiver/app/src/main/cpp" "$CASTKIT_TREE/tools"
cp -a <repo>/receiver/app/src/main/cpp/third_party "$CASTKIT_TREE/receiver/app/src/main/cpp/"
cp -f <repo>/tools/build-native-deps.sh "$CASTKIT_TREE/tools/"
export ANDROID_NDK_HOME=$HOME/opt/android-ndk-r27 JOBS=8 API_LEVEL=24
bash "$CASTKIT_TREE/tools/build-native-deps.sh" arm64-v8a      # 8 核约 40 秒
```

产物在 `$CASTKIT_TREE/native-deps/arm64-v8a/{openssl,ffmpeg}`。
`/home/...` 对 Windows 侧不可见，必须拷到 `/mnt/e/...` 或任何 `E:\` 路径下再用。

### B2. 在 Windows 上打包 APK

```powershell
# 一次性准备
#   ① NDK r27 (windows) 解到 E:\castkit-sdk\ndk；注意该 zip 的顶层是 android-ndk-r27/，要去掉这一层
#   ② cmake-3.22.1-windows.zip 解到 E:\castkit-sdk\cmake
#      注意这个 zip **没有**顶层目录（bin/、share/ 就在根），不要再剥一层
#   ③ 让 AGP 找得到 NDK：它不是装在 SDK 里的，用联结点挂进去（无需管理员）
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\Android\Sdk\ndk" | Out-Null
New-Item -ItemType Junction "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973" -Target "E:\castkit-sdk\ndk"

# ④ receiver\local.properties（被 .gitignore 忽略，每台机器自己写）
#    sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
#    cmake.dir=E\:\\castkit-sdk\\cmake

# ⑤ 关键：先把第三方源码适配补丁打上（平台无关，但 Windows 侧同样必须跑）
python tools\port-sources.py receiver\app\src\main\cpp\third_party .
#    不跑就会在 android_raop_callbacks.c 上报
#    error: use of undeclared identifier 'RESET_TYPE_HLS_CONN_CLOSED'

# ⑥ 打包（原生依赖路径用正斜杠，反斜杠会被 CMake 吃掉）
$env:GRADLE_USER_HOME = "E:\program\Aixiede\castkit\.tmp-gh"
cd receiver
.\gradlew.bat :app:assembleDebug -PcastkitAbis=arm64-v8a -PcastkitDepsRoot=E:/castkit-sdk/native-deps
#  产物: receiver\app\build\outputs\apk\debug\app-debug.apk
```

### B3. 混合路线踩过的坑（都已修好，改代码时注意别改回去）

| 现象 | 原因 / 处理 |
|---|---|
| `zsh: clang-18: error: no input files`（OpenSSL `make` 阶段） | `python3 -m zipfile -e` 不建符号链接。用 `tools/unzip-symlinks.py` |
| `***** Unsupported options: no-cast no-md2 …` + `Failure! build file wasn't produced.` | `tools/build-native-deps.sh` 里 OpenSSL 的整串 disable 选项被当成**一个** argv。每个选项必须独立传参。容器里被「复用 third_party/openssl-src 旧静态库」的快路径掩盖了 |
| `error: use of undeclared identifier 'RESET_TYPE_HLS_CONN_CLOSED'` | 没跑 `tools/port-sources.py`。见第 3 节「与上游的偏差」 |
| `Custom AAPT2 location does not point to an AAPT2 executable: /opt/castkit-host/bin/aapt2` | `receiver/gradle.properties` 里残留的容器专用 `android.aapt2FromMavenOverride`。已删除 |
| `Installed Build Tools revision 35.0.0 is corrupted` | 两侧共用同一个 SDK 目录，`35.0.0` 是 Linux 可执行文件、`36.0.0` 是 Windows `.exe`。两个工程的 `app/build.gradle.kts` 已按 `os.name` 分支 |
| `CastKit-Receiver…apk` 比预期大好几 MB | 旧包是增量打包留下的，zip 里有失效的本地条目（`PK\x03\x04` 计数远多于中央目录条目数）。`gradlew clean` 后重打即可 |


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

**但 0005 的 UxPlay 侧改动是编译必需的**：CastKit 自己的 `android_raop_callbacks.c` 引用了
`RESET_TYPE_HLS_CONN_CLOSED`，而上游 `942d7b2` 没有这个枚举。所以 `tools/port-sources.py`
把那部分手工移植进 UxPlay 源码（`raop.h` 加枚举、`raop.c` 记 `video_play_conn`、
`http_handlers.h` 在 play/stop 路径上维护它）。这个脚本是**平台无关**的，容器与 Windows 两侧
都要在构建前跑一次（幂等）。

## 4. 离线/兼容补丁

| 脚本 | 作用 |
|---|---|
| `tools/setup-host-compat.sh` | amd64 运行库 + qemu 包装 `aapt2`/`zipalign`/`aapt`；调用 `setup-ndk-host-wrappers.sh` |
| `tools/setup-ndk-host-wrappers.sh` | 把 NDK `bin/`（x86_64）换成指向本机 clang-18/lld-18/llvm-18 的包装脚本，并注入 `--sysroot`、`-resource-dir <NDK clang 资源目录>`、`-rtlib=compiler-rt`、`-unwindlib=libunwind` |
| `tools/port-sources.py` | **平台无关**：① `openssl-cmake` 改用本地 `openssl-src`（原 mirror.viaduck.org 不可达）② Gradle wrapper 指向腾讯镜像 ③ 手工移植 UxPlay 补丁 0005 的 UxPlay 侧改动。幂等，两侧构建前都要跑 |
| `tools/unzip-symlinks.py` | **平台无关**：认符号链接 + 还原执行位的 zip 解包器，用于解 NDK/CMake 这类含大量符号链接的包 |
| `tools/patch-sources.sh` | 调用 `port-sources.py`，外加容器专用的 `~/.gradle/init.d/mirrors.gradle`（Windows 侧不需要） |
| `tools/build-tree.sh` | 工作区 → `/root/castkit` 源码同步（排除 `third_party` 与 build 产物） |

## 5. 常见失败与处理

| 现象 | 处理 |
|---|---|
| `sdkmanager: Permission denied` | `chmod +x /opt/android-sdk/cmdline-tools/latest/bin/*` |
| `AAPT2 Daemon #0: Daemon startup failed` | 容器侧：确认 `tools/setup-host-compat.sh` 已生成 `/opt/castkit-host/bin/aapt2`。**不要**把 `android.aapt2FromMavenOverride` 写进 `gradle.properties`——Windows/Android Studio 侧会因此直接以 `Custom AAPT2 location does not point to an AAPT2 executable` 失败，且 `tools/` 里没有任何脚本会注入该属性 |
| `Installed Build Tools revision 35.0.0 is corrupted` / `missing AAPT at ...\35.0.0\aapt.exe` | 两侧共用同一个 SDK 目录，而 `build-tools/35.0.0` 是 Linux 可执行文件、`36.0.0` 是 Windows `.exe`。两个工程的 `app/build.gradle.kts` 已按 `os.name` 分支选版本（Windows→36.0.0，Linux→35.0.0）；若新增工程需照抄 |
| `ld.lld: unable to find library -lgcc / -latomic` | 重新执行 `tools/setup-ndk-host-wrappers.sh`（确认包装脚本含 `-resource-dir` 与 `-rtlib=compiler-rt`） |
| `./configure: Permission denied` | `bash tools/fetch-sources.sh`（会补齐脚本执行位）或手动 `chmod +x` 那两个 configure |
| `Host compiler lacks C11 support`（FFmpeg） | `apt-get install -y gcc` |
| `Daemon compilation failed: null`（KSP/Kotlin） | 已通过 `kotlin.compiler.execution.strategy=in-process` 规避（两个工程 gradle.properties） |
| 解析依赖卡在 “Still waiting for package manifests…” | 检查 `~/.gradle/init.d/mirrors.gradle`；仓库里已移除不可达的 `mavenCentral()`/`gradlePluginPortal()` |
| `gradlew`/脚本无法执行 | `/sdcard` 无 exec 位，一律 `bash <脚本>` |
