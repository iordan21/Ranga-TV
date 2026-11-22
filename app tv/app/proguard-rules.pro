# R8 está LIGADO no release (optimization { enable = true }).
#
# Regra geral: só entra aqui o que o R8 não consegue enxergar sozinho, ou seja,
# código alcançado por reflexão. Tudo que é chamado normalmente em Kotlin ele
# rastreia sem ajuda — não adicione -keep "por precaução", cada um deles
# desfaz parte da otimização.

# --- Media3 / ExoPlayer ---------------------------------------------------
# O ExoPlayer instancia extractors e renderers por nome de classe (é assim que
# ele decide, em runtime, se o .ts precisa do TsExtractor ou o .m3u8 do HLS).
# Sem isto o vídeo simplesmente não abre no release, e o stack trace não diz
# o motivo — só um erro genérico de source.
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.datasource.** { *; }

# --- OkHttp ---------------------------------------------------------------
# Referências a plataformas que não existem no Android (Conscrypt, BouncyCastle,
# OpenJSSE). Não são usadas; só silenciam o aviso de referência ausente.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- kotlinx-serialization ------------------------------------------------
# Uso só o parser de JsonElement (sem o plugin de compilação), então basta
# manter os serializadores internos do próprio JsonElement.
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**
