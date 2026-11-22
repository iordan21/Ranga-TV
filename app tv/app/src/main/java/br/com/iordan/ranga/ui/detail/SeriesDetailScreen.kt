package br.com.iordan.ranga.ui.detail

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.ui.common.ChannelRow
import br.com.iordan.ranga.ui.common.EmptyBox
import br.com.iordan.ranga.ui.common.ErrorBox
import br.com.iordan.ranga.ui.common.LoadingBox
import br.com.iordan.ranga.ui.common.RemoteImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    onBack: () -> Unit,
    onPlay: (List<Playable>, Int) -> Unit,
    viewModel: SeriesDetailViewModel = viewModel(factory = SeriesDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.playRequest) {
        state.playRequest?.let { (queue, index) ->
            onPlay(queue, index)
            viewModel.consumePlayRequest()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.series?.title ?: "Série") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    if (state.detail != null) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector = if (state.isFavorite) Icons.Default.Favorite
                                else Icons.Default.FavoriteBorder,
                                contentDescription = "Favoritar",
                                tint = if (state.isFavorite) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val detail = state.detail
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.error != null -> ErrorBox(
                state.error!!,
                Modifier.padding(padding),
                onRetry = viewModel::load,
            )

            detail == null -> ErrorBox("Série não encontrada.", Modifier.padding(padding))

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RemoteImage(
                            url = detail.series.image,
                            contentDescription = detail.series.title,
                            modifier = Modifier
                                .width(120.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val meta = listOfNotNull(
                                detail.series.releaseDate?.take(4),
                                detail.series.genre,
                                "${detail.seasons.size} temporada(s)".takeIf {
                                    detail.seasons.isNotEmpty()
                                },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            detail.series.plot?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (detail.seasons.isEmpty()) {
                    item { EmptyBox("Esta série está sem episódios no servidor.") }
                    return@LazyColumn
                }

                item {
                    // Botão simples em vez de FilterChip: chip é experimental no
                    // Material3 e, na TV, botão dá um retângulo de foco maior e
                    // mais fácil de enxergar de longe.
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.seasonNumbers, key = { it }) { season ->
                            val selected = season == state.selectedSeason
                            Button(
                                onClick = { viewModel.selectSeason(season) },
                                colors = if (selected) ButtonDefaults.buttonColors()
                                else ButtonDefaults.outlinedButtonColors(),
                            ) {
                                Text("Temporada $season")
                            }
                        }
                    }
                }

                val episodes = state.episodesOfSelectedSeason
                items(count = episodes.size, key = { episodes[it].key }) { index ->
                    val episode = episodes[index]
                    val resumeMs = state.resumeByKey[episode.key] ?: 0L
                    ChannelRow(
                        title = "${episode.number}. ${episode.title}",
                        imageUrl = episode.image ?: detail.series.image,
                        subtitle = listOfNotNull(
                            episode.durationSecs?.let { "${it / 60} min" },
                            if (resumeMs > 0) "parou em ${formatClock(resumeMs)}" else null,
                        ).joinToString(" · ").ifBlank { null },
                        onClick = { viewModel.play(index) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
