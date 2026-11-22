package br.com.iordan.ranga.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ranga")

/**
 * Persistência no Android.
 *
 * Este arquivo é só o *onde*: DataStore, no diretório privado do app. O *o quê*
 * (formato do JSON, regra dos 95% do "continuar assistindo", presets de
 * User-Agent) mora em `core/…/Storage.kt`, compartilhado com o app de Windows.
 * O equivalente lá é um arquivo em %APPDATA%, e é a única diferença.
 *
 * Sobre guardar a senha: fica em `/data/data/br.com.iordan.ranga/`, que outro
 * app só lê com root — mesmo nível do IPTV Smarters e afins. Não usei
 * EncryptedSharedPreferences porque a lib está depreciada e a chave ficaria no
 * mesmo aparelho, então o ganho real seria pequeno. O que importa mais: nada
 * disso está no código-fonte.
 */
class SessionStore(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val SERVERS = stringPreferencesKey("servers_json")
        val LIVE_FORMAT = stringPreferencesKey("live_format")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val FAVORITES = stringPreferencesKey("favorites_json")
        val RESUME = stringPreferencesKey("resume_json")
    }

    /** `catch` porque leitura de DataStore pode falhar por I/O — melhor cair no default. */
    private val prefs: Flow<Preferences> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    // ------------------------------------------------------------ credenciais

    val credentials: Flow<Credentials> = prefs.map {
        Credentials(
            host = it[Keys.HOST].orEmpty(),
            username = it[Keys.USERNAME].orEmpty(),
            password = it[Keys.PASSWORD].orEmpty(),
        )
    }

    suspend fun saveCredentials(creds: Credentials) {
        context.dataStore.edit {
            it[Keys.HOST] = creds.host
            it[Keys.USERNAME] = creds.username
            it[Keys.PASSWORD] = creds.password
            it[Keys.SERVERS] = PrefsCodec.encodeServers(
                PrefsCodec.withServers(PrefsCodec.decodeServers(it[Keys.SERVERS]), listOf(creds.host))
            )
        }
    }

    /**
     * Logout: some credencial, mas preserva favoritos, progresso e a lista de
     * DNS — ela não é segredo, e quem sai para trocar a senha volta para o
     * mesmo provedor.
     */
    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(Keys.HOST)
            it.remove(Keys.USERNAME)
            it.remove(Keys.PASSWORD)
        }
    }

    // ------------------------------------------------------------ servidores (DNS)

    /**
     * Todos os endereços conhecidos da conta, o atual incluído.
     *
     * O atual entra mesmo sem estar gravado na lista: quem fez login antes de
     * a lista existir (Ranga 1.1) tem só `host`, e a tela mostraria "nenhum
     * servidor" para uma conta que funciona.
     */
    val servers: Flow<List<String>> = prefs.map { p ->
        PrefsCodec.withServers(
            PrefsCodec.decodeServers(p[Keys.SERVERS]),
            listOfNotNull(p[Keys.HOST]?.takeIf { it.isNotBlank() }),
        )
    }

    suspend fun addServers(hosts: List<String>) {
        context.dataStore.edit {
            it[Keys.SERVERS] = PrefsCodec.encodeServers(
                PrefsCodec.withServers(PrefsCodec.decodeServers(it[Keys.SERVERS]), hosts)
            )
        }
    }

    suspend fun removeServer(host: String) {
        context.dataStore.edit {
            it[Keys.SERVERS] = PrefsCodec.encodeServers(
                PrefsCodec.decodeServers(it[Keys.SERVERS]).filterNot { saved -> saved == host }
            )
        }
    }

    /** Troca só o endereço: usuário e senha são os mesmos em todos os DNS. */
    suspend fun setHost(host: String) {
        context.dataStore.edit { it[Keys.HOST] = host }
    }

    // ------------------------------------------------------------ preferências

    val liveFormat: Flow<LiveFormat> = prefs.map { p ->
        LiveFormat.entries.firstOrNull { it.name == p[Keys.LIVE_FORMAT] } ?: LiveFormat.HLS
    }

    suspend fun setLiveFormat(format: LiveFormat) {
        context.dataStore.edit { it[Keys.LIVE_FORMAT] = format.name }
    }

    val userAgent: Flow<String> = prefs.map { it[Keys.USER_AGENT] ?: RangaDefaults.USER_AGENT }

    suspend fun setUserAgent(value: String) {
        context.dataStore.edit {
            it[Keys.USER_AGENT] = value.trim().ifEmpty { RangaDefaults.USER_AGENT }
        }
    }

    // -------------------------------------------------------------- favoritos

    val favorites: Flow<List<SavedItem>> = prefs.map { PrefsCodec.decodeFavorites(it[Keys.FAVORITES]) }

    val favoriteKeys: Flow<Set<String>> = favorites.map { list -> list.map { it.key }.toSet() }

    suspend fun toggleFavorite(item: CatalogItem) {
        context.dataStore.edit { p ->
            val current = PrefsCodec.decodeFavorites(p[Keys.FAVORITES])
            p[Keys.FAVORITES] = PrefsCodec.encodeFavorites(PrefsCodec.toggleFavorite(current, item))
        }
    }

    // ------------------------------------------------- continuar assistindo

    val resume: Flow<List<ResumeEntry>> = prefs.map { PrefsCodec.decodeResume(it[Keys.RESUME]) }

    suspend fun saveProgress(entry: ResumeEntry) {
        context.dataStore.edit { p ->
            val current = PrefsCodec.decodeResume(p[Keys.RESUME])
            p[Keys.RESUME] = PrefsCodec.encodeResume(PrefsCodec.withProgress(current, entry))
        }
    }

    suspend fun clearResume() {
        context.dataStore.edit { it.remove(Keys.RESUME) }
    }
}
