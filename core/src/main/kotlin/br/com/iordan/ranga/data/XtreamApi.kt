package br.com.iordan.ranga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Erro já traduzido para algo que faz sentido mostrar na tela. */
class XtreamException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

/**
 * Cliente do painel Xtream Codes.
 *
 * Decisão que talvez surpreenda: **não uso Retrofit nem @Serializable aqui.**
 * Painéis Xtream são notoriamente inconsistentes com tipos — o mesmo campo
 * volta como `"5"`, `5`, `5.0` ou `null` dependendo da rota e da versão do
 * painel; `episodes` volta como objeto quando tem dado e como array vazio
 * quando não tem. Com data class anotada isso vira crash em produção.
 *
 * Então: parseio para [JsonElement] cru e faço a leitura campo a campo com
 * helpers tolerantes ([text], [int], [dbl]). Nada explode por tipo inesperado —
 * no pior caso um campo vem nulo. Como bônus, isso dispensa o plugin de
 * compilação do kotlinx-serialization (só a lib em runtime), o que é uma peça
 * a menos para dar conflito de versão com o Kotlin embutido no AGP 9.
 */
class XtreamApi(
    private val client: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ---------------------------------------------------------------- rotas

    suspend fun login(creds: Credentials): AccountInfo {
        val root = request(creds).asObject()
            ?: throw XtreamException("O servidor respondeu num formato inesperado.")
        val info = root["user_info"].asObject()
            ?: throw XtreamException("Servidor não reconheceu esta conta.")

        if (info.int("auth") == 0) {
            throw XtreamException("Usuário ou senha incorretos.")
        }
        val status = info.text("status") ?: "Desconhecido"
        if (!status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Assinatura com status \"$status\". Fale com o provedor.")
        }

        return AccountInfo(
            username = info.text("username") ?: creds.username,
            status = status,
            expiresAtEpochSecs = info.long("exp_date"),
            maxConnections = info.int("max_connections"),
            activeConnections = info.int("active_cons"),
            isTrial = info.text("is_trial") == "1",
            allowedFormats = info["allowed_output_formats"].asArray()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    suspend fun categories(creds: Credentials, section: Section): List<Category> =
        request(creds, "action" to section.categoriesAction)
            .asArray()
            .mapNotNull { element ->
                val o = element.asObject() ?: return@mapNotNull null
                val id = o.text("category_id") ?: return@mapNotNull null
                Category(
                    id = id,
                    name = o.text("category_name") ?: "Sem nome",
                    section = section,
                )
            }

    suspend fun channels(creds: Credentials, categoryId: String): List<Channel> =
        request(creds, "action" to Section.LIVE.streamsAction, "category_id" to categoryId)
            .asArray()
            .mapNotNull { element ->
                val o = element.asObject() ?: return@mapNotNull null
                val id = o.int("stream_id") ?: return@mapNotNull null
                Channel(
                    streamId = id,
                    title = o.text("name") ?: "Canal $id",
                    image = o.text("stream_icon"),
                    categoryId = o.text("category_id"),
                    epgChannelId = o.text("epg_channel_id"),
                    number = o.int("num"),
                )
            }
            .sortedBy { it.number ?: Int.MAX_VALUE }

    suspend fun movies(creds: Credentials, categoryId: String): List<Movie> =
        request(creds, "action" to Section.MOVIES.streamsAction, "category_id" to categoryId)
            .asArray()
            .mapNotNull { element ->
                val o = element.asObject() ?: return@mapNotNull null
                val id = o.int("stream_id") ?: return@mapNotNull null
                Movie(
                    streamId = id,
                    title = o.text("name") ?: "Filme $id",
                    image = o.text("stream_icon"),
                    categoryId = o.text("category_id"),
                    extension = o.text("container_extension") ?: "mp4",
                    rating = o.dbl("rating"),
                )
            }

    suspend fun series(creds: Credentials, categoryId: String): List<SeriesItem> =
        request(creds, "action" to Section.SERIES.streamsAction, "category_id" to categoryId)
            .asArray()
            .mapNotNull { element -> element.asObject()?.toSeriesItem() }

    /**
     * Detalhe da série. O ponto sensível é `episodes`: quando a série tem
     * episódios vem `{"1": [...], "2": [...]}`; quando não tem, vários painéis
     * mandam `[]` (array!) no lugar. [asObject] devolve null nesse caso e a
     * série simplesmente aparece sem episódios, em vez de derrubar o app.
     */
    /**
     * Detalhe por id (e não por objeto) de propósito: assim a rota de navegação
     * carrega só um número. Se o Android matar o processo com a tela aberta,
     * ela se reconstrói sozinha — nada de "objeto perdido" no restore.
     */
    suspend fun seriesDetail(creds: Credentials, seriesId: Int): SeriesDetail {
        val root = request(creds, "action" to "get_series_info", "series_id" to "$seriesId")
            .asObject() ?: throw XtreamException("Não consegui ler os episódios desta série.")

        val placeholder = SeriesItem(seriesId, "Série $seriesId", null, null, null, null, null, null)
        val merged = root["info"].asObject()?.toSeriesItem(fallback = placeholder) ?: placeholder

        val seasons = root["episodes"].asObject()
            ?.mapNotNull { (seasonKey, value) ->
                val seasonNumber = seasonKey.toIntOrNull() ?: return@mapNotNull null
                val episodes = value.asArray()
                    .mapNotNull { it.asObject()?.toEpisode(seasonNumber) }
                    .distinctBy { it.id } // chave repetida derruba a LazyColumn
                    .sortedBy { it.number }
                if (episodes.isEmpty()) null else seasonNumber to episodes
            }
            ?.toMap()
            .orEmpty()

        return SeriesDetail(series = merged, seasons = seasons)
    }

    suspend fun movieDetail(creds: Credentials, streamId: Int): MovieDetail {
        val root = request(creds, "action" to "get_vod_info", "vod_id" to "$streamId")
            .asObject()
        val info = root?.get("info").asObject()
        val data = root?.get("movie_data").asObject()

        return MovieDetail(
            movie = Movie(
                streamId = data?.int("stream_id") ?: streamId,
                title = data?.text("name") ?: info?.text("name") ?: "Filme $streamId",
                image = info?.text("movie_image") ?: info?.text("cover_big"),
                categoryId = data?.text("category_id"),
                // container_extension confiável só vem aqui, no detalhe.
                extension = data?.text("container_extension") ?: "mp4",
                rating = info?.dbl("rating"),
            ),
            plot = info?.text("plot") ?: info?.text("description"),
            cast = info?.text("cast") ?: info?.text("actors"),
            director = info?.text("director"),
            genre = info?.text("genre"),
            releaseDate = info?.text("releasedate") ?: info?.text("release_date"),
            durationSecs = info?.int("duration_secs"),
            backdrop = info?.get("backdrop_path").asArray()
                .firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull },
            youtubeTrailer = info?.text("youtube_trailer"),
        )
    }

    /**
     * EPG curto ("agora / a seguir") de um canal. Título e descrição vêm em
     * Base64 no Xtream — mas nem sempre, dependendo do painel; por isso o
     * [decodeMaybeBase64] em vez de um decode direto.
     */
    suspend fun shortEpg(creds: Credentials, streamId: Int, limit: Int = 4): List<EpgEntry> =
        runCatching {
            request(
                creds,
                "action" to "get_short_epg",
                "stream_id" to "$streamId",
                "limit" to "$limit",
            ).asObject()
                ?.get("epg_listings").asArray()
                .mapNotNull { element ->
                    val o = element.asObject() ?: return@mapNotNull null
                    val start = o.long("start_timestamp") ?: return@mapNotNull null
                    val end = o.long("stop_timestamp") ?: (start + 3600)
                    EpgEntry(
                        title = decodeMaybeBase64(o.text("title")) ?: "Sem informação",
                        description = decodeMaybeBase64(o.text("description")),
                        startEpochSecs = start,
                        endEpochSecs = end,
                    )
                }
                .sortedBy { it.startEpochSecs }
        }.getOrDefault(emptyList()) // EPG é enfeite: se falhar, a tela segue sem ele.

    // ------------------------------------------------------------- plumbing

    private suspend fun request(
        creds: Credentials,
        vararg params: Pair<String, String>,
    ): JsonElement = withContext(Dispatchers.IO) {
        val url = XtreamUrls.api(creds, *params)
            ?: throw XtreamException("Endereço do servidor inválido.")
        val body = fetch(url)
        try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            // Painel fora do ar costuma devolver HTML de erro no lugar de JSON.
            throw XtreamException("O servidor respondeu algo que não é JSON. Ele pode estar fora do ar.", e)
        }
    }

    private fun fetch(url: HttpUrl): String {
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw XtreamException(
                        when (response.code) {
                            401, 403 -> "Servidor recusou o login (HTTP ${response.code})."
                            404 -> "Rota não encontrada no servidor (HTTP 404). Confira o endereço."
                            512, 521, 522 -> "Servidor do provedor está fora do ar (HTTP ${response.code})."
                            else -> "Servidor retornou erro HTTP ${response.code}."
                        }
                    )
                }
                return response.body.string()
            }
        } catch (e: UnknownHostException) {
            throw XtreamException("Não achei esse servidor. Confira o endereço e sua internet.", e)
        } catch (e: SocketTimeoutException) {
            throw XtreamException("O servidor demorou demais para responder.", e)
        } catch (e: ConnectException) {
            // Endereço existe, mas ninguém atende na porta: é o caso típico de
            // DNS do provedor que saiu do ar.
            throw XtreamException("O servidor não atendeu. Ele pode estar fora do ar.", e)
        } catch (e: SSLException) {
            throw XtreamException("Falha na conexão segura (https). Tente o endereço com http://.", e)
        } catch (e: XtreamException) {
            throw e
        } catch (e: IOException) {
            // Sem e.message na tela: ela vem do OkHttp, em inglês e técnica
            // ("Failed to connect to /1.2.3.4:80"). O detalhe fica na causa.
            throw XtreamException("Falha de rede. Confira sua internet e o endereço do servidor.", e)
        }
    }
}

// ------------------------------------------------------ leitores tolerantes

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

/** Devolve null para ausente, JSON null, string vazia e a string literal "null". */
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

/** Aceita 5, "5" e 5.0 — os três aparecem no mesmo painel. */
private fun JsonObject.int(key: String): Int? =
    text(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

private fun JsonObject.long(key: String): Long? =
    text(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

private fun JsonObject.dbl(key: String): Double? =
    text(key)?.replace(',', '.')?.toDoubleOrNull()

private fun JsonObject.toSeriesItem(fallback: SeriesItem? = null): SeriesItem? {
    val id = int("series_id") ?: fallback?.seriesId ?: return null
    return SeriesItem(
        seriesId = id,
        title = text("name") ?: fallback?.title ?: "Série $id",
        image = text("cover") ?: text("cover_big") ?: fallback?.image,
        categoryId = text("category_id") ?: fallback?.categoryId,
        plot = text("plot") ?: fallback?.plot,
        rating = dbl("rating_5based")?.times(2) ?: dbl("rating") ?: fallback?.rating,
        genre = text("genre") ?: fallback?.genre,
        releaseDate = text("releaseDate") ?: text("releasedate") ?: fallback?.releaseDate,
    )
}

private fun JsonObject.toEpisode(seasonFromKey: Int): Episode? {
    val id = text("id") ?: return null
    val info = this["info"].asObject()
    return Episode(
        id = id,
        season = int("season") ?: seasonFromKey,
        number = int("episode_num") ?: 0,
        title = text("title") ?: "Episódio ${int("episode_num") ?: ""}".trim(),
        extension = text("container_extension") ?: "mp4",
        image = info?.text("movie_image") ?: text("movie_image"),
        plot = info?.text("plot") ?: info?.text("overview"),
        durationSecs = info?.int("duration_secs"),
    )
}

/**
 * Xtream manda título/descrição do EPG em Base64, mas alguns painéis mandam
 * texto puro. Tento decodificar; se o resultado tiver caracteres de controle
 * (sinal claro de que não era Base64 de texto), devolvo o original.
 */
private fun decodeMaybeBase64(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val bytes = decodeBase64(raw) ?: return raw
    val decoded = String(bytes, Charsets.UTF_8)
    val looksLikeText = decoded.isNotBlank() &&
        decoded.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
    return if (looksLikeText) decoded else raw
}

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * Base64 escrito à mão, e não `android.util.Base64` nem `java.util.Base64`.
 *
 * Este arquivo é compilado pelo app Android **e** pelo app de Windows.
 * `android.util.Base64` não existe fora do Android; `java.util.Base64` existe
 * nos dois, mas só a partir da API 26 no Android — e o app roda desde a 24,
 * que é o que ainda se vê em TV Box. Vinte linhas aqui evitam ter que escolher
 * entre abandonar aparelho antigo ou duplicar o arquivo.
 *
 * Devolve null se a entrada não for Base64 válido, para o chamador poder
 * tratar como texto puro.
 */
private fun decodeBase64(input: String): ByteArray? {
    val out = java.io.ByteArrayOutputStream(input.length * 3 / 4 + 3)
    var buffer = 0
    var bits = 0
    for (raw in input) {
        if (raw == '=') break
        if (raw == '\n' || raw == '\r' || raw == ' ' || raw == '\t') continue
        // Alguns painéis usam o alfabeto "URL-safe" ('-' e '_').
        val ch = when (raw) {
            '-' -> '+'
            '_' -> '/'
            else -> raw
        }
        val value = BASE64_ALPHABET.indexOf(ch)
        if (value < 0) return null
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xFF)
        }
    }
    return out.toByteArray()
}
