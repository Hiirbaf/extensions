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
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

open class LectorMOnline(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : HttpSource() {

    override val supportsLatest = true

        /* ============================
         * POPULAR / LATEST / SEARCH
         * ============================ */

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/comics?page=$page&sort=views", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/comics?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val genre = genreFilter?.toUriPart()

        val url = when {
            !genre.isNullOrBlank() -> "$baseUrl/api/comics?page=$page&genres=$genre"
            else -> "$baseUrl/api/comics?page=$page"
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val json = response.body!!.string()
        val obj = JSONObject(json)

        val data = obj.getJSONArray("data")
        val mangas = mutableListOf<SManga>()

        for (i in 0 until data.length()) {
            val mangaObj = data.getJSONObject(i)

            val manga = SManga.create().apply {
                title = mangaObj.getString("title")
                setUrlWithoutDomain(mangaObj.getString("slug"))
                thumbnail_url = mangaObj.optString("coverImage")
            }

            mangas.add(manga)
        }

        val pagination = obj.getJSONObject("pagination")
        val currentPage = pagination.getInt("page")
        val totalPages = pagination.getInt("totalPages")
        val hasNextPage = currentPage < totalPages

        return MangasPage(mangas, hasNextPage)
    }

        /* ============================
         * DETAILS
         * ============================ */

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comics/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/api/comics/${manga.url}", headers)

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

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

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
