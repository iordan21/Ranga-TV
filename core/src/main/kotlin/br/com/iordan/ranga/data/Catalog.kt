package br.com.iordan.ranga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Repositório com cache em memória.
 *
 * Ponto central do desenho: **nunca peço a lista inteira de uma seção.**
 * Sua assinatura tem 70.000+ títulos de VOD; `get_vod_streams` sem
 * `category_id` devolveria dezenas de MB num JSON só — parse lento e pico de
 * memória que derruba TV Box barato. Então carrego sempre por categoria, e
 * guardo o resultado aqui para não repetir a chamada quando você voltar.
 *
 * O cache é em memória (não em disco) de propósito: catálogo de IPTV muda
 * direto, e ao reabrir o app você quer o estado real do provedor.
 */
class Catalog(private val api: XtreamApi) {

    private val mutex = Mutex()
    private val categoryCache = mutableMapOf<Section, List<Category>>()
    private val itemCache = mutableMapOf<String, List<CatalogItem>>()

    suspend fun categories(
        creds: Credentials,
        section: Section,
        refresh: Boolean = false,
    ): List<Category> {
        if (!refresh) {
            mutex.withLock { categoryCache[section] }?.let { return it }
        }
        val fetched = withContext(Dispatchers.Default) { api.categories(creds, section) }
        mutex.withLock { categoryCache[section] = fetched }
        return fetched
    }

    suspend fun items(
        creds: Credentials,
        section: Section,
        categoryId: String,
        refresh: Boolean = false,
    ): List<CatalogItem> {
        val cacheKey = "${section.name}:$categoryId"
        if (!refresh) {
            mutex.withLock { itemCache[cacheKey] }?.let { return it }
        }
        // Dispatchers.Default porque quem chama é o viewModelScope, que roda
        // na thread da tela. O download e o parse do JSON já iam para IO
        // dentro do XtreamApi, mas transformar milhares de objetos em itens,
        // ordenar e tirar repetidos acontecia na thread principal — numa
        // categoria grande, a TV Box ficava congelada até acabar.
        val fetched: List<CatalogItem> = withContext(Dispatchers.Default) {
            when (section) {
                Section.LIVE -> api.channels(creds, categoryId)
                Section.MOVIES -> api.movies(creds, categoryId)
                Section.SERIES -> api.series(creds, categoryId)
            }
                // distinctBy não é paranoia: painel Xtream repete o mesmo stream_id
                // na categoria com alguma frequência, e as LazyList do Compose
                // lançam IllegalArgumentException quando duas chaves se repetem.
                // Sem isto, o app abriria a categoria e crasharia na hora.
                .distinctBy { it.key }
        }
        mutex.withLock { itemCache[cacheKey] = fetched }
        return fetched
    }

    suspend fun login(creds: Credentials): AccountInfo = api.login(creds)

    suspend fun seriesDetail(creds: Credentials, seriesId: Int): SeriesDetail =
        api.seriesDetail(creds, seriesId)

    suspend fun movieDetail(creds: Credentials, streamId: Int): MovieDetail =
        api.movieDetail(creds, streamId)

    suspend fun shortEpg(creds: Credentials, streamId: Int): List<EpgEntry> =
        api.shortEpg(creds, streamId)

    /**
     * Busca em tudo que já está em cache. É instantânea e não gasta rede, mas
     * só enxerga categorias que você já abriu — por isso a tela oferece
     * [loadSection] quando o resultado vem curto.
     */
    suspend fun searchCached(section: Section, query: String): List<CatalogItem> {
        val needle = query.normalizeForSearch()
        if (needle.length < 2) return emptyList()
        val snapshot = mutex.withLock {
            itemCache.filterKeys { it.startsWith("${section.name}:") }.values.flatten()
        }
        // Normalizo uma vez por item e carrego a posição junto. A versão
        // ingênua (filter { contains } depois sortedBy { indexOf }) normaliza
        // a mesma string duas vezes, e sortedBy refaz isso a cada comparação —
        // com milhares de itens em cache, dá para sentir digitando.
        // Fora da thread da tela pelo mesmo motivo de [items].
        return withContext(Dispatchers.Default) { search(snapshot, needle) }
    }

    private fun search(snapshot: List<CatalogItem>, needle: String): List<CatalogItem> {
        return snapshot.asSequence()
            .mapNotNull { item ->
                val position = item.title.normalizeForSearch().indexOf(needle)
                if (position < 0) null else item to position
            }
            .distinctBy { it.first.key }
            .sortedBy { it.second }
            .take(300)
            .map { it.first }
            .toList()
    }

    /**
     * Carrega todas as categorias de uma seção, uma a uma, avisando o progresso.
     * Serve para "buscar em tudo". Sequencial de propósito: paralelizar aqui
     * costuma fazer o painel derrubar a conexão por excesso de requisições
     * (sua conta permite poucas conexões simultâneas).
     */
    suspend fun loadSection(
        creds: Credentials,
        section: Section,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val cats = categories(creds, section)
        cats.forEachIndexed { index, category ->
            runCatching { items(creds, section, category.id) }
            onProgress(index + 1, cats.size)
        }
    }

    suspend fun invalidate() {
        mutex.withLock {
            categoryCache.clear()
            itemCache.clear()
        }
    }
}

// Fora da função de propósito: `Regex(...)` dentro do corpo recompilaria o
// padrão a cada chamada, e esta função roda uma vez por item a cada tecla
// digitada na busca. Era o gargalo real, não o loop.
private val diacritics = Regex("\\p{Mn}+")

/**
 * Nomes de canal vêm com acento, caixa mista e prefixos tipo "| BR |".
 * Normalizo tirando acento e caixa para que "sao paulo" ache "SÃO PAULO".
 */
fun String.normalizeForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(diacritics, "")
        .lowercase()
        .trim()
