package br.com.iordan.ranga.player

import br.com.iordan.ranga.data.Playable

/**
 * Fila do player, compartilhada por referência entre a UI e a PlayerActivity.
 *
 * Por que não passar a lista no Intent? Porque uma categoria de canais pode ter
 * centenas de itens, e Intent trafega por Binder, que tem limite de ~1 MB por
 * transação. Passar isso gera `TransactionTooLargeException` — e o pior é que
 * ela só aparece nas categorias grandes, ou seja, em produção e não no teste.
 *
 * Então o Intent leva só o índice inicial, e a lista fica aqui no singleton do
 * processo. É seguro porque a PlayerActivity é aberta imediatamente depois de
 * [submit], no mesmo processo.
 */
class PlaybackQueue {

    @Volatile
    var items: List<Playable> = emptyList()
        private set

    fun submit(items: List<Playable>) {
        this.items = items
    }

    fun itemAt(index: Int): Playable? = items.getOrNull(index)

    /** Zapping circular: passar do último volta pro primeiro. */
    fun indexAfter(index: Int): Int =
        if (items.isEmpty()) 0 else (index + 1).mod(items.size)

    fun indexBefore(index: Int): Int =
        if (items.isEmpty()) 0 else (index - 1).mod(items.size)
}
