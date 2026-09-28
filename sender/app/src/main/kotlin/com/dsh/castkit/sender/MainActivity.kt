package com.dsh.castkit.sender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.dsh.castkit.sender.cast.CastBus
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.cast.CastService
import com.dsh.castkit.sender.cast.VideoCastService
import com.dsh.castkit.sender.net.LanCast
import com.dsh.castkit.sender.net.LanCastDiscovery
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.MainScreen

class MainActivity : ComponentActivity() {

    /**
     * 设备发现提升到 Activity 级：投屏页和播放页的「快捷投屏」弹窗都要能看到同一份设备列表，
     * 不能只在某一个页面里跑。
     */
    private val discovery by lazy { LanCastDiscovery(this) }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val captureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                CastService.start(this, result.resultCode, data)
            } else {
                CastBus.update {
                    it.copy(phase = CastPhase.IDLE, message = getString(R.string.err_no_projection))
                }
                toast(getString(R.string.err_no_projection))
            }
        }

    /** 选中的待投视频（SAF）。 */
    private val pickedVideo = mutableStateOf<Uri?>(null)

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pickedVideo.value = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        setContent {
            CastKitTheme {
                MainScreen(
                    initial = Prefs.config(this),
                    onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                    onPickVideoUri = { pickedVideo.value = it },
                    onQuickCast = { uri, host, port, positionMs ->
                        // 弹窗里点设备名即开投：记下目标地址，其余设置不动；带上本机进度接着播
                        Prefs.setTarget(this, host, port, auto = true)
                        VideoCastService.start(this, uri, host, port, positionMs)
                    },
                    // 投送中点「上一个/下一个」：换片但**不结束投送**，接收端在同一会话里改播新片
                    onSwitchVideo = { uri -> VideoCastService.switchTo(this, uri) },
                    onRemoteToggle = { VideoCastService.control(this, LanCast.ACTION_TOGGLE) },
                    onRemoteSeek = { ms -> VideoCastService.control(this, LanCast.ACTION_SEEK, ms) },
                    onStopVideo = { VideoCastService.stop(this) },
                    onStart = ::requestCast,
                    onStop = { CastService.stop(this) },
                    discovery = discovery,
                )
            }
        }
    }

    /** 每次投屏都要重新申请录屏授权（Android 14+ 要求）。 */
    private fun requestCast() {
        val cfg = Prefs.config(this)
        if (cfg.host.isBlank()) {
            toast(getString(R.string.err_bad_address))
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        captureConsent.launch(mgr.createScreenCaptureIntent())
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
