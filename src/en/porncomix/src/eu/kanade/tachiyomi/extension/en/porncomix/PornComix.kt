package eu.kanade.tachiyomi.extension.en.porncomix

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class PornComix : ParsedHttpSource() {

    override val name = "PornComix"

    override val baseUrl = "https://bestporncomix.com"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/multporn-net/", headers)
        } else {
            GET("$baseUrl/multporn-net/page/$page/", headers)
        }
    }

    override fun popularMangaSelector() = "div.post-listing article.post, div.content-area article"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val anchor = element.selectFirst("h2.post-title a, h3.post-title a, .entry-title a")!!
        manga.setUrlWithoutDomain(anchor.attr("href"))
        manga.title = anchor.text().trim()
        manga.thumbnail_url = element.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        return manga
    }

    override fun popularMangaNextPageSelector() = "a.next.page-numbers, div.nav-links a.next"

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/multporn-net/", headers)
        } else {
            GET("$baseUrl/multporn-net/page/$page/", headers)
        }
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            val url = "$baseUrl/".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .build()
            GET(url, headers)
        } else {
            popularMangaRequest(page)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaParse(response: okhttp3.Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map { searchMangaFromElement(it) }
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    // ======================== Details ========================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        manga.title = document.selectFirst("h1.post-title, h1.entry-title")?.text()?.trim() ?: ""

        manga.thumbnail_url = document.selectFirst(
            "div.post-inner img, div.entry-content img, article img"
        )?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        manga.description = document.selectFirst(
            "div.entry-content p, div.post-content p"
        )?.text()?.trim()

        // Tags / genres
        val tags = document.select("a[rel=tag], .post-tags a, .tags-links a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        if (tags.isNotEmpty()) {
            manga.genre = tags.joinToString(", ")
        }

        manga.status = SManga.COMPLETED

        return manga
    }

    // ======================== Chapters ========================

    override fun chapterListSelector() = "div.entry-content, article.post"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.name = "Chapter 1"
        chapter.setUrlWithoutDomain(
            element.selectFirst("link[rel=canonical]")?.attr("href")
                ?: element.ownerDocument()?.location() ?: ""
        )
        return chapter
    }

    override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
        val document = response.asJsoup()
        val chapter = SChapter.create()
        chapter.name = "Read"
        chapter.setUrlWithoutDomain(response.request.url.toString())
        return listOf(chapter)
    }

    // ======================== Pages ========================

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // Gallery / nggallery images
        val galleryImages = document.select(
            "div.ngg-gallery-thumbnail img, " +
                "div.ngg-galleryoverview img, " +
                "#ngg-image-\\d+ img, " +
                "div.entry-content img, " +
                "div.post-content img, " +
                "article img"
        )

        galleryImages.forEachIndexed { index, img ->
            val url = img.attr("data-src").ifBlank {
                img.attr("data-lazy-src").ifBlank {
                    img.attr("src")
                }
            }
            if (url.isNotBlank() && !url.contains("blank.gif") && !url.contains("placeholder")) {
                // Try to get full-size image instead of thumbnail
                val fullUrl = url
                    .replace("-150x150", "")
                    .replace(Regex("-\\d+x\\d+\\."), ".")
                pages.add(Page(index, "", fullUrl))
            }
        }

        // Also check for <a> links wrapping images (linked full-size)
        if (pages.isEmpty()) {
            document.select("div.entry-content a[href], div.post-content a[href]").forEachIndexed { index, a ->
                val href = a.attr("href")
                if (href.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)(\\?.*)?", RegexOption.IGNORE_CASE))) {
                    pages.add(Page(index, "", href))
                }
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String =
        document.selectFirst("div.entry-content img, article img")
            ?.attr("src") ?: ""

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Filters only work with Browse, not Search"),
    )
}
