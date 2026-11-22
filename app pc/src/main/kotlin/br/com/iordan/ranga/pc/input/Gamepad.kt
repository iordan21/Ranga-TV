package br.com.iordan.ranga.pc.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.io.File

/**
 * O controle (DualSense, Xbox e afins) virando teclado.
 *
 * O app inteiro já anda pelo teclado: setas movem o foco do Compose, Enter
 * aperta o que está em foco, Esc volta, e o player tem os atalhos dele num
 * KeyEventDispatcher (PlayerView). Então o controle não ganha um segundo
 * caminho de navegação: cada botão vira a tecla que já faz aquilo, entregue
 * ao componente que tem o foco. Um caminho só para os dois é o mesmo
 * raciocínio do dispatcher do player.
 *
 * | Controle            | Tecla      | O que faz                              |
 * |---------------------|------------|----------------------------------------|
 * | direcional / analógico | setas   | move o foco; no player, canal e busca  |
 * | A / Cruz            | Enter      | abre; no player, pausa                 |
 * | B / Círculo         | Esc        | volta; na tela inicial vinda do EMU, fecha |
 * | Start / Options     | F11        | tela cheia                             |
 * | LB / RB             | PgUp/PgDn  | aba anterior / seguinte; no player, canal |
 *
 * A leitura vem da `ranga_pad.dll` (native/ranga_pad.c), que é o SDL 2 do
 * Ranga EMU. Sem a dll o app funciona igual, só sem controle.
 *
 * Só manda tecla quando uma janela do Ranga tem o foco: com ele minimizado ou
 * atrás de outra janela, o controle é dos outros programas.
 */
object Gamepad {

    /** Há algum controle ligado agora (a interface mostra as dicas de botão). */
    var connected by mutableStateOf(false)
        private set

    private const val UP = 1 shl 0
    private const val DOWN = 1 shl 1
    private const val LEFT = 1 shl 2
    private const val RIGHT = 1 shl 3
    private const val A = 1 shl 4
    private const val B = 1 shl 5
    private const val START = 1 shl 8
    private const val LB = 1 shl 10
    private const val RB = 1 shl 11
    private const val CONNECTED = 1 shl 30

    /** Botão do controle → tecla. Os do direcional repetem enquanto seguros. */
    private val keys = listOf(
        UP to KeyEvent.VK_UP,
        DOWN to KeyEvent.VK_DOWN,
        LEFT to KeyEvent.VK_LEFT,
        RIGHT to KeyEvent.VK_RIGHT,
        A to KeyEvent.VK_ENTER,
        B to KeyEvent.VK_ESCAPE,
        START to KeyEvent.VK_F11,
        LB to KeyEvent.VK_PAGE_UP,
        RB to KeyEvent.VK_PAGE_DOWN,
    )
    private const val REPEATS = UP or DOWN or LEFT or RIGHT

    private const val FIRST_REPEAT_MS = 380L
    private const val REPEAT_MS = 110L

    @Volatile private var running = false

    /**
     * Só no teste (`RANGA_TV_TESTE_LOG=<arquivo>`): cada botão, cada tela e cada
     * item que ganha o foco, em texto. Sem foto de tela de propósito: a janela de
     * teste abre atrás das outras, e uma foto pegaria o que estiver por cima.
     */
    private val testLog: File? = System.getenv("RANGA_TV_TESTE_LOG")?.let(::File)

    /** Uma linha no log de teste (nada sem ele). */
    fun testNote(text: String) {
        testLog?.let { log -> runCatching { log.appendText(text + System.lineSeparator()) } }
    }

    /** Começa a ler o controle numa thread própria. Chamar uma vez, no início. */
    fun start() {
        System.getenv("RANGA_TV_TESTE_CONTROLE")?.let(::testScript)
        // Medição: sem ler o controle, para separar o custo dele do resto.
        if (System.getenv("RANGA_TV_SEM_CONTROLE") != null) return
        if (running) return
        val poll = load() ?: return
        running = true
        Thread({ loop(poll) }, "ranga-controle").apply {
            isDaemon = true
            start()
        }
    }

    private fun load(): Function? = candidates().firstOrNull { it.isFile }?.let { dll ->
        runCatching { NativeLibrary.getInstance(dll.absolutePath).getFunction("ranga_pad_poll") }.getOrNull()
    }

    /** Mesma ordem do VlcEngine: a pasta de recursos do pacote, depois a do projeto (`gradlew run`). */
    private fun candidates(): List<File> = listOfNotNull(
        System.getProperty("compose.application.resources.dir")?.let { File(it, "ranga_pad.dll") },
        File("resources${File.separator}windows-x64${File.separator}ranga_pad.dll"),
    )

    private fun loop(poll: Function) {
        var held = 0
        val nextRepeat = LongArray(32)
        while (running) {
            val bits = runCatching { poll.invokeInt(emptyArray()) }.getOrDefault(-1)
            if (bits == -1) break
            val isConnected = bits and CONNECTED != 0
            if (isConnected != connected) EventQueue.invokeLater { connected = isConnected }

            val now = System.currentTimeMillis()
            for ((button, key) in keys) {
                val down = bits and button != 0
                val wasDown = held and button != 0
                val slot = Integer.numberOfTrailingZeros(button)
                when {
                    down && !wasDown -> {
                        press(key)
                        nextRepeat[slot] = now + FIRST_REPEAT_MS
                    }
                    down && button and REPEATS != 0 && now >= nextRepeat[slot] -> {
                        press(key)
                        nextRepeat[slot] = now + REPEAT_MS
                    }
                }
            }
            held = bits
            Thread.sleep(16)
        }
    }

    /**
     * Traz a janela para a frente. O Windows não deixa um programa aberto em
     * segundo plano tomar o foco (ele só pisca na barra de tarefas), e sem foco
     * o controle não manda nada. Aberto pelo Ranga EMU isso já vem liberado (o
     * EMU chama AllowSetForegroundWindow); este é o reforço: a thread se junta
     * por um instante à da janela que está na frente, o que o Windows aceita.
     */
    fun bringToFront(window: Window) {
        runCatching {
            val user32 = User32.INSTANCE
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val front = user32.GetForegroundWindow()
            val frontThread = user32.GetWindowThreadProcessId(front, null)
            val me = Kernel32.INSTANCE.GetCurrentThreadId()
            val attached = frontThread != me && user32.AttachThreadInput(WinDef.DWORD(me.toLong()), WinDef.DWORD(frontThread.toLong()), true)
            user32.SetForegroundWindow(hwnd)
            user32.BringWindowToTop(hwnd)
            if (attached) user32.AttachThreadInput(WinDef.DWORD(me.toLong()), WinDef.DWORD(frontThread.toLong()), false)
        }
        window.toFront()
        window.requestFocus()
    }

    /**
     * Põe a janela no meio do monitor dado (x, y, largura, altura em pixels do
     * Windows, como o Ranga EMU manda). Pelo Win32 e não pelo AWT: os números
     * vêm em pixels físicos, e o AWT mede em pixels já escalados pelo zoom de
     * cada monitor, que podem ser diferentes entre a TV e o monitor.
     */
    fun moveToMonitor(window: Window, monitor: IntArray) {
        runCatching {
            val user32 = User32.INSTANCE
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val rect = WinDef.RECT()
            user32.GetWindowRect(hwnd, rect)
            val width = minOf(rect.right - rect.left, monitor[2])
            val height = minOf(rect.bottom - rect.top, monitor[3])
            val x = monitor[0] + (monitor[2] - width) / 2
            val y = monitor[1] + (monitor[3] - height) / 2
            user32.SetWindowPos(hwnd, null, x, y, width, height, 0x0004 /* SWP_NOZORDER */)
        }
    }

    /**
     * O passo "foto" do teste: o que o próprio Skia desenhou na janela do Ranga
     * TV (SkiaLayer.screenshot), ao lado do log como foto-N.png. Nunca a tela:
     * a janela de teste abre atrás das outras, e uma foto da tela pegaria o que
     * estiver por cima.
     */
    private fun photo(number: Int) {
        val log = testLog ?: return
        val window = Window.getWindows().firstOrNull { it.isShowing } ?: return
        fun layer(c: java.awt.Component): org.jetbrains.skiko.SkiaLayer? {
            if (c is org.jetbrains.skiko.SkiaLayer) return c
            if (c is java.awt.Container) for (child in c.components) layer(child)?.let { return it }
            return null
        }
        val shot = runCatching { layer(window)?.screenshot() }.getOrNull() ?: return testNote("foto $number: sem imagem")
        val png = org.jetbrains.skia.Image.makeFromBitmap(shot).use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) }
        png?.bytes?.let { File(log.parentFile, "foto-$number.png").writeBytes(it) }
        testNote("foto $number: ${shot.width}x${shot.height}")
        shot.close()
    }

    /**
     * Teste sem controle na mão: `RANGA_TV_TESTE_CONTROLE="cima,baixo,a,b,start,lb,rb,espera,foto"`
     * aperta esses botões, um a cada 0,8 s depois de 5 s, pelo mesmo caminho
     * dos de verdade, e finge um controle ligado (o foco inicial aparece).
     */
    private fun testScript(script: String) {
        val byName = mapOf(
            "cima" to KeyEvent.VK_UP, "baixo" to KeyEvent.VK_DOWN, "esquerda" to KeyEvent.VK_LEFT,
            "direita" to KeyEvent.VK_RIGHT, "a" to KeyEvent.VK_ENTER, "b" to KeyEvent.VK_ESCAPE,
            "start" to KeyEvent.VK_F11, "lb" to KeyEvent.VK_PAGE_UP, "rb" to KeyEvent.VK_PAGE_DOWN, "tab" to KeyEvent.VK_TAB,
        )
        Thread({
            Thread.sleep(4000)
            EventQueue.invokeAndWait { Window.getWindows().firstOrNull { it.isShowing }?.let(::bringToFront) }
            Thread.sleep(1000)
            EventQueue.invokeLater { connected = true }
            var photos = 0
            for (name in script.split(',').map { it.trim().lowercase() }) {
                Thread.sleep(900)
                testNote("botão $name")
                byName[name]?.let(::press)
                if (name == "foto") EventQueue.invokeAndWait { photo(++photos) }
            }
        }, "ranga-controle-teste").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * As setas do controle andam entre os itens. No PC o Compose só anda com
     * Tab; seta é do cursor dos campos de texto, e continua sendo para quem usa
     * o teclado. Então o controle conta as setas que ele mesmo mandou ([press])
     * e só essas movem o foco aqui. (Marcar a tecla não dá: o Compose descarta
     * seta do teclado numérico e esconde o evento original.) Vai no
     * onPreviewKeyEvent da raiz, antes de qualquer campo de texto comer a seta.
     */
    fun moveFocus(event: androidx.compose.ui.input.key.KeyEvent, focus: androidx.compose.ui.focus.FocusManager): Boolean {
        val arrow = when (event.key) {
            androidx.compose.ui.input.key.Key.DirectionUp -> 0
            androidx.compose.ui.input.key.Key.DirectionDown -> 1
            androidx.compose.ui.input.key.Key.DirectionLeft -> 2
            androidx.compose.ui.input.key.Key.DirectionRight -> 3
            else -> return false
        }
        val down = event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
        val waiting = if (down) padDowns else padUps
        if (waiting[arrow] == 0) return false
        waiting[arrow]--
        // A seta solta não move de novo; o direcional segurado repete pelo press.
        if (down) focus.moveFocus(directions[arrow])
        return true
    }

    private val arrows = listOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT)
    private val directions = listOf(
        androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down,
        androidx.compose.ui.focus.FocusDirection.Left, androidx.compose.ui.focus.FocusDirection.Right,
    )

    /** Setas do controle a caminho, por direção (só mexidas na thread da janela). */
    private val padDowns = IntArray(4)
    private val padUps = IntArray(4)

    private fun count(keyCode: Int) {
        val arrow = arrows.indexOf(keyCode)
        if (arrow < 0) return
        // Nunca acumula: se uma se perdeu (janela sem foco), não vira dívida.
        padDowns[arrow] = 1
        padUps[arrow] = 1
    }

    /** Aperta e solta a tecla no componente com o foco, como se viesse do teclado. */
    private fun press(keyCode: Int) {
        EventQueue.invokeLater {
            val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (target == null) {
                // Só no teste: a janela aberta em segundo plano nunca fica ativa
                // (o Windows não deixa), então a tecla vai direto ao componente
                // que teria o foco, passando pelos mesmos KeyEventDispatchers.
                if (testLog == null) return@invokeLater
                val window = Window.getWindows().firstOrNull { it.isShowing } ?: return@invokeLater
                // O componente onde o Compose escuta o teclado (o primeiro com KeyListener lá dentro).
                fun listening(c: java.awt.Component): java.awt.Component? {
                    if (c.keyListeners.isNotEmpty() && c !is Window) return c
                    if (c is java.awt.Container) for (child in c.components) listening(child)?.let { return it }
                    return null
                }
                val inside = listening(window) ?: window
                val time = System.currentTimeMillis()
                count(keyCode)
                inside.dispatchEvent(KeyEvent(inside, KeyEvent.KEY_PRESSED, time, 0, keyCode, KeyEvent.CHAR_UNDEFINED))
                inside.dispatchEvent(KeyEvent(inside, KeyEvent.KEY_RELEASED, time, 0, keyCode, KeyEvent.CHAR_UNDEFINED))
                return@invokeLater
            }
            val queue = target.toolkit.systemEventQueue
            val time = System.currentTimeMillis()
            count(keyCode)
            queue.postEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, time, 0, keyCode, KeyEvent.CHAR_UNDEFINED))
            queue.postEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, time, 0, keyCode, KeyEvent.CHAR_UNDEFINED))
        }
    }
}
