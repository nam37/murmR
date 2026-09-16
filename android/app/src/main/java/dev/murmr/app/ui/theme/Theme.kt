package dev.murmr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand teal from the logo mark. */
val MurmrTeal = Color(0xFF4A8578)

/** Lighter teal for dark backgrounds, so the hold-to-talk button keeps its contrast. */
val MurmrTealLight = Color(0xFF7FB3A6)

@Composable
fun MurmrTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = MurmrTealLight, onPrimary = Color(0xFF00382F))
    } else {
        lightColorScheme(primary = MurmrTeal, onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
