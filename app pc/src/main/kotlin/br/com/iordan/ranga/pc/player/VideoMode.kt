package br.com.iordan.ranga.pc.player

/**
 * Como o vídeo chega à tela. É uma troca real, não uma preferência de gosto.
 *
 * [OVERLAY] é o padrão porque o que ele destrava vale o custo: com o Compose
 * desenhando os quadros, dá para ter controles, legenda e mensagem de erro
 * *em cima* do vídeo — inclusive em tela cheia, onde antes a barra sumia e
 * sobrava só o teclado.
 *
 * [ACCELERATED] existe para quando isso pesar. Aí a libvlc escreve direto na
 * tela pelo Direct3D, sem passar pela CPU, que é o caminho mais leve que
 * existe — e o preço é que nada pode ser desenhado por cima.
 */
enum class VideoMode(val label: String, val explanation: String) {

    OVERLAY(
        label = "Com controles sobre o vídeo",
        explanation = "Os quadros passam pelo aplicativo antes de chegar à tela, " +
            "o que permite barra e mensagens sobrepostas, também em tela cheia. " +
            "A decodificação continua por hardware; o custo é a cópia de cada " +
            "quadro, que pesa um pouco mais no processador.",
    ),

    ACCELERATED(
        label = "Acelerado",
        explanation = "O vídeo vai direto para a placa, do jeito mais leve. " +
            "Em compensação a barra de controles fica abaixo do vídeo e some em " +
            "tela cheia. Use se o modo com sobreposição engasgar.",
    ),
}
