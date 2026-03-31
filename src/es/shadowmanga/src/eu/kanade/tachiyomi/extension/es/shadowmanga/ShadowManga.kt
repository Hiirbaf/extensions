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
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class ShadowManga :
    HttpSource(),
    ConfigurableSource {

    override val name = "ShadowManga"
    override val baseUrl = "https://shadowmanga.es"
    override val lang = "es"
    override val supportsLatest = true
    override val client: OkHttpClient = network.client

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // Por ahora vacío
    }

    // ----------------- REQUESTS -----------------
    override fun popularMangaRequest(page: Int): Request {
        // Puedes definir cómo obtener los populares, o usar búsqueda vacía
        return GET("$baseUrl/api/series-locales/search-candidates?take=120", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/series-locales/search-candidates?take=120", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = buildSearchUrl(query, filters)
        return GET(url, headers)
    }

    // ----------------- PARSERS -----------------
    override fun popularMangaParse(response: okhttp3.Response): MangasPage = parseMangasResponse(response)

    override fun latestUpdatesParse(response: okhttp3.Response): MangasPage = parseMangasResponse(response)

    override fun searchMangaParse(response: okhttp3.Response): MangasPage = parseMangasResponse(response)

    private fun parseMangasResponse(response: okhttp3.Response): MangasPage {
        val body = response.body!!.string()
        val jsonArray = JSONObject("{\"data\":$body}").getJSONArray("data")
        val mangas = mutableListOf<SManga>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            mangas.add(parseManga(item))
        }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun parseManga(item: JSONObject): SManga {
        val manga = SManga.create()
        manga.title = item.getString("titulo")
        manga.url = "/series-locales/${item.getInt("id")}"
        manga.thumbnail_url = item.optString("portadaUrl")
        return manga
    }

    override fun mangaDetailsParse(response: okhttp3.Response): SManga {
        val manga = SManga.create()
        val json = JSONObject(response.body!!.string())
        manga.author = json.optString("autor")
        manga.genre = json.optJSONArray("generos")?.join(", ")
        manga.status = when (json.optString("estado")) {
            "Completado" -> SManga.COMPLETED
            "En curso" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        manga.description = ""
        return manga
    }

    // ----------------- CAPÍTULOS -----------------
    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.split("/").last()
        return GET("$baseUrl/api/series-locales/$id/capitulos", headers)
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
        chapter.url = "/series-locales/${item.getInt("idSerie")}/capitulos/${item.getInt("id")}/paginas"
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SS", Locale.ENGLISH)
        chapter.date_upload = sdf.parse(item.optString("fechaActualizacion"))?.time ?: 0
        return chapter
    }

    // ----------------- PÁGINAS -----------------
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

    override fun imageUrlParse(response: okhttp3.Response): String {
        // No se usa, las URLs ya vienen en pageListParse
        return ""
    }

    // ----------------- FILTROS -----------------
    private val genres = listOf(
        "Acción", "Aventura", "Artes marciales", "Boys Love", "Ciencia Ficción",
        "Comedia", "Drama", "Ecchi", "Fantasía", "Gore", "Harem", "Horror",
        "Misterio", "Psicológico", "Recuentos de la vida", "Romance",
        "Seinen", "Shoujo", "Shoujo-ai", "Shounen", "Shounen Ai", "Superpoderes",
        "Suspense", "Thriller", "Vida escolar", "Yaoi", "Yuri", "Isekai",
        "Magia", "Sobrenatural", "Webtoon", "Webcomic", "Novela", "Manhwa", "Manhua",
    )

    class GenreFilter :
        Filter.Group<Filter.CheckBox>(
            "Géneros",
            genres.map { Filter.CheckBox(it, false) },
        )

    class StatusFilter :
        Filter.Select<String>(
            "Estado",
            arrayOf("Todos", "En curso", "Completado")
        )

    class AdultFilter : Filter.TriState("Mostrar contenido adulto")

    override fun getFilterList() = FilterList(
        GenreFilter(),
        StatusFilter(),
        AdultFilter(),
    )
}
