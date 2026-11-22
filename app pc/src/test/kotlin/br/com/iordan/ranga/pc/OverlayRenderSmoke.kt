package br.com.iordan.ranga.pc

import androidx.compose.ui.graphics.toPixelMap
import br.com.iordan.ranga.pc.player.VideoFrames
import br.com.iordan.ranga.pc.player.VlcEngine
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Mede o custo do modo sobreposto. Rode com `./gradlew overlaySmoke`.
 *
 * O modo sobreposto tem uma conta a pagar: em vez de a libvlc escrever direto
 * na placa de vídeo, cada quadro atravessa a memória principal para o Compose
 * poder desenhar coisas por cima. A pergunta que decide se ele serve de padrão
 * é simples — **1080p passa sem perder quadro?** — e ela não se responde no
 * olho, se responde contando.
 *
 * O teste roda sem abrir janela nenhuma: a superfície de callback não precisa
 * de tela, que é justamente o que a torna mensurável aqui.
 *
 * Duas contagens, e a diferença entre elas é o que importa:
 *
 *  - **entregues**: quadros que a libvlc decodificou e depositou;
 *  - **convertidos**: quadros que a UI puxou e transformou em imagem.
 *
 * Convertidos menor que entregues é normal e desejado — é o descarte de quadro
 * repetido quando o vídeo é mais lento que a tela. Entregues muito abaixo do
 * fps do stream é o sinal ruim: aí a decodificação não está dando conta.
 *
 * Ele também confere o **conteúdo** de um quadro, pixel a pixel. O erro
 * clássico deste caminho não dá exceção nem derruba a contagem: com o formato
 * de cor errado tudo "funciona" e a tela fica preta.
 */
private const val DEFAULT_HLS =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

/**
 * `-Dranga.stream.url=...` troca o stream medido. Existe porque o padrão é
 * H.264, e o codec do canal muda tudo: HEVC em FHD custa muito mais para
 * decodificar, e foi num canal desses que o app apareceu travando.
 */
private val PUBLIC_HLS: String
    get() = System.getProperty("ranga.stream.url")?.takeIf { it.isNotBlank() } ?: DEFAULT_HLS

/** Quanto tempo medir depois que a reprodução estabiliza. */
private const val MEASURE_SECONDS = 15

/** Piso do aceitável: abaixo disso a decodificação não acompanha. */
private const val MINIMUM_FPS = 20.0

fun main() {
    println("1) Descoberta da libvlc")
    if (!VlcEngine.isAvailable) {
        println("Nenhuma libvlc encontrada. Rode `./gradlew vlcRuntime`.")
        exitProcess(1)
    }
    println("  motor: ${VlcEngine.source} — ${VlcEngine.loadedFrom}")

    // Até três tentativas, pelo mesmo motivo do vlcSmoke: o stream vem de CDN
    // pública, e um handshake TLS que estoura o tempo produz exatamente o mesmo
    // sintoma de um plugin faltando — zero quadro. Só que não se repete.
    var result = measure()
    var tries = 1
    while (result == null && tries < 3) {
        println("  tentativa $tries não rendeu quadro. Repetindo — costuma ser rede.")
        tries++
        result = measure()
    }

    if (result == null) {
        println("\nFALHA: nenhum quadro em $tries tentativas.")
        exitProcess(1)
    }

    println(
        "  entregues pela libvlc: %d  (%.1f/s)"
            .format(result.delivered, result.deliveredPerSecond),
    )
    println(
        "  convertidos para a tela: %d  (%.1f/s)"
            .format(result.converted, result.convertedPerSecond),
    )

    val ok = result.deliveredPerSecond >= MINIMUM_FPS && result.litSamples > 0
    println(
        when {
            ok -> "\nTUDO OK"
            result.litSamples == 0 -> "\nFALHA: quadro todo preto — formato de cor errado."
            else -> "\nFALHA: menos de $MINIMUM_FPS quadros por segundo."
        },
    )
    exitProcess(if (ok) 0 else 1)
}

private class Measurement(
    val delivered: Long,
    val converted: Int,
    val seconds: Double,
    val litSamples: Int,
) {
    val deliveredPerSecond get() = delivered / seconds
    val convertedPerSecond get() = converted / seconds
}

/** Uma medição completa. Devolve `null` quando nem chegou a render quadro. */
private fun measure(): Measurement? {
    val frames = VideoFrames()

    // `-Dranga.vlc.args=...` acrescenta argumentos à libvlc, separados por
    // espaço. Serve para comparar caminhos de decodificação sem recompilar.
    val extraArgs = System.getProperty("ranga.vlc.args").orEmpty()
        .split(" ").filter { it.isNotBlank() }

    // Os mesmos argumentos do app no modo sobreposto — hoje nenhum. Medir com
    // outros mediria outro programa. Ver VlcEngine.OVERLAY_ARGS para o que já
    // esteve aqui e por que saiu.
    val args = listOf("--aout=dummy") + VlcEngine.OVERLAY_ARGS + extraArgs
    val factory = MediaPlayerFactory(*args.toTypedArray())
    val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
    player.videoSurface().set(factory.videoSurfaces().newVideoSurface(frames, frames, true))

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

        println("\n2) Abrindo o stream na maior resolução disponível")
        // `adaptive-logic=highest` força a variante de maior banda do HLS. Sem
        // isso o VLC escolhe pela banda medida e cai numa variante pequena —
        // medir 480x270 não diria nada sobre 1080p, que é o caso que preocupa.
        player.media().play(
            PUBLIC_HLS,
            *VlcEngine.mediaOptions("VLC/3.0.20 LibVLC/3.0.20"),
            ":adaptive-logic=highest",
        )

        if (!playing.await(30, TimeUnit.SECONDS) || errored) return null

        // Alguns segundos para o buffer encher e a variante estabilizar. Medir
        // a partida mediria a partida, não a reprodução.
        Thread.sleep(4_000)

        val dimension = player.video().videoDimension()
        println("  resolução: ${dimension?.width}x${dimension?.height}")

        // O mesmo que o PlayerController faz assim que a mídia abre: contar ao
        // VideoFrames qual pedaço do buffer é imagem.
        dimension?.let { frames.setVisibleSize(it.width, it.height) }

        println("\n3) Medindo por ${MEASURE_SECONDS}s")
        var converted = 0
        var lit = 0
        var described = false
        val deliveredAtStart = frames.delivered
        val startedAt = System.currentTimeMillis()
        val until = startedAt + MEASURE_SECONDS * 1_000L

        // Imita o laço da UI: uma tentativa de leitura por quadro de tela, num
        // monitor de 60 Hz.
        while (System.currentTimeMillis() < until) {
            val frame = frames.poll()
            if (frame != null) {
                converted++
                if (!described) {
                    described = true
                    lit = countLitSamples(frame)
                    println(
                        "  buffer ${frame.image.width}x${frame.image.height}, " +
                            "imagem ${frame.visibleWidth}x${frame.visibleHeight}, " +
                            "$lit amostras acesas",
                    )
                }
            }
            Thread.sleep(16)
        }

        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
        val delivered = frames.delivered - deliveredAtStart
        if (delivered == 0L) return null

        return Measurement(delivered, converted, seconds, lit)
    } finally {
        player.controls().stop()
        player.release()
        factory.release()
    }
}

/** Amostra o quadro numa grade esparsa e conta pixels que não são pretos. */
private fun countLitSamples(frame: VideoFrames.Frame): Int {
    val pixels = frame.image.toPixelMap()
    return (0 until frame.visibleWidth.coerceAtMost(pixels.width) step 37).sumOf { x ->
        (0 until frame.visibleHeight.coerceAtMost(pixels.height) step 37).count { y ->
            val color = pixels[x, y]
            color.alpha > 0.5f && (color.red + color.green + color.blue) > 0.05f
        }
    }
}
