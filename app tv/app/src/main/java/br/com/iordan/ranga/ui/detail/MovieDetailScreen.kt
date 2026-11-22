package br.com.iordan.ranga.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.iordan.ranga.data.Playable
import br.com.iordan.ranga.ui.common.ErrorBox
import br.com.iordan.ranga.ui.common.LoadingBox
import br.com.iordan.ranga.ui.common.RemoteImage
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    onBack: () -> Unit,
    onPlay: (Playable) -> Unit,
    viewModel: MovieDetailViewModel = viewModel(factory = MovieDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Evento de uma vez só: o ViewModel monta o Playable (precisa das
    // credenciais, que vêm de um Flow) e a tela consome e limpa. Sem isso,
    // uma recomposição reabriria o player.
    LaunchedEffect(state.playRequest) {
        state.playRequest?.let {
            onPlay(it)
            viewModel.consumePlayRequest()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.movie?.title ?: "Filme") },
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

            detail == null -> ErrorBox("Filme não encontrado.", Modifier.padding(padding))

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RemoteImage(
                        url = detail.movie.image,
                        contentDescription = detail.movie.title,
                        modifier = Modifier
                            .width(140.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = detail.movie.title,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val meta = listOfNotNull(
                            detail.releaseDate?.take(4),
                            detail.genre,
                            detail.durationSecs?.let { "${(it / 60f).roundToInt()} min" },
                            detail.movie.rating?.let { "★ %.1f".format(it) },
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Button(
                            onClick = { viewModel.play(fromStart = false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(
                                if (state.resumeMs > 0) {
                                    "  Continuar de ${formatClock(state.resumeMs)}"
                                } else "  Assistir"
                            )
                        }
                        if (state.resumeMs > 0) {
                            OutlinedButton(
                                onClick = { viewModel.play(fromStart = true) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Começar do início")
                            }
                        }
                    }
                }

                detail.plot?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                detail.cast?.let {
                    Text(
                        "Elenco: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                detail.director?.let {
                    Text(
                        "Direção: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** ms -> "1h23" ou "12min", só para o rótulo do botão de continuar. */
internal fun formatClock(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h${minutes.toString().padStart(2, '0')}" else "${minutes}min"
}
