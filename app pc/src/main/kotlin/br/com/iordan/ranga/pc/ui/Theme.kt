package br.com.iordan.ranga.pc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Mesma paleta do app de TV, para os dois parecerem o mesmo produto. */
private val RangaDark = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF003824),
    secondary = Color(0xFF7FD1E8),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF232A34),
    onSurfaceVariant = Color(0xFFB6BEC9),
    outline = Color(0xFF3A434F),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0000),
)

@Composable
fun RangaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RangaDark, content = content)
}
