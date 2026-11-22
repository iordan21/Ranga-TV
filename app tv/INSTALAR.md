# Ranga — instalação

APK único, serve para celular e para Android TV / TV Box.
Assinado com chave de debug: instala normalmente, mas não sobe na Play Store.

## Celular

Copie o `.apk` para o aparelho e abra. O Android vai pedir para liberar
"instalar apps desconhecidos" para o app que está abrindo o arquivo
(Arquivos, Drive, WhatsApp) — libere só para ele.

Ou, com o celular no cabo e depuração USB ligada:

```
adb install -r Ranga-1.0.apk
```

## Android TV / TV Box

TV não tem gerenciador de arquivos decente, então o caminho curto é `adb` pela
rede:

1. Na TV: Configurações → Sobre → clique 7 vezes em "Versão" para virar
   desenvolvedor.
2. Configurações → Opções do desenvolvedor → ligue **Depuração USB** e
   **Depuração por rede** (ou "ADB debugging" / "Network debugging").
3. Anote o IP da TV em Configurações → Rede.
4. No PC, com a TV na mesma rede:

```
adb connect 192.168.0.X:5555
```

```
adb install -r Ranga-1.0.apk
```

Autorize a conexão no aviso que aparece na tela da TV.

Se preferir sem PC: instale o **Downloader** (AFTV) ou o **Send Files to TV** na
loja da TV e transfira o APK por ali.

## Primeiro uso

O app abre direto na aba **Grátis**: canais abertos (TV Brasil, TV Câmara,
TV Cultura, Rede Minas) com o stream que a própria emissora publica. Não
precisa de conta nenhuma.

As abas Ao vivo, Filmes e Séries vêm da sua conta IPTV. Para usá-las, toque em
"Entrar com a conta" (ou vá em Configurações) e preencha com os dados do
painel do seu provedor:

| No app | No painel |
|---|---|
| Servidor | **DNS/XCIPTV/API** — ex.: `http://seu-painel.exemplo:80` |
| Usuário | **Nome** |
| Senha | **Senha** |

O app valida no servidor antes de salvar. As credenciais ficam só neste
aparelho.

## Controles na TV

| Tecla | O que faz |
|---|---|
| ↑ ↓ (ou CH+ / CH−) | troca de canal, dentro da categoria aberta |
| OK | mostra/esconde a barra de informação |
| Voltar | fecha a barra; de novo, sai do player |

Em filme e série o controle é o padrão do player: OK abre os comandos, ← →
avançam e voltam.

## Se algo não funcionar

**O catálogo abre mas nenhum vídeo toca** — quase sempre é o servidor filtrando
o User-Agent. Configurações → **User-Agent** → escolha outro da lista.

**Um canal específico trava ou não abre** — Configurações → **Formato do canal
ao vivo** → troque entre HLS e MPEG-TS. O app já tenta o outro sozinho quando
dá erro, mas isso define qual ele tenta primeiro.

**"Este aparelho não consegue decodificar esse vídeo"** — TV Box antigo sem
suporte a H.265/4K. Procure a versão SD/HD do mesmo canal.

**"O servidor recusou este stream"** — provavelmente o limite de conexões
simultâneas do seu plano. Feche o app nos outros aparelhos.
