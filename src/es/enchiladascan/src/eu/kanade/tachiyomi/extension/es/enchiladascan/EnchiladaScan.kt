package eu.kanade.tachiyomi.extension.es.enchiladascan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

class EnchiladaScan : HttpSource() {

    override val name = "EnchiladaScan"
    override val baseUrl = "https://enchiladascan.github.io/enchiladaweb"
    override val lang = "es"
    override val supportsLatest = true
    override val client: OkHttpClient = OkHttpClient()

    // ------------------ Listado de mangas ------------------
    override fun fetchPopularManga(page: Int): MangasPage {
        val mangas = mutableListOf<SManga>()
        val url = "$baseUrl/catalogo.json"
        val json = client.newCall(GET(url)).execute().body?.string()
            ?: throw IOException("No se pudo descargar catalogo.json")
        val data = JSONObject(json)
        val items = data.optJSONArray("items") ?: return MangasPage(emptyList(), false)

        for (i in 0 until items.length()) {
            val m = items.getJSONObject(i)
            val manga = SManga.create()
            manga.title = m.optString("title")
            manga.url = m.optString("post_url")
            manga.thumbnail_url = m.optString("portada")?.let { baseUrl + it }
            manga.description = "Sección: ${m.optString("seccion")}\nÚltimo: ${m.optString("latest")}\nTags: ${(m.optJSONArray("tags")?.join(", ") ?: "")}"
            mangas.add(manga)
        }

        return MangasPage(mangas, false)
    }

    override fun fetchLatestUpdates(page: Int): MangasPage = fetchPopularManga(page)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val allMangas = fetchPopularManga(1).mangas
        val filtered = allMangas.filter {
            it.title.contains(query, ignoreCase = true) || (it.description?.contains(query, ignoreCase = true) ?: false)
        }
        return MangasPage(filtered, false)
    }

    // ------------------ Capítulos ------------------
    override fun fetchMangaDetails(manga: SManga): SManga = manga

    override fun fetchChapterList(manga: SManga): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        try {
            val doc: Document = Jsoup.connect(baseUrl + manga.url).get()

            fun parseChapterElements(elements: List<org.jsoup.nodes.Element>) {
                for (el in elements) {
                    val chapter = SChapter.create()
                    chapter.name = el.selectFirst(".cap-title")?.text() ?: el.text()
                    val numText = el.selectFirst(".cap-number")?.text()?.replace("Cap. ", "") ?: "0"
                    chapter.chapter_number = numText.toFloatOrNull() ?: 0F
                    chapter.url = el.attr("href").removePrefix(baseUrl)
                    chapters.add(chapter)
                }
            }

            // Capítulos principales
            parseChapterElements(doc.select("#chaptersList li a"))
            // Capítulos extras
            parseChapterElements(doc.select("#extrasList li a"))

            chapters.reverse() // del más antiguo al más reciente
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return chapters
    }

    // ------------------ Páginas ------------------
    override fun fetchPageList(chapter: SChapter): List<Page> {
        val pages = mutableListOf<Page>()
        try {
            val parts = chapter.url.trim('/').split('/')
            if (parts.size < 2) return pages
            val mangaSlug = parts[0]
            val capSlug = parts[1]
            val jsonUrl = "$baseUrl/assets/mangas/$mangaSlug/$capSlug/images.json"

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

    // ------------------ Función de normalización de Google Drive ------------------
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
