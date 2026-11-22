package br.com.iordan.ranga.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * O tijolo de navegação do app.
 *
 * No celular é só um clicável. Na TV, o usuário não aponta: move o foco com o
 * direcional e aperta OK. `clickable` já resolve metade (torna focável e mapeia
 * DPAD_CENTER/ENTER para o onClick); o que ele não dá é o *sinal visual* de
 * onde o foco está — sem isso a TV fica inutilizável.
 *
 * Desenho minimalista: parado, o item não tem moldura nenhuma. A borda só
 * existe enquanto está focado. Numa grade de 40 categorias, 40 molduras
 * competindo com o conteúdo é exatamente o ruído que dá para eliminar de graça.
 *
 * `onFocusChanged` precisa vir **antes** do `clickable` na cadeia de Modifier:
 * ele observa o nó de foco declarado depois dele. Invertido, nunca dispara.
 *
 * A borda animada é lida só na hora de desenhar (`drawWithContent`), nunca na
 * composição. Com `.border(2.dp, cor)` a cor animada era lida ao compor, e
 * cada quadro da animação recompunha o cartão inteiro — imagem e texto junto.
 * Cada passo do direcional anima dois cartões (o que perde e o que ganha
 * foco); segurando o botão numa TV Box fraca, era isso que engasgava a lista.
 */
@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    background: Color = Color.Transparent,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focusScale")
    val border = animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "focusBorder",
    )
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .drawWithContent {
                drawContent()
                val color = border.value
                if (color.alpha > 0f) {
                    // Metade do traço cairia fora do clip; o inset o mantém
                    // inteiro por dentro, como o Modifier.border fazia.
                    val stroke = 2.dp.toPx()
                    inset(stroke / 2) {
                        drawOutline(
                            outline = shape.createOutline(size, layoutDirection, this),
                            color = color,
                            style = Stroke(stroke),
                        )
                    }
                }
            },
    ) {
        content(focused)
    }
}

/**
 * Imagem remota. Sem placeholder animado e sem ícone de erro: logo de painel
 * IPTV quebra o tempo todo, e um ícone de "quebrado" repetido pela grade
 * inteira chama mais atenção do que o conteúdo. Falhou, fica o retângulo neutro.
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
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
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
fun ErrorBox(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Tentar de novo") }
        }
    }
}

@Composable
fun EmptyBox(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

/** Barra fininha de progresso: "continuar assistindo" e EPG. */
@Composable
fun ThinProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Pôster 2:3 — filme e série. */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FocusableCard(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            RemoteImage(
                url = imageUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            )
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

/** Linha de canal / episódio: miniatura à esquerda, texto à direita. */
@Composable
fun ChannelRow(
    title: String,
    imageUrl: String?,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    FocusableCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteImage(
                url = imageUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

/**
 * Campo de texto que só vira campo quando você aperta OK nele.
 *
 * Na TV, um `OutlinedTextField` comum abre o teclado assim que recebe foco. O
 * campo de busca fica no topo da tela, então qualquer passo do direcional que
 * passasse por ele — subir até a barra, descer dela, a lista recarregar — abria
 * o teclado por cima de tudo. Aqui, parado, ele é um cartão com cara de campo:
 * o foco passa por ele sem efeito, e o teclado só aparece com OK ou toque.
 *
 * Ao sair do campo (direcional, ou a tecla de buscar/concluir do teclado), ele
 * volta a ser cartão e mostra o que foi digitado.
 */
@Composable
fun ClickToEditField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    maxLines: Int = 1,
) {
    var editing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    if (editing) {
        val focusRequester = remember { FocusRequester() }
        // O onFocusChanged dispara uma vez com "sem foco" logo ao entrar na
        // tela, antes do requestFocus. Sem esta trava, o campo se fecharia
        // no mesmo instante em que abriu.
        var hadFocus by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null) } },
            singleLine = maxLines == 1,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.moveFocus(FocusDirection.Down) },
                onSearch = { focusManager.moveFocus(FocusDirection.Down) },
            ),
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true
                    else if (hadFocus) editing = false
                },
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    } else {
        FocusableCard(
            onClick = { editing = true },
            modifier = modifier,
            shape = RoundedCornerShape(4.dp),
            background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (leadingIcon != null) {
                    Icon(
                        leadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = value.ifEmpty { label },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
