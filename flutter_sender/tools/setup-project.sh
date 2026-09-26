#!/usr/bin/env bash
# CastKit —— 把 Kotlin 引擎与通道层接进一个 Flutter 工程。
#
# 用法：
#   1) 先自己生成工程（用你的 Flutter 版本，保证 Gradle/插件版本匹配）：
#        flutter create --org com.dsh --project-name castkit_sender --platforms android ~/castkit_sender
#   2) 再执行本脚本接好一切：
#        bash flutter_sender/tools/setup-project.sh ~/castkit_sender
#   3) 构建：
#        cd ~/castkit_sender && flutter build apk --debug
#
# 脚本会做的事：拷 Dart 接口与参考界面、拷 Kotlin 引擎与通道层、补字符串资源、
# 给 AndroidManifest 加权限与服务、给 app/build.gradle.kts 加引擎依赖、可选切国内镜像源。
set -euo pipefail

PROJ="${1:-}"
if [ -z "$PROJ" ] || [ ! -d "$PROJ" ]; then
  echo "用法: bash $0 <flutter 工程目录>" >&2
  exit 1
fi

HERE="$(cd "$(dirname "$0")/.." && pwd)"
KT_APP="$PROJ/android/app/src/main/kotlin"
PKG_DIR="$KT_APP/com/dsh/castkit/flutter"
ENGINE_DIR="$KT_APP/com/dsh/castkit/sender"

echo "==> 1/5 拷贝 Dart 接口与参考界面"
mkdir -p "$PROJ/lib"
cp -f "$HERE/lib/castkit.dart" "$PROJ/lib/castkit.dart"
if [ -f "$PROJ/lib/main.dart" ]; then
  cp -f "$PROJ/lib/main.dart" "$PROJ/lib/main.dart.bak.$(date +%s)"
fi
cp -f "$HERE/lib/main.dart" "$PROJ/lib/main.dart"

echo "==> 2/5 拷贝 Kotlin 引擎（原样，包名不变）与通道层"
mkdir -p "$PKG_DIR" "$ENGINE_DIR"
cp -f "$HERE/android/MainActivity.kt" "$HERE/android/CastKitBridge.kt" "$PKG_DIR/"
rm -rf "$ENGINE_DIR/cast" "$ENGINE_DIR/net" "$ENGINE_DIR/media"
cp -r "$HERE/android/engine/." "$ENGINE_DIR/"

echo "==> 3/5 补引擎用到的字符串资源"
mkdir -p "$PROJ/android/app/src/main/res/values"
cp -f "$HERE/android/engine_strings.xml" "$PROJ/android/app/src/main/res/values/engine_strings.xml"

echo "==> 4/5 打补丁：AndroidManifest / build.gradle.kts"
python3 - "$PROJ" <<'PY'
import io, os, re, sys

proj = sys.argv[1]
here = os.path.dirname(os.path.dirname(os.path.abspath(sys.argv[0]))) if False else None

MANIFEST = os.path.join(proj, 'android/app/src/main/AndroidManifest.xml')
GRADLE = os.path.join(proj, 'android/app/build.gradle.kts')
if not os.path.exists(GRADLE):
    GRADLE = os.path.join(proj, 'android/app/build.gradle')  # 老模板是 groovy

perms = """    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
"""

services = """
        <!-- CastKit 引擎的前台服务（别改名，引擎里按类名启动） -->
        <service
            android:name="com.dsh.castkit.sender.cast.CastService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
        <service
            android:name="com.dsh.castkit.sender.cast.VideoCastService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
"""

t = io.open(MANIFEST, encoding='utf-8').read()
if 'READ_MEDIA_VIDEO' not in t:
    t = re.sub(r'(<manifest[^>]*>\n)', r'\1' + perms, t, count=1)
if 'CastKitApp' not in t:
    t = re.sub(r'<application\b', '<application\n        android:name="com.dsh.castkit.sender.CastKitApp"', t, count=1)
if 'VideoCastService' not in t:
    t = t.replace('</application>', services + '\n    </application>', 1)
io.open(MANIFEST, 'w', encoding='utf-8').write(t)
print('   manifest 已打补丁:', MANIFEST)

g = io.open(GRADLE, encoding='utf-8').read()
# 引擎需要 core-ktx（NotificationCompat/ServiceCompat）与协程
deps = []
if 'androidx.core:core-ktx' not in g:
    deps.append('    implementation("androidx.core:core-ktx:1.15.0")')
if 'kotlinx-coroutines-android' not in g:
    deps.append('    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")')
if deps:
    if re.search(r'dependencies\s*\{', g):
        g = re.sub(r'(dependencies\s*\{\n)', r'\1' + '\n'.join(deps) + '\n', g, count=1)
    else:
        g += '\n\ndependencies {\n' + '\n'.join(deps) + '\n}\n'
# 引擎用到 MediaProjection/MediaStore，minSdk 至少 26
g = re.sub(r'minSdk\s*=\s*flutter\.minSdkVersion', 'minSdk = 26', g)
g = re.sub(r'minSdkVersion\s+flutter\.minSdkVersion', 'minSdkVersion 26', g)
io.open(GRADLE, 'w', encoding='utf-8').write(g)
print('   gradle 已打补丁:', GRADLE)
PY

echo "==> 5/5 可选：把 Maven 源换成阿里云镜像（国内网络更快；不需要就跳过）"
if [ "${USE_ALIYUN:-0}" = "1" ]; then
  python3 - "$PROJ" <<'PY'
import io, os, re, sys
proj = sys.argv[1]
for rel in ['android/settings.gradle.kts', 'android/build.gradle.kts', 'android/settings.gradle', 'android/build.gradle']:
    p = os.path.join(proj, rel)
    if not os.path.exists(p):
        continue
    t = io.open(p, encoding='utf-8').read()
    if 'maven.aliyun.com' in t:
        continue
    if rel.endswith('.kts'):
        t = t.replace('google()', 'maven { url = uri("https://maven.aliyun.com/repository/google") }')
        t = t.replace('mavenCentral()', 'maven { url = uri("https://maven.aliyun.com/repository/public") }')
    else:
        t = t.replace('google()', 'maven { url "https://maven.aliyun.com/repository/google" }')
        t = t.replace('mavenCentral()', 'maven { url "https://maven.aliyun.com/repository/public" }')
    io.open(p, 'w', encoding='utf-8').write(t)
    print('   已切镜像:', rel)
PY
fi

cat <<'TIP'

接好了 ✅
接下来：
  cd <你的工程>
  flutter pub get
  flutter build apk --debug        # 产物: build/app/outputs/flutter-apk/app-debug.apk

注意：
  * <application> 已指向引擎的 CastKitApp（创建通知渠道）；类名写的是全限定名，不受 namespace 影响。
  * 两个前台服务用的是引擎里的全限定类名，别改名。
  * 引擎代码在 android/app/src/main/kotlin/com/dsh/castkit/sender/ 下，原样复用、可单独维护。
  * Dart 侧所有接口都在 lib/castkit.dart，注释里写了每个方法的用途。
TIP
