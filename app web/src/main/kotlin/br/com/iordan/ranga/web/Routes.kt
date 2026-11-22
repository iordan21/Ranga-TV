package br.com.iordan.ranga.web

import br.com.iordan.ranga.data.Catalog
import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.Channel
import br.com.iordan.ranga.data.Credentials
import br.com.iordan.ranga.data.FreeChannels
import br.com.iordan.ranga.data.LiveFormat
import br.com.iordan.ranga.data.Movie
import br.com.iordan.ranga.data.PrefsCodec
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.data.SavedItem
import br.com.iordan.ranga.data.Section
import br.com.iordan.ranga.data.SeriesItem
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.XtreamUrls
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Tudo que o navegador pede passa por aqui.
 *
 *   /                 a página (index.html, app.js, app.css)
 *   /api/...          JSON: conta, categorias, itens, séries, DNS
 *   /stream/...       vídeo, montado no servidor com a credencial guardada
 *   /hls?u=..&s=..    pedaços de HLS repassados, só com assinatura válida
 *
 * O navegador nunca recebe usuário e senha do painel: ele pede
 * `/stream/live/123.m3u8` e o servidor monta a URL Xtream por dentro. Assim a
 * credencial não aparece no histórico da TV nem passa pelo túnel do playit.
 */
class Routes(
    private val store: WebStore,
    private val catalog: Catalog,
    private val streamHttp: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val throttle = LoginThrottle()
    private val staticCache = mutableMapOf<String, ByteArray>()

    /**
     * Busca precisa da seção inteira carregada, e o painel só entrega por
     * categoria. Quem carrega é o PC, em segundo plano, uma categoria por vez
     * (o painel derruba conexões em paralelo — ver Catalog.loadSection). A TV
     * só pergunta o progresso e vai recebendo resultados melhores.
     */
    private data class IndexState(val done: Int, val total: Int, val finished: Boolean)
    private val indexes = ConcurrentHashMap<Section, IndexState>()
    @Volatile private var indexGeneration = 0

    fun handle(ex: HttpExchange) {
        try {
            route(ex)
        } catch (e: XtreamException) {
            runCatching { sendJson(ex, 502, error(e.userMessage)) }
        } catch (_: IOException) {
            // O navegador fechou a conexão no meio: trocou de canal, saiu do
            // vídeo, pulou para outro ponto do filme. É o normal num player.
        } catch (e: Exception) {
            runCatching { sendJson(ex, 500, error(e.message ?: "Falha inesperada.")) }
        } finally {
            ex.close()
        }
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        securityHeaders(ex)
        if (ex.requestMethod == "POST" && !sameOrigin(ex)) {
            return sendJson(ex, 403, error("Pedido vindo de outro site."))
        }
        when (path) {
            "/" -> return static(ex, "index.html", "text/html; charset=utf-8")
            "/app.js" -> return static(ex, "app.js", "text/javascript; charset=utf-8")
            "/app.css" -> return static(ex, "app.css", "text/css; charset=utf-8")
            "/lib/hls.min.js" -> return static(ex, "lib/hls.min.js", "text/javascript; charset=utf-8")
            "/lib/mpegts.js" -> return static(ex, "lib/mpegts.js", "text/javascript; charset=utf-8")
            "/api/status" -> return sendJson(ex, 200, status(ex))
            "/api/access/setup" -> return accessSetup(ex)
            "/api/access/login" -> return accessLogin(ex)
        }
        if (!isAuthed(ex)) return sendJson(ex, 401, error("Entre com a senha de acesso."))
        when {
            path.startsWith("/api/") -> api(ex, path.removePrefix("/api/"))
            path.startsWith("/stream/") -> stream(ex, path.removePrefix("/stream/"))
            path == "/hls" -> hls(ex)
            else -> sendText(ex, 404, "Não encontrado.")
        }
    }

    // ------------------------------------------------------- senha de acesso

    private fun status(ex: HttpExchange): JsonObject {
        val d = store.data
        val authed = isAuthed(ex)
        return buildJsonObject {
            put("needsSetup", d.accessHash == null)
            put("canSetup", d.accessHash == null && isHomeNetwork(ex))
            put("authed", authed)
            if (authed) {
                put("loggedIn", d.credentials.isComplete)
                put("host", d.credentials.host)
                put("username", d.credentials.username)
                put("servers", strings(serversOf(d.servers, d.credentials)))
            }
        }
    }

    /**
     * Criar a senha só vale de dentro de casa. Sem esta trava, quem achasse o
     * endereço do playit antes de você criaria a senha e ficaria com o site.
     *
     * Olho o cabeçalho Host, e não o IP de origem: pelo playit todo pedido
     * chega de 127.0.0.1 (o túnel é um programa local), mas o Host é o
     * endereço que a pessoa digitou — o domínio do playit.
     */
    private fun accessSetup(ex: HttpExchange) {
        if (store.data.accessHash != null) return sendJson(ex, 409, error("A senha de acesso já foi criada."))
        if (!isHomeNetwork(ex)) return sendJson(ex, 403, error("Crie a senha de dentro de casa, pelo endereço da rede local."))
        val password = body(ex).str("password").orEmpty()
        if (password.length < 6) return sendJson(ex, 400, error("Use pelo menos 6 caracteres."))
        val token = Access.newSessionToken()
        store.update { it.copy(accessHash = Access.hashPassword(password), sessions = listOf(token)) }
        setSessionCookie(ex, token)
        sendJson(ex, 200, status(ex, authed = true))
    }

    private fun accessLogin(ex: HttpExchange) {
        val hash = store.data.accessHash ?: return sendJson(ex, 409, error("Crie a senha de acesso primeiro."))
        if (throttle.isLocked()) return sendJson(ex, 429, error("Muitas tentativas erradas. Espere 5 minutos."))
        val password = body(ex).str("password").orEmpty()
        if (!Access.checkPassword(password, hash)) {
            throttle.recordFailure()
            return sendJson(ex, 401, error("Senha errada."))
        }
        throttle.recordSuccess()
        val token = Access.newSessionToken()
        // Guarda os 20 aparelhos mais recentes; um token esquecido num
        // navegador qualquer acaba saindo da lista sozinho.
        store.update { it.copy(sessions = (listOf(token) + it.sessions).take(20)) }
        setSessionCookie(ex, token)
        sendJson(ex, 200, status(ex, authed = true))
    }

    private fun status(ex: HttpExchange, authed: Boolean): JsonObject {
        val d = store.data
        return buildJsonObject {
            put("needsSetup", false)
            put("canSetup", false)
            put("authed", authed)
            put("loggedIn", d.credentials.isComplete)
            put("host", d.credentials.host)
            put("username", d.credentials.username)
            put("servers", strings(serversOf(d.servers, d.credentials)))
        }
    }

    private fun isAuthed(ex: HttpExchange): Boolean {
        val d = store.data
        if (d.accessHash == null) return false
        val token = cookie(ex, SESSION_COOKIE) ?: return false
        return token in d.sessions
    }

    private fun setSessionCookie(ex: HttpExchange, token: String) {
        ex.responseHeaders.add(
            "Set-Cookie",
            "$SESSION_COOKIE=$token; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax",
        )
    }

    // -------------------------------------------------------------------- API

    private fun api(ex: HttpExchange, name: String) {
        val d = store.data
        when (name) {
            "access/logout" -> {
                val token = cookie(ex, SESSION_COOKIE)
                store.update { it.copy(sessions = it.sessions - token.orEmpty()) }
                ex.responseHeaders.add("Set-Cookie", "$SESSION_COOKIE=; Path=/; Max-Age=0")
                return sendJson(ex, 200, buildJsonObject { put("ok", true) })
            }
            "login" -> return xtreamLogin(ex)
            "logout" -> {
                runBlocking { invalidateCatalog() }
                store.update { it.copy(credentials = Credentials.EMPTY) }
                return sendJson(ex, 200, status(ex, authed = true))
            }
            "free" -> return sendJson(ex, 200, buildJsonArray {
                FreeChannels.ALL.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id)
                        put("title", c.name)
                        c.logo?.let { put("image", it) }
                        put("source", c.sourceHost)
                    })
                }
            })
            "library" -> return sendJson(ex, 200, libraryJson())
            "favorites/toggle" -> {
                val o = body(ex)
                val key = o.str("key").orEmpty()
                val id = o.str("id")?.toIntOrNull()
                    ?: return sendJson(ex, 400, error("Item inválido."))
                val item = SavedItem(
                    key = key,
                    title = o.str("title").orEmpty().take(300).ifBlank { "Sem título" },
                    image = o.str("image")?.takeIf { it.startsWith("http") },
                    section = Section.fromName(o.str("section")),
                    id = id,
                    extension = o.str("ext")?.take(8),
                )
                // A chave é refeita aqui a partir de seção+id: a que veio do
                // navegador só vale se bater, para não gravar lixo no arquivo.
                val expected = when (item.section) {
                    Section.LIVE -> "live:$id"
                    Section.MOVIES -> "movie:$id"
                    Section.SERIES -> "series:$id"
                }
                if (key != expected) return sendJson(ex, 400, error("Item inválido."))
                store.update { d ->
                    d.copy(
                        favorites = if (d.favorites.any { it.key == key }) d.favorites.filterNot { it.key == key }
                        else d.favorites + item,
                    )
                }
                return sendJson(ex, 200, libraryJson())
            }
            "progress" -> {
                val o = body(ex)
                val src = o.str("src").orEmpty()
                // Só caminho de filme/episódio deste servidor: é o que o
                // "continuar assistindo" sabe tocar de volta.
                if (!src.startsWith("/stream/movie/") && !src.startsWith("/stream/series/")) {
                    return sendJson(ex, 400, error("Item inválido."))
                }
                val entry = ResumeEntry(
                    key = o.str("key").orEmpty().take(64),
                    title = o.str("title").orEmpty().take(300),
                    subtitle = o.str("subtitle")?.take(300),
                    image = o.str("image")?.takeIf { it.startsWith("http") },
                    streamPath = src.take(200),
                    positionMs = o.str("pos")?.toLongOrNull() ?: 0L,
                    durationMs = o.str("dur")?.toLongOrNull() ?: 0L,
                )
                if (entry.key.isBlank()) return sendJson(ex, 400, error("Item inválido."))
                // Mesma regra do app: menos de 1 min ou mais de 95% não guarda.
                store.update { it.copy(resume = PrefsCodec.withProgress(it.resume, entry)) }
                return sendJson(ex, 200, buildJsonObject { put("ok", true) })
            }
            "resume/clear" -> {
                store.update { it.copy(resume = emptyList()) }
                return sendJson(ex, 200, libraryJson())
            }
            "servers/add" -> {
                val parsed = PrefsCodec.parseServers(body(ex).str("text").orEmpty())
                if (parsed.isEmpty()) return sendJson(ex, 400, error("Nenhum endereço válido no que foi digitado."))
                store.update { it.copy(servers = PrefsCodec.withServers(it.servers, parsed)) }
                return sendJson(ex, 200, status(ex, authed = true))
            }
            "servers/remove" -> {
                val host = body(ex).str("host").orEmpty()
                if (host == d.credentials.host) return sendJson(ex, 400, error("Esse é o servidor em uso."))
                store.update { it.copy(servers = it.servers - host) }
                return sendJson(ex, 200, status(ex, authed = true))
            }
            "servers/test" -> {
                // Testa antes de trocar: um DNS fora do ar deixaria o site
                // inteiro sem catálogo, longe da escolha que causou o erro.
                val host = body(ex).str("host").orEmpty()
                val started = System.nanoTime()
                runBlocking { catalog.login(d.credentials.copy(host = host)) }
                return sendJson(ex, 200, buildJsonObject { put("ms", (System.nanoTime() - started) / 1_000_000) })
            }
            "servers/use" -> {
                val host = body(ex).str("host").orEmpty()
                runBlocking {
                    catalog.login(d.credentials.copy(host = host))
                    invalidateCatalog()
                }
                store.update { it.copy(credentials = it.credentials.copy(host = host)) }
                return sendJson(ex, 200, status(ex, authed = true))
            }
        }

        val creds = d.credentials
        if (!creds.isComplete) return sendJson(ex, 409, error("Entre com a conta IPTV primeiro."))
        val q = query(ex)
        when (name) {
            "categories" -> {
                val section = Section.fromName(q["section"])
                val list = runBlocking { catalog.categories(creds, section) }
                sendJson(ex, 200, buildJsonArray {
                    list.forEach { c -> add(buildJsonObject { put("id", c.id); put("name", c.name) }) }
                })
            }
            "items" -> {
                val section = Section.fromName(q["section"])
                val list = runBlocking { catalog.items(creds, section, q["category"].orEmpty()) }
                sendJson(ex, 200, JsonArray(list.map(::itemJson)))
            }
            "epg" -> {
                val id = q["id"]?.toIntOrNull() ?: return sendJson(ex, 400, error("Canal inválido."))
                val list = runBlocking { catalog.shortEpg(creds, id) }
                sendJson(ex, 200, buildJsonArray {
                    list.forEach { e ->
                        add(buildJsonObject {
                            put("title", e.title)
                            put("start", e.startEpochSecs)
                            put("end", e.endEpochSecs)
                        })
                    }
                })
            }
            "search" -> {
                val section = Section.fromName(q["section"])
                startIndex(section, creds)
                val results = runBlocking { catalog.searchCached(section, q["q"].orEmpty()) }
                val state = indexes[section]
                sendJson(ex, 200, buildJsonObject {
                    put("items", JsonArray(results.map(::itemJson)))
                    put("done", state?.done ?: 0)
                    put("total", state?.total ?: 0)
                    put("finished", state?.finished ?: false)
                })
            }
            "series" -> {
                val id = q["id"]?.toIntOrNull() ?: return sendJson(ex, 400, error("Série inválida."))
                val detail = runBlocking { catalog.seriesDetail(creds, id) }
                sendJson(ex, 200, buildJsonObject {
                    put("title", detail.series.title)
                    detail.series.image?.let { put("image", it) }
                    detail.series.plot?.let { put("plot", it) }
                    put("seasons", buildJsonArray {
                        detail.seasons.toSortedMap().forEach { (season, episodes) ->
                            add(buildJsonObject {
                                put("season", season)
                                put("episodes", buildJsonArray {
                                    episodes.forEach { e ->
                                        add(buildJsonObject {
                                            put("id", e.id)
                                            put("number", e.number)
                                            put("title", e.title)
                                            put("ext", e.extension)
                                            e.image?.let { put("image", it) }
                                        })
                                    }
                                })
                            })
                        }
                    })
                })
            }
            else -> sendJson(ex, 404, error("Rota desconhecida."))
        }
    }

    private fun startIndex(section: Section, creds: Credentials) {
        // putIfAbsent: duas TVs buscando ao mesmo tempo não disparam duas cargas.
        if (indexes.putIfAbsent(section, IndexState(0, 0, false)) != null) return
        val generation = indexGeneration
        Thread.ofVirtual().start {
            try {
                runBlocking {
                    catalog.loadSection(creds, section) { done, total ->
                        if (generation == indexGeneration) indexes[section] = IndexState(done, total, false)
                    }
                }
                if (generation == indexGeneration) {
                    val total = indexes[section]?.total ?: 0
                    indexes[section] = IndexState(total, total, true)
                }
            } catch (_: Exception) {
                // Painel caiu no meio: esquece o estado para a próxima busca tentar de novo.
                if (generation == indexGeneration) indexes.remove(section)
            }
        }
    }

    /** Trocou conta ou DNS: cache e índice da busca são do painel antigo. */
    private suspend fun invalidateCatalog() {
        indexGeneration++
        indexes.clear()
        catalog.invalidate()
    }

    private fun xtreamLogin(ex: HttpExchange) {
        val o = body(ex)
        val creds = Credentials(
            host = o.str("host").orEmpty().trim(),
            username = o.str("username").orEmpty().trim(),
            password = o.str("password").orEmpty().trim(),
        )
        if (!creds.isComplete) return sendJson(ex, 400, error("Preencha servidor, usuário e senha."))
        val normalized = XtreamUrls.normalizeHost(creds.host)
            ?: return sendJson(ex, 400, error("Endereço do servidor inválido."))
        // Valida contra o painel antes de salvar, como o app de TV.
        runBlocking {
            catalog.login(creds)
            invalidateCatalog()
        }
        val saved = creds.copy(host = XtreamUrls.displayHost(normalized))
        store.update { it.copy(credentials = saved, servers = PrefsCodec.withServers(it.servers, listOf(saved.host))) }
        sendJson(ex, 200, status(ex, authed = true))
    }

    private fun libraryJson(): JsonObject {
        val d = store.data
        return buildJsonObject {
            put("favorites", buildJsonArray {
                d.favorites.forEach { f ->
                    add(buildJsonObject {
                        put("key", f.key)
                        put("title", f.title)
                        f.image?.let { put("image", it) }
                        put("section", f.section.name)
                        put("id", f.id)
                        f.extension?.let { put("ext", it) }
                    })
                }
            })
            put("resume", buildJsonArray {
                d.resume.forEach { r ->
                    add(buildJsonObject {
                        put("key", r.key)
                        put("title", r.title)
                        r.subtitle?.let { put("subtitle", it) }
                        r.image?.let { put("image", it) }
                        put("src", r.streamPath)
                        put("pos", r.positionMs)
                        put("dur", r.durationMs)
                    })
                }
            })
        }
    }

    private fun itemJson(item: CatalogItem): JsonObject = buildJsonObject {
        put("key", item.key)
        put("title", item.title)
        item.image?.let { put("image", it) }
        when (item) {
            is Channel -> {
                put("id", item.streamId)
                item.number?.let { put("number", it) }
            }
            is Movie -> {
                put("id", item.streamId)
                put("ext", item.extension)
            }
            is SeriesItem -> put("id", item.seriesId)
        }
    }

    // ------------------------------------------------------------------ vídeo

    /** `live/123.m3u8`, `live/123.ts`, `movie/45.mkv`, `series/678.mp4`, `free/tvbrasil.m3u8`. */
    private fun stream(ex: HttpExchange, rest: String) {
        val kind = rest.substringBefore('/')
        val file = rest.substringAfter('/', "")
        val id = file.substringBeforeLast('.')
        val ext = file.substringAfterLast('.', "")
        val creds = store.data.credentials
        val url = when (kind) {
            "free" -> FreeChannels.byId(id)?.url
            "live" -> id.toIntOrNull()?.let {
                XtreamUrls.live(creds, it, if (ext == "ts") LiveFormat.TS else LiveFormat.HLS)
            }
            "movie" -> id.toIntOrNull()?.let { XtreamUrls.movie(creds, it, ext) }
            "series" -> XtreamUrls.episode(creds, id, ext)
            else -> null
        } ?: return sendText(ex, 404, "Vídeo não encontrado.")
        proxy(ex, url)
    }

    private fun hls(ex: HttpExchange) {
        val q = query(ex)
        val u = q["u"].orEmpty()
        val s = q["s"].orEmpty()
        if (!Access.verify(store.data.secret, u, s)) return sendText(ex, 403, "Link sem assinatura válida.")
        if (!u.startsWith("http://") && !u.startsWith("https://")) return sendText(ex, 400, "Link inválido.")
        proxy(ex, u)
    }

    /**
     * Busca no servidor do vídeo e devolve ao navegador.
     *
     * Playlist HLS é reescrita (ver [HlsRewrite]); o resto — segmentos, .ts ao
     * vivo, arquivo de filme — passa direto, em pedaços de 64 KB, sem guardar
     * nada em memória. O cabeçalho Range vai e volta: é ele que deixa pular
     * para o meio do filme sem baixar tudo antes.
     */
    private fun proxy(ex: HttpExchange, url: String) {
        val request = Request.Builder().url(url)
            .apply { ex.requestHeaders.getFirst("Range")?.let { header("Range", it) } }
            .build()
        streamHttp.newCall(request).execute().use { up ->
            if (!up.isSuccessful) return sendText(ex, up.code, "O servidor do vídeo respondeu ${up.code}.")
            val finalUrl = up.request.url
            val type = up.header("Content-Type").orEmpty()
            val isPlaylist = type.contains("mpegurl", ignoreCase = true) ||
                finalUrl.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
                up.peekBody(7).string().startsWith("#EXTM3U")
            ex.responseHeaders.set("Cache-Control", "no-store")
            if (isPlaylist) {
                val secret = store.data.secret
                val text = HlsRewrite.rewrite(up.body.string(), finalUrl) { absolute ->
                    "/hls?u=" + URLEncoder.encode(absolute, Charsets.UTF_8) + "&s=" + Access.sign(secret, absolute)
                }
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                return sendBytes(ex, 200, text.toByteArray())
            }
            listOf("Content-Type", "Content-Range", "Accept-Ranges").forEach { h ->
                up.header(h)?.let { ex.responseHeaders.set(h, it) }
            }
            val length = up.body.contentLength()
            // Para o HttpServer: 0 = tamanho desconhecido (chunked), -1 = sem corpo.
            ex.sendResponseHeaders(up.code, if (length > 0) length else if (length == 0L) -1 else 0)
            if (length != 0L) {
                up.body.byteStream().use { input -> ex.responseBody.use { input.copyTo(it, 64 * 1024) } }
            }
        }
    }

    // ---------------------------------------------------------------- miúdos

    /**
     * Cabeçalhos de proteção em toda resposta:
     *  - CSP: só roda script deste servidor (ver abaixo);
 *  - frame-ancestors/X-Frame-Options: outro site não consegue abrir o
     *    Ranga escondido dentro dele e induzir cliques;
     *  - nosniff: o navegador não "adivinha" tipo de arquivo;
     *  - no-referrer: as capas vêm de servidores de fora, e sem isto cada
     *    imagem contaria a eles o endereço do seu Ranga (o do playit, inclusive).
     */
    private fun securityHeaders(ex: HttpExchange) {
        ex.responseHeaders.set("X-Frame-Options", "DENY")
        // script-src 'self': a página só roda o código que sai deste servidor.
        // Nome de canal vindo do painel nunca vira código, mesmo que alguém
        // ponha HTML nele. As bibliotecas de vídeo moram em /lib por isso, e
        // não num CDN. Imagens podem vir de qualquer lugar (capas do painel);
        // blob: é como o hls.js entrega o vídeo ao <video>.
        ex.responseHeaders.set(
            "Content-Security-Policy",
            "default-src 'self'; img-src * data:; media-src 'self' blob:; " +
                "worker-src 'self' blob:; connect-src 'self'; style-src 'self'; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )
        ex.responseHeaders.set("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.set("Referrer-Policy", "no-referrer")
    }

    /**
     * POST só da própria página. O cookie SameSite=Lax já barra a maior parte,
     * e isto fecha o resto: uma página maliciosa aberta na mesma TV não
     * consegue mandar "trocar DNS" ou "sair" em seu nome.
     */
    private fun sameOrigin(ex: HttpExchange): Boolean {
        val origin = ex.requestHeaders.getFirst("Origin") ?: return true
        val host = ex.requestHeaders.getFirst("Host") ?: return false
        return origin.substringAfter("://") == host
    }

    private fun serversOf(saved: List<String>, creds: Credentials): List<String> =
        PrefsCodec.withServers(saved, listOfNotNull(creds.host.takeIf { it.isNotBlank() }))

    private fun static(ex: HttpExchange, name: String, type: String) {
        val bytes = synchronized(staticCache) {
            staticCache.getOrPut(name) {
                javaClass.getResourceAsStream("/web/$name")?.use { it.readBytes() } ?: ByteArray(0)
            }
        }
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Cache-Control", "no-cache")
        sendBytes(ex, 200, bytes)
    }

    private fun body(ex: HttpExchange): JsonObject {
        // 64 KB sobra para qualquer formulário daqui; o limite evita que um
        // pedido gigante encha a memória do PC.
        val raw = ex.requestBody.readNBytes(64 * 1024).decodeToString()
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    private fun cookie(ex: HttpExchange, name: String): String? =
        ex.requestHeaders["Cookie"].orEmpty()
            .flatMap { it.split(';') }
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }

    /** Endereço digitado é localhost ou IP de rede doméstica (192.168, 10, 172.16–31). */
    private fun isHomeNetwork(ex: HttpExchange): Boolean {
        val host = ex.requestHeaders.getFirst("Host").orEmpty().let {
            if (it.startsWith("[")) it.substringBefore(']').removePrefix("[") else it.substringBefore(':')
        }.lowercase()
        if (host == "localhost" || host == "::1" || host.startsWith("127.")) return true
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }

    private fun strings(list: List<String>) = JsonArray(list.map { JsonPrimitive(it) })

    private fun error(message: String) = buildJsonObject { put("error", message) }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun sendJson(ex: HttpExchange, code: Int, body: JsonElement) {
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-store")
        sendBytes(ex, code, body.toString().toByteArray())
    }

    private fun sendText(ex: HttpExchange, code: Int, text: String) {
        ex.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        sendBytes(ex, code, text.toByteArray())
    }

    private fun sendBytes(ex: HttpExchange, code: Int, bytes: ByteArray) {
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val SESSION_COOKIE = "ranga_sessao"
    }
}
