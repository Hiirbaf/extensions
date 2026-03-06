package eu.kanade.tachiyomi.extension.es.manhwalatino

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
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

    init {
        launchIO {
            runCatching {
                client.newCall(GET("$baseUrl/manga/el-jugador/", headers)).execute().close()
            }
        }
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 1, 1)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .build()

            val response = chain.proceed(request)

            if (response.isJpegServedAsOctetStream()) {
                response.rewrapBodyAs("image/jpeg")
            } else {
                response
            }
        }
        .build()

    override val useNewChapterEndpoint = true

    override val chapterUrlSelector = "div.mini-letters > a"

    override val mangaDetailsSelectorStatus = "div.post-content_item:contains(Estado del comic) > div.summary-content"
    override val mangaDetailsSelectorDescription = "div.post-content_item:contains(Resumen) div.summary-container"
    override val pageListParseSelector = "div.page-break img.wp-manga-chapter-img"

    private val chapterListNextPageSelector = "div.pagination > span.current + span"
    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url
        var document = response.asJsoup()
        launchIO { countViews(document) }

        return buildList {
            var page = 1
            do {
                val elements = document.select(chapterListSelector())
                if (elements.isEmpty()) break

                elements.mapTo(this) { chapterFromElement(it) }

                val hasNextPage = document.select(chapterListNextPageSelector).isNotEmpty()
                if (!hasNextPage) break

                val nextUrl = mangaUrl.newBuilder()
                    .setQueryParameter("t", (++page).toString())
                    .build()
                document = client.newCall(GET(nextUrl, headers)).execute().asJsoup()
            } while (true)
        }
    }

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val urlElement = element.selectFirst(chapterUrlSelector)!!

        url = urlElement.attr("abs:href").let { href ->
            val base = href.substringBefore("?style=paged")
            if (base.endsWith(chapterUrlSuffix)) base else base + chapterUrlSuffix
        }
        name = urlElement.wholeText().substringAfter("\n")

        date_upload = element.selectFirst("img:not(.thumb)")?.attr("alt")?.let { parseRelativeDate(it) }
            ?: element.selectFirst("span a")?.attr("title")?.let { parseRelativeDate(it) }
            ?: parseChapterDate(element.selectFirst(chapterDateSelector())?.text())
    }

    // --- Private helpers ---

    private fun Response.isJpegServedAsOctetStream(): Boolean = headers("Content-Type").contains("application/octet-stream") &&
        request.url.toString().endsWith(".jpg")

    private fun Response.rewrapBodyAs(mediaType: String): Response = newBuilder()
        .body(body.source().asResponseBody(mediaType.toMediaType()))
        .build()
}
