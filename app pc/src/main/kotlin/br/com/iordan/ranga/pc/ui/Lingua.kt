package br.com.iordan.ranga.pc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.com.iordan.ranga.i18n.Idioma

/**
 * O idioma da tela, como estado do Compose: [t] lê [current], então trocar o
 * idioma em Configurações redesenha na hora tudo o que usa [t].
 *
 * Quem escolhe, nesta ordem: `--idioma=` (o Ranga EMU passa o dele), o salvo em
 * settings.json e o idioma do Windows (fora dos quatro, inglês).
 */
object Lingua {
    var current by mutableStateOf(Idioma.Lang.PT)

    fun init(forced: String?, saved: String?) {
        current = Idioma.Lang.fromCode(forced) ?: Idioma.Lang.fromCode(saved) ?: Idioma.Lang.fromSystem()
    }
}

/** O texto em português (a chave) no idioma atual. */
fun t(pt: String): String = Idioma.translate(Lingua.current, pt)

/** Com `%s` / `%d` dentro: traduz e preenche. */
fun t(pt: String, vararg args: Any?): String = Idioma.format(Lingua.current, pt, *args)
