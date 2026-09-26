package com.dsh.castkit.sender.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.dsh.castkit.sender.ui.theme.CastKitSemanticColors
import com.dsh.castkit.sender.ui.theme.CastKitShapes
import com.dsh.castkit.sender.ui.theme.CastKitTypography
import com.dsh.castkit.sender.ui.theme.DarkColorScheme
import com.dsh.castkit.sender.ui.theme.DarkSemanticColors
import com.dsh.castkit.sender.ui.theme.LightColorScheme
import com.dsh.castkit.sender.ui.theme.LightSemanticColors

/*
 * ============================================================================
 * CastKit 主题入口
 * ============================================================================
 *
 * 第三步已完成：Miuix 依赖与过渡期的桥接层都已删除，整个 sender 的 UI
 * 现在统一在 Material 3 上（`androidx.compose.material3:1.3.1`）。
 */

/**
 * 语义色（M3 的 ColorScheme 没有 success 系列），通过 CompositionLocal 下发。
 * 默认值取浅色，保证任何脱离主题的预览也不会拿到「未初始化」状态。
 */
internal val LocalCastKitSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/**
 * 应用主题。
 *
 * @param darkTheme 跟随系统深浅色，可在预览里显式指定。
 * @param dynamicColor Android 12+ 是否使用壁纸派生的动态配色。
 *        为 true 时 `#6FA8F5` **不会出现**——这是 `dynamic*ColorScheme()` 的定义，
 *        不是实现取舍；品牌色板作为 Android 12 以下与不支持机型时的 fallback。
 */
@Composable
fun CastKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val semantic = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalCastKitSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CastKitTypography,
            shapes = CastKitShapes,
            content = content,
        )
    }
}

/**
 * 设计系统的 token 入口，用法与 `MaterialTheme` 一致：
 *
 * ```
 * CastKitTheme.colorScheme.primary
 * CastKitTheme.typography.titleLarge
 * CastKitTheme.shapes.large
 * CastKitTheme.semantic.success
 * ```
 *
 * `CastKitTheme` 同时是函数（上面的主题包裹）与 object（这里的 token 访问器），
 * 与 Material 3 自己的 `MaterialTheme` 同构。
 */
object CastKitTheme {
    val colorScheme: ColorScheme
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography

    val shapes: Shapes
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes

    val semantic: CastKitSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCastKitSemanticColors.current
}
