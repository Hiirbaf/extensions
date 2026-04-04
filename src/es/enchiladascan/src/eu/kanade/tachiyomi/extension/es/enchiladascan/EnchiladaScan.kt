package eu.kanade.tachiyomi.extension.es.enchiladascan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

class EnchiladaScan : HttpSource() {

    override val name: String = "EnchiladaScan"
    override val baseUrl: String = "https://enchiladascan.github.io/enchiladaweb"
    override val lang: String = "es"
    override val supportsLatest: Boolean = false
    override val client: OkHttpClient = OkHttpClient()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/catalogo.json", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    // ------------------ Popular ------------------
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/catalogo.json", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = mutableListOf<SManga>()
        val json = response.body?.string() ?: throw IOException("No se pudo descargar catalogo.json")
        val data = JSONArray(JSONObject(json).optJSONArray("items").toString())
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val manga = SManga.create()
            manga.title = m.optString("title")
            manga.url = m.optString("post_url")
            manga.thumbnail_url = m.optString("portada")?.let { baseUrl + it }
            manga.description = "" // la sinopsis la obtenemos desde mangaDetailsParse
            mangas.add(manga)
        }
        return MangasPage(mangas, false)
    }

    // ------------------ Search ------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/catalogo.json", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val allMangas = popularMangaParse(response).mangas
        val query = response.request.url.queryParameter("query") ?: ""
        val filtered = allMangas.filter {
            it.title.contains(query, ignoreCase = true)
        }
        return MangasPage(filtered, false)
    }

    // ------------------ Manga details ------------------
    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string() ?: "")
        // Obtenemos el SManga original si existe
        val urlPath = response.request.url.encodedPath.removePrefix("/").removeSuffix("/")
        val originalManga = SManga.create()
        originalManga.url = urlPath

        // Solo actualizamos los campos necesarios
        originalManga.title = doc.selectFirst("h1.manga-title")?.text() ?: originalManga.title
        originalManga.thumbnail_url = doc.selectFirst("div.manga-cover img")?.attr("src")?.let { baseUrl + it }

        val metaList = doc.select("ul.manga-meta-list li")
        originalManga.author = metaList.find { it.text().startsWith("Autor:") }?.ownText() ?: originalManga.author
        originalManga.artist = metaList.find { it.text().startsWith("Arte:") }?.ownText() ?: originalManga.artist
        originalManga.genre = metaList.find { it.text().startsWith("Géneros:") }?.ownText() ?: originalManga.genre

        val statusText = metaList.find { it.text().startsWith("Estado:") }?.ownText() ?: ""
        originalManga.status = when {
            statusText.contains("En publicación", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Finalizado", ignoreCase = true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        originalManga.description = doc.selectFirst("p.manga-sinopsis")?.text() ?: originalManga.description

        return originalManga
    }

    // ------------------ Chapter list ------------------
    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val doc = Jsoup.parse(response.body?.string() ?: "")
        val elements = doc.select("#chaptersList li a") + doc.select("#extrasList li a")
        elements.forEach {
            val chapter = SChapter.create()
            chapter.name = it.selectFirst(".cap-title")?.text() ?: it.text()
            val numText = it.selectFirst(".cap-number")?.text()?.replace("Cap. ", "") ?: "0"
            chapter.chapter_number = numText.toFloatOrNull() ?: 0F
            val href = it.attr("href")
            chapter.url = href.replace(Regex("^/enchiladaweb/"), "/")
            chapters.add(chapter)
        }
        chapters.reverse()
        return chapters
    }

    // ------------------ Page list ------------------
    override fun pageListParse(response: Response): List<Page> {
        val pages = mutableListOf<Page>()
        val chapterPath = response.request.url.encodedPath
        val pathParts = chapterPath.replace(Regex("^/enchiladaweb/"), "").trim('/').split('/')
        if (pathParts.size < 2) return pages
        val mangaSlug = pathParts[0]
        val capSlug = pathParts[1]
        val jsonUrl = "$baseUrl/assets/mangas/$mangaSlug/$capSlug/images.json"
        try {
            val json = client.newCall(GET(jsonUrl)).execute().body?.string() ?: return pages
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val url = normalizeGoogleDriveUrl(array.getString(i))
                pages.add(Page(i, "", url))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String = ""

    private fun normalizeGoogleDriveUrl(url: String): String {
        var u = url
        u = u.replace(
            Regex("^https://drive\\.google\\.com/uc\\?export=(?:view|download)&id=([^&]+).*"),
            "https://drive.usercontent.google.com/uc?id=$1&export=download",
        )
        u = u.replace(Regex("(drive\\.usercontent\\.google\\.com/uc\\?[^#]*?)export=view"), "$1export=download")
        return u
    }
}
