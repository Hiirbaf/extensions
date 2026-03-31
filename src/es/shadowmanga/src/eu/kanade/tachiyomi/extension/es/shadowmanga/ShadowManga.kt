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
import org.json.JSONObject
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
        // Si no hay preferencias adicionales, se deja vacío
    }

    // ----------------- BUSQUEDA -----------------
    override suspend fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val url = buildSearchUrl(query, filters)
        val response = client.newCall(GET(url)).execute()
        val jsonArray = JSONObject("{\"data\":${response.body!!.string()}}").getJSONArray("data")
        val mangas = mutableListOf<SManga>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            mangas.add(parseManga(item))
        }
        return MangasPage(mangas, mangas.isNotEmpty())
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
    override suspend fun fetchMangaDetails(manga: SManga): SManga {
        val url = "$baseUrl${manga.url}"
        val response = client.newCall(GET(url)).execute()
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
    override suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val url = "$baseUrl/api/series-locales/${manga.url.split("/").last()}/capitulos"
        val response = client.newCall(GET(url)).execute()
        val jsonArray = JSONObject("{\"data\":${response.body!!.string()}}").getJSONArray("data")
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
    override suspend fun fetchPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl${chapter.url}"
        val response = client.newCall(GET(url)).execute()
        val json = JSONObject(response.body!!.string())
        val pagesJson = json.getJSONArray("paginas")
        val pages = mutableListOf<Page>()
        for (i in 0 until pagesJson.length()) {
            pages.add(Page(i, "", pagesJson.getString(i)))
        }
        return pages
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
            arrayOf("Todos", "En curso", "Completado"),
        )

    class AdultFilter : Filter.TriState("Mostrar contenido adulto")

    override fun getFilterList() = FilterList(
        GenreFilter(),
        StatusFilter(),
        AdultFilter(),
    )
}
