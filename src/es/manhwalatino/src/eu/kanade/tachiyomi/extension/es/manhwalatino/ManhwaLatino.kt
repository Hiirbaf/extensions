package eu.kanade.tachiyomi.extension.es.manhwalatino

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class ManhwaLatino :
    Madara(
        "Manhwa-Latino",
        "https://manhwa-latino.com",
        "es",
        SimpleDateFormat("dd/MM/yyyy", Locale("es")),
    ) {

    /**
     * Cloudflare + Turnstile safe client
     */
    override val client: OkHttpClient = super.client.newBuilder()
        // IMPORTANTÍSIMO para evitar rate limiter
        .rateLimitHost(
            baseUrl.toHttpUrl(),
            1,
            3,
        )
        .addInterceptor { chain ->

            val request = chain.request()
            val response = chain.proceed(request)
            val bodyString = response.peekBody(1024 * 1024).string()
            // Detectar challenge Cloudflare
            if (
                response.code == 503 ||
                response.code == 403 ||
                bodyString.contains("challenge-platform") ||
                bodyString.contains("cf-chl") ||
                bodyString.contains("turnstile")
            ) {

                response.close()

                throw Exception(
                    "Cloudflare bloqueó el acceso.\n\n" +
                        "Abrí el manga en WebView y resolvé el CAPTCHA.",
                )
            }

            response
        }
        .build()

    /**
     * Madara moderno
     */
    override val useNewChapterEndpoint = true

    override val useLoadMoreRequest = true

    /**
     * Selectores específicos del sitio
     */
    override val chapterUrlSelector = "div.mini-letters > a"

    override val mangaDetailsSelectorStatus =
        "div.post-content_item:contains(Estado del comic) > div.summary-content"

    override val mangaDetailsSelectorDescription =
        "div.post-content_item:contains(Resumen) div.summary-container"

    override val pageListParseSelector =
        "div.page-break img.wp-manga-chapter-img"

    /**
     * Paginación real de capítulos
     */
    private val chapterListNextPageSelector =
        "div.pagination > span.current + span"

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url
        var document = response.asJsoup()

        val chapterList = mutableListOf<SChapter>()

        var page = 1

        do {
            val chapterElements =
                document.select(chapterListSelector())

            if (chapterElements.isEmpty()) break

            chapterList.addAll(
                chapterElements.map {
                    chapterFromElement(it)
                },
            )

            val hasNextPage =
                document.select(
                    chapterListNextPageSelector,
                ).isNotEmpty()

            if (hasNextPage) {
                page++

                val nextPageUrl =
                    mangaUrl.newBuilder()
                        .setQueryParameter(
                            "t",
                            page.toString(),
                        )
                        .build()

                document =
                    client.newCall(
                        GET(nextPageUrl, headers),
                    ).execute().asJsoup()
            } else {
                break
            }
        } while (true)

        return chapterList
    }

    /**
     * Fix URLs de capítulos
     */
    override fun chapterFromElement(
        element: Element,
    ): SChapter {
        val chapter = SChapter.create()

        with(element) {
            selectFirst(chapterUrlSelector)!!.let { urlElement ->

                chapter.url =
                    urlElement.attr("abs:href")
                        .substringBefore("?style=paged")
                        .let {
                            if (!it.endsWith(chapterUrlSuffix)) {
                                it + chapterUrlSuffix
                            } else {
                                it
                            }
                        }

                chapter.name =
                    urlElement.wholeText()
                        .substringAfter("\n")
                        .trim()
            }

            chapter.date_upload =
                selectFirst("img:not(.thumb)")
                    ?.attr("alt")
                    ?.let { parseRelativeDate(it) }
                    ?: parseChapterDate(
                        selectFirst(
                            chapterDateSelector(),
                        )?.text(),
                    )
        }

        return chapter
    }

    /**
     * Forzar headers reales tipo navegador
     */
    override fun headersBuilder() = super.headersBuilder()
        .add(
            "Referer",
            "$baseUrl/",
        )
        .add(
            "Upgrade-Insecure-Requests",
            "1",
        )
        .add(
            "Accept",
            "text/html,application/xhtml+xml",
        )
}
