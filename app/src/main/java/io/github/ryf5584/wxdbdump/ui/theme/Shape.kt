package io.github.ryf5584.wxdbdump.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Material 3 shape scale plus two project-specific shapes for cards and sheets. */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object Shapes2 {
    val card = RoundedCornerShape(20.dp)
    val sheetTop = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val chip = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(percent = 50)
    val phoneFrame = RoundedCornerShape(18.dp)
}
