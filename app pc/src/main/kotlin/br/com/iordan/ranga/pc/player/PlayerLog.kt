package br.com.iordan.ranga.pc.player

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Grava as reclamações da libvlc em `%APPDATA%\Ranga\ranga.log`.
 *
 * Existe porque diagnóstico à distância sem log vira adivinhação. "Não dá
 * imagem" pode ser codec ausente, servidor recusando, limite de conexões,
 * formato errado — e a libvlc sabe qual dos quatro é, ela só não tinha para
 * onde falar. Agora tem, e o arquivo cabe num anexo.
 *
 * Três decisões:
 *
 *  - **só WARNING para cima**. No nível de depuração a libvlc escreve milhares
 *    de linhas por minuto, e o arquivo vira inútil por excesso;
 *  - **zera a cada abertura do app**. O log que interessa é o da vez em que o
 *    problema aconteceu, não o histórico de um mês;
 *  - **falhar aqui não derruba nada**. Se não der para escrever o arquivo, o
 *    player continua. Log é ferramenta de conserto, não requisito para tocar.
 */
class PlayerLog private constructor(
    private val nativeLog: NativeLog,
    private val writer: Writer,
) {

    fun release() {
        runCatching { nativeLog.release() }
        runCatching { writer.close() }
    }

    companion object {

        /** Ao lado do `settings.json`, pela mesma regra de pasta. */
        val FILE: Path = Path.of(
            System.getenv("APPDATA") ?: System.getProperty("user.home"),
            "Ranga",
            "ranga.log",
        )

        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun attach(factory: MediaPlayerFactory): PlayerLog? = runCatching {
            Files.createDirectories(FILE.parent)
            val writer = Files.newBufferedWriter(
                FILE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            val nativeLog = factory.application().newLog() ?: return@runCatching null
            nativeLog.setLevel(LogLevel.WARNING)

            writer.write("libvlc ${factory.application().version()}\n")
            writer.flush()

            // O callback vem de thread nativa, e de várias. O lock protege o
            // writer; o flush a cada linha é de propósito, porque o caso que eu
            // quero investigar às vezes termina em travamento, e log em buffer
            // que não chegou ao disco não serve para nada.
            val lock = Any()
            nativeLog.addLogListener { level, module, _, _, _, _, _, message ->
                synchronized(lock) {
                    runCatching {
                        writer.write("${LocalTime.now().format(TIME)} $level $module: $message\n")
                        writer.flush()
                    }
                }
            }

            PlayerLog(nativeLog, writer)
        }.getOrNull()
    }
}
