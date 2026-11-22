package br.com.iordan.ranga.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.ui.common.ScreenCast
import br.com.iordan.ranga.ui.theme.RangaTheme

/**
 * O player mora numa Activity separada, não numa rota do Compose Navigation.
 *
 * Motivo: fullscreen, orientação, Picture-in-Picture e "manter tela ligada" são
 * propriedades de *janela*. Numa rota do Nav eu teria que ligar e desligar tudo
 * isso na mão a cada entrada/saída — e a hora que dá errado é quando o usuário
 * volta com o botão do controle. Como Activity, o Android faz isso sozinho.
 */
class PlayerActivity : ComponentActivity() {

    /**
     * A tela de player registra aqui seu tratador de teclas.
     *
     * Por que não `Modifier.onKeyEvent` do Compose? Porque o vídeo é um
     * `PlayerView` (View clássica) dentro de um `AndroidView`. Quando ele tem o
     * foco, as teclas do controle vão direto para a hierarquia de Views e nunca
     * sobem para os modifiers do Compose. `dispatchKeyEvent` da Activity é o
     * único ponto por onde *toda* tecla passa, sem exceção.
     */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sem isso a tela apaga no meio do filme por inatividade de toque.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        updateOrientation()

        setContent {
            RangaTheme {
                PlayerScreen(
                    startIndex = startIndex,
                    onExit = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    /**
     * Espelhando para a TV, o vídeo deita sozinho. O espelhamento copia a tela
     * do celular como ela está: com o aparelho em pé, a TV mostrava uma faixa
     * vertical no meio e duas bordas pretas enormes. Sem espelhamento, quem
     * manda é o sensor, como antes.
     */
    private fun updateOrientation() {
        requestedOrientation =
            if (ScreenCast.isMirroring(this)) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** O espelhamento pode começar ou cair com o vídeo já tocando. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateOrientation()
        override fun onDisplayRemoved(displayId: Int) = updateOrientation()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyHandler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_INDEX = "start_index"

        /**
         * Único jeito certo de abrir o player: entrega a fila inteira ao
         * singleton e passa só o índice pelo Intent. Ver [PlaybackQueue] para
         * o porquê de a lista não ir no Intent.
         */
        fun launch(context: Context, queue: PlaybackQueue, items: List<Playable>, index: Int) {
            if (items.isEmpty()) return
            queue.submit(items)
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_INDEX, index.coerceIn(0, items.lastIndex))
            )
        }
    }
}
