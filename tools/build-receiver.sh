#!/usr/bin/env bash
# 构建接收端 App（默认 debug + arm64-v8a）
#   用法: bash tools/build-receiver.sh [gradle 任务...]   默认 :app:assembleDebug
#
# 构建在 $CASTKIT_TREE（默认 /root/castkit，可执行文件系统）里进行，
# 产物会回拷到 out/。原因见 tools/build-tree.sh。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

bash "$CASTKIT_ROOT/tools/build-tree.sh" sync_project receiver
cd "$CASTKIT_TREE/receiver"

# cmake.dir: 本机 aarch64，SDK 自带的 CMake/Ninja 是 x86_64，改用系统 cmake/ninja（apt）
cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
cmake.dir=/usr
EOF

TASK="${1:-:app:assembleDebug}"
shift || true

# 预构建原生依赖（OpenSSL + 最小 FFmpeg）；原因见 tools/build-native-deps.sh 顶部
ABI="${CASTKIT_ABIS:-arm64-v8a}"
if [ "$ABI" = "arm64-v8a" ]; then
  bash "$CASTKIT_ROOT/tools/build-native-deps.sh" "$ABI"
fi

set +e
bash ./gradlew \
  ${CASTKIT_GRADLE_ARGS} \
  -PcastkitAbis="$ABI" \
  -PcastkitDepsRoot="$CASTKIT_TREE/native-deps" \
  "$TASK" "$@"
STATUS=$?
set -e

# 把产物回拷到工作区 out/
mkdir -p "$CASTKIT_ROOT/out"
for variant in debug release; do
  for apk in "$CASTKIT_TREE/receiver/app/build/outputs/apk/$variant/"*.apk; do
    [ -f "$apk" ] || continue
    cp -f "$apk" "$CASTKIT_ROOT/out/castkit-receiver-$variant-$(basename "$apk")"
  done
done
ls -la "$CASTKIT_ROOT/out/"/*.apk 2>/dev/null || true

exit $STATUS
