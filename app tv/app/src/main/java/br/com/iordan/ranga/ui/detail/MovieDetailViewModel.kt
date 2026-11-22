package br.com.iordan.ranga.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.iordan.ranga.AppContainer
import br.com.iordan.ranga.appContainer
import br.com.iordan.ranga.data.MovieDetail
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.toPlayable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MovieDetailUiState(
    val detail: MovieDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val isFavorite: Boolean = false,
    val resumeMs: Long = 0L,
    val playRequest: Playable? = null,
)

class MovieDetailViewModel(
    private val container: AppContainer,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val streamId: Int = savedState.get<String>(ARG_STREAM_ID)?.toIntOrNull() ?: -1

    private val _state = MutableStateFlow(MovieDetailUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.session.favoriteKeys.collect { keys ->
                _state.update { it.copy(isFavorite = "movie:$streamId" in keys) }
            }
        }
        viewModelScope.launch {
            container.session.resume.collect { entries ->
                val saved = entries.firstOrNull { it.key == "movie:$streamId" }
                _state.update { it.copy(resumeMs = saved?.positionMs ?: 0L) }
            }
        }
        load()
    }

    fun load() {
        if (streamId <= 0) {
            _state.update { it.copy(loading = false, error = "Filme inválido.") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val creds = container.session.credentials.first()
                val detail = container.catalog.movieDetail(creds, streamId)
                _state.update { it.copy(detail = detail, loading = false) }
            } catch (e: XtreamException) {
                _state.update { it.copy(loading = false, error = e.userMessage) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Falha ao carregar.") }
            }
        }
    }

    fun play(fromStart: Boolean) {
        val movie = _state.value.detail?.movie ?: return
        viewModelScope.launch {
            val creds = container.session.credentials.first()
            val start = if (fromStart) 0L else _state.value.resumeMs
            val playable = movie.toPlayable(creds, start)
            _state.update { it.copy(playRequest = playable) }
        }
    }

    fun consumePlayRequest() = _state.update { it.copy(playRequest = null) }

    fun toggleFavorite() {
        val movie = _state.value.detail?.movie ?: return
        viewModelScope.launch { container.session.toggleFavorite(movie) }
    }

    companion object {
        const val ARG_STREAM_ID = "streamId"

        val Factory = viewModelFactory {
            initializer { MovieDetailViewModel(this.appContainer, this.createSavedStateHandle()) }
        }
    }
}
