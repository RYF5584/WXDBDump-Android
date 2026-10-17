package io.github.ryf5584.wxdbdump.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** Motion durations in milliseconds. */
object Motion {
    const val SHORT1 = 50
    const val SHORT2 = 100
    const val SHORT3 = 150
    const val MEDIUM1 = 200
    const val MEDIUM2 = 250
    const val MEDIUM3 = 300
    const val LONG1 = 400
    const val LONG2 = 500
    const val EXTRA_LONG = 700
}

/** Whether motion is reduced, following the setting or the system animation scale. */
val LocalReduceMotion = staticCompositionLocalOf { false }
