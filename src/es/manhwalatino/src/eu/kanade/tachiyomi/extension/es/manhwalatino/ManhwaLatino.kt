package eu.kanade.tachiyomi.extension.es.manhwalatino

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
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
    ),
    ConfigurableSource {

    private val preferences: SharedPreferences = super.preferences

    private val serverPref = "server_url"

    private val serverValues = arrayOf(
        "https://manhwa-latino.com",
        "https://manhwa-es.com",
    )

    private val serverEntries = arrayOf(
        "Manhwa-Latino",
        "Manhwa-ES",
    )

    override val baseUrl: String
        get() = preferences.getString(serverPref, serverValues[0])!!

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 1, 1)
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder()
                .removeAll("Accept-Encoding")
                .build()

            val newUrl = request.url.toString()
                .replace("manhwa-latino.com", baseUrl.removePrefix("https://"))
                .replace("manhwa-es.com", baseUrl.removePrefix("https://"))
                .toHttpUrl()

            val response = chain.proceed(
                request.newBuilder()
                    .url(newUrl)
                    .headers(headers)
                    .build(),
            )

            if (
                response.headers("Content-Type").contains("application/octet-stream") &&
                response.request.url.toString().endsWith(".jpg")
            ) {
                val newBody = response.body.source()
                    .asResponseBody("image/jpeg".toMediaType())

                response.newBuilder().body(newBody).build()
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

        val chapterList = mutableListOf<SChapter>()
        var page = 1

        do {
            val chapterElements = document.select(chapterListSelector())
            if (chapterElements.isEmpty()) break
            chapterList.addAll(chapterElements.map { chapterFromElement(it) })
            val hasNextPage = document.select(chapterListNextPageSelector).isNotEmpty()
            if (hasNextPage) {
                page++
                val nextPageUrl = mangaUrl.newBuilder().setQueryParameter("t", page.toString()).build()
                document = client.newCall(GET(nextPageUrl, headers)).execute().asJsoup()
            } else {
                break
            }
        } while (true)

        return chapterList
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        with(element) {
            selectFirst(chapterUrlSelector)!!.let { urlElement ->
                chapter.url = urlElement.attr("abs:href").let {
                    it.substringBefore("?style=paged") + if (!it.endsWith(chapterUrlSuffix)) chapterUrlSuffix else ""
                }
                chapter.name = urlElement.wholeText().substringAfter("\n")
            }

            // ❌ Sin fecha (el sitio no da año)
            chapter.date_upload = 0L
        }

        return chapter
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val pref = ListPreference(screen.context).apply {
            key = serverPref
            title = "Servidor"
            entries = serverEntries
            entryValues = serverValues
            setDefaultValue(serverValues[0])
            summary = "%s"
        }

        screen.addPreference(pref)
    }
}
