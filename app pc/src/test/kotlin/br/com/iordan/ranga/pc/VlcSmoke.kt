package br.com.iordan.ranga.pc

import br.com.iordan.ranga.pc.player.VlcEngine
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.Dimension
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Teste de fumaça do motor de vídeo. Rode com `./gradlew vlcSmoke`.
 *
 * Existe porque a integração com o VLC é o único pedaço do app de Windows que
 * nenhum teste de unidade alcança: ela depende de uma biblioteca nativa
 * resolvida por reflexão via JNA. Ou dá certo em runtime, ou não dá — e
 * descobrir isso abrindo a janela e clicando num canal é lento.
 *
 * Desde que a libvlc passou a viajar dentro do pacote, ele responde a uma
 * pergunta a mais: a árvore de plugins podada pela task `vlcRuntime` ainda
 * toca? Plugin faltando não dá erro de carregamento — dá vídeo que não abre.
 *
 * Usa um stream HLS público de propósito: valida descoberta da libvlc, rede,
 * demux e decode sem envolver credencial de assinatura nenhuma.
 */
private const val PUBLIC_HLS =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

fun main() {
    var failures = 0

    fun check(label: String, ok: Boolean, detail: String = "") {
        println(if (ok) "  OK    $label $detail" else "  FALHA $label $detail")
        if (!ok) failures++
    }

    println("1) Descoberta da libvlc")
    // Sempre pelo VlcEngine. É ele quem aponta o vlcj para a pasta embutida
    // antes da primeira busca, e o vlcj guarda o resultado da descoberta num
    // estático: quem chamar a NativeDiscovery antes dele acha o VLC do sistema
    // e trava essa escolha para o processo inteiro. O teste passaria contando
    // uma verdade que o app instalado não vive.
    check("VlcEngine.isAvailable", VlcEngine.isAvailable)
    check(
        "libvlc veio do pacote",
        VlcEngine.source == VlcEngine.Source.BUNDLED,
        "${VlcEngine.source} — ${VlcEngine.loadedFrom}",
    )
    if (!VlcEngine.isAvailable) {
        println("\nNenhuma libvlc encontrada — o resto não faz sentido.")
        println("Rode `./gradlew vlcRuntime` para extrair a embutida.")
        kotlin.system.exitProcess(1)
    }

    // As duas saídas "dummy" são obrigatórias aqui, e a razão é sutil: o
    // relógio de reprodução do VLC é puxado pela saída. Um player headless sem
    // janela e sem placa de som não tem saída nenhuma, então ele demuxa e
    // decodifica normalmente mas o tempo fica parado em 0 — o que parece bug do
    // app e não é.
    //
    // Só o --aout=dummy não bastava, e o teste falhava uma vez a cada duas ou
    // três. O stream da Apple tem uma faixa alternativa em AC-3, e quando o
    // demuxer escolhe essa faixa antes de descobrir a taxa de amostragem, a
    // saída de áudio não abre ("too low audio sample frequency (0)"). Sem
    // áudio e sem vídeo, ninguém dá o relógio. O --vout=dummy garante um
    // segundo candidato, e a falha intermitente sumiu.
    val factory = MediaPlayerFactory("--aout=dummy", "--vout=dummy")
    println("  versão da libvlc: ${factory.application().version()}")

    println("\n2) Reprodução de um stream HLS remoto (headless)")

    /*
     * Até três tentativas, e não uma. O relógio ficar parado em zero tem duas
     * causas possíveis: falta plugin na árvore podada, ou a CDN pública
     * engasgou no caminho. A primeira falha sempre, a segunda não se repete —
     * então repetir é o que separa uma da outra. Sem isso o teste acusava o
     * app por um handshake TLS que estourou o tempo.
     */
    var attempt = playOnce(factory)
    var tries = 1
    while (attempt.timeMs == 0L && tries < 3) {
        println("  tentativa $tries não saiu do zero. Repetindo — costuma ser rede.")
        tries++
        attempt = playOnce(factory)
    }

    check("chegou ao estado 'playing'", attempt.reachedPlaying)
    check(
        "tem faixa de vídeo",
        attempt.dimension != null,
        "${attempt.dimension?.width}x${attempt.dimension?.height}",
    )
    check(
        "posição avançou",
        attempt.timeMs > 0,
        "${attempt.timeMs} ms" + if (tries > 1) " (em $tries tentativas)" else "",
    )
    println("  faixas de áudio: ${attempt.audioTracks}")

    println("\n3) Superfície de vídeo embutida (o caminho que a UI usa)")
    // Este é o objeto que o PlayerController cria. Se o binding nativo estiver
    // errado, é aqui que estoura UnsatisfiedLinkError — e não no play().
    val component = runCatching { EmbeddedMediaPlayerComponent() }
    check("EmbeddedMediaPlayerComponent construído", component.isSuccess,
        component.exceptionOrNull()?.toString().orEmpty())
    component.getOrNull()?.release()

    factory.release()

    println(if (failures == 0) "\nTUDO OK" else "\n$failures FALHA(S)")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** O que uma tentativa de reprodução conseguiu observar. */
private class Attempt(
    val reachedPlaying: Boolean,
    val timeMs: Long,
    val dimension: Dimension?,
    val audioTracks: Int,
)

/**
 * Abre o stream, espera o estado 'playing' e depois espera o relógio sair de
 * zero. Devolve o que observou, e sempre solta o player — a tentativa seguinte
 * precisa começar limpa.
 */
private fun playOnce(factory: MediaPlayerFactory): Attempt {
    val player = factory.mediaPlayers().newMediaPlayer()
    try {
        val playing = CountDownLatch(1)
        var errored = false
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = playing.countDown()
            override fun error(mediaPlayer: MediaPlayer) {
                errored = true
                playing.countDown()
            }
        })

        // Exatamente as opções que o app usa em produção.
        player.media().play(PUBLIC_HLS, *VlcEngine.mediaOptions("VLC/3.0.20 LibVLC/3.0.20"))

        if (!playing.await(30, TimeUnit.SECONDS) || errored) return Attempt(false, 0, null, 0)

        // Espera o primeiro frame em vez de dormir um tempo fixo: sai assim que
        // o relógio anda, e só desiste depois de 20s.
        val limit = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < limit && player.status().time() <= 0) {
            Thread.sleep(200)
        }
        return Attempt(
            reachedPlaying = true,
            timeMs = player.status().time(),
            dimension = player.video().videoDimension(),
            audioTracks = player.audio().trackCount(),
        )
    } finally {
        player.controls().stop()
        player.release()
    }
}
