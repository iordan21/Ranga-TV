import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.compose") version "1.9.3"
}

val appVersion = "1.2.1"

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        // Mesma pasta que o app Android compila. Uma cópia só do protocolo
        // Xtream: corrigir uma esquisitice de painel conserta os dois apps.
        kotlin.srcDir("../core/src/main/kotlin")
    }
    test {
        kotlin.srcDir("../core/src/test/kotlin")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Só o -core: 0,8 MB e cobre quase tudo. O `compose.materialIconsExtended`
    // seria 36 MB — 40% do pacote sem o JRE — e eu usava dele quatro ícones,
    // que agora estão desenhados à mão em ui/RangaIcons.kt.
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")

    // Ponte para a libvlc. É o motor de vídeo do VLC: engole MPEG-TS e HLS de
    // IPTV muito melhor do que qualquer coisa disponível em Java puro.
    implementation("uk.co.caprica:vlcj:4.12.1")

    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // Coil 3.3.0 e não a mais nova, pelo mesmo motivo do app Android:
    // a partir da 3.4 ela exige kotlin-stdlib 2.3+.
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

/* ===========================================================================
 * libvlc embutida — o app não exige mais o VLC instalado
 *
 * Estas tasks baixam o zip oficial do VideoLAN, conferem o SHA-256 e extraem
 * só o que a reprodução usa para `resources/windows-x64/vlc`. O plugin do
 * Compose copia essa pasta para dentro do pacote e a aponta pela propriedade
 * `compose.application.resources.dir` — o mesmo caminho no `run` e no app
 * instalado. O lado do runtime está no cabeçalho de player/VlcEngine.kt.
 * ======================================================================== */

val vlcVersion = "3.0.23"

/*
 * SHA-256 publicado pelo VideoLAN ao lado do zip, no arquivo
 * `vlc-3.0.23-win64.zip.sha256` do mesmo diretório.
 *
 * Conferir não é zelo: o build puxa 80 MB de binário pela rede e o embute num
 * app que eu distribuo. Sem a conferência, o que chegar no lugar do zip entra
 * no pacote junto.
 */
val vlcSha256 = "992d19dbd0b8a7cde9167d2f7780b1ef6f92acc8a71acfa736101a21f35181e1"

val vlcZipUrl =
    "https://download.videolan.org/pub/videolan/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip"

// Cache fora de build/ de propósito: `clean` não pode custar um download novo.
val vlcZipFile: File =
    gradle.gradleUserHomeDir.resolve("caches/ranga-vlc/vlc-$vlcVersion-win64.zip")

val vlcResourcesRoot = layout.projectDirectory.dir("resources")
val vlcRuntimeDir = vlcResourcesRoot.dir("windows-x64/vlc")

/*
 * A pasta `plugins` do VLC tem 134 MB porque serve ao VLC inteiro, não a um
 * player de IPTV embutido. Estas categorias saem por completo:
 *
 *  - gui (19 MB) — as interfaces Qt e skins2. Quem desenha a tela aqui é o
 *    Compose, e a libvlc embutida nunca sobe interface própria;
 *  - access_output, stream_out, mux — o lado de *saída*: transcodificar,
 *    servir, gravar em rede. O Ranga só consome;
 *  - services_discovery, control, keystore, meta_engine, lua, visualization,
 *    video_splitter — descoberta de rede, hotkeys, cofre de senhas, leitura de
 *    tags, scripts de playlist e efeitos de tela. Nada é chamado daqui.
 */
val vlcDropDirs = listOf(
    "access_output", "control", "gui", "keystore", "lua", "meta_engine",
    "mux", "services_discovery", "stream_out", "video_splitter", "visualization",
)

/*
 * E estes plugins, pelo mesmo critério — codificador não serve para tocar, e
 * estas fontes de mídia não existem num painel Xtream:
 *
 *  - x264, x265, x26410b, aom, twolame, qsv: codificadores, 11 MB somados;
 *  - vpx, theora, schroedinger: formatos de web e de cinema que o painel não
 *    entrega, e que o libavcodec decodifica se um dia aparecerem;
 *  - vnc, dcp, bluray, dvd, dshow, dtv, cdda, sftp, srt, sid, gme: disco,
 *    webcam, antena, área de trabalho remota e música de videogame;
 *  - caca, sdl_image: saída de vídeo em ASCII e leitor de imagem antigo.
 *
 * Fica o essencial: libavcodec (H.264/H.265/AAC), adaptive (HLS/DASH), ts,
 * gnutls (HTTPS), freetype e libass (legenda), swscale, samplerate e as saídas
 * de vídeo Direct3D. Resultado medido: 65 MB de plugins em vez de 134 MB.
 */
val vlcDropPlugins = listOf(
    "libaccess_srt", "libaom", "libcaca", "libcdda", "libdcp", "libdshow",
    "libdtv", "libdvdnav", "libdvdread", "libgme", "liblibbluray", "libqsv",
    "libschroedinger", "libsdl_image", "libsftp", "libsid", "libskins2",
    "libtheora", "libtwolame", "libvnc", "libvpx", "libx264", "libx26410b",
    "libx265",
)

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val vlcDownload = tasks.register("vlcDownload") {
    group = "distribution"
    description = "Baixa o VLC $vlcVersion oficial (win64) e confere o SHA-256."

    val target = vlcZipFile
    val url = vlcZipUrl
    val expected = vlcSha256

    outputs.file(target)
    // O hash inteiro a cada build: 80 MB de SHA-256 custa dezenas de
    // milissegundos com as instruções nativas da JVM, e é a única checagem que
    // pega um download truncado no meio.
    outputs.upToDateWhen { target.isFile && sha256Of(target) == expected }

    doLast {
        if (target.isFile && sha256Of(target) == expected) return@doLast

        target.parentFile.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        logger.lifecycle("Baixando $url — 80 MB, uma vez só por versão.")
        URI(url).toURL().openStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        }

        val got = sha256Of(partial)
        if (got != expected) {
            partial.delete()
            throw GradleException(
                "SHA-256 do zip do VLC não confere.\n" +
                    "  esperado: $expected\n" +
                    "  recebido: $got\n" +
                    "Nada foi embutido. Confira o .sha256 publicado junto do zip."
            )
        }

        target.delete()
        partial.renameTo(target)
    }
}

val vlcRuntime = tasks.register<Sync>("vlcRuntime") {
    group = "distribution"
    description = "Extrai a libvlc embutida para resources/windows-x64/vlc."
    dependsOn(vlcDownload)

    val root = "vlc-$vlcVersion"
    val outDir = vlcRuntimeDir.asFile
    val version = vlcVersion

    from(zipTree(vlcZipFile)) {
        include("$root/libvlc.dll")
        include("$root/libvlccore.dll")
        include("$root/plugins/**")
        // Exigência da LGPL: a licença viaja junto da biblioteca.
        include("$root/COPYING.txt")
        // Regerado no fim desta task — o do zip indexa plugins que eu tirei.
        exclude("$root/plugins/plugins.dat")
        vlcDropDirs.forEach { exclude("$root/plugins/$it/**") }
        vlcDropPlugins.forEach { exclude("$root/plugins/**/" + it + "_plugin.dll") }
        // Só para gerar o índice; some no doLast.
        include("$root/vlc-cache-gen.exe")

        eachFile { path = path.removePrefix("$root/") }
        includeEmptyDirs = false
    }
    into(vlcRuntimeDir)

    doLast {
        /*
         * Sem `plugins.dat` a libvlc abre e inspeciona uma a uma as ~250 dlls
         * da pasta a cada início. O vlc-cache-gen escreve esse índice na build,
         * contra a árvore já podada. Se ele falhar o app funciona igual: só
         * demora mais para o primeiro vídeo aparecer.
         */
        val cacheGen = File(outDir, "vlc-cache-gen.exe")
        if (cacheGen.isFile) {
            val indexed = runCatching {
                ProcessBuilder(cacheGen.absolutePath, "plugins")
                    .directory(outDir)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
            cacheGen.delete()
            if (!indexed) {
                logger.lifecycle("vlc-cache-gen falhou — o app abre igual, só mais devagar.")
            }
        }

        // Fonte única da versão: o VlcEngine lê deste arquivo em vez de repetir
        // a constante do build dentro do código.
        File(outDir, "version.txt").writeText(version)

        val megabytes = outDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } shr 20
        logger.lifecycle("libvlc $version embutida: $megabytes MB em ${outDir.path}")
    }
}

/*
 * O plugin do Compose lê `appResourcesRootDir` para montar a pasta de recursos,
 * e o Gradle não tem como adivinhar que quem produz o conteúdo é o `vlcRuntime`.
 * Daí a amarração explícita — incluindo o `run`, senão rodar pelo Gradle acha a
 * pasta vazia e cai calado no VLC instalado na máquina, que é exatamente a
 * dependência que estas mudanças existem para eliminar.
 */
listOf(
    "prepareAppResources",
    "run", "runRelease",
    "createDistributable", "createReleaseDistributable",
    "packageMsi", "packageReleaseMsi",
    "vlcSmoke", "videoSmoke", "overlaySmoke",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach { dependsOn(vlcRuntime) }
}

compose.desktop {
    application {
        mainClass = "br.com.iordan.ranga.pc.MainKt"

        /*
         * NÃO ligue `compose.interop.blending` aqui. Já tentei; o resultado é
         * tela preta com som saindo normalmente.
         *
         * O blending faz o Compose compor o conteúdo do Swing dentro da cena
         * dele. Isso funciona com componente Swing leve, desenhado em Java. A
         * superfície do VLC não é: é um Canvas AWT pesado, onde a libvlc
         * escreve os frames direto pelo sistema operacional. O Compose não tem
         * como ler esses pixels, então compõe por cima um retângulo vazio — o
         * vídeo continua tocando, com áudio e tudo, atrás de uma tela preta.
         *
         * A consequência é que nada de Compose pode ser desenhado sobre o
         * vídeo. Por isso o "carregando" e as mensagens de erro do player
         * ficam numa faixa ABAIXO da área de vídeo, e não sobrepostos.
         */

        /*
         * ProGuard desligado, ao contrário do app Android (onde o R8 tira o APK
         * de 17 MB para 3,4 MB). Aqui a conta é outra:
         *
         *  - o que domina o tamanho é o JRE embutido, não os jars. Encolher os
         *    jars mexe em poucos MB de uma distribuição de dezenas;
         *  - o vlcj fala com a libvlc através do JNA, que resolve *tudo* por
         *    reflexão em runtime. A análise estática do ProGuard não enxerga
         *    nada disso, então ou eu escrevo uma lista de `-keep` cobrindo JNA,
         *    vlcj e as estruturas nativas, ou o app compila, empacota, abre —
         *    e morre na hora de tocar o primeiro vídeo.
         *
         * Trocar risco real de quebrar por alguns MB não vale.
         */
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            /*
             * Pasta de recursos do app. O plugin do Compose copia o conteúdo
             * de `resources/windows-x64` para dentro do pacote e passa o
             * caminho em `-Dcompose.application.resources.dir`. É por ali que
             * a libvlc embutida viaja — ver a task `vlcRuntime`.
             */
            appResourcesRootDir.set(vlcResourcesRoot)

            // Só o que o app usa — sem isto o jpackage embute o JRE inteiro.
            modules("java.instrument", "java.management", "jdk.unsupported")

            packageName = "Ranga"
            packageVersion = appVersion
            description = "Player de IPTV"
            vendor = "Iordan"

            // Msi e Exe exigem o WiX Toolset instalado, que não está nesta
            // máquina. `createReleaseDistributable` gera uma pasta com o
            // Ranga.exe e o JRE embutido, que roda sem instalar nada — para
            // uso pessoal é melhor até: dá para copiar num pendrive.
            targetFormats(TargetFormat.Msi)

            windows {
                // UUID fixo: é o que permite ao instalador ATUALIZAR a versão
                // anterior em vez de instalar uma segunda cópia lado a lado.
                upgradeUuid = "6F1D2C84-9A3B-4E77-B5C1-2A9E0D4F8B31"

                // Instalação por usuário, e não por máquina. Essencial aqui:
                // sua conta do Windows não é administrador, e o padrão do
                // jpackage (Program Files) exigiria elevação a cada instalação
                // e a cada atualização.
                perUserInstall = true

                // O próprio instalador cria o atalho na Área de Trabalho e a
                // entrada no menu Iniciar — e os REMOVE ao desinstalar, que é
                // o que o CriarAtalho.bat não sabe fazer.
                shortcut = true
                menu = true
                menuGroup = "Ranga"

                // Deixa escolher a pasta em vez de impor %LOCALAPPDATA%.
                dirChooser = true
            }
        }
    }
}

/**
 * `./gradlew dist` — gera `dist/` com um ZIP único para copiar/levar.
 *
 * A pasta que o `createReleaseDistributable` produz tem ~130 MB espalhados em
 * centenas de arquivos (o JRE embutido). Zipar não é firula: copiar um arquivo
 * para pendrive ou outra máquina é ordens de magnitude mais rápido do que
 * copiar a árvore inteira, e não corre risco de faltar um pedaço no caminho.
 */
tasks.register<Zip>("dist") {
    group = "distribution"
    description = "Compila o release e empacota Ranga-PC.zip em /dist."

    dependsOn("createReleaseDistributable")

    // Nome resolvido na configuração — dentro do lambda ele seguraria uma
    // referência ao script e quebraria o configuration cache.
    archiveFileName.set("Ranga-PC-$appVersion.zip")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))

    from(layout.buildDirectory.dir("compose/binaries/main-release/app"))
    from(layout.projectDirectory.file("INSTALAR.md"))
    from(layout.projectDirectory.file("CriarAtalho.bat"))
}

/** `./gradlew vlcSmoke` — ver o cabeçalho de src/test/.../VlcSmoke.kt. */
tasks.register<JavaExec>("vlcSmoke") {
    group = "verification"
    description = "Testa a integração com o VLC contra um stream público."
    mainClass.set("br.com.iordan.ranga.pc.VlcSmokeKt")
    defaultCharacterEncoding = "UTF-8"
    classpath = sourceSets["test"].runtimeClasspath
    // Repasse das duas propriedades que o VlcEngine consulta, para poder
    // apontar o teste onde eu quiser: `ranga.vlc.dir` compara a árvore podada
    // com uma instalação inteira do VLC, e `compose.application.resources.dir`
    // roda contra o pacote já montado em build/compose — o mesmo caminho que
    // o app instalado percorre.
    listOf("ranga.vlc.dir", "ranga.vlc.args", "ranga.stream.url", "compose.application.resources.dir").forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

/** `./gradlew overlaySmoke` — ver o cabeçalho de OverlayRenderSmoke.kt. */
tasks.register<JavaExec>("overlaySmoke") {
    group = "verification"
    description = "Mede quantos quadros por segundo o modo sobreposto aguenta."
    mainClass.set("br.com.iordan.ranga.pc.OverlayRenderSmokeKt")
    defaultCharacterEncoding = "UTF-8"
    classpath = sourceSets["test"].runtimeClasspath
    listOf("ranga.vlc.dir", "ranga.vlc.args", "ranga.stream.url", "compose.application.resources.dir").forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

/** `./gradlew videoSmoke [-Pblending]` — ver o cabeçalho de VideoRenderSmoke.kt. */
tasks.register<JavaExec>("videoSmoke") {
    group = "verification"
    description = "Abre uma janela real e confere se o vídeo aparece mesmo."
    mainClass.set("br.com.iordan.ranga.pc.VideoRenderSmokeKt")
    classpath = sourceSets["test"].runtimeClasspath
    defaultCharacterEncoding = "UTF-8"
    listOf("ranga.vlc.dir", "ranga.vlc.args", "ranga.stream.url", "compose.application.resources.dir").forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // Com -Pblending reproduz o bug de propósito, para comparar.
    if (project.hasProperty("blending")) jvmArgs("-Dcompose.interop.blending=true")
}
