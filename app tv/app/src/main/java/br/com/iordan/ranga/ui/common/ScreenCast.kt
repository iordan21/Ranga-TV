package br.com.iordan.ranga.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * "Transmitir para a TV" por espelhamento de tela (Miracast), e não Google Cast.
 *
 * O Google Cast mandaria só o link do vídeo e a TV tocaria sozinha, com
 * qualidade cheia — seria o ideal. Mas ele depende de duas coisas que faltam
 * justamente no caso que motivou isto: a Fire TV com Vega OS não recebe Cast,
 * e celular com ROM chinesa não tem o Google Play Services que o SDK do Cast
 * exige. Miracast a Vega aceita, e todo Android com "Transmitir tela" fala.
 *
 * App comum não consegue iniciar o espelhamento sozinho: a API de Wi-Fi
 * Display é privada do sistema. O que dá para fazer é abrir a tela de
 * transmissão do próprio Android, já com a lista de TVs.
 */
object ScreenCast {

    /** Na TV não faz sentido: ela é o destino, não a origem. */
    fun isAvailable(context: Context): Boolean =
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun open(context: Context) {
        // A ação padrão primeiro; a segunda é o nome antigo que várias
        // ROMs (Xiaomi inclusive) ainda registram no lugar dela.
        val candidates = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Esta ROM não tem essa tela; tenta a próxima.
            } catch (_: SecurityException) {
                // Algumas ROMs registram a tela mas não deixam app de fora abrir.
            }
        }
        // Na Samsung o espelhamento se chama Smart View e mora só no painel
        // rápido. A Samsung não documenta Intent para abri-lo, e o pacote dele
        // (com.samsung.android.smartmirroring) não expõe tela de entrada, então
        // o melhor que dá é dizer onde fica.
        val hint = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            "Deslize a barra do topo para baixo duas vezes e toque em Smart View. Escolha a TV."
        } else {
            "Abra \"Transmitir tela\" na central de controle e escolha a TV."
        }
        Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
    }

    /**
     * Há uma tela externa ligada — espelhamento sem fio ou cabo HDMI.
     * O Android as classifica como "presentation"; a tela do próprio aparelho
     * nunca entra nessa categoria.
     */
    fun isMirroring(context: Context): Boolean {
        val displays = context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return !displays.isNullOrEmpty()
    }

    /**
     * Ícone "cast" do Material. Só ele: o material-icons-extended traria
     * ~1.500 vetores para usar um (ver build.gradle.kts).
     */
    val icon: ImageVector by lazy {
        ImageVector.Builder(
            name = "Cast",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(
                "M21,3H3C1.9,3 1,3.9 1,5v3h2V5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3z" +
                    "M1,18v3h3C4,19.34 2.66,18 1,18z" +
                    "M1,14v2c2.76,0 5,2.24 5,5h2C8,17.13 4.87,14 1,14z" +
                    "M1,10v2c4.97,0 9,4.03 9,9h2C12,14.92 7.07,10 1,10z"
            ),
            fill = SolidColor(Color.Black),
        ).build()
    }
}
