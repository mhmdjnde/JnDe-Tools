package com.example.ytsaverbyjnde.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = darkColorScheme(
    primary          = YTRed,
    onPrimary        = TextPri,
    secondary        = FBBlue,
    onSecondary      = TextPri,
    tertiary         = Gold,
    background       = BgDeep,
    onBackground     = TextPri,
    surface          = BgSurface,
    onSurface        = TextPri,
    surfaceVariant   = CardBase,
    onSurfaceVariant = TextSec,
    outline          = CardBorder,
)

@Composable
fun YTSaverByJndeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography  = Typography,
        content     = content,
    )
}
