#!/usr/bin/env bash
# 构建发送端 App（默认 debug）
#   用法: bash tools/build-sender.sh [gradle 任务...]   默认 :app:assembleDebug
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

bash "$CASTKIT_ROOT/tools/build-tree.sh" sync_project sender
cd "$CASTKIT_TREE/sender"

# cmake.dir: 本机 aarch64，SDK 自带的 CMake/Ninja 是 x86_64，改用系统 cmake/ninja（apt）
cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
cmake.dir=/usr
EOF

TASK="${1:-:app:assembleDebug}"
shift || true

set +e
bash ./gradlew ${CASTKIT_GRADLE_ARGS} "$TASK" "$@"
STATUS=$?
set -e

mkdir -p "$CASTKIT_ROOT/out"
for variant in debug release; do
  for apk in "$CASTKIT_TREE/sender/app/build/outputs/apk/$variant/"*.apk; do
    [ -f "$apk" ] || continue
    cp -f "$apk" "$CASTKIT_ROOT/out/castkit-sender-$variant-$(basename "$apk")"
  done
done
ls -la "$CASTKIT_ROOT/out/"/*.apk 2>/dev/null || true

exit $STATUS
