package com.dsh.castkit.sender.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * 仅 **debug** 源集存在的组件画廊入口（见 `src/debug/AndroidManifest.xml`）。
 *
 * 为什么需要它：第二步只交付了主题与公共组件，三个业务页面要到第三步才迁移。
 * 在那之前直接把 debug 包装到设备上，看到的仍是改造前的 Miuix 界面——完全看不出
 * 新设计系统长什么样。这个 Activity 直接渲染 [GalleryBody]，让新组件可以立刻
 * 在真机屏幕上验收。
 *
 * 第三步页面迁移完成后可以直接删掉本文件与 `src/debug/AndroidManifest.xml`，
 * 也可以留着当设计系统的活文档——它不进入 release 包。
 */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 默认关闭动态取色：这是**设计验收**入口，要看的是品牌 fallback 色板本身
        // （#2A62B8 / #6FA8F5）。开着动态取色的话，颜色来自模拟器壁纸派生的
        // Material You 调色板，看不到我们定的品牌色。
        // 想看动态取色的实际效果：
        //   adb shell am start -n com.dsh.castkit.sender/com.dsh.castkit.sender.ui.GalleryActivity --ez dynamic true
        val useDynamicColor = intent?.getBooleanExtra(EXTRA_DYNAMIC, false) ?: false

        setContent {
            CastKitTheme(dynamicColor = useDynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CastKitTheme.colorScheme.background,
                ) {
                    GalleryBody()
                }
            }
        }
    }

    companion object {
        const val EXTRA_DYNAMIC = "dynamic"
    }
}
