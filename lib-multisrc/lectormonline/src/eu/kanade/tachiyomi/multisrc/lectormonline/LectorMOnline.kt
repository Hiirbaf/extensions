package eu.kanade.tachiyomi.multisrc.lectormonline

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import uy.kohesive.injekt.injectLazy

open class LectorMOnline(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    // 🔥 Preferences (MEJOR ARRIBA)
    override val preferences: SharedPreferences
        get() = sourcePreferences()

    private fun showNsfw(): Boolean = preferences.getBoolean(SHOW_NSFW, false)

        /* ============================
         * POPULAR
         * ============================ */

    override fun popularMangaRequest(page: Int): Request {
        val showNsfw = showNsfw()
        return GET("$baseUrl/api/comics/popular?limit=100&nsfw=$showNsfw", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = response.parseAs<List<ComicDto>>()
            .map { it.toSManga() }

        return MangasPage(mangas, false)
    }

        /* ============================
         * LATEST
         * ============================ */

    override fun latestUpdatesRequest(page: Int): Request {
        val showNsfw = showNsfw()
        return GET("$baseUrl/api/comics?page=$page&nsfw=$showNsfw", headers)
    }

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

        val sortFilter = filters.filterIsInstance<SortByFilter>().firstOrNull()
        val sort = sortFilter?.selected

        val showNsfw = showNsfw()

        val url = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("nsfw", showNsfw.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query.trim())
        }

        if (!genre.isNullOrBlank()) {
            url.addQueryParameter("genres", genre)
        }

        if (!sort.isNullOrBlank()) {
            url.addQueryParameter("sort", sort)
        }

        return GET(url.build(), headers)
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

    private var genresCache: List<String>? = null

    override fun getFilterList(): FilterList {
        val genres = genresCache ?: run {
            try {
                val request = GET("$baseUrl/api/comics?genres=", headers)
                val response = client.newCall(request).execute()

                val dto = response.parseAs<GenreResponseDto>()

                genresCache = dto.genres
                dto.genres
            } catch (e: Exception) {
                emptyList()
            }
        }

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
            GenreFilter(genres),
        )
    }

        /* ============================
         * SETTINGS
         * ============================ */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val nsfwPref = CheckBoxPreference(screen.context).apply {
            key = SHOW_NSFW
            title = "Mostrar contenido NSFW"
            summary = "Incluir mangas +18 en los resultados"
            setDefaultValue(false)
        }

        screen.addPreference(nsfwPref)
    }

    companion object {
        private const val SHOW_NSFW = "show_nsfw"
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
