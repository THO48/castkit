package com.dsh.castkit.sender.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.ResolutionPreset
import com.dsh.castkit.sender.cast.CastBus
import com.dsh.castkit.sender.cast.CastMode
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.net.LanCastDiscovery
import com.dsh.castkit.sender.ui.components.CastKitCard
import com.dsh.castkit.sender.ui.components.CastKitSlider
import com.dsh.castkit.sender.ui.components.CastKitTopBar
import com.dsh.castkit.sender.ui.components.CastStatusRow
import com.dsh.castkit.sender.ui.components.OptionChipRow
import com.dsh.castkit.sender.ui.components.PrimaryBottomButton
import com.dsh.castkit.sender.ui.components.SectionHeader
import com.dsh.castkit.sender.ui.components.SectionHeaderLevel
import com.dsh.castkit.sender.ui.components.SettingRow
import com.dsh.castkit.sender.ui.theme.CastKitSpacing
import kotlin.math.roundToInt

/**
 * 分辨率档位。
 *
 * **CUSTOM 已移除**（设计系统 §10 偏差 7，你在 Q20 明确要求"不需要自定义"）。
 * 这是本次重构唯一的功能削减：用户不再能投非预设尺寸。
 * 枚举里的 `P480` 与库存量值都不动 —— 后者靠 `Prefs.preset()` 的 `runCatching`
 * 自动回落到 720p，不会崩。
 */
private val ResolutionOptions = listOf(
    ResolutionPreset.P720,
    ResolutionPreset.P1080,
    ResolutionPreset.P1440,
    ResolutionPreset.NATIVE,
)

/** 帧率档位（与改造前一致）。 */
private val FpsOptions = listOf(15, 24, 30, 60)

/**
 * 页面 2 —— 投屏设置页（底部导航「投屏」Tab）。
 *
 * 只做镜像投屏；投视频文件从「视频」页点开视频、在播放页点「投屏」发起。
 *
 * 结构：顶栏固定 + 滚动区 + 吸底主按钮。分三层写而不是用 `Scaffold`，
 * 是为了让吸底按钮真的吸在底部而不随内容滚走，同时避免与外壳的 Scaffold 嵌套。
 */
@Composable
fun CastSettingsScreen(
    vm: CastViewModel,
    discovery: LanCastDiscovery,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val castState by CastBus.state.collectAsState()
    val found by discovery.receivers.collectAsState()

    // 发现的设备一变就重新评估自动选中（保持改造前的原语义）
    LaunchedEffect(found) { vm.autoSelectIfNeeded(found) }

    val running = castState.phase == CastPhase.RUNNING ||
        castState.phase == CastPhase.CONNECTING ||
        castState.phase == CastPhase.RECONNECTING
    val videoCasting = castState.mode == CastMode.VIDEO

    val statusLabel = when (castState.phase) {
        CastPhase.IDLE -> stringResource(R.string.cast_idle)
        CastPhase.CONNECTING -> stringResource(R.string.cast_connecting)
        CastPhase.RUNNING -> stringResource(R.string.cast_running)
        CastPhase.RECONNECTING -> stringResource(R.string.cast_reconnecting)
        CastPhase.ERROR -> stringResource(R.string.cast_error, castState.message)
    }
    val measured = stringResource(
        R.string.measured_stats,
        castState.measuredKbps / 1000f,
        castState.measuredFps,
    )
    val sizeLine = "${castState.width}×${castState.height} @${castState.fps}fps  " +
        "${castState.bitrateBps / 1_000_000}Mbps"
    // 第二行：非错误时把服务层文案（"正在搜索…"等）也算进去，错误文案已经在标题里了
    val statusStats = if (castState.phase == CastPhase.RUNNING) {
        if (castState.width > 0) "$sizeLine · $measured" else measured
    } else {
        if (castState.message.isNotBlank()) castState.message else if (castState.width > 0) sizeLine else null
    }

    val effective = remember(vm.preset, vm.keepAspect) { Prefs.config(context) }

    Column(modifier = modifier.fillMaxSize()) {
        CastKitTopBar(title = stringResource(R.string.tab_cast))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // ---------------- 接收端 ----------------
            SectionHeader(
                text = stringResource(R.string.section_target),
                level = SectionHeaderLevel.Page,
            )
            CastKitCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CastKitSpacing.pageHorizontal),
            ) {
                // 原来的 CastStatusCard 合并到这里 —— 它本来就是"当前接收端的连接详情"
                CastStatusRow(
                    phase = castState.phase,
                    label = statusLabel,
                    detail = if (castState.host.isNotBlank()) {
                        "${castState.host}:${castState.port}"
                    } else {
                        null
                    },
                    stats = statusStats,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = CastKitSpacing.cardInside),
                    color = CastKitTheme.colorScheme.outlineVariant,
                )

                if (found.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(CastKitSpacing.cardInside),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = CastKitTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(CastKitSpacing.space3))
                        Text(
                            text = stringResource(R.string.target_searching),
                            style = CastKitTheme.typography.bodySmall,
                            color = CastKitTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    found.forEach { receiver ->
                        SettingRow(
                            title = receiver.name,
                            summary = "${receiver.host}:${receiver.port}",
                            trailing = {
                                if (vm.selectedId == receiver.id) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.cd_selected),
                                        tint = CastKitTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                            onClick = { vm.selectReceiver(receiver) },
                        )
                    }
                }

                SettingRow(
                    title = stringResource(R.string.target_refresh),
                    onClick = { discovery.probeNow() },
                )

                SettingRow(
                    title = stringResource(
                        if (vm.showManual) R.string.target_manual_hide else R.string.target_manual_show,
                    ),
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = CastKitTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = { vm.toggleManual() },
                )

                if (vm.showManual) {
                    Column(
                        modifier = Modifier.padding(
                            start = CastKitSpacing.cardInside,
                            end = CastKitSpacing.cardInside,
                            bottom = CastKitSpacing.cardInside,
                        ),
                    ) {
                        CastKitTextField(
                            value = vm.host,
                            onValueChange = vm::applyHost,
                            label = stringResource(R.string.target_host),
                        )
                        Spacer(Modifier.height(CastKitSpacing.space3))
                        CastKitTextField(
                            value = vm.portText,
                            onValueChange = vm::applyPort,
                            label = stringResource(R.string.target_port),
                            keyboardType = KeyboardType.Number,
                        )
                    }
                }
            }

            // ---------------- 画面参数 ----------------
            SectionHeader(
                text = stringResource(R.string.section_video),
                level = SectionHeaderLevel.Page,
            )
            CastKitCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CastKitSpacing.pageHorizontal),
            ) {
                Column(modifier = Modifier.padding(vertical = CastKitSpacing.space4)) {
                    FieldLabel(stringResource(R.string.label_resolution))
                    OptionChipRow(
                        options = ResolutionOptions,
                        selected = vm.preset,
                        onSelect = vm::selectPreset,
                        label = { it.label },
                    )

                    Spacer(Modifier.height(CastKitSpacing.space2))
                    Text(
                        text = stringResource(
                            R.string.effective_size,
                            effective.width,
                            effective.height,
                        ),
                        style = CastKitTheme.typography.bodySmall,
                        color = CastKitTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = CastKitSpacing.pageHorizontal),
                    )
                    effective.sizeNote?.let { note ->
                        Text(
                            text = note,
                            style = CastKitTheme.typography.bodySmall,
                            color = CastKitTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = CastKitSpacing.pageHorizontal),
                        )
                    }

                    Spacer(Modifier.height(CastKitSpacing.space4))
                    SettingRow(
                        title = stringResource(R.string.label_keep_aspect),
                        // ★ 这条说明必须写清楚它对**预设档位同样生效**：
                        // 开启 = 数字是短边、长边按屏幕比例推；关闭 = 字面 16:9、会拉伸。
                        // 删掉 CUSTOM 之后它是唯一决定"720p 到底指什么"的开关。
                        summary = stringResource(
                            if (vm.keepAspect) {
                                R.string.label_keep_aspect_desc_on
                            } else {
                                R.string.keep_aspect_off_warning
                            },
                        ),
                        trailing = {
                            Switch(checked = vm.keepAspect, onCheckedChange = vm::applyKeepAspect)
                        },
                    )

                    Spacer(Modifier.height(CastKitSpacing.space4))
                    FieldLabel(stringResource(R.string.label_fps))
                    OptionChipRow(
                        options = FpsOptions,
                        selected = vm.fps,
                        onSelect = vm::selectFps,
                        label = { "${it}fps" },
                    )

                    Spacer(Modifier.height(CastKitSpacing.space4))
                    SettingRow(
                        title = stringResource(R.string.label_bitrate),
                        summary = "${vm.mbps} Mbps",
                    )
                    CastKitSlider(
                        value = vm.mbps.toFloat(),
                        // 拖动中只改内存状态，松手才落盘。
                        // 改造前用的是 Miuix 的 Slider（没有 onValueChangeFinished），
                        // 只能每一帧都写一次 SharedPreferences。
                        onValueChange = { vm.setMbpsDraft(it.toInt()) },
                        onValueChangeFinished = { vm.persist() },
                        valueRange = 1f..20f,
                        steps = 18,
                        // 跟手数值气泡：手指不用离开滑块就能读数
                        valueLabel = { "${it.roundToInt()} Mbps" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CastKitSpacing.pageHorizontal),
                    )
                }
            }

            Spacer(Modifier.height(CastKitSpacing.space6))
        }

        PrimaryBottomButton(
            text = stringResource(if (running) R.string.action_stop else R.string.action_start),
            onClick = { if (running) onStop() else onStart() },
            enabled = !videoCasting,
            hint = when {
                !videoCasting -> null
                running -> stringResource(R.string.cast_page_video_running)
                else -> stringResource(R.string.cast_page_video_hint)
            },
        )
    }
}

/** 卡片内的小标题（"分辨率" / "帧率"）。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = CastKitTheme.typography.labelLarge,
        color = CastKitTheme.colorScheme.onSurface,
        modifier = Modifier.padding(
            start = CastKitSpacing.pageHorizontal,
            end = CastKitSpacing.pageHorizontal,
            bottom = CastKitSpacing.space2,
        ),
    )
}

/**
 * 设计系统统一的输入框：M3 的 **Filled 变体**（`TextField` + 自定义 colors），
 * 而不是 `OutlinedTextField` —— 后者圆角为 0，属于设计系统明令禁止的"方正默认控件"。
 */
@Composable
private fun CastKitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = CastKitTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = CastKitTheme.typography.bodyMedium,
        shape = CastKitTheme.shapes.small,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CastKitTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = CastKitTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = CastKitTheme.colorScheme.primary,
            unfocusedIndicatorColor = CastKitTheme.colorScheme.outlineVariant,
            focusedTextColor = CastKitTheme.colorScheme.onSurface,
            unfocusedTextColor = CastKitTheme.colorScheme.onSurface,
            focusedLabelColor = CastKitTheme.colorScheme.primary,
            unfocusedLabelColor = CastKitTheme.colorScheme.onSurfaceVariant,
            cursorColor = CastKitTheme.colorScheme.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
