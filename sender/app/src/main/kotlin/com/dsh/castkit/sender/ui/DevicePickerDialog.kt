package com.dsh.castkit.sender.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.net.DiscoveredReceiver
import com.dsh.castkit.sender.net.LanCast

/**
 * 投屏设备选择底部弹窗：
 * - 自动展示局域网内在线接收端（点即开投）
 * - 展示最近连接记录（AP 隔离时快速重连）
 * - 支持内联手动输入 IP:端口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerDialog(
    show: MutableState<Boolean>,
    receivers: List<DiscoveredReceiver>,
    onSelect: (DiscoveredReceiver) -> Unit,
    onRefresh: () -> Unit,
) {
    if (!show.value) return
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recent = remember(show.value, receivers) {
        val onlineIds = receivers.mapTo(HashSet()) { it.id }
        Prefs.recentReceivers(context).filter { it.id !in onlineIds }
    }
    var showManual by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf(Prefs.host(context)) }
    var manualPort by remember { mutableStateOf(Prefs.port(context).toString()) }

    ModalBottomSheet(
        onDismissRequest = { show.value = false },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Cast,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("选择投屏设备", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (receivers.isEmpty()) "正在扫描同一 Wi-Fi 下的接收端" else "点击设备即可立即投送",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新搜索")
                    }
                    IconButton(onClick = { show.value = false }) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (receivers.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusPulseDot(color = MaterialTheme.colorScheme.primary, pulsing = true)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("暂未发现局域网接收端", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "请确保平板/接收端已打开 CastKit 并连接同一 Wi-Fi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    receivers.forEach { device ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelect(device) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(liveGreenColor().copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.TabletAndroid,
                                        contentDescription = null,
                                        tint = liveGreenColor(),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            device.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        StatusPill(text = "在线", color = liveGreenColor())
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${device.host}:${device.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (recent.isNotEmpty()) {
                Text(
                    "最近连接",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { saved ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onSelect(DiscoveredReceiver(saved.name, saved.host, saved.port))
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(saved.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${saved.host}:${saved.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.Rounded.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = { showManual = !showManual },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Icon(Icons.Rounded.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (showManual) "收起手动连接" else "手动输入 IP 地址")
            }

            if (showManual) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text("接收端 IP") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                Button(
                    onClick = {
                        val h = manualHost.trim()
                        val p = manualPort.toIntOrNull() ?: LanCast.DEFAULT_PORT
                        if (h.isNotBlank()) {
                            onSelect(DiscoveredReceiver(h, h, p))
                        }
                    },
                    enabled = manualHost.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("直接投送到此地址")
                }
            }
        }
    }
}
