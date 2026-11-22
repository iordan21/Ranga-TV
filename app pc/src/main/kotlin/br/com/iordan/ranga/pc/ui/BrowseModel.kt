package br.com.iordan.ranga.pc.ui

import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.Category
import br.com.iordan.ranga.data.FreeChannel
import br.com.iordan.ranga.data.FreeChannels
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.normalizeForSearch
import br.com.iordan.ranga.pc.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val tab: BrowseTab = BrowseTab.LIVE,
    /** Sem conta, as abas do Xtream mostram um convite para entrar em vez de carregar. */
    val loggedIn: Boolean = false,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val items: List<CatalogItem> = emptyList(),
    val results: List<CatalogItem> = emptyList(),
    val loadingCategories: Boolean = true,
    val loadingItems: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val indexProgress: Pair<Int, Int>? = null,
)

/**
 * O "ViewModel" do desktop.
 *
 * Não é `androidx.lifecycle.ViewModel` porque aqui não existe ciclo de vida de
 * Activity nem morte de processo para sobreviver — é uma classe comum,
 * lembrada com `remember`, que recebe o escopo de corrotina da composição.
 * A lógica em si é a mesma do `HomeViewModel` do Android.
 */
class BrowseModel(
    private val app: AppState,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(BrowseUiState())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Diferente do Android, o Settings já está lido quando o modelo nasce:
        // dá para decidir a aba inicial na hora, sem estado "ainda não sei".
        val loggedIn = app.settings.current.credentials.isComplete
        _state.update {
            it.copy(loggedIn = loggedIn, tab = if (loggedIn) BrowseTab.LIVE else BrowseTab.FREE,
                loadingCategories = loggedIn)
        }
        if (loggedIn) loadCategories(BrowseTab.LIVE)

        // A tela de login abre por cima desta, e o logout acontece nas
        // Configurações: nos dois casos este modelo continua vivo e precisa
        // reagir à credencial mudando.
        scope.launch {
            app.settings.state.map { it.credentials.isComplete }.distinctUntilChanged()
                .collect { now ->
                    val before = _state.value.loggedIn
                    if (now == before) return@collect
                    _state.update { it.copy(loggedIn = now) }
                    if (now) {
                        loadCategories(_state.value.tab)
                    } else {
                        // Saiu da conta: o catálogo dela não pode ficar na barra.
                        _state.update {
                            it.copy(
                                tab = BrowseTab.FREE,
                                categories = emptyList(),
                                selectedCategoryId = null,
                                items = emptyList(),
                                results = emptyList(),
                                query = "",
                                error = null,
                                loadingCategories = false,
                            )
                        }
                    }
                }
        }
    }

    fun selectTab(tab: BrowseTab) {
        if (_state.value.tab == tab) return
        _state.update {
            it.copy(
                tab = tab,
                query = "",
                results = emptyList(),
                items = emptyList(),
                selectedCategoryId = null,
                error = null,
            )
        }
        if (tab.section != null) loadCategories(tab)
    }

    fun retry() {
        val current = _state.value
        if (current.selectedCategoryId != null) selectCategory(current.selectedCategoryId)
        else loadCategories(current.tab)
    }

    private fun loadCategories(tab: BrowseTab) {
        val section = tab.section ?: return
        // Sem conta não há o que pedir ao painel; a tela mostra o convite.
        if (!_state.value.loggedIn) {
            _state.update { it.copy(loadingCategories = false, error = null) }
            return
        }
        _state.update { it.copy(loadingCategories = true, error = null) }
        scope.launch {
            try {
                val categories = app.catalog.categories(app.settings.current.credentials, section)
                _state.update {
                    if (it.tab == tab) it.copy(categories = categories, loadingCategories = false)
                    else it
                }
            } catch (e: Exception) {
                _state.update {
                    if (it.tab == tab) it.copy(loadingCategories = false, error = e.friendly()) else it
                }
            }
        }
    }

    fun selectCategory(categoryId: String) {
        val section = _state.value.tab.section ?: return
        _state.update { it.copy(selectedCategoryId = categoryId, loadingItems = true, error = null) }
        scope.launch {
            try {
                val items = app.catalog.items(app.settings.current.credentials, section, categoryId)
                _state.update {
                    if (it.selectedCategoryId == categoryId) {
                        it.copy(items = items, loadingItems = false)
                    } else it
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingItems = false, error = e.friendly()) }
            }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.length < 2) {
            _state.update { it.copy(results = emptyList()) }
            return
        }
        searchJob = scope.launch {
            delay(250) // não refiltra a cada tecla
            val section = _state.value.tab.section ?: return@launch
            val results = app.catalog.searchCached(section, value)
            _state.update { if (it.query == value) it.copy(results = results) else it }
        }
    }

    fun indexEverything() {
        val section = _state.value.tab.section ?: return
        if (_state.value.indexProgress != null) return
        scope.launch {
            _state.update { it.copy(indexProgress = 0 to 1) }
            try {
                app.catalog.loadSection(app.settings.current.credentials, section) { done, total ->
                    _state.update { it.copy(indexProgress = done to total) }
                }
                val results = app.catalog.searchCached(section, _state.value.query)
                _state.update { it.copy(results = results) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendly()) }
            } finally {
                _state.update { it.copy(indexProgress = null) }
            }
        }
    }

    /** A aba Grátis filtra a própria lista pela busca; ela não passa pelo catálogo. */
    fun visibleFree(state: BrowseUiState): List<FreeChannel> =
        if (state.query.length < 2) FreeChannels.ALL else {
            val needle = state.query.normalizeForSearch()
            FreeChannels.ALL.filter { it.name.normalizeForSearch().contains(needle) }
        }

    /**
     * O que a área de conteúdo deve mostrar, nesta ordem de prioridade:
     * busca digitada > aba de favoritos > categoria escolhida.
     */
    fun visibleItems(state: BrowseUiState, favorites: List<CatalogItem>): List<CatalogItem> = when {
        state.query.length >= 2 && state.tab == BrowseTab.FAVORITES -> {
            val needle = state.query.normalizeForSearch()
            favorites.filter { it.title.normalizeForSearch().contains(needle) }
        }

        state.query.length >= 2 -> state.results
        state.tab == BrowseTab.FAVORITES -> favorites
        else -> state.items
    }

    private fun Exception.friendly(): String =
        (this as? XtreamException)?.userMessage ?: message ?: "Falha ao carregar."
}
