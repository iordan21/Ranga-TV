package br.com.iordan.ranga.data

/**
 * Credencial que já vem digitada na tela de login.
 *
 * Está vazia porque este repositório é público. Credencial em código-fonte é
 * credencial publicada: quem clona o repositório lê, e quem baixa o APK
 * também — o valor vai compilado lá dentro, descompilar é trivial.
 *
 * Para pré-preencher na SUA máquina, escreva os dados aqui e não suba a
 * alteração. Depois de clonar, isto evita o commit por engano:
 *
 * ```
 * git update-index --skip-worktree core/src/main/kotlin/br/com/iordan/ranga/data/Prefill.kt
 * ```
 *
 * Voltar para [Credentials.EMPTY] devolve o comportamento de campos vazios.
 *
 * Isto nunca pulou o login: os campos só vinham preenchidos, e a validação
 * contra o painel continua acontecendo quando você aperta Entrar. Depois da
 * primeira vez a credencial fica salva e a tela não aparece mais — o custo de
 * deixar vazio é digitar uma vez por aparelho.
 */
object Prefill {

    val CREDENTIALS = Credentials.EMPTY

    /** Usa o que está salvo; só cai no pré-preenchido quando não há nada. */
    fun orSaved(saved: Credentials): Credentials =
        if (saved.isComplete) saved else CREDENTIALS
}
