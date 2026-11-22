package br.com.iordan.ranga.pc.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/** Uma faixa de áudio ou de legenda oferecida pela mídia. */
data class Track(val id: Int, val label: String)

/** Por que a reprodução parou. Quem escreve a frase para o usuário é a UI. */
enum class PlaybackFailure {
    /** A libvlc não conseguiu abrir o stream. */
    OPEN_FAILED,

    /** Abriu e tocou, mas o tempo congelou e não voltou. */
    STALLED,
}

data class PlaybackState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 80,
    val failure: PlaybackFailure? = null,
    /** Fica true quando a mídia chega ao fim — a UI usa para emendar o próximo. */
    val finished: Boolean = false,
    /** Canal ao vivo. Muda o que a barra oferece e o que o watchdog espera. */
    val isLive: Boolean = false,
    /** 0 quando está tudo bem; 1..N durante uma tentativa de reconexão. */
    val reconnectAttempt: Int = 0,
    val audioTracks: List<Track> = emptyList(),
    val audioTrack: Int = -1,
    val subtitleTracks: List<Track> = emptyList(),
    val subtitleTrack: Int = -1,
) {
    /**
     * Ao vivo não tem duração — mas nem sempre o painel concorda: os do
     * Iordan mandam uma duração pequena e falsa num canal ao vivo, e a barra
     * de posição aparecia marcando "0min / 0min", como se desse para arrastar.
     * Canal ao vivo não é procurável, e quem decide isso aqui é o catálogo,
     * não o servidor.
     */
    val isSeekable: Boolean get() = !isLive && durationMs > 0
}

/**
 * Casca fina em volta do vlcj.
 *
 * O ponto delicado aqui é **thread**. Os callbacks do VLC chegam numa thread
 * nativa, não na thread de UI. Escrever `mutableStateOf` do Compose de lá é
 * pedir corrida com o sistema de snapshots. Por isso todo evento cai num
 * [MutableStateFlow], que é seguro para escrever de qualquer thread, e a UI
 * apenas coleta. É o mesmo motivo pelo qual não guardo nada em `var` solto.
 *
 * A mesma regra vale na volta: nada aqui chama de dentro de um callback um
 * método que fale com a libvlc de novo — a documentação do vlcj avisa que isso
 * trava. Tudo que precisa perguntar algo ao player depois de um evento passa
 * pelo [scope].
 */
class PlayerController(
    private val userAgent: String,
    val videoMode: VideoMode,
) {

    /**
     * A superfície acelerada: um canvas pesado do AWT onde a própria libvlc
     * escreve pelo Direct3D. Existe só no modo [VideoMode.ACCELERATED], e é
     * ela que impede qualquer desenho do Compose por cima.
     */
    val awtComponent: EmbeddedMediaPlayerComponent? =
        if (videoMode == VideoMode.ACCELERATED) EmbeddedMediaPlayerComponent() else null

    /** Os quadros para o Compose desenhar. Só no modo [VideoMode.OVERLAY]. */
    val frames: VideoFrames? = if (videoMode == VideoMode.OVERLAY) VideoFrames() else null

    private val ownFactory: MediaPlayerFactory? =
        if (videoMode == VideoMode.OVERLAY) MediaPlayerFactory(*VlcEngine.OVERLAY_ARGS) else null

    private val player: EmbeddedMediaPlayer = when (videoMode) {
        VideoMode.ACCELERATED -> awtComponent!!.mediaPlayer()
        VideoMode.OVERLAY -> ownFactory!!.mediaPlayers().newEmbeddedMediaPlayer().also {
            it.videoSurface().set(
                ownFactory.videoSurfaces().newVideoSurface(frames!!, frames, true),
            )
        }
    }

    /**
     * O log da libvlc em arquivo. Ver [PlayerLog] — existe para diagnóstico à
     * distância, e não atrapalha nada se falhar.
     */
    private val vlcLog: PlayerLog? = PlayerLog.attach(
        when (videoMode) {
            VideoMode.ACCELERATED -> awtComponent!!.mediaPlayerFactory()
            VideoMode.OVERLAY -> ownFactory!!
        },
    )

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** O que está tocando, para a reconexão saber o que reabrir. */
    private var currentUrl: String? = null
    private var currentFallbackUrl: String? = null
    private var currentIsLive = false
    private var pendingSeekMs: Long = 0

    private var reconnectJob: Job? = null

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {

            override fun playing(mediaPlayer: MediaPlayer) {
                _state.update {
                    it.copy(playing = true, buffering = false, failure = null, reconnectAttempt = 0)
                }
                refreshTracks()
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _state.update { it.copy(playing = false) }
            }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                // O VLC manda 0..100. Só considero "carregando" abaixo de 100
                // para a rodinha não piscar a cada reajuste de buffer.
                _state.update { it.copy(buffering = newCache < 100f) }
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                _state.update { it.copy(positionMs = newTime) }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _state.update { it.copy(durationMs = newLength.coerceAtLeast(0)) }
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                // Canal ao vivo não "acaba". Quando a libvlc diz que acabou, o
                // servidor fechou a conexão — é queda, não fim, e o certo é
                // voltar sozinho em vez de deixar a tela preta.
                if (currentIsLive) reconnect(PlaybackFailure.STALLED)
                else _state.update { it.copy(finished = true, playing = false) }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                reconnect(PlaybackFailure.OPEN_FAILED)
            }

            override fun elementaryStreamAdded(
                mediaPlayer: MediaPlayer,
                type: TrackType,
                id: Int,
            ) = refreshTracks()

            override fun elementaryStreamSelected(
                mediaPlayer: MediaPlayer,
                type: TrackType,
                id: Int,
            ) = refreshTracks()
        })

        startStallWatchdog()
    }

    // ------------------------------------------------------------ comandos

    /**
     * @param fallbackUrl o mesmo canal no outro formato (HLS ↔ MPEG-TS). A
     *   reconexão alterna entre os dois: painel que engasga num formato quase
     *   sempre entrega o outro, e antes isso era instrução no manual — trocar
     *   à mão em Configurações e tentar de novo.
     */
    fun play(
        url: String,
        startPositionMs: Long = 0,
        isLive: Boolean = false,
        fallbackUrl: String? = null,
    ) {
        reconnectJob?.cancel()
        currentUrl = url
        currentFallbackUrl = fallbackUrl
        currentIsLive = isLive
        pendingSeekMs = startPositionMs
        _state.update {
            it.copy(
                buffering = true,
                failure = null,
                finished = false,
                isLive = isLive,
                reconnectAttempt = 0,
                positionMs = 0,
                durationMs = 0,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
            )
        }
        open(url)
    }

    fun togglePause() = player.controls().pause()

    fun seekTo(ms: Long) {
        if (player.status().isSeekable) player.controls().setTime(ms)
    }

    fun skip(deltaMs: Long) {
        if (player.status().isSeekable) player.controls().skipTime(deltaMs)
    }

    fun setVolume(value: Int) {
        val clamped = value.coerceIn(0, 100)
        player.audio().setVolume(clamped)
        _state.update { it.copy(volume = clamped) }
    }

    fun setAudioTrack(id: Int) = scope.launch {
        player.audio().setTrack(id)
        _state.update { it.copy(audioTrack = id) }
    }

    fun setSubtitleTrack(id: Int) = scope.launch {
        player.subpictures().setTrack(id)
        _state.update { it.copy(subtitleTrack = id) }
    }

    /** Chamado pela UI quando a reprodução começa, para aplicar a retomada. */
    fun applyPendingSeek() {
        val target = pendingSeekMs
        if (target > 0 && player.status().isSeekable) {
            player.controls().setTime(target)
            pendingSeekMs = 0
        }
    }

    fun stop() = player.controls().stop()

    /** Obrigatório: sem release a libvlc vaza a thread nativa e o app não fecha. */
    fun release() {
        // `cancelAndJoin`, e não `cancel`: o scope tem corrotinas que falam com
        // a libvlc — leitura de faixas, reconexão. Cancelar e seguir em frente
        // deixaria uma delas conversando com um player que a linha seguinte
        // destrói. O join é rápido: o que está em voo é uma chamada JNI curta
        // ou um `delay`, que morre na hora.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        vlcLog?.release()
        when (videoMode) {
            VideoMode.ACCELERATED -> awtComponent!!.release()
            VideoMode.OVERLAY -> {
                player.release()
                ownFactory!!.release()
                frames?.release()
            }
        }
    }

    // ------------------------------------------------------------- interno

    private fun open(url: String) {
        player.media().play(url, *VlcEngine.mediaOptions(userAgent))
    }

    /**
     * Reabre o mesmo stream algumas vezes antes de desistir.
     *
     * IPTV cai. O servidor derruba a conexão, o CDN troca de nó, o Wi-Fi
     * oscila — e o comportamento antigo era jogar um erro na cara de quem só
     * queria continuar vendo o jogo. Três tentativas com espera crescente
     * cobrem a queda passageira; o que sobrevive a elas é problema de verdade,
     * e aí sim vira mensagem.
     *
     * A posição é preservada: filme e série voltam de onde pararam, e ao vivo
     * ignora o valor porque o stream não é procurável.
     */
    private fun reconnect(failure: PlaybackFailure) {
        val url = currentUrl
        if (url == null) {
            _state.update { it.copy(failure = failure, playing = false, buffering = false) }
            return
        }
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            val resumeAt = if (currentIsLive) 0L else _state.value.positionMs

            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                _state.update {
                    it.copy(
                        reconnectAttempt = attempt,
                        buffering = true,
                        playing = false,
                        failure = null,
                    )
                }

                // Espera crescente: 2s, 4s, 6s. Voltar na hora costuma bater no
                // mesmo servidor ainda ocupado, e queima uma tentativa à toa.
                delay(attempt * 2_000L)
                if (!isActive) return@launch

                // Ímpar tenta o formato original, par tenta o alternativo.
                val target = if (attempt % 2 == 0) currentFallbackUrl ?: url else url

                player.controls().stop()
                pendingSeekMs = resumeAt
                open(target)

                val recovered = withTimeoutOrNull(RECONNECT_TIMEOUT_MS) {
                    state.first { it.playing }
                } != null
                if (recovered) return@launch
            }

            _state.update {
                it.copy(
                    failure = failure,
                    playing = false,
                    buffering = false,
                    reconnectAttempt = 0,
                )
            }
        }
    }

    /**
     * Vigia se ainda está chegando imagem.
     *
     * A queda mais comum de IPTV não dá erro: o stream para de chegar e a
     * libvlc fica esperando para sempre, com o último quadro congelado. Nenhum
     * evento avisa.
     *
     * A primeira versão disto olhava o relógio de reprodução, e estava errada:
     * tem canal ao vivo cujo tempo fica parado em zero enquanto o vídeo corre
     * normalmente. O watchdog interpretava isso como queda e reconectava
     * sozinho a cada 12 segundos — ou seja, ele *causava* o travamento que
     * deveria consertar.
     *
     * O sinal certo são os contadores da própria libvlc: quadros exibidos e
     * bytes lidos pelo demuxer. Se qualquer um dos dois anda, está vivo.
     *
     * E, na dúvida, não age: sem estatística legível, nada de reconectar.
     * Derrubar uma reprodução boa por falta de informação é pior do que deixar
     * um congelamento passar.
     */
    private fun startStallWatchdog() = scope.launch {
        var lastSignature: String? = null
        var frozenSince = System.currentTimeMillis()

        while (isActive) {
            delay(WATCHDOG_INTERVAL_MS)

            val snapshot = _state.value
            if (!snapshot.playing || snapshot.reconnectAttempt > 0) {
                lastSignature = null
                frozenSince = System.currentTimeMillis()
                continue
            }

            val statistics = runCatching { player.media().info()?.statistics() }.getOrNull()
            if (statistics == null) {
                frozenSince = System.currentTimeMillis()
                continue
            }

            val signature = "${statistics.picturesDisplayed()}:" +
                "${statistics.demuxBytesRead()}:${snapshot.positionMs}"
            if (signature != lastSignature) {
                lastSignature = signature
                frozenSince = System.currentTimeMillis()
                continue
            }

            if (System.currentTimeMillis() - frozenSince > STALL_MS) {
                reconnect(PlaybackFailure.STALLED)
            }
        }
    }

    /**
     * Lê as faixas de áudio e legenda que a mídia oferece.
     *
     * Sempre fora da thread do evento: perguntar à libvlc de dentro de um
     * callback dela é o caminho conhecido para travar o processo.
     */
    private fun refreshTracks() {
        scope.launch {
            val audio = runCatching { player.audio().trackDescriptions().map(::toTrack) }
                .getOrDefault(emptyList())
            val subtitles = runCatching { player.subpictures().trackDescriptions().map(::toTrack) }
                .getOrDefault(emptyList())
            val currentAudio = runCatching { player.audio().track() }.getOrDefault(-1)
            val currentSubtitle = runCatching { player.subpictures().track() }.getOrDefault(-1)

            // O tamanho que a libvlc considera imagem, para o desenho recortar
            // as linhas de alinhamento do buffer. Ver VideoFrames.setVisibleSize.
            runCatching { player.video().videoDimension() }.getOrNull()?.let {
                frames?.setVisibleSize(it.width, it.height)
            }

            _state.update {
                it.copy(
                    audioTracks = audio,
                    audioTrack = currentAudio,
                    subtitleTracks = subtitles,
                    subtitleTrack = currentSubtitle,
                )
            }
        }
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_TIMEOUT_MS = 20_000L

        const val WATCHDOG_INTERVAL_MS = 2_000L

        /**
         * 20s sem um quadro novo nem um byte novo. Generoso de propósito:
         * rebuffer de canal pesado se resolve sozinho e não merece uma
         * reconexão, e o custo de esperar demais é bem menor que o de derrubar
         * quem estava assistindo.
         */
        const val STALL_MS = 20_000L
    }
}

/**
 * O VLC descreve as faixas como "Track 2 - [Portuguese]" ou "Disable". Corto o
 * prefixo e os colchetes: quem está escolhendo o áudio de um filme quer ler
 * "Portuguese", não a numeração interna do demuxer.
 */
private fun toTrack(description: uk.co.caprica.vlcj.player.base.TrackDescription): Track {
    val raw = description.description().orEmpty().trim()
    val label = raw
        .substringAfter(" - ", raw)
        .removePrefix("[")
        .removeSuffix("]")
        .trim()
        .ifEmpty { raw }
    return Track(
        id = description.id(),
        label = if (label.equals("Disable", ignoreCase = true)) "Desligada" else label,
    )
}
