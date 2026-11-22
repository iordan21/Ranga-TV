package br.com.iordan.ranga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.iordan.ranga.AppContainer
import br.com.iordan.ranga.appContainer
import br.com.iordan.ranga.data.AccountInfo
import br.com.iordan.ranga.data.LiveFormat
import br.com.iordan.ranga.data.PrefsCodec
import br.com.iordan.ranga.data.RangaDefaults
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.ui.common.ClickToEditField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsUiState(
    val host: String = "",
    val username: String = "",
    /** Derivado da credencial salva; sem ela o grupo "Conta" vira só o botão de entrar. */
    val loggedIn: Boolean = false,
    val liveFormat: LiveFormat = LiveFormat.HLS,
    val userAgent: String = RangaDefaults.USER_AGENT,
    val account: AccountInfo? = null,
    val accountError: String? = null,
    val loggedOut: Boolean = false,
    /** Todos os DNS da conta; [host] é o que está em uso. */
    val servers: List<String> = emptyList(),
    val serverChecks: Map<String, ServerCheck> = emptyMap(),
    val newServer: String = "",
    val serverError: String? = null,
    /** DNS sendo testado antes de virar o atual; trava os outros botões enquanto isso. */
    val switchingTo: String? = null,
)

sealed interface ServerCheck {
    data object Testing : ServerCheck
    data class Ok(val millis: Long) : ServerCheck
    data class Failed(val message: String) : ServerCheck
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // distinctUntilChanged porque o DataStore reemite a credencial a
            // cada preferência salva (trocar o User-Agent aqui mesmo, por
            // exemplo) — sem ele, cada toque refaria a consulta ao painel.
            container.session.credentials.distinctUntilChanged().collect { creds ->
                _state.update {
                    it.copy(
                        host = creds.host,
                        username = creds.username,
                        loggedIn = creds.isComplete,
                        // Saiu: a situação da conta antiga não pode ficar na tela.
                        account = if (creds.isComplete) it.account else null,
                    )
                }
                // Cobre o boot com credencial salva e o login feito pelo botão
                // desta tela (a tela de login fica em cima dela na pilha).
                if (creds.isComplete) refreshAccount()
            }
        }
        viewModelScope.launch {
            container.session.servers.collect { list -> _state.update { it.copy(servers = list) } }
        }
        viewModelScope.launch {
            container.session.liveFormat.collect { f -> _state.update { it.copy(liveFormat = f) } }
        }
        viewModelScope.launch {
            container.session.userAgent.collect { ua -> _state.update { it.copy(userAgent = ua) } }
        }
    }

    fun refreshAccount() {
        viewModelScope.launch {
            try {
                val creds = container.session.credentials.first()
                if (!creds.isComplete) return@launch
                val info = container.catalog.login(creds)
                _state.update { it.copy(account = info, accountError = null) }
            } catch (e: Exception) {
                _state.update { it.copy(accountError = e.message) }
            }
        }
    }

    fun onNewServer(value: String) = _state.update { it.copy(newServer = value, serverError = null) }

    fun addServers() {
        val parsed = PrefsCodec.parseServers(_state.value.newServer)
        if (parsed.isEmpty()) {
            _state.update { it.copy(serverError = "Nenhum endereço válido no que foi digitado.") }
            return
        }
        viewModelScope.launch {
            container.session.addServers(parsed)
            _state.update { it.copy(newServer = "", serverError = null) }
        }
    }

    fun removeServer(host: String) {
        // O atual não sai: sem ele a conta fica sem endereço nenhum.
        if (host == _state.value.host) return
        viewModelScope.launch {
            container.session.removeServer(host)
            _state.update { it.copy(serverChecks = it.serverChecks - host) }
        }
    }

    /**
     * Testa antes de trocar. Trocar direto para um DNS fora do ar deixaria o
     * app inteiro sem catálogo, e o erro só apareceria na tela seguinte, longe
     * da escolha que o causou.
     */
    fun selectServer(host: String) {
        val current = _state.value
        if (host == current.host || current.switchingTo != null) return
        _state.update {
            it.copy(
                switchingTo = host,
                serverError = null,
                serverChecks = it.serverChecks + (host to ServerCheck.Testing),
            )
        }
        viewModelScope.launch {
            val check = check(host)
            if (check is ServerCheck.Ok) {
                // O cache é do endereço antigo. Os IDs devem ser os mesmos
                // (é o mesmo painel), mas se o provedor apontou o DNS para
                // outro servidor, item velho na grade abriria canal errado.
                container.catalog.invalidate()
                container.session.setHost(host)
            }
            _state.update {
                it.copy(
                    switchingTo = null,
                    serverChecks = it.serverChecks + (host to check),
                    serverError = (check as? ServerCheck.Failed)?.let { f -> "Continua no atual. $host: ${f.message}" },
                )
            }
        }
    }

    /**
     * Todos de uma vez, e não em fila: um DNS morto segura a resposta até o
     * connectTimeout (15 s). Cinco fora do ar em fila dariam mais de um minuto
     * olhando para a tela. É só o login, sem stream — não conta no limite de
     * conexões da conta.
     */
    fun testAllServers() {
        val hosts = _state.value.servers
        _state.update { s -> s.copy(serverChecks = hosts.associateWith { ServerCheck.Testing }, serverError = null) }
        hosts.forEach { host ->
            viewModelScope.launch {
                val check = check(host)
                _state.update { it.copy(serverChecks = it.serverChecks + (host to check)) }
            }
        }
    }

    private suspend fun check(host: String): ServerCheck {
        return try {
            val creds = container.session.credentials.first().copy(host = host)
            val started = System.nanoTime()
            container.catalog.login(creds)
            ServerCheck.Ok((System.nanoTime() - started) / 1_000_000)
        } catch (e: XtreamException) {
            ServerCheck.Failed(e.userMessage)
        } catch (e: Exception) {
            ServerCheck.Failed(e.message ?: "Não respondeu.")
        }
    }

    fun setLiveFormat(format: LiveFormat) {
        viewModelScope.launch { container.session.setLiveFormat(format) }
    }

    fun setUserAgent(value: String) {
        viewModelScope.launch { container.session.setUserAgent(value) }
    }

    fun clearResume() {
        viewModelScope.launch { container.session.clearResume() }
    }

    fun logout() {
        viewModelScope.launch {
            container.catalog.invalidate()
            container.session.clearCredentials()
            _state.update { it.copy(loggedOut = true) }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SettingsViewModel(this.appContainer) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) onLoggedOut()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Group("Conta", if (state.loggedIn) null else "Só para quem tem conta IPTV. A aba Grátis não precisa.") {
                if (!state.loggedIn) {
                    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                        Text("Entrar com a conta")
                    }
                } else {
                    Line("Servidor", state.host)
                    Line("Usuário", state.username)
                    state.account?.let { account ->
                        Line("Situação", account.status)
                        account.expiresAtEpochSecs?.let { Line("Vence em", formatDate(it)) }
                        Line(
                            "Conexões",
                            "${account.activeConnections ?: 0} de ${account.maxConnections ?: "?"} em uso",
                        )
                        if (account.allowedFormats.isNotEmpty()) {
                            Line("Formatos", account.allowedFormats.joinToString(", "))
                        }
                    }
                    state.accountError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (state.loggedIn) {
                ServersGroup(
                    state = state,
                    onSelect = viewModel::selectServer,
                    onRemove = viewModel::removeServer,
                    onTestAll = viewModel::testAllServers,
                    onNewServer = viewModel::onNewServer,
                    onAdd = viewModel::addServers,
                )
            }

            Group("Formato do canal ao vivo", "Qual o app tenta primeiro. Falhou, ele tenta o outro sozinho.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveFormat.entries.forEach { format ->
                        val selected = state.liveFormat == format
                        Button(
                            onClick = { viewModel.setLiveFormat(format) },
                            colors = if (selected) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(format.label)
                        }
                    }
                }
            }

            Group("User-Agent", "Se o catálogo abre mas nenhum vídeo toca, troque aqui.") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RangaDefaults.USER_AGENT_PRESETS.forEach { preset ->
                        val selected = state.userAgent == preset
                        Button(
                            onClick = { viewModel.setUserAgent(preset) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (selected) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(preset)
                        }
                    }
                }
            }

            Group(
                "Dados",
                if (state.loggedIn) "Sair apaga só a credencial. Favoritos e histórico ficam." else null,
            ) {
                OutlinedButton(
                    onClick = viewModel::clearResume,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Limpar \"continuar assistindo\"")
                }
                if (state.loggedIn) {
                    Button(
                        onClick = viewModel::logout,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("Sair da conta")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServersGroup(
    state: SettingsUiState,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTestAll: () -> Unit,
    onNewServer: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Group(
        "Servidor (DNS)",
        "Endereços da mesma conta. Se um parar, escolha outro: usuário e senha continuam os mesmos.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.servers.forEach { host ->
                val active = host == state.host
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { onSelect(host) },
                        enabled = state.switchingTo == null || state.switchingTo == host,
                        modifier = Modifier.weight(1f),
                        colors = if (active) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(if (active) "$host  (em uso)" else host)
                            serverCheckLabel(state.serverChecks[host])?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (!active) {
                        OutlinedButton(
                            onClick = { onRemove(host) },
                            enabled = state.switchingTo == null,
                        ) {
                            Text("Remover")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onTestAll,
                enabled = state.switchingTo == null && state.servers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Testar todos")
            }

            ClickToEditField(
                value = state.newServer,
                onValueChange = onNewServer,
                label = "Adicionar DNS",
                placeholder = "http://outro-endereco.exemplo",
                // Não é uma linha só: colar a lista inteira do provedor, com
                // uma linha por endereço, tem que funcionar no celular.
                maxLines = 5,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onAdd,
                enabled = state.newServer.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Adicionar")
            }

            state.serverError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun serverCheckLabel(check: ServerCheck?): String? = when (check) {
    null -> null
    ServerCheck.Testing -> "testando…"
    is ServerCheck.Ok -> "respondeu em ${check.millis} ms"
    is ServerCheck.Failed -> "falhou: ${check.message}"
}

@Composable
private fun Group(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))

private fun formatDate(epochSecs: Long) = dateFormat.format(Date(epochSecs * 1000))
