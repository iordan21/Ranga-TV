package br.com.iordan.ranga.data

/**
 * Canal aberto com stream publicado pelo próprio canal. Não precisa de conta.
 *
 * A regra para entrar nesta lista é uma só: a URL tem que ter sido tirada da
 * página oficial do canal (ou do embed que ela carrega), e não de lista de
 * terceiro. Canal que só transmite por YouTube/Dailymotion fica de fora — o
 * ExoPlayer não toca esses players, e extrair o vídeo deles viola os termos.
 *
 * [source] e [checkedOn] não são enfeite: stream oficial troca de endereço sem
 * aviso, e sem saber de onde veio e quando funcionou não dá para distinguir
 * "link caiu ontem" de "link errado desde sempre".
 */
data class FreeChannel(
    /** Estável, sem acento nem espaço: vira a chave "free:{id}" do [Playable]. */
    val id: String,
    val name: String,
    /** HLS (.m3u8). */
    val url: String,
    val logo: String?,
    /** Página oficial de onde a URL saiu. */
    val source: String,
    /** Dia em que o stream foi conferido tocando, no formato AAAA-MM-DD. */
    val checkedOn: String,
) {
    val key: String get() = "free:$id"

    /** Só o domínio da fonte, para caber numa linha da lista. */
    val sourceHost: String
        get() = source.removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").substringBefore('/')
}

object FreeChannels {

    /**
     * Ordem fixa, e não alfabética: TV Brasil primeiro por ser a emissora
     * pública nacional, e é ela que abre quando a lista vira fila de zapping.
     */
    val ALL: List<FreeChannel> = listOf(
        FreeChannel(
            id = "tvbrasil",
            name = "TV Brasil",
            url = "https://tvbrasil-stream.ebc.com.br/index.m3u8",
            logo = "https://tvbrasil.ebc.com.br/sites/default/themes/tvbrasil/img/logo_tvbrasil.png",
            // A página "WebTV" carrega o embed aovivo.ebc.com.br/embed-tvbrasil.html,
            // e é dele que a URL sai.
            source = "https://tvbrasil.ebc.com.br/webtv",
            checkedOn = "2026-09-20",
        ),
        // Os dois abaixo são da EBC, como a TV Brasil, e saem do mesmo lugar:
        // os embeds oficiais em aovivo.ebc.com.br.
        FreeChannel(
            id = "canalgov",
            name = "Canal Gov",
            url = "https://canalgov-stream.ebc.com.br/index.m3u8",
            logo = null,
            source = "https://aovivo.ebc.com.br/embed-canalgov.html",
            checkedOn = "2026-11-10",
        ),
        FreeChannel(
            id = "canaleducacao",
            name = "Canal Educação",
            url = "https://canaleducacao-stream.ebc.com.br/index.m3u8",
            logo = null,
            source = "https://aovivo.ebc.com.br/embed-canaleducacao.html",
            checkedOn = "2026-11-10",
        ),
        FreeChannel(
            id = "tvcamara",
            name = "TV Câmara",
            url = "https://stream3.camara.gov.br/tv1/manifest.m3u8",
            logo = null,
            source = "https://www.camara.leg.br/tv",
            checkedOn = "2026-09-20",
        ),
        FreeChannel(
            id = "tvcultura",
            name = "TV Cultura",
            url = "https://player-tvcultura.stream.uol.com.br/live/tvcultura.m3u8",
            logo = null,
            source = "https://cultura.uol.com.br/aovivo",
            checkedOn = "2026-09-20",
        ),
        FreeChannel(
            id = "redeminas",
            name = "Rede Minas",
            url = "https://8hzcavccys.zoeweb.tv/redeminas/ngrp:redeminas_all/playlist.m3u8",
            logo = null,
            // redeminas.tv manda para minasplay.com/aovivo, cujo botão "Assistir"
            // abre o player da ZoePlay com esta URL.
            source = "https://minasplay.com/aovivo",
            checkedOn = "2026-09-20",
        ),
    )

    fun byId(id: String): FreeChannel? = ALL.firstOrNull { it.id == id }
}
