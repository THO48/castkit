package io.github.jqssun.airplay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.theme.CastCard
import io.github.jqssun.airplay.ui.theme.StatusPill
import io.github.jqssun.airplay.ui.theme.liveGreenColor
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val serverName by viewModel.serverName.collectAsState()
    val h265Enabled by viewModel.h265Enabled.collectAsState()
    val enforceSdr by viewModel.enforceSdr.collectAsState()
    val alacEnabled by viewModel.alacEnabled.collectAsState()
    val aacEnabled by viewModel.aacEnabled.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val idlePreview by viewModel.idlePreview.collectAsState()
    val autoFullscreen by viewModel.autoFullscreen.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val advertiseVideo by viewModel.advertiseVideo.collectAsState()
    val advertiseAudio by viewModel.advertiseAudio.collectAsState()
    val launchOnConnect by viewModel.launchOnConnect.collectAsState()
    val maxFps by viewModel.maxFps.collectAsState()
    val overscanned by viewModel.overscanned.collectAsState()
    val requirePin by viewModel.requirePin.collectAsState()
    val allowNewConn by viewModel.allowNewConn.collectAsState()
    val autoStart by viewModel.autoStart.collectAsState()
    val bootAutoStart by viewModel.bootAutoStart.collectAsState()
    val runInBackground by viewModel.runInBackground.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val audioLatencyMs by viewModel.audioLatencyMs.collectAsState()
    val forceSwAlac by viewModel.forceSwAlac.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val developerOptions by viewModel.developerOptions.collectAsState()
    val keyAllowFrameDrop by viewModel.keyAllowFrameDrop.collectAsState()
    val realtimeDecoderPriority by viewModel.realtimeDecoderPriority.collectAsState()
    val lowLatency by viewModel.lowLatency.collectAsState()
    val operatingRate by viewModel.operatingRate.collectAsState()
    val scheduledOutputBufferRelease by viewModel.scheduledOutputBufferRelease.collectAsState()
    val benchmarkLog by viewModel.benchmarkLog.collectAsState()
    val audioAutoBuffer by viewModel.audioAutoBuffer.collectAsState()
    val audioCushionMs by viewModel.audioCushionMs.collectAsState()
    val audioAdaptiveStep by viewModel.audioAdaptiveStep.collectAsState()
    val oboeBufferFrames by viewModel.oboeBufferFrames.collectAsState()

    val lanEnabled by viewModel.lanEnabled.collectAsState()
    val lanPort by viewModel.lanPort.collectAsState()
    val lanStatus by viewModel.lanStatus.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val compatNoPairing by viewModel.compatNoPairing.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var hasOverlayPermission by remember { mutableStateOf(canAutoLaunch(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasOverlayPermission = canAutoLaunch(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            // ---- 1. 安卓投屏接收（LANCast）----
            SettingsSectionCard(title = stringResource(R.string.section_lan), icon = Icons.Rounded.Lan) {
                SettingSwitchRow(
                    title = stringResource(R.string.setting_lan_enabled),
                    description = stringResource(R.string.setting_lan_enabled_desc),
                    checked = lanEnabled,
                    onCheckedChange = { viewModel.setLanEnabled(it) },
                )
                CardDivider()
                SettingTextFieldRow(
                    label = stringResource(R.string.setting_lan_port),
                    value = lanPort.toString(),
                    onCommit = { it.toIntOrNull()?.let { p -> viewModel.setLanPort(p) } },
                    description = stringResource(R.string.setting_lan_port_desc),
                    range = 1024..65535,
                )
                CardDivider()
                val ipText = "${localIp ?: stringResource(R.string.lan_ip_unknown)}:$lanPort"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.lan_address), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(ipText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        if (lanStatus.client != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(
                                    R.string.lan_stats,
                                    lanStatus.width,
                                    lanStatus.height,
                                    lanStatus.measuredKbps,
                                    lanStatus.decoder.ifBlank { "-" },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    StatusPill(
                        text = when {
                            lanStatus.client != null -> "已连接"
                            lanStatus.error != null -> "错误"
                            lanStatus.listening -> "监听中"
                            else -> "已关闭"
                        },
                        color = when {
                            lanStatus.client != null -> liveGreenColor()
                            lanStatus.error != null -> MaterialTheme.colorScheme.error
                            lanStatus.listening -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("CastKit Address", ipText))
                        Toast.makeText(context, "已复制 $ipText", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制地址", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ---- 2. 服务与身份 ----
            SettingsSectionCard(title = stringResource(R.string.section_server), icon = Icons.Rounded.Dns) {
                SettingTextFieldRow(
                    label = stringResource(R.string.setting_server_name),
                    value = serverName,
                    onCommit = { viewModel.setServerName(it) },
                )
                SettingTextFieldRow(
                    label = stringResource(R.string.setting_server_port),
                    value = serverPort.toString(),
                    onCommit = { viewModel.setServerPort(it.toInt()) },
                    range = 1..65535,
                )
                CardDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.setting_boot_auto_start),
                    description = stringResource(R.string.setting_boot_auto_start_desc),
                    checked = bootAutoStart,
                    onCheckedChange = { viewModel.setBootAutoStart(it) },
                )
                CardDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.setting_run_in_background),
                    description = stringResource(R.string.setting_run_in_background_desc),
                    checked = runInBackground,
                    onCheckedChange = { viewModel.setRunInBackground(it) },
                )
            }

            // ---- 3. 连接与安全 ----
            SettingsSectionCard(title = stringResource(R.string.section_connection), icon = Icons.Rounded.Link) {
                SettingSwitchRow(
                    title = stringResource(R.string.setting_require_pin),
                    description = stringResource(R.string.setting_require_pin_desc),
                    checked = requirePin,
                    onCheckedChange = { viewModel.setRequirePin(it) },
                )
                CardDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.setting_allow_new_conn),
                    description = stringResource(R.string.setting_allow_new_conn_desc),
                    checked = allowNewConn,
                    onCheckedChange = { viewModel.setAllowNewConn(it) },
                )
                CardDivider()
                val needsOverlayPermission = launchOnConnect && !hasOverlayPermission
                SettingSwitchRow(
                    title = stringResource(R.string.setting_launch_on_connect),
                    description = stringResource(
                        if (needsOverlayPermission) R.string.setting_launch_on_connect_no_permission
                        else R.string.setting_launch_on_connect_desc,
                    ),
                    checked = launchOnConnect,
                    onCheckedChange = {
                        viewModel.setLaunchOnConnect(it)
                        if (it && !canAutoLaunch(context)) context.startActivity(_overlayIntent(context))
                    },
                )
                CardDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.setting_compat_no_pairing),
                    description = stringResource(R.string.setting_compat_no_pairing_desc),
                    checked = compatNoPairing,
                    onCheckedChange = { viewModel.setCompatNoPairing(it) },
                )
                CardDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dpadFocus(RectangleShape)
                        .clickable {
                            viewModel.resetPairingKey()
                            Toast.makeText(context, "已重置 AirPlay 配对密钥", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_reset_pairing), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.setting_reset_pairing_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            // ---- 4. 显示与画质 ----
            SettingsSectionCard(title = stringResource(R.string.section_display), icon = Icons.Rounded.DisplaySettings) {
                SettingSwitchRow(
                    title = stringResource(R.string.setting_auto_fullscreen),
                    description = stringResource(R.string.setting_auto_fullscreen_desc),
                    checked = autoFullscreen,
                    onCheckedChange = { viewModel.setAutoFullscreen(it) },
                )
                CardDivider()
                SettingResolutionRow(
                    value = resolution,
                    onValueChange = { viewModel.setResolution(it) },
                )
                CardDivider()
                SettingChipFieldRow(
                    title = stringResource(R.string.setting_max_fps),
                    description = stringResource(R.string.setting_max_fps_desc),
                    value = maxFps.toString(),
                    presets = listOf("24" to "24", "30" to "30", "60" to "60", "120" to "120"),
                    placeholder = stringResource(R.string.setting_max_fps_placeholder),
                    keyboardType = KeyboardType.Number,
                    onValueChange = { it.toIntOrNull()?.let { v -> viewModel.setMaxFps(v) } },
                )
                CardDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.setting_overscanned),
                    description = stringResource(R.string.setting_overscanned_desc),
                    checked = overscanned,
                    onCheckedChange = { viewModel.setOverscanned(it) },
                )
            }

            // ---- 5. 解码 ----
            SettingsSectionCard(title = stringResource(R.string.section_decode), icon = Icons.Rounded.Memory) {
                SettingSwitchRow(
                    title = stringResource(R.string.setting_h265),
                    description = stringResource(R.string.setting_h265_desc),
                    checked = h265Enabled,
                    onCheckedChange = { viewModel.setH265Enabled(it) },
                )
                CardDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.setting_sw_alac),
                    description = stringResource(R.string.setting_sw_alac_desc),
                    checked = forceSwAlac,
                    onCheckedChange = { viewModel.setForceSwAlac(it) },
                )
                CardDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.setting_debug_overlay),
                    description = stringResource(R.string.setting_debug_overlay_desc),
                    checked = debugEnabled,
                    onCheckedChange = { viewModel.setDebugEnabled(it) },
                )
            }

            // ---- 6. 开发者选项 ----
            SettingsSectionCard(title = stringResource(R.string.section_developer), icon = Icons.Rounded.DeveloperMode) {
                SettingSwitchRow(
                    title = stringResource(R.string.setting_developer_options),
                    description = stringResource(R.string.setting_developer_options_desc),
                    checked = developerOptions,
                    onCheckedChange = { viewModel.setDeveloperOptions(it) },
                )

                if (developerOptions) {
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_auto_start),
                        description = stringResource(R.string.setting_auto_start_desc),
                        checked = autoStart,
                        onCheckedChange = { viewModel.setAutoStart(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_keep_screen_on),
                        description = stringResource(R.string.setting_keep_screen_on_desc),
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_idle_preview),
                        description = stringResource(R.string.setting_idle_preview_desc),
                        checked = idlePreview,
                        onCheckedChange = { viewModel.setIdlePreview(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_advertise_video),
                        description = stringResource(R.string.setting_advertise_video_desc),
                        checked = advertiseVideo,
                        onCheckedChange = { viewModel.setAdvertiseVideo(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_advertise_audio),
                        description = stringResource(R.string.setting_advertise_audio_desc),
                        checked = advertiseAudio,
                        onCheckedChange = { viewModel.setAdvertiseAudio(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_alac),
                        description = stringResource(R.string.setting_alac_desc),
                        checked = alacEnabled,
                        onCheckedChange = { viewModel.setAlacEnabled(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_aac),
                        description = stringResource(R.string.setting_aac_desc),
                        checked = aacEnabled,
                        onCheckedChange = { viewModel.setAacEnabled(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_key_allow_frame_drop),
                        description = stringResource(R.string.setting_key_allow_frame_drop_desc),
                        checked = keyAllowFrameDrop,
                        onCheckedChange = { viewModel.setKeyAllowFrameDrop(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_enforce_sdr),
                        description = stringResource(R.string.setting_enforce_sdr_desc),
                        checked = enforceSdr,
                        onCheckedChange = { viewModel.setEnforceSdr(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_realtime_decoder_priority),
                        description = stringResource(R.string.setting_realtime_decoder_priority_desc),
                        checked = realtimeDecoderPriority,
                        onCheckedChange = { viewModel.setRealtimeDecoderPriority(it) },
                    )
                    CardDivider()
                    SettingChipsRow(
                        title = stringResource(R.string.setting_operating_rate),
                        description = stringResource(R.string.setting_operating_rate_desc),
                        value = operatingRate,
                        options = listOf(
                            Prefs.AUTO to stringResource(R.string.chip_auto),
                            Prefs.ON to stringResource(R.string.chip_on),
                            Prefs.OFF to stringResource(R.string.chip_off),
                        ),
                        onValueChange = { viewModel.setOperatingRate(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_low_latency),
                        description = stringResource(R.string.setting_low_latency_desc),
                        checked = lowLatency,
                        onCheckedChange = { viewModel.setLowLatency(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_scheduled_output_buffer_release),
                        description = stringResource(R.string.setting_scheduled_output_buffer_release_desc),
                        checked = scheduledOutputBufferRelease,
                        onCheckedChange = { viewModel.setScheduledOutputBufferRelease(it) },
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_audio_delay),
                        description = stringResource(R.string.setting_audio_delay_desc),
                        checked = audioLatencyMs >= 0,
                        onCheckedChange = { viewModel.setAudioLatencyMs(if (it) 250 else -1) },
                    )
                    if (audioLatencyMs >= 0) {
                        var sliderVal by remember(audioLatencyMs) { mutableFloatStateOf(audioLatencyMs.toFloat()) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Slider(
                                value = sliderVal,
                                onValueChange = { sliderVal = it },
                                onValueChangeFinished = { viewModel.setAudioLatencyMs(sliderVal.roundToInt()) },
                                valueRange = 0f..1000f,
                                steps = 19,
                                modifier = Modifier
                                    .weight(1f)
                                    .dpadFocus()
                                    .dpadAdjust(
                                        onLeft = { viewModel.setAudioLatencyMs((audioLatencyMs - 50).coerceIn(0, 1000)) },
                                        onRight = { viewModel.setAudioLatencyMs((audioLatencyMs + 50).coerceIn(0, 1000)) },
                                    ),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.audio_delay_value, sliderVal.roundToInt()),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_audio_auto_buffer),
                        description = stringResource(R.string.setting_audio_auto_buffer_desc),
                        checked = audioAutoBuffer,
                        onCheckedChange = { viewModel.setAudioAutoBuffer(it) },
                    )
                    if (audioAutoBuffer) {
                        val maxStep = Prefs.ADAPTIVE_PERCENTILES.size - 1
                        var stepVal by remember(audioAdaptiveStep) { mutableFloatStateOf(audioAdaptiveStep.toFloat()) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Slider(
                                value = stepVal,
                                onValueChange = { stepVal = it },
                                onValueChangeFinished = { viewModel.setAudioAdaptiveStep(stepVal.roundToInt()) },
                                valueRange = 0f..maxStep.toFloat(),
                                steps = maxStep - 1,
                                modifier = Modifier
                                    .weight(1f)
                                    .dpadFocus()
                                    .dpadAdjust(
                                        onLeft = { viewModel.setAudioAdaptiveStep((audioAdaptiveStep - 1).coerceIn(0, maxStep)) },
                                        onRight = { viewModel.setAudioAdaptiveStep((audioAdaptiveStep + 1).coerceIn(0, maxStep)) },
                                    ),
                            )
                            Spacer(Modifier.width(12.dp))
                            val step = stepVal.roundToInt().coerceIn(0, maxStep)
                            Text(
                                stringResource(
                                    R.string.audio_adaptive_value,
                                    Prefs.ADAPTIVE_PERCENTILES[step],
                                    stringArrayResource(R.array.audio_adaptive_step_names)[step],
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    } else {
                        SettingTextFieldRow(
                            label = stringResource(R.string.setting_audio_cushion_ms),
                            value = audioCushionMs.toString(),
                            onCommit = { viewModel.setAudioCushionMs(it.toInt()) },
                            description = stringResource(R.string.setting_audio_cushion_ms_desc),
                            range = 1..1000,
                        )
                    }
                    CardDivider()
                    SettingTextFieldRow(
                        label = stringResource(R.string.setting_oboe_buffer_frames),
                        value = oboeBufferFrames.toString(),
                        onCommit = { viewModel.setOboeBufferFrames(it.toInt()) },
                        description = stringResource(R.string.setting_oboe_buffer_frames_desc),
                        range = 0..8192,
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_benchmark_log),
                        description = stringResource(R.string.setting_benchmark_log_desc),
                        checked = benchmarkLog,
                        onCheckedChange = { viewModel.setBenchmarkLog(it) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CastCard { content() }
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocus(RectangleShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingTextFieldRow(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    description: String? = null,
    range: IntRange? = null,
) {
    var text by remember(value) { mutableStateOf(value) }
    val valid = range == null || text.toIntOrNull()?.let { it in range } == true
    fun save() {
        if (valid && text != value) onCommit(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = if (range == null) it else it.filter { c -> c.isDigit() }.take(6) },
        label = { Text(label) },
        supportingText = description?.let { { Text(it) } },
        singleLine = true,
        isError = !valid,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (range == null) KeyboardType.Text else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = doneAndHide(::save),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .onFocusChanged { if (!it.isFocused) save() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingResolutionRow(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val presets = listOf(
        Prefs.AUTO to stringResource(R.string.chip_auto),
        "1280x720" to "1280x720",
        "1920x1080" to "1920x1080",
        "3840x2160" to "3840x2160",
    )
    val devicePresets = listOf(
        "portrait" to stringResource(R.string.chip_device_portrait),
        "landscape" to stringResource(R.string.chip_device_landscape),
    )
    val isPreset = (presets + devicePresets).any { it.first == value }
    var editing by remember { mutableStateOf(false) }
    val parts = if (!isPreset && value.contains("x")) value.split("x", limit = 2) else listOf("", "")
    var width by remember(value) { mutableStateOf(if (isPreset) "" else parts[0]) }
    var height by remember(value) { mutableStateOf(if (isPreset) "" else parts.getOrElse(1) { "" }) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(stringResource(R.string.setting_resolution), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.setting_resolution_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun presetChip(key: String, label: String) = FilterChip(
                selected = value == key && !editing,
                onClick = {
                    editing = false
                    width = ""
                    height = ""
                    onValueChange(key)
                },
                label = { Text(label) },
                modifier = Modifier.dpadFocus(),
            )
            presets.forEach { (key, label) -> presetChip(key, label) }
            FilterChip(
                selected = !isPreset || editing,
                onClick = { editing = true },
                label = { Text(stringResource(R.string.chip_custom)) },
                modifier = Modifier.dpadFocus(),
            )
            devicePresets.forEach { (key, label) -> presetChip(key, label) }
        }
        if (editing || !isPreset) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text(stringResource(R.string.setting_resolution_width)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text(stringResource(R.string.setting_resolution_height)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = doneAndHide {
                        val w = width.toIntOrNull()
                        val h = height.toIntOrNull()
                        if (w != null && w > 0 && h != null && h > 0) {
                            onValueChange("${w}x${h}")
                            editing = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SettingChipFieldRow(
    title: String,
    description: String,
    value: String,
    presets: List<Pair<String, String>>,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val isPreset = presets.any { it.first == value }
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(if (isPreset) "" else value) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ChipRow(if (editing) "" else value, presets, { editing = false; text = ""; onValueChange(it) }) {
            FilterChip(
                selected = !isPreset || editing,
                onClick = { editing = true },
                label = { Text(stringResource(R.string.chip_custom)) },
                modifier = Modifier.dpadFocus(),
            )
        }
        if (editing || !isPreset) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                label = { Text(placeholder) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = doneAndHide {
                    if (text.isNotBlank()) {
                        onValueChange(text)
                        editing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingChipsRow(
    title: String,
    description: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ChipRow(value, options, onValueChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = value == key,
                onClick = { onSelect(key) },
                label = { Text(label) },
                modifier = Modifier.dpadFocus(),
            )
        }
        trailing()
    }
}

@Composable
private fun doneAndHide(commit: () -> Unit): KeyboardActions {
    val keyboard = LocalSoftwareKeyboardController.current
    return KeyboardActions(onDone = { commit(); keyboard?.hide() })
}

private fun canAutoLaunch(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)

private fun _overlayIntent(ctx: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
