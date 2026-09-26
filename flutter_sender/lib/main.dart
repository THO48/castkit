import 'package:flutter/material.dart';

import 'castkit.dart';

/// **参考界面**（不是成品，随你替换）。
///
/// 它把 `castkit.dart` 里的接口都用了一遍，作为对接示例：
/// 设备发现事件流、投屏状态事件流、开始/停止镜像、选文件并投视频、投送中当遥控器。
void main() => runApp(const CastKitDemoApp());

class CastKitDemoApp extends StatelessWidget {
  const CastKitDemoApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'CastKit 发送端',
        theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.blue),
        home: const HomePage(),
      );
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  CastReceiver? _target;
  String? _pickedVideo;
  String? _pickedName;
  int _localPositionMs = 0;

  @override
  void initState() {
    super.initState();
    CastKit.discoveryStart(); // 进入界面即开始搜索；离开时记得 discoveryStop()
    CastKit.getConfig().then((cfg) {
      if (mounted && cfg.host.isNotEmpty) {
        setState(() => _target = CastReceiver(
              id: '${cfg.host}:${cfg.port}',
              name: '上次使用',
              host: cfg.host,
              port: cfg.port,
            ));
      }
    });
  }

  @override
  void dispose() {
    CastKit.discoveryStop();
    super.dispose();
  }

  Future<void> _pickVideo() async {
    final uri = await CastKit.pickVideo();
    if (uri == null) return;
    final info = await CastKit.describeVideo(uri);
    setState(() {
      _pickedVideo = uri;
      _pickedName = info?.name ?? uri;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('CastKit 发送端')),
      body: StreamBuilder<CastState>(
        stream: CastKit.stateStream,
        builder: (context, stateSnap) {
          final state = stateSnap.data;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _statusCard(state),
              const SizedBox(height: 16),

              // ---------- 设备发现（事件流）----------
              const Text('接收端', style: TextStyle(fontWeight: FontWeight.bold)),
              StreamBuilder<List<CastReceiver>>(
                stream: CastKit.receiverStream,
                builder: (context, snap) {
                  final list = snap.data ?? const <CastReceiver>[];
                  if (list.isEmpty) {
                    return const ListTile(
                      title: Text('正在搜索同一 Wi-Fi 下的接收端…'),
                      trailing: SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    );
                  }
                  return Column(
                    children: [
                      for (final r in list)
                        RadioListTile<String>(
                          value: r.id,
                          groupValue: _target?.id,
                          title: Text(r.name),
                          subtitle: Text('${r.host}:${r.port}'),
                          onChanged: (_) async {
                            setState(() => _target = r);
                            await CastKit.setTarget(host: r.host, port: r.port);
                          },
                        ),
                      TextButton(
                        onPressed: CastKit.discoveryProbe,
                        child: const Text('重新搜索'),
                      ),
                    ],
                  );
                },
              ),
              const Divider(height: 32),

              // ---------- 镜像投屏 ----------
              const Text('镜像投屏', style: TextStyle(fontWeight: FontWeight.bold)),
              const Text('需要系统录屏授权；接收端按手机屏幕比例显示，比例不同会有黑边。',
                  style: TextStyle(fontSize: 12)),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: state?.isRunning == true && state?.mode == CastMode.mirror
                    ? null
                    : () async {
                        if (_target == null) return;
                        final ok = await CastKit.startMirror();
                        if (!ok && context.mounted) {
                          ScaffoldMessenger.of(context)
                              .showSnackBar(const SnackBar(content: Text('未获得录屏授权')));
                        }
                      },
                child: const Text('开始镜像投屏'),
              ),
              const SizedBox(height: 24),

              // ---------- 投视频文件 ----------
              const Text('投视频文件', style: TextStyle(fontWeight: FontWeight.bold)),
              const Text('接收端用系统播放器播原文件：原画质、有声音、可拖进度，不需要录屏授权。',
                  style: TextStyle(fontSize: 12)),
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(_pickedName ?? '未选择视频'),
                subtitle: _pickedVideo == null ? null : Text(_pickedVideo!),
                trailing: TextButton(onPressed: _pickVideo, child: const Text('选择')),
              ),
              FilledButton(
                onPressed: _pickedVideo == null || _target == null
                    ? null
                    : () async {
                        await CastKit.startVideoCast(
                          uri: _pickedVideo!,
                          host: _target!.host,
                          port: _target!.port,
                          startPositionMs: _localPositionMs, // 本机播放进度，接收端接着播
                        );
                      },
                child: const Text('投到接收端'),
              ),
              if (state?.isRemote == true) ...[
                const SizedBox(height: 12),
                Text('投送中 · 接收端进度 ${formatDuration(state!.remotePositionMs)} / '
                    '${formatDuration(state.remoteDurationMs)}'),
                Row(
                  children: [
                    IconButton(
                      onPressed: () => CastKit.videoControl(CastKit.actionToggle),
                      icon: Icon(state.remotePlaying ? Icons.pause : Icons.play_arrow),
                    ),
                    Expanded(
                      child: Slider(
                        value: state.remoteDurationMs == 0
                            ? 0
                            : (state.remotePositionMs / state.remoteDurationMs).clamp(0, 1),
                        onChanged: (v) {}, // 拖动中只更新本地 UI
                        onChangeEnd: (v) => CastKit.videoControl(
                          CastKit.actionSeek,
                          (v * state.remoteDurationMs).round(),
                        ),
                      ),
                    ),
                    TextButton(
                      onPressed: CastKit.stopVideoCast,
                      child: const Text('停止投送'),
                    ),
                  ],
                ),
              ],
            ],
          );
        },
      ),
    );
  }

  Widget _statusCard(CastState? s) {
    if (s == null) return const Card(child: ListTile(title: Text('就绪')));
    final label = switch (s.phase) {
      CastPhase.idle => '未投屏',
      CastPhase.connecting => '连接中…',
      CastPhase.running => '投屏中',
      CastPhase.reconnecting => '重连中…',
      CastPhase.error => '错误：${s.message}',
    };
    return Card(
      child: ListTile(
        leading: Icon(
          s.phase == CastPhase.running ? Icons.cast_connected : Icons.cast,
          color: s.phase == CastPhase.error ? Colors.red : null,
        ),
        title: Text(label),
        subtitle: Text([
          if (s.host.isNotEmpty) '${s.host}:${s.port}',
          if (s.width > 0) '${s.width}×${s.height} @${s.fps}fps',
          if (s.phase == CastPhase.running)
            '实测 ${(s.measuredKbps / 1000).toStringAsFixed(1)} Mbps · ${s.measuredFps.toStringAsFixed(0)} fps',
        ].join('\n')),
      ),
    );
  }
}
