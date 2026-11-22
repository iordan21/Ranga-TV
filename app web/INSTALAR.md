# Ranga Web

O PC vira o servidor; qualquer navegador da casa vira o player — a Fire TV
com Vega OS inclusive, que não instala APK.

## Ligar

1. Dê dois cliques em `RangaWeb.bat`. Na primeira vez ele compila (1–2 min).
2. O Windows vai perguntar se libera o acesso à rede. Marque **só "Redes
   privadas"**, não "públicas".
3. A janela mostra o endereço, algo como `http://192.168.0.101:8088`.
   Abra esse endereço no navegador da TV. Fechar a janela desliga o Ranga Web.

## Ligar sozinho com o PC

- Dois cliques em `LigarComOPC.bat`: cria um atalho na pasta Inicializar do
  Windows e já liga o Ranga Web, escondido, sem janela. Daí em diante ele liga
  sozinho toda vez que você entra no Windows.
- Para desligar o que está rodando escondido: `DesligarRangaWeb.bat`.
- Para parar de ligar sozinho: `LigarComOPC.bat -Remover` (ou apague o atalho
  "Ranga Web" em Win+R → `shell:startup`).
- Se algo der errado, o motivo fica em `ranga-web.log`, nesta pasta.
- Abrir o `RangaWeb.bat` com ele já ligado não cria uma segunda cópia: ele só
  avisa que já está ligado.

## Primeira vez

1. **Crie a senha de acesso.** Só dá para criar pelo endereço da rede de casa —
   assim ninguém de fora cria antes de você.
2. Em **Conta**, entre com servidor, usuário e senha do IPTV. Eles ficam
   guardados só no PC (`%APPDATA%\Ranga\web.json`); a TV nunca recebe a senha
   do painel.
3. Cada aparelho pede a senha de acesso uma vez. "Esquecer este aparelho", em
   Conta, desfaz.

## Controle remoto

Setas andam pelos itens, OK abre, Voltar volta. No player: ▲ ▼ trocam de
canal ao vivo; ◀ ▶ voltam e avançam 10 s em filme.

Ao dar play, a página entra em tela cheia sozinha e continua assim na volta
para a lista. O botão "Tela cheia" no topo liga e desliga.

## O que o navegador não toca

Canal ao vivo (HLS e .ts) toca. Filme e episódio em **mp4** tocam. Em
**.mkv / .avi**, ou com áudio AC3, o navegador costuma não tocar ou tocar sem
som — limitação do navegador, não do Ranga. Para esses, use o app.

## Proteção

- Nada funciona sem a senha de acesso: lista, vídeo, nada.
- A senha não fica guardada, só um hash dela.
- Cinco senhas erradas em 10 minutos travam a entrada por 5 minutos.
- O servidor só repassa vídeo de links que ele mesmo montou (assinados). Não dá
  para usá-lo para acessar outros aparelhos da sua rede.
- A página só roda o código dela mesma; nome de canal nunca vira código.

## Fora de casa

**Melhor jeito: Tailscale.** De graça, criptografado, e só os seus aparelhos
entram. Instale no PC e no celular/tablet, entre com a mesma conta, e abra
`http://NOME-DO-PC:8088` de onde estiver. Nada fica exposto na internet.

**Com playit (grátis):** funciona — crie um túnel TCP apontando para a porta
8088. Mas o plano grátis do playit não tem HTTPS: a senha de acesso e o vídeo
passam sem criptografia. Em Wi-Fi público, alguém na mesma rede consegue ver a
senha. HTTPS no playit exige o plano pago e um programa a mais (Caddy) no PC.
