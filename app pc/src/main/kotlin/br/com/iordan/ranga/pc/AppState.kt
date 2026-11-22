package br.com.iordan.ranga.pc

import br.com.iordan.ranga.data.Catalog
import br.com.iordan.ranga.data.XtreamApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Grafo de dependências do app de Windows.
 *
 * É o gêmeo do `AppContainer` do Android: mesma ideia (DI à mão, tudo numa
 * tela), mesmas peças do core. As únicas diferenças são a persistência
 * ([Settings] em vez de DataStore) e a ausência de `Context`.
 */
class AppState {

    val settings = Settings()

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(Interceptor { chain ->
            // Lê do Settings a cada requisição em vez de fixar no build do
            // cliente: trocar o User-Agent nas configurações passa a valer na
            // hora, sem reiniciar o app.
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", settings.current.userAgent)
                    .build()
            )
        })
        .build()

    private val api = XtreamApi(okHttp)
    val catalog = Catalog(api)
}
