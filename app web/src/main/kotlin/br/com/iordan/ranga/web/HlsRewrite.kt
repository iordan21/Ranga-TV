package br.com.iordan.ranga.web

import okhttp3.HttpUrl

/**
 * Reescreve uma playlist HLS para que tudo que ela aponta passe pelo servidor.
 *
 * Por que passar tudo por aqui: o navegador não deixa o hls.js buscar arquivo
 * em outro domínio sem permissão (CORS), e painel IPTV não dá essa permissão.
 * Então a playlist que chega ao navegador só pode ter endereços do próprio
 * Ranga Web — inclusive as sub-playlists de qualidade e as chaves de
 * criptografia, que vêm escondidas em atributos `URI="..."`.
 *
 * Endereço relativo é resolvido contra [base], que precisa ser a URL *final*,
 * depois dos redirecionamentos: o painel costuma mandar para outro servidor
 * com um token, e os segmentos são relativos a esse, não ao original.
 */
object HlsRewrite {

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    fun rewrite(playlist: String, base: HttpUrl, proxify: (String) -> String): String =
        playlist.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> raw
                line.startsWith("#") -> uriAttribute.replace(raw) { match ->
                    "URI=\"${proxify(resolve(base, match.groupValues[1]))}\""
                }
                else -> proxify(resolve(base, line))
            }
        }

    private fun resolve(base: HttpUrl, ref: String): String =
        base.resolve(ref)?.toString() ?: ref
}
