package br.com.iordan.ranga.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Fábrica de MediaSource do Ranga: igual à padrão, exceto que o HLS passa por
 * um parser de playlist tolerante.
 *
 * Existe por causa de um caso real: o master da TV Cultura anuncia
 * `FRAME-RATE=0.000`, e o Media3 1.11 recusa a playlist inteira por isso —
 * `Format.Builder.setFrameRate` exige valor positivo e lança
 * IllegalArgumentException dentro do parser. O VLC toca o mesmo link sem
 * reclamar. A `DefaultMediaSourceFactory` não deixa trocar o parser do HLS,
 * então esta classe despacha: `.m3u8` vai para uma `HlsMediaSource.Factory`
 * com o parser tolerante, e todo o resto (.ts, .mp4, .mkv) segue na padrão.
 */
@UnstableApi
class RangaMediaSourceFactory(dataSourceFactory: DataSource.Factory) : MediaSource.Factory {

    private val default = DefaultMediaSourceFactory(dataSourceFactory)
    private val hls = HlsMediaSource.Factory(dataSourceFactory)
        .setPlaylistParserFactory(TolerantHlsPlaylistParserFactory())

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        default.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        default.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration
        val type = Util.inferContentTypeForUriAndMimeType(config?.uri ?: Uri.EMPTY, config?.mimeType)
        return if (type == C.CONTENT_TYPE_HLS) hls.createMediaSource(mediaItem)
        else default.createMediaSource(mediaItem)
    }
}

/**
 * Entrega o parser oficial do Media3, mas com o texto da playlist limpo antes.
 * As duas sobrecargas existem porque o Media3 cria um parser novo para cada
 * playlist de mídia, já sabendo qual é o master dela.
 */
@UnstableApi
private class TolerantHlsPlaylistParserFactory : HlsPlaylistParserFactory {

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        TolerantPlaylistParser(HlsPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        TolerantPlaylistParser(HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist))
}

@UnstableApi
private class TolerantPlaylistParser(
    private val delegate: HlsPlaylistParser,
) : ParsingLoadable.Parser<HlsPlaylist> {

    /**
     * Lê a playlist inteira para a memória para poder reescrevê-la. Playlist
     * HLS tem poucos KB, mesmo a de mídia que o ao vivo rebaixa a cada poucos
     * segundos — o custo não aparece.
     */
    override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
        val text = inputStream.readBytes().toString(Charsets.UTF_8)
        val cleaned = ZERO_FRAME_RATE.replace(text, "")
        return delegate.parse(uri, ByteArrayInputStream(cleaned.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        /**
         * Só o valor zero, em qualquer grafia (`0`, `0.0`, `0.000`), e com a
         * vírgula que o separa do atributo vizinho — de qualquer lado em que ela
         * esteja. FRAME-RATE válido continua chegando ao parser intacto.
         */
        val ZERO_FRAME_RATE = Regex("""(?:,FRAME-RATE=0(?:\.0+)?(?![\d.])|(?<=:)FRAME-RATE=0(?:\.0+)?,)""")
    }
}
