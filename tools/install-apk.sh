#!/usr/bin/env bash
# 把构建产物交给用户：经 DSHA 桥导出到 Download/DSHA，并尝试直接拉起安装器。
#   用法: bash tools/install-apk.sh <apk 路径 | receiver|sender>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T="$(cat /root/.dsh/.bridge_token)"
BRIDGE="http://127.0.0.1:3090"

case "${1:-}" in
  receiver|"") APK="$ROOT/receiver/app/build/outputs/apk/debug/app-debug.apk" ;;
  sender)      APK="$ROOT/sender/app/build/outputs/apk/debug/app-debug.apk" ;;
  *)           APK="$1" ;;
esac

if [ ! -f "$APK" ]; then
  echo "找不到 APK: $APK" >&2
  echo "先构建: bash tools/build-receiver.sh 或 bash tools/build-sender.sh" >&2
  exit 1
fi

echo "导出 $APK …"
curl -s -G "$BRIDGE/app/export" --data-urlencode "path=$APK" | head -c 400
echo
NAME="$(basename "$APK")"
SRC="/sdcard/Download/DSHA/$NAME"
# 桥的导出会落成 <名字>.gz（内容其实是原生 APK），这里复制/改名为可直接点安装的 .apk
SHORT="$(echo "$NAME" | sed 's/castkit-receiver-debug-app-debug/castkit-receiver/; s/castkit-sender-debug-app-debug/castkit-sender/')"
if [ -f "$SRC.gz" ]; then
  cp -f "$SRC.gz" "/sdcard/Download/$SHORT"
elif [ -f "$SRC" ]; then
  cp -f "$SRC" "/sdcard/Download/$SHORT"
fi
echo "已放到 /sdcard/Download/$SHORT —— 在手机上用文件管理器点它安装（需允许「安装未知应用」）。"
echo "提示：接收端装好后在「设置 → 安卓投屏接收」里能看到本机 IP:端口，发送端填这个地址。"

