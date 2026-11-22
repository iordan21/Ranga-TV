package br.com.iordan.ranga.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tema fixo em escuro, mesmo quando o sistema está no claro.
 *
 * Não é preguiça: app de vídeo é usado no escuro (sala à noite, TV) e fundo
 * claro atrás de um player estoura o contraste. Todo player sério —
 * Netflix, YouTube TV, Plex — faz igual.
 */
private val RangaDark = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFF5FF9B3),
    secondary = Color(0xFF7FD1E8),
    onSecondary = Color(0xFF00363F),
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
fun RangaTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = RangaDark,
        content = content,
    )
}
