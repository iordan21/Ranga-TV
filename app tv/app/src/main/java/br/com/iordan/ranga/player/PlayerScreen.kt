package br.com.iordan.ranga.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import br.com.iordan.ranga.container
import br.com.iordan.ranga.data.EpgEntry
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.ui.common.ScreenCast
import br.com.iordan.ranga.ui.common.ThinProgressBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val OVERLAY_TIMEOUT_MS = 6_000L
private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    startIndex: Int,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.container
    val activity = remember(context) { context.findActivity() }

    var index by remember { mutableIntStateOf(startIndex) }
    // Contador usado só como chave de LaunchedEffect: incrementar força
    // uma nova tentativa de preparar o stream (retry / troca para fallback).
    var attempt by remember { mutableIntStateOf(0) }
    // Em qual item da fila já troquei para o formato alternativo (-1 = nenhum).
    // Guardar o índice, e não um Boolean, é o que impede o "já tentei" de um
    // canal vazar para o próximo: com o Boolean, o reset ficava num
    // LaunchedEffect separado que rodava depois do que prepara a mídia — o
    // canal seguinte abria direto no .ts e o .m3u8 nunca era tentado.
    var fallbackIndex by remember { mutableIntStateOf(-1) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var overlayVisible by remember { mutableStateOf(true) }
    // Filme e série usam a barra de controles do Media3; guardo se ela está
    // na tela para o botão de transmitir aparecer e sumir junto com ela.
    var controllerVisible by remember { mutableStateOf(false) }
    var epg by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    val current: Playable? = app.queue.itemAt(index)

    val exoPlayer = remember {
        // OkHttpDataSource em vez do DefaultHttpDataSource: reaproveita o mesmo
        // cliente da API, e com ele o User-Agent configurável e o pool de
        // conexões. Painel que filtra UA rejeitaria o player mas aceitaria a
        // API — o bug mais confuso possível de diagnosticar.
        val dataSourceFactory = OkHttpDataSource.Factory(app.okHttp)

        // Buffer generoso: IPTV oscila muito mais que vídeo de CDN.
        // O default do ExoPlayer é afinado para streaming estável e engasga.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .build()

        ExoPlayer.Builder(context)
            // Parser de HLS tolerante; ver RangaMediaSourceFactory.
            .setMediaSourceFactory(RangaMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
    }

    // ------------------------------------------------------------- listener

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING

                // Maratona: quando um episódio acaba, emenda no próximo da fila
                // (a tela de série enfileira a temporada inteira). Canal ao vivo
                // nunca chega em ENDED, então não corro risco de "zapear" sozinho.
                if (state == Player.STATE_ENDED) {
                    val finished = app.queue.itemAt(index)
                    if (finished?.isLive == false && index < app.queue.items.lastIndex) {
                        index += 1
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = app.queue.itemAt(index)
                // Canal ao vivo tem dois formatos (.m3u8 e .ts). Muito painel
                // serve bem um e mal o outro, e isso varia por canal. Em vez de
                // mostrar erro de cara, tento o outro formato uma vez.
                if (item?.fallbackUrl != null && fallbackIndex != index) {
                    fallbackIndex = index
                    attempt++
                } else {
                    errorMessage = error.friendlyMessage()
                    overlayVisible = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // ------------------------------------------------------- preparar mídia

    LaunchedEffect(index, attempt) {
        val item = app.queue.itemAt(index) ?: return@LaunchedEffect
        errorMessage = null
        buffering = true
        val url = if (fallbackIndex == index && item.fallbackUrl != null) item.fallbackUrl else item.url
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        if (item.startPositionMs > 0) exoPlayer.seekTo(item.startPositionMs)
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(index) {
        overlayVisible = true
        epg = emptyList()
        val item = app.queue.itemAt(index)
        if (item?.streamId != null) {
            val creds = app.session.credentials.first()
            epg = app.catalog.shortEpg(creds, item.streamId)
        }
    }

    // ------------------------------------------------ continuar assistindo

    LaunchedEffect(index) {
        val item = app.queue.itemAt(index) ?: return@LaunchedEffect
        if (item.resumePath == null) return@LaunchedEffect // ao vivo não retoma
        while (true) {
            delay(PROGRESS_SAVE_INTERVAL_MS)
            app.saveProgress(item, exoPlayer)
        }
    }

    // Salvar também na saída: quem fecha no minuto 8 espera voltar no minuto 8,
    // e não no último tick de 10s.
    DisposableEffect(exoPlayer) {
        onDispose {
            val item = app.queue.itemAt(index)
            val position = exoPlayer.currentPosition
            val duration = exoPlayer.duration
            exoPlayer.release()
            if (item?.resumePath != null) {
                app.launchDetached {
                    app.session.saveProgress(item.toResumeEntry(position, duration))
                }
            }
        }
    }

    // Some com o overlay sozinho depois de um tempo (só quando não há erro).
    LaunchedEffect(overlayVisible, errorMessage) {
        if (overlayVisible && errorMessage == null) {
            delay(OVERLAY_TIMEOUT_MS)
            overlayVisible = false
        }
    }

    // ----------------------------------------------------------- teclas TV

    val isLive = current?.isLive == true

    DisposableEffect(activity, isLive, index, errorMessage) {
        val playerActivity = activity as? PlayerActivity
        playerActivity?.keyHandler = handler@{ event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@handler false
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (overlayVisible && errorMessage == null) {
                        overlayVisible = false
                        true
                    } else {
                        false // deixa a Activity fechar normalmente
                    }
                }

                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (isLive) { index = app.queue.indexBefore(index); true } else false
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (isLive) { index = app.queue.indexAfter(index); true } else false
                }

                // Direcional só faz zapping no ao vivo, onde não existe
                // controlador do PlayerView disputando as setas.
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isLive) { index = app.queue.indexBefore(index); true } else false
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isLive) { index = app.queue.indexAfter(index); true } else false
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (isLive) { overlayVisible = !overlayVisible; true } else false
                }

                else -> false
            }
        }
        onDispose { playerActivity?.keyHandler = null }
    }

    // ---------------------------------------------------------------- view

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // No ao vivo o controlador nativo está desligado, então sem isto o
            // usuário de celular não teria como trazer a barra de volta depois
            // que ela some. Uso pointerInput e não clickable de propósito:
            // clickable criaria um alvo de foco que roubaria o direcional na TV.
            .then(
                if (isLive) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { overlayVisible = !overlayVisible }
                    }
                } else Modifier
            ),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    player = exoPlayer
                    // Ao vivo não tem barra de progresso útil, então desligo o
                    // controlador nativo e uso meu overlay. No VOD o
                    // controlador do Media3 é melhor do que qualquer coisa que
                    // eu faria: seek, velocidade, faixa de áudio e legenda,
                    // tudo pronto e já navegável por controle remoto.
                    useController = !(current?.isLive ?: false)
                    setShowSubtitleButton(true)
                    setShowShuffleButton(false)
                    controllerShowTimeoutMs = 4_000
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controllerVisible = visibility == View.VISIBLE
                        }
                    )
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view ->
                view.useController = !(current?.isLive ?: false)
                view.resizeMode = resizeMode
            },
        )

        // No ao vivo o botão de transmitir fica no meu overlay. No filme e na
        // série o overlay não existe (a barra é a do Media3), então ele vai no
        // canto de cima, fora do caminho dos controles de baixo.
        if (!isLive && controllerVisible && errorMessage == null && ScreenCast.isAvailable(context)) {
            Button(
                onClick = { ScreenCast.open(context) },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(
                    ScreenCast.icon,
                    contentDescription = "Transmitir para a TV",
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (buffering && errorMessage == null) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible || errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            PlayerOverlay(
                item = current,
                epg = epg,
                errorMessage = errorMessage,
                showChannelButtons = isLive,
                onPrevious = { index = app.queue.indexBefore(index) },
                onNext = { index = app.queue.indexAfter(index) },
                onCycleAspect = { resizeMode = nextResizeMode(resizeMode) },
                onCast = if (ScreenCast.isAvailable(context)) {
                    { ScreenCast.open(context) }
                } else null,
                onRetry = {
                    fallbackIndex = -1
                    attempt++
                },
            )
        }
    }

    // Se a fila veio vazia (processo recriado do zero), não há o que tocar.
    LaunchedEffect(current) {
        if (current == null) onExit()
    }
}

@Composable
private fun PlayerOverlay(
    item: Playable?,
    epg: List<EpgEntry>,
    errorMessage: String?,
    showChannelButtons: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleAspect: () -> Unit,
    /** null na TV, onde transmitir não faz sentido. */
    onCast: (() -> Unit)?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                )
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = item?.title ?: "",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = item?.subtitle
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val now = System.currentTimeMillis() / 1000
        val airing = epg.firstOrNull { now in it.startEpochSecs..it.endEpochSecs }
        val next = epg.firstOrNull { it.startEpochSecs > now }

        if (airing != null) {
            Text(
                text = "Agora · ${airing.clock()} — ${airing.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ThinProgressBar(
                progress = airing.progressAt(now),
                modifier = Modifier.clip(RoundedCornerShape(2.dp)),
            )
        }
        if (next != null) {
            Text(
                text = "A seguir · ${next.clock()} — ${next.title}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (errorMessage != null) {
                Button(onClick = onRetry) { Text("Tentar de novo") }
            }
            if (showChannelButtons) {
                Button(onClick = onPrevious) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Canal anterior",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Button(onClick = onNext) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Próximo canal",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Button(onClick = onCycleAspect) { Text("Proporção") }
            if (onCast != null) {
                Button(onClick = onCast) {
                    Icon(
                        ScreenCast.icon,
                        contentDescription = "Transmitir para a TV",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (showChannelButtons) {
            Text(
                text = "↑ ↓ troca canal · OK oculta",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
            )
        }
    }
}

// ------------------------------------------------------------------ helpers

@OptIn(UnstableApi::class)
private fun nextResizeMode(current: Int): Int = when (current) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun EpgEntry.clock(): String = clockFormat.format(Date(startEpochSecs * 1000))

/**
 * Traduz o erro do ExoPlayer para algo acionável. O caso que mais importa é o
 * de decoder: TV Box antigo não decodifica H.265/4K, e o erro genérico
 * ("Playback error") não dá nenhuma pista disso.
 */
private fun PlaybackException.friendlyMessage(): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "Sem conexão com o servidor do provedor."

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "O servidor recusou este stream. Pode ser limite de conexões simultâneas: " +
            "feche o app em outro aparelho e tente de novo."

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        "Esse conteúdo não existe mais no servidor."

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> "O stream veio corrompido. Tente o outro formato em Configurações."

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> "Este aparelho não consegue decodificar esse vídeo (provavelmente H.265/4K). " +
        "Procure a versão SD/HD do mesmo canal."

    // Código numérico, e não o errorCodeName: o nome é inglês técnico
    // ("IO_UNSPECIFIED") e não diz nada a quem está no sofá, mas o número
    // ainda serve para eu pesquisar quando você me mandar print.
    else -> "Não consegui reproduzir este conteúdo (erro $errorCode). Tente de novo ou outro canal."
}

private fun Playable.toResumeEntry(positionMs: Long, durationMs: Long) = ResumeEntry(
    key = key,
    title = title,
    subtitle = subtitle,
    image = image,
    streamPath = resumePath.orEmpty(),
    positionMs = positionMs,
    durationMs = if (durationMs > 0) durationMs else 0L,
)

private suspend fun br.com.iordan.ranga.AppContainer.saveProgress(
    item: Playable,
    player: ExoPlayer,
) {
    if (item.resumePath == null) return
    session.saveProgress(item.toResumeEntry(player.currentPosition, player.duration))
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
