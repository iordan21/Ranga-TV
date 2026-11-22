package br.com.iordan.ranga.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {

    @Test
    fun `senha certa passa e errada nao`() {
        val stored = Access.hashPassword("sofa-da-sala")
        assertTrue(Access.checkPassword("sofa-da-sala", stored))
        assertFalse(Access.checkPassword("sofa-da-sal", stored))
        assertFalse(Access.checkPassword("", stored))
    }

    @Test
    fun `a mesma senha gera hashes diferentes por causa do sal`() {
        assertNotEquals(Access.hashPassword("123456"), Access.hashPassword("123456"))
    }

    @Test
    fun `hash corrompido no arquivo recusa em vez de quebrar`() {
        assertFalse(Access.checkPassword("qualquer", "nao-e-um-hash"))
        assertFalse(Access.checkPassword("qualquer", "a:b:c"))
    }

    @Test
    fun `assinatura so vale para o mesmo link e a mesma chave`() {
        val secret = Access.newSecret()
        val link = "http://cdn.exemplo/seg1.ts"
        val sig = Access.sign(secret, link)
        assertTrue(Access.verify(secret, link, sig))
        assertFalse(Access.verify(secret, "http://192.168.0.1/admin", sig))
        assertFalse(Access.verify(Access.newSecret(), link, sig))
        assertFalse(Access.verify(secret, link, ""))
    }

    @Test
    fun `cinco erros travam o login e sucesso limpa a contagem`() {
        var now = 0L
        val throttle = LoginThrottle { now }
        repeat(4) { throttle.recordFailure() }
        assertFalse(throttle.isLocked())
        throttle.recordSuccess()
        repeat(4) { throttle.recordFailure() }
        assertFalse(throttle.isLocked())
        throttle.recordFailure()
        assertTrue(throttle.isLocked())
        now += 5 * 60_000 + 1
        assertFalse(throttle.isLocked())
    }
}
