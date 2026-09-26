package com.dsh.castkit.sender.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

/**
 * JVM 侧截图渲染通道的「探针」预览，放在 `src/screenshotTest/kotlin`。
 *
 * 用途：证明在不启动模拟器 / 真机的情况下，`@Preview` 能被 layoutlib 渲染成 PNG。
 * 内容刻意保持最小（只用 MaterialTheme + Text），这样一旦截图任务失败，
 * 就能确定是通道本身的问题，而不是某个业务组件的依赖缺失。
 *
 * 注意：截图引擎是**反射**扫描 @Preview 函数的，所以这些函数**不能是 private**，
 * 否则引擎会安静地「扫到 0 个预览、退出码 0、不产出任何 PNG」。
 *
 * 生成命令（Windows，注意 GRADLE_USER_HOME 与短路径，见仓库文档）：
 *   $env:GRADLE_USER_HOME="E:\program\Aixiede\castkit\.tmp-gh"
 *   cd E:\program\Aixiede\castkit\sender
 *   .\gradlew.bat :app:updateDebugScreenshotTest
 */
@Composable
private fun HarnessContent(label: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column {
                Text(text = "screenshot harness ok", style = MaterialTheme.typography.headlineSmall)
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 浅色：基本通道验证。 */
@PreviewTest
@Preview(name = "harness-light", showBackground = true, widthDp = 320, heightDp = 240)
@Composable
fun HarnessLightPreview() {
    MaterialTheme {
        HarnessContent("light")
    }
}

/** 深色：验证 `uiMode = UI_MODE_NIGHT_YES` 能在 JVM 上正确生效。 */
@PreviewTest
@Preview(
    name = "harness-dark",
    showBackground = true,
    widthDp = 320,
    heightDp = 240,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    backgroundColor = 0xFF000000,
)
@Composable
fun HarnessDarkPreview() {
    MaterialTheme {
        HarnessContent("dark")
    }
}

/** 尺寸：验证自定义设备规格（横屏平板）能被截图任务正确出图。 */
@PreviewTest
@Preview(
    name = "harness-landscape",
    device = "spec:width=891dp,height=411dp,dpi=420",
    showBackground = true,
    backgroundColor = 0xFF102030,
)
@Composable
fun HarnessLandscapePreview() {
    MaterialTheme {
        HarnessContent("landscape-891x411")
    }
}
