package br.com.iordan.ranga.pc

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.onPreviewKeyEvent
import br.com.iordan.ranga.pc.input.Gamepad
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.Channel
import br.com.iordan.ranga.data.FreeChannel
import br.com.iordan.ranga.data.Movie
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.data.SeriesItem
import br.com.iordan.ranga.data.toPlayable
import br.com.iordan.ranga.pc.ui.BrowseView
import br.com.iordan.ranga.pc.ui.LoginView
import br.com.iordan.ranga.pc.ui.MovieDetailView
import br.com.iordan.ranga.pc.ui.PlayerView
import br.com.iordan.ranga.pc.ui.RangaTheme
import br.com.iordan.ranga.pc.ui.SeriesDetailView
import br.com.iordan.ranga.pc.ui.SettingsView
import androidx.compose.runtime.collectAsState

/**
 * Navegação por pilha, à mão.
 *
 * São cinco destinos e uma profundidade de dois. Uma biblioteca de navegação
 * aqui traria roteamento por string, argumentos tipados e restauração de
 * processo — três coisas que um app de desktop com uma janela só não usa.
 */
private sealed interface Screen {
    data object Browse : Screen
    data object Login : Screen
    data object Settings : Screen
    data class MovieDetail(val streamId: Int) : Screen
    data class SeriesDetail(val seriesId: Int) : Screen
    data class Play(val queue: List<Playable>, val index: Int) : Screen
}

/**
 * Como o app foi aberto. Pelo Ranga EMU (o bloco Ranga TV) ele vem com
 * `--ranga-emu`, e no modo TV de lá também com `--tela-cheia --escala=1.35`:
 *
 * - `--ranga-emu`: B (Esc) na tela inicial fecha o app e devolve ao EMU, em vez
 *   de não fazer nada — quem está no sofá não tem outro jeito de sair;
 * - `--tela-cheia`: já abre em tela cheia;
 * - `--escala=N`: tudo N vezes maior, para ler de longe (1 a 2).
 */
private class LaunchOptions(args: Array<String>) {
    val fromEmu = "--ranga-emu" in args
    val fullscreen = "--tela-cheia" in args
    /**
     * `--monitor=x,y,largura,altura`: o monitor do Ranga EMU, em pixels do
     * Windows. Sem isto o Windows abre a janela no monitor principal, que pode
     * não ser a TV onde o EMU está.
     */
    val monitor: IntArray? = args.firstOrNull { it.startsWith("--monitor=") }
        ?.substringAfter('=')?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }?.toIntArray()
    val scale = args.firstOrNull { it.startsWith("--escala=") }
        ?.substringAfter('=')?.replace(',', '.')?.toFloatOrNull()?.coerceIn(1f, 2f) ?: 1f
}

fun main(args: Array<String>) {
    val options = LaunchOptions(args)
    Gamepad.start()
    application {
        val windowState = rememberWindowState(
            width = 1360.dp,
            height = 820.dp,
            placement = if (options.fullscreen && options.monitor == null) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        )
        val app = remember { AppState() }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Ranga TV",
        ) {
            // Aberto pelo Ranga EMU: vem para a frente, senão o controle não chega.
            if (options.fromEmu) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    options.monitor?.let { Gamepad.moveToMonitor(window, it) }
                    if (options.fullscreen && options.monitor != null) windowState.placement = WindowPlacement.Fullscreen
                    Gamepad.bringToFront(window)
                }
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density * options.scale, density.fontScale),
            ) {
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                RangaTheme {
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = androidx.compose.ui.Modifier.onPreviewKeyEvent { Gamepad.moveFocus(it, focusManager) },
                    ) {
                        RangaApp(
                            app = app,
                            isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                            onToggleFullscreen = {
                                windowState.placement =
                                    if (windowState.placement == WindowPlacement.Fullscreen) {
                                        WindowPlacement.Floating
                                    } else WindowPlacement.Fullscreen
                            },
                            onLeave = if (options.fromEmu) ::exitApplication else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RangaApp(
    app: AppState,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onLeave: (() -> Unit)?,
) {
    val settings by app.settings.state.collectAsState()
    val stack = remember { mutableStateListOf<Screen>(Screen.Browse) }

    fun push(screen: Screen) = stack.add(screen)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun resetToBrowse() {
        stack.clear()
        stack.add(Screen.Browse)
    }

    // Login é rota, não estado (já foi estado, e o comentário do app Android
    // conta a história): a aba Grátis funciona sem conta, então a tela
    // principal precisa existir antes de qualquer login. Quem reage a entrar
    // e sair é o BrowseModel, observando a credencial no Settings.

    /** Abre um item da lista: canal vai direto ao player, VOD vai ao detalhe. */
    fun openItems(items: List<CatalogItem>, index: Int) {
        when (val item = items.getOrNull(index)) {
            is Movie -> push(Screen.MovieDetail(item.streamId))
            is SeriesItem -> push(Screen.SeriesDetail(item.seriesId))
            is Channel -> {
                val creds = settings.credentials
                val format = settings.liveFormat
                // Emparelha canal+playable antes de descartar nulos, senão um
                // canal com URL inválida desalinha os índices e abre o errado.
                val pairs = items.filterIsInstance<Channel>()
                    .mapNotNull { ch -> ch.toPlayable(creds, format)?.let { ch to it } }
                val start = pairs.indexOfFirst { it.first.streamId == item.streamId }
                if (pairs.isNotEmpty()) {
                    push(Screen.Play(pairs.map { it.second }, start.coerceAtLeast(0)))
                }
            }

            null -> Unit
        }
    }

    fun openResume(entry: ResumeEntry) {
        entry.toPlayable(settings.credentials)?.let { push(Screen.Play(listOf(it), 0)) }
    }

    /** Canal aberto: a URL já é a final, não depende de credencial nem de formato. */
    fun openFree(channels: List<FreeChannel>, index: Int) {
        push(Screen.Play(channels.map { it.toPlayable() }, index))
    }

    /*
     * Esc volta uma tela e F11 alterna a tela cheia, em todas as telas menos
     * no player, que tem os atalhos dele (PlayerView). É por aqui que o B e o
     * Start do controle chegam (input/Gamepad.kt). KeyEventDispatcher, e não
     * Modifier: ele recebe a tecla mesmo quando nada na tela tem o foco.
     */
    val onTop by rememberUpdatedState(stack.last())
    val leave by rememberUpdatedState(onLeave)
    val toggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    DisposableEffect(Unit) {
        val keys = KeyEventDispatcher { event ->
            if (event.id != AwtKeyEvent.KEY_PRESSED || onTop is Screen.Play) return@KeyEventDispatcher false
            when (event.keyCode) {
                AwtKeyEvent.VK_ESCAPE -> {
                    if (stack.size > 1) pop() else leave?.invoke()
                    true
                }
                AwtKeyEvent.VK_F11 -> { toggleFullscreen(); true }
                else -> false
            }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
        onDispose { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys) }
    }

    androidx.compose.runtime.LaunchedEffect(stack.last()) {
        Gamepad.testNote("tela: ${stack.last()::class.simpleName}")
    }

    when (val screen = stack.last()) {
        Screen.Browse -> BrowseView(
            app = app,
            onOpenItems = ::openItems,
            onOpenFree = ::openFree,
            onResume = ::openResume,
            onLogin = { push(Screen.Login) },
            onOpenSettings = { push(Screen.Settings) },
        )

        // Entrou ou desistiu: nos dois casos volta para onde estava.
        Screen.Login -> LoginView(app = app, onLoggedIn = ::pop, onBack = ::pop)

        Screen.Settings -> SettingsView(
            app = app,
            onBack = ::pop,
            onLogin = { push(Screen.Login) },
            // Sair da conta volta para a tela principal, que já trocou para a
            // aba Grátis ao ver a credencial sumir.
            onLoggedOut = ::resetToBrowse,
        )

        is Screen.MovieDetail -> MovieDetailView(
            app = app,
            streamId = screen.streamId,
            onBack = ::pop,
            onPlay = { push(Screen.Play(listOf(it), 0)) },
        )

        is Screen.SeriesDetail -> SeriesDetailView(
            app = app,
            seriesId = screen.seriesId,
            onBack = ::pop,
            onPlay = { queue, index -> push(Screen.Play(queue, index)) },
        )

        is Screen.Play -> PlayerView(
            app = app,
            queue = screen.queue,
            startIndex = screen.index,
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onExit = {
                if (isFullscreen) onToggleFullscreen()
                pop()
            },
        )
    }
}
