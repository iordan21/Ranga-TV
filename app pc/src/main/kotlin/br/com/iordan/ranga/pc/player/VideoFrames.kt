package br.com.iordan.ranga.pc.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import com.sun.jna.Pointer
import java.nio.ByteBuffer

/**
 * Ponte entre a thread nativa do VLC e a thread de UI do Compose.
 *
 * No modo sobreposto a libvlc não desenha em lugar nenhum: ela escreve cada
 * quadro num buffer de memória e chama [display]. Quem pinta é o Compose. É o
 * que permite ter botão, legenda e mensagem *em cima* do vídeo — coisa
 * impossível com a superfície acelerada, que é um canvas do sistema onde o
 * Compose não consegue compor nada por cima.
 *
 * ### As duas threads
 *
 * [display] vem de uma thread nativa do VLC, no ritmo do vídeo. A UI redesenha
 * no ritmo do monitor. Ligar as duas com `mutableStateOf` seria corrida com o
 * sistema de snapshots do Compose, o mesmo motivo pelo qual o
 * [PlayerController] publica tudo em `StateFlow`.
 *
 * Aqui a direção é invertida: o VLC só deposita, e a UI *puxa* com [poll] uma
 * vez por quadro de tela. Quando nada mudou, [poll] devolve `null` e a tela
 * mantém a imagem anterior sem custo. Quando o vídeo tem 24 fps e o monitor
 * 144 Hz, isso evita converter cinco vezes o mesmo quadro.
 *
 * ### Dois buffers
 *
 * A thread do VLC escreve no de trás e troca os dois na saída; a UI lê o da
 * frente. Sem isso, um quadro sendo escrito enquanto a tela o lê apareceria
 * rasgado ao meio. A troca é a única parte que precisa do lock, e ela é uma
 * atribuição de referência.
 */
class VideoFrames : BufferFormatCallback, RenderCallback {

    private val lock = Any()

    private var back = ByteArray(0)
    private var front = ByteArray(0)
    private var width = 0
    private var height = 0

    /** Quanto do buffer é imagem de verdade. Zero enquanto o VLC não avisa. */
    private var visibleWidth = 0
    private var visibleHeight = 0

    /** Contador de quadros depositados; a UI compara com o que já mostrou. */
    private var revision = 0L
    private var shown = -1L

    /** Quadros que a libvlc entregou desde que este objeto existe. */
    @Volatile
    var delivered: Long = 0
        private set

    // ------------------------------------------------------ lado do VLC

    /**
     * RV32 é BGRA de 32 bits, exatamente o layout que o Skia chama de
     * `BGRA_8888`. Pedir qualquer outro formato obrigaria a uma conversão de
     * espaço de cor por quadro, na CPU, para nada.
     */
    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
        RV32BufferFormat(sourceWidth, sourceHeight)

    /**
     * Formato novo: o tamanho visível volta a ser o do buffer até alguém
     * corrigir com [setVisibleSize]. Trocar de canal tem de esquecer o tamanho
     * do canal anterior, senão o próximo sai recortado.
     */
    override fun newFormatSize(
        bufferWidth: Int,
        bufferHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ) {
        synchronized(lock) {
            visibleWidth = displayWidth.coerceIn(1, bufferWidth)
            visibleHeight = displayHeight.coerceIn(1, bufferHeight)
        }
    }

    /**
     * Diz qual pedaço do buffer é imagem de verdade.
     *
     * O buffer costuma ser maior. Medido no `overlaySmoke`: um vídeo que a
     * libvlc reporta como 1920x1080 chega num buffer de 1920x1090 — dez linhas
     * de sobra no fim, que não fazem parte do filme. Desenhar o buffer inteiro
     * estica a imagem 1% na vertical e mostra uma tarja de lixo embaixo:
     * pequeno o bastante para ninguém reportar, grande o bastante para
     * incomodar.
     *
     * Quem sabe o tamanho certo é o próprio player, por `videoDimension()`, e
     * quem pergunta é o [PlayerController] — de fora da thread de callback,
     * porque perguntar de dentro dela é o caminho conhecido para travar.
     */
    fun setVisibleSize(imageWidth: Int, imageHeight: Int) = synchronized(lock) {
        if (imageWidth > 0 && imageHeight > 0) {
            visibleWidth = imageWidth
            visibleHeight = imageHeight
        }
    }

    override fun allocatedBuffers(buffers: Array<ByteBuffer>) = Unit

    override fun lock(mediaPlayer: MediaPlayer) = Unit

    override fun unlock(mediaPlayer: MediaPlayer) = Unit

    override fun display(
        mediaPlayer: MediaPlayer,
        nativeBuffers: Array<ByteBuffer>,
        bufferFormat: BufferFormat,
        displayWidth: Int,
        displayHeight: Int,
    ) {
        val w = bufferFormat.width
        val h = bufferFormat.height
        val size = w * h * 4
        val source = nativeBuffers[0]

        // O rewind não é opcional: a libvlc reaproveita o mesmo ByteBuffer a
        // cada quadro, e sem ele a posição de leitura fica onde parou da vez
        // anterior — o segundo quadro viria vazio.
        source.rewind()

        synchronized(lock) {
            if (back.size != size) back = ByteArray(size)
            source.get(back, 0, size)

            val swap = front
            front = back
            back = swap

            width = w
            height = h
            revision++
        }
        delivered++
    }

    // ------------------------------------------------------- lado da UI

    /**
     * Devolve o quadro novo, ou `null` se nenhum chegou desde a última chamada.
     * Só a thread de UI chama isto.
     *
     * A conversão acontece com o lock preso, e é de propósito: ela é uma cópia
     * de memória de alguns MB, na casa do milissegundo, e segurar a thread do
     * VLC por esse tempo custa menos do que um terceiro buffer para evitá-lo.
     */
    fun poll(): Frame? = synchronized(lock) {
        if (revision == shown || width == 0) return null
        shown = revision
        val bitmap = canvasFor(width, height)
        // Os bytes vão direto para a memória do bitmap: nada é alocado por quadro.
        Pointer(bitmap.peekPixels()!!.addr).write(0, front, 0, width * height * 4)
        // Sem isto o Skia continuaria desenhando a textura do quadro anterior,
        // que ele guarda na placa pelo número de geração do bitmap.
        bitmap.notifyPixelsChanged()
        Frame(
            // Um embrulho novo por quadro (é só uma referência), para o Compose
            // perceber que mudou e redesenhar; os pixels são sempre os mesmos.
            image = bitmap.asComposeImageBitmap(),
            visibleWidth = visibleWidth.takeIf { it in 1..width } ?: width,
            visibleHeight = visibleHeight.takeIf { it in 1..height } ?: height,
        )
    }

    /*
     * Um bitmap só, reaproveitado em todo quadro.
     *
     * Antes cada quadro virava uma Image do Skia, e o `toComposeImageBitmap()`
     * ainda fazia mais uma cópia num Bitmap que ninguém fechava: memória nativa
     * que só voltava quando o coletor do Java passava. Medido num canal 720p:
     * a RAM subia uns 110 MB por segundo e caía em serra entre 1,2 e 4 GB.
     * Agora há um bitmap por tamanho de vídeo; trocar de tamanho (outro canal)
     * cria o novo, e o velho é fechado no quadro seguinte, quando a tela já
     * não o usa.
     */
    private var bitmap: Bitmap? = null
    private var retired: Bitmap? = null

    private fun canvasFor(w: Int, h: Int): Bitmap {
        retired?.close()
        retired = null
        bitmap?.let { current ->
            if (current.width == w && current.height == h) return current
            retired = current
        }
        // OPAQUE, e não UNPREMUL: o VLC não usa o canal alfa do RV32 e o deixa
        // zerado. Declarar que ele vale faria o Skia desenhar um retângulo
        // transparente — vídeo tocando e tela preta de novo.
        return Bitmap().apply { allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)) }.also { bitmap = it }
    }

    /** O player fechou: solta o bitmap. */
    fun release() = synchronized(lock) {
        retired?.close()
        bitmap?.close()
        retired = null
        bitmap = null
    }

    /** Um quadro pronto para desenhar, e o pedaço dele que é imagem. */
    data class Frame(
        val image: ImageBitmap,
        val visibleWidth: Int,
        val visibleHeight: Int,
    )
}
