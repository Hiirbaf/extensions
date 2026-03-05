package eu.kanade.tachiyomi.multisrc.lectormonline

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

open class LectorMOnline(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : HttpSource() {

    override val supportsLatest = true

    /* ============================
     * POPULAR / LATEST / SEARCH
     * ============================ */

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/comics?page=$page&sort=views", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/comics?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("comics")
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortByFilter -> {
                    url.addQueryParameter("sort", filter.selected)
                    url.addQueryParameter(
                        "isDesc",
                        filter.state!!.ascending.not().toString()
                    )
                }
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<ComicListDto>()

        val mangas = dto.comics.map { comic ->
            SManga.create().apply {
                title = comic.title
                thumbnail_url = comic.coverImage
                url = comic.id.toString() // usamos ID numérico
            }
        }

        return MangasPage(mangas, dto.hasNextPage())
    }

    /* ============================
     * DETAILS
     * ============================ */

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/comics/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/api/comics/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = response.parseAs<ComicDto>()

        return SManga.create().apply {
            title = obj.title
            description = obj.description
            thumbnail_url = obj.coverImage

            status = when (obj.status) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            genre = obj.comicGenres.joinToString(", ") { it.name }
        }
    }

    /* ============================
     * CHAPTERS
     * ============================ */

    override fun chapterListRequest(manga: SManga): Request =
        mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = response.parseAs<ComicDto>()
        val chapters = mutableListOf<SChapter>()

        obj.comicScans.forEach { scan ->
            scan.chapters.forEach { ch ->
                chapters += SChapter.create().apply {
                    url = ch.id.toString() // solo ID
                    name = "Capítulo ${ch.chapterNumber}"
                    chapter_number = ch.chapterNumber.toFloat()
                    date_upload = parseDate(ch.releaseDate)
                }
            }
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    /* ============================
     * PAGES
     * ============================ */

    override fun pageListRequest(chapter: SChapter): Request {
        // reutilizamos el endpoint del manga completo
        return GET("$baseUrl/api/comics/${chapter.mangaUrl}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.pathSegments.last().toInt()
        val obj = response.parseAs<ComicDto>()

        obj.comicScans.forEach { scan ->
            scan.chapters.forEach { ch ->
                if (ch.id == chapterId) {
                    return ch.urlPages.mapIndexed { index, image ->
                        Page(index, imageUrl = image)
                    }
                }
            }
        }

        return emptyList()
    }

    /* ============================
     * FILTERS (solo orden)
     * ============================ */

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Ordenar resultados"),
            SortByFilter(
                "Ordenar por",
                listOf(
                    SortProperty("Más vistos", "views"),
                    SortProperty("Más recientes", "created_at"),
                ),
                0,
            ),
        )
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()
}
