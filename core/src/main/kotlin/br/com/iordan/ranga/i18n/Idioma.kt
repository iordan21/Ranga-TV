package br.com.iordan.ranga.i18n

import java.util.Locale

/**
 * Os idiomas do Ranga TV no PC: português, inglês, russo e espanhol.
 *
 * O texto em português é a chave: quem desenha chama [translate] com ele e recebe
 * o do idioma escolhido (sem tradução, o próprio português). Mensagens que levam
 * um número ou nome no meio usam `%s` / `%d` e passam por [format]. As mensagens de
 * erro que já chegam prontas (as do [br.com.iordan.ranga.data.XtreamApi]) também
 * passam por aqui na hora de aparecer, inclusive as que levam o código HTTP.
 *
 * Fica no core por não depender de nada da interface; o app de TV (Android) ainda
 * não usa.
 */
object Idioma {

    enum class Lang(val code: String, val nativeName: String) {
        PT("pt", "Português"),
        EN("en", "English"),
        RU("ru", "Русский"),
        ES("es", "Español");

        companion object {
            fun fromCode(code: String?): Lang? =
                entries.firstOrNull { code != null && code.lowercase().startsWith(it.code) }

            /** O idioma do Windows; fora desses quatro, inglês. */
            fun fromSystem(): Lang = fromCode(Locale.getDefault().language) ?: EN
        }
    }

    fun translate(lang: Lang, pt: String): String {
        if (lang == Lang.PT) return pt
        val column = lang.ordinal - 1
        table[pt]?.let { return it[column] }
        for ((pattern, out) in patterns) {
            val match = pattern.matchEntire(pt) ?: continue
            var text = out[column]
            match.groupValues.drop(1).forEachIndexed { i, value -> text = text.replace("\$${i + 1}", value) }
            return text
        }
        return pt
    }

    fun format(lang: Lang, pt: String, vararg args: Any?): String = String.format(translate(lang, pt), *args)

    // en, ru, es
    private val table: Map<String, Array<String>> = mapOf(
        // Abas e navegação
        "Ao vivo" to arrayOf("Live", "Эфир", "En vivo"),
        "Filmes" to arrayOf("Movies", "Фильмы", "Películas"),
        "Séries" to arrayOf("Series", "Сериалы", "Series"),
        "Grátis" to arrayOf("Free", "Бесплатно", "Gratis"),
        "Favoritos" to arrayOf("Favorites", "Избранное", "Favoritos"),
        "Configurações" to arrayOf("Settings", "Настройки", "Ajustes"),
        "Voltar" to arrayOf("Back", "Назад", "Volver"),
        "Favoritar" to arrayOf("Add to favorites", "В избранное", "Añadir a favoritos"),
        "Tentar de novo" to arrayOf("Try again", "Повторить", "Reintentar"),
        "Buscar em %s" to arrayOf("Search %s", "Поиск: %s", "Buscar en %s"),
        "Continuar assistindo" to arrayOf("Continue watching", "Продолжить просмотр", "Seguir viendo"),
        "Nada encontrado nas categorias já abertas." to arrayOf(
            "Nothing found in the categories opened so far.",
            "В уже открытых категориях ничего не найдено.",
            "No se encontró nada en las categorías ya abiertas.",
        ),
        "Carregar todas as categorias de %s" to arrayOf(
            "Load every %s category", "Загрузить все категории: %s", "Cargar todas las categorías de %s",
        ),
        "Escolha uma categoria à esquerda." to arrayOf(
            "Pick a category on the left.", "Выберите категорию слева.", "Elige una categoría a la izquierda.",
        ),
        "Canal %s" to arrayOf("Channel %s", "Канал %s", "Canal %s"),
        "Nenhum canal com esse nome." to arrayOf("No channel with that name.", "Нет канала с таким названием.", "Ningún canal con ese nombre."),
        "Canais abertos, com o stream que a própria emissora publica. Sem conta." to arrayOf(
            "Free-to-air channels, with the stream the broadcaster itself publishes. No account needed.",
            "Открытые каналы — поток публикует сам телеканал. Аккаунт не нужен.",
            "Canales abiertos, con la señal que publica la propia emisora. Sin cuenta.",
        ),
        "Stream oficial · %s" to arrayOf("Official stream · %s", "Официальный поток · %s", "Señal oficial · %s"),
        "Canais ao vivo, filmes e séries vêm da sua conta IPTV.\nOs canais da aba Grátis não precisam dela." to arrayOf(
            "Live channels, movies and series come from your IPTV account.\nThe channels in the Free tab don't need it.",
            "Каналы, фильмы и сериалы берутся из вашего IPTV-аккаунта.\nКаналам на вкладке «Бесплатно» он не нужен.",
            "Los canales en vivo, las películas y las series vienen de tu cuenta IPTV.\nLos canales de la pestaña Gratis no la necesitan.",
        ),
        "Entrar com a conta" to arrayOf("Sign in with your account", "Войти в аккаунт", "Iniciar sesión con la cuenta"),

        // Detalhe de filme e série
        "Filme" to arrayOf("Movie", "Фильм", "Película"),
        "Série" to arrayOf("Series", "Сериал", "Serie"),
        "Falha ao carregar." to arrayOf("Failed to load.", "Не удалось загрузить.", "No se pudo cargar."),
        "Assistir" to arrayOf("Watch", "Смотреть", "Ver"),
        "Continuar de %s" to arrayOf("Resume from %s", "Продолжить с %s", "Seguir desde %s"),
        "Do início" to arrayOf("From the start", "С начала", "Desde el principio"),
        "Elenco: %s" to arrayOf("Cast: %s", "В ролях: %s", "Reparto: %s"),
        "%d temporada(s)" to arrayOf("%d season(s)", "Сезонов: %d", "%d temporada(s)"),
        "Temporada %s" to arrayOf("Season %s", "Сезон %s", "Temporada %s"),
        "Esta série está sem episódios no servidor." to arrayOf(
            "This series has no episodes on the server.", "У этого сериала нет серий на сервере.", "Esta serie no tiene episodios en el servidor.",
        ),
        "parou em %s" to arrayOf("stopped at %s", "остановлено на %s", "se detuvo en %s"),

        // Entrar
        "Preencha servidor, usuário e senha." to arrayOf(
            "Fill in server, username and password.", "Заполните сервер, логин и пароль.", "Completa servidor, usuario y contraseña.",
        ),
        "Endereço do servidor inválido." to arrayOf("Invalid server address.", "Неверный адрес сервера.", "Dirección del servidor no válida."),
        "Falha inesperada." to arrayOf("Unexpected failure.", "Непредвиденная ошибка.", "Error inesperado."),
        "Dados do painel da sua conta IPTV. A aba Grátis funciona sem isto." to arrayOf(
            "Details from your IPTV account panel. The Free tab works without this.",
            "Данные из панели вашего IPTV-аккаунта. Вкладка «Бесплатно» работает и без них.",
            "Datos del panel de tu cuenta IPTV. La pestaña Gratis funciona sin esto.",
        ),
        "Servidor" to arrayOf("Server", "Сервер", "Servidor"),
        "Usuário" to arrayOf("Username", "Логин", "Usuario"),
        "Senha" to arrayOf("Password", "Пароль", "Contraseña"),
        "Ocultar" to arrayOf("Hide", "Скрыть", "Ocultar"),
        "Ver" to arrayOf("Show", "Показать", "Ver"),
        "Entrar" to arrayOf("Sign in", "Войти", "Entrar"),

        // Player
        "Pausar" to arrayOf("Pause", "Пауза", "Pausar"),
        "Tocar" to arrayOf("Play", "Играть", "Reproducir"),
        "Canal anterior" to arrayOf("Previous channel", "Предыдущий канал", "Canal anterior"),
        "Próximo canal" to arrayOf("Next channel", "Следующий канал", "Canal siguiente"),
        "Áudio" to arrayOf("Audio", "Аудио", "Audio"),
        "Legenda" to arrayOf("Subtitles", "Субтитры", "Subtítulos"),
        "Volume" to arrayOf("Volume", "Громкость", "Volumen"),
        "Tela cheia" to arrayOf("Full screen", "Полный экран", "Pantalla completa"),
        "Desligada" to arrayOf("Off", "Выкл.", "Desactivada"),
        "Caiu. Reconectando (%d de 3)…" to arrayOf(
            "Connection dropped. Reconnecting (%d of 3)…", "Связь прервалась. Переподключение (%d из 3)…", "Se cortó. Reconectando (%d de 3)…",
        ),
        "Seu plano permite %d conexão(ões) ao mesmo tempo, e %d já está(ão) em uso. Feche o Ranga nos outros aparelhos e tente de novo." to arrayOf(
            "Your plan allows %d connection(s) at a time, and %d are already in use. Close Ranga on your other devices and try again.",
            "Ваш тариф допускает подключений одновременно: %d, уже используется: %d. Закройте Ranga на других устройствах и повторите.",
            "Tu plan permite %d conexión(es) a la vez, y %d ya está(n) en uso. Cierra Ranga en los otros dispositivos y vuelve a intentarlo.",
        ),
        "Não deu para abrir. Tentei três vezes, nos dois formatos. O canal pode estar fora do ar; se todos falharem, é o servidor." to arrayOf(
            "Couldn't open it. I tried three times, in both formats. The channel may be off the air; if every channel fails, it's the server.",
            "Не удалось открыть: три попытки в обоих форматах. Канал может не работать; если не открывается ни один, дело в сервере.",
            "No se pudo abrir. Lo intenté tres veces, en los dos formatos. El canal puede estar caído; si fallan todos, es el servidor.",
        ),
        "O canal parou de responder e não voltou em três tentativas. Costuma ser queda do lado do provedor." to arrayOf(
            "The channel stopped responding and didn't come back after three tries. It's usually an outage on the provider's side.",
            "Канал перестал отвечать и не восстановился за три попытки. Обычно это сбой у провайдера.",
            "El canal dejó de responder y no volvió en tres intentos. Suele ser una caída del lado del proveedor.",
        ),
        "O motor de vídeo não carregou" to arrayOf("The video engine didn't load", "Видеодвижок не загрузился", "El motor de video no se cargó"),
        "O Ranga já vem com o motor de vídeo dentro dele, então isto quer dizer que a instalação está incompleta — o antivírus é o suspeito mais comum. Reinstalar o Ranga resolve. Instalar o VLC 64 bits também serve de quebra-galho." to arrayOf(
            "Ranga ships with its own video engine, so this means the installation is incomplete — an antivirus is the most common suspect. Reinstalling Ranga fixes it. Installing 64-bit VLC also works as a stopgap.",
            "Видеодвижок входит в состав Ranga, значит установка неполная — чаще всего виноват антивирус. Переустановка Ranga это исправит. Временно поможет и установка VLC 64-bit.",
            "Ranga ya trae el motor de video incluido, así que esto significa que la instalación está incompleta: el antivirus es el sospechoso más común. Reinstalar Ranga lo resuelve. Instalar VLC de 64 bits también sirve como apaño.",
        ),
        "Baixar o VLC" to arrayOf("Download VLC", "Скачать VLC", "Descargar VLC"),

        // Configurações
        "Idioma" to arrayOf("Language", "Язык", "Idioma"),
        "Motor de vídeo" to arrayOf("Video engine", "Видеодвижок", "Motor de video"),
        "Embutido no app%s. Nada para instalar." to arrayOf(
            "Built into the app%s. Nothing to install.", "Встроен в приложение%s. Ничего устанавливать не нужно.", "Incluido en la app%s. Nada que instalar.",
        ),
        "Usando o VLC instalado nesta máquina. O motor que vem no pacote não foi encontrado — reinstale o Ranga se isso repetir." to arrayOf(
            "Using the VLC installed on this computer. The engine that comes in the package wasn't found — reinstall Ranga if this keeps happening.",
            "Используется VLC, установленный на этом компьютере. Встроенный движок не найден — переустановите Ranga, если это повторится.",
            "Usando el VLC instalado en este equipo. No se encontró el motor que viene en el paquete: reinstala Ranga si se repite.",
        ),
        "Nenhum motor de vídeo carregou. O catálogo funciona, mas nada toca. Reinstale o Ranga; se continuar, instalar o VLC 64 bits resolve." to arrayOf(
            "No video engine loaded. The catalog works, but nothing plays. Reinstall Ranga; if it continues, installing 64-bit VLC fixes it.",
            "Видеодвижок не загрузился. Каталог работает, но ничего не воспроизводится. Переустановите Ranga; если не поможет, установите VLC 64-bit.",
            "No se cargó ningún motor de video. El catálogo funciona, pero nada se reproduce. Reinstala Ranga; si sigue, instalar VLC de 64 bits lo resuelve.",
        ),
        "Como o vídeo aparece" to arrayOf("How the video is shown", "Как показывается видео", "Cómo se muestra el video"),
        "Trocar vale da próxima vez que você abrir um canal." to arrayOf(
            "Changes apply the next time you open a channel.", "Изменение вступит в силу при следующем открытии канала.", "El cambio se aplica la próxima vez que abras un canal.",
        ),
        "Com controles sobre o vídeo" to arrayOf("With controls over the video", "С элементами управления поверх видео", "Con controles sobre el video"),
        "Os quadros passam pelo aplicativo antes de chegar à tela, o que permite barra e mensagens sobrepostas, também em tela cheia. A decodificação continua por hardware; o custo é a cópia de cada quadro, que pesa um pouco mais no processador." to arrayOf(
            "Frames go through the app before reaching the screen, which allows an overlaid bar and messages, also in full screen. Decoding is still done by hardware; the cost is copying each frame, which weighs a little more on the processor.",
            "Кадры проходят через приложение, прежде чем попасть на экран, — так панель и сообщения видны поверх видео, в том числе в полноэкранном режиме. Декодирование остаётся аппаратным; цена — копирование каждого кадра, что немного сильнее нагружает процессор.",
            "Los fotogramas pasan por la app antes de llegar a la pantalla, lo que permite barra y mensajes superpuestos, también en pantalla completa. La decodificación sigue siendo por hardware; el costo es copiar cada fotograma, lo que exige un poco más al procesador.",
        ),
        "Acelerado" to arrayOf("Accelerated", "Ускоренный", "Acelerado"),
        "O vídeo vai direto para a placa, do jeito mais leve. Em compensação a barra de controles fica abaixo do vídeo e some em tela cheia. Use se o modo com sobreposição engasgar." to arrayOf(
            "The video goes straight to the graphics card, the lightest way. On the other hand, the control bar sits below the video and disappears in full screen. Use it if the overlay mode stutters.",
            "Видео идёт прямо на видеокарту — самый лёгкий способ. Зато панель управления находится под видео и пропадает в полноэкранном режиме. Используйте, если режим с наложением подтормаживает.",
            "El video va directo a la tarjeta gráfica, de la forma más ligera. A cambio, la barra de controles queda debajo del video y desaparece en pantalla completa. Úsalo si el modo superpuesto se traba.",
        ),
        "Conta" to arrayOf("Account", "Аккаунт", "Cuenta"),
        "Só para quem tem conta IPTV. A aba Grátis não precisa." to arrayOf(
            "Only for those with an IPTV account. The Free tab doesn't need one.",
            "Только для тех, у кого есть IPTV-аккаунт. Вкладке «Бесплатно» он не нужен.",
            "Solo para quien tiene cuenta IPTV. La pestaña Gratis no la necesita.",
        ),
        "Situação" to arrayOf("Status", "Статус", "Estado"),
        "Vence em" to arrayOf("Expires on", "Действует до", "Vence el"),
        "Conexões" to arrayOf("Connections", "Подключения", "Conexiones"),
        "%s de %s em uso" to arrayOf("%s of %s in use", "%s из %s используется", "%s de %s en uso"),
        "Formato do canal ao vivo" to arrayOf("Live channel format", "Формат прямого эфира", "Formato del canal en vivo"),
        "Qual o app tenta primeiro." to arrayOf("Which one the app tries first.", "Какой приложение пробует первым.", "Cuál intenta primero la app."),
        "Se o catálogo abre mas nenhum vídeo toca, troque aqui." to arrayOf(
            "If the catalog opens but no video plays, change it here.", "Если каталог открывается, но видео не играет, смените здесь.",
            "Si el catálogo abre pero ningún video se reproduce, cámbialo aquí.",
        ),
        "Dados" to arrayOf("Data", "Данные", "Datos"),
        "Sair apaga só a credencial. Favoritos e histórico ficam." to arrayOf(
            "Signing out only deletes the credentials. Favorites and history stay.",
            "Выход удаляет только учётные данные. Избранное и история остаются.",
            "Cerrar sesión solo borra la credencial. Los favoritos y el historial se quedan.",
        ),
        "Arquivo: %s" to arrayOf("File: %s", "Файл: %s", "Archivo: %s"),
        "Log do motor de vídeo: %s" to arrayOf("Video engine log: %s", "Журнал видеодвижка: %s", "Registro del motor de video: %s"),
        "Limpar \"continuar assistindo\"" to arrayOf("Clear \"continue watching\"", "Очистить «Продолжить просмотр»", "Borrar \"seguir viendo\""),
        "Sair da conta" to arrayOf("Sign out", "Выйти из аккаунта", "Cerrar sesión"),
        "Não respondeu." to arrayOf("No response.", "Нет ответа.", "No respondió."),
        "Servidor (DNS)" to arrayOf("Server (DNS)", "Сервер (DNS)", "Servidor (DNS)"),
        "Endereços da mesma conta. Se um parar, escolha outro: usuário e senha continuam os mesmos." to arrayOf(
            "Addresses for the same account. If one stops working, pick another: username and password stay the same.",
            "Адреса одного и того же аккаунта. Если один перестал работать, выберите другой: логин и пароль те же.",
            "Direcciones de la misma cuenta. Si una deja de funcionar, elige otra: usuario y contraseña siguen siendo los mismos.",
        ),
        "Continua no atual. %s: %s" to arrayOf("Staying on the current one. %s: %s", "Остаётся текущий. %s: %s", "Sigue en el actual. %s: %s"),
        "%s  (em uso)" to arrayOf("%s  (in use)", "%s  (используется)", "%s  (en uso)"),
        "testando…" to arrayOf("testing…", "проверка…", "probando…"),
        "respondeu em %d ms" to arrayOf("responded in %d ms", "ответ за %d мс", "respondió en %d ms"),
        "falhou: %s" to arrayOf("failed: %s", "ошибка: %s", "falló: %s"),
        "Remover" to arrayOf("Remove", "Удалить", "Quitar"),
        "Testar todos" to arrayOf("Test all", "Проверить все", "Probar todos"),
        "Adicionar DNS" to arrayOf("Add DNS", "Добавить DNS", "Añadir DNS"),
        "Cole um ou mais endereços, um por linha" to arrayOf(
            "Paste one or more addresses, one per line", "Вставьте один или несколько адресов, по одному в строке", "Pega una o más direcciones, una por línea",
        ),
        "Nenhum endereço válido no que foi digitado." to arrayOf(
            "No valid address in what was typed.", "В введённом тексте нет ни одного правильного адреса.", "No hay ninguna dirección válida en lo escrito.",
        ),
        "Adicionar" to arrayOf("Add", "Добавить", "Añadir"),

        // Mensagens do core (XtreamApi, Storage)
        "O servidor respondeu num formato inesperado." to arrayOf(
            "The server answered in an unexpected format.", "Сервер ответил в неожиданном формате.", "El servidor respondió con un formato inesperado.",
        ),
        "Servidor não reconheceu esta conta." to arrayOf("The server didn't recognize this account.", "Сервер не узнал этот аккаунт.", "El servidor no reconoció esta cuenta."),
        "Usuário ou senha incorretos." to arrayOf("Wrong username or password.", "Неверный логин или пароль.", "Usuario o contraseña incorrectos."),
        "Desconhecido" to arrayOf("Unknown", "Неизвестно", "Desconocido"),
        "Sem nome" to arrayOf("No name", "Без названия", "Sin nombre"),
        "Sem título" to arrayOf("Untitled", "Без названия", "Sin título"),
        "Sem informação" to arrayOf("No information", "Нет информации", "Sin información"),
        "Não consegui ler os episódios desta série." to arrayOf(
            "Couldn't read this series' episodes.", "Не удалось прочитать серии этого сериала.", "No pude leer los episodios de esta serie.",
        ),
        "O servidor respondeu algo que não é JSON. Ele pode estar fora do ar." to arrayOf(
            "The server answered with something that isn't JSON. It may be down.", "Сервер ответил не в формате JSON. Возможно, он не работает.",
            "El servidor respondió algo que no es JSON. Puede estar caído.",
        ),
        "Rota não encontrada no servidor (HTTP 404). Confira o endereço." to arrayOf(
            "Path not found on the server (HTTP 404). Check the address.", "Путь не найден на сервере (HTTP 404). Проверьте адрес.",
            "Ruta no encontrada en el servidor (HTTP 404). Revisa la dirección.",
        ),
        "Não achei esse servidor. Confira o endereço e sua internet." to arrayOf(
            "Couldn't find that server. Check the address and your internet.", "Сервер не найден. Проверьте адрес и подключение к интернету.",
            "No encontré ese servidor. Revisa la dirección y tu internet.",
        ),
        "O servidor demorou demais para responder." to arrayOf("The server took too long to respond.", "Сервер слишком долго не отвечал.", "El servidor tardó demasiado en responder."),
        "O servidor não atendeu. Ele pode estar fora do ar." to arrayOf(
            "The server didn't answer. It may be down.", "Сервер не ответил. Возможно, он не работает.", "El servidor no atendió. Puede estar caído.",
        ),
        "Falha na conexão segura (https). Tente o endereço com http://." to arrayOf(
            "Secure connection (https) failed. Try the address with http://.", "Ошибка защищённого соединения (https). Попробуйте адрес с http://.",
            "Falló la conexión segura (https). Prueba la dirección con http://.",
        ),
        "Falha de rede. Confira sua internet e o endereço do servidor." to arrayOf(
            "Network failure. Check your internet and the server address.", "Ошибка сети. Проверьте интернет и адрес сервера.",
            "Error de red. Revisa tu internet y la dirección del servidor.",
        ),
    )

    // Mensagens que já chegam com o número ou o nome dentro: $1, $2... são os grupos.
    private val patterns: List<Pair<Regex, Array<String>>> = listOf(
        Regex("Servidor recusou o login \\(HTTP (\\d+)\\)\\.") to arrayOf(
            "The server refused the login (HTTP \$1).", "Сервер отклонил вход (HTTP \$1).", "El servidor rechazó el inicio de sesión (HTTP \$1).",
        ),
        Regex("Servidor do provedor está fora do ar \\(HTTP (\\d+)\\)\\.") to arrayOf(
            "The provider's server is down (HTTP \$1).", "Сервер провайдера не работает (HTTP \$1).", "El servidor del proveedor está caído (HTTP \$1).",
        ),
        Regex("Servidor retornou erro HTTP (\\d+)\\.") to arrayOf(
            "The server returned HTTP error \$1.", "Сервер вернул ошибку HTTP \$1.", "El servidor devolvió el error HTTP \$1.",
        ),
        Regex("Assinatura com status \"(.*)\"\\. Fale com o provedor\\.") to arrayOf(
            "Subscription status is \"\$1\". Contact your provider.", "Статус подписки: «\$1». Обратитесь к провайдеру.",
            "Suscripción con estado \"\$1\". Habla con el proveedor.",
        ),
        Regex("Série (\\d+)") to arrayOf("Series \$1", "Сериал \$1", "Serie \$1"),
        Regex("Episódio (\\d+)") to arrayOf("Episode \$1", "Серия \$1", "Episodio \$1"),
    )
}
