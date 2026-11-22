package br.com.iordan.ranga.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Montagem de URL do padrão Xtream Codes.
 *
 * Isolei tudo aqui por dois motivos:
 *  1. É a parte mais fácil de errar (uma barra a mais e o painel devolve 404),
 *     e ao mesmo tempo é pura — dá para testar em JVM, sem emulador.
 *     Ver `app/src/test/.../XtreamUrlsTest.kt`.
 *  2. Uso [HttpUrl] do OkHttp em vez de concatenar String porque ele faz o
 *     percent-encoding correto de usuário e senha. Se um dia a senha tiver
 *     um `+` ou `&`, concatenação silenciosamente geraria um link quebrado.
 *
 * Formato dos links (é o mesmo que aparece no painel):
 *   catálogo : {base}/player_api.php?username=U&password=P&action=...
 *   ao vivo  : {base}/live/U/P/{stream_id}.m3u8   (ou .ts)
 *   filme    : {base}/movie/U/P/{stream_id}.{container_extension}
 *   episódio : {base}/series/U/P/{episode_id}.{container_extension}
 */
object XtreamUrls {

    /**
     * Aceita o que o usuário digitar e devolve só `scheme://host:porta`.
     *
     * Tolera: "painel.exemplo", "http://painel.exemplo:80", "http://painel.exemplo/c/",
     * e até a URL completa do get.php colada do painel — em todos os casos
     * o path e a query são descartados.
     */
    fun normalizeHost(raw: String): HttpUrl? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
            text = "http://$text"
        }
        val parsed = text.toHttpUrlOrNull() ?: return null
        return HttpUrl.Builder()
            .scheme(parsed.scheme)
            .host(parsed.host)
            .port(parsed.port) // HttpUrl já preenche 80/443 quando omitido
            .build()
    }

    /** Forma legível para mostrar/salvar: omite a porta quando é a padrão. */
    fun displayHost(url: HttpUrl): String =
        if (url.port == HttpUrl.defaultPort(url.scheme)) "${url.scheme}://${url.host}"
        else "${url.scheme}://${url.host}:${url.port}"

    fun api(creds: Credentials, vararg params: Pair<String, String>): HttpUrl? {
        val base = normalizeHost(creds.host) ?: return null
        return base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", creds.username)
            .addQueryParameter("password", creds.password)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
    }

    fun live(creds: Credentials, streamId: Int, format: LiveFormat): String? =
        fromPath(creds, livePath(streamId, format))

    fun movie(creds: Credentials, streamId: Int, extension: String): String? =
        fromPath(creds, moviePath(streamId, extension))

    fun episode(creds: Credentials, episodeId: String, extension: String): String? =
        fromPath(creds, episodePath(episodeId, extension))

    // "Path" = a parte estável do link, sem host nem credencial:
    // "movie/1234.mp4". É o que guardo em "continuar assistindo", para que
    // trocar de senha ou de servidor não invalide a lista salva.

    fun livePath(streamId: Int, format: LiveFormat) = "live/$streamId.${format.extension}"

    fun moviePath(streamId: Int, extension: String) = "movie/$streamId.${sanitizeExtension(extension)}"

    fun episodePath(episodeId: String, extension: String) = "series/$episodeId.${sanitizeExtension(extension)}"

    /** Remonta a URL tocável a partir de um path salvo + credenciais atuais. */
    fun fromPath(creds: Credentials, path: String): String? {
        val separator = path.indexOf('/')
        if (separator <= 0 || separator == path.lastIndex) return null
        val base = normalizeHost(creds.host) ?: return null
        return base.newBuilder()
            .addPathSegment(path.substring(0, separator))
            .addPathSegment(creds.username)
            .addPathSegment(creds.password)
            .addPathSegment(path.substring(separator + 1))
            .build()
            .toString()
    }

    /**
     * `container_extension` às vezes volta vazio, nulo ou já com ponto.
     * mp4 é o chute seguro: é o container mais comum nos painéis.
     */
    private fun sanitizeExtension(raw: String?): String =
        raw?.trim()?.removePrefix(".")?.lowercase()?.takeIf { it.isNotEmpty() } ?: "mp4"
}
