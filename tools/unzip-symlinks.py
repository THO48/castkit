#!/usr/bin/env python3
"""认符号链接的 zip 解包器（跨平台，纯标准库）。

用法: python3 tools/unzip-symlinks.py <archive.zip> <dest_dir/>

为什么需要它：
    `python3 -m zipfile -e` 和 Python 的 `ZipFile.extractall()` 都**不会**创建符号链接，
    而是把链接目标当成文件内容写出来。Android NDK 的
    `toolchains/llvm/prebuilt/linux-x86_64/bin/` 里大量使用相对符号链接，例如

        clang -> clang-18
        clang++ -> clang

    被扁平化之后 `clang` 就变成一个内容是 `clang-18` 的小文本文件。之后任何
    `make` / `configure` 调用编译器都会失败在

        clang-18: error: no input files

    而且它是**静默**的：解包不报错，只有真正开始编译才炸。

    `unzip` 能正确处理，但 WSL 的 Debian 精简镜像里默认没装（且没有 sudo 装不了），
    所以这里用标准库自己解。

同时处理执行位：zip 里 Unix 权限存在 `external_attr` 的高 16 位，
普通解包器不会还原，于是 `*.sh` / `configure` 会报 `Permission denied`。
"""

import os
import stat
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    zip_path, dest = sys.argv[1], sys.argv[2]
    z = zipfile.ZipFile(zip_path)
    files = dirs = links = 0

    for info in z.infolist():
        mode = info.external_attr >> 16
        target = os.path.join(dest, info.filename)

        if info.filename.endswith("/"):
            os.makedirs(target, exist_ok=True)
            dirs += 1
            continue

        parent = os.path.dirname(target)
        if parent:
            os.makedirs(parent, exist_ok=True)

        # 符号链接：内容是链接目标，不是文件数据
        if stat.S_ISLNK(mode):
            linkto = z.read(info).decode("utf-8", "replace")
            if os.path.lexists(target):
                os.remove(target)
            os.symlink(linkto, target)
            links += 1
            continue

        with z.open(info) as src, open(target, "wb") as dst:
            while True:
                chunk = src.read(1 << 20)
                if not chunk:
                    break
                dst.write(chunk)

        # 可执行位
        if mode & 0o111:
            os.chmod(target, 0o755)

        files += 1

    print(f"extracted files={files} dirs={dirs} symlinks={links} -> {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
