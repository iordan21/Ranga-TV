package br.com.iordan.ranga

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider

import androidx.lifecycle.viewmodel.CreationExtras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class RangaApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Mantém o holder de User-Agent sincronizado com o que está salvo.
        // Sem isto, trocar o UA em Configurações só valeria no próximo boot.
        container.session.userAgent
            .onEach { container.userAgent.value = it }
            .launchIn(appScope)
    }

    /**
     * Coil 3 não embute cliente de rede — é preciso registrar um fetcher.
     * Reaproveito o mesmo OkHttpClient da API para que as capas/logos saiam
     * com o mesmo User-Agent (alguns painéis servem imagem do mesmo host que
     * filtra UA) e compartilhem o pool de conexões.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttp })) }
            .crossfade(true)
            .build()
}

/** Atalho para pegar o container de dentro de um Composable ou Activity. */
val Context.container: AppContainer
    get() = (applicationContext as RangaApp).container

/**
 * Injeção de construtor em ViewModel sem biblioteca nenhuma: o
 * `viewModelFactory { initializer { ... } }` recebe [CreationExtras], de onde
 * saco a Application e, dela, o container.
 */
val CreationExtras.appContainer: AppContainer
    get() = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
        .let { (it as RangaApp).container }
