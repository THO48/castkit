package com.dsh.castkit.sender.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.net.DiscoveredReceiver
import com.dsh.castkit.sender.ui.components.SettingRow
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/**
 * 投屏设备选择弹窗。
 *
 * **点设备名就直接开投**，不需要再点一次「开始投屏」——这条行为原样保留。
 *
 * 开关状态由调用方持有（`show: Boolean` + `onDismiss`），弹窗自己不改别人的状态；
 * 这也是 [AppDialog] 从 `MutableState` 改成普通 `Boolean` 之后带来的接口变化。
 */
@Composable
fun DevicePickerDialog(
    show: Boolean,
    receivers: List<DiscoveredReceiver>,
    onSelect: (DiscoveredReceiver) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        show = show,
        onDismiss = onDismiss,
        title = stringResource(R.string.dialog_pick_device),
        summary = stringResource(
            if (receivers.isEmpty()) R.string.dialog_pick_device_summary_empty
            else R.string.dialog_pick_device_summary,
        ),
    ) {
        if (receivers.isEmpty()) {
            Text(
                text = stringResource(R.string.dialog_no_receivers),
                style = CastKitTheme.typography.bodySmall,
                color = CastKitTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = CastKitSpacing.space2),
            )
        } else {
            receivers.forEach { device ->
                SettingRow(
                    title = device.name,
                    summary = "${device.host}:${device.port}",
                    onClick = { onSelect(device) },
                )
            }
        }
        SettingRow(
            title = stringResource(R.string.target_refresh),
            onClick = onRefresh,
        )
    }
}
