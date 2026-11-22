package br.com.iordan.ranga.pc

import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.Credentials
import br.com.iordan.ranga.data.LiveFormat
import br.com.iordan.ranga.data.PrefsCodec
import br.com.iordan.ranga.data.RangaDefaults
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.data.SavedItem
import br.com.iordan.ranga.pc.player.VideoMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class SettingsData(
    val credentials: Credentials = Credentials.EMPTY,
    /** Endereços (DNS) cadastrados da conta; o em uso é `credentials.host`. */
    val savedServers: List<String> = emptyList(),
    val liveFormat: LiveFormat = LiveFormat.HLS,
    val userAgent: String = RangaDefaults.USER_AGENT,
    val videoMode: VideoMode = VideoMode.OVERLAY,
    val favorites: List<SavedItem> = emptyList(),
    val resume: List<ResumeEntry> = emptyList(),
    /** "pt", "en", "ru" ou "es"; null: o do Windows (ui/Lingua.kt). */
    val language: String? = null,
) {
    val favoriteKeys: Set<String> get() = favorites.map { it.key }.toSet()

    /**
     * Lista para a tela, com o endereço em uso sempre dentro: quem entrou
     * antes de a lista existir tem só `host`, e veria "nenhum servidor" numa
     * conta que funciona.
     */
    val servers: List<String>
        get() = PrefsCodec.withServers(savedServers, listOfNotNull(credentials.host.takeIf { it.isNotBlank() }))
}

/**
 * Persistência no Windows: um `settings.json` em `%APPDATA%\Ranga\`.
 *
 * É o equivalente do `SessionStore` do Android — mesma responsabilidade, mesmo
 * formato de dados (o `PrefsCodec` do core é literalmente o mesmo código), só
 * muda o meio. Não uso `java.util.prefs` (Registro do Windows) porque a lista
 * de favoritos passa fácil do limite de 8 KB por chave que ele impõe.
 *
 * O estado inteiro vive em memória num [StateFlow] e é gravado a cada mudança.
 * O arquivo é pequeno (KB) e as mudanças são raras — favoritar, pausar um
 * filme — então não vale a complexidade de gravação assíncrona com debounce.
 */
class Settings(private val file: Path = defaultFile()) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsData> = _state.asStateFlow()

    val current: SettingsData get() = _state.value

    fun saveCredentials(credentials: Credentials) = mutate {
        it.copy(
            credentials = credentials,
            savedServers = PrefsCodec.withServers(it.savedServers, listOf(credentials.host)),
        )
    }

    fun addServers(hosts: List<String>) = mutate {
        it.copy(savedServers = PrefsCodec.withServers(it.savedServers, hosts))
    }

    fun removeServer(host: String) = mutate { it.copy(savedServers = it.savedServers - host) }

    /** Troca só o endereço: usuário e senha são os mesmos em todos os DNS. */
    fun setHost(host: String) = mutate { it.copy(credentials = it.credentials.copy(host = host)) }

    fun clearCredentials() = mutate { it.copy(credentials = Credentials.EMPTY) }

    fun setLiveFormat(format: LiveFormat) = mutate { it.copy(liveFormat = format) }

    fun setUserAgent(value: String) = mutate {
        it.copy(userAgent = value.trim().ifEmpty { RangaDefaults.USER_AGENT })
    }

    fun setVideoMode(mode: VideoMode) = mutate { it.copy(videoMode = mode) }

    fun toggleFavorite(item: CatalogItem) = mutate {
        it.copy(favorites = PrefsCodec.toggleFavorite(it.favorites, item))
    }

    fun saveProgress(entry: ResumeEntry) = mutate {
        it.copy(resume = PrefsCodec.withProgress(it.resume, entry))
    }

    fun clearResume() = mutate { it.copy(resume = emptyList()) }

    fun setLanguage(code: String) = mutate { it.copy(language = code) }

    fun resumeMsFor(key: String): Long =
        _state.value.resume.firstOrNull { it.key == key }?.positionMs ?: 0L

    private fun mutate(block: (SettingsData) -> SettingsData) {
        _state.update(block)
        write(_state.value)
    }

    // ------------------------------------------------------------------ disco

    private fun read(): SettingsData {
        val raw = runCatching { Files.readString(file) }.getOrNull() ?: return SettingsData()
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return SettingsData()
        return SettingsData(
            credentials = Credentials(
                host = root.str("host").orEmpty(),
                username = root.str("username").orEmpty(),
                password = root.str("password").orEmpty(),
            ),
            savedServers = PrefsCodec.decodeServers(root["servers"]?.toString()),
            liveFormat = LiveFormat.entries.firstOrNull { it.name == root.str("liveFormat") }
                ?: LiveFormat.HLS,
            userAgent = root.str("userAgent") ?: RangaDefaults.USER_AGENT,
            videoMode = VideoMode.entries.firstOrNull { it.name == root.str("videoMode") }
                ?: VideoMode.OVERLAY,
            favorites = PrefsCodec.decodeFavorites(root["favorites"]?.toString()),
            resume = PrefsCodec.decodeResume(root["resume"]?.toString()),
            language = root.str("language"),
        )
    }

    private fun write(data: SettingsData) {
        runCatching {
            Files.createDirectories(file.parent)
            val text = buildJsonObject {
                put("host", data.credentials.host)
                put("username", data.credentials.username)
                put("password", data.credentials.password)
                put("servers", json.parseToJsonElement(PrefsCodec.encodeServers(data.savedServers)))
                put("liveFormat", data.liveFormat.name)
                put("userAgent", data.userAgent)
                put("videoMode", data.videoMode.name)
                data.language?.let { put("language", it) }
                // Reaproveito o codec do core e re-parseio para JsonElement, em
                // vez de enfiar a string escapada. Custa microssegundos e deixa
                // o settings.json legível se você abrir no Bloco de Notas.
                put("favorites", json.parseToJsonElement(PrefsCodec.encodeFavorites(data.favorites)))
                put("resume", json.parseToJsonElement(PrefsCodec.encodeResume(data.resume)))
            }

            // Grava em arquivo temporário e move por cima: se o app for fechado
            // no meio da escrita, você perde a última alteração em vez de ficar
            // com um settings.json truncado que não abre mais.
            val temp = file.resolveSibling("settings.json.tmp")
            Files.writeString(temp, json.encodeToString(JsonObject.serializer(), text))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        fun defaultFile(): Path {
            val base = System.getenv("APPDATA")
                ?: System.getProperty("user.home")
            return Path.of(base, "Ranga", "settings.json")
        }
    }
}
