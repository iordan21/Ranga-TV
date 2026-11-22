package br.com.iordan.ranga.pc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Equivalente do `FocusableCard` do app de TV, traduzido para mouse.
 *
 * Na TV o sinal é o *foco* (você navega com o direcional). No PC é o *hover*:
 * o ponteiro já diz onde você está, e a borda só confirma que aquilo é
 * clicável. Também não há zoom no hover — na TV o leve zoom ajuda a enxergar
 * de três metros; a 50 cm de um monitor ele só treme.
 */
@Composable
fun HoverCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    background: Color = Color.Transparent,
    label: String? = null,
    content: @Composable (hovered: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Com o controle (input/Gamepad.kt) o sinal volta a ser o foco, como na TV:
    // borda mais grossa, para achar de longe onde se está.
    val focused by interaction.collectIsFocusedAsState()
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (focused) br.com.iordan.ranga.pc.input.Gamepad.testNote("foco: ${label ?: "cartão"}")
    }
    val border by animateColorAsState(
        if (hovered || focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "hoverBorder",
    )
    Box(
        modifier = modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .clip(shape)
            .background(if (focused) MaterialTheme.colorScheme.surfaceVariant else background)
            .border(if (focused) 3.dp else 2.dp, border, shape),
    ) {
        content(hovered || focused)
    }
}

/**
 * Anel de foco para botões do Material (Button, IconButton), que no tema
 * escuro marcam o foco com uma sombra que não se vê de longe. Vem antes do
 * botão na cadeia: `hasFocus` enxerga o foco do botão lá dentro.
 */
@Composable
fun Modifier.focusRing(label: String = "botão", shape: Shape = RoundedCornerShape(50)): Modifier {
    var focused by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (focused) br.com.iordan.ranga.pc.input.Gamepad.testNote("foco: $label")
    }
    val color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    return this.onFocusChanged { focused = it.hasFocus }.border(3.dp, color, shape)
}

@Composable
fun RemoteImage(url: String?, contentDescription: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
    }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Tentar de novo") }
    }
}

@Composable
fun EmptyBox(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ThinProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        HoverCard(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.surfaceVariant,
            label = title,
        ) {
            RemoteImage(imageUrl, title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun ItemRow(
    title: String,
    imageUrl: String?,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    HoverCard(onClick = onClick, modifier = modifier.fillMaxWidth(), label = title) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteImage(imageUrl, null, Modifier.size(40.dp).clip(RoundedCornerShape(5.dp)))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
