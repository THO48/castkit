package io.github.jqssun.airplay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.theme.CastCard
import io.github.jqssun.airplay.ui.theme.StatusPill
import io.github.jqssun.airplay.ui.theme.liveGreenColor
import io.github.jqssun.airplay.ui.theme.warnAmberColor
import io.github.jqssun.airplay.viewmodel.MainViewModel

@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val filtered = remember(logs, query) {
        val q = query.trim()
        if (q.isEmpty()) logs else logs.filter { it.contains(q, ignoreCase = true) }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.tab_logs), style = MaterialTheme.typography.headlineSmall)
                    StatusPill(text = "${filtered.size} 条", color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(
                onClick = { autoScroll = !autoScroll },
                modifier = Modifier.dpadFocus(),
            ) {
                Icon(
                    if (autoScroll) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (autoScroll) "暂停自动滚动" else "恢复自动滚动",
                    tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CastKit Logs", filtered.joinToString("\n")))
                    Toast.makeText(context, "已复制 ${filtered.size} 条日志", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.dpadFocus(),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制日志")
            }
            IconButton(onClick = { viewModel.exportLogs() }, modifier = Modifier.dpadFocus()) {
                Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.btn_export))
            }
            IconButton(onClick = { viewModel.clearLogs() }, modifier = Modifier.dpadFocus()) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.btn_clear))
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("过滤日志关键字（如 LANCast / AirPlay / 失败）…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        CastCard(modifier = Modifier.weight(1f)) {
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotEmpty()) "没有匹配的日志" else "暂无运行日志",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        items(filtered) { line ->
                            val lineColor = when {
                                line.contains("失败") || line.contains("error", true) || line.contains("错误") ->
                                    MaterialTheme.colorScheme.error
                                line.contains("警告") || line.contains("warn", true) || line.contains("超时") ->
                                    warnAmberColor()
                                line.contains("局域网") || line.contains("发送端") || line.contains("投视频") ->
                                    liveGreenColor()
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                            }
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = lineColor,
                            )
                        }
                    }
                }
            }
        }
    }
}
