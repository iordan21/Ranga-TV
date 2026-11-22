package br.com.iordan.ranga.pc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.iordan.ranga.data.Credentials
import br.com.iordan.ranga.data.Prefill
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.XtreamUrls
import br.com.iordan.ranga.pc.AppState
import kotlinx.coroutines.launch

@Composable
fun LoginView(app: AppState, onLoggedIn: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved = Prefill.orSaved(app.settings.current.credentials)

    var host by remember { mutableStateOf(saved.host) }
    var username by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (loading) return
        val creds = Credentials(host.trim(), username.trim(), password.trim())
        if (!creds.isComplete) {
            error = "Preencha servidor, usuário e senha."
            return
        }
        val normalized = XtreamUrls.normalizeHost(creds.host)
        if (normalized == null) {
            error = "Endereço do servidor inválido."
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                // Valida contra o painel ANTES de gravar: credencial que não
                // funciona salva só empurra o erro para a próxima tela.
                app.catalog.login(creds)
                app.settings.saveCredentials(
                    creds.copy(host = XtreamUrls.displayHost(normalized))
                )
                app.catalog.invalidate()
                onLoggedIn()
            } catch (e: XtreamException) {
                error = e.userMessage
            } catch (e: Exception) {
                error = e.message ?: "Falha inesperada."
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.width(420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Ranga",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Dados do painel da sua conta IPTV. A aba Grátis funciona sem isto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; error = null },
                label = { Text("Servidor") },
                placeholder = { Text("http://seu-painel.exemplo:80") },
                singleLine = true,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text("Usuário") },
                singleLine = true,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Senha") },
                singleLine = true,
                enabled = !loading,
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Ocultar" else "Ver")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { submit() },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Entrar")
                }
            }
            // Entrar é opcional; o botão deixa isso explícito e dá a saída.
            TextButton(
                onClick = onBack,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Voltar")
            }
        }
    }
}
