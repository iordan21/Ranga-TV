package br.com.iordan.ranga.pc.player

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.io.File

/**
 * Descoberta da libvlc.
 *
 * O Ranga **embute** o motor de vídeo. A pasta `resources/vlc` do pacote traz
 * a `libvlc.dll`, a `libvlccore.dll` e os plugins usados na reprodução. Não há
 * nada para instalar antes de usar o app.
 *
 * Antes o app dependia do VLC instalado na máquina, para economizar download.
 * Era a troca errada: um player que não toca enquanto você não instala outro
 * player é um catálogo, não um player — e a primeira coisa que o usuário via
 * era uma tarefa. O pacote engordou ~75 MB e o problema deixou de existir.
 *
 * A busca tem um segundo degrau: sem a pasta embutida, cai na [NativeDiscovery]
 * padrão, que acha o VLC instalado no Windows (inclusive pelo Registro). Isso é
 * rede de segurança para rodar pelo Gradle antes de `vlcRuntime`, e socorro se
 * a instalação do usuário perder arquivos — não é o caminho normal.
 *
 * ### Por que `jna.library.path`
 *
 * O vlcj procura a biblioteca numa lista de provedores ordenada por
 * prioridade. `JnaLibraryPathDirectoryProvider` (-1) é consultado antes de
 * `WindowsInstallDirectoryProvider` (-3), então apontar a propriedade para a
 * pasta embutida garante que ela ganhe do VLC da máquina — o que importa
 * quando as duas versões diferem.
 *
 * Quem acha a pasta também define a `VLC_PLUGIN_PATH` para `<pasta>\plugins`.
 * Isso não é detalhe: dll de uma versão com plugins de outra não carrega, e o
 * sintoma é o app abrir normalmente e nenhum vídeo tocar.
 */
object VlcEngine {

    /** De onde saiu a libvlc que está carregada. */
    enum class Source { BUNDLED, SYSTEM, NONE }

    /**
     * `by lazy` e não `val` direto: a busca varre disco e Registro, e quero
     * isso fora do caminho de inicialização da janela.
     */
    val source: Source by lazy { locate() }

    val isAvailable: Boolean get() = source != Source.NONE

    /** Pasta de onde a libvlc foi carregada. Nula enquanto [source] não roda. */
    var loadedFrom: String? = null
        private set

    /**
     * Versão da libvlc embutida, gravada em `version.txt` pela task
     * `vlcRuntime`. Ler do arquivo em vez de repetir a versão numa constante
     * aqui: a única fonte é o build, e não tem como as duas desencontrarem.
     */
    val bundledVersion: String? by lazy {
        bundledDir()?.let { dir ->
            File(dir, "version.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        }
    }

    const val DOWNLOAD_URL = "https://www.videolan.org/vlc/"

    /**
     * Argumentos extras da libvlc no modo sobreposto. Nenhum — e isto aqui é
     * um registro do que **não** fazer.
     *
     * Eu cheguei a fixar `--avcodec-hw=none`, convencido de que a superfície de
     * callback não convivia com decodificação por hardware: os primeiros testes
     * davam zero quadro com `d3d11va` e `dxva2`. Estava errado. Os zeros eram a
     * CDN pública engasgando, que produz exatamente o mesmo sintoma, e as
     * medições eram de execução única — sem repetir, os dois casos são
     * indistinguíveis.
     *
     * Repetido pelo `overlaySmoke` em 1080p, o resultado é empate: `d3d11va`
     * 59,5 quadro/s, `any` 59,6, software 59,5. Forçar software só jogaria fora
     * a aceleração, e num canal H265 em FHD isso é a diferença entre tocar e
     * picotar.
     *
     * A lição, escrita aqui porque custou caro: teste que depende de rede
     * pública não vale sem repetição.
     */
    val OVERLAY_ARGS = emptyArray<String>()

    /**
     * Opções passadas por mídia no `play()`.
     *
     * `network-caching` é o buffer em ms. O default do VLC é 1000, afinado
     * para arquivo local e rede estável; IPTV oscila muito mais e engasga.
     * 3000 troca 3s a mais para começar por bem menos travada no meio.
     */
    fun mediaOptions(userAgent: String): Array<String> = arrayOf(
        ":network-caching=3000",
        ":http-reconnect",
        ":http-user-agent=$userAgent",
    )

    private fun locate(): Source {
        val bundled = bundledDir()
        if (bundled != null) prependJnaLibraryPath(bundled)

        val discovery = NativeDiscovery()
        val found = runCatching { discovery.discover() }.getOrDefault(false)
        if (!found) return Source.NONE

        val path = discovery.discoveredPath()
        loadedFrom = path ?: bundled?.absolutePath

        // `discoveredPath()` volta nulo se algo já tinha chamado `discover()`
        // antes — o vlcj guarda o resultado num estático. Nesse caso confio na
        // prioridade do jna.library.path, que é o que acabamos de configurar.
        if (path == null) return if (bundled != null) Source.BUNDLED else Source.SYSTEM

        val isBundled = bundled != null &&
            runCatching { File(path).canonicalFile == bundled.canonicalFile }.getOrDefault(false)
        return if (isBundled) Source.BUNDLED else Source.SYSTEM
    }

    /**
     * Candidatos a pasta embutida, na ordem:
     *
     * 1. `-Dranga.vlc.dir=...` — escape manual, para testar outra build da libvlc;
     * 2. a pasta de recursos do app — onde o pacote instalado guarda;
     * 3. `resources/windows-x64/vlc` do projeto — para `./gradlew run`.
     *
     * O teste é a presença da `libvlc.dll`, não da pasta: pasta vazia (ou pela
     * metade, se um antivírus comeu um arquivo) precisa cair no degrau seguinte
     * em vez de dar erro de carregamento no meio da reprodução.
     */
    private fun bundledDir(): File? = listOfNotNull(
        System.getProperty("ranga.vlc.dir"),
        System.getProperty("compose.application.resources.dir")?.let { "$it${File.separator}vlc" },
        "resources${File.separator}windows-x64${File.separator}vlc",
    ).map(::File).firstOrNull { File(it, "libvlc.dll").isFile }

    private fun prependJnaLibraryPath(dir: File) {
        val current = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (current.isNullOrBlank()) dir.absolutePath
            else dir.absolutePath + File.pathSeparator + current,
        )
    }
}
