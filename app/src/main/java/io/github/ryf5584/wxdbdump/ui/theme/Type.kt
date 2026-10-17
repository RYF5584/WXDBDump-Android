package io.github.ryf5584.wxdbdump.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Full Material 3 type scale.
 *
 * - Display and title use the system sans-serif; CJK glyphs come from the system font so
 *   no font is bundled, keeping the APK small.
 * - Every line height is centred to keep mixed CJK and Latin text from jumping.
 * - Everything is in sp so a 200% font scale does not clip.
 */
private val center = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun s(
    size: Int,
    lineHeight: Int,
    weight: Int = 400,
    tracking: Double = 0.0,
    family: FontFamily = FontFamily.Default,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = center,
)

internal val AppTypography = Typography(
    displayLarge = s(57, 64, 400, -0.25),
    displayMedium = s(45, 52),
    displaySmall = s(36, 44),
    headlineLarge = s(32, 40),
    headlineMedium = s(28, 36),
    headlineSmall = s(24, 32),
    titleLarge = s(22, 28, 500),
    titleMedium = s(16, 24, 500, 0.15),
    titleSmall = s(14, 20, 500, 0.1),
    bodyLarge = s(16, 24, 400, 0.5),
    bodyMedium = s(14, 20, 400, 0.25),
    bodySmall = s(12, 16, 400, 0.4),
    labelLarge = s(14, 20, 500, 0.1),
    labelMedium = s(12, 16, 500, 0.5),
    labelSmall = s(11, 16, 500, 0.5),
)

/** Monospace for paths, sizes, speeds and times, which keeps digits from shifting. */
internal val MonoTextStyle = s(12, 16, 400, family = FontFamily.Monospace)
internal val MonoTextStyleSmall = s(11, 16, 400, family = FontFamily.Monospace)
internal val MonoNumberStyle = s(20, 24, 500, family = FontFamily.Monospace)
