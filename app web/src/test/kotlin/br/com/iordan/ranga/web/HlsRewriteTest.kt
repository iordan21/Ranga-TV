package br.com.iordan.ranga.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reescrita de playlist é o ponto que mais quebra em silêncio: um endereço
 * esquecido sem passar pelo servidor não dá erro na tela, o vídeo só não toca
 * (o navegador bloqueia por CORS e a TV fica no carregando).
 */
class HlsRewriteTest {

    private val base = "http://cdn.exemplo/live/token123/index.m3u8".toHttpUrl()
    private val proxify: (String) -> String = { "P($it)" }

    @Test
    fun `segmento relativo resolve contra a url final`() {
        val out = HlsRewrite.rewrite("#EXTM3U\n#EXTINF:6,\nseg1.ts", base, proxify)
        assertEquals("#EXTM3U\n#EXTINF:6,\nP(http://cdn.exemplo/live/token123/seg1.ts)", out)
    }

    @Test
    fun `segmento absoluto e caminho com barra tambem passam pelo servidor`() {
        val out = HlsRewrite.rewrite("http://outro.exemplo/a.ts\n/raiz/b.ts", base, proxify)
        assertEquals("P(http://outro.exemplo/a.ts)\nP(http://cdn.exemplo/raiz/b.ts)", out)
    }

    @Test
    fun `chave de criptografia e sub-playlist em atributo URI sao reescritas`() {
        val playlist = "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio/pt.m3u8\""
        val out = HlsRewrite.rewrite(playlist, base, proxify)
        assertTrue(out.contains("URI=\"P(http://cdn.exemplo/live/token123/key.bin)\""))
        assertTrue(out.contains("URI=\"P(http://cdn.exemplo/live/token123/audio/pt.m3u8)\""))
        assertTrue(out.contains("IV=0x1"))
    }

    @Test
    fun `comentario sem URI e linha vazia ficam iguais`() {
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n\n#EXT-X-ENDLIST"
        assertEquals(playlist, HlsRewrite.rewrite(playlist, base, proxify))
    }

    @Test
    fun `nenhum endereco do painel sobra sem passar pelo servidor`() {
        val playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhttp://cdn.exemplo/high/index.m3u8"
        val out = HlsRewrite.rewrite(playlist, base, proxify)
        out.lines().filter { !it.startsWith("#") && it.isNotBlank() }.forEach {
            assertTrue("sobrou sem proxy: $it", it.startsWith("P("))
        }
        assertFalse(out.lines().any { it.startsWith("http") })
    }
}
