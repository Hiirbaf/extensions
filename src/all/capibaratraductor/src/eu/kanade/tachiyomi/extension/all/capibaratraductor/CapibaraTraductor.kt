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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class CapibaraTraductor(
    override val name: String,
    private val orgSlug: String,
) : HttpSource() {

    override val baseUrl = "https://capibaratraductor.com"
    override val lang = "es"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // ── Headers ──────────────────────────────────────────────────────────────

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("X-Organization", orgSlug)
        .add("Referer", "$baseUrl/$orgSlug/")

    private fun pageHeaders(mangaSlug: String, chapterNumber: String) = headersBuilder()
        .set("Referer", "$baseUrl/$orgSlug/manga/$mangaSlug/chapters/$chapterNumber?page=1")
        .build()

    // ── Popular ──────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/manga-custom".toHttpUrl().newBuilder()
            .addQueryParameter("order", "popular")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("nsfw", "false")
            .addQueryParameter("organizationSlug", orgSlug)
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
            .addQueryParameter("organizationSlug", orgSlug)
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
            .addQueryParameter("organizationSlug", orgSlug)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ── Manga details ─────────────────────────────────────────────────────────

    // url stored as: /orgSlug/manga/mangaSlug::mangaCustomId
    // e.g. /senshimanga/manga/blue-lock::96

    override fun mangaDetailsRequest(manga: SManga): Request {
        val (_, mangaCustomId) = manga.url.parseUrl()
        val url = "$baseUrl/api/manga-custom".toHttpUrl().newBuilder()
            .addQueryParameter("id", mangaCustomId)
            .addQueryParameter("organizationSlug", orgSlug)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<MangaListResponse>()
        return result.data.items.first().toSManga()
    }

    // ── Chapter list ──────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request {
        val (mangaSlug, mangaCustomId) = manga.url.parseUrl()
        val url = "$baseUrl/api/manga-custom/$mangaSlug/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "500")
            .addQueryParameter("organizationSlug", orgSlug)
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ChapterListResponse>()
        return result.data.items.map { it.toSChapter(response.request.url.pathSegments[3]) }
            .sortedByDescending { it.chapter_number }
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    // chapter url: /orgSlug/manga/mangaSlug/chapters/chapterNumber
    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url = /orgSlug/manga/mangaSlug/chapters/chapterNumber
        val parts = chapter.url.trimStart('/').split('/')
        // parts: [orgSlug, manga, mangaSlug, chapters, chapterNumber]
        val mangaSlug = parts[2]
        val chapterNumber = parts[4]
        return GET(
            "$baseUrl/api/manga-custom/$mangaSlug/chapter/$chapterNumber/pages",
            pageHeaders(mangaSlug, chapterNumber),
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
        // url encodes both slug and id separated by ::
        val slug = manga.slug
        url = "/$orgSlug/manga/$slug::$id"
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

    private fun ChapterItem.toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = "/$orgSlug/manga/$mangaSlug/chapters/$number"
        name = title.ifBlank { "Capítulo $number" }
        chapter_number = number.toFloat()
        date_upload = runCatching {
            dateFormat.parse(releasedAt)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun String.parseUrl(): Pair<String, String> {
        // url format: /orgSlug/manga/mangaSlug::mangaCustomId
        val path = trimStart('/').split('/').last() // "mangaSlug::mangaCustomId"
        val (slug, id) = path.split("::")
        return slug to id
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
    val chapters: List<ChapterItem> = emptyList(),
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
data class ChapterListResponse(
    val status: Boolean,
    val data: ChapterListData,
)

@Serializable
data class ChapterListData(
    val items: List<ChapterItem>,
)

@Serializable
data class ChapterItem(
    val id: Int,
    val number: Double,
    val title: String = "",
    val releasedAt: String = "",
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
