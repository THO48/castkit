import 'dart:typed_data';

import 'package:flutter/services.dart';

/// CastKit 发送端 —— Kotlin 引擎的 Dart 门面。
///
/// **界面归你，能力归我**：录屏、编码、LANCast 协议、局域网 HTTP 文件服务、
/// 视频库扫描、权限、方向控制全部在 Kotlin 引擎里（原样复用，未重写），
/// 这里只把它们包成方法调用与事件流。
///
/// 三类用法：
/// ```dart
/// // 1) 事件流：投屏状态（约每秒刷新）与发现的设备
/// StreamBuilder<CastState>(stream: CastKit.stateStream, builder: ...);
/// StreamBuilder<List<CastReceiver>>(stream: CastKit.receiverStream, builder: ...);
///
/// // 2) 命令
/// await CastKit.startMirror();          // 会弹出系统录屏授权
/// await CastKit.startVideoCast(uri: uri, host: r.host, port: r.port);
///
/// // 3) 视频库
/// final folders = await CastKit.listFolders(includeNoMedia: false);
/// final videos  = await CastKit.listVideos(folderPath: folders.first.path);
/// final thumb   = await CastKit.loadThumbnail(videos.first.uri);   // Image.memory(thumb!)
/// ```
class CastKit {
  CastKit._();

  static const MethodChannel _methods = MethodChannel('com.dsh.castkit/methods');
  static const EventChannel _stateEvents = EventChannel('com.dsh.castkit/state');
  static const EventChannel _receiverEvents = EventChannel('com.dsh.castkit/receivers');

  // ---------------- 遥控动作（投视频文件时用） ----------------
  static const int actionPlay = 1;
  static const int actionPause = 2;
  static const int actionToggle = 3;
  static const int actionSeek = 4;

  // ---------------- 事件流 ----------------

  /// 投屏状态：阶段、模式、目标地址、分辨率/码率/实测速率、接收端回报的播放进度。
  static Stream<CastState> get stateStream => _stateEvents
      .receiveBroadcastStream()
      .map((e) => CastState.fromMap(Map<String, dynamic>.from(e as Map)));

  /// 局域网里发现的接收端（自动发现，约 15 秒无心跳即摘除）。
  static Stream<List<CastReceiver>> get receiverStream => _receiverEvents
      .receiveBroadcastStream()
      .map((e) => (e as List)
          .map((it) => CastReceiver.fromMap(Map<String, dynamic>.from(it as Map)))
          .toList());

  // ---------------- 配置 ----------------

  /// 读取当前投屏配置（含"实际投出尺寸"，已按屏幕比例与编码器约束算好）。
  static Future<CastConfig> getConfig() async {
    final map = await _methods.invokeMethod<Map<dynamic, dynamic>>('getConfig');
    return CastConfig.fromMap(Map<String, dynamic>.from(map ?? {}));
  }

  /// 保存镜像投屏参数。
  static Future<void> saveConfig({
    required String preset,
    required int customW,
    required int customH,
    required int fps,
    required int mbps,
    required String host,
    required int port,
    required bool keepAspect,
  }) =>
      _methods.invokeMethod('saveConfig', {
        'preset': preset,
        'customW': customW,
        'customH': customH,
        'fps': fps,
        'mbps': mbps,
        'host': host,
        'port': port,
        'keepAspect': keepAspect,
      });

  /// 只改目标地址（快捷投屏选中设备后调用）。
  static Future<void> setTarget({
    required String host,
    required int port,
    bool auto = true,
  }) =>
      _methods.invokeMethod('setTarget', {'host': host, 'port': port, 'auto': auto});

  // ---------------- 镜像投屏 ----------------

  /// 开始镜像投屏。内部会拉起系统录屏授权：用户同意返回 true，拒绝返回 false。
  static Future<bool> startMirror() async =>
      await _methods.invokeMethod<bool>('startMirror') ?? false;

  static Future<void> stopMirror() => _methods.invokeMethod('stopMirror');

  // ---------------- 投视频文件 ----------------

  /// 系统文件选择器选一个视频，返回其 uri（取消返回 null）。
  static Future<String?> pickVideo() => _methods.invokeMethod<String>('pickVideo');

  /// 取文件名/大小/MIME（用于界面展示）。
  static Future<VideoSource?> describeVideo(String uri) async {
    final map = await _methods.invokeMethod<Map<dynamic, dynamic>>('describeVideo', {'uri': uri});
    return map == null ? null : VideoSource.fromMap(Map<String, dynamic>.from(map));
  }

  /// 开始投视频：把文件通过局域网 HTTP 交给接收端原生播放。
  /// [startPositionMs] 传本机播放进度，接收端会从同一位置接着播。
  static Future<bool> startVideoCast({
    required String uri,
    required String host,
    required int port,
    int startPositionMs = 0,
  }) async =>
      await _methods.invokeMethod<bool>('startVideoCast', {
        'uri': uri,
        'host': host,
        'port': port,
        'startPositionMs': startPositionMs,
      }) ??
      false;

  static Future<void> stopVideoCast() => _methods.invokeMethod('stopVideoCast');

  /// 投送中当遥控器：播放/暂停/切换/seek（用 actionXxx 常量）。
  static Future<void> videoControl(int action, [int value = 0]) =>
      _methods.invokeMethod('videoControl', {'action': action, 'value': value});

  // ---------------- 设备发现 ----------------

  static Future<void> discoveryStart() => _methods.invokeMethod('discoveryStart');
  static Future<void> discoveryStop() => _methods.invokeMethod('discoveryStop');
  static Future<void> discoveryProbe() => _methods.invokeMethod('discoveryProbe');

  // ---------------- 视频库 ----------------

  /// 某个目录下的视频（[folderPath] 传 null 表示全部，传 '' 表示内部存储根目录）。
  static Future<List<VideoItem>> listVideos({
    String? folderPath,
    bool includeNoMedia = false,
    VideoSort sortBy = VideoSort.date,
    bool ascending = false,
  }) async {
    final list = await _methods.invokeMethod<List<dynamic>>('listVideos', {
      'folderPath': folderPath,
      'includeNoMedia': includeNoMedia,
      'sortBy': sortBy.name,
      'ascending': ascending,
    });
    return (list ?? [])
        .map((it) => VideoItem.fromMap(Map<String, dynamic>.from(it as Map)))
        .toList();
  }

  /// 子文件夹（已聚合数量/封面/排序键）。层级浏览用这个。
  static Future<List<VideoFolder>> listFolders({
    String folderPath = '',
    bool includeNoMedia = false,
    VideoSort sortBy = VideoSort.date,
    bool ascending = false,
  }) async {
    final list = await _methods.invokeMethod<List<dynamic>>('listFolders', {
      'folderPath': folderPath,
      'includeNoMedia': includeNoMedia,
      'sortBy': sortBy.name,
      'ascending': ascending,
    });
    return (list ?? [])
        .map((it) => VideoFolder.fromMap(Map<String, dynamic>.from(it as Map)))
        .toList();
  }

  /// 缩略图 JPEG 字节（内存 + 磁盘缓存 + 并发限流都在引擎里）→ `Image.memory(bytes)`。
  /// 媒体库条目走系统缩略图，`.nomedia` 条目直接抽帧；失败返回 null。
  static Future<Uint8List?> loadThumbnail(String uri) =>
      _methods.invokeMethod<Uint8List>('loadThumbnail', {'uri': uri});

  /// 时长（毫秒）。媒体库条目直接有值；`.nomedia` 条目按需解析并缓存。
  static Future<int> loadDuration(String uri) async =>
      await _methods.invokeMethod<int>('loadDuration', {'uri': uri}) ?? 0;

  // ---------------- 权限 ----------------

  static Future<bool> hasMediaPermission() async =>
      await _methods.invokeMethod<bool>('hasMediaPermission') ?? false;

  static Future<bool> requestMediaPermission() async =>
      await _methods.invokeMethod<bool>('requestMediaPermission') ?? false;

  /// 是否已有「所有文件访问」权限（显示 `.nomedia` 目录需要）。
  static Future<bool> canReadAllFiles() async =>
      await _methods.invokeMethod<bool>('canReadAllFiles') ?? false;

  static Future<void> openAllFilesSettings() => _methods.invokeMethod('openAllFilesSettings');
}

// ======================= 数据模型 =======================

enum CastPhase { idle, connecting, running, reconnecting, error }

enum CastMode { mirror, video }

enum VideoSort { date, name, size, duration }

class CastState {
  const CastState({
    required this.phase,
    required this.mode,
    this.message = '',
    this.host = '',
    this.port = 0,
    this.width = 0,
    this.height = 0,
    this.fps = 0,
    this.bitrateBps = 0,
    this.measuredKbps = 0,
    this.measuredFps = 0,
    this.localPlayerActive = false,
    this.remotePositionMs = 0,
    this.remoteDurationMs = 0,
    this.remotePlaying = false,
    this.remoteBuffering = false,
  });

  final CastPhase phase;
  final CastMode mode;
  final String message;
  final String host;
  final int port;
  final int width;
  final int height;
  final int fps;
  final int bitrateBps;
  final int measuredKbps;
  final double measuredFps;

  /// 应用内播放器是否在前台（引擎据此停止镜像跟随本机旋转）。
  final bool localPlayerActive;

  /// 投视频文件时**接收端**回报的播放进度（进度条要显示这个）。
  final int remotePositionMs;
  final int remoteDurationMs;
  final bool remotePlaying;
  final bool remoteBuffering;

  bool get isRunning =>
      phase == CastPhase.running || phase == CastPhase.connecting || phase == CastPhase.reconnecting;

  /// 投送中：界面切成"遥控器"。
  bool get isRemote => mode == CastMode.video && isRunning;

  factory CastState.fromMap(Map<String, dynamic> m) => CastState(
        phase: CastPhase.values.firstWhere((e) => e.name == m['phase'],
            orElse: () => CastPhase.idle),
        mode: CastMode.values.firstWhere((e) => e.name == m['mode'],
            orElse: () => CastMode.mirror),
        message: m['message'] as String? ?? '',
        host: m['host'] as String? ?? '',
        port: (m['port'] as num?)?.toInt() ?? 0,
        width: (m['width'] as num?)?.toInt() ?? 0,
        height: (m['height'] as num?)?.toInt() ?? 0,
        fps: (m['fps'] as num?)?.toInt() ?? 0,
        bitrateBps: (m['bitrateBps'] as num?)?.toInt() ?? 0,
        measuredKbps: (m['measuredKbps'] as num?)?.toInt() ?? 0,
        measuredFps: (m['measuredFps'] as num?)?.toDouble() ?? 0,
        localPlayerActive: m['localPlayerActive'] as bool? ?? false,
        remotePositionMs: (m['remotePositionMs'] as num?)?.toInt() ?? 0,
        remoteDurationMs: (m['remoteDurationMs'] as num?)?.toInt() ?? 0,
        remotePlaying: m['remotePlaying'] as bool? ?? false,
        remoteBuffering: m['remoteBuffering'] as bool? ?? false,
      );
}

class CastReceiver {
  const CastReceiver({required this.id, required this.name, required this.host, required this.port});

  final String id;
  final String name;
  final String host;
  final int port;

  String get label => '$name（$host:$port）';

  factory CastReceiver.fromMap(Map<String, dynamic> m) => CastReceiver(
        id: m['id'] as String? ?? '',
        name: m['name'] as String? ?? '接收端',
        host: m['host'] as String? ?? '',
        port: (m['port'] as num?)?.toInt() ?? 8123,
      );
}

class CastConfig {
  const CastConfig({
    required this.host,
    required this.port,
    required this.preset,
    required this.customW,
    required this.customH,
    required this.fps,
    required this.mbps,
    required this.keepAspect,
    required this.effectiveWidth,
    required this.effectiveHeight,
    this.sizeNote,
    required this.autoDiscovered,
    required this.presets,
    required this.nativeWidth,
    required this.nativeHeight,
    required this.defaultFps,
    required this.defaultMbps,
    required this.defaultPort,
  });

  final String host;
  final int port;
  final String preset;
  final int customW;
  final int customH;
  final int fps;
  final int mbps;
  final bool keepAspect;
  final int effectiveWidth;
  final int effectiveHeight;
  final String? sizeNote;
  final bool autoDiscovered;
  final List<(String, String)> presets; // (枚举名, 显示名)
  final int nativeWidth;
  final int nativeHeight;
  final int defaultFps;
  final int defaultMbps;
  final int defaultPort;

  factory CastConfig.fromMap(Map<String, dynamic> m) {
    final native = (m['nativeSize'] as List?) ?? const [1280, 720];
    final defaults = (m['defaults'] as Map?) ?? const {};
    return CastConfig(
      host: m['host'] as String? ?? '',
      port: (m['port'] as num?)?.toInt() ?? 8123,
      preset: m['preset'] as String? ?? 'P720',
      customW: (m['customW'] as num?)?.toInt() ?? 720,
      customH: (m['customH'] as num?)?.toInt() ?? 720,
      fps: (m['fps'] as num?)?.toInt() ?? 30,
      mbps: (m['mbps'] as num?)?.toInt() ?? 8,
      keepAspect: m['keepAspect'] as bool? ?? true,
      effectiveWidth: (m['effectiveWidth'] as num?)?.toInt() ?? 0,
      effectiveHeight: (m['effectiveHeight'] as num?)?.toInt() ?? 0,
      sizeNote: m['sizeNote'] as String?,
      autoDiscovered: m['autoDiscovered'] as bool? ?? true,
      presets: ((m['presets'] as List?) ?? const [])
          .map((e) => ((e as List)[0] as String, e[1] as String))
          .toList(),
      nativeWidth: (native.isNotEmpty ? native[0] as num : 1280).toInt(),
      nativeHeight: (native.length > 1 ? native[1] as num : 720).toInt(),
      defaultFps: (defaults['fps'] as num?)?.toInt() ?? 30,
      defaultMbps: (defaults['mbps'] as num?)?.toInt() ?? 8,
      defaultPort: (defaults['port'] as num?)?.toInt() ?? 8123,
    );
  }
}

class VideoSource {
  const VideoSource({required this.uri, required this.name, required this.size, required this.mime});

  final String uri;
  final String name;
  final int size;
  final String mime;

  String get sizeLabel => size <= 0
      ? ''
      : size >= 1 << 30
          ? '${(size / 1024 / 1024 / 1024).toStringAsFixed(2)} GB'
          : '${(size / 1024 / 1024).toStringAsFixed(0)} MB';

  factory VideoSource.fromMap(Map<String, dynamic> m) => VideoSource(
        uri: m['uri'] as String? ?? '',
        name: m['name'] as String? ?? '',
        size: (m['size'] as num?)?.toInt() ?? -1,
        mime: m['mime'] as String? ?? '',
      );
}

class VideoItem {
  const VideoItem({
    required this.uri,
    required this.name,
    required this.folderPath,
    required this.durationMs,
    required this.sizeBytes,
    required this.dateAddedSec,
    required this.width,
    required this.height,
  });

  final String uri;
  final String name;
  final String folderPath;
  final int durationMs;
  final int sizeBytes;
  final int dateAddedSec;
  final int width;
  final int height;

  factory VideoItem.fromMap(Map<String, dynamic> m) => VideoItem(
        uri: m['uri'] as String? ?? '',
        name: m['name'] as String? ?? '',
        folderPath: m['folderPath'] as String? ?? '',
        durationMs: (m['durationMs'] as num?)?.toInt() ?? 0,
        sizeBytes: (m['sizeBytes'] as num?)?.toInt() ?? 0,
        dateAddedSec: (m['dateAddedSec'] as num?)?.toInt() ?? 0,
        width: (m['width'] as num?)?.toInt() ?? 0,
        height: (m['height'] as num?)?.toInt() ?? 0,
      );
}

class VideoFolder {
  const VideoFolder({
    required this.path,
    required this.name,
    required this.directCount,
    required this.totalCount,
    required this.subFolderCount,
    this.coverUri,
    required this.aggSizeBytes,
    required this.aggDurationMs,
  });

  final String path;
  final String name;
  final int directCount;
  final int totalCount;
  final int subFolderCount;
  final String? coverUri;
  final int aggSizeBytes;
  final int aggDurationMs;

  factory VideoFolder.fromMap(Map<String, dynamic> m) => VideoFolder(
        path: m['path'] as String? ?? '',
        name: m['name'] as String? ?? '',
        directCount: (m['directCount'] as num?)?.toInt() ?? 0,
        totalCount: (m['totalCount'] as num?)?.toInt() ?? 0,
        subFolderCount: (m['subFolderCount'] as num?)?.toInt() ?? 0,
        coverUri: m['coverUri'] as String?,
        aggSizeBytes: (m['aggSizeBytes'] as num?)?.toInt() ?? 0,
        aggDurationMs: (m['aggDurationMs'] as num?)?.toInt() ?? 0,
      );
}

/// 时长格式化：`mm:ss` / `h:mm:ss`。
String formatDuration(int ms) {
  if (ms <= 0) return '--:--';
  final total = ms ~/ 1000;
  final h = total ~/ 3600;
  final m = (total % 3600) ~/ 60;
  final s = total % 60;
  return h > 0
      ? '$h:${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}'
      : '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
}
