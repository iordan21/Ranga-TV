# Ranga

Player de IPTV para Android, para uso pessoal, que fala o protocolo
**Xtream Codes** — o mesmo que o painel do seu provedor expõe em
`player_api.php` e nos links `get.php`.

Roda em celular/tablet (toque) e em Android TV / TV Box (controle remoto), com
o mesmo APK.

---

## Como rodar

Instalar direto num aparelho conectado:

```bash
./gradlew installRelease
```

Gerar a pasta de entrega (`dist/` com o APK + `INSTALAR.md`), para mandar para
o TV Box por rede ou pendrive:

```bash
./gradlew dist
```

A task recompila o release antes de copiar — não tem como sair dali um APK
desatualizado.

`installDebug` também funciona, mas o APK de debug tem 17 MB (leva as
ferramentas de inspeção do Compose). O release tem **3,4 MB** e é o que vale
instalar no TV Box. Ele é assinado com a chave de debug justamente para você
poder instalar direto, sem montar keystore — para Play Store, troque o
`signingConfig`.

O app abre na aba **Grátis** — canais abertos com stream oficial, sem conta.
Para as abas do Xtream, entre com os dados do painel (pelo convite na aba ou
em Configurações):

| Campo no app | Campo no painel |
|---|---|
| Servidor | **DNS/XCIPTV/API** (ex.: `http://seu-painel.exemplo:80`) |
| Usuário | **Nome** |
| Senha | **Senha** |

O app valida no servidor antes de salvar — se o login estiver errado, ele avisa
ali mesmo em vez de deixar você descobrir na próxima tela.

**As credenciais não estão no código.** Ficam no DataStore, no diretório
privado do app. Se um dia você publicar este repositório, não vaza assinatura.

Testes (JVM pura, sem emulador):

```bash
./gradlew test
```

---

## Como está organizado

```
data/       Protocolo Xtream + persistência. Nada de Android UI aqui.
  Models.kt        Modelos de domínio (Channel, Movie, SeriesItem, Playable…)
  XtreamUrls.kt    Montagem de URL — puro, testado em JVM
  XtreamApi.kt     Chamadas HTTP + parsing tolerante
  Catalog.kt       Repositório com cache em memória
  SessionStore.kt  Credenciais, favoritos, "continuar assistindo"
  Playables.kt     Item de catálogo -> item tocável

ui/         Compose. Cada tela = 1 arquivo de tela + 1 ViewModel.
  common/     FocusableCard (o tijolo de navegação por controle), imagens, estados
  login/      Tela de entrada
  browse/     Home (abas + busca + favoritos) e categoria
  detail/     Filme e série
  settings/   Conta, formato de stream, User-Agent

player/     Activity separada com ExoPlayer/Media3
```

`AppContainer.kt` é o grafo de dependências inteiro, escrito à mão.

---

## Decisões que valem explicação

**Por que parsing manual de JSON, e não `@Serializable`.**
Painéis Xtream são inconsistentes com tipos: o mesmo campo volta como `"5"`,
`5`, `5.0` ou `null` conforme a rota; `episodes` volta como objeto quando tem
dado e como *array vazio* quando não tem. Data class anotada transforma isso em
crash. `XtreamApi` lê `JsonElement` cru com leitores tolerantes — no pior caso
um campo vem nulo. De quebra, dispensa o plugin de compilação do
kotlinx-serialization.

**Por que nunca carrego uma seção inteira.**
São 70.000+ títulos de VOD. `get_vod_streams` sem `category_id` devolveria
dezenas de MB num JSON só. O catálogo é sempre por categoria; a busca global
existe, mas é explícita ("Carregar todas as categorias") e mostra progresso.

**Por que o HLS passa por um parser próprio.**
O master da TV Cultura anuncia `FRAME-RATE=0.000`, e o Media3 recusa a playlist
inteira por isso (`Format.Builder.setFrameRate` exige valor positivo). O VLC toca
o mesmo link. `player/TolerantHls.kt` apaga só o valor zero antes de entregar o
texto ao parser oficial; a `DefaultMediaSourceFactory` não deixa trocar o parser,
então uma fábrica própria despacha `.m3u8` para ela e o resto para a padrão.

**Por que o player é Activity e não rota do Navigation.**
Fullscreen, orientação, PiP e "manter a tela ligada" são propriedades de
janela. Como Activity, o Android gerencia sozinho ao entrar e sair.

**Por que a fila do player não vai no Intent.**
Uma categoria pode ter centenas de canais, e Intent trafega por Binder, com
limite de ~1 MB. Isso daria `TransactionTooLargeException` só nas categorias
grandes — ou seja, em produção e não no teste. O Intent leva só o índice; a
lista fica em `PlaybackQueue`.

**Por que `dispatchKeyEvent` na Activity em vez de `Modifier.onKeyEvent`.**
O vídeo é um `PlayerView` (View clássica) dentro de `AndroidView`. Quando ele
tem o foco, as teclas do controle vão para a hierarquia de Views e nunca sobem
para os modifiers do Compose.

**Por que o login virou rota (e já foi estado).**
Enquanto tudo dependia de conta, o login embrulhava a árvore inteira: sem
credencial, só ele existia. A aba Grátis quebrou essa premissa — a Home precisa
existir antes de qualquer login. Então o login é uma rota comum, e quem reage a
entrar e sair é a Home, observando a credencial salva no DataStore.

---

## Otimização

| | |
|---|---|
| APK release | **3,4 MB** (17 MB sem R8) |
| Composição do release | 3,3 MB de dex, 355 KB de recursos, 72 KB de nativo |

O que rendeu:

- **R8 + `shrinkResources`** — o grosso. Media3 e material-icons-core entram
  inteiros e saem só com o que o app referencia. As regras em
  `proguard-rules.pro` são poucas de propósito: só o que o ExoPlayer carrega
  por reflexão (extractors/renderers). Cada `-keep` a mais desfaz otimização.
- **`AsyncImage` no lugar de `SubcomposeAsyncImage`** — o Subcompose faz um
  `SubcomposeLayout` por célula; numa grade de centenas de pôsteres isso pesa
  na rolagem. O preço é não ter placeholder animado, que aqui não faz falta.
- **`Regex` de normalização içado para fora da função** — ele rodava uma vez
  por item a cada tecla digitada na busca, recompilando o padrão toda vez.
  Era o gargalo real, não o loop.
- **Busca normaliza cada título uma vez só** — a versão ingênua
  (`filter { contains }` + `sortedBy { indexOf }`) normalizava duas vezes por
  item, e o `sortedBy` refazia isso a cada comparação.

Deliberadamente **não** otimizado: o DataStore continua (trocar por
SharedPreferences economizaria ~400 KB e custaria mais código, porque o Flow é
o que faz favoritos e histórico se propagarem sozinhos), e não há split de ABI
(o nativo inteiro são 72 KB).

---

## O que o app faz

- Aba Grátis: canais abertos com stream publicado pela própria emissora, sem
  conta. A lista mora em `core/…/FreeChannels.kt`, com fonte e data de
  verificação por canal — canal que só transmite por YouTube/Dailymotion
  fica de fora, porque o ExoPlayer não toca esses players
- Ao vivo, Filmes e Séries por categoria; favoritos; "continuar assistindo"
- EPG "agora / a seguir" no canal ao vivo
- Zapping com ↑ ↓ (ou CH+/CH−) dentro da categoria aberta
- Retomada de posição em filme e episódio; emenda automática no próximo episódio
- Troca de proporção da imagem (ajustar / preencher / zoom)
- Fallback automático `.m3u8` ⇄ `.ts` quando um canal falha
- Erros traduzidos, inclusive o caso "seu aparelho não decodifica H.265/4K"

## O que ainda não faz

- EPG completo (grade de programação); só o "agora / a seguir" por canal
- Gravação e catch-up (`tv_archive`)
- Chromecast
- Busca global instantânea sem o passo de "carregar todas as categorias"
- Múltiplos perfis / múltiplas listas

---

## Notas de versão do build

Duas travas foram necessárias e estão comentadas em `gradle/libs.versions.toml`:

- **Coil 3.3.0** (e não 3.5.0): a partir da 3.4 ela exige `kotlin-stdlib` 2.3/2.4,
  e o compilador Kotlin embutido no AGP 9.3.1 é o 2.2, que lê metadata só até 2.3.0.
- **core-ktx 1.17.0 / lifecycle 2.10.0**: as versões seguintes declaram exigir
  `compileSdk 37`, e aqui compilamos contra o 36.

Suba as duas travas quando subir o AGP e instalar a plataforma `android-37`.
