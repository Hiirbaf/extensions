package eu.kanade.tachiyomi.extension.es.nexusscanlation

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class NexusScanlation : HttpSource() {

    override val name = "Nexus Scanlation"
    override val baseUrl = "https://nexusscanlation.com"
    override val lang = "es"
    override val supportsLatest = true

    private val apiUrl = "https://api.nexusscanlation.com/api/v1"
    private val cdnUrl = "https://cdn.nexusscanlation.com"

    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

    // ========================= Headers =========================

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ========================= Popular =========================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("orden", "popular")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", ITEMS_PER_PAGE.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseCatalogResponse(response)

    // ========================= Latest ==========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("orden", "nuevo")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", ITEMS_PER_PAGE.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalogResponse(response)

    // ========================= Search ==========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$apiUrl/catalog/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            return GET(url, headers)
        }

        val urlBuilder = "$apiUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", ITEMS_PER_PAGE.toString())

        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> urlBuilder.addQueryParameter("orden", filter.selected())
                is StatusFilter -> {
                    val value = filter.selected()
                    if (value.isNotEmpty()) urlBuilder.addQueryParameter("estado", value)
                }
                is TypeFilter -> {
                    val value = filter.selected()
                    if (value.isNotEmpty()) urlBuilder.addQueryParameter("tipo", value)
                }
                else -> {}
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseCatalogResponse(response)

    // ===================== Catalog parser ======================

    private fun parseCatalogResponse(response: Response): MangasPage {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val data = root["data"]!!.jsonArray
        val meta = root["meta"]!!.jsonObject
        val hasNext = meta["has_next"]!!.jsonPrimitive.boolean

        val mangas = data.map { it.jsonObject.toSManga() }
        return MangasPage(mangas, hasNext)
    }

    private fun JsonObject.toSManga(): SManga = SManga.create().apply {
        val slug = this@toSManga["slug"]!!.jsonPrimitive.content
        url = "/series/$slug"
        title = this@toSManga["titulo"]!!.jsonPrimitive.content
        thumbnail_url = this@toSManga["portada_url"]?.jsonPrimitive?.contentOrNull
        description = this@toSManga["descripcion"]?.jsonPrimitive?.contentOrNull
        status = when (this@toSManga["estado"]?.jsonPrimitive?.contentOrNull) {
            "en_emision" -> SManga.ONGOING
            "finalizado" -> SManga.COMPLETED
            "pausado" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = buildList {
            this@toSManga["tipo"]?.jsonPrimitive?.contentOrNull
                ?.replaceFirstChar { it.uppercase() }
                ?.let { add(it) }
        }.joinToString(", ")
    }

    // ====================== Manga detail =======================

    override fun mangaDetailsRequest(manga: SManga): Request {
        // url is like /series/{slug}
        val slug = manga.url.removePrefix("/series/")
        return GET("$apiUrl/series/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        // Real API structure: root["serie"] contains the manga info
        val serie = root["serie"]?.jsonObject ?: root

        return SManga.create().apply {
            val slug = serie["slug"]!!.jsonPrimitive.content
            url = "/series/$slug"
            title = serie["titulo"]!!.jsonPrimitive.content
            thumbnail_url = serie["portada_url"]?.jsonPrimitive?.contentOrNull

            // Build description: main description + alt titles
            val desc = serie["descripcion"]?.jsonPrimitive?.contentOrNull
            val altTitles = serie["titulos_alt"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
            description = buildString {
                if (!desc.isNullOrBlank()) append(desc)
                if (!altTitles.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Títulos alternativos: $altTitles")
                }
            }.ifBlank { null }

            // autores can be null (JsonNull) or an array
            val autoresList = serie["autores"]?.takeIf { it !is JsonNull }?.let {
                runCatching {
                    it.jsonArray.mapNotNull { a ->
                        when {
                            a is JsonObject -> a["nombre"]?.jsonPrimitive?.contentOrNull
                            else -> a.jsonPrimitive.contentOrNull
                        }
                    }
                }.getOrNull()
            }
            author = autoresList?.joinToString(", ")
            artist = author

            status = when (serie["estado"]?.jsonPrimitive?.contentOrNull) {
                "en_emision" -> SManga.ONGOING
                "finalizado" -> SManga.COMPLETED
                "pausado" -> SManga.ON_HIATUS
                "cancelado" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            // Genres: tipo + generos array (objects with "nombre" or plain strings)
            val genres = mutableListOf<String>()
            serie["tipo"]?.jsonPrimitive?.contentOrNull
                ?.replaceFirstChar { it.uppercase() }
                ?.let { genres.add(it) }
            serie["generos"]?.jsonArray?.forEach { g ->
                val name = when {
                    g is JsonObject -> g["nombre"]?.jsonPrimitive?.contentOrNull
                    else -> g.jsonPrimitive.contentOrNull
                }
                if (!name.isNullOrBlank()) genres.add(name)
            }
            genre = genres.joinToString(", ").ifBlank { null }
        }
    }

    // ======================= Chapters ==========================

    // The series endpoint already returns "capitulos" — reuse mangaDetailsRequest
    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/series/")
        return GET("$apiUrl/series/$slug", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        // Real API: chapters are in root["capitulos"]
        val chapters = root["capitulos"]?.jsonArray ?: JsonArray(emptyList())
        val serieSlug = root["serie"]?.jsonObject
            ?.get("slug")?.jsonPrimitive?.contentOrNull ?: ""

        return chapters.map { it.jsonObject.toSChapter(serieSlug) }
            .sortedByDescending { it.chapter_number }
    }

    private fun JsonObject.toSChapter(serieSlug: String): SChapter = SChapter.create().apply {
        val chapterSlug = this@toSChapter["slug"]!!.jsonPrimitive.content
        val numPrimitive = this@toSChapter["numero"]!!.jsonPrimitive
        val numStr = numPrimitive.contentOrNull ?: numPrimitive.content
        // Store serie slug + chapter slug so we can build the pages URL later
        url = "/series/$serieSlug/$chapterSlug"
        name = "Capítulo $numStr"
        chapter_number = numStr.toFloatOrNull() ?: -1f
        date_upload = this@toSChapter["published_at"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { dateFormat.parse(it.substringBefore("."))?.time }.getOrNull() }
            ?: 0L
        scanlator = "Nexus Scanlation"
    }

    // ========================= Pages ==========================

    override fun pageListRequest(chapter: SChapter): Request {
        // url is /series/{serieSlug}/{chapterSlug}
        val parts = chapter.url.removePrefix("/series/").split("/")
        val serieSlug = parts[0]
        val chapterSlug = parts.getOrElse(1) { "" }
        return GET("$apiUrl/series/$serieSlug/capitulos/$chapterSlug", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = json.parseToJsonElement(response.body.string())
            .jsonObject["data"]!!.jsonObject
        val paginas = data["paginas"]!!.jsonArray

        return paginas
            .sortedBy { it.jsonObject["orden"]!!.jsonPrimitive.content.toIntOrNull() ?: 0 }
            .mapIndexed { index, el ->
                Page(index, imageUrl = el.jsonObject["url"]!!.jsonPrimitive.content)
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ========================= Filters =========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Ignorado si hay texto de búsqueda"),
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    class OrderFilter :
        SelectFilter(
            "Ordenar por",
            listOf(
                Pair("Más nuevo", "nuevo"),
                Pair("Más popular", "popular"),
                Pair("Alfabético", "az"),
            ),
        )

    class StatusFilter :
        SelectFilter(
            "Estado",
            listOf(
                Pair("Todos", ""),
                Pair("En emisión", "en_emision"),
                Pair("Finalizado", "finalizado"),
                Pair("Pausado", "pausado"),
                Pair("Cancelado", "cancelado"),
            ),
        )

    class TypeFilter :
        SelectFilter(
            "Tipo",
            listOf(
                Pair("Todos", ""),
                Pair("Manhwa", "manhwa"),
                Pair("Manga", "manga"),
                Pair("Manhua", "manhua"),
            ),
        )

    open class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
        private val default: Int = 0,
    ) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), default) {
        fun selected(): String = options[state].second
    }

    // ========================= Companion =======================

    companion object {
        private const val ITEMS_PER_PAGE = 24
    }
}
