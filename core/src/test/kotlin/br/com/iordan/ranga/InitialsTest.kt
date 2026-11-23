package br.com.iordan.ranga

import br.com.iordan.ranga.data.FreeChannels
import br.com.iordan.ranga.data.initials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialsTest {
    @Test
    fun `iniciais do nome do canal`() {
        assertEquals("TC", initials("TV Cultura"))
        assertEquals("CE", initials("Canal Educação"))
        assertEquals("FU", initials("Futura"))
        assertEquals("", initials(" - "))
    }

    @Test
    fun `todo canal gratis tem logo https do site oficial`() {
        FreeChannels.ALL.forEach { assertTrue(it.name, it.logo.orEmpty().startsWith("https://")) }
    }
}
