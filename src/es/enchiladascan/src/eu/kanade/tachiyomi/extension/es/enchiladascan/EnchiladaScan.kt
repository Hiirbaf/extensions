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

    private var cachedCatalog: JSONArray? = null

    // ------------------ Popular ------------------
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/catalogo.json", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = mutableListOf<SManga>()
        val json = response.body?.string() ?: throw IOException("No se pudo descargar catalogo.json")
        val data = JSONObject(json)
        cachedCatalog = data.optJSONArray("items")
        if (cachedCatalog == null) return MangasPage(emptyList(), false)

        for (i in 0 until cachedCatalog!!.length()) {
            val m = cachedCatalog!!.getJSONObject(i)
            val manga = SManga.create()
            manga.title = m.optString("title")
            manga.url = m.optString("post_url")
            manga.thumbnail_url = m.optString("portada")?.let { baseUrl + it }
            manga.description = buildString {
                append("Autor: ${m.optString("autor")}\n")
                append("Artista: ${m.optString("artista")}\n")
                append("Géneros: ${(m.optJSONArray("generos")?.join(", ") ?: "")}\n")
                append("Estado: ${m.optString("estado")}\n")
                append("Año: ${m.optInt("anio")}\n")
                append("Editorial: ${m.optString("editorial")}\n")
                append("Sección: ${m.optString("seccion")}\n")
                append("Último capítulo: ${m.optString("latest")}\n")
                append("Sinopsis: ${m.optString("sinopsis")}")
            }
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
            it.title.contains(query, ignoreCase = true) ||
                (it.description?.contains(query, ignoreCase = true) ?: false)
        }
        return MangasPage(filtered, false)
    }

    // ------------------ Manga details ------------------
    override fun mangaDetailsParse(response: Response): SManga {
        val pathParts = response.request.url.encodedPath.trim('/').split('/')
        val mangaSlug = pathParts.lastOrNull() ?: return SManga.create()
        cachedCatalog?.let { catalog ->
            for (i in 0 until catalog.length()) {
                val m = catalog.getJSONObject(i)
                if (m.optString("post_url").contains(mangaSlug)) {
                    val manga = SManga.create()
                    manga.title = m.optString("title")
                    manga.description = buildString {
                        append("Autor: ${m.optString("autor")}\n")
                        append("Artista: ${m.optString("artista")}\n")
                        append("Géneros: ${(m.optJSONArray("generos")?.join(", ") ?: "")}\n")
                        append("Estado: ${m.optString("estado")}\n")
                        append("Año: ${m.optInt("anio")}\n")
                        append("Editorial: ${m.optString("editorial")}\n")
                        append("Sección: ${m.optString("seccion")}\n")
                        append("Último capítulo: ${m.optString("latest")}\n")
                        append("Sinopsis: ${m.optString("sinopsis")}")
                    }
                    manga.thumbnail_url = m.optString("portada")?.let { baseUrl + it }
                    return manga
                }
            }
        }
        return SManga.create()
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
