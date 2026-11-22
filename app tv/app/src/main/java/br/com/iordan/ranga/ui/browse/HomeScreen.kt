package br.com.iordan.ranga.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.iordan.ranga.data.Category
import br.com.iordan.ranga.data.FreeChannel
import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.data.Section
import br.com.iordan.ranga.ui.common.ChannelRow
import br.com.iordan.ranga.ui.common.ClickToEditField
import br.com.iordan.ranga.ui.common.EmptyBox
import br.com.iordan.ranga.ui.common.ErrorBox
import br.com.iordan.ranga.ui.common.FocusableCard
import br.com.iordan.ranga.ui.common.LoadingBox
import br.com.iordan.ranga.ui.common.PosterCard
import br.com.iordan.ranga.ui.common.RemoteImage
import br.com.iordan.ranga.ui.common.ScreenCast
import br.com.iordan.ranga.ui.common.SectionHeader
import br.com.iordan.ranga.ui.common.ThinProgressBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenCategory: (Section, Category) -> Unit,
    onOpenItems: (List<CatalogItem>, Int) -> Unit,
    onOpenFree: (List<FreeChannel>, Int) -> Unit,
    onResume: (ResumeEntry) -> Unit,
    onLogin: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val canCast = remember(context) { ScreenCast.isAvailable(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Ranga") },
                actions = {
                    if (canCast) {
                        IconButton(onClick = { ScreenCast.open(context) }) {
                            Icon(ScreenCast.icon, contentDescription = "Transmitir para a TV")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurações")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            ClickToEditField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                label = "Buscar em ${state.tab.label}",
                leadingIcon = Icons.Default.Search,
                imeAction = ImeAction.Search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // A aba Grátis filtra a própria lista; as outras trocam para a
            // grade de resultados.
            if (state.query.length >= 2 && state.tab != HomeTab.FREE) {
                SearchResults(state, viewModel, onOpenItems)
                return@Column
            }

            val tabs: @Composable () -> Unit = {
                HomeTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.onTab(tab) },
                        text = {
                            Text(tab.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        },
                    )
                }
            }
            // Cinco abas de largura igual não cabem num celular em pé: cada uma
            // fica com ~56 dp úteis e "Favoritos" quebra em "Favorit / os".
            // Abaixo de 600 dp a fileira rola, e cada aba tem a largura do
            // próprio nome. Na TV e no tablet continua a fileira fixa.
            BoxWithConstraints {
                if (maxWidth < 600.dp) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = state.tab.ordinal,
                        edgePadding = 8.dp,
                        divider = {},
                        tabs = tabs,
                    )
                } else {
                    PrimaryTabRow(
                        selectedTabIndex = state.tab.ordinal,
                        // Sem a linha divisória: o indicador da aba ativa já diz onde
                        // você está, e a régua horizontal só soma traço na tela.
                        divider = {},
                        tabs = tabs,
                    )
                }
            }

            when {
                state.tab == HomeTab.FREE -> FreeChannelList(state.visibleFree, onOpenFree)
                state.tab == HomeTab.FAVORITES -> FavoritesGrid(state.favorites, onOpenItems)
                // Ainda não sei se há conta: spinner, e não o convite, para
                // ele não piscar em todo boot de quem tem credencial salva.
                state.loggedIn == null -> LoadingBox()
                state.loggedIn == false -> LoginPrompt(onLogin)
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error!!, onRetry = viewModel::retry)
                else -> CategoriesWithResume(
                    state = state,
                    onOpenCategory = { onOpenCategory(state.tab.section!!, it) },
                    onResume = onResume,
                )
            }
        }
    }
}

@Composable
private fun CategoriesWithResume(
    state: HomeUiState,
    onOpenCategory: (Category) -> Unit,
    onResume: (ResumeEntry) -> Unit,
) {
    if (state.categories.isEmpty()) {
        EmptyBox("Seu plano não tem nada nesta seção.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 200.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.resume.isNotEmpty()) {
            // span máximo: a faixa "continuar assistindo" ocupa a linha inteira
            // dentro da mesma grade, para não precisar de dois scrolls aninhados.
            item(span = { GridItemSpan(maxLineSpan) }) {
                ResumeRow(state.resume, onResume)
            }
        }
        items(state.categories, key = { it.id }) { category ->
            FocusableCard(onClick = { onOpenCategory(category) }) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun ResumeRow(entries: List<ResumeEntry>, onResume: (ResumeEntry) -> Unit) {
    Column {
        SectionHeader("Continuar assistindo")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                Column(Modifier.width(150.dp)) {
                    FocusableCard(onClick = { onResume(entry) }) {
                        Box {
                            RemoteImage(
                                url = entry.image,
                                contentDescription = entry.title,
                                modifier = Modifier.fillMaxWidth().height(90.dp),
                            )
                            ThinProgressBar(
                                progress = entry.progress,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!entry.subtitle.isNullOrBlank()) {
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Canais abertos. Lista, e não grade: são poucos, e o que interessa mostrar é
 * de onde cada stream veio — a linha de baixo diz o domínio da emissora.
 */
@Composable
private fun FreeChannelList(
    channels: List<FreeChannel>,
    onOpen: (List<FreeChannel>, Int) -> Unit,
) {
    if (channels.isEmpty()) {
        EmptyBox("Nenhum canal com esse nome.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = "Canais abertos, com o stream que a própria emissora publica. Sem conta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(count = channels.size, key = { channels[it].key }) { index ->
            val channel = channels[index]
            ChannelRow(
                title = channel.name,
                imageUrl = channel.logo,
                subtitle = "Stream oficial · ${channel.sourceHost}",
                onClick = { onOpen(channels, index) },
            )
        }
    }
}

/** Aba do Xtream sem conta: o que ela mostraria e como destravar. */
@Composable
private fun LoginPrompt(onLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Canais ao vivo, filmes e séries vêm da sua conta IPTV.\n" +
                "Os canais da aba Grátis não precisam dela.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onLogin) { Text("Entrar com a conta") }
    }
}

@Composable
private fun FavoritesGrid(
    favorites: List<CatalogItem>,
    onOpenItems: (List<CatalogItem>, Int) -> Unit,
) {
    if (favorites.isEmpty()) {
        EmptyBox("Nenhum favorito ainda.\nAbra uma categoria e toque no coração.")
        return
    }
    ItemGrid(favorites, onOpenItems)
}

@Composable
private fun SearchResults(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onOpenItems: (List<CatalogItem>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val progress = state.indexProgress
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / progress.second.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            // A busca só enxerga o que já foi baixado — carregar 70 mil títulos
            // de uma vez trava o aparelho. O botão deixa isso explícito e
            // opcional, em vez de esconder a limitação.
            TextButton(
                onClick = viewModel::indexEverything,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("Buscando no que já foi aberto · carregar tudo")
            }
        }

        if (state.results.isEmpty()) {
            EmptyBox("Nada encontrado para \"${state.query}\".")
        } else {
            ItemGrid(state.results, onOpenItems)
        }
    }
}

@Composable
private fun ItemGrid(
    items: List<CatalogItem>,
    onOpenItems: (List<CatalogItem>, Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count = items.size, key = { items[it].key }) { index ->
            val item = items[index]
            PosterCard(
                title = item.title,
                imageUrl = item.image,
                onClick = { onOpenItems(items, index) },
            )
        }
    }
}

enum class HomeTab(val label: String, val section: Section?) {
    LIVE("Ao vivo", Section.LIVE),
    MOVIES("Filmes", Section.MOVIES),
    SERIES("Séries", Section.SERIES),
    /** Canais abertos de [br.com.iordan.ranga.data.FreeChannels]; sem seção Xtream. */
    FREE("Grátis", null),
    FAVORITES("Favoritos", null),
}
