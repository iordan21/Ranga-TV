package br.com.iordan.ranga

import br.com.iordan.ranga.data.Credentials
import br.com.iordan.ranga.data.LiveFormat
import br.com.iordan.ranga.data.XtreamUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testes de URL. Rodam em JVM pura (`./gradlew test`), sem emulador, porque
 * XtreamUrls não toca em nada do Android — foi justamente por isso que essa
 * lógica ficou separada do resto.
 *
 * É o único pedaço do app que dá para testar de verdade sem um servidor
 * Xtream real na frente, e por sorte é também o que mais quebra em silêncio:
 * uma URL errada não dá exceção, só devolve 404 e some com o canal.
 */
class XtreamUrlsTest {

    private val creds = Credentials(
        host = "http://painel.exemplo:80",
        username = "usuario",
        password = "senha",
    )

    @Test
    fun `host sem esquema recebe http`() {
        val url = XtreamUrls.normalizeHost("meupainel.exemplo")
        assertEquals("http", url?.scheme)
        assertEquals("meupainel.exemplo", url?.host)
        assertEquals(80, url?.port)
    }

    @Test
    fun `host descarta caminho e query colados do painel`() {
        val raw = "http://meupainel.exemplo:8080/get.php?username=a&password=b&type=m3u_plus"
        val url = XtreamUrls.normalizeHost(raw)
        assertEquals("http://meupainel.exemplo:8080/", url.toString())
    }

    @Test
    fun `host invalido devolve null em vez de explodir`() {
        assertNull(XtreamUrls.normalizeHost(""))
        assertNull(XtreamUrls.normalizeHost("   "))
    }

    @Test
    fun `displayHost omite a porta padrao`() {
        val comum = XtreamUrls.normalizeHost("http://meupainel.exemplo:80")!!
        assertEquals("http://meupainel.exemplo", XtreamUrls.displayHost(comum))

        val alternativa = XtreamUrls.normalizeHost("http://meupainel.exemplo:8080")!!
        assertEquals("http://meupainel.exemplo:8080", XtreamUrls.displayHost(alternativa))
    }

    @Test
    fun `url da api leva usuario senha e action`() {
        val url = XtreamUrls.api(creds, "action" to "get_live_categories")
        assertEquals(
            "http://painel.exemplo/player_api.php" +
                "?username=usuario&password=senha&action=get_live_categories",
            url.toString(),
        )
    }

    @Test
    fun `canal ao vivo respeita o formato escolhido`() {
        assertEquals(
            "http://painel.exemplo/live/usuario/senha/123.m3u8",
            XtreamUrls.live(creds, 123, LiveFormat.HLS),
        )
        assertEquals(
            "http://painel.exemplo/live/usuario/senha/123.ts",
            XtreamUrls.live(creds, 123, LiveFormat.TS),
        )
    }

    @Test
    fun `extensao vazia ou com ponto vira mp4 ou e limpa`() {
        assertEquals(
            "http://painel.exemplo/movie/usuario/senha/7.mp4",
            XtreamUrls.movie(creds, 7, ""),
        )
        assertEquals(
            "http://painel.exemplo/movie/usuario/senha/7.mkv",
            XtreamUrls.movie(creds, 7, ".MKV"),
        )
    }

    /**
     * O caso que motivou usar HttpUrl em vez de concatenar String: senha de
     * painel com caractere reservado. Concatenado, o `+` viraria espaço e o
     * `&` cortaria a query.
     */
    @Test
    fun `senha com caractere especial e escapada`() {
        val estranha = creds.copy(password = "a+b&c d")
        val url = XtreamUrls.api(estranha)
        assertEquals(true, url.toString().contains("password=a%2Bb%26c%20d"))
    }

    @Test
    fun `fromPath remonta a url a partir do path salvo`() {
        val path = XtreamUrls.moviePath(42, "mkv")
        assertEquals("movie/42.mkv", path)
        assertEquals(
            "http://painel.exemplo/movie/usuario/senha/42.mkv",
            XtreamUrls.fromPath(creds, path),
        )
    }

    @Test
    fun `fromPath rejeita path malformado`() {
        assertNull(XtreamUrls.fromPath(creds, "semBarra"))
        assertNull(XtreamUrls.fromPath(creds, "movie/"))
    }

    @Test
    fun `episodio usa o id do episodio e nao o da serie`() {
        assertEquals(
            "http://painel.exemplo/series/usuario/senha/98765.mp4",
            XtreamUrls.episode(creds, "98765", "mp4"),
        )
    }
}
