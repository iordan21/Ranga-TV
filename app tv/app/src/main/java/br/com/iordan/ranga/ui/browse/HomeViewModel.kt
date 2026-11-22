package br.com.iordan.ranga.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.iordan.ranga.AppContainer
import br.com.iordan.ranga.appContainer
import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.Category
import br.com.iordan.ranga.data.FreeChannel
import br.com.iordan.ranga.data.FreeChannels
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.data.Section
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.normalizeForSearch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val tab: HomeTab = HomeTab.LIVE,
    /**
     * Sem conta, as abas do Xtream não carregam nada: mostram um convite para
     * entrar. `null` enquanto o DataStore ainda não respondeu — senão a Home
     * piscaria o convite em todo boot, antes de ler a credencial salva.
     */
    val loggedIn: Boolean? = null,
    val categories: List<Category> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val results: List<CatalogItem> = emptyList(),
    /** (categorias carregadas, total) enquanto o "carregar tudo" roda. */
    val indexProgress: Pair<Int, Int>? = null,
    val favorites: List<CatalogItem> = emptyList(),
    val resume: List<ResumeEntry> = emptyList(),
) {
    /** A aba Grátis filtra no lugar, sem passar pela grade de resultados. */
    val visibleFree: List<FreeChannel>
        get() = if (query.length < 2) FreeChannels.ALL else {
            val needle = query.normalizeForSearch()
            FreeChannels.ALL.filter { it.name.normalizeForSearch().contains(needle) }
        }
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Favoritos e progresso vêm do DataStore como Flow: favoritar num lugar
        // reflete em todas as telas sem eu precisar avisar ninguém.
        viewModelScope.launch {
            container.session.favorites.collect { saved ->
                _state.update { it.copy(favorites = saved.map { s -> s.toCatalogItem() }) }
            }
        }
        viewModelScope.launch {
            container.session.resume.collect { entries ->
                _state.update { it.copy(resume = entries) }
            }
        }
        // A credencial é observada, não lida uma vez: entrar pela tela de login
        // ou sair pelas Configurações acontece com a Home viva na pilha, e ela
        // precisa reagir sem ser recriada. distinctUntilChanged porque o
        // DataStore reemite o mesmo valor a cada favorito ou progresso salvo.
        viewModelScope.launch {
            container.session.credentials.distinctUntilChanged().collect { creds ->
                val before = _state.value.loggedIn
                val now = creds.isComplete
                _state.update { it.copy(loggedIn = now) }
                when {
                    // Primeira leitura: quem não tem conta começa na aba que
                    // funciona sem ela; quem tem, na de sempre.
                    before == null -> {
                        if (now) loadCategories(_state.value.tab)
                        else _state.update { it.copy(tab = HomeTab.FREE, loading = false) }
                    }
                    // Acabou de entrar: a aba Xtream aberta (se houver) carrega.
                    before == false && now -> loadCategories(_state.value.tab)
                    // Saiu da conta: some com o catálogo dela da tela.
                    before == true && !now -> _state.update {
                        it.copy(
                            tab = HomeTab.FREE,
                            categories = emptyList(),
                            results = emptyList(),
                            query = "",
                            loading = false,
                            error = null,
                        )
                    }
                }
            }
        }
    }

    fun onTab(tab: HomeTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab, query = "", results = emptyList()) }
        if (tab.section != null) loadCategories(tab)
    }

    fun retry() = loadCategories(_state.value.tab)

    private fun loadCategories(tab: HomeTab) {
        val section = tab.section ?: return
        // Sem conta não há o que pedir ao painel; a tela mostra o convite.
        if (_state.value.loggedIn != true) {
            _state.update { it.copy(loading = false, error = null) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val creds = container.session.credentials.first()
                val categories = container.catalog.categories(creds, section)
                _state.update {
                    // Guarda só se o usuário ainda está na aba que pediu —
                    // senão uma resposta lenta sobrescreveria a aba atual.
                    if (it.tab == tab) it.copy(categories = categories, loading = false)
                    else it
                }
            } catch (e: XtreamException) {
                _state.update {
                    if (it.tab == tab) it.copy(loading = false, error = e.userMessage) else it
                }
            } catch (e: Exception) {
                _state.update {
                    if (it.tab == tab) {
                        it.copy(loading = false, error = e.message ?: "Falha ao carregar.")
                    } else it
                }
            }
        }
    }

    fun onQuery(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()

        // A aba Grátis não usa a grade de resultados: `visibleFree` filtra a
        // lista no lugar, e ela é pequena demais para precisar de atraso.
        if (value.length < 2 || _state.value.tab == HomeTab.FREE) {
            _state.update { it.copy(results = emptyList()) }
            return
        }

        searchJob = viewModelScope.launch {
            // Pequeno atraso para não refiltrar dezenas de milhares de itens a
            // cada tecla. Cancelado pelo `searchJob?.cancel()` acima.
            delay(250)
            val current = _state.value
            val results = when (val section = current.tab.section) {
                null -> {
                    val needle = value.normalizeForSearch()
                    current.favorites.filter { it.title.normalizeForSearch().contains(needle) }
                }
                else -> container.catalog.searchCached(section, value)
            }
            _state.update { if (it.query == value) it.copy(results = results) else it }
        }
    }

    /**
     * Baixa todas as categorias da seção atual para que a busca enxergue tudo.
     * É a operação mais cara do app — por isso só roda quando o usuário pede.
     */
    fun indexEverything() {
        val section = _state.value.tab.section ?: return
        if (_state.value.indexProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(indexProgress = 0 to 1) }
            try {
                val creds = container.session.credentials.first()
                container.catalog.loadSection(creds, section) { done, total ->
                    _state.update { it.copy(indexProgress = done to total) }
                }
                val results = container.catalog.searchCached(section, _state.value.query)
                _state.update { it.copy(results = results) }
            } catch (e: Exception) {
                _state.update { it.copy(error = (e as? XtreamException)?.userMessage ?: e.message) }
            } finally {
                _state.update { it.copy(indexProgress = null) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { HomeViewModel(this.appContainer) }
        }
    }
}
