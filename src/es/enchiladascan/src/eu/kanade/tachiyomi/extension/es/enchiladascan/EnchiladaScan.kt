package eu.kanade.tachiyomi.extension.es.enchiladascan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class EnchiladaScan : HttpSource() {

    override val name: String = "EnchiladaScan"
    override val baseUrl: String = "https://enchiladascan.github.io/enchiladaweb"
    override val lang: String = "es"
    override val supportsLatest: Boolean = false

    // FIX #3: Usa kotlinx.serialization en vez de org.json
    private val json: Json by injectLazy()

    // FIX #1: Variable de instancia para guardar el query de búsqueda
    private var searchQuery = ""

    // FIX #5: No se sobreescribe client; se usa el heredado de HttpSource

    // ------------------ Popular ------------------

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/catalogo.json", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val catalogo = json.decodeFromString<Catalogo>(response.body!!.string())
        val mangas = catalogo.items.map { item ->
            SManga.create().apply {
                title = item.title
                url = item.postUrl
                // FIX #2: Portada extraída directamente del catálogo, sin cache
                thumbnail_url = if (item.portada.isNotBlank()) baseUrl + item.portada else null
            }
        }
        return MangasPage(mangas, false)
    }

    // ------------------ Search ------------------

    // FIX #1: El query se guarda en searchQuery para usarlo en searchMangaParse
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        searchQuery = query
        return GET("$baseUrl/catalogo.json", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val allMangas = popularMangaParse(response).mangas
        val filtered = if (searchQuery.isBlank()) allMangas
                       else allMangas.filter { it.title.contains(searchQuery, ignoreCase = true) }
        return MangasPage(filtered, false)
    }

    // ------------------ Manga details ------------------

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body!!.string())
        val urlPath = response.request.url.encodedPath
            .removePrefix("/enchiladaweb")
            .removePrefix("/")
            .removeSuffix("/")

        return SManga.create().apply {
            url = urlPath

            // FIX #2: Portada extraída del HTML, sin cache
            thumbnail_url = doc.selectFirst("img.manga-cover")?.attr("abs:src")

            title = doc.selectFirst("h1.manga-title")?.text() ?: ""

            val metaList = doc.select("ul.manga-meta-list li")
            author = metaList.find { it.text().startsWith("Autor:") }?.ownText() ?: ""
            artist = metaList.find { it.text().startsWith("Arte:") }?.ownText() ?: ""
            genre = metaList.find { it.text().startsWith("Géneros:") }?.ownText() ?: ""

            val statusText = metaList.find { it.text().startsWith("Estado:") }?.ownText() ?: ""
            status = when {
                statusText.contains("En publicación", ignoreCase = true) -> SManga.ONGOING
                statusText.contains("Finalizado", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            description = doc.selectFirst("p.manga-sinopsis")?.text() ?: ""
        }
    }

    // ------------------ Chapter list ------------------

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body!!.string())
        val elements = doc.select("#chaptersList li a") + doc.select("#extrasList li a")

        return elements.map { el ->
            SChapter.create().apply {
                name = el.selectFirst(".cap-title")?.text() ?: el.text()
                val numText = el.selectFirst(".cap-number")?.text()?.replace("Cap. ", "") ?: "0"
                chapter_number = numText.toFloatOrNull() ?: 0F
                url = el.attr("href").replace(Regex("^/enchiladaweb/"), "/")
            }
        }.reversed()
    }

    // ------------------ Page list ------------------

    // FIX #4: Se usa pageListRequest para que HttpSource haga la llamada de red correctamente
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.trim('/').split('/')
        // Espera url del tipo: /manga-slug/cap-slug
        val mangaSlug = parts.getOrNull(parts.size - 2) ?: error("URL de capítulo inválida: ${chapter.url}")
        val capSlug = parts.lastOrNull() ?: error("URL de capítulo inválida: ${chapter.url}")
        return GET("$baseUrl/assets/mangas/$mangaSlug/$capSlug/images.json", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val array = json.parseToJsonElement(response.body!!.string()).jsonArray
        return array.mapIndexed { i, el ->
            Page(i, "", normalizeGoogleDriveUrl(el.jsonPrimitive.content))
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    // ------------------ Helpers ------------------

    private fun normalizeGoogleDriveUrl(url: String): String {
        return url
            .replace(
                Regex("^https://drive\\.google\\.com/uc\\?export=(?:view|download)&id=([^&]+).*"),
                "https://drive.usercontent.google.com/uc?id=$1&export=download",
            )
            .replace(
                Regex("(drive\\.usercontent\\.google\\.com/uc\\?[^#]*?)export=view"),
                "$1export=download",
            )
    }

    // FIX #3: Data classes para kotlinx.serialization
    @Serializable
    data class Catalogo(
        val items: List<CatalogoItem> = emptyList(),
    )

    @Serializable
    data class CatalogoItem(
        val title: String = "",
        @SerialName("post_url") val postUrl: String = "",
        val portada: String = "",
    )
}
