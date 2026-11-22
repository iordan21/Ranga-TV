package br.com.iordan.ranga.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * O que é guardado em disco, e como.
 *
 * O *onde* é problema de cada plataforma — no Android é DataStore, no Windows é
 * um arquivo em `%APPDATA%`. Mas o formato e as regras de negócio (quando vale
 * a pena salvar um progresso, quantos itens manter) são iguais nos dois, e é
 * isso que mora aqui. Se um dia eu mudar a regra dos 95%, muda nos dois apps.
 */

object RangaDefaults {

    /**
     * Alguns painéis Xtream só respondem a User-Agents de players que eles
     * conhecem e devolvem 403 para o padrão do ExoPlayer. Este default
     * maximiza compatibilidade; dá para trocar em Configurações.
     */
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    val USER_AGENT_PRESETS = listOf(
        USER_AGENT,
        "Lavf/60.16.100",
        "IPTVSmartersPro",
        "Ranga/1.0",
    )

    const val RESUME_LIMIT = 30
}

/** Item de catálogo achatado para caber no disco (favoritos). */
data class SavedItem(
    val key: String,
    val title: String,
    val image: String?,
    val section: Section,
    val id: Int,
    val extension: String?,
) {
    fun toCatalogItem(): CatalogItem = when (section) {
        Section.LIVE -> Channel(id, title, image, null, null, null)
        Section.MOVIES -> Movie(id, title, image, null, extension ?: "mp4", null)
        Section.SERIES -> SeriesItem(id, title, image, null, null, null, null, null)
    }

    companion object {
        fun from(item: CatalogItem) = SavedItem(
            key = item.key,
            title = item.title,
            image = item.image,
            section = item.section,
            id = when (item) {
                is Channel -> item.streamId
                is Movie -> item.streamId
                is SeriesItem -> item.seriesId
            },
            extension = (item as? Movie)?.extension,
        )
    }
}

data class ResumeEntry(
    val key: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    /** "movie/1234.mp4" — sem host nem credencial, ver [XtreamUrls.fromPath]. */
    val streamPath: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Serialização à mão com `buildJsonObject`, e não `@Serializable`.
 *
 * Motivo: assim nenhum dos dois apps precisa do plugin de compilação do
 * kotlinx-serialization — só da lib em runtime. Uma peça a menos para dar
 * conflito de versão do Kotlin entre o build do Android (Kotlin do AGP) e o
 * build do desktop (Kotlin próprio). São poucos campos e o formato é meu.
 */
object PrefsCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encodeFavorites(items: List<SavedItem>): String = buildJsonArray {
        items.forEach { item ->
            add(buildJsonObject {
                put("key", item.key)
                put("title", item.title)
                item.image?.let { put("image", it) }
                put("section", item.section.name)
                put("id", item.id.toString())
                item.extension?.let { put("extension", it) }
            })
        }
    }.toString()

    fun decodeFavorites(raw: String?): List<SavedItem> = parseArray(raw) { o ->
        val key = o.str("key") ?: return@parseArray null
        val id = o.str("id")?.toIntOrNull() ?: return@parseArray null
        SavedItem(
            key = key,
            title = o.str("title") ?: "Sem título",
            image = o.str("image"),
            section = Section.fromName(o.str("section")),
            id = id,
            extension = o.str("extension"),
        )
    }

    fun encodeResume(entries: List<ResumeEntry>): String = buildJsonArray {
        entries.forEach { entry ->
            add(buildJsonObject {
                put("key", entry.key)
                put("title", entry.title)
                entry.subtitle?.let { put("subtitle", it) }
                entry.image?.let { put("image", it) }
                put("path", entry.streamPath)
                put("pos", entry.positionMs.toString())
                put("dur", entry.durationMs.toString())
                put("at", entry.updatedAt.toString())
            })
        }
    }.toString()

    fun decodeResume(raw: String?): List<ResumeEntry> = parseArray(raw) { o ->
        val key = o.str("key") ?: return@parseArray null
        val path = o.str("path") ?: return@parseArray null
        ResumeEntry(
            key = key,
            title = o.str("title") ?: "Sem título",
            subtitle = o.str("subtitle"),
            image = o.str("image"),
            streamPath = path,
            positionMs = o.str("pos")?.toLongOrNull() ?: 0L,
            durationMs = o.str("dur")?.toLongOrNull() ?: 0L,
            updatedAt = o.str("at")?.toLongOrNull() ?: 0L,
        )
    }

    /**
     * Regra do "continuar assistindo", compartilhada:
     *  - antes de 1 min ou depois de 95% assistido, não guarda (você quer
     *    recomeçar, não retomar nos créditos);
     *  - lista limitada, mais recente primeiro, sem repetir chave.
     */
    fun withProgress(current: List<ResumeEntry>, entry: ResumeEntry): List<ResumeEntry> {
        val without = current.filterNot { it.key == entry.key }
        val tooEarly = entry.positionMs < 60_000
        val basicallyDone = entry.durationMs > 0 && entry.positionMs > entry.durationMs * 0.95
        return if (tooEarly || basicallyDone) without
        else (listOf(entry) + without).take(RangaDefaults.RESUME_LIMIT)
    }

    // ------------------------------------------------------------ servidores
    //
    // Provedor costuma passar vários endereços ("DNS") para a mesma conta:
    // espelhos do mesmo painel, com o mesmo usuário e senha. A lista guarda
    // todos; a credencial guarda só o que está em uso.

    fun encodeServers(hosts: List<String>): String = buildJsonArray {
        hosts.forEach { add(JsonPrimitive(it)) }
    }.toString()

    fun decodeServers(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotEmpty() } }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Quebra o texto digitado em endereços. Aceita vários de uma vez porque o
     * provedor manda a lista inteira numa mensagem, e colar tudo é mais fácil
     * do que digitar cinco domínios no controle remoto.
     *
     * Devolve na forma canônica (`http://host` ou `http://host:porta`), que é
     * a mesma que o login salva — sem isso, "painel.exemplo" e
     * "http://painel.exemplo:80" entrariam duas vezes na lista.
     */
    fun parseServers(text: String): List<String> =
        text.split(Regex("[\\s,;]+"))
            .mapNotNull { XtreamUrls.normalizeHost(it)?.let(XtreamUrls::displayHost) }
            .distinct()

    /** Acrescenta no fim, sem repetir. A ordem é a que o usuário cadastrou. */
    fun withServers(current: List<String>, added: List<String>): List<String> =
        (current + added).distinct()

    fun toggleFavorite(current: List<SavedItem>, item: CatalogItem): List<SavedItem> {
        val saved = SavedItem.from(item)
        return if (current.any { it.key == saved.key }) current.filterNot { it.key == saved.key }
        else current + saved
    }

    private fun <T> parseArray(raw: String?, mapper: (JsonObject) -> T?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(mapper) }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}
