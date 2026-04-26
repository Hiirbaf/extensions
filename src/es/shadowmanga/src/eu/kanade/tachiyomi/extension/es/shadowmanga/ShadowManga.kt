package eu.kanade.tachiyomi.extension.es.shadowmanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

private val genres = listOf(
    "Acción", "Aventura", "Artes marciales", "Boys Love", "Ciencia Ficción",
    "Comedia", "Drama", "Ecchi", "Fantasía", "Gore", "Harem", "Horror",
    "Misterio", "Psicológico", "Recuentos de la vida", "Romance",
    "Seinen", "Shoujo", "Shoujo-ai", "Shounen", "Shounen Ai", "Superpoderes",
    "Suspense", "Thriller", "Vida escolar", "Yaoi", "Yuri", "Isekai",
    "Magia", "Sobrenatural", "Webtoon", "Webcomic", "Novela", "Manhwa", "Manhua",
)

class ShadowManga :
    HttpSource(),
    ConfigurableSource {

    override val name = "ShadowManga"
    override val baseUrl = "https://shadowmanga.es"
    override val lang = "es"
    override val supportsLatest = true
    override val client: OkHttpClient = network.client

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {}

    private fun buildSearchUrl(query: String, filters: FilterList): String {
        val selectedGenres = (filters.find { it is GenreFilter } as? GenreFilter)
            ?.state
            ?.filter { it.state }
            ?.joinToString(",") { it.name } ?: ""

        val statusFilter = filters.find { it is StatusFilter } as? StatusFilter
        val status = when (statusFilter?.state) {
            1 -> "En curso"
            2 -> "Completado"
            else -> ""
        }

        val adultFilter = filters.find { it is AdultFilter } as? AdultFilter
        val includeAdult = when (adultFilter?.state) {
            Filter.TriState.STATE_INCLUDE -> true
            Filter.TriState.STATE_EXCLUDE -> false
            else -> false
        }

        return "$baseUrl/api/series-locales/search-candidates?" +
            "q=$query" +
            "&tags=$selectedGenres" +
            "&estado=$status" +
            "&includeAdult=$includeAdult" +
            "&showSinPortada=false" +
            "&take=120"
    }

    // ----------------- REQUESTS -----------------
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/series-locales/popular", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/series-locales/novedades", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = buildSearchUrl(query, filters)
        return GET(url, headers)
    }

    // ----------------- PARSERS -----------------
    override fun popularMangaParse(response: okhttp3.Response): MangasPage = parseMangasResponse(response)

    override fun latestUpdatesParse(response: okhttp3.Response): MangasPage = parseMangasResponse(response)

    override fun searchMangaParse(response: okhttp3.Response): MangasPage = parseMangasResponse(response)

    private fun parseMangasResponse(response: okhttp3.Response, isSearch: Boolean = false): MangasPage {
        val body = response.body!!.string()
        val jsonArray = org.json.JSONArray(body)
        val mangasMap = linkedMapOf<String, SManga>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)

            if (obj.has("series")) {
                val seriesArray = obj.getJSONArray("series")
                for (j in 0 until seriesArray.length()) {
                    val item = seriesArray.getJSONObject(j)
                    val manga = parseManga(item)
                    mangasMap[manga.url] = manga
                }
            } else {
                val manga = parseManga(obj)
                mangasMap[manga.url] = manga
            }
        }

        return MangasPage(mangasMap.values.toList(), false)
    }

    private fun parseManga(item: JSONObject): SManga {
        val manga = SManga.create()
        manga.title = item.getString("titulo")
        manga.url = "/serie/local/${item.getInt("id")}"
        manga.thumbnail_url = item.optString("portadaUrl")
        return manga
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.split("/").last()
        return GET("$baseUrl/api/series-locales/$id", headers)
    }

    override fun mangaDetailsParse(response: okhttp3.Response): SManga {
        val manga = SManga.create()
        val json = JSONObject(response.body!!.string())
        manga.title = json.optString("titulo")
        manga.description = json.optString("descripcion")
        manga.author = json.optString("autor")
        manga.thumbnail_url = json.optString("portadaUrl")
        manga.genre = json.optString("generos")
        manga.status = when (json.optString("estado")) {
            "Completado" -> SManga.COMPLETED
            "En curso" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        return manga
    }

    // ----------------- CHAPTERS -----------------
    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.split("/").last()
        return GET("$baseUrl/api/serie/local/$id/capitulos", headers)
    }

    override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
        val body = response.body!!.string()
        val jsonArray = JSONObject("{\"data\":$body}").getJSONArray("data")
        val chapters = mutableListOf<SChapter>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            chapters.add(parseChapter(item))
        }
        return chapters
    }

    private fun parseChapter(item: JSONObject): SChapter {
        val chapter = SChapter.create()
        chapter.name = item.getString("titulo")
        chapter.url = "/reader/local/${item.getInt("idSerie")}/capitulos/${item.getInt("id")}/paginas"
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SS", Locale.ENGLISH)
        chapter.date_upload = sdf.parse(item.optString("fechaActualizacion"))?.time ?: 0
        return chapter
    }

    // ----------------- PAGES -----------------
    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: okhttp3.Response): List<Page> {
        val json = JSONObject(response.body!!.string())
        val pagesJson = json.getJSONArray("paginas")
        val pages = mutableListOf<Page>()
        for (i in 0 until pagesJson.length()) {
            pages.add(Page(i, "", pagesJson.getString(i)))
        }
        return pages
    }

    override fun imageUrlParse(response: okhttp3.Response): String = ""

    // ----------------- FILTERS -----------------
    override fun getFilterList() = FilterList(
        GenreFilter(),
        StatusFilter(),
        AdultFilter(),
    )
}

// ----------------- FILTER CLASSES -----------------
private class GenreFilter :
    Filter.Group<Filter.CheckBox>(
        "Géneros",
        genres.map { genre ->
            object : Filter.CheckBox(genre, false) {}
        },
    )

private class StatusFilter :
    Filter.Select<String>(
        "Estado",
        arrayOf("Todos", "En curso", "Completado"),
    )

private class AdultFilter : Filter.TriState("Mostrar contenido adulto")
