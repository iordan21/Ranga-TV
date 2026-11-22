package br.com.iordan.ranga.pc.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Os ícones que faltavam no `material-icons-core`, desenhados aqui.
 *
 * A alternativa era a dependência `material-icons-extended`, que são **36 MB**
 * no pacote final — 40% de tudo que não é o JRE — para usar meia dúzia deles.
 * No app Android o R8 apagaria o resto; aqui o ProGuard está desligado (ver o
 * porquê no build.gradle.kts), então tudo que entra, fica.
 *
 * O `pathData` é o mesmo dos ícones oficiais do Material, em viewport 24x24.
 */
object RangaIcons {

    val Pause: ImageVector = icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")

    val Fullscreen: ImageVector = icon(
        name = "Fullscreen",
        pathData = "M7,14H5v5h5v-2H7v-3zm-2,-4h2V7h3V5H5v5zm12,7h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z",
    )

    val FullscreenExit: ImageVector = icon(
        name = "FullscreenExit",
        pathData = "M5,16h3v3h2v-5H5v2zm3,-8H5v2h5V5H8v3zm6,11h2v-3h3v-2h-5v5zm2,-11V5h-2v5h5V8h-3z",
    )

    val VolumeUp: ImageVector = icon(
        name = "VolumeUp",
        pathData = "M3,9v6h4l5,5V4L7,9H3zm13.5,3c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48," +
            "-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5," +
            "6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    )

    val AudioTrack: ImageVector = icon(
        name = "AudioTrack",
        pathData = "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79," +
            "4 4,4 4,-1.79 4,-4V7h4V3h-6z",
    )

    val ClosedCaption: ImageVector = icon(
        name = "ClosedCaption",
        pathData = "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0," +
            "-1.1 -0.9,-2 -2,-2zM11,11L9.5,11v-0.5h-2v3h2L9.5,13L11,13v1c0,0.55 -0.45,1 -1," +
            "1L7,15c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1zM18," +
            "11h-1.5v-0.5h-2v3h2L16.5,13L18,13v1c0,0.55 -0.45,1 -1,1h-3c-0.55,0 -1,-0.45 -1," +
            "-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1z",
    )

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Preto sólido: o `Icon` do Material3 pinta por cima com o tint,
            // então a cor daqui nunca aparece — serve só para o path existir.
            addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        }.build()
}
