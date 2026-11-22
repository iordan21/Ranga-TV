package br.com.iordan.ranga

import android.content.Context
import br.com.iordan.ranga.data.Catalog
import br.com.iordan.ranga.data.RangaDefaults
import br.com.iordan.ranga.data.SessionStore
import br.com.iordan.ranga.data.XtreamApi
import br.com.iordan.ranga.player.PlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Injeção de dependência feita à mão.
 *
 * Poderia ser Hilt, mas para um app deste tamanho o Hilt custa caro: exige o
 * plugin KSP, mais uma versão para casar com o Kotlin do AGP, e espalha
 * `@Inject`/`@Module` em vinte arquivos. Aqui o grafo inteiro cabe numa tela e
 * você lê de cima a baixo quem depende de quem. Se o app crescer, migrar para
 * Hilt depois é mecânico.
 */
class AppContainer(context: Context) {

    /**
     * O User-Agent é configurável em runtime, mas o OkHttpClient é único
     * (criar vários desperdiça o pool de conexões). Solução: um holder
     * volátil que o interceptor lê a cada requisição.
     */
    val userAgent = MutableUserAgent()

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent.value)
                .build()
            chain.proceed(request)
        })
        .build()

    val session = SessionStore(context.applicationContext)
    private val api = XtreamApi(okHttp)
    val catalog = Catalog(api)
    val queue = PlaybackQueue()

    /**
     * Escopo com tempo de vida do processo.
     *
     * Existe para um caso específico: gravar a posição do vídeo em
     * `onDispose` do player. Nesse instante o escopo da tela já foi cancelado,
     * então uma corrotina lançada nele morreria antes de escrever no disco — e
     * o "continuar assistindo" simplesmente não gravaria ao sair.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launchDetached(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}

class MutableUserAgent {
    @Volatile
    var value: String = RangaDefaults.USER_AGENT
}
