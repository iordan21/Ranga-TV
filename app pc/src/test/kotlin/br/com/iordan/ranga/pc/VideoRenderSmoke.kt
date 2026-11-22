package br.com.iordan.ranga.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import br.com.iordan.ranga.pc.player.VlcEngine
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.Rectangle
import java.awt.Robot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Prova que o vídeo aparece de verdade na tela.
 *
 * Monta exatamente o mesmo arranjo do app — janela Compose, SwingPanel,
 * EmbeddedMediaPlayerComponent — toca um stream público, tira um print da
 * janela e conta quantos pixels não são pretos.
 *
 * Existe por causa de um bug que eu mesma criei: liguei
 * `compose.interop.blending` e a tela ficou preta com o som saindo. Nenhum
 * teste pegava isso, porque compilava, empacotava e abria — só não mostrava
 * imagem. Este roda de dois jeitos:
 *
 *   ./gradlew videoSmoke              -> como o app está hoje (deve PASSAR)
 *   ./gradlew videoSmoke -Pblending   -> com o blending ligado (deve FALHAR)
 *
 * O segundo é o que transforma "acho que era isso" em "é isso".
 */
private const val DEFAULT_HLS =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

/** `-Dranga.stream.url=...` troca o stream. Ver o mesmo em OverlayRenderSmoke. */
private val PUBLIC_HLS: String
    get() = System.getProperty("ranga.stream.url")?.takeIf { it.isNotBlank() } ?: DEFAULT_HLS

private const val WINDOW_TITLE = "RangaVideoSmoke"

fun main() {
    val blending = System.getProperty("compose.interop.blending") ?: "(desligado)"
    println("compose.interop.blending = $blending")

    // Pelo VlcEngine, e não pela NativeDiscovery direto: é ele quem aponta o
    // vlcj para a libvlc embutida antes da primeira busca. Chamar a descoberta
    // por fora acharia o VLC do sistema e o resultado do teste não diria nada
    // sobre o que o app instalado vai carregar.
    if (!VlcEngine.isAvailable) {
        println("Nenhuma libvlc encontrada. Rode `./gradlew vlcRuntime`.")
        exitProcess(1)
    }
    println("motor: ${VlcEngine.source} — ${VlcEngine.loadedFrom}")

    val component = EmbeddedMediaPlayerComponent()
    val playing = CountDownLatch(1)
    component.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) = playing.countDown()
    })

    thread(isDaemon = true) {
        application {
            Window(
                onCloseRequest = {},
                state = rememberWindowState(width = 640.dp, height = 400.dp),
                title = WINDOW_TITLE,
                alwaysOnTop = true, // para o print não pegar outra janela por cima
            ) {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SwingPanel(
                            background = Color.Black,
                            factory = { component },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    Thread.sleep(4_000) // janela abrir e assentar
    component.mediaPlayer().media().play(PUBLIC_HLS, *VlcEngine.mediaOptions("VLC/3.0.20 LibVLC/3.0.20"))

    if (!playing.await(30, TimeUnit.SECONDS)) {
        println("FALHA: não chegou a tocar.")
        exitProcess(1)
    }
    Thread.sleep(6_000) // frames de verdade na tela

    val window = java.awt.Window.getWindows().firstOrNull {
        (it as? java.awt.Frame)?.title == WINDOW_TITLE && it.isShowing
    }
    if (window == null) {
        println("FALHA: janela não encontrada para capturar.")
        exitProcess(1)
    }

    // Recorta o miolo da área de vídeo, longe de bordas e barra de título.
    val bounds = window.bounds
    val area = Rectangle(
        bounds.x + bounds.width / 4,
        bounds.y + bounds.height / 3,
        bounds.width / 2,
        bounds.height / 3,
    )
    val shot = Robot().createScreenCapture(area)

    var lit = 0
    var total = 0
    for (x in 0 until shot.width step 2) {
        for (y in 0 until shot.height step 2) {
            val rgb = shot.getRGB(x, y)
            val sum = ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)
            total++
            if (sum > 45) lit++ // 45/765: qualquer coisa acima de preto puro
        }
    }
    val ratio = lit.toDouble() / total

    println("pixels não-pretos no centro: %.1f%%".format(ratio * 100))
    component.release()

    // O stream de teste da Apple é colorido; abaixo de 10% é tela preta.
    val ok = ratio > 0.10
    println(if (ok) "OK — o vídeo está sendo desenhado." else "FALHA — tela preta.")
    exitProcess(if (ok) 0 else 1)
}
