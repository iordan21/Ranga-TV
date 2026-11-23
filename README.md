# Ranga TV

Player de TV ao vivo para **Android, Windows e navegador**, feito para usar com controle remoto ou controle de videogame.

> **O Ranga TV não traz lista, senha nem canal pago.** A aba **Grátis** usa só canais abertos, com o endereço que a
> própria emissora publica. A fonte e a data de verificação de cada um estão em
> [`FreeChannels.kt`](core/src/main/kotlin/br/com/iordan/ranga/data/FreeChannels.kt).
> Quem já tem conta em um serviço compatível com Xtream Codes pode entrar com ela, e isso é opcional.

O código comum (protocolo, catálogo, favoritos) fica em `core/`, e cada app tem a sua tela.

```
Ranga-TV\
  core\     protocolo Xtream + regras de dados. Kotlin puro, sem UI.
  app tv\   Android: celular, tablet e Android TV / TV Box.
  app pc\   Windows: aplicativo de desktop.
  app web\  servidor no PC + player no navegador (Fire TV com Vega OS e afins).
```

| | app tv | app pc |
|---|---|---|
| Build | Gradle + AGP 9.3.1 (Kotlin do AGP) | Gradle + Kotlin 2.2.10 |
| UI | Compose + Material3, foco por D-pad | Compose Multiplatform 1.9.3, mouse |
| Vídeo | Media3 / ExoPlayer | libvlc **embutida**, via vlcj |
| Entrega | `./gradlew dist` → APK 3,4 MB | `./gradlew dist` → ZIP 96 MB com JRE e libvlc |

Cada um tem seu `INSTALAR.md` com o passo a passo.

---

## Por que a libvlc vai dentro do pacote

O `app pc` embute a libvlc: `resources/windows-x64/vlc` entra na distribuição e
o app carrega o motor de vídeo de lá. Ninguém instala VLC para usar o Ranga.

Antes ele dependia do VLC da máquina, para poupar download. Era a troca errada.
Um player que não toca enquanto você não instala outro player é um catálogo, e
a primeira coisa que o usuário via ao abrir era uma tarefa. Pior: a versão do
VLC instalado é uma variável que eu não controlo — quando um canal não abria,
não dava para saber se o culpado era o painel, o meu código ou a build de VLC
que aquela máquina tinha.

O preço é tamanho: a pasta instalada saiu de 130 MB para 197 MB, e o instalador
de 68 MB para 97 MB. Podia ser pior — a `plugins` do VLC tem 134 MB porque
serve ao VLC completo, então a task `vlcRuntime` corta o que um player de IPTV
nunca chama: interface Qt, codificadores, saída de streaming, disco, webcam,
antena. Sobram 65 MB. O que fica, e por quê, está listado em
`app pc/build.gradle.kts`.

A libvlc é LGPL 2.1: ela vem como biblioteca separada, sem alteração, e a
licença viaja junto em `resources/.../vlc/COPYING.txt`.

O download vem do zip oficial do VideoLAN e é conferido por SHA-256 antes de
entrar no pacote. Sem a conferência, o que chegasse no lugar do zip seria
embutido junto.

## Os dois jeitos de desenhar o vídeo

O player do PC tem dois modos, e a escolha é uma troca real:

| | Sobreposto (padrão) | Acelerado |
|---|---|---|
| Quem desenha | Compose, quadro a quadro | a libvlc, direto pelo Direct3D |
| Controles sobre o vídeo | sim, inclusive em tela cheia | não |
| Custo | uma cópia de cada quadro pela memória | nenhuma |

O modo sobreposto existe por um motivo só: com a superfície acelerada, **nada**
do Compose pode ser desenhado por cima do vídeo. Ela é um canvas pesado do AWT
onde a libvlc escreve pelo sistema operacional, e o Compose não enxerga esses
pixels — o resultado de tentar é vídeo tocando atrás de um retângulo preto.
Isso empurrava a barra de controles para *baixo* do vídeo, e em tela cheia ela
sumia: sobrava o teclado.

Passando os quadros pela memória, o Compose vira o dono da tela e a barra pode
flutuar por cima. O preço é uma cópia por quadro — a decodificação continua por
hardware. Medido pelo `overlaySmoke` em 1080p: `d3d11va` 59,5 quadro/s, `any`
59,6, software 59,5, num vídeo de 60. Empate.

Cheguei a fixar decodificação por software aqui, convencido pelos primeiros
testes de que o hardware entregava zero quadro. Era a CDN pública engasgando, e
as medições eram de execução única — sem repetir, uma rede ruim e um plugin
faltando são indistinguíveis. Fica o registro: teste que depende de rede
pública não vale sem repetição.

O modo acelerado continua em Configurações para a máquina em que a cópia por
quadro pesar — com a barra de volta para baixo do vídeo.

## Por que `core/` não é um módulo Gradle

Seria o normal, e não dá para fazer aqui: os dois apps usam **compiladores
Kotlin diferentes**. O `app tv` usa o que vem embutido no AGP 9.3.1 (Kotlin
2.2); o `app pc` precisa do seu próprio, porque o Compose Multiplatform tem
exigências que o AGP não acompanha. Um módulo compartilhado dentro de um único
build obrigaria os dois a casarem a versão — a mesma briga que já custou travar
o Coil na 3.3.0.

A saída: `core/` é uma **pasta de fontes**, não um módulo. Cada build a inclui
no seu source set (`kotlin.srcDir("../core/src/main/kotlin")`) e a compila com
o compilador que quiser. Um código só, zero acoplamento entre os builds.

O preço é que `core/` não pode tocar em nada de Android. É por isso que o
Base64 do EPG está escrito à mão em `XtreamApi.kt`: `android.util.Base64` não
existe no desktop, e `java.util.Base64` só existe no Android a partir da API 26
— e o `app tv` roda desde a 24.

## O que mora em `core/`

| Arquivo | Responsabilidade |
|---|---|
| `Models.kt` | Canal, filme, série, episódio, EPG, `Playable` |
| `XtreamUrls.kt` | Montagem de URL. Puro e testado em JVM (11 testes) |
| `XtreamApi.kt` | HTTP + parsing tolerante ao caos de tipos do painel |
| `Catalog.kt` | Repositório com cache em memória, sempre por categoria |
| `Storage.kt` | Favoritos, "continuar assistindo", presets — formato e regras |
| `Playables.kt` | Item de catálogo → item tocável |

Os testes de `core/` rodam nos **dois** builds, com dois compiladores
diferentes. `./gradlew test` em qualquer uma das pastas.

O que **não** está no core: persistência (DataStore no Android, arquivo JSON em
`%APPDATA%` no Windows), UI e player. São genuinamente diferentes por
plataforma — tentar unificar custaria mais do que duplica.

## Licença

GPL v3 (veja [LICENSE](LICENSE)). O app de PC usa o [vlcj](https://github.com/caprica/vlcj), que é GPL v3.
A libvlc é LGPL 2.1 e vem como biblioteca separada.
