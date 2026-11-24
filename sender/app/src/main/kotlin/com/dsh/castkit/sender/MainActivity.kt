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
import androidx.activity.enableEdgeToEdge
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
     * 设备发现提升到 Activity 级：投屏页和播放页的「快捷投屏」弹窗共用同一份设备列表。
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

    private var pendingStartMirrorAfterAudio = false

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Prefs.setMirrorAudio(this, false)
                toast(getString(R.string.err_audio_permission_fallback))
            }
            if (pendingStartMirrorAfterAudio) {
                pendingStartMirrorAfterAudio = false
                launchScreenCapture()
            }
        }

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

    /** 选中的待投视频（SAF 或视频库）。 */
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
        enableEdgeToEdge()
        askNotificationPermission()
        setContent {
            CastKitTheme {
                MainScreen(
                    initial = Prefs.config(this),
                    pickedVideo = pickedVideo.value,
                    onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                    onPickVideoUri = { pickedVideo.value = it },
                    onStartVideo = { uri ->
                        val resumeMs = Prefs.getVideoProgress(this, uri.toString())
                        VideoCastService.start(
                            this,
                            uri,
                            Prefs.host(this),
                            Prefs.port(this),
                            startPositionMs = resumeMs,
                            targetName = Prefs.targetName(this),
                        )
                    },
                    onShareLibrary = {
                        VideoCastService.startLibrary(
                            this,
                            Prefs.host(this),
                            Prefs.port(this),
                            targetName = Prefs.targetName(this),
                        )
                    },
                    onQuickCast = { uri, host, port, name, positionMs ->
                        Prefs.setTarget(this, host, port, name = name, auto = true)
                        VideoCastService.start(
                            this,
                            uri,
                            host,
                            port,
                            startPositionMs = positionMs,
                            targetName = name,
                        )
                    },
                    onRemoteToggle = { VideoCastService.control(this, LanCast.ACTION_TOGGLE) },
                    onRemoteSeek = { ms -> VideoCastService.control(this, LanCast.ACTION_SEEK, ms) },
                    onRemoteSeekBy = { deltaMs ->
                        if (deltaMs >= 0) {
                            VideoCastService.control(this, LanCast.ACTION_SEEK_FORWARD, deltaMs)
                        } else {
                            VideoCastService.control(this, LanCast.ACTION_SEEK_BACKWARD, -deltaMs)
                        }
                    },
                    onRemoteSpeed = { speed ->
                        VideoCastService.control(this, LanCast.ACTION_SPEED, (speed * 100f).toLong())
                    },
                    onRemoteVolumeDelta = { up ->
                        VideoCastService.control(this, LanCast.ACTION_VOLUME_DELTA, if (up) 1L else 0L)
                    },
                    onStopVideo = { VideoCastService.stop(this) },
                    onStart = ::requestCast,
                    onStop = { CastService.stop(this) },
                    onRequestKeyFrame = { CastService.requestKeyFrame(this) },
                    onUpdateBitrate = { mbps -> CastService.updateBitrate(this, mbps * 1_000_000) },
                    onRequestAudioPermission = ::requestAudioPermissionOnly,
                    discovery = discovery,
                )
            }
        }
    }

    private fun requestAudioPermissionOnly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartMirrorAfterAudio = false
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 每次投屏都要重新申请录屏授权（Android 14+ 要求）。 */
    private fun requestCast() {
        val cfg = Prefs.config(this)
        if (cfg.host.isBlank()) {
            toast(getString(R.string.err_bad_address))
            return
        }
        if (cfg.audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartMirrorAfterAudio = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
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
