package br.com.iordan.ranga.web

import br.com.iordan.ranga.data.Credentials
import br.com.iordan.ranga.data.PrefsCodec
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.data.SavedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class WebData(
    val credentials: Credentials = Credentials.EMPTY,
    val servers: List<String> = emptyList(),
    /** Hash da senha de acesso ao site; null = ainda não criada. */
    val accessHash: String? = null,
    /** Chave das assinaturas de link. Gerada uma vez, nunca sai do PC. */
    val secret: String = Access.newSecret(),
    /** Aparelhos que já entraram. Guardado para a TV não pedir senha a cada boot do PC. */
    val sessions: List<String> = emptyList(),
    /** Mesmo formato do app (PrefsCodec), guardado no PC: vale para todo aparelho da casa. */
    val favorites: List<SavedItem> = emptyList(),
    val resume: List<ResumeEntry> = emptyList(),
)

/**
 * `%APPDATA%\Ranga\web.json`. Arquivo separado do `settings.json` do app de
 * Windows de propósito: os dois podem estar abertos ao mesmo tempo, e quem
 * gravasse por último apagaria o favorito que o outro acabou de salvar.
 */
class WebStore(private val file: Path = defaultFile()) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    @Volatile
    var data: WebData = read().also { write(it) }
        private set

    @Synchronized
    fun update(block: (WebData) -> WebData) {
        data = block(data)
        write(data)
    }

    private fun read(): WebData {
        val raw = runCatching { Files.readString(file) }.getOrNull() ?: return WebData()
        val o = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return WebData()
        return WebData(
            credentials = Credentials(o.str("host").orEmpty(), o.str("username").orEmpty(), o.str("password").orEmpty()),
            servers = PrefsCodec.decodeServers(o["servers"]?.toString()),
            accessHash = o.str("accessHash"),
            secret = o.str("secret") ?: Access.newSecret(),
            sessions = (o["sessions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            favorites = PrefsCodec.decodeFavorites(o["favorites"]?.toString()),
            resume = PrefsCodec.decodeResume(o["resume"]?.toString()),
        )
    }

    private fun write(d: WebData) {
        runCatching {
            Files.createDirectories(file.parent)
            val text = buildJsonObject {
                put("host", d.credentials.host)
                put("username", d.credentials.username)
                put("password", d.credentials.password)
                put("servers", json.parseToJsonElement(PrefsCodec.encodeServers(d.servers)))
                d.accessHash?.let { put("accessHash", it) }
                put("secret", d.secret)
                put("sessions", buildJsonArray { d.sessions.forEach { add(JsonPrimitive(it)) } })
                put("favorites", json.parseToJsonElement(PrefsCodec.encodeFavorites(d.favorites)))
                put("resume", json.parseToJsonElement(PrefsCodec.encodeResume(d.resume)))
            }
            // Temporário + move: fechar o servidor no meio da escrita não
            // deixa um web.json pela metade.
            val temp = file.resolveSibling("web.json.tmp")
            Files.writeString(temp, json.encodeToString(JsonObject.serializer(), text))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        /** RANGA_WEB_DATA troca o arquivo — para testar sem mexer na conta de verdade. */
        fun defaultFile(): Path =
            System.getenv("RANGA_WEB_DATA")?.let { Path.of(it) }
                ?: Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Ranga", "web.json")
    }
}
