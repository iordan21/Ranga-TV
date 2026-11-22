package br.com.iordan.ranga.data

/**
 * Conversores de item de catálogo -> [Playable].
 *
 * Ficam separados porque é aqui que mora a diferença entre ao vivo e VOD:
 *  - ao vivo ganha `fallbackUrl` (o outro formato) e `streamId` (para o EPG),
 *    e nunca ganha `resumePath` — ninguém quer "retomar" um canal;
 *  - VOD ganha `resumePath` e `startPositionMs`, e nunca ganha fallback —
 *    filme só existe num container.
 */

fun Channel.toPlayable(creds: Credentials, format: LiveFormat): Playable? {
    val url = XtreamUrls.live(creds, streamId, format) ?: return null
    return Playable(
        key = key,
        title = title,
        subtitle = null,
        url = url,
        isLive = true,
        logo = image,
        streamId = streamId,
        fallbackUrl = XtreamUrls.live(creds, streamId, format.fallback),
        image = image,
    )
}

fun Movie.toPlayable(creds: Credentials, startPositionMs: Long = 0L): Playable? {
    val path = XtreamUrls.moviePath(streamId, extension)
    val url = XtreamUrls.fromPath(creds, path) ?: return null
    return Playable(
        key = key,
        title = title,
        subtitle = null,
        url = url,
        isLive = false,
        resumePath = path,
        startPositionMs = startPositionMs,
        image = image,
    )
}

fun Episode.toPlayable(
    creds: Credentials,
    seriesTitle: String,
    startPositionMs: Long = 0L,
): Playable? {
    val path = XtreamUrls.episodePath(id, extension)
    val url = XtreamUrls.fromPath(creds, path) ?: return null
    return Playable(
        key = key,
        title = seriesTitle,
        subtitle = "T$season:E$number · $title",
        url = url,
        isLive = false,
        resumePath = path,
        startPositionMs = startPositionMs,
        image = image,
    )
}

/**
 * Canal aberto: a URL já é a final, sem credencial. Sem `streamId` porque o
 * EPG "agora / a seguir" é rota do painel Xtream, e sem `fallbackUrl` porque
 * só existe um formato — se o HLS oficial cair, não tem outro para tentar.
 */
fun FreeChannel.toPlayable(): Playable = Playable(
    key = key,
    title = name,
    subtitle = null,
    url = url,
    isLive = true,
    logo = logo,
    image = logo,
)

/**
 * Remonta um item de "continuar assistindo". Note que a URL é reconstruída com
 * as credenciais *atuais*: se você trocou a senha no painel, a lista salva
 * continua funcionando sem precisar limpar nada.
 */
fun ResumeEntry.toPlayable(creds: Credentials): Playable? {
    val url = XtreamUrls.fromPath(creds, streamPath) ?: return null
    return Playable(
        key = key,
        title = title,
        subtitle = subtitle,
        url = url,
        isLive = false,
        resumePath = streamPath,
        startPositionMs = positionMs,
        image = image,
    )
}
