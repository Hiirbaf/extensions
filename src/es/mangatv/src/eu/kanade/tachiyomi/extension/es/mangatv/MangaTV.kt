package eu.kanade.tachiyomi.extension.es.mangatv

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.lib.unpacker.Unpacker
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MangaTV :
    MangaThemesia(
        "Manga TV",
        "https://mangatv.net",
        "es",
        mangaUrlDirectory = "/lista",
        dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT),
    ) {

    override val seriesDescriptionSelector = "b:contains(Sinopsis) + span"

    /* ================= REQUEST BUILDER ================= */

    private fun buildListRequest(page: Int, order: String? = null, query: String? = null, filters: FilterList? = null): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(mangaUrlDirectory.substring(1))
            .addQueryParameter("page", page.toString())

        order?.let { url.addQueryParameter("order", it) }
        query?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("s", it) }

        filters?.forEach { filter ->
            when (filter) {

                is StatusFilter -> {
                    val values = listOf("", "ongoing", "completed")
                    values[filter.state].takeIf { it.isNotEmpty() }?.let {
                        url.addQueryParameter("status", it)
                    }
                }

                is TypeFilter -> {
                    val values = listOf("", "manga", "manhwa", "manhua", "comic")
                    values[filter.state].takeIf { it.isNotEmpty() }?.let {
                        url.addQueryParameter("type", it)
                    }
                }

                is OrderFilter -> {
                    val values = listOf("popular", "update", "new", "title")
                    url.addQueryParameter("order", values[filter.state])
                }

                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    /* ================= POPULAR / LATEST ================= */

    override fun popularMangaRequest(page: Int) =
        buildListRequest(page, order = "popular")

    override fun latestUpdatesRequest(page: Int) =
        buildListRequest(page, order = "latest")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        buildListRequest(page, query = query, filters = filters)

    override fun popularMangaNextPageSelector() = "a[rel=next]"
    override fun latestUpdatesNextPageSelector() = "a[rel=next]"
    override fun searchMangaNextPageSelector() = "a[rel=next]"

    /* ================= PAGE LIST ================= */

    override fun pageListParse(document: Document): List<Page> {
        val unpacked = document.selectFirst("script:containsData(eval)")!!.data().let(Unpacker::unpack)

        val images = JSON_IMAGE_LIST_REGEX.find(unpacked)
            ?.groupValues?.getOrNull(1)
            ?.replace(TRAILING_COMMA_REGEX, "]")
            ?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() }
            ?: return emptyList()

        return images.mapIndexed { i, el ->
            val decoded = String(Base64.decode(el.jsonPrimitive.content, Base64.DEFAULT))
            Page(i, document.location(), "https:$decoded")
        }
    }

    /* ================= CHAPTER PARSER ================= */

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val spans = element.select(".chapternum")

        val chapterText = spans.getOrNull(0)?.text().orEmpty()
        val infoText = spans.getOrNull(1)?.text().orEmpty()

        chapter.name = chapterText

        chapter.chapter_number = Regex("""\d+(\.\d+)?""")
            .find(chapterText)
            ?.value
            ?.toFloatOrNull()
            ?: -1f

        infoText.substringAfter("|", "")
            .replace("Fansub", "")
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { chapter.scanlator = it }

        chapter.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        chapter.date_upload = element.selectFirst(".chapterdate")?.text().parseChapterDate()

        return chapter
    }

    /* ================= FILTERS ================= */

    override fun getFilterList() = FilterList(
        Filter.Header("Filtros"),
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    private class OrderFilter : Filter.Select<String>(
        "Ordenar",
        arrayOf("Popular", "Actualizado", "Nuevo", "A-Z"),
    )

    private class StatusFilter : Filter.Select<String>(
        "Estado",
        arrayOf("Todos", "En emisión", "Completo"),
    )

    private class TypeFilter : Filter.Select<String>(
        "Tipo",
        arrayOf("Todos", "Manga", "Manhwa", "Manhua", "Comic"),
    )

    companion object {
        private val TRAILING_COMMA_REGEX = """,\s+]""".toRegex()
    }
}
