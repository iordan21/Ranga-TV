package br.com.iordan.ranga.data

/**
 * As três seções que o painel Xtream expõe. Cada uma usa um par de "actions"
 * diferente na mesma URL (`player_api.php`), então guardo os nomes aqui em vez
 * de espalhar string solta pelo código.
 */
enum class Section(
    val label: String,
    val categoriesAction: String,
    val streamsAction: String,
) {
    LIVE("Ao vivo", "get_live_categories", "get_live_streams"),
    MOVIES("Filmes", "get_vod_categories", "get_vod_streams"),
    SERIES("Séries", "get_series_categories", "get_series");

    companion object {
        fun fromName(value: String?): Section = entries.firstOrNull { it.name == value } ?: LIVE
    }
}

data class Credentials(
    val host: String,
    val username: String,
    val password: String,
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    companion object {
        val EMPTY = Credentials("", "", "")
    }
}

/** Formato do stream ao vivo. O painel oferece os dois; ver [LiveFormat.fallback]. */
enum class LiveFormat(val extension: String, val label: String) {
    HLS("m3u8", "HLS (.m3u8)"),
    TS("ts", "MPEG-TS (.ts)");

    /** Se um falhar, o player tenta automaticamente o outro antes de desistir. */
    val fallback: LiveFormat get() = if (this == HLS) TS else HLS
}

data class Category(
    val id: String,
    val name: String,
    val section: Section,
)

/**
 * Tudo que aparece numa grade vira um CatalogItem. Isso deixa a tela de
 * navegação genérica: uma única grade renderiza canal, filme e série.
 * [key] é o identificador estável usado em favoritos e em "continuar assistindo".
 */
sealed interface CatalogItem {
    val key: String
    val title: String
    val image: String?
    val section: Section
}

data class Channel(
    val streamId: Int,
    override val title: String,
    override val image: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val number: Int?,
) : CatalogItem {
    override val key = "live:$streamId"
    override val section = Section.LIVE
}

data class Movie(
    val streamId: Int,
    override val title: String,
    override val image: String?,
    val categoryId: String?,
    /** `container_extension`: mp4, mkv, avi... entra literal na URL do stream. */
    val extension: String,
    val rating: Double?,
) : CatalogItem {
    override val key = "movie:$streamId"
    override val section = Section.MOVIES
}

data class SeriesItem(
    val seriesId: Int,
    override val title: String,
    override val image: String?,
    val categoryId: String?,
    val plot: String?,
    val rating: Double?,
    val genre: String?,
    val releaseDate: String?,
) : CatalogItem {
    override val key = "series:$seriesId"
    override val section = Section.SERIES
}

data class Episode(
    /** Id do episódio — é ele que vai na URL, não o id da série. */
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val image: String?,
    val plot: String?,
    val durationSecs: Int?,
) {
    val key = "episode:$id"
}

data class SeriesDetail(
    val series: SeriesItem,
    /** Temporada -> episódios, já ordenados. */
    val seasons: Map<Int, List<Episode>>,
) {
    val allEpisodes: List<Episode> = seasons.toSortedMap().values.flatten()
}

data class MovieDetail(
    val movie: Movie,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationSecs: Int?,
    val backdrop: String?,
    val youtubeTrailer: String?,
)

data class EpgEntry(
    val title: String,
    val description: String?,
    val startEpochSecs: Long,
    val endEpochSecs: Long,
) {
    fun progressAt(nowSecs: Long): Float {
        val span = (endEpochSecs - startEpochSecs).toFloat()
        if (span <= 0f) return 0f
        return ((nowSecs - startEpochSecs) / span).coerceIn(0f, 1f)
    }
}

data class AccountInfo(
    val username: String,
    val status: String,
    val expiresAtEpochSecs: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    val allowedFormats: List<String>,
)

/**
 * O que o player realmente precisa saber. Canal, filme e episódio são
 * convertidos para isto antes de abrir a PlayerActivity — assim o player não
 * conhece nada de Xtream.
 */
data class Playable(
    val key: String,
    val title: String,
    val subtitle: String?,
    val url: String,
    val isLive: Boolean,
    val logo: String? = null,
    /** Só para canal ao vivo: usado para buscar o EPG "agora / a seguir". */
    val streamId: Int? = null,
    /** Só para canal ao vivo: URL alternativa no outro formato (.ts <-> .m3u8). */
    val fallbackUrl: String? = null,
    /** Só para VOD: path sem credencial, usado para gravar "continuar assistindo". */
    val resumePath: String? = null,
    /** Só para VOD: posição salva de onde retomar, em ms. */
    val startPositionMs: Long = 0L,
    val image: String? = null,
)
