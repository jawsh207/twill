package com.android.messaging.ui.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(size = 12.dp),
    small = RoundedCornerShape(size = 16.dp),
    medium = RoundedCornerShape(size = 20.dp),
    large = RoundedCornerShape(size = 28.dp),
    extraLarge = RoundedCornerShape(size = 36.dp),
)

/**
 * Twill takes its colours from the system's dynamic colour roles exactly like GrapheneOS
 * Settings (on API 34+ the dynamic scheme reads `system_surface_container_*`,
 * `system_surface_bright_*` and friends), so "Other colors" gives neutral greys and
 * "Wallpaper colors" gives wallpaper tints, in light and dark:
 *
 * - **Page / window** (`background`, `surface`, `surfaceContainer`): surface container.
 *   Scaffolds, top bars and plain Surfaces sit on it.
 * - **Rows, cards, others' bubbles, dialogs** (`surfaceBright`, and `surfaceContainerHigh`,
 *   which M3 dialogs and incoming bubbles use): surface bright. `surfaceContainerHighest` is
 *   left as the raised layer for fields and chips.
 * - **Accent** (`primary`, `primaryContainer`, `secondaryContainer`) only for highlights:
 *   FAB, switches, own bubbles, selection, links.
 * - **Text and icons**: `onSurface` / `onSurfaceVariant`, also for `onBackground`.
 *
 * Material 3 Expressive: expressive motion via [MaterialExpressiveTheme].
 */
@Composable
fun AppTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamic = when {
        isSystemInDarkTheme() -> dynamicDarkColorScheme(context = context)
        else -> dynamicLightColorScheme(context = context)
    }

    MaterialExpressiveTheme(
        colorScheme = dynamic.withSettingsSurfaces(),
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        content = content,
    )
}

/** Maps the surface roles the way Settings uses them (see [AppTheme]). */
internal fun ColorScheme.withSettingsSurfaces(): ColorScheme = copy(
    background = surfaceContainer,
    onBackground = onSurface,
    surface = surfaceContainer,
    surfaceContainerHigh = surfaceBright,
)
