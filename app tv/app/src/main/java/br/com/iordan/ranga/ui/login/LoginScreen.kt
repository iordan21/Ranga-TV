package br.com.iordan.ranga.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.iordan.ranga.AppContainer
import br.com.iordan.ranga.appContainer
import br.com.iordan.ranga.data.Credentials
import br.com.iordan.ranga.data.Prefill
import br.com.iordan.ranga.data.XtreamException
import br.com.iordan.ranga.data.XtreamUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    init {
        // Se já houver credencial salva (ex.: usuário voltou pelo logout),
        // pré-preenche para não obrigar a redigitar tudo.
        viewModelScope.launch {
            val saved = Prefill.orSaved(container.session.credentials.first())
            _state.update {
                it.copy(host = saved.host, username = saved.username, password = saved.password)
            }
        }
    }

    fun onHost(value: String) = _state.update { it.copy(host = value, error = null) }
    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = _state.value
        if (current.loading) return

        val creds = Credentials(
            host = current.host.trim(),
            username = current.username.trim(),
            password = current.password.trim(),
        )
        if (!creds.isComplete) {
            _state.update { it.copy(error = "Preencha servidor, usuário e senha.") }
            return
        }
        if (XtreamUrls.normalizeHost(creds.host) == null) {
            _state.update { it.copy(error = "Endereço do servidor inválido.") }
            return
        }

        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // Valida contra o painel ANTES de salvar. Salvar credencial
                // que não funciona só empurra o erro para a próxima tela.
                container.catalog.login(creds)
                // Normalizo o host aqui: o usuário pode ter digitado
                // "painel.exemplo" e daí para frente tudo usa a forma canônica.
                val normalized = XtreamUrls.normalizeHost(creds.host)!!
                container.session.saveCredentials(
                    creds.copy(host = XtreamUrls.displayHost(normalized))
                )
                container.catalog.invalidate()
                _state.update { it.copy(loading = false, success = true) }
            } catch (e: XtreamException) {
                _state.update { it.copy(loading = false, error = e.userMessage) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Falha inesperada.") }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LoginViewModel(this.appContainer) }
        }
    }
}

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onBack: () -> Unit,
    viewModel: LoginViewModel = viewModel(factory = LoginViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(state.success) {
        if (state.success) onLoggedIn()
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Column(
                // widthIn segura o formulário no centro em tela de TV,
                // onde esticar campo de texto por 1920px fica ridículo.
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Ranga",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Dados do painel da sua conta IPTV. " +
                        "A aba Grátis funciona sem isto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::onHost,
                    label = { Text("Servidor") },
                    placeholder = { Text("http://seu-painel.exemplo:80") },
                    singleLine = true,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsername,
                    label = { Text("Usuário") },
                    singleLine = true,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPassword,
                    label = { Text("Senha") },
                    singleLine = true,
                    enabled = !state.loading,
                    visualTransformation =
                        if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Ocultar" else "Ver")
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button(
                    onClick = viewModel::submit,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.widthIn(max = 20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Entrar")
                    }
                }
                // No controle remoto o Voltar do aparelho já sai daqui; o botão
                // existe para o toque e para deixar claro que entrar é opcional.
                TextButton(
                    onClick = onBack,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Voltar")
                }
            }
        }
    }
}
