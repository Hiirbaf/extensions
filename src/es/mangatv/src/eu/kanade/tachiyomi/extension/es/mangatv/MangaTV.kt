package eu.kanade.tachiyomi.extension.es.mangatv

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
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
        "Manga  TV",
        "https://mangatv.net",
        "es",
        mangaUrlDirectory = "/lista",
        dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT),
    ) {

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(mangaUrlDirectory.substring(1))
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("s", query)
        }

        return GET(url.build(), headers)
    }

    override val seriesDescriptionSelector = "b:contains(Sinopsis) + span"

    override fun pageListParse(document: Document): List<Page> {
        val unpackedScript = document.selectFirst("script:containsData(eval)")!!.data()
            .let(Unpacker::unpack)

        val imageListJson = JSON_IMAGE_LIST_REGEX.find(unpackedScript)?.destructured?.toList()?.get(0).orEmpty()
        val imageList = try {
            json.parseToJsonElement(imageListJson.replace(TRAILING_COMMA_REGEX, "]")).jsonArray
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        return imageList.mapIndexed { i, jsonEl ->
            val encodedLink = jsonEl.jsonPrimitive.content
            val decodedLink = String(Base64.decode(encodedLink, Base64.DEFAULT))
            Page(i, document.location(), "https:$decodedLink")
        }
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        val spans = element.select(".chapternum")

        val chapterText = spans.getOrNull(0)?.text().orEmpty()
        val infoText = spans.getOrNull(1)?.text().orEmpty()

        chapter.name = chapterText

        chapter.chapter_number = chapterText
            .substringAfter("Capítulo", "")
            .trim()
            .replace(",", ".")
            .toFloatOrNull() ?: -1f

        infoText.substringAfter("|", "")
            .replace("Fansub", "")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { chapter.scanlator = it }

        chapter.setUrlWithoutDomain(element.select("a").attr("href"))

        chapter.date_upload = element.selectFirst(".chapterdate")?.text().parseChapterDate()

        return chapter
    }

    // TODO: add demografia, order, tipos, genre
    override fun getFilterList() = FilterList()

    companion object {
        val TRAILING_COMMA_REGEX = """,\s+]""".toRegex()
    }
}
