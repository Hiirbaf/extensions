package eu.kanade.tachiyomi.multisrc.lectormonline

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.Request
import okhttp3.Response

open class LectorMOnline(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : HttpSource() {

    override val supportsLatest = true

        /* ============================
         * POPULAR
         * ============================ */

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/comics/popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = response.parseAs<List<ComicDto>>()
            .map { it.toSManga() }

        return MangasPage(
            mangas,
            false,
        )
    }

        /* ============================
         * LATEST
         * ============================ */

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/comics?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

        /* ============================
         * SEARCH
         * ============================ */

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val genre = genreFilter?.toUriPart()

        val url = when {
            !genre.isNullOrBlank() -> "$baseUrl/api/comics?page=$page&genres=$genre"
            query.isNotBlank() -> "$baseUrl/api/comics?page=$page&search=${query.trim()}"
            else -> "$baseUrl/api/comics?page=$page"
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val obj = response.parseAs<ComicListDto>()

        val mangas = obj.data.map { it.toSManga() }

        return MangasPage(
            mangas,
            obj.hasNextPage(),
        )
    }

        /* ============================
         * DETAILS
         * ============================ */

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comics/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/api/comics/slug/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<ComicDto>().toSMangaDetails()

        /* ============================
         * CHAPTERS
         * ============================ */

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<ComicDto>().getChapters()

        /* ============================
         * PAGES
         * ============================ */

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/api/chapters/${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val obj = response.parseAs<ChapterResponseDto>()

        return obj.data.urlPages.mapIndexed { index, image ->
            Page(index, imageUrl = image)
        }
    }

        /* ============================
         * FILTERS
         * ============================ */

    override fun getFilterList(): FilterList = FilterList(
        SortByFilter(
            "Ordenar por",
            listOf(
                SortProperty("Popularidad", "views"),
                SortProperty("Recientes", "latest"),
            ),
            0,
        ),
        GenreFilter(
            listOf(
                "Acción" to "Acción",
                "Action" to "Action",
                "Adulto" to "Adulto",
                "Adventure" to "Adventure",
                "Boys love" to "Boys love",
                "Comedy" to "Comedy",
                "Drama" to "Drama",
                "Fantasy" to "Fantasy",
                "Harem" to "Harem",
                "Historical" to "Historical",
                "Horror" to "Horror",
                "Isekai" to "Isekai",
                "Josei" to "Josei",
                "Romance" to "Romance",
                "Seinen" to "Seinen",
                "Shoujo" to "Shoujo",
                "Shounen" to "Shounen",
                "Slice of life" to "Slice of life",
                "Supernatural" to "Supernatural",
                "Transmigración" to "Transmigración",
                "Yaoi" to "Yaoi",
                "Yuri" to "Yuri",
            ),
        ),
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
