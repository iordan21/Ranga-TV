package br.com.iordan.ranga.pc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.iordan.ranga.data.AccountInfo
import br.com.iordan.ranga.data.LiveFormat
import br.com.iordan.ranga.data.PrefsCodec
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.RangaDefaults
import br.com.iordan.ranga.pc.AppState
import br.com.iordan.ranga.pc.SettingsData
import br.com.iordan.ranga.pc.player.PlayerLog
import br.com.iordan.ranga.pc.player.VideoMode
import br.com.iordan.ranga.pc.player.VlcEngine
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsView(
    app: AppState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val loggedIn = settings.credentials.isComplete

    // Chaveado pela credencial: entrar pelo botão desta tela (o login abre por
    // cima dela) refaz a consulta ao voltar, em vez de mostrar conta nenhuma.
    val account by produceState<AccountInfo?>(initialValue = null, settings.credentials) {
        value = if (loggedIn) runCatching { app.catalog.login(settings.credentials) }.getOrNull()
        else null
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Text(
                text = "Configurações",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Group("Motor de vídeo") {
            when (VlcEngine.source) {
                VlcEngine.Source.BUNDLED -> Text(
                    text = "Embutido no app" +
                        (VlcEngine.bundledVersion?.let { " — libvlc $it" } ?: "") +
                        ". Nada para instalar.",
                    color = MaterialTheme.colorScheme.primary,
                )

                // Só acontece se a pasta do app perder arquivos: antivírus,
                // cópia pela metade, instalação interrompida. Toca do mesmo
                // jeito, mas quero que apareça — é a pista de por que uma
                // máquina se comporta diferente das outras.
                VlcEngine.Source.SYSTEM -> Text(
                    text = "Usando o VLC instalado nesta máquina. O motor que vem " +
                        "no pacote não foi encontrado — reinstale o Ranga se isso repetir.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                VlcEngine.Source.NONE -> {
                    Text(
                        "Nenhum motor de vídeo carregou. O catálogo funciona, mas nada toca. " +
                            "Reinstale o Ranga; se continuar, instalar o VLC 64 bits resolve.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { openInBrowser(VlcEngine.DOWNLOAD_URL) }) {
                        Text("Baixar o VLC")
                    }
                }
            }
        }

        Group("Como o vídeo aparece", "Trocar vale da próxima vez que você abrir um canal.") {
            VideoMode.entries.forEach { mode ->
                val selected = settings.videoMode == mode
                Button(
                    onClick = { app.settings.setVideoMode(mode) },
                    modifier = Modifier.width(360.dp),
                    colors = if (selected) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text(mode.label)
                }
            }
            Text(
                text = settings.videoMode.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Group("Conta", if (loggedIn) null else "Só para quem tem conta IPTV. A aba Grátis não precisa.") {
            if (!loggedIn) {
                Button(onClick = onLogin, modifier = Modifier.width(360.dp)) {
                    Text("Entrar com a conta")
                }
            } else {
                Line("Servidor", settings.credentials.host)
                Line("Usuário", settings.credentials.username)
                account?.let {
                    Line("Situação", it.status)
                    it.expiresAtEpochSecs?.let { exp -> Line("Vence em", formatDate(exp)) }
                    Line("Conexões", "${it.activeConnections ?: 0} de ${it.maxConnections ?: "?"} em uso")
                }
            }
        }

        if (loggedIn) ServersGroup(app, settings)

        Group("Formato do canal ao vivo", "Qual o app tenta primeiro.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveFormat.entries.forEach { format ->
                    val selected = settings.liveFormat == format
                    Button(
                        onClick = { app.settings.setLiveFormat(format) },
                        colors = if (selected) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Text(format.label)
                    }
                }
            }
        }

        Group("User-Agent", "Se o catálogo abre mas nenhum vídeo toca, troque aqui.") {
            RangaDefaults.USER_AGENT_PRESETS.forEach { preset ->
                val selected = settings.userAgent == preset
                Button(
                    onClick = { app.settings.setUserAgent(preset) },
                    modifier = Modifier.width(360.dp),
                    colors = if (selected) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text(preset)
                }
            }
        }

        Group("Dados", if (loggedIn) "Sair apaga só a credencial. Favoritos e histórico ficam." else null) {
            Text(
                text = "Arquivo: ${br.com.iordan.ranga.pc.Settings.defaultFile()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // O caminho do log fica à vista porque ele só serve se a pessoa
            // souber onde está na hora em que algo deu errado.
            Text(
                text = "Log do motor de vídeo: ${PlayerLog.FILE}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { app.settings.clearResume() },
                modifier = Modifier.width(360.dp),
            ) {
                Text("Limpar \"continuar assistindo\"")
            }
            if (loggedIn) {
                Button(
                    onClick = {
                        scope.launch { app.catalog.invalidate() }
                        app.settings.clearCredentials()
                        onLoggedOut()
                    },
                    modifier = Modifier.width(360.dp),
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

private sealed interface ServerCheck {
    data object Testing : ServerCheck
    data class Ok(val millis: Long) : ServerCheck
    data class Failed(val message: String) : ServerCheck
}

/**
 * Escolha de DNS, igual à do app de TV: provedor passa vários endereços para
 * a mesma conta, e quando um cai dá para trocar sem sair e entrar de novo.
 */
@Composable
private fun ServersGroup(app: AppState, settings: SettingsData) {
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<Map<String, ServerCheck>>(emptyMap()) }
    var newServer by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var switchingTo by remember { mutableStateOf<String?>(null) }
    val current = settings.credentials.host

    suspend fun check(host: String): ServerCheck = try {
        val started = System.nanoTime()
        app.catalog.login(settings.credentials.copy(host = host))
        ServerCheck.Ok((System.nanoTime() - started) / 1_000_000)
    } catch (e: XtreamException) {
        ServerCheck.Failed(e.userMessage)
    } catch (e: Exception) {
        ServerCheck.Failed(e.message ?: "Não respondeu.")
    }

    Group(
        "Servidor (DNS)",
        "Endereços da mesma conta. Se um parar, escolha outro: usuário e senha continuam os mesmos.",
    ) {
        settings.servers.forEach { host ->
            val active = host == current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (active || switchingTo != null) return@Button
                        switchingTo = host
                        message = null
                        checks = checks + (host to ServerCheck.Testing)
                        scope.launch {
                            // Testa antes de trocar: ir direto para um DNS fora
                            // do ar deixaria o app inteiro sem catálogo.
                            val result = check(host)
                            if (result is ServerCheck.Ok) {
                                app.catalog.invalidate()
                                app.settings.setHost(host)
                            } else if (result is ServerCheck.Failed) {
                                message = "Continua no atual. $host: ${result.message}"
                            }
                            checks = checks + (host to result)
                            switchingTo = null
                        }
                    },
                    enabled = switchingTo == null || switchingTo == host,
                    modifier = Modifier.width(360.dp),
                    colors = if (active) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(if (active) "$host  (em uso)" else host)
                        when (val c = checks[host]) {
                            null -> Unit
                            ServerCheck.Testing -> Text("testando…", style = MaterialTheme.typography.bodySmall)
                            is ServerCheck.Ok ->
                                Text("respondeu em ${c.millis} ms", style = MaterialTheme.typography.bodySmall)
                            is ServerCheck.Failed ->
                                Text("falhou: ${c.message}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (!active) {
                    OutlinedButton(
                        onClick = {
                            app.settings.removeServer(host)
                            checks = checks - host
                        },
                        enabled = switchingTo == null,
                    ) {
                        Text("Remover")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                message = null
                val hosts = settings.servers
                checks = hosts.associateWith { ServerCheck.Testing }
                // Todos de uma vez: um DNS morto segura a resposta até o
                // timeout de conexão, e em fila seriam minutos de espera.
                hosts.forEach { host ->
                    scope.launch {
                        val result = check(host)
                        checks = checks + (host to result)
                    }
                }
            },
            enabled = switchingTo == null,
            modifier = Modifier.width(360.dp),
        ) {
            Text("Testar todos")
        }

        OutlinedTextField(
            value = newServer,
            onValueChange = { newServer = it; message = null },
            label = { Text("Adicionar DNS") },
            placeholder = { Text("Cole um ou mais endereços, um por linha") },
            maxLines = 5,
            modifier = Modifier.width(360.dp),
        )
        Button(
            onClick = {
                val parsed = PrefsCodec.parseServers(newServer)
                if (parsed.isEmpty()) {
                    message = "Nenhum endereço válido no que foi digitado."
                } else {
                    app.settings.addServers(parsed)
                    newServer = ""
                }
            },
            enabled = newServer.isNotBlank(),
            modifier = Modifier.width(360.dp),
        ) {
            Text("Adicionar")
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Group(title: String, hint: String? = null, content: @Composable () -> Unit) {
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
    Row(Modifier.width(420.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))

private fun formatDate(epochSecs: Long) = dateFormat.format(Date(epochSecs * 1000))

/** `Desktop.browse` em try: em Windows sem navegador padrão configurado, lança. */
internal fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }
}
