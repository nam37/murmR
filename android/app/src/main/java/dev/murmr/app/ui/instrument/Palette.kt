package dev.murmr.app.ui.instrument

import androidx.compose.ui.graphics.Color

/**
 * Colours lifted verbatim from the approved mockup's stylesheet
 * (design/phone-mockup/src/prototype.css). Keep them in sync with it, not with Material.
 */
internal object Palette {
    val chassisBase = Color(0xFF243A48)

    val text = Color(0xFFD9E5EB)
    val transcript = Color(0xFFDCE5E9)
    val placeholder = Color(0xFF8DA39F)
    val heading = Color(0xFFA1B9C5)
    val status = Color(0xFF8CEBDD)
    val caret = Color(0xFF91F6E1)
    val pending = Color(0xFF84999F)
    val notice = Color(0xFFA6C2C5)
    val instructions = Color(0xFFB1C6D0)
    val hint = Color(0xFFA8BEC7)

    val pillBg = Color(0xFF1D303B)
    val pillBorder = Color(0xFF09191E)
    val pillText = Color(0xFFB3C7D3)
    val pillState = Color(0xFF84EBD9)
    val pillHighlight = Color(0xFF8298A6)
    val pillShadow = Color(0xFF050E14)

    val ledOn = Color(0xFF6BF9DC)
    val ledGlow = Color(0xFF3DDDD0)
    val ledOff = Color(0xFFDD9168)

    val waveActive = Color(0xFF80F4DF)
    val waveIdle = Color(0xFF648F89)
    val waveGlow = Color(0xFF57DBC3)
    /** rgba(100, 204, 188, 0.09) */
    val grid = Color(0x1764CCBC)

    val buttonText = Color(0xFF032E2F)
    val buttonTextShadow = Color(0xFF92DDCB)
    val lampOff = Color(0xFF294D48)
    val lampReady = Color(0xFF91EFD4)
    val lampListening = Color(0xFFBDFFE9)

    /** The wordmark is charcoal in the logo; the mockup recolours it to this over the chassis. */
    val wordmark = Color(0xFFE6E6E6)
}
