package br.com.iordan.ranga.web

import br.com.iordan.ranga.data.Catalog
import br.com.iordan.ranga.data.RangaDefaults
import br.com.iordan.ranga.data.XtreamApi
import com.sun.net.httpserver.HttpServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.BindException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ranga Web: o PC vira o servidor, e qualquer navegador da casa vira o player.
 *
 * Existe por causa da Fire TV com Vega OS, que não instala APK mas tem
 * navegador. O PC faz o que o navegador não pode: conversar com o painel
 * IPTV (que não libera acesso de páginas de outro domínio) e repassar o vídeo.
 *
 * Uso: `ranga-web [porta]`. Padrão 8088.
 */
fun main(args: Array<String>) {
    val wanted = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
    if (alreadyRunning(wanted)) {
        println("O Ranga Web já está ligado em http://localhost:$wanted — nada a fazer.")
        return
    }

    val apiHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", RangaDefaults.USER_AGENT).build())
        })
        .build()
    // Para vídeo, sem callTimeout: um canal ao vivo é uma resposta que nunca
    // acaba, e o limite de 60 s derrubaria todo canal no primeiro minuto.
    val streamHttp = apiHttp.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()

    val routes = Routes(WebStore(), Catalog(XtreamApi(apiHttp)), streamHttp)

    val server = bindFirstFree(wanted) ?: run {
        println("Nenhuma porta livre entre $wanted e ${wanted + PORT_TRIES - 1}.")
        println("Feche o programa que está usando essas portas, ou rode: RangaWeb.bat 9000")
        return
    }
    val port = server.address.port
    if (port != wanted) println("A porta $wanted estava ocupada por outro programa; usando a $port.")
    // Uma thread virtual por conexão: cada vídeo tocando segura uma conexão
    // aberta enquanto durar, e thread virtual parada esperando rede é de graça.
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    server.createContext("/") { routes.handle(it) }
    server.start()

    println("Ranga Web rodando.")
    println("  Neste PC:        http://localhost:$port")
    lanAddresses().forEach { println("  Na rede de casa: http://$it:$port") }
    println("Feche esta janela para desligar.")
}

/**
 * 8088, e não 8080: neste PC o NVIDIA Broadcast já ocupa a 8080 (o servidor
 * do NFS World esbarrou no mesmo). E se a escolhida também estiver ocupada,
 * tenta as seguintes em vez de morrer com BindException na cara do usuário.
 */
private const val DEFAULT_PORT = 8088
private const val PORT_TRIES = 10

/**
 * Ligado sozinho com o Windows e depois aberto de novo pelo RangaWeb.bat, sem
 * esta checagem ele subiria uma segunda cópia na porta seguinte — e o túnel
 * do playit, que aponta para a 8088, continuaria falando com a primeira.
 */
private fun alreadyRunning(port: Int): Boolean = runCatching {
    val conn = URI("http://127.0.0.1:$port/api/status").toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = 1500
    conn.readTimeout = 1500
    conn.inputStream.use { it.readBytes().decodeToString() }.contains("needsSetup")
}.getOrDefault(false)

private fun bindFirstFree(start: Int): HttpServer? {
    for (port in start until start + PORT_TRIES) {
        try {
            return HttpServer.create(InetSocketAddress(port), 0)
        } catch (_: BindException) {
            // Ocupada; tenta a próxima.
        }
    }
    return null
}

/** IPv4 de rede local, para mostrar o endereço que a TV deve abrir. */
private fun lanAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress }
        .sorted()
