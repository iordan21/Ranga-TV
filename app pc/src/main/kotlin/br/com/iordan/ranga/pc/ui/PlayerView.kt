package br.com.iordan.ranga.pc.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.pc.AppState
import br.com.iordan.ranga.pc.player.PlaybackFailure
import br.com.iordan.ranga.pc.player.PlaybackState
import br.com.iordan.ranga.pc.player.PlayerController
import br.com.iordan.ranga.pc.player.Track
import br.com.iordan.ranga.pc.player.VideoFrames
import br.com.iordan.ranga.pc.player.VideoMode
import br.com.iordan.ranga.pc.player.VlcEngine
import kotlinx.coroutines.delay
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun PlayerView(
    app: AppState,
    queue: List<Playable>,
    startIndex: Int,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onExit: () -> Unit,
) {
    if (!VlcEngine.isAvailable) {
        EngineMissingView(onExit)
        return
    }

    val settings by app.settings.state.collectAsState()
    var index by remember { mutableIntStateOf(startIndex.coerceIn(queue.indices)) }
    val current = queue.getOrNull(index)

    // Sem chave no remember de propósito: trocar o modo de vídeo exige sair do
    // player, e recriar o controller no meio de um filme derrubaria a imagem.
    val controller = remember { PlayerController(settings.userAgent, settings.videoMode) }
    val playback by controller.state.collectAsState()
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    fun next() { if (queue.getOrNull(index)?.isLive == true) index = (index + 1).mod(queue.size) }
    fun previous() { if (queue.getOrNull(index)?.isLive == true) index = (index - 1).mod(queue.size) }

    /*
     * Teclado pelo KeyEventDispatcher do AWT, e não por Modifier do Compose.
     *
     * Este foi o bug do "não consigo sair da tela cheia". No modo acelerado a
     * superfície de vídeo é um componente AWT pesado dentro do SwingPanel.
     * Quando ela tem o foco — e ela pega o foco sozinha, ainda mais depois de a
     * janela mudar de modo — as teclas vão para a hierarquia de Views do AWT e
     * nunca chegam ao `onPreviewKeyEvent` do Compose.
     *
     * O KeyEventDispatcher enxerga *toda* tecla da aplicação antes de qualquer
     * componente, independente de quem tem o foco. É o mesmo raciocínio do
     * `dispatchKeyEvent` na PlayerActivity do app Android — mesmo problema,
     * mesmo remédio, outra plataforma.
     *
     * No modo sobreposto não haveria mais essa disputa de foco, mas o
     * dispatcher fica: um caminho só para o teclado é mais fácil de manter do
     * que dois que precisam se comportar igual.
     */
    DisposableEffect(controller, isFullscreen) {
        val keys = KeyEventDispatcher { event ->
            if (event.id != AwtKeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            when (event.keyCode) {
                // Em tela cheia, Esc sai da tela cheia; fora dela, fecha o
                // player. É o que todo mundo espera, e evita o susto de perder
                // o canal só porque quis voltar para a janela.
                AwtKeyEvent.VK_ESCAPE -> {
                    if (isFullscreen) onToggleFullscreen() else onExit()
                    true
                }

                AwtKeyEvent.VK_F, AwtKeyEvent.VK_F11 -> { onToggleFullscreen(); true }
                // Enter é o A do controle (input/Gamepad.kt); PgUp/PgDn, o LB/RB.
                AwtKeyEvent.VK_SPACE, AwtKeyEvent.VK_ENTER -> { controller.togglePause(); true }
                AwtKeyEvent.VK_UP, AwtKeyEvent.VK_PAGE_UP -> { previous(); true }
                AwtKeyEvent.VK_DOWN, AwtKeyEvent.VK_PAGE_DOWN -> { next(); true }
                AwtKeyEvent.VK_LEFT -> { controller.skip(-10_000); true }
                AwtKeyEvent.VK_RIGHT -> { controller.skip(30_000); true }
                else -> false
            }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)

        // O duplo clique é a segunda saída, para quem está com a mão no mouse.
        // Só o modo acelerado precisa dele aqui: no sobreposto quem escuta o
        // clique é o próprio Compose, logo abaixo.
        val surface = controller.awtComponent?.videoSurfaceComponent()
        val doubleClick = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) onToggleFullscreen()
            }
        }
        surface?.addMouseListener(doubleClick)

        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys)
            surface?.removeMouseListener(doubleClick)
        }
    }

    LaunchedEffect(index) {
        val item = queue.getOrNull(index) ?: return@LaunchedEffect
        controller.play(
            url = item.url,
            startPositionMs = item.startPositionMs,
            isLive = item.isLive,
            fallbackUrl = item.fallbackUrl,
        )
    }

    // Erro em tela cheia sai da tela cheia — só no modo acelerado, onde a
    // mensagem mora numa faixa de três pixels abaixo do vídeo que ninguém lê.
    // No sobreposto ela aparece no meio da tela, e sair seria atrapalhar.
    LaunchedEffect(playback.failure) {
        if (playback.failure != null && isFullscreen &&
            controller.videoMode == VideoMode.ACCELERATED
        ) {
            onToggleFullscreen()
        }
    }

    // A retomada só pode ser aplicada depois que a mídia abriu; antes disso o
    // VLC ignora o setTime silenciosamente.
    LaunchedEffect(playback.playing) {
        if (playback.playing) controller.applyPendingSeek()
    }

    // Emenda no próximo episódio. Canal ao vivo nunca "termina", então não há
    // risco de zapear sozinho.
    LaunchedEffect(playback.finished) {
        if (playback.finished && current?.isLive == false && index < queue.lastIndex) {
            index += 1
        }
    }

    // Progresso de "continuar assistindo", igual ao app de TV: a cada 10s.
    LaunchedEffect(index) {
        val item = queue.getOrNull(index) ?: return@LaunchedEffect
        if (item.resumePath == null) return@LaunchedEffect
        while (true) {
            delay(10_000)
            app.settings.saveProgress(item.toResume(controller))
        }
    }

    DisposableEffect(index) {
        onDispose {
            val item = queue.getOrNull(index)
            if (item?.resumePath != null) app.settings.saveProgress(item.toResume(controller))
        }
    }

    val message = failureMessage(app, playback)

    val controls = @Composable { onVideo: Boolean ->
        PlayerControls(
            controller = controller,
            playback = playback,
            current = current,
            isFullscreen = isFullscreen,
            onVideo = onVideo,
            onExit = onExit,
            onToggleFullscreen = onToggleFullscreen,
            onPrevious = ::previous,
            onNext = ::next,
        )
    }

    val retry = {
        current?.let {
            controller.play(it.url, it.startPositionMs, it.isLive, it.fallbackUrl)
        }
        Unit
    }

    when (controller.videoMode) {
        VideoMode.OVERLAY -> OverlayPlayer(
            frames = controller.frames!!,
            playback = playback,
            message = message,
            onRetry = retry,
            onToggleFullscreen = onToggleFullscreen,
            controls = controls,
        )

        VideoMode.ACCELERATED -> AcceleratedPlayer(
            controller = controller,
            playback = playback,
            message = message,
            onRetry = retry,
            isFullscreen = isFullscreen,
            controls = controls,
        )
    }
}

/* -------------------------------------------------------------------------
 * Os dois arranjos de tela
 * ---------------------------------------------------------------------- */

/**
 * Vídeo desenhado pelo Compose, com tudo o mais por cima.
 *
 * A barra some sozinha depois de três segundos parado e volta ao primeiro
 * movimento do mouse — inclusive em tela cheia, que é onde o modo acelerado
 * deixava o usuário sem nada além do teclado.
 */
@Composable
private fun OverlayPlayer(
    frames: VideoFrames,
    playback: PlaybackState,
    message: String?,
    onRetry: () -> Unit,
    onToggleFullscreen: () -> Unit,
    controls: @Composable (onVideo: Boolean) -> Unit,
) {
    val lastPointerAt = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var visible by remember { mutableStateOf(true) }

    // Pausado, reconectando ou com erro, a barra fica: são exatamente os
    // momentos em que a pessoa quer um botão à mão.
    val pinned = !playback.playing || playback.reconnectAttempt > 0 || message != null

    LaunchedEffect(lastPointerAt.longValue, pinned) {
        visible = true
        if (pinned) return@LaunchedEffect
        delay(3_000)
        visible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Passe Initial: quero saber do movimento antes de qualquer filho
            // consumir o evento, senão passar o mouse sobre um botão da barra
            // contaria como "parado" e ela sumiria embaixo do ponteiro.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move ||
                            event.type == PointerEventType.Press
                        ) {
                            lastPointerAt.longValue = System.currentTimeMillis()
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onToggleFullscreen() })
            },
    ) {
        VideoCanvas(frames, Modifier.fillMaxSize())

        StatusOverlay(
            playback = playback,
            message = message,
            onRetry = onRetry,
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
        )

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier.background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                    ),
                ),
            ) {
                controls(true)
            }
        }
    }
}

/**
 * O arranjo antigo, para quando o sobreposto pesar.
 *
 * Dentro da Box do vídeo só entra o SwingPanel. Nada de Compose por cima — a
 * superfície do VLC é um Canvas AWT pesado, e qualquer coisa que o Compose
 * desenhe ali some (ou pior: apaga o vídeo). Ver o comentário longo no
 * build.gradle.kts sobre `compose.interop.blending`.
 */
@Composable
private fun AcceleratedPlayer(
    controller: PlayerController,
    playback: PlaybackState,
    message: String?,
    onRetry: () -> Unit,
    isFullscreen: Boolean,
    controls: @Composable (onVideo: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SwingPanel(
                background = Color.Black,
                factory = { controller.awtComponent!! },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Faixa de estado, ABAIXO do vídeo. Fica visível também em tela cheia,
        // custando três pixels de altura, porque um erro invisível é pior do
        // que uma tarja fina.
        when {
            message != null -> Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onRetry) { Text("Tentar de novo") }
            }

            playback.reconnectAttempt > 0 -> Text(
                text = reconnectLabel(playback.reconnectAttempt),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            playback.buffering -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }

        // Barra fixa embaixo, e não sobreposta: aqui não dá para sobrepor.
        // Em tela cheia ela some junto, e o teclado vira a única saída.
        if (!isFullscreen) controls(false)
    }
}

/* -------------------------------------------------------------------------
 * Peças
 * ---------------------------------------------------------------------- */

/**
 * Desenha os quadros que a libvlc deposita.
 *
 * O laço puxa um quadro por quadro de tela, via [withFrameNanos] embutido no
 * `withFrameNanos` do Compose: pedir mais que isso seria desenhar duas vezes a
 * mesma imagem, e pedir menos deixaria o vídeo atrasado do monitor.
 *
 * O `letterbox` mantém a proporção do vídeo. Sem ele um filme 2.39:1 numa
 * janela 16:9 sairia esticado, que é o tipo de coisa que ninguém reporta como
 * bug mas todo mundo sente.
 */
@Composable
private fun VideoCanvas(frames: VideoFrames, modifier: Modifier = Modifier) {
    var frame by remember { mutableStateOf<VideoFrames.Frame?>(null) }

    LaunchedEffect(frames) {
        while (true) {
            withFrameNanos { }
            frames.poll()?.let { frame = it }
        }
    }

    Canvas(modifier) {
        val current = frame ?: return@Canvas
        val scale = min(
            size.width / current.visibleWidth,
            size.height / current.visibleHeight,
        )
        val width = (current.visibleWidth * scale).roundToInt()
        val height = (current.visibleHeight * scale).roundToInt()
        drawImage(
            image = current.image,
            // O recorte na origem descarta as linhas de alinhamento que vêm
            // no fim do buffer e não são parte da imagem.
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(current.visibleWidth, current.visibleHeight),
            dstOffset = IntOffset(
                x = ((size.width - width) / 2).roundToInt(),
                y = ((size.height - height) / 2).roundToInt(),
            ),
            dstSize = IntSize(width, height),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Carregando, reconectando ou erro — no meio da tela, sobre o vídeo. */
@Composable
private fun StatusOverlay(
    playback: PlaybackState,
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        message != null -> Column(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = message, color = Color.White)
            Button(onClick = onRetry) { Text("Tentar de novo") }
        }

        playback.reconnectAttempt > 0 -> Column(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Color.White)
            Text(text = reconnectLabel(playback.reconnectAttempt), color = Color.White)
        }

        playback.buffering -> CircularProgressIndicator(color = Color.White, modifier = modifier)
    }
}

/**
 * A barra de controles, uma só para os dois arranjos.
 *
 * `onVideo` troca as cores: sobre o vídeo tudo é branco, porque o fundo é o
 * filme e não o tema do app; abaixo dele vale o esquema do Material.
 */
@Composable
private fun PlayerControls(
    controller: PlayerController,
    playback: PlaybackState,
    current: Playable?,
    isFullscreen: Boolean,
    onVideo: Boolean,
    onExit: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val tint = if (onVideo) Color.White else MaterialTheme.colorScheme.onSurface
    val faded = if (onVideo) Color.White.copy(alpha = 0.75f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    val sliderColors = if (onVideo) {
        SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.35f),
        )
    } else {
        SliderDefaults.colors()
    }

    CompositionLocalProvider(LocalContentColor provides tint) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(
                    if (onVideo) Modifier
                    else Modifier.background(MaterialTheme.colorScheme.surface),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = tint)
            }
            IconButton(onClick = { controller.togglePause() }) {
                Icon(
                    imageVector = if (playback.playing) RangaIcons.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playback.playing) "Pausar" else "Tocar",
                    tint = tint,
                )
            }
            if (current?.isLive == true) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Canal anterior",
                        tint = tint,
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Próximo canal",
                        tint = tint,
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = current?.title.orEmpty(),
                    color = tint,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                current?.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = faded,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (playback.isSeekable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatClock(playback.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                        )
                        Slider(
                            value = playback.positionMs.toFloat(),
                            valueRange = 0f..playback.durationMs.toFloat(),
                            onValueChange = { controller.seekTo(it.toLong()) },
                            colors = sliderColors,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(
                            text = formatClock(playback.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                        )
                    }
                }
            }

            // Faixas: só aparecem quando há escolha a fazer. Um filme com uma
            // faixa de áudio e nenhuma legenda não ganha dois botões inúteis.
            TrackMenu(
                icon = RangaIcons.AudioTrack,
                label = "Áudio",
                tracks = playback.audioTracks,
                selected = playback.audioTrack,
                tint = tint,
                onSelect = { controller.setAudioTrack(it) },
            )
            TrackMenu(
                icon = RangaIcons.ClosedCaption,
                label = "Legenda",
                tracks = playback.subtitleTracks,
                selected = playback.subtitleTrack,
                tint = tint,
                onSelect = { controller.setSubtitleTrack(it) },
            )

            Icon(RangaIcons.VolumeUp, contentDescription = "Volume", tint = tint)
            Slider(
                value = playback.volume.toFloat(),
                valueRange = 0f..100f,
                onValueChange = { controller.setVolume(it.toInt()) },
                colors = sliderColors,
                modifier = Modifier.width(110.dp),
            )
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (isFullscreen) RangaIcons.FullscreenExit
                    else RangaIcons.Fullscreen,
                    contentDescription = "Tela cheia",
                    tint = tint,
                )
            }
        }
    }
}

/**
 * Menu de faixa de áudio ou legenda.
 *
 * Some quando há menos de duas opções. Para legenda o VLC já inclui a entrada
 * "Desligada" na lista, então duas opções significam "desligada" e uma legenda
 * de verdade — que é exatamente quando o botão faz falta.
 */
@Composable
private fun TrackMenu(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tracks: List<Track>,
    selected: Int,
    tint: Color,
    onSelect: (Int) -> Unit,
) {
    if (tracks.size < 2) return
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = track.label,
                            fontWeight = if (track.id == selected) FontWeight.Bold
                            else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(track.id)
                        open = false
                    },
                )
            }
        }
    }
}

private fun reconnectLabel(attempt: Int) = "Caiu. Reconectando ($attempt de 3)…"

/**
 * A frase que o usuário lê quando o player desiste.
 *
 * Antes era uma lista dos suspeitos habituais — limite de conexões, canal fora
 * do ar, formato errado — despejada de uma vez, que é o mesmo que não dizer
 * nada. Duas dessas hipóteses agora dá para descartar sozinho: o formato o
 * player já alternou na reconexão, e o limite de conexões o painel responde se
 * a gente perguntar. Só o que sobra vira texto.
 */
@Composable
private fun failureMessage(app: AppState, playback: PlaybackState): String? {
    val failure = playback.failure
    val credentials = app.settings.current.credentials

    // O `remember` e o `LaunchedEffect` vêm antes de qualquer saída
    // antecipada, de propósito: um `remember` atrás de um `return` condicional
    // muda a quantidade de slots que a composição guarda entre recomposições,
    // e é a receita para estado que troca de dono sozinho.
    var connectionLimit by remember(failure) { mutableStateOf<String?>(null) }
    LaunchedEffect(failure) {
        if (failure == null) return@LaunchedEffect
        val account = runCatching { app.catalog.login(credentials) }.getOrNull()
            ?: return@LaunchedEffect
        val max = account.maxConnections ?: return@LaunchedEffect
        val active = account.activeConnections ?: return@LaunchedEffect
        if (max in 1..active) {
            connectionLimit = "Seu plano permite $max conexão(ões) ao mesmo tempo, e " +
                "$active já está(ão) em uso. Feche o Ranga nos outros aparelhos e tente " +
                "de novo."
        }
    }

    if (failure == null) return null
    connectionLimit?.let { return it }

    return when (failure) {
        PlaybackFailure.OPEN_FAILED ->
            "Não deu para abrir. Tentei três vezes, nos dois formatos. O canal pode " +
                "estar fora do ar; se todos falharem, é o servidor."

        PlaybackFailure.STALLED ->
            "O canal parou de responder e não voltou em três tentativas. Costuma ser " +
                "queda do lado do provedor."
    }
}

/**
 * Tela de "não deu para tocar".
 *
 * Ela mudou de sentido quando a libvlc passou a viajar dentro do pacote: antes
 * era uma tarefa para o usuário — instale o VLC —, agora é sinal de pacote
 * quebrado. Quem chega aqui tem uma instalação incompleta, quase sempre porque
 * um antivírus comeu uma dll ou a cópia da pasta parou no meio.
 *
 * O botão do VLC continua, como última saída: com o VLC instalado o app volta a
 * tocar pelo motor do sistema enquanto ele não reinstala o Ranga.
 */
@Composable
private fun EngineMissingView(onExit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("O motor de vídeo não carregou", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "O Ranga já vem com o motor de vídeo dentro dele, então isto quer dizer " +
                "que a instalação está incompleta — o antivírus é o suspeito mais comum. " +
                "Reinstalar o Ranga resolve. Instalar o VLC 64 bits também serve de quebra-galho.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openInBrowser(VlcEngine.DOWNLOAD_URL) }) { Text("Baixar o VLC") }
            Button(onClick = onExit) { Text("Voltar") }
        }
    }
}

private fun Playable.toResume(controller: PlayerController) = ResumeEntry(
    key = key,
    title = title,
    subtitle = subtitle,
    image = image,
    streamPath = resumePath.orEmpty(),
    positionMs = controller.state.value.positionMs,
    durationMs = controller.state.value.durationMs,
)
