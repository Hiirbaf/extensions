package eu.kanade.tachiyomi.extension.es.mangatv

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.lib.unpacker.Unpacker
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
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

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(mangaUrlDirectory.substring(1))
            .addQueryParameter("s", query)
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is AuthorFilter -> url.addQueryParameter("author", filter.state)
                is YearFilter -> url.addQueryParameter("yearx", filter.state)
                is StatusFilter -> url.addQueryParameter("status", filter.selectedValue())
                is TypeFilter -> url.addQueryParameter("type", filter.selectedValue())
                is OrderByFilter -> url.addQueryParameter("order", filter.selectedValue())

                is GenreListFilter -> {
                    filter.state
                        .filter { it.state != Filter.TriState.STATE_IGNORE }
                        .forEach {
                            val value = if (it.state == Filter.TriState.STATE_EXCLUDE)
                                "-${it.value}" else it.value
                            url.addQueryParameter("genre[]", value)
                        }
                }

                is ProjectFilter -> {
                    if (filter.selectedValue() == "project-filter-on") {
                        url.setPathSegment(0, projectPageString.substring(1))
                    }
                }

                else -> Unit
            }
        }

        url.addPathSegment("")
        return GET(url.build(), headers)
    }

    companion object {
        val TRAILING_COMMA_REGEX = """,\s+]""".toRegex()
    }
}
