# Assinatura do APK

Todo APK Android é assinado. A assinatura é a identidade do app: o Android só
aceita atualizar um app por cima de outro se as duas assinaturas forem a mesma.

Por padrão o Ranga sai assinado com a **chave de debug** — a que o Android
Studio gera sozinho em `~/.android/debug.keystore`. Funciona para instalar por
`adb`, e é por isso que era o padrão aqui.

O problema aparece depois. A chave de debug tem validade e é recriada pelas
ferramentas quando expira ou quando você troca de máquina. Assinatura nova não
atualiza por cima da antiga: o Android recusa e manda desinstalar primeiro. E
desinstalar apaga o DataStore — login do painel, favoritos e o "continuar
assistindo" vão junto.

Uma chave sua, guardada num arquivo que você controla, elimina isso.

## Gerar a chave

Uma vez só. Roda no terminal, dentro de `app tv`:

```
keytool -genkeypair -v -keystore ranga-release.jks -alias ranga -keyalg RSA -keysize 4096 -validity 10000
```

O `keytool` vai pedir uma senha e alguns dados (nome, cidade, país). Só a senha
importa — o resto pode ser qualquer coisa, é um app pessoal.

**Guarde a senha e o `.jks` num lugar que sobreviva à formatação da máquina.**
Perdeu a chave, perdeu o direito de atualizar o app: os aparelhos com a versão
antiga instalada vão precisar desinstalar e perder os dados. Não existe
recuperação — é assim por desenho.

## Apontar o build para ela

Crie `keystore.properties` na pasta `app tv`, ao lado do `settings.gradle.kts`:

```
storeFile=ranga-release.jks
storePassword=SUA_SENHA
keyAlias=ranga
keyPassword=SUA_SENHA
```

Se você não trocou a senha da chave quando o `keytool` perguntou, as duas
senhas são a mesma.

**O `storeFile` tem que ser caminho relativo**, como no exemplo. Caminho
absoluto do Windows quebra: em arquivo `.properties` o dois-pontos separa
chave de valor e a barra invertida é escape, então um caminho como
`C:\chaves\ranga.jks` chega no build como lixo. Deixe o `.jks` na pasta
`app tv` e escreva só o nome dele.

Esse arquivo está no `.gitignore` e não deve sair da sua máquina. Ele tem senha
em texto puro — é o padrão do Android para projeto pessoal, mas trate como
trata uma senha.

## Conferir que pegou

```
./gradlew dist
```

Se o `keystore.properties` estiver faltando ou com campo errado, o build avisa
no log e assina com a chave de debug. Para ver com qual chave o APK saiu:

```
apksigner verify --print-certs dist/Ranga-1.0.apk
```

Chave de debug aparece como `CN=Android Debug`. A sua aparece com os dados que
você digitou no `keytool`.

## Na hora de trocar a chave

O primeiro APK assinado com a chave nova **não** atualiza por cima do antigo,
que estava com a de debug. Nesse aparelho, uma vez só: desinstale e instale de
novo, e refaça o login no painel. Depois disso, atualização normal.
