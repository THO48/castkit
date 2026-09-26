# flutter_sender —— Flutter 版发送端骨架

界面你写，能力用已跑通的 Kotlin 引擎。完整说明见 **[docs/FLUTTER.md](../docs/FLUTTER.md)**。

```bash
# 1) 生成工程（用你自己的 Flutter）
flutter create --org com.dsh --project-name castkit_sender --platforms android ~/castkit_sender
# 2) 一键接线
bash tools/setup-project.sh ~/castkit_sender
# 3) 构建
cd ~/castkit_sender && flutter pub get && flutter build apk --debug
```

- Dart 接口：`lib/castkit.dart`（设备/状态事件流 + 投屏/投视频/视频库/权限全部方法）
- 参考界面：`lib/main.dart`
- Kotlin 引擎：`android/engine/`（原样复用，包名不变）
- 通道层：`android/CastKitBridge.kt`、`android/MainActivity.kt`

⚠️ 本骨架未在本机编译验证（本机 aarch64，Flutter 只有 x64 Linux SDK），请在装了 Flutter 的机器上跑上面三步。
