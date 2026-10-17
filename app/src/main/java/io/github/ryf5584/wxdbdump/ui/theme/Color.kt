package io.github.ryf5584.wxdbdump.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Fallback palette. API 31 and above uses Material You dynamic colour by default, and this
 * set only applies when dynamic colour is unavailable or turned off.
 *
 * Every foreground and background pair meets WCAG AA: 4.5:1 for body text, 3:1 for large text.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF4C56C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF000A61),
    secondary = Color(0xFF00695C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF6B5DD3),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6DEFF),
    onTertiaryContainer = Color(0xFF1E0A56),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF3EFF7),
    inversePrimary = Color(0xFFBDC2FF),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF4C56C7),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFBDC2FF),
    onPrimary = Color(0xFF1B227A),
    primaryContainer = Color(0xFF343CA6),
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFFCFBDFF),
    onTertiary = Color(0xFF38265F),
    tertiaryContainer = Color(0xFF4F3D77),
    onTertiaryContainer = Color(0xFFE6DEFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF46464F),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    inversePrimary = Color(0xFF4C56C7),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFBDC2FF),
    surfaceBright = Color(0xFF39383F),
    surfaceDim = Color(0xFF131318),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292930),
    surfaceContainerHighest = Color(0xFF34343A),
)

/**
 * Semantic colours. They deliberately stay out of dynamic colouring, because encrypted
 * and plaintext, success and failure must remain distinguishable.
 */
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightSemantic = SemanticColors(
    success = Color(0xFF146C2E),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA6F2B8),
    onSuccessContainer = Color(0xFF0A4018),
    warning = Color(0xFF8B5000),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDCBE),
    onWarningContainer = Color(0xFF3A1F00),
)

internal val DarkSemantic = SemanticColors(
    success = Color(0xFF8CD79B),
    onSuccess = Color(0xFF00391A),
    successContainer = Color(0xFF005320),
    onSuccessContainer = Color(0xFFA6F2B8),
    warning = Color(0xFFFFB86B),
    onWarning = Color(0xFF4A2800),
    warningContainer = Color(0xFF6B3A00),
    onWarningContainer = Color(0xFFFFDCBE),
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

/** Brand wheel for avatars, indexed by a hash of the account so colours stay stable. */
internal val AvatarPalette = listOf(
    Color(0xFF4C56C7) to Color(0xFFDFE0FF),
    Color(0xFF00695C) to Color(0xFFB2DFDB),
    Color(0xFF8B5000) to Color(0xFFFFDCBE),
    Color(0xFF6B5DD3) to Color(0xFFE6DEFF),
    Color(0xFF9A4058) to Color(0xFFFFD9E2),
    Color(0xFF2C6A2F) to Color(0xFFCCEECC),
)
