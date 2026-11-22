package br.com.iordan.ranga.pc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import br.com.iordan.ranga.pc.input.Gamepad
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.iordan.ranga.data.MovieDetail
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.data.SeriesDetail
import br.com.iordan.ranga.data.toPlayable
import br.com.iordan.ranga.pc.AppState
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt

@Composable
fun MovieDetailView(
    app: AppState,
    streamId: Int,
    onBack: () -> Unit,
    onPlay: (Playable) -> Unit,
) {
    val settings by app.settings.state.collectAsState()

    // produceState carrega uma vez, na chave streamId. É o equivalente enxuto
    // do "ViewModel + load()" do Android para uma tela que só lê.
    val detail by produceState<Result<MovieDetail>?>(initialValue = null, streamId) {
        value = runCatching { app.catalog.movieDetail(settings.credentials, streamId) }
    }

    DetailScaffold(
        title = detail?.getOrNull()?.movie?.title ?: "Filme",
        isFavorite = "movie:$streamId" in settings.favoriteKeys,
        onFavorite = { detail?.getOrNull()?.movie?.let { app.settings.toggleFavorite(it) } },
        onBack = onBack,
    ) {
        val result = detail
        when {
            result == null -> LoadingBox()
            result.isFailure -> ErrorBox(result.exceptionOrNull()?.message ?: "Falha ao carregar.")
            else -> {
                val movie = result.getOrThrow()
                val resumeMs = app.settings.resumeMsFor(movie.movie.key)
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    RemoteImage(
                        url = movie.movie.image,
                        contentDescription = movie.movie.title,
                        modifier = Modifier.width(200.dp).aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(movie.movie.title, style = MaterialTheme.typography.headlineSmall)
                        val meta = listOfNotNull(
                            movie.releaseDate?.take(4),
                            movie.genre,
                            movie.durationSecs?.let { "${(it / 60f).roundToInt()} min" },
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Com controle, o foco já começa no Assistir.
                        val playFocus = remember { FocusRequester() }
                        LaunchedEffect(Gamepad.connected) {
                            if (Gamepad.connected) runCatching { playFocus.requestFocus() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    movie.movie.toPlayable(settings.credentials, resumeMs)?.let(onPlay)
                                },
                                modifier = Modifier.focusRing().focusRequester(playFocus),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text(
                                    if (resumeMs > 0) "  Continuar de ${formatClock(resumeMs)}"
                                    else "  Assistir"
                                )
                            }
                            if (resumeMs > 0) {
                                OutlinedButton(
                                    onClick = {
                                        movie.movie.toPlayable(settings.credentials, 0)?.let(onPlay)
                                    },
                                    modifier = Modifier.focusRing(),
                                ) {
                                    Text("Do início")
                                }
                            }
                        }
                        movie.plot?.let { Text(it) }
                        movie.cast?.let {
                            Text(
                                "Elenco: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailView(
    app: AppState,
    seriesId: Int,
    onBack: () -> Unit,
    onPlay: (List<Playable>, Int) -> Unit,
) {
    val settings by app.settings.state.collectAsState()

    val detail by produceState<Result<SeriesDetail>?>(initialValue = null, seriesId) {
        value = runCatching { app.catalog.seriesDetail(settings.credentials, seriesId) }
    }

    var season by remember { mutableIntStateOf(-1) }

    DetailScaffold(
        title = detail?.getOrNull()?.series?.title ?: "Série",
        isFavorite = "series:$seriesId" in settings.favoriteKeys,
        onFavorite = { detail?.getOrNull()?.series?.let { app.settings.toggleFavorite(it) } },
        onBack = onBack,
    ) {
        val result = detail
        when {
            result == null -> LoadingBox()
            result.isFailure -> ErrorBox(result.exceptionOrNull()?.message ?: "Falha ao carregar.")
            else -> {
                val series = result.getOrThrow()
                // Nem toda série começa na temporada 1: há painel que usa 0
                // para especiais, ou que só tem da 2 em diante.
                val seasons = series.seasons.keys.sorted()
                val selected = if (season in seasons) season else seasons.firstOrNull() ?: 0
                val episodes = series.seasons[selected].orEmpty()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            RemoteImage(
                                url = series.series.image,
                                contentDescription = series.series.title,
                                modifier = Modifier.width(160.dp).aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val meta = listOfNotNull(
                                    series.series.releaseDate?.take(4),
                                    series.series.genre,
                                    "${series.seasons.size} temporada(s)",
                                ).joinToString(" · ")
                                Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                series.series.plot?.let {
                                    Text(it, maxLines = 8, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    if (seasons.isEmpty()) {
                        item { EmptyBox("Esta série está sem episódios no servidor.") }
                        return@LazyColumn
                    }

                    item {
                        // Com controle, o foco começa na temporada escolhida.
                        val seasonFocus = remember { FocusRequester() }
                        LaunchedEffect(Gamepad.connected) {
                            if (Gamepad.connected) runCatching { seasonFocus.requestFocus() }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(count = seasons.size, key = { seasons[it] }) { i ->
                                val number = seasons[i]
                                Button(
                                    onClick = { season = number },
                                    modifier = Modifier.focusRing().then(
                                        if (number == selected) Modifier.focusRequester(seasonFocus) else Modifier
                                    ),
                                    colors = if (number == selected) ButtonDefaults.buttonColors()
                                    else ButtonDefaults.outlinedButtonColors(),
                                ) {
                                    Text("Temporada $number")
                                }
                            }
                        }
                    }

                    items(count = episodes.size, key = { episodes[it].key }) { index ->
                        val episode = episodes[index]
                        val resumeMs = app.settings.resumeMsFor(episode.key)
                        ItemRow(
                            title = "${episode.number}. ${episode.title}",
                            imageUrl = episode.image ?: series.series.image,
                            subtitle = listOfNotNull(
                                episode.durationSecs?.let { "${it / 60} min" },
                                if (resumeMs > 0) "parou em ${formatClock(resumeMs)}" else null,
                            ).joinToString(" · ").ifBlank { null },
                            onClick = {
                                // Enfileira a temporada toda a partir daqui,
                                // para emendar no próximo episódio sozinho.
                                val queue = episodes.mapNotNull {
                                    it.toPlayable(
                                        creds = settings.credentials,
                                        seriesTitle = series.series.title,
                                        startPositionMs = app.settings.resumeMsFor(it.key),
                                    )
                                }
                                if (queue.isNotEmpty()) onPlay(queue, index.coerceIn(queue.indices))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailScaffold(
    title: String,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.focusRing()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = onFavorite, modifier = Modifier.focusRing()) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite
                    else Icons.Default.FavoriteBorder,
                    contentDescription = "Favoritar",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

internal fun formatClock(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h${minutes.toString().padStart(2, '0')}" else "${minutes}min"
}
