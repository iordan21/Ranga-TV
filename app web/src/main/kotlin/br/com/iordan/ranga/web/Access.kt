package br.com.iordan.ranga.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Senha de acesso ao Ranga Web e assinatura dos links de vídeo.
 *
 * Existe porque o servidor pode sair da rede de casa (playit, por exemplo).
 * Aberto na internet sem senha, quem achasse o endereço assistiria com a conta
 * IPTV do dono — e derrubaria a dele, que permite poucas conexões.
 *
 * A senha não é guardada: só o hash PBKDF2 com sal. Quem ler o web.json não
 * descobre a senha, só consegue testar chutes, e cada chute custa caro.
 */
object Access {

    private const val ITERATIONS = 120_000
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun newSecret(): String = b64.encodeToString(ByteArray(32).also(random::nextBytes))

    fun newSessionToken(): String = newSecret()

    /** "sal:hash", os dois em Base64. */
    fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return b64.encodeToString(salt) + ":" + b64.encodeToString(pbkdf2(password, salt))
    }

    fun checkPassword(password: String, stored: String): Boolean {
        val (saltText, hashText) = stored.split(":").takeIf { it.size == 2 } ?: return false
        val salt = runCatching { Base64.getUrlDecoder().decode(saltText) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getUrlDecoder().decode(hashText) }.getOrNull() ?: return false
        // Comparação em tempo constante: `==` sairia no primeiro byte
        // diferente, e o tempo de resposta vazaria quanto do hash bateu.
        return MessageDigest.isEqual(pbkdf2(password, salt), expected)
    }

    /**
     * Assinatura de um link repassado pelo `/hls`. Sem ela, o `/hls?u=` seria
     * um proxy aberto: qualquer um faria o PC buscar qualquer endereço —
     * inclusive o roteador e outros aparelhos da rede de casa. Assinado, só
     * passa o que saiu de uma playlist que o próprio servidor montou.
     */
    fun sign(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return b64.encodeToString(mac.doFinal(value.toByteArray())).take(22)
    }

    fun verify(secret: String, value: String, signature: String): Boolean =
        MessageDigest.isEqual(sign(secret, value).toByteArray(), signature.toByteArray())

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256))
            .encoded
}

/**
 * Freio contra chute de senha: depois de 5 erros em 10 minutos, recusa tudo
 * por 5 minutos. Global, e não por IP, porque pelo playit todo mundo chega
 * como 127.0.0.1 — o túnel é um programa local repassando a conexão.
 */
class LoginThrottle(private val now: () -> Long = System::currentTimeMillis) {
    private val failures = ArrayDeque<Long>()
    private var lockedUntil = 0L

    @Synchronized
    fun isLocked(): Boolean = now() < lockedUntil

    @Synchronized
    fun recordFailure() {
        val t = now()
        failures.addLast(t)
        while (failures.isNotEmpty() && t - failures.first() > 10 * 60_000) failures.removeFirst()
        if (failures.size >= 5) {
            lockedUntil = t + 5 * 60_000
            failures.clear()
        }
    }

    @Synchronized
    fun recordSuccess() = failures.clear()
}
