# Ranga PC — instalação

Aplicativo Windows 64 bits. **Não precisa instalar nada antes**: o pacote já
traz o próprio Java (pasta `runtime`) e o próprio motor de vídeo (pasta
`app\resources\vlc`). Não é preciso ter o VLC na máquina.

## 1. Instale o Ranga

Duas opções — escolha uma.

**Instalador (recomendado):** dê dois cliques em `Ranga-1.1.0.msi`.

Ele instala por usuário, então **não pede senha de administrador**. Cria o
atalho na Área de Trabalho e a entrada no menu Iniciar sozinho, e deixa tudo
listado em Configurações → Aplicativos, com desinstalação limpa. Instalar uma
versão nova por cima substitui a anterior em vez de duplicar.

**Pasta portátil:** descompacte o ZIP e abra `Ranga\Ranga.exe`.

Não instala nada, roda de onde estiver, inclusive de um pendrive. Para ter
atalho na Área de Trabalho, rode o `CriarAtalho.bat` que vem junto.

## 2. Primeiro uso

| No app | No painel do provedor |
|---|---|
| Servidor | **DNS/XCIPTV/API** — ex.: `http://seu-painel.exemplo:80` |
| Usuário | **Nome** |
| Senha | **Senha** |

O app valida no servidor antes de salvar. Suas credenciais ficam em
`%APPDATA%\Ranga\settings.json`, só nesta máquina.

## Controles no player

| | O que faz |
|---|---|
| **Esc** | em tela cheia, sai da tela cheia; fora dela, volta para a lista |
| **F** ou **F11** | entra / sai da tela cheia |
| **Duplo clique** no vídeo | entra / sai da tela cheia |
| Espaço | pausa / continua |
| ↑ ↓ | canal anterior / próximo (ao vivo) |
| ← → | volta 10s / avança 30s (filme e série) |

A barra fica **sobre o vídeo**, inclusive em tela cheia. Ela some sozinha
depois de três segundos parado e volta ao primeiro movimento do mouse. Com o
vídeo pausado, ou durante uma reconexão, ela fica.

Dois botões só aparecem quando há escolha a fazer: **áudio**, para filme com
mais de uma faixa (dublado e legendado, por exemplo), e **legenda**, quando o
canal manda alguma.

## Quando o canal cai

O Ranga tenta voltar sozinho, três vezes, e alterna entre os dois formatos
(HLS e MPEG-TS) no meio das tentativas — não precisa mais trocar à mão em
Configurações. A tela mostra "Reconectando (2 de 3)". Só depois disso vem a
mensagem de erro, e ela diz o que dá para saber: se o painel responder que seu
plano está com todas as conexões em uso, é isso que aparece, com o número.

O aviso vale também para o congelamento silencioso: quando o vídeo para de
chegar mas ninguém reclama, o app percebe em 12 segundos e reconecta.

## Se algo não funcionar

**"O motor de vídeo não carregou"** — a instalação está incompleta. O suspeito
mais comum é o antivírus, que às vezes põe em quarentena uma das dlls de
vídeo. Reinstale o Ranga; se repetir, libere a pasta do Ranga no antivírus.
Enquanto isso, instalar o [VLC 64 bits](https://www.videolan.org/vlc/) serve de
quebra-galho: o app usa o motor do sistema quando não acha o próprio, e
Configurações → **Motor de vídeo** mostra qual dos dois está em uso.

**O catálogo abre mas nenhum vídeo toca** — quase sempre é o servidor filtrando
o User-Agent. Configurações → **User-Agent** → escolha outro da lista.

**Um canal trava ou não abre** — o app já tenta os dois formatos sozinho ao
reconectar. Se um canal específico nunca abre, Configurações → **Formato do
canal ao vivo** troca qual deles é tentado primeiro.

**O vídeo pica ou o PC fica lento** — Configurações → **Como o vídeo aparece** →
**Acelerado**. O vídeo passa a ir direto para a placa, do jeito mais leve, e em
troca a barra de controles volta para baixo do vídeo e some em tela cheia.

## O que vem dentro

O motor de vídeo é a **libvlc 3.0.23**, a mesma biblioteca que o VLC usa por
baixo, distribuída sob a LGPL 2.1. A licença viaja no pacote, em
`Ranga\app\resources\vlc\COPYING.txt`. Só a parte usada para tocar vídeo vem
junto: interface, gravação, transmissão e leitura de disco ficaram de fora.
