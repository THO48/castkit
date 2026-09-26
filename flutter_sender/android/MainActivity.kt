package com.dsh.castkit.flutter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter 宿主 Activity。
 *
 * 除了常规的通道注册，它还负责三件**必须由 Activity 完成**的事：
 *  1. 录屏授权（MediaProjection 的 `createScreenCaptureIntent`）
 *  2. 读取视频权限（Android 13+ 的 READ_MEDIA_VIDEO）
 *  3. 选视频文件（SAF 的 ACTION_OPEN_DOCUMENT）
 * 这三件事通过 [CastKitBridge.HostRequests] 回给桥层。
 */
class MainActivity : FlutterActivity(), CastKitBridge.HostRequests {

    private var captureCallback: ((resultCode: Int, data: Intent?) -> Unit)? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var documentCallback: ((String?) -> Unit)? = null

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = captureCallback
            captureCallback = null
            cb?.invoke(r.resultCode, r.data)
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = permissionCallback
            permissionCallback = null
            cb?.invoke(granted)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val documentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = documentCallback
            documentCallback = null
            if (r.resultCode == RESULT_OK) {
                val uri = r.data?.data
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                cb?.invoke(uri?.toString())
            } else {
                cb?.invoke(null)
            }
        }

    private lateinit var bridge: CastKitBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = CastKitBridge(this, this)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CastKitBridge.METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                val args = call.arguments as? Map<String, Any?> ?: emptyMap()
                runCatching {
                    bridge.onMethodCall(
                        call = call.method,
                        args = args,
                        result = { result.success(it) },
                        error = { code, msg -> result.error(code, msg, null) },
                    )
                }.onFailure { result.error("E_INTERNAL", it.message ?: "内部错误", null) }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, CastKitBridge.STATE_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    bridge.onStateListen(true) { events.success(it) }
                }

                override fun onCancel(arguments: Any?) {
                    bridge.onStateListen(false) { }
                }
            })

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, CastKitBridge.RECEIVERS_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    bridge.onReceiversListen(true) { events.success(it) }
                }

                override fun onCancel(arguments: Any?) {
                    bridge.onReceiversListen(false) { }
                }
            })
    }

    // ---------- CastKitBridge.HostRequests ----------

    override fun requestScreenCapture(onResult: (resultCode: Int, data: Intent?) -> Unit) {
        captureCallback = onResult
        val mgr = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    override fun requestReadMediaVideo(onResult: (granted: Boolean) -> Unit) {
        permissionCallback = onResult
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        mediaPermissionLauncher.launch(permission)
    }

    override fun openDocument(onResult: (uri: String?) -> Unit) {
        documentCallback = onResult
        documentLauncher.launch(arrayOf("video/*"))
    }

    override fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    override fun hasReadMediaVideo(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        // Android 14+ 的"部分授权"也允许读取（内容较少）
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                this,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            ) == PackageManager.PERMISSION_GRANTED
    }

    override fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
