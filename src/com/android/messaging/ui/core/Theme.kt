package com.android.messaging.ui.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.messaging.R

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(size = 12.dp),
    small = RoundedCornerShape(size = 16.dp),
    medium = RoundedCornerShape(size = 20.dp),
    large = RoundedCornerShape(size = 28.dp),
    extraLarge = RoundedCornerShape(size = 36.dp),
)

/** Google Sans Flex: one variable font file, one entry per weight (SIL OFL 1.1). */
internal val GoogleSansFlex: FontFamily = FontFamily(
    listOf(
        FontWeight.Thin,
        FontWeight.ExtraLight,
        FontWeight.Light,
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold,
        FontWeight.ExtraBold,
        FontWeight.Black,
    ).map { weight -> Font(R.font.google_sans_flex, weight) },
)

/** Every Material type style, re-set in [family]. */
internal fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/**
 * The same theme as Dialtone and Contacts: wallpaper (dynamic) colour following the system's
 * light/dark setting, Google Sans Flex, Material 3 Expressive. Colour roles:
 *
 * - **Pages** (`background`, `surface`): surface container low.
 * - **Cards, rows, menus, others' bubbles**: `surfaceBright` (set where they're drawn).
 * - **Search bars**: `surfaceContainerHighest`.
 * - **Accent** (`primary`, `primaryContainer`...) only for highlights: FAB, switches, own
 *   bubbles, selection, links.
 *
 * Twill only: upstream's screens paint their pages and top bars with `surfaceContainer`
 * directly, so it is set to the page colour too (Twill has no bottom bar, the one thing that
 * keeps the default `surfaceContainer` in Dialtone). minSdk is 36, so dynamic colour is always
 * available and Dialtone's fallback to the plain Material schemes isn't needed.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val wallpaper = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    // The scheme's own surface container, for the bottom bar (see LocalBarContainerColor).
    CompositionLocalProvider(LocalBarContainerColor provides wallpaper.surfaceContainer) {
        MaterialExpressiveTheme(
            colorScheme = wallpaper.withTwillSurfaces(),
            typography = Typography().withFontFamily(GoogleSansFlex),
            shapes = AppShapes,
            content = content,
        )
    }
}

/**
 * The dynamic scheme's real surface container: the bottom bar's default colour in Dialtone and
 * Contacts. Twill's scheme maps `surfaceContainer` to the page colour (see [withTwillSurfaces]),
 * so the bar takes it from here.
 */
internal val LocalBarContainerColor = staticCompositionLocalOf { Color.Unspecified }

/** Pages use surface container low (see [AppTheme]). */
internal fun ColorScheme.withTwillSurfaces(): ColorScheme = copy(
    background = surfaceContainerLow,
    surface = surfaceContainerLow,
    surfaceContainer = surfaceContainerLow,
)
