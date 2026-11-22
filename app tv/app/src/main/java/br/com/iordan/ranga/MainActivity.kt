package br.com.iordan.ranga

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.Channel
import br.com.iordan.ranga.data.FreeChannel
import br.com.iordan.ranga.data.Movie
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.data.SeriesItem
import br.com.iordan.ranga.data.toPlayable
import br.com.iordan.ranga.player.PlayerActivity
import br.com.iordan.ranga.ui.browse.CategoryScreen
import br.com.iordan.ranga.ui.browse.CategoryViewModel
import br.com.iordan.ranga.ui.browse.HomeScreen
import br.com.iordan.ranga.ui.detail.MovieDetailScreen
import br.com.iordan.ranga.ui.detail.MovieDetailViewModel
import br.com.iordan.ranga.ui.detail.SeriesDetailScreen
import br.com.iordan.ranga.ui.detail.SeriesDetailViewModel
import br.com.iordan.ranga.ui.login.LoginScreen
import br.com.iordan.ranga.ui.settings.SettingsScreen
import br.com.iordan.ranga.ui.theme.RangaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RangaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainNavHost()
                }
            }
        }
    }
}

/**
 * O app abre direto na Home, com ou sem conta.
 *
 * O login já foi um *estado* que embrulhava a árvore inteira: sem credencial,
 * só a tela de login existia. Isso deixou de servir quando a aba Grátis passou
 * a funcionar sem conta — a Home precisa existir antes de qualquer login. Então
 * o login virou rota comum, aberta pelo convite nas abas do Xtream ou pelas
 * Configurações, e a Home observa a credencial para reagir a entrar e sair.
 */
@Composable
private fun MainNavHost() {
    val navController = rememberNavController()
    val play = rememberPlayLauncher()
    val openItems = rememberItemOpener(navController, play)

    NavHost(navController = navController, startDestination = ROUTE_HOME) {

        composable(ROUTE_HOME) {
            HomeScreen(
                onOpenCategory = { section, category ->
                    navController.navigate("category/${section.name}/${category.id}")
                },
                onOpenItems = openItems,
                onOpenFree = { channels, index -> play.free(channels, index) },
                onResume = { entry -> play.resume(entry) },
                onLogin = { navController.navigate(ROUTE_LOGIN) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }

        composable(ROUTE_LOGIN) {
            // Entrou ou desistiu: nos dois casos volta para onde estava. Quem
            // reage ao login é a Home, observando a credencial salva.
            LoginScreen(
                onLoggedIn = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "category/{${CategoryViewModel.ARG_SECTION}}/{${CategoryViewModel.ARG_CATEGORY_ID}}",
            arguments = listOf(
                navArgument(CategoryViewModel.ARG_SECTION) { type = NavType.StringType },
                navArgument(CategoryViewModel.ARG_CATEGORY_ID) { type = NavType.StringType },
            ),
        ) {
            CategoryScreen(
                onBack = { navController.popBackStack() },
                onOpenItems = openItems,
            )
        }

        composable(
            route = "movie/{${MovieDetailViewModel.ARG_STREAM_ID}}",
            arguments = listOf(
                navArgument(MovieDetailViewModel.ARG_STREAM_ID) { type = NavType.StringType },
            ),
        ) {
            MovieDetailScreen(
                onBack = { navController.popBackStack() },
                onPlay = { playable -> play.single(playable) },
            )
        }

        composable(
            route = "series/{${SeriesDetailViewModel.ARG_SERIES_ID}}",
            arguments = listOf(
                navArgument(SeriesDetailViewModel.ARG_SERIES_ID) { type = NavType.StringType },
            ),
        ) {
            SeriesDetailScreen(
                onBack = { navController.popBackStack() },
                onPlay = { queue, index -> play.queue(queue, index) },
            )
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLogin = { navController.navigate(ROUTE_LOGIN) },
                // Sair da conta volta para a Home, que já trocou para a aba
                // Grátis ao ver a credencial sumir.
                onLoggedOut = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Abre um item de uma lista.
 *
 * Filme e série vão para a tela de detalhe. Canal ao vivo vai direto para o
 * player — e leva junto **todos os canais visíveis na lista**, para que o
 * zapping com o direcional ande dentro da categoria que você estava vendo.
 */
@Composable
private fun rememberItemOpener(
    navController: NavHostController,
    play: PlayLauncher,
): (List<CatalogItem>, Int) -> Unit = remember(navController, play) {
    { items: List<CatalogItem>, index: Int ->
        when (val item = items.getOrNull(index)) {
            is Movie -> navController.navigate("movie/${item.streamId}")
            is SeriesItem -> navController.navigate("series/${item.seriesId}")
            is Channel -> play.channels(items, item)
            null -> Unit
        }
    }
}

@Composable
private fun rememberPlayLauncher(): PlayLauncher {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    return remember(context) { PlayLauncher(context, app, scope) }
}

private class PlayLauncher(
    private val context: Context,
    private val app: AppContainer,
    private val scope: CoroutineScope,
) {
    fun single(playable: Playable) = queue(listOf(playable), 0)

    fun queue(items: List<Playable>, index: Int) =
        PlayerActivity.launch(context, app.queue, items, index)

    /** Canal aberto não depende de credencial nem de formato: a URL já é a final. */
    fun free(channels: List<FreeChannel>, index: Int) =
        queue(channels.map { it.toPlayable() }, index)

    fun resume(entry: ResumeEntry) {
        scope.launch {
            val creds = app.session.credentials.first()
            entry.toPlayable(creds)?.let(::single)
        }
    }

    fun channels(items: List<CatalogItem>, selected: Channel) {
        scope.launch {
            val creds = app.session.credentials.first()
            val format = app.session.liveFormat.first()
            // Emparelho canal+playable antes de descartar os nulos: se eu
            // montasse duas listas separadas, um único canal com URL inválida
            // desalinharia os índices e você abriria o canal errado.
            val pairs = items.filterIsInstance<Channel>()
                .mapNotNull { channel -> channel.toPlayable(creds, format)?.let { channel to it } }
            val start = pairs.indexOfFirst { it.first.streamId == selected.streamId }
            queue(pairs.map { it.second }, start.coerceAtLeast(0))
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_LOGIN = "login"
private const val ROUTE_SETTINGS = "settings"
