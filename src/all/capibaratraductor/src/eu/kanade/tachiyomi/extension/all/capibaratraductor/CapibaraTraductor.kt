package eu.kanade.tachiyomi.extension.all.capibaratraductor

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class CapibaraTraductor(
    override val name: String,
    private val orgSlug: String? = null,
) : HttpSource() {

    override val baseUrl = "https://capibaratraductor.com"
    override val lang = "es"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // ── Headers ──────────────────────────────────────────────────────────────

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .apply {
            orgSlug?.let {
                add("X-Organization", it)
                add("Referer", "$baseUrl/$it/")
            }
        }

    // ── Popular ──────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/manga-custom".toHttpUrl().newBuilder()
            .addQueryParameter("order", "popular")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("nsfw", "false")
            .apply { orgSlug?.let { addQueryParameter("organizationSlug", it) } }
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<MangaListResponse>()
        val mangas = result.data.items.map { it.toSManga() }
        val hasNextPage = result.data.items.size == PAGE_SIZE
        return MangasPage(mangas, hasNextPage)
    }

    // ── Latest ───────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/manga-custom".toHttpUrl().newBuilder()
            .addQueryParameter("order", "latest")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("nsfw", "false")
            .apply { orgSlug?.let { addQueryParameter("organizationSlug", it) } }
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ── Search ───────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/manga-custom".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("nsfw", "false")
            .apply { orgSlug?.let { addQueryParameter("organizationSlug", it) } }
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ── Manga details ─────────────────────────────────────────────────────────

    // url: /$orgSlug/manga/$mangaSlug::$mangaCustomId
    override fun mangaDetailsRequest(manga: SManga): Request {
        val (_, mangaCustomId) = manga.url.parseUrl()
        val url = "$baseUrl/api/manga-custom".toHttpUrl().newBuilder()
            .addQueryParameter("ids", mangaCustomId)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<MangaListResponse>()
        return result.data.items.first().toSManga()
    }

    // ── Chapter list ──────────────────────────────────────────────────────────

    // All chapters are embedded in the manga HTML page as Astro island props.
    override fun chapterListRequest(manga: SManga): Request {
        val (realOrgSlug, mangaSlug, _) = manga.url.parseUrlFull()
        return GET(
            "$baseUrl/$realOrgSlug/manga/$mangaSlug",
            headersBuilder().apply { set("X-Organization", realOrgSlug) }.build(),
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val doc = Jsoup.parse(html)

        val island = doc.select("astro-island[component-url*='MangaDetailPageContainer']").first()
            ?: throw Exception("No se encontró el contenedor del manga")

        val propsRaw = island.attr("props")
        val props = json.parseToJsonElement(propsRaw).jsonObject

        // URL: /{orgSlug}/manga/{mangaSlug} — extract both
        val urlSegments = response.request.url.pathSegments
        val realOrgSlug = urlSegments[0]
        val mangaSlug = urlSegments.last()

        // Astro encodes props as {"key": [typeTag, value]}
        // props["manga"] = [0, {mangaObj}], inside mangaObj["chapters"] = [1, [[0,{ch}],...]]
        val mangaRaw = props["manga"]?.jsonArray?.get(1)?.jsonObject

        // chapters may be at root or inside manga object
        val chaptersEncoded = props["chapters"]
            ?: mangaRaw?.get("chapters")
            ?: throw Exception("No se encontraron capítulos en los props")

        // chaptersEncoded = [1, [ [0,{ch1}], [0,{ch2}], ... ]]
        val chaptersArray = chaptersEncoded.jsonArray[1].jsonArray

        return chaptersArray.mapNotNull { el ->
            runCatching {
                // el = [0, {chapterObj}] where each field is also [typeTag, value]
                val ch = el.jsonArray[1].jsonObject
                val number = ch["number"]!!.jsonArray[1].jsonPrimitive.double
                val title = ch["title"]!!.jsonArray[1].jsonPrimitive.content
                val releasedAtRaw = ch["releasedAt"]?.jsonArray?.get(1)
                val releasedAt = if (releasedAtRaw == null || releasedAtRaw is JsonNull) {
                    null
                } else {
                    releasedAtRaw.jsonPrimitive.content
                }

                SChapter.create().apply {
                    url = "/$realOrgSlug/manga/$mangaSlug/chapters/$number"
                    name = title.ifBlank { "Capítulo $number" }
                    chapter_number = number.toFloat()
                    date_upload = runCatching {
                        releasedAt?.let { dateFormat.parse(it)?.time } ?: 0L
                    }.getOrDefault(0L)
                }
            }.getOrNull()
        }.sortedByDescending { it.chapter_number }
    }

    /**
     * Decode Astro's serialized prop format: [typeTag, value]
     * 0 = primitive or plain object
     * 1 = array
     */
    private fun decodeAstro(element: JsonElement): JsonElement {
        if (element !is JsonArray || element.size < 2) return element
        return when (element[0].jsonPrimitive.int) {
            0 -> {
                val v = element[1]
                when {
                    v is JsonPrimitive || v is JsonNull -> v
                    else -> {
                        // Object — recursively decode each value
                        val obj = v.jsonObject
                        val sb = StringBuilder("{")
                        obj.entries.forEachIndexed { i, (k, child) ->
                            if (i > 0) sb.append(',')
                            sb.append('"').append(k.replace("\"", "\\\"")).append('"')
                            sb.append(':').append(decodeAstro(child))
                        }
                        sb.append('}')
                        json.parseToJsonElement(sb.toString())
                    }
                }
            }
            1 -> {
                val arr = element[1].jsonArray
                val sb = StringBuilder("[")
                arr.forEachIndexed { i, child ->
                    if (i > 0) sb.append(',')
                    sb.append(decodeAstro(child))
                }
                sb.append(']')
                json.parseToJsonElement(sb.toString())
            }
            else -> element[1]
        }
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    // chapter.url = /$orgSlug/manga/$mangaSlug/chapters/$chapterNumber
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.trimStart('/').split('/')
        // [orgSlug, manga, mangaSlug, chapters, chapterNumber]
        val realOrgSlug = parts[0]
        val mangaSlug = parts[2]
        val chapterNumber = parts[4]
        return GET(
            "$baseUrl/api/manga-custom/$mangaSlug/chapter/$chapterNumber/pages",
            headersBuilder()
                .set("X-Organization", realOrgSlug)
                .set("Referer", "$baseUrl/$realOrgSlug/manga/$mangaSlug/chapters/$chapterNumber?page=1")
                .build(),
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<PageListResponse>()
        return result.data.mapIndexed { index, pageData ->
            Page(index, imageUrl = pageData.imageUrl)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ── Filters ───────────────────────────────────────────────────────────────

    override fun getFilterList() = FilterList()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun MangaCustomItem.toSManga() = SManga.create().apply {
        // Always store the real org slug from the API, so the hub source works correctly
        url = "/${organization.slug}/manga/${manga.slug}::$id"
        title = this@toSManga.title
        thumbnail_url = imageUrl
        description = this@toSManga.description
        genre = genres.joinToString(", ") { it.name }
        status = when (this@toSManga.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    // url = /{orgSlug}/manga/{mangaSlug}::{id}
    private fun String.parseUrl(): Pair<String, String> {
        val last = trimStart('/').split('/').last()
        val (slug, id) = last.split("::")
        return slug to id
    }

    private fun String.parseUrlFull(): Triple<String, String, String> {
        val parts = trimStart('/').split('/')
        // parts: [orgSlug, manga, mangaSlug::id]
        val realOrgSlug = parts[0]
        val (mangaSlug, id) = parts[2].split("::")
        return Triple(realOrgSlug, mangaSlug, id)
    }

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    companion object {
        private const val PAGE_SIZE = 20
    }
}

// ── Serialization models ──────────────────────────────────────────────────────

@Serializable
data class MangaListResponse(
    val status: Boolean,
    val data: MangaListData,
)

@Serializable
data class MangaListData(
    val items: List<MangaCustomItem>,
)

@Serializable
data class MangaCustomItem(
    val id: Int,
    val title: String,
    val description: String? = null,
    @SerialName("imageUrl") val imageUrl: String? = null,
    val status: String? = null,
    val manga: MangaBase,
    val organization: OrganizationInfo,
    val genres: List<GenreItem> = emptyList(),
)

@Serializable
data class MangaBase(
    val id: Int,
    val slug: String,
    val title: String,
)

@Serializable
data class OrganizationInfo(
    val id: Int,
    val slug: String,
    val name: String,
)

@Serializable
data class GenreItem(
    val id: Int,
    val slug: String,
    val name: String,
)

@Serializable
data class PageListResponse(
    val status: Boolean,
    val data: List<PageItem>,
)

@Serializable
data class PageItem(
    @SerialName("imageUrl") val imageUrl: String,
)
