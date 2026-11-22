package br.com.iordan.ranga.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.iordan.ranga.AppContainer
import br.com.iordan.ranga.appContainer
import br.com.iordan.ranga.data.Episode
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.data.SeriesDetail
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.toPlayable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeriesDetailUiState(
    val detail: SeriesDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val isFavorite: Boolean = false,
    val selectedSeason: Int = 0,
    val resumeByKey: Map<String, Long> = emptyMap(),
    /** (fila de episódios, índice inicial) — evento de uma vez só. */
    val playRequest: Pair<List<Playable>, Int>? = null,
) {
    val seasonNumbers: List<Int> get() = detail?.seasons?.keys?.sorted().orEmpty()

    val episodesOfSelectedSeason: List<Episode>
        get() = detail?.seasons?.get(selectedSeason).orEmpty()
}

class SeriesDetailViewModel(
    private val container: AppContainer,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val seriesId: Int = savedState.get<String>(ARG_SERIES_ID)?.toIntOrNull() ?: -1

    private val _state = MutableStateFlow(SeriesDetailUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.session.favoriteKeys.collect { keys ->
                _state.update { it.copy(isFavorite = "series:$seriesId" in keys) }
            }
        }
        viewModelScope.launch {
            container.session.resume.collect { entries ->
                _state.update { s ->
                    s.copy(resumeByKey = entries.associate { it.key to it.positionMs })
                }
            }
        }
        load()
    }

    fun load() {
        if (seriesId <= 0) {
            _state.update { it.copy(loading = false, error = "Série inválida.") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val creds = container.session.credentials.first()
                val detail = container.catalog.seriesDetail(creds, seriesId)
                _state.update {
                    it.copy(
                        detail = detail,
                        loading = false,
                        // Abre já na primeira temporada disponível — que nem
                        // sempre é a 1: tem série no painel que começa na 0
                        // (especiais) ou direto na 2.
                        selectedSeason = detail.seasons.keys.minOrNull() ?: 0,
                    )
                }
            } catch (e: XtreamException) {
                _state.update { it.copy(loading = false, error = e.userMessage) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Falha ao carregar.") }
            }
        }
    }

    fun selectSeason(season: Int) = _state.update { it.copy(selectedSeason = season) }

    /**
     * Toca a partir do episódio escolhido, mas enfileira a temporada inteira
     * daquele ponto em diante — assim o player emenda no próximo quando acaba.
     */
    fun play(indexInSeason: Int) {
        val current = _state.value
        val detail = current.detail ?: return
        val episodes = current.episodesOfSelectedSeason
        if (indexInSeason !in episodes.indices) return

        viewModelScope.launch {
            val creds = container.session.credentials.first()
            val queue = episodes.mapNotNull { episode ->
                episode.toPlayable(
                    creds = creds,
                    seriesTitle = detail.series.title,
                    startPositionMs = current.resumeByKey[episode.key] ?: 0L,
                )
            }
            if (queue.isEmpty()) return@launch
            _state.update { it.copy(playRequest = queue to indexInSeason.coerceIn(queue.indices)) }
        }
    }

    fun consumePlayRequest() = _state.update { it.copy(playRequest = null) }

    fun toggleFavorite() {
        val series = _state.value.detail?.series ?: return
        viewModelScope.launch { container.session.toggleFavorite(series) }
    }

    companion object {
        const val ARG_SERIES_ID = "seriesId"

        val Factory = viewModelFactory {
            initializer { SeriesDetailViewModel(this.appContainer, this.createSavedStateHandle()) }
        }
    }
}
