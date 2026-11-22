package br.com.iordan.ranga

import br.com.iordan.ranga.data.PrefsCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lista de DNS. O que importa aqui é não duplicar: o mesmo endereço escrito de
 * dois jeitos viraria duas linhas na tela, e só uma delas apareceria marcada
 * como "em uso".
 */
class ServersTest {

    @Test
    fun `lista colada de uma vez vira um endereco por linha`() {
        val text = """
            http://painel-a.exemplo
            http://painel-b.exemplo
            painel-c.exemplo:8080
        """.trimIndent()
        assertEquals(
            listOf("http://painel-a.exemplo", "http://painel-b.exemplo", "http://painel-c.exemplo:8080"),
            PrefsCodec.parseServers(text),
        )
    }

    @Test
    fun `mesmo endereco escrito de jeitos diferentes entra uma vez`() {
        assertEquals(
            listOf("http://painel.exemplo"),
            PrefsCodec.parseServers("painel.exemplo, http://painel.exemplo:80/ http://painel.exemplo/c/"),
        )
    }

    @Test
    fun `texto sem endereco nenhum devolve lista vazia`() {
        assertEquals(emptyList<String>(), PrefsCodec.parseServers("   "))
    }

    @Test
    fun `acrescentar preserva a ordem e nao repete`() {
        val current = listOf("http://a.exemplo", "http://b.exemplo")
        assertEquals(
            listOf("http://a.exemplo", "http://b.exemplo", "http://c.exemplo"),
            PrefsCodec.withServers(current, listOf("http://b.exemplo", "http://c.exemplo")),
        )
    }

    @Test
    fun `ida e volta pelo json`() {
        val hosts = listOf("http://a.exemplo", "https://b.exemplo:8443")
        assertEquals(hosts, PrefsCodec.decodeServers(PrefsCodec.encodeServers(hosts)))
    }

    @Test
    fun `json corrompido nao derruba o app`() {
        assertEquals(emptyList<String>(), PrefsCodec.decodeServers("{nao e lista"))
    }
}
