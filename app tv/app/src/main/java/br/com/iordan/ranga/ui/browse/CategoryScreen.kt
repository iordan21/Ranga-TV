package br.com.iordan.ranga.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.iordan.ranga.AppContainer
import br.com.iordan.ranga.appContainer
import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.Channel
import br.com.iordan.ranga.data.Section
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.normalizeForSearch
import br.com.iordan.ranga.ui.common.ChannelRow
import br.com.iordan.ranga.ui.common.ClickToEditField
import br.com.iordan.ranga.ui.common.EmptyBox
import br.com.iordan.ranga.ui.common.ErrorBox
import br.com.iordan.ranga.ui.common.LoadingBox
import br.com.iordan.ranga.ui.common.PosterCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategoryUiState(
    val title: String = "",
    val section: Section = Section.LIVE,
    val items: List<CatalogItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val favoriteKeys: Set<String> = emptySet(),
    /**
     * [items] filtrado por [query]. Guardado, e não um `get()`: o getter
     * refiltrava a lista inteira toda vez que a tela lia o campo — duas
     * vezes por recomposição, e a cada favorito salvo — na thread da tela.
     */
    val visible: List<CatalogItem> = emptyList(),
)

class CategoryViewModel(
    private val container: AppContainer,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val section = Section.fromName(savedState.get<String>(ARG_SECTION))
    private val categoryId: String = savedState.get<String>(ARG_CATEGORY_ID).orEmpty()

    private val _state = MutableStateFlow(CategoryUiState(section = section))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.session.favoriteKeys.collect { keys ->
                _state.update { it.copy(favoriteKeys = keys) }
            }
        }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val creds = container.session.credentials.first()
                // categories() vem do cache do Catalog, então achar o nome aqui
                // não custa rede — por isso não preciso passar o título na rota.
                val title = container.catalog.categories(creds, section)
                    .firstOrNull { it.id == categoryId }?.name.orEmpty()
                val items = container.catalog.items(creds, section, categoryId)
                val visible = filter(items, _state.value.query)
                _state.update { it.copy(title = title, items = items, visible = visible, loading = false) }
            } catch (e: XtreamException) {
                _state.update { it.copy(loading = false, error = e.userMessage) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Falha ao carregar.") }
            }
        }
    }

    fun onQuery(value: String) {
        _state.update { it.copy(query = value) }
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val visible = filter(_state.value.items, value)
            _state.update { if (it.query == value) it.copy(visible = visible) else it }
        }
    }

    private var filterJob: Job? = null

    /** Fora da thread da tela: categoria de VOD passa de milhares de títulos. */
    private suspend fun filter(items: List<CatalogItem>, query: String): List<CatalogItem> {
        if (query.length < 2) return items
        return withContext(Dispatchers.Default) {
            val needle = query.normalizeForSearch()
            items.filter { it.title.normalizeForSearch().contains(needle) }
        }
    }

    fun toggleFavorite(item: CatalogItem) {
        viewModelScope.launch { container.session.toggleFavorite(item) }
    }

    companion object {
        const val ARG_SECTION = "section"
        const val ARG_CATEGORY_ID = "categoryId"

        val Factory = viewModelFactory {
            initializer { CategoryViewModel(this.appContainer, this.createSavedStateHandle()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onBack: () -> Unit,
    onOpenItems: (List<CatalogItem>, Int) -> Unit,
    viewModel: CategoryViewModel = viewModel(factory = CategoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { "Categoria" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ClickToEditField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                label = "Filtrar nesta categoria",
                leadingIcon = Icons.Default.Search,
                imeAction = ImeAction.Search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error!!, onRetry = viewModel::load)
                state.visible.isEmpty() -> EmptyBox("Nada aqui.")

                // Canal ao vivo em lista: o logo é largo e o nome é longo
                // ("| BR | GLOBO SP FHD"), então grade de pôster fica ilegível.
                state.section == Section.LIVE -> ChannelList(
                    state = state,
                    onOpen = onOpenItems,
                    onToggleFavorite = viewModel::toggleFavorite,
                )

                else -> PosterGrid(state = state, onOpen = onOpenItems)
            }
        }
    }
}

@Composable
private fun ChannelList(
    state: CategoryUiState,
    onOpen: (List<CatalogItem>, Int) -> Unit,
    onToggleFavorite: (CatalogItem) -> Unit,
) {
    val visible = state.visible
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count = visible.size, key = { visible[it].key }) { index ->
            val item = visible[index]
            val isFavorite = item.key in state.favoriteKeys
            ChannelRow(
                title = item.title,
                imageUrl = item.image,
                subtitle = (item as? Channel)?.number?.let { "Canal $it" },
                onClick = { onOpen(visible, index) },
                trailing = {
                    IconButton(onClick = { onToggleFavorite(item) }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite
                            else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remover dos favoritos"
                            else "Adicionar aos favoritos",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun PosterGrid(
    state: CategoryUiState,
    onOpen: (List<CatalogItem>, Int) -> Unit,
) {
    val visible = state.visible
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count = visible.size, key = { visible[it].key }) { index ->
            val item = visible[index]
            PosterCard(
                title = item.title,
                imageUrl = item.image,
                onClick = { onOpen(visible, index) },
            )
        }
    }
}
