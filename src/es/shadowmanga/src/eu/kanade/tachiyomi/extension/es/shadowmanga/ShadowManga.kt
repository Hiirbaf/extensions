package eu.kanade.tachiyomi.extension.es.shadowmanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Cover
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ShadowManga : ConfigurableSource, HttpSource() {

    override val name = "ShadowManga"
    override val baseUrl = "https://shadowmanga.es"
    override val lang = "es"
    override val supportsLatest = true
    override val client: OkHttpClient = network.client

    // ----------------- BUSQUEDA -----------------
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val url = buildSearchUrl(query, filters)
        return client.newCall(GET(url))
            .asObservable()
            .map { response ->
                val jsonArray = JSONObject("{\"data\":${response.body!!.string()}}").getJSONArray("data")
                val mangas = mutableListOf<SManga>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    mangas.add(parseManga(item))
                }
                MangasPage(mangas, mangas.isNotEmpty())
            }
    }

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

    private fun parseManga(item: JSONObject): SManga {
        val manga = SManga.create()
        manga.title = item.getString("titulo")
        manga.url = "/series-locales/${item.getInt("id")}"
        manga.thumbnail_url = item.optString("portadaUrl")
        return manga
    }

    // ----------------- DETALLE -----------------
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val url = "$baseUrl${manga.url}"
        return client.newCall(GET(url))
            .asObservable()
            .map { response ->
                val json = JSONObject(response.body!!.string())
                manga.author = json.optString("autor")
                manga.genre = json.optJSONArray("generos")?.join(", ")
                manga.status = when (json.optString("estado")) {
                    "Completado" -> SManga.COMPLETED
                    "En curso" -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
                manga.description = ""
                manga
            }
    }

    // ----------------- CAPÍTULOS -----------------
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val url = "$baseUrl/api/series-locales/${manga.url.split("/").last()}/capitulos"
        return client.newCall(GET(url))
            .asObservable()
            .map { response ->
                val jsonArray = JSONObject("{\"data\":${response.body!!.string()}}").getJSONArray("data")
                val chapters = mutableListOf<SChapter>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    chapters.add(parseChapter(item))
                }
                chapters
            }
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
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val url = "$baseUrl${chapter.url}"
        return client.newCall(GET(url))
            .asObservable()
            .map { response ->
                val json = JSONObject(response.body!!.string())
                val pagesJson = json.getJSONArray("paginas")
                val pages = mutableListOf<Page>()
                for (i in 0 until pagesJson.length()) {
                    pages.add(Page(i, "", pagesJson.getString(i)))
                }
                pages
            }
    }

    // ----------------- FILTROS -----------------
    private val genres = listOf(
        "Acción", "Aventura", "Artes marciales", "Boys Love", "Ciencia Ficción",
        "Comedia", "Drama", "Ecchi", "Fantasía", "Gore", "Harem", "Horror",
        "Misterio", "Psicológico", "Recuentos de la vida", "Romance",
        "Seinen", "Shoujo", "Shoujo-ai", "Shounen", "Shounen Ai", "Superpoderes",
        "Suspense", "Thriller", "Vida escolar", "Yaoi", "Yuri", "Isekai",
        "Magia", "Sobrenatural", "Webtoon", "Webcomic", "Novela", "Manhwa", "Manhua"
    )

    class GenreFilter : Filter.Group<Filter.CheckBox>(
        "Géneros",
        genres.map { Filter.CheckBox(it, false) }
    )

    class StatusFilter : Filter.Select<String>(
        "Estado",
        arrayOf("Todos", "En curso", "Completado")
    )

    class AdultFilter : Filter.TriState("Mostrar contenido adulto")

    override fun getFilterList() = FilterList(
        GenreFilter(),
        StatusFilter(),
        AdultFilter()
    )
}
