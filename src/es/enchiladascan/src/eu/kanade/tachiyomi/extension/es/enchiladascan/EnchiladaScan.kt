package eu.kanade.tachiyomi.extension.es.enchiladascan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class EnchiladaScan : HttpSource() {

    override val name = "EnchiladaScan"
    override val baseUrl = "https://enchiladascan.github.io/enchiladaweb"
    override val lang = "es"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------ Popular / Latest ------------------

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/catalogo.json", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val items = root["items"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val mangas = items.map { it.jsonObject.toSManga() }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ------------------ Search ------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(page)

    override fun searchMangaParse(response: Response): MangasPage {
        val all = popularMangaParse(response).mangas
        // query is not accessible here, workaround: filter is done in fetchSearchManga override
        return MangasPage(all, false)
    }

    // fetchSearchManga override to apply client-side filtering
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val response = client.newCall(popularMangaRequest(page)).execute()
        val all = popularMangaParse(response).mangas
        val filtered = all.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.description.orEmpty().contains(query, ignoreCase = true)
        }
        return MangasPage(filtered, false)
    }

    // ------------------ Manga details ------------------

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        // Details are already populated from catalog; return as-is
        return SManga.create().apply {
            url = response.request.url.toString().removePrefix(baseUrl)
        }
    }

    // ------------------ Chapter list ------------------

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val chapters = mutableListOf<SChapter>()

        fun parseChapterElements(selector: String) {
            doc.select(selector).forEach { el ->
                chapters += SChapter.create().apply {
                    name = el.selectFirst(".cap-title")?.text() ?: el.text()
                    chapter_number = el.selectFirst(".cap-number")
                        ?.text()
                        ?.removePrefix("Cap. ")
                        ?.toFloatOrNull()
                        ?: 0f
                    url = el.attr("href").removePrefix(baseUrl)
                }
            }
        }

        parseChapterElements("#chaptersList li a")
        parseChapterElements("#extrasList li a")

        // Newest first (index 0 = latest), as Tachiyomi expects
        return chapters.sortedByDescending { it.chapter_number }
    }

    // ------------------ Page list ------------------

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.trim('/').split('/')
        require(parts.size >= 2) { "URL de capítulo inválida: ${chapter.url}" }
        val (mangaSlug, capSlug) = parts
        return GET("$baseUrl/assets/mangas/$mangaSlug/$capSlug/images.json", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val array = json.parseToJsonElement(response.body.string()).jsonArray
        return array.mapIndexed { index, el ->
            Page(index, "", normalizeGoogleDriveUrl(el.jsonPrimitive.content))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ------------------ Helpers ------------------

    private fun JsonObject.toSManga() = SManga.create().apply {
        title = this@toSManga["title"]?.jsonPrimitive?.content.orEmpty()
        url = this@toSManga["post_url"]?.jsonPrimitive?.content.orEmpty()
        val portada = this@toSManga["portada"]?.jsonPrimitive?.content.orEmpty()
        thumbnail_url = if (portada.startsWith("http")) portada else baseUrl + portada
        val tags = this@toSManga["tags"]?.jsonArray
            ?.joinToString(", ") { it.jsonPrimitive.content }
            .orEmpty()
        description = buildString {
            append("Sección: ", this@toSManga["seccion"]?.jsonPrimitive?.content.orEmpty(), "\n")
            append("Último: ", this@toSManga["latest"]?.jsonPrimitive?.content.orEmpty(), "\n")
            append("Tags: ", tags)
        }
    }

    private fun normalizeGoogleDriveUrl(url: String): String {
        val idRegex = Regex("""https://drive\.google\.com/uc\?export=(?:view|download)&id=([^&]+).*""")
        val match = idRegex.matchEntire(url)
        return if (match != null) {
            "https://drive.usercontent.google.com/uc?id=${match.groupValues[1]}&export=download"
        } else {
            url.replace(
                Regex("""(drive\.usercontent\.google\.com/uc\?[^#]*?)export=view"""),
                "$1export=download",
            )
        }
    }
}
