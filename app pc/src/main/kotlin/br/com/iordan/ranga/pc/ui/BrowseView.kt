package br.com.iordan.ranga.pc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import br.com.iordan.ranga.pc.input.Gamepad
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.iordan.ranga.data.CatalogItem
import br.com.iordan.ranga.data.Channel
import br.com.iordan.ranga.data.FreeChannel
import br.com.iordan.ranga.data.ResumeEntry
import br.com.iordan.ranga.data.Section
import br.com.iordan.ranga.pc.AppState
import androidx.compose.runtime.collectAsState

/**
 * Tela principal no PC: barra lateral com seções e categorias, conteúdo à
 * direita.
 *
 * É deliberadamente diferente do app de TV, que usa abas e uma coisa de cada
 * vez. Aqui há mouse, 1920px de largura e nenhuma razão para esconder a lista
 * de categorias atrás de uma navegação — dá para ver onde você está e para
 * onde pode ir ao mesmo tempo.
 */
@Composable
fun BrowseView(
    app: AppState,
    onOpenItems: (List<CatalogItem>, Int) -> Unit,
    onOpenFree: (List<FreeChannel>, Int) -> Unit,
    onResume: (ResumeEntry) -> Unit,
    onLogin: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = remember { BrowseModel(app, scope) }
    val state by model.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    val focusManager = LocalFocusManager.current

    // O controle: o foco começa na aba escolhida (sem isto o primeiro aperto
    // do direcional não teria de onde sair), e LB/RB (PgUp/PgDn) trocam de aba.
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Gamepad.connected, state.tab) {
        if (Gamepad.connected) runCatching { tabFocus.requestFocus() }
    }
    DisposableEffect(model) {
        val keys = KeyEventDispatcher { event ->
            if (event.id != AwtKeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            val step = when (event.keyCode) {
                AwtKeyEvent.VK_PAGE_UP -> -1
                AwtKeyEvent.VK_PAGE_DOWN -> 1
                else -> return@KeyEventDispatcher false
            }
            val tabs = BrowseTab.entries
            model.selectTab(tabs[(tabs.indexOf(model.state.value.tab) + step).mod(tabs.size)])
            true
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
        onDispose { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys) }
    }

    Row(Modifier.fillMaxSize()) {

        // ------------------------------------------------------- barra lateral
        Column(Modifier.width(260.dp).fillMaxHeight().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Ranga",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings, modifier = Modifier.focusRing()) {
                    Icon(Icons.Default.Settings, contentDescription = t("Configurações"))
                }
            }

            Column(Modifier.padding(vertical = 8.dp)) {
                BrowseTab.entries.forEach { tab ->
                    val selected = state.tab == tab
                    HoverCard(
                        onClick = { model.selectTab(tab) },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (selected) Modifier.focusRequester(tabFocus) else Modifier),
                        label = "aba ${tab.label}",
                        background = if (selected) MaterialTheme.colorScheme.surfaceVariant
                        else androidx.compose.ui.graphics.Color.Transparent,
                    ) {
                        Text(
                            text = t(tab.label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        )
                    }
                }
            }

            when {
                // Grátis e Favoritos não têm categoria; sem conta não há o que listar.
                state.tab.section == null || !state.loggedIn -> Unit
                state.loadingCategories -> LoadingBox()
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(count = state.categories.size, key = { state.categories[it].id }) { i ->
                        val category = state.categories[i]
                        val selected = category.id == state.selectedCategoryId
                        HoverCard(
                            onClick = { model.selectCategory(category.id) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "categoria ${category.name}",
                            background = if (selected) MaterialTheme.colorScheme.surfaceVariant
                            else androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            )
                        }
                    }
                }
            }
        }

        VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        // ------------------------------------------------------------ conteúdo
        Column(Modifier.weight(1f).fillMaxHeight()) {

            OutlinedTextField(
                value = state.query,
                onValueChange = model::setQuery,
                label = { Text(t("Buscar em %s", t(state.tab.label))) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                // O campo de texto come as setas (cursor): para baixo, o
                // controle sai dele para a lista.
                modifier = Modifier.fillMaxWidth().padding(12.dp).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                        focusManager.moveFocus(FocusDirection.Down)
                    } else false
                },
            )

            state.indexProgress?.let { (done, total) ->
                LinearProgressIndicator(
                    progress = { done.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }

            if (settings.resume.isNotEmpty() && state.query.isBlank()) {
                Text(
                    text = t("Continuar assistindo"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(count = settings.resume.size, key = { settings.resume[it].key }) { i ->
                        val entry = settings.resume[i]
                        Column(Modifier.width(160.dp)) {
                            HoverCard(onClick = { onResume(entry) }) {
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
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            val shown = model.visibleItems(state, settings.favorites.map { it.toCatalogItem() })

            when {
                state.tab == BrowseTab.FREE -> FreeChannelList(model.visibleFree(state), onOpenFree)
                state.tab.section != null && !state.loggedIn -> LoginPrompt(onLogin)
                state.error != null -> ErrorBox(state.error!!, onRetry = model::retry)
                state.loadingItems -> LoadingBox()

                shown.isEmpty() && state.query.length >= 2 -> Column {
                    EmptyBox(t("Nada encontrado nas categorias já abertas."), Modifier.weight(1f))
                    TextButton(
                        onClick = model::indexEverything,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp),
                    ) {
                        Text(t("Carregar todas as categorias de %s", t(state.tab.label)))
                    }
                }

                shown.isEmpty() -> EmptyBox(t("Escolha uma categoria à esquerda."))

                state.tab == BrowseTab.LIVE && state.query.length < 2 -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(count = shown.size, key = { shown[it].key }) { index ->
                        val item = shown[index]
                        val isFavorite = item.key in settings.favoriteKeys
                        ItemRow(
                            title = item.title,
                            imageUrl = item.image,
                            subtitle = (item as? Channel)?.number?.let { t("Canal %s", it) },
                            onClick = { onOpenItems(shown, index) },
                            trailing = {
                                IconButton(
                                    onClick = { app.settings.toggleFavorite(item) },
                                    modifier = Modifier.focusRing(),
                                ) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Default.Favorite
                                        else Icons.Default.FavoriteBorder,
                                        contentDescription = t("Favoritar"),
                                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(count = shown.size, key = { shown[it].key }) { index ->
                        val item = shown[index]
                        PosterCard(
                            title = item.title,
                            imageUrl = item.image,
                            onClick = { onOpenItems(shown, index) },
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
        EmptyBox(t("Nenhum canal com esse nome."))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = t("Canais abertos, com o stream que a própria emissora publica. Sem conta."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        items(count = channels.size, key = { channels[it].key }) { index ->
            val channel = channels[index]
            ItemRow(
                title = channel.name,
                imageUrl = channel.logo,
                subtitle = t("Stream oficial · %s", channel.sourceHost),
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
            text = t("Canais ao vivo, filmes e séries vêm da sua conta IPTV.\nOs canais da aba Grátis não precisam dela."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onLogin, modifier = Modifier.focusRing()) { Text(t("Entrar com a conta")) }
    }
}

enum class BrowseTab(val label: String, val section: Section?) {
    LIVE("Ao vivo", Section.LIVE),
    MOVIES("Filmes", Section.MOVIES),
    SERIES("Séries", Section.SERIES),
    /** Canais abertos de [br.com.iordan.ranga.data.FreeChannels]; sem seção Xtream. */
    FREE("Grátis", null),
    FAVORITES("Favoritos", null),
}
