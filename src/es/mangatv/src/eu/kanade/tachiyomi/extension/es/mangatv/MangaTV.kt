package eu.kanade.tachiyomi.extension.es.mangatv

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.multisrc.mangathemesia.GenreFilter
import eu.kanade.tachiyomi.multisrc.mangathemesia.OrderByFilter
import eu.kanade.tachiyomi.multisrc.mangathemesia.StatusFilter
import eu.kanade.tachiyomi.multisrc.mangathemesia.TypeFilter
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

    override val popularFilter = FilterList(OrderByFilter("popular"))
    override val latestFilter = FilterList(OrderByFilter("update"))

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(mangaUrlDirectory.substring(1))
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) url.addQueryParameter("s", query)

        filters.forEach {
            when (it) {
                is OrderByFilter -> it.toUriPart().takeIf(String::isNotEmpty)?.let { v -> url.addQueryParameter("order", v) }
                is GenreFilter -> it.toUriPart().takeIf(String::isNotEmpty)?.let { v -> url.addQueryParameter("genre", v) }
                is TypeFilter -> it.toUriPart().takeIf(String::isNotEmpty)?.let { v -> url.addQueryParameter("type", v) }
                is StatusFilter -> it.toUriPart().takeIf(String::isNotEmpty)?.let { v -> url.addQueryParameter("status", v) }
            }
        }

        return GET(url.build(), headers)
    }

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

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val spans = element.select(".chapternum")

        val chapterText = spans.getOrNull(0)?.text().orEmpty()
        val infoText = spans.getOrNull(1)?.text().orEmpty()

        chapter.name = chapterText
        chapter.chapter_number = chapterText.substringAfter("Capítulo", "")
            .trim().replace(",", ".").toFloatOrNull() ?: -1f

        infoText.substringAfter("|", "")
            .replace("Fansub", "")
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { chapter.scanlator = it }

        chapter.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        chapter.date_upload = element.selectFirst(".chapterdate")?.text().parseChapterDate()

        return chapter
    }

    override fun getFilterList() = FilterList(
        Filter.Header("Filtros"),
        OrderByFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    companion object {
        private val TRAILING_COMMA_REGEX = """,\s+]""".toRegex()
    }
}
